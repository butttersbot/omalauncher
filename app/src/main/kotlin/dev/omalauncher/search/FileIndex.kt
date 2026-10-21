package dev.omalauncher.search

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.BatteryManager
import android.os.Environment
import java.io.File
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * The file index search reads (Boss, 2026-10-20, step 2: files by name and by the text inside them). One SQLite file
 * with a full-text table: each file's name cut into words, and the text of the files that have some (plain text,
 * code, notes, office files, PDFs). A query is a prefix match on the index, a few milliseconds over a hundred
 * thousand files, read from disk: nothing of it stays in memory between searches.
 *
 * It is kept by [FileIndexJob] in its own process, so the launcher never carries the walk or the reading; that
 * process ends when the job does. Names are brought up to date whenever it runs; the text of PDFs and office files
 * is read only while the phone charges.
 */
object FileIndex {
    fun file(context: Context) = File(context.filesDir, "search/files.db")

    fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    fun linuxHome(context: Context) = File(context.filesDir, "arch/root").absolutePath

    /** Whether Android lets Soma read all of shared storage ("All files access"); Linux's files are its own either way. */
    fun storageAllowed() = Environment.isExternalStorageManager()

    private const val SCHEMA = 1

    fun open(
        context: Context,
        writable: Boolean,
    ): SQLiteDatabase? {
        val f = file(context)
        if (!writable && !f.exists()) return null
        f.parentFile?.mkdirs()
        return runCatching {
            val db = SQLiteDatabase.openDatabase(f.path, null, if (writable) SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else SQLiteDatabase.OPEN_READONLY)
            if (writable && db.version < SCHEMA) {
                db.execSQL("CREATE TABLE IF NOT EXISTS files(id INTEGER PRIMARY KEY, path TEXT UNIQUE NOT NULL, name TEXT NOT NULL, size INTEGER, modified INTEGER, read INTEGER DEFAULT 0)")
                // The words of each file's name, and its text: prefix searches go through the prefix indexes.
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts4(name, body, tokenize=unicode61 \"remove_diacritics=1\", prefix=\"2,3,4\")")
                db.version = SCHEMA
            }
            db
        }.getOrNull()
    }

    /** Files whose name has every word of [query], newest first; [FileQuery.rankNames] orders them for showing. */
    fun byName(
        db: SQLiteDatabase,
        query: String,
        limit: Int = 300,
    ): List<FileQuery.Hit> {
        val match = FileQuery.match(query, "name") ?: return emptyList()
        return runCatching {
            db.rawQuery("SELECT f.path, f.name, f.modified, f.size FROM fts JOIN files f ON f.id = fts.docid WHERE fts MATCH ? ORDER BY f.modified DESC LIMIT $limit", arrayOf(match)).use { c ->
                buildList { while (c.moveToNext()) add(FileQuery.Hit(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))) }
            }
        }.getOrDefault(emptyList())
    }

    /** Files with every word of [query] in their text, with the words around the match; newest first. */
    fun byText(
        db: SQLiteDatabase,
        query: String,
        limit: Int = 30,
    ): List<FileQuery.Hit> {
        if (FileQuery.words(query).sumOf { it.length } < 3) return emptyList()
        val match = FileQuery.match(query, "body") ?: return emptyList()
        return runCatching {
            db.rawQuery(
                "SELECT f.path, f.name, f.modified, f.size, snippet(fts, char(1), char(2), '…', 1, 10) FROM fts JOIN files f ON f.id = fts.docid WHERE fts MATCH ? ORDER BY f.modified DESC LIMIT $limit",
                arrayOf(match),
            ).use { c ->
                buildList { while (c.moveToNext()) add(FileQuery.Hit(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4))) }
            }
        }.getOrDefault(emptyList())
    }

    /** How many files are indexed, for the row that says the index is still being made. */
    fun count(db: SQLiteDatabase): Long = runCatching { db.compileStatement("SELECT count(*) FROM files").simpleQueryForLong() }.getOrDefault(0)
}

/**
 * Brings the index up to date: walks shared storage (with All files access) and Linux's home, adds what is new or
 * changed, drops what is gone, and reads the text of files that have some. Runs in the `:indexer` process.
 */
class FileIndexJob : JobService() {
    @Volatile private var stop = false

