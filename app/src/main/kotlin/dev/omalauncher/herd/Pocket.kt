package dev.omalauncher.herd

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.omalauncher.phone.PhoneService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.WeakHashMap
import kotlin.concurrent.thread

/*
 * The pocket on the phone (Boss, 2026-10-16; the Tailshare session's spec in ClaudeSync's omalauncher/CURRENT-STATE.md):
 * one folder every device with the sheep sees, kept by tailshare-serve on the Mac Pro. The page lists it, pins and
 * removes by itself; the phone pulls an item to open, save or copy it, and puts things in from the share sheet ("Add
 * to pocket") or a paste over the open panel. Only Boss's devices reach :8480 (tailscale whois there).
 */

/** The pocket, where the page reads it too (pet.html's POCKET). */
const val POCKET_URL = "http://100.120.219.46:8480/v1/pocket"

/** The type a pulled file is opened and saved as: the server's, else its name's, else plain bytes. */
fun pocketMime(
    contentType: String?,
    name: String,
): String {
    val given = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (!given.isNullOrEmpty() && given != "application/octet-stream" && '/' in given) return given
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: given?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
}

/** What the page is told of an upload: `herd.pocketBusy({name, state, error})`. */
fun pocketBusyJson(
    name: String,
    state: String,
    error: String? = null,
): String = JSONObject().put("name", name).put("state", state).apply { if (error != null) put("error", error) }.toString()

object Pocket {
    private val main = Handler(Looper.getMainLooper())

    // The sheep's pages that are up, home's and the floating one's; main thread only.
    private val pages: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

    fun attach(page: WebView) {
        pages += page
    }

    fun detach(page: WebView) {
        pages -= page
    }

    /** Something was shared to the sheep: each that is up opens its pocket tab. Any thread. */
    fun dropped(names: List<String>) = tell("pocketDropped", JSONArray(names).toString())

    /** Tells every sheep that is up, through its `herd.FUNCTION(json)`. Any thread. */
    internal fun tell(
        function: String,
        json: String,
    ) = main.post { pages.toList().forEach { giveHerd(it, function, json) } }

    /** Pulls item [id] and does [action] with it, then answers [page] with `herd.pocketPulled`. Main thread; works on its own. */
    fun pull(
        context: Context,
        page: WebView,
        id: String,
        name: String,
        action: PocketAction,
    ) {
        val app = context.applicationContext
        thread(name = "oma-pocket-pull", isDaemon = true) {
            val got = runCatching { download(app, id, name) }
            main.post {
                val error = got.fold({ (file, mime) -> runCatching { use(context, file, name, mime, action) }.exceptionOrNull()?.let { it.message ?: "It could not be done." } }, { it.message ?: "The pocket did not answer." })
                answer(page, id, action, error)
            }
        }
    }

    private fun answer(
        page: WebView,
        id: String,
        action: PocketAction,
        error: String?,
    ) = giveHerd(page, "pocketPulled", JSONObject().put("id", id).put("action", action.word).apply { if (error != null) put("error", error) }.toString())

    private fun download(
        context: Context,
        id: String,
        name: String,
    ): Pair<File, String> {
        val connection = URL("$POCKET_URL/$id").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != 200) error("The pocket answered ${connection.responseCode}.")
            val dir = File(context.cacheDir, "pocket/$id").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, pocketName(name, id))
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            return file to pocketMime(connection.contentType, file.name)
        } finally {
            connection.disconnect()
        }
    }

    private fun use(
        context: Context,
        file: File,
        name: String,
        mime: String,
        action: PocketAction,
    ) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        when (action) {
            PocketAction.Open -> {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(view)
                } catch (_: ActivityNotFoundException) {
                    error("No app here opens $name.")
                }
            }
            PocketAction.Save -> save(context, file, name, mime)
            PocketAction.Copy -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val clip =
                    if (mime.startsWith("text/") && file.length() <= 1_000_000) ClipData.newPlainText(name, file.readText())
                    else ClipData.newUri(context.contentResolver, name, uri)
                clipboard.setPrimaryClip(clip)
            }
        }
    }

    /** Into Download/Pocket, through MediaStore, which needs no permission for the app's own files. */
    private fun save(
        context: Context,
        file: File,
        name: String,
        mime: String,
    ) {
        check(Build.VERSION.SDK_INT >= 29) { "Saving needs Android 10 or later." }
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pocket")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Download/Pocket could not be written.")
        try {
            resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Download/Pocket could not be written.")
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }

    /**
     * Puts [name] in the pocket from [open], [size] bytes long (null when not known: then it is spooled first, as the
     * server wants a length). Tells the sheep as it goes. Blocking: call it off the main thread. True when it went in.
     */
    fun upload(
        context: Context,
        name: String,
        size: Long?,
        open: () -> InputStream,
    ): Boolean {
        val clean = pocketName(name)
        tell("pocketBusy", pocketBusyJson(clean, "uploading"))
        var spool: File? = null
        val error =
            runCatching {
                val length =
                    size?.takeIf { it >= 0 } ?: run {
                        val dir = File(context.cacheDir, "pocket-out").apply { mkdirs() }
                        spool = File.createTempFile("up", null, dir).also { f -> open().use { input -> f.outputStream().use { input.copyTo(it) } } }
                        spool!!.length()
                    }
                val connection = URL(POCKET_URL + "?name=" + URLEncoder.encode(clean, "UTF-8")).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 120_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(length)
                try {
                    (spool?.inputStream() ?: open()).use { input -> connection.outputStream.use { input.copyTo(it) } }
                    if (connection.responseCode != 200) error("The pocket answered ${connection.responseCode}.")
                } finally {
                    connection.disconnect()
                }
                null
            }.getOrElse { it.message ?: "The pocket did not answer." }
        spool?.delete()
        tell("pocketBusy", pocketBusyJson(clean, if (error == null) "done" else "failed", error))
        return error == null
    }

    /** What is on the clipboard into the pocket: text as a note, anything else as the file it points to. Main thread. */
    fun paste(context: Context) {
        val app = context.applicationContext
        val item = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        if (item == null) {
            tell("pocketBusy", pocketBusyJson("clipboard", "failed", "Nothing to paste here."))
            return
        }
        val uri = item.uri
        val text = item.coerceToText(context)?.toString().orEmpty()
        thread(name = "oma-pocket-paste", isDaemon = true) {
            if (uri != null) {
                val (name, size) = described(app, uri)
                upload(app, name, size) { app.contentResolver.openInputStream(uri) ?: error("The clipboard's file could not be read.") }
            } else if (text.isNotBlank()) {
                val bytes = text.toByteArray()
                upload(app, noteName(), bytes.size.toLong()) { bytes.inputStream() }
            }
        }
    }

    /** A shared or pasted file's name and size, as its provider says them. */
    fun described(
        context: Context,
        uri: Uri,
    ): Pair<String, Long?> =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "shared")
                val size = if (c.isNull(1)) null else c.getLong(1)
                name to size
            }
        }.getOrNull() ?: ((uri.lastPathSegment ?: "shared") to null)

    fun noteName() = "note-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
}

