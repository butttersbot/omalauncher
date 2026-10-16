package dev.omalauncher.herd

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.omalauncher.phone.PhoneService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/*
 * The rest of the sheep's Tailshare tab on the phone (Boss, 2026-10-16: "we should have both"; settled with the
 * Tailshare session): "Send clipboard to" a computer, and "Sent to this phone", what Tailshare received.
 */

/** The phone's name on the tailnet, which a clip's file name carries so the receiver says who sent it. */
const val PHONE_TAILNET_NAME = "jacks-z-fold8"

/** What Tailshare sends for each thing it receives, with the item's JSON in [EXTRA_CLIP_ITEM]. */
const val ACTION_CLIP_RECEIVED = "dev.omalauncher.action.CLIP_RECEIVED"
const val EXTRA_CLIP_ITEM = "item"

/** How many received things the list keeps. */
const val INBOX_KEEP = 30

/** A clip's name on the computer: clip-* is what makes the receiver treat it as a clipboard send. */
fun clipName(
    at: Date,
    picture: Boolean,
): String = "clip-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(at) + "-from-" + PHONE_TAILNET_NAME + if (picture) ".png" else ".txt"

/** One thing Tailshare received, as the page lists it. [file] is the copy kept here, when it came as a file. */
data class InboxItem(
    val name: String,
    val from: String?,
    val t: Double,
    val kind: String,
    val size: Long,
    val text: String?,
    val file: String?,
)

/** An item from Tailshare's JSON, checked; null for anything that isn't one. */
fun inboxItem(json: String?): InboxItem? {
    val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
    val name = pocketName(o.optString("name"), "").takeIf { it.isNotEmpty() } ?: return null
    val kind = o.optString("kind").takeIf { it in setOf("text", "image", "video", "file") } ?: "file"
    val from = (o.opt("from") as? String)?.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,62}").matches(it) }
    val text = (o.opt("text") as? String)?.take(8000)
    return InboxItem(name, from, o.optDouble("t", System.currentTimeMillis() / 1000.0), kind, o.optLong("size", text?.length?.toLong() ?: 0), text, null)
}

/** The list the page is given, newest first: `herd.inbox([...])`; [src] makes a picture's preview address. */
fun inboxJson(
    items: List<InboxItem>,
    src: (InboxItem) -> String? = { null },
): String =
    JSONArray(
        items.map { i ->
            JSONObject().put("name", i.name).put("from", i.from ?: JSONObject.NULL).put("t", i.t).put("kind", i.kind).put("size", i.size)
                .apply { i.text?.let { put("text", it) } }
                .apply { src(i)?.let { put("src", it) } }
        },
    ).toString()

object ClipInbox {
    private val main = Handler(Looper.getMainLooper())

    private fun dir(context: Context) = File(context.filesDir, "inbox").apply { mkdirs() }

    private fun index(context: Context) = File(dir(context), "index.json")

    /** What was received, newest first. */
    @Synchronized fun items(context: Context): List<InboxItem> {
        val array = runCatching { JSONArray(index(context).readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            inboxItem(o.toString())?.copy(file = (o.opt("file") as? String)?.takeIf { it.isNotEmpty() })
        }
    }

    @Synchronized private fun save(
        context: Context,
        items: List<InboxItem>,
    ) {
        val kept = items.take(INBOX_KEEP)
        // A file whose entry fell off the list goes with it.
        val files = kept.mapNotNull { it.file }.toSet()
        dir(context).listFiles()?.filter { it.name != "index.json" && it.name !in files }?.forEach { it.delete() }
        val array = JSONArray(kept.map { i -> JSONArray(inboxJson(listOf(i))).getJSONObject(0).put("file", i.file ?: "") })
        index(context).writeText(array.toString())
    }

    /** A picture's preview, through the app's file provider, which the page's WebView may load. */
    private fun src(
        context: Context,
        item: InboxItem,
    ): String? =
        item.file?.takeIf { item.kind == "image" }?.let {
            FileProvider.getUriForFile(context, context.packageName + ".files", File(dir(context), it)).toString()
        }

    /** Gives the list to every sheep that is up, or to [page] alone. */
    fun show(
        context: Context,
        page: WebView? = null,
    ) {
        val json = inboxJson(items(context)) { src(context, it) }
        if (page != null) main.post { giveHerd(page, "inbox", json) } else Pocket.tell("inbox", json)
    }

    /** Something Tailshare received: kept, a file copied in while the grant lasts, and the list shown again. */
    fun received(
        context: Context,
        item: InboxItem,
        uri: Uri?,
    ) {
        val file =
            uri?.let {
                val name = System.currentTimeMillis().toString() + "-" + item.name
                runCatching {
                    context.contentResolver.openInputStream(it)?.use { input -> File(dir(context), name).outputStream().use { out -> input.copyTo(out) } }
                    name
                }.getOrNull()
            }
        save(context, listOf(item.copy(file = file)) + items(context).filterNot { it.name == item.name && it.t == item.t })
        show(context)
    }

    /** Copy, open or remove [name] from the list, for the page's buttons. Main thread. */
    fun act(
        context: Context,
        page: WebView,
        name: String,
        action: String,
    ) {
        val item = items(context).firstOrNull { it.name == name } ?: return
        when (action) {
            "remove" -> {
                save(context, items(context).filterNot { it.name == name })
                show(context)
            }
            "copy" -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val file = item.file?.let { File(dir(context), it) }?.takeIf { it.isFile }
                val clip =
                    when {
                        file != null -> ClipData.newUri(context.contentResolver, name, FileProvider.getUriForFile(context, context.packageName + ".files", file))
                        item.text != null -> ClipData.newPlainText(name, item.text)
                        else -> null
                    }
                if (clip != null) clipboard.setPrimaryClip(clip)
                giveHerd(page, "sent", JSONObject.quote(if (clip != null) "Copied $name" else "$name is no longer here"))
            }
            "open" -> {
                val file = item.file?.let { File(dir(context), it) }?.takeIf { it.isFile }
                if (file == null) {
                    giveHerd(page, "sent", JSONObject.quote("$name is no longer here"))
                    return
                }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, pocketMime(null, name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(view) }.onFailure { giveHerd(page, "sent", JSONObject.quote("No app here opens $name.")) }
            }
        }
    }