    override fun onStartJob(params: JobParameters): Boolean {
        stop = false
        thread(name = "file-index") {
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
        private const val NOW = 4101
        private const val PERIODIC = 4102

        /** At most this many files are indexed: past it the walk stops. */
        private const val MAX_FILES = 400_000

        /** Text read of one file, at most: the beginning of a long log or book is what is searched. */
        private const val MAX_TEXT = 200_000

        /** Larger files than this aren't read for text. */
        private const val MAX_READ = 25L * 1024 * 1024

        /** Asks for a run soon, and for one every few hours; harmless to ask again. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, FileIndexJob::class.java)
            if (js.getPendingJob(PERIODIC) == null) {
                js.schedule(JobInfo.Builder(PERIODIC, component).setPeriodic(4 * 3600_000L).build())
            }
            if (js.getPendingJob(NOW) == null) js.schedule(JobInfo.Builder(NOW, component).setOverrideDeadline(2_000).build())
        }

        private fun charging(context: Context) = context.getSystemService(BatteryManager::class.java)?.isCharging == true

        fun index(
            context: Context,
            stopped: () -> Boolean,
        ) {
            val db = FileIndex.open(context, writable = true) ?: return
            try {
                val roots = buildList {
                    if (FileIndex.storageAllowed()) add(File(FileIndex.storageRoot()))
                    add(File(FileIndex.linuxHome(context)))
                }
                // What the index has now: path -> (id, modified, size).
                val known = HashMap<String, Triple<Long, Long, Long>>()
                db.rawQuery("SELECT path, id, modified, size FROM files", null).use { c -> while (c.moveToNext()) known[c.getString(0)] = Triple(c.getLong(1), c.getLong(2), c.getLong(3)) }
                val seen = HashSet<String>()
                val storage = FileIndex.storageRoot()
                db.beginTransaction()
                var batch = 0
                try {
                    val stack = ArrayDeque(roots)
                    while (stack.isNotEmpty() && !stopped() && seen.size < MAX_FILES) {
                        val dir = stack.removeLast()
                        // Other apps' private storage is not his to search.
                        if (dir.path == "$storage/Android/data" || dir.path == "$storage/Android/obb") continue
                        val entries = dir.listFiles() ?: continue
                        for (f in entries) {
                            if (FileQuery.skipped(f.name)) continue
                            // A link could lead back up the tree, or out of it: only what is really here is walked.
                            if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                            if (f.isDirectory) {
                                stack.addLast(f)
                                continue
                            }
                            val path = f.path
                            seen += path
                            val modified = f.lastModified()
                            val size = f.length()
                            val old = known[path]
                            if (old != null && old.second == modified && old.third == size) continue
                            val id =
                                if (old != null) {
                                    db.update("files", ContentValues().apply { put("modified", modified); put("size", size); put("read", 0) }, "id = ?", arrayOf(old.first.toString()))
                                    db.execSQL("UPDATE fts SET name = ?, body = '' WHERE docid = ?", arrayOf<Any>(FileQuery.nameWords(f.name), old.first))
                                    old.first
                                } else {
                                    val id = db.insert("files", null, ContentValues().apply { put("path", path); put("name", f.name); put("modified", modified); put("size", size) })
                                    db.execSQL("INSERT INTO fts(docid, name, body) VALUES(?, ?, '')", arrayOf<Any>(id, FileQuery.nameWords(f.name)))
                                    id
                                }
                            if (id < 0) continue
                            if (++batch % 2000 == 0) {
                                db.setTransactionSuccessful()
                                db.endTransaction()
                                db.beginTransaction()
                            }
                        }
                    }
                    // Gone since: out of the index, unless the walk was cut short and didn't get to look.
                    if (!stopped() && seen.size < MAX_FILES) {
                        for ((path, row) in known) {
                            if (path in seen) continue
                            db.delete("files", "id = ?", arrayOf(row.first.toString()))
                            db.execSQL("DELETE FROM fts WHERE docid = ?", arrayOf<Any>(row.first))
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                readTexts(context, db, stopped)
            } finally {
                db.close()
            }
        }

        /** Reads the text of files not read yet: plain text always, office files and PDFs only while charging. */
        private fun readTexts(
            context: Context,
            db: SQLiteDatabase,
            stopped: () -> Boolean,
        ) {
            val heavy = charging(context)
            val todo = ArrayList<Pair<Long, String>>()
            db.rawQuery("SELECT id, path FROM files WHERE read = 0 AND size <= $MAX_READ", null).use { c -> while (c.moveToNext()) todo += c.getLong(0) to c.getString(1) }
            var pdf = false
            for ((id, path) in todo) {
                if (stopped()) return
                val ext = path.substringAfterLast('.', "").lowercase()
                val text =
                    runCatching {
                        when (ext) {
                            in FileQuery.TEXT -> File(path).inputStream().use { String(readUpTo(it, MAX_TEXT), Charsets.UTF_8) }
                            in FileQuery.OFFICE -> if (heavy) office(File(path)) else null
                            "pdf" ->
                                if (heavy) {
                                    if (!pdf) {
                                        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
                                        pdf = true
                                    }
                                    pdfText(File(path))
                                } else {
                                    null
                                }
                            else -> ""
                        }
                    }.getOrDefault("")
                // Null: to be read later, while charging.
                if (text == null) continue
                db.execSQL("UPDATE fts SET body = ? WHERE docid = ?", arrayOf<Any>(text.take(MAX_TEXT), id))
                db.execSQL("UPDATE files SET read = 1 WHERE id = ?", arrayOf<Any>(id))
            }
        }

        private fun office(file: File): String =
            ZipFile(file).use { zip ->
                val parts = zip.entries().asSequence().filter { e ->
                    val n = e.name
                    n == "word/document.xml" || n == "content.xml" || n == "xl/sharedStrings.xml" || (n.startsWith("ppt/slides/slide") && n.endsWith(".xml"))
                }.toList()
                buildString {
                    for (p in parts) {
                        if (length > MAX_TEXT) break
                        append(FileQuery.xmlText(zip.getInputStream(p).use { String(readUpTo(it, MAX_TEXT * 4), Charsets.UTF_8) })).append('\n')
                    }
                }
            }

        /** The first [n] bytes of [input], or all of it if shorter. */
        private fun readUpTo(
            input: java.io.InputStream,
            n: Int,
        ): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < n) {
                val r = input.read(buf, 0, minOf(buf.size, n - out.size()))
                if (r < 0) break
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }

        private fun pdfText(file: File): String =
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                stripper.endPage = minOf(doc.numberOfPages, 60)
                stripper.getText(doc)
            }
    }
}
