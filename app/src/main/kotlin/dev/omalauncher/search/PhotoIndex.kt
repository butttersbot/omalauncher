package dev.omalauncher.search

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Photos, videos and GIFs found by what is in them (Boss, 2026-10-20: "describe an image that I know I have and we
 * would find it"). EmbeddingGemma 2 reads each one once, on the Mac Pro ([PhotoMac], tools/embed-mac): the phone
 * keeps what it says of each, 256 small numbers and any text in it, in files/search/photos.db, about 300 bytes a
 * photo. A search's words become numbers the same way (on the Mac, or by the phone's own copy of the model's text
 * part when the Mac is away) and are compared with every photo's on the phone, in a few milliseconds.
 */
object PhotoIndex {
    const val DIM = 256

    fun file(context: Context) = File(context.filesDir, "search/photos.db")

    fun open(
        context: Context,
        writable: Boolean,
    ): SQLiteDatabase? {
        val f = file(context)
        if (!writable && !f.exists()) return null
        f.parentFile?.mkdirs()
        return runCatching {
            val db = SQLiteDatabase.openDatabase(f.path, null, if (writable) SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else SQLiteDatabase.OPEN_READONLY)
            if (writable && db.version < 1) {
                db.execSQL("CREATE TABLE IF NOT EXISTS photos(id INTEGER PRIMARY KEY, uri TEXT UNIQUE NOT NULL, kind TEXT, mime TEXT, name TEXT, folder TEXT, taken INTEGER, vec BLOB)")
                // Any text in a photo (a sign, a screenshot), and its name and folder, for searching by words.
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS ptext USING fts4(words, tokenize=unicode61 \"remove_diacritics=1\", prefix=\"2,3\")")
                db.version = 1
            }
            if (writable && db.version < 2) {
                // Which reading of the Mac's each photo has ([PhotoMac.reads]), and a video's other frames' vectors.
                db.execSQL("ALTER TABLE photos ADD COLUMN reads INTEGER DEFAULT 0")
                db.execSQL("UPDATE photos SET reads = 1 WHERE vec IS NOT NULL AND length(vec) > 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS frames(photo INTEGER NOT NULL, vec BLOB NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS frames_photo ON frames(photo)")
                db.version = 2
            }
            db
        }.getOrNull()
    }

    /** One photo found: where it is, what kind, when, and how near it is to what was asked (higher is nearer). */
    data class Hit(
        val uri: String,
        val kind: String,
        val mime: String,
        val name: String,
        val taken: Long,
        val score: Float,
    )

    /** Every photo's numbers at once, for comparing a search with all of them; read while the search card is open. */
    class Vectors(
        val ids: LongArray,
        val data: ByteArray,
    ) {
        val size get() = ids.size
    }

    fun vectors(db: SQLiteDatabase): Vectors {
        val ids = ArrayList<Long>()
        val out = ByteArrayOutputStream()
        // A video's other frames come in under its id too: it is as near as its nearest frame.
        for (sql in listOf("SELECT id, vec FROM photos WHERE vec IS NOT NULL", "SELECT photo, vec FROM frames")) {
            runCatching {
                db.rawQuery(sql, null).use { c ->
                    while (c.moveToNext()) {
                        val v = c.getBlob(1) ?: continue
                        if (v.size != DIM) continue
                        ids += c.getLong(0)
                        out.write(v)
                    }
                }
            }
        }
        return Vectors(ids.toLongArray(), out.toByteArray())
    }

    /** A vector as the index keeps it: each number of the unit vector times 127, as a byte. */
    fun quantize(v: FloatArray): ByteArray = ByteArray(DIM) { i -> (v.getOrElse(i) { 0f } * 127f).toInt().coerceIn(-127, 127).toByte() }

    /** The [k] photos nearest [query] (a unit vector), nearest first, with their closeness from -1 to 1. */
    fun nearest(
        vectors: Vectors,
        query: FloatArray,
        k: Int,
    ): List<Pair<Long, Float>> = nearestRows(vectors, query, k * FRAMES).distinctBy { it.first }.take(k)

    /** A video has up to this many vectors, one a frame. */
    const val FRAMES = 3

    private fun nearestRows(
        vectors: Vectors,
        query: FloatArray,
        k: Int,
    ): List<Pair<Long, Float>> {
        val q = FloatArray(DIM) { query.getOrElse(it) { 0f } }
        val best = java.util.PriorityQueue<Pair<Long, Float>>(k + 1, compareBy { it.second })
        val data = vectors.data
        for (n in 0 until vectors.size) {
            var dot = 0f
            val at = n * DIM
            for (i in 0 until DIM) dot += q[i] * data[at + i]
            val score = dot / 127f
            if (best.size < k) {
                best.add(vectors.ids[n] to score)
            } else if (score > best.peek()!!.second) {
                best.poll()
                best.add(vectors.ids[n] to score)
            }
        }
        return best.sortedByDescending { it.second }
    }