    /**
     * The clipboard to [device]'s tailshare-serve, as Tailshare's own picker sends it: text as a .txt, a picture as a
     * PNG, named clip-* so the computer puts it on its clipboard. [page] is told how it went, as is the look's card.
     * Main thread: Android lets the clipboard be read only from what has the focus (home's sheep, not the floating one).
     */
    fun sendClipboard(
        context: Context,
        page: WebView,
        device: String,
    ) {
        val app = context.applicationContext
        val item = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        if (item == null) {
            giveHerd(page, "sent", JSONObject.quote("Couldn't send: Android doesn't let the floating sheep read the clipboard. Use the sheep on home."))
            return
        }
        val uri = item.uri
        val text = item.coerceToText(context)?.toString().orEmpty()
        thread(name = "oma-clip-send", isDaemon = true) {
            val result =
                runCatching {
                    val picture = uri != null && app.contentResolver.getType(uri)?.startsWith("image/") == true
                    val bytes =
                        if (picture) png(app, uri!!)
                        else text.takeIf { it.isNotEmpty() }?.toByteArray() ?: error("the clipboard has nothing to send")
                    val address = address(device) ?: error("$device doesn't take clips")
                    val connection = URL("http://$address:8480/v1/drop?name=" + URLEncoder.encode(clipName(Date(), picture), "UTF-8")).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 60_000
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    try {
                        connection.outputStream.use { it.write(bytes) }
                        if (connection.responseCode != 200) error("$device answered ${connection.responseCode}")
                    } finally {
                        connection.disconnect()
                    }
                }
            val said = result.fold({ "Sent your clipboard to $device" }, { "Couldn't send: " + (it.message ?: "$device didn't answer") })
            main.post {
                giveHerd(page, "sent", JSONObject.quote(said))
                if (PhoneService.instance?.showToast("Tailshare", said) != true) Toast.makeText(app, said, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** A picture as PNG, which a Mac's clipboard needs. */
    private fun png(
        context: Context,
        uri: Uri,
    ): ByteArray {
        check(Build.VERSION.SDK_INT >= 28) { "pictures need Android 9 or later" }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** [device]'s tailnet address, from tailshare-serve's own list on the Mac Pro, or the MacBook's when that is away. */
    private fun address(device: String): String? {
        for (host in listOf("100.120.219.46", "100.105.137.74")) {
            val list =
                runCatching {
                    val c = URL("http://$host:8480/v1/devices").openConnection() as HttpURLConnection
                    c.connectTimeout = 3_000
                    c.readTimeout = 6_000
                    try {
                        c.inputStream.bufferedReader().readText()
                    } finally {
                        c.disconnect()
                    }
                }.getOrNull() ?: continue
            val devices = runCatching { JSONObject(list).getJSONArray("devices") }.getOrNull() ?: continue
            for (i in 0 until devices.length()) {
                val d = devices.optJSONObject(i) ?: continue
                if (d.optString("name") == device) return d.optString("address").takeIf { Regex("[0-9a-fA-F.:]+").matches(it) }
            }
            return null
        }
        return null
    }
}

/** Tailshare's word that it received something. Only Tailshare is heard, by Android's own word for who sent it. */
class ClipReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_CLIP_RECEIVED || Build.VERSION.SDK_INT < 34) return
        if (sentFromPackage != "com.butttersbot.tailshare") return
        val raw = intent.getStringExtra(EXTRA_CLIP_ITEM)
        val item = inboxItem(raw) ?: return
        // The file comes with a read grant through the intent's ClipData, which lasts only as long as this broadcast.
        val uri =
            (runCatching { JSONObject(raw!!).optString("uri") }.getOrNull())?.takeIf { it.startsWith("content://com.butttersbot.tailshare.files/") }?.let(Uri::parse)
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "oma-clip-received", isDaemon = true) {
            try {
                ClipInbox.received(app, item, uri)
            } finally {
                pending.finish()
            }
        }
    }
}
