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
        db.rawQuery("SELECT id, vec FROM photos WHERE vec IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val v = c.getBlob(1) ?: continue
                if (v.size != DIM) continue
                ids += c.getLong(0)
                out.write(v)
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
     * Which of the photos nearest a search to show: the clear leaders only. EmbeddingGemma's closeness has no fixed
     * scale, so a photo shows if it is within [SPREAD] of the best one and above [FLOOR].
     */
    fun leaders(scored: List<Pair<Long, Float>>): List<Pair<Long, Float>> {
        val top = scored.firstOrNull()?.second ?: return emptyList()
        return scored.filter { it.second >= FLOOR && it.second >= top - SPREAD }
    }

    const val FLOOR = 0.05f
    const val SPREAD = 0.06f
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

    /** The vector of a search's words, or null if the Mac didn't answer in time. */
    fun text(
        base: String,
        words: String,
    ): FloatArray? = post(base, "/text", JSONObject().put("texts", JSONArray().put(words)), 2_000)?.optJSONArray("vectors")?.optJSONArray(0)?.let(::floats)

    /** What the Mac reads of each photo ([jpegs] by id): its vector and any text in it. */
    fun images(
        base: String,
        jpegs: List<Pair<Long, ByteArray>>,
    ): Map<Long, Pair<FloatArray, String>>? {
        val arr = JSONArray()
        for ((id, jpeg) in jpegs) arr.put(JSONObject().put("id", id.toString()).put("jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP)))
        val items = post(base, "/images", JSONObject().put("images", arr), 120_000)?.optJSONArray("items") ?: return null
        return buildMap {
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                put(it.getString("id").toLong(), floats(it.getJSONArray("vector")) to it.optString("text"))
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
                val base = PhotoMac.base(context)
                if (!PhotoMac.reachable(base)) return
                // Newest first, so what he took lately is searchable soonest.
                val todo = ArrayList<Pair<Long, Uri>>()
                db.rawQuery("SELECT id, uri FROM photos WHERE vec IS NULL ORDER BY taken DESC", null).use { c -> while (c.moveToNext()) todo += c.getLong(0) to Uri.parse(c.getString(1)) }
                for (batch in todo.chunked(BATCH)) {
                    if (stopped()) return
                    val jpegs =
                        batch.mapNotNull { (id, uri) ->
                            runCatching {
                                val bmp = context.contentResolver.loadThumbnail(uri, Size(SIDE, SIDE), null)
                                val out = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                                bmp.recycle()
                                id to out.toByteArray()
                            }.getOrNull()
                        }
                    if (jpegs.isEmpty()) continue
                    val read = PhotoMac.images(base, jpegs) ?: return
                    db.beginTransaction()
                    try {
                        for ((id, r) in read) {
                            db.execSQL("UPDATE photos SET vec = ? WHERE id = ?", arrayOf<Any>(PhotoIndex.quantize(r.first), id))
                            if (r.second.isNotBlank()) db.execSQL("UPDATE ptext SET words = words || ' ' || ? WHERE docid = ?", arrayOf<Any>(r.second, id))
                        }
                        // A photo the Mac couldn't read gets an empty vector, so it isn't sent again and again.
                        for ((id, _) in jpegs) if (id !in read) db.execSQL("UPDATE photos SET vec = x'' WHERE id = ?", arrayOf<Any>(id))
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