    fun hits(
        db: SQLiteDatabase,
        scored: List<Pair<Long, Float>>,
    ): List<Hit> {
        if (scored.isEmpty()) return emptyList()
        val byId = scored.toMap()
        return db.rawQuery("SELECT id, uri, kind, mime, name, taken FROM photos WHERE id IN (${scored.joinToString(",") { it.first.toString() }})", null).use { c ->
            buildList { while (c.moveToNext()) add(Hit(c.getString(1), c.getString(2), c.getString(3) ?: "image/*", c.getString(4) ?: "", c.getLong(5), byId[c.getLong(0)] ?: 0f)) }
        }.sortedByDescending { it.score }
    }

    /** Photos with every word of [query] in their text, name or folder: what still works with no vector for the words. */
    fun byWords(
        db: SQLiteDatabase,
        query: String,
        limit: Int = 12,
    ): List<Hit> {
        val match = FileQuery.match(query, "words") ?: return emptyList()
        return runCatching {
            db.rawQuery("SELECT p.uri, p.kind, p.mime, p.name, p.taken FROM ptext JOIN photos p ON p.id = ptext.docid WHERE ptext MATCH ? ORDER BY p.taken DESC LIMIT $limit", arrayOf(match)).use { c ->
                buildList { while (c.moveToNext()) add(Hit(c.getString(0), c.getString(1), c.getString(2) ?: "image/*", c.getString(3) ?: "", c.getLong(4), 1f)) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Which of the photos nearest a search to show: the clear leaders only. EmbeddingGemma's closeness sits in a narrow
     * band (about 0.55 to 0.85, unrelated photos near 0.6, measured on the Mac Pro 2026-10-20), so a photo shows if it
     * is within [SPREAD] of the best one, and the best itself only above [FLOOR].
     */
    fun leaders(scored: List<Pair<Long, Float>>): List<Pair<Long, Float>> {
        val top = scored.firstOrNull()?.second ?: return emptyList()
        return scored.filter { it.second >= FLOOR && it.second >= top - SPREAD }
    }

    /**
     * Which leaders to show, by how sure the best of them is: a clear match ([STRONG] or more) shows them all; a weak
     * one only a row of [WEAK_MAX], and none at all when the words are the very name of an app or command he has
     * ([named]): "claude" brought a screenful of clouds (2026-10-21, on the phone).
     */
    fun shown(
        scored: List<Pair<Long, Float>>,
        named: Boolean,
    ): List<Pair<Long, Float>> {
        val l = leaders(scored)
        val top = l.firstOrNull()?.second ?: return emptyList()
        if (top >= STRONG) return l
        return if (named) emptyList() else l.take(WEAK_MAX)
    }

    /**
     * True when [query] names one of [names] (the apps, commands and people it found): it begins a word of one, or is
     * a word of one with a typo. Weak photos then stay out ([shown]): the 135 check, "andrew" and "dicsord" brought
     * rows of unrelated photos under the contact and the app he meant.
     */
    fun names(
        names: List<String>,
        query: String,
    ): Boolean {
        val q = RootMatch.fold(query.trim())
        if (q.isEmpty()) return false
        val most = FileQuery.typos(q.length)
        return names.any { n ->
            val folded = RootMatch.fold(n)
            folded == q || folded.split(Regex("[^\\p{L}\\p{N}]+")).any { w -> w.startsWith(q) || (most > 0 && FileQuery.distance(q, w, most) <= most) }
        }
    }

    const val STRONG = 0.67f
    const val WEAK_MAX = 3

    /** A photo's words for searching by them: its name and folder, any text in it, and what the Mac saw in it. */
    fun words(
        name: String,
        folder: String,
        texts: List<String>,
        tags: List<String>,
    ): String = (listOf(FileQuery.nameWords(name), folder) + texts.filter { it.isNotBlank() } + tags.distinct()).joinToString(" ")

    const val FLOOR = 0.62f
    const val SPREAD = 0.05f
}

/** EmbeddingGemma 2 on the Mac Pro, over Tailscale (tools/embed-mac). */
object PhotoMac {
    const val DEFAULT = "http://100.120.219.46:8179"

    fun base(context: Context): String =
        runCatching { File(context.filesDir, "arch/root/.config/omalauncher/embed-server.txt").readText().trim() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.trimEnd('/')
            ?: DEFAULT

    fun reachable(base: String): Boolean {
        val url = runCatching { URL(base) }.getOrNull() ?: return false
        return runCatching { Socket().use { it.connect(InetSocketAddress(url.host, if (url.port > 0) url.port else 80), 800) } }.isSuccess
    }

    private fun post(
        base: String,
        path: String,
        body: JSONObject,
        timeoutMs: Int,
    ): JSONObject? =
        runCatching {
            val c = URL(base + path).openConnection() as HttpURLConnection
            c.connectTimeout = 800
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    /** Which reading of photos the Mac gives now (1: vector and text; 2: and tags); 0 if it didn't say. */
    fun reads(base: String): Int =
        runCatching {
            val c = URL("$base/health").openConnection() as HttpURLConnection
            c.connectTimeout = 800
            c.readTimeout = 3_000
            JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("reads", 1)
        }.getOrDefault(0)

    /** What the Mac read of one photo: its vector, any text in it, and what is in it as words. */
    class Read(
        val vector: FloatArray,
        val text: String,
        val tags: List<String>,
    )

    /** The vector of a search's words, or null if the Mac didn't answer in time. */
    fun text(
        base: String,
        words: String,
    ): FloatArray? = post(base, "/text", JSONObject().put("texts", JSONArray().put(words)), 2_000)?.optJSONArray("vectors")?.optJSONArray(0)?.let(::floats)

    /** How near in meaning each of [docs] is to [query], by the Mac's model; null if it didn't answer in time. */
    fun rerank(
        base: String,
        query: String,
        docs: List<String>,
    ): FloatArray? =
        post(base, "/rerank", JSONObject().put("query", query).put("docs", JSONArray(docs)), 2_500)
            ?.optJSONArray("scores")
            ?.let(::floats)
            ?.takeIf { it.size == docs.size }

    /** What the Mac reads of each picture ([jpegs] by key). */
    fun images(
        base: String,
        jpegs: List<Pair<String, ByteArray>>,
    ): Map<String, Read>? {
        val arr = JSONArray()
        for ((key, jpeg) in jpegs) arr.put(JSONObject().put("id", key).put("jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP)))
        val items = post(base, "/images", JSONObject().put("images", arr), 120_000)?.optJSONArray("items") ?: return null
        return buildMap {
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val tags = it.optJSONArray("tags")?.let { t -> List(t.length()) { n -> t.getString(n) } }.orEmpty()
                put(it.getString("id"), Read(floats(it.getJSONArray("vector")), it.optString("text"), tags))
            }
        }
    }
}

/**
 * Keeps the photo index: new photos, videos and GIFs from the media store are sent to the Mac as small copies to be
 * read; gone ones are dropped. Runs in the `:indexer` process, on unmetered Wi-Fi only, and only when the Mac answers.
 */
class PhotoIndexJob : JobService() {
    @Volatile private var stop = false

    override fun onStartJob(params: JobParameters): Boolean {
        stop = false
        thread(name = "photo-index") {
            runCatching { index(applicationContext) { stop } }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stop = true
        return true
    }

    companion object {
        private const val NOW = 4201
        private const val PERIODIC = 4202
        private const val BATCH = 16
        private const val SIDE = 384
        private const val SCREENSHOT_SIDE = 1080

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, PhotoIndexJob::class.java)
            if (js.getPendingJob(PERIODIC) == null) {
                js.schedule(JobInfo.Builder(PERIODIC, component).setPeriodic(3 * 3600_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).build())
            }
            if (js.getPendingJob(NOW) == null) {
                js.schedule(JobInfo.Builder(NOW, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setOverrideDeadline(5_000).build())
            }
        }

        private class Todo(val id: Long, val uri: Uri, val name: String, val folder: String, val video: Boolean, val side: Int)

        /** The small copies of a photo to send: one, or for a video three frames, at 15, 50 and 85% of it. */
        private fun pictures(
            context: Context,
            t: Todo,
        ): List<ByteArray> {
            fun jpeg(bmp: Bitmap): ByteArray {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                bmp.recycle()
                return out.toByteArray()
            }
            if (t.video) {
                val frames =
                    runCatching {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, t.uri)
                            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            listOf(0.15, 0.5, 0.85).mapNotNull { at ->
                                r.getScaledFrameAtTime((ms * at * 1000).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, t.side, t.side)?.let(::jpeg)
                            }
                        } finally {
                            r.release()
                        }
                    }.getOrDefault(emptyList())
                if (frames.isNotEmpty()) return frames
            }
            return listOfNotNull(runCatching { jpeg(context.contentResolver.loadThumbnail(t.uri, Size(t.side, t.side), null)) }.getOrNull())
        }

        private class Media(val uri: Uri, val kind: String, val mime: String, val name: String, val folder: String, val taken: Long)

        private fun media(context: Context): List<Media> {
            val out = ArrayList<Media>()

            fun read(
                collection: Uri,
                video: Boolean,
            ) {
                val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED)
                runCatching {
                    context.contentResolver.query(collection, cols, null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val mime = c.getString(1) ?: if (video) "video/*" else "image/*"
                            val taken = c.getLong(4).takeIf { it > 0 } ?: (c.getLong(5) * 1000)
                            val kind = if (video) "video" else if (mime == "image/gif") "gif" else "image"
                            out += Media(ContentUris.withAppendedId(collection, id), kind, mime, c.getString(2) ?: "", c.getString(3) ?: "", taken)
                        }
                    }
                }
            }
            read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false)
            read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true)
            return out
        }

        fun index(
            context: Context,
            stopped: () -> Boolean,
        ) {
            val db = PhotoIndex.open(context, writable = true) ?: return
            try {
                val now = media(context)
                val known = HashMap<String, Long>()
                db.rawQuery("SELECT uri, id FROM photos", null).use { c -> while (c.moveToNext()) known[c.getString(0)] = c.getLong(1) }
                val here = now.map { it.uri.toString() }.toHashSet()
                db.beginTransaction()
                try {
                    for (m in now) {
                        val key = m.uri.toString()
                        if (key in known) continue
                        val id = db.insert("photos", null, ContentValues().apply { put("uri", key); put("kind", m.kind); put("mime", m.mime); put("name", m.name); put("folder", m.folder); put("taken", m.taken) })
                        if (id >= 0) db.execSQL("INSERT INTO ptext(docid, words) VALUES(?, ?)", arrayOf<Any>(id, FileQuery.nameWords(m.name) + " " + m.folder))
                    }
                    for ((uri, id) in known) {
                        if (uri in here) continue
                        db.delete("photos", "id = ?", arrayOf(id.toString()))
                        db.execSQL("DELETE FROM ptext WHERE docid = ?", arrayOf<Any>(id))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                // The phone's own text model, for searching while the Mac is away: fetched here, on unmetered Wi-Fi.
                PhotoModel.download(context, stopped)
                val base = PhotoMac.base(context)
                if (!PhotoMac.reachable(base)) return
                PhotoModel.check(context, base)
                val reads = PhotoMac.reads(base).takeIf { it > 0 } ?: return
                // Newest first, so what he took lately is searchable soonest; then the ones an older reading of the
                // Mac's saw, to be read again for what it reads now (tags, since 2026-10-21).
                // A screenshot goes at its full width: the Mac reads its text too, and at 384 pixels that misreads.
                val todo = ArrayList<Todo>()
                db.rawQuery(
                    "SELECT id, uri, name, folder, kind FROM photos WHERE vec IS NULL OR (length(vec) > 0 AND reads < ?) ORDER BY vec IS NOT NULL, taken DESC",
                    arrayOf(reads.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(2) ?: ""
                        val folder = c.getString(3) ?: ""
                        val shot = name.startsWith("Screenshot", ignoreCase = true) || folder.equals("Screenshots", ignoreCase = true)
                        todo += Todo(c.getLong(0), Uri.parse(c.getString(1)), name, folder, c.getString(4) == "video", if (shot) SCREENSHOT_SIDE else SIDE)
                    }
                }
                for (batch in todo.chunked(BATCH)) {
                    if (stopped()) return
                    // A video goes as three frames, keyed "id/0", "id/1", "id/2"; anything else as one picture.
                    val jpegs = batch.flatMap { t -> pictures(context, t).mapIndexed { n, jpeg -> "${t.id}/$n" to jpeg } }
                    if (jpegs.isEmpty()) continue
                    val read = PhotoMac.images(base, jpegs) ?: return
                    db.beginTransaction()
                    try {
                        for (t in batch) {
                            val got = (0 until PhotoIndex.FRAMES).mapNotNull { read["${t.id}/$it"] }
                            if (got.isEmpty()) {
                                // One the Mac couldn't read gets an empty vector, so it isn't sent again and again.
                                if (jpegs.none { it.first.startsWith("${t.id}/") } || read.isNotEmpty()) db.execSQL("UPDATE photos SET vec = CASE WHEN vec IS NULL THEN x'' ELSE vec END, reads = ? WHERE id = ?", arrayOf<Any>(reads, t.id))
                                continue
                            }
                            db.execSQL("UPDATE photos SET vec = ?, reads = ? WHERE id = ?", arrayOf<Any>(PhotoIndex.quantize(got[0].vector), reads, t.id))
                            db.execSQL("DELETE FROM frames WHERE photo = ?", arrayOf<Any>(t.id))
                            for (f in got.drop(1)) db.execSQL("INSERT INTO frames(photo, vec) VALUES(?, ?)", arrayOf<Any>(t.id, PhotoIndex.quantize(f.vector)))
                            db.execSQL("UPDATE ptext SET words = ? WHERE docid = ?", arrayOf<Any>(PhotoIndex.words(t.name, t.folder, got.map { it.text }, got.flatMap { it.tags }), t.id))
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            } finally {
                db.close()
            }
        }
    }
}