/**
 * "Add to pocket" in the share sheet: whatever is shared goes into the pocket, a file as itself and text as a note.
 * It shows nothing of its own: it stays up, unseen, only while it reads what it was given (the grant to read it lasts
 * as long as it does), then says how it went in the look's card and goes.
 */
class PocketShareActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val app = applicationContext
        val streams =
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intentUri(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE -> intentUris()
                else -> emptyList()
            }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { streams.isEmpty() && it.isNotBlank() }
        if (streams.isEmpty() && text == null) {
            finish()
            return
        }
        val shared = streams.map { Pocket.described(app, it) }
        thread(name = "oma-pocket-share", isDaemon = true) {
            val names = if (text != null) listOf(Pocket.noteName()) else shared.map { pocketName(it.first) }
            Pocket.dropped(names)
            val ok =
                if (text != null) {
                    val bytes = text.toByteArray()
                    Pocket.upload(app, names[0], bytes.size.toLong()) { bytes.inputStream() }
                } else {
                    streams.indices.map { i -> Pocket.upload(app, shared[i].first, shared[i].second) { contentResolver.openInputStream(streams[i]) ?: error("It could not be read.") } }.all { it }
                }
            val said = if (ok) (if (names.size == 1) "Added ${names[0]}" else "Added ${names.size} things") else "Couldn't add it. Is the Mac Pro on the tailnet?"
            Handler(Looper.getMainLooper()).post {
                if (PhoneService.instance?.showToast("Pocket", said) != true) Toast.makeText(app, "Pocket: $said", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun intentUri(key: String): Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java) else intent.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun intentUris(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}

/**
 * The newest screenshot into the pocket, for the button over Samsung's screenshot toolbar (phone/ShotButton.kt).
 * Reading it needs photo access, which Android asks him for here the first time; nothing is changed without him.
 * Shows nothing of its own, and says how it went in the look's card.
 */
class PocketShotActivity : Activity() {
    private val photos = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (checkSelfPermission(photos) == android.content.pm.PackageManager.PERMISSION_GRANTED) send() else requestPermissions(arrayOf(photos), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) send() else say("Oma needs photo access to put screenshots in the pocket.")
    }

    /** The newest screenshot of the last five minutes, uploaded. */
    private fun send() {
        val app = applicationContext
        thread(name = "oma-pocket-shot", isDaemon = true) {
            val shot =
                runCatching {
                    val since = System.currentTimeMillis() / 1000 - 300
                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE),
                        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?",
                        arrayOf("%Screenshots%", since.toString()),
                        "${MediaStore.Images.Media.DATE_ADDED} DESC",
                    )?.use { c ->
                        if (!c.moveToFirst()) null
                        else Triple(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getString(1), c.getLong(2))
                    }
                }.getOrNull()
            if (shot == null) {
                say("No new screenshot to add.")
                return@thread
            }
            val (uri, name, size) = shot
            Pocket.dropped(listOf(pocketName(name)))
            val ok = Pocket.upload(app, name, size) { contentResolver.openInputStream(uri) ?: error("The screenshot could not be read.") }
            say(if (ok) "Added ${pocketName(name)}" else "Couldn't add it. Is the Mac Pro on the tailnet?")
        }
    }

    private fun say(text: String) =
        Handler(Looper.getMainLooper()).post {
            if (PhoneService.instance?.showToast("Pocket", text) != true) Toast.makeText(applicationContext, "Pocket: $text", Toast.LENGTH_SHORT).show()
            finish()
        }
}
