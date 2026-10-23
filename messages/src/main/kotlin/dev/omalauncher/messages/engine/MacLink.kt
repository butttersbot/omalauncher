package dev.omalauncher.messages.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Where the Mac's iMessage service answers, and the token it wants. */
data class MacAddress(
    val base: String,
    val token: String,
) {
    val host: String get() = runCatching { URL(base).host }.getOrDefault(base)

    fun toJson(): String = JSONObject().put("base", base).put("token", token).toString()

    companion object {
        /**
         * An address from the link the service prints (soma-messages://link?url=…&token=…); null unless it is one this
         * app will talk to: https anywhere, plain http only inside the tailnet (100.64.0.0/10 or a ts.net name).
         */
        fun of(
            url: String?,
            token: String?,
        ): MacAddress? {
            val base = url?.trim()?.trimEnd('/') ?: return null
            if (token.isNullOrBlank()) return null
            val u = runCatching { URL(base) }.getOrNull() ?: return null
            val ok =
                when (u.protocol) {
                    "https" -> true
                    "http" -> inTailnet(u.host)
                    else -> false
                }
            return if (ok && u.host.isNotEmpty()) MacAddress(base, token.trim()) else null
        }

        fun inTailnet(host: String): Boolean {
            if (host.endsWith(".ts.net")) return true
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            return p.size == 4 && p[0] == 100 && p[1] in 64..127 && p.all { it in 0..255 }
        }

        fun fromJson(text: String): MacAddress? =
            runCatching {
                val o = JSONObject(text)
                of(o.getString("base"), o.getString("token"))
            }.getOrNull()
    }
}

/**
 * The link to the Mac that runs Messages (Boss, 2026-10-22: the Mac Pro, which sends from his number). Once linked,
 * conversations come from the Mac and replies and photos go out through it, with no other app opened. A thread
 * keeps the event stream open while the app runs, and picks it up again after the network drops.
 */
object MacLink {
    private const val TAG = "SomaMessages"

    /** The conversations fetched with their history when the stream (re)connects; the rest load when opened. */
    private const val PRIMED = 15

    /** What the link is doing, for the screens: empty when all is well. */
    val trouble = MutableStateFlow("")

    private val io = Executors.newFixedThreadPool(2)
    private var running = false
    private var address: MacAddress? = null
    private val stream = MacWire.Stream()

    @Synchronized
    fun start(to: MacAddress) {
        address = to
        if (running) return
        running = true
        thread(name = "mac-link", isDaemon = true) { loop() }
    }

    private fun loop() {
        var wait = 2_000L
        while (true) {
            val at = address ?: return
            try {
                sync(at)
                listen(at)
                wait = 2_000L
            } catch (e: Exception) {
                Log.w(TAG, "mac link", e)
                trouble.value = "Can't reach your Mac (${at.host}). Trying again…"
            }
            Thread.sleep(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }

    private fun open(
        at: MacAddress,
        path: String,
        method: String = "GET",
        timeout: Int = 15_000,
    ): HttpURLConnection =
        (URL(at.base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = timeout
            setRequestProperty("Authorization", "Bearer " + at.token)
            setRequestProperty("Accept", "application/json")
        }

    private fun get(
        at: MacAddress,
        path: String,
    ): String {
        val c = open(at, path)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException(error(c))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun post(
        at: MacAddress,
        path: String,
        body: JSONObject,
    ) {
        val c = open(at, path, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) throw IllegalStateException(error(c))
            val answer = c.inputStream.bufferedReader().use { it.readText() }
            if (answer.isNotBlank() && !JSONObject(answer).optBoolean("ok", true)) throw IllegalStateException(JSONObject(answer).optString("error"))
        } finally {
            c.disconnect()
        }
    }

    private fun error(c: HttpURLConnection): String {
        val text = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        return runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifEmpty { null } ?: "The Mac answered ${c.responseCode}"
    }

    private fun path(id: String) = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    /** The chats, and the latest messages of the first few. */
    private fun sync(at: MacAddress) {
        val chats = MacWire.chats(get(at, "/v1/chats?limit=50"))
        chats.forEach { c -> Messages.fromMac(c.id, c.name, c.isGroup, c.last?.let(MacWire::toMessages).orEmpty(), c.unread) }
        chats.take(PRIMED).forEach { c -> history(at, c.id) }
        trouble.value = ""
    }

    private fun history(
        at: MacAddress,
        id: String,
    ) {
        val ms = MacWire.messages(get(at, "/v1/chats/${path(id)}/messages?limit=50"))
        Messages.fromMac(id, null, null, ms.flatMap(MacWire::toMessages), unread = null, answered = emptyList(), counting = false)
    }

    /** Holds the event stream open until it drops. */
    private fun listen(at: MacAddress) {
        // The service sends a keep-alive well inside this; silence for longer is a dead link.
        val c = open(at, "/v1/events", timeout = 90_000)
        c.setRequestProperty("Accept", "text/event-stream")
        stream.lastId?.let { c.setRequestProperty("Last-Event-ID", it) }
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException(error(c))
            stream.reset()
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val e = stream.line(line) ?: continue
                    runCatching { take(at, e) }.onFailure { Log.w(TAG, "mac event ${e.type}", it) }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun take(
        at: MacAddress,
        e: MacWire.Event,
    ) {
        when (e.type) {
            "message.new", "message.updated" -> {
                val m = MacWire.msg(JSONObject(e.data))
                val known = Messages.book.value[m.chatId] != null
                Messages.fromMac(m.chatId, null, null, MacWire.toMessages(m), answered = listOfNotNull(m.clientId))
                // A conversation not seen before: its name comes with the chat list.
                if (!known) io.execute { runCatching { sync(at) } }
            }
            "chat.updated" -> {
                val o = JSONObject(e.data)
                Messages.fromMac(o.getString("id"), o.optString("name").ifEmpty { null }, if (o.has("isGroup")) o.optBoolean("isGroup") else null, emptyList(), if (o.has("unread")) o.optInt("unread") else null)
            }
        }
    }

    /** Sends [text] to [chat]; [onFail] when the Mac doesn't take it. */
    fun send(
        chat: String,
        text: String,
        clientId: String,
        onFail: () -> Unit,
    ) {
        val at = address ?: return onFail()
        io.execute {
            runCatching { post(at, "/v1/chats/${path(chat)}/messages", JSONObject().put("text", text).put("clientId", clientId)) }
                .onFailure {
                    Log.w(TAG, "sending", it)
                    onFail()
                }
        }
    }

    /** Sends [photos] (and [text] with them) to [chat] in one go. */
    fun sendFiles(
        context: Context,
        chat: String,
        photos: List<Uri>,
        text: String,
        clientId: String,
        onFail: () -> Unit,
    ) {
        val at = address ?: return onFail()
        val resolver = context.contentResolver
        io.execute {
            runCatching {
                val boundary = "soma" + System.nanoTime()
                val c = open(at, "/v1/chats/${path(chat)}/attachments", "POST", timeout = 120_000)
                try {
                    c.doOutput = true
                    c.setChunkedStreamingMode(64 * 1024)
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    c.outputStream.buffered().use { out ->
                        fun field(
                            name: String,
                            value: String,
                        ) = out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                        field("clientId", clientId)
                        if (text.isNotBlank()) field("text", text)
                        photos.forEachIndexed { i, uri ->
                            val type = resolver.getType(uri) ?: "image/jpeg"
                            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
                            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo-${i + 1}.$ext\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
                            resolver.openInputStream(uri)!!.use { it.copyTo(out) }
                            out.write("\r\n".toByteArray())
                        }
                        out.write("--$boundary--\r\n".toByteArray())
                    }
                    if (c.responseCode !in 200..299) throw IllegalStateException(error(c))
                } finally {
                    c.disconnect()
                }
            }.onFailure {
                Log.w(TAG, "sending photos", it)
                onFail()
            }
        }
    }

    /** The owner opened [chat]: its history, and read on the Mac. */
    fun opened(chat: String) {
        val at = address ?: return
        io.execute {
            runCatching { history(at, chat) }.onFailure { Log.w(TAG, "history", it) }
            runCatching { post(at, "/v1/chats/${path(chat)}/read", JSONObject()) }.onFailure { Log.w(TAG, "read", it) }
        }
    }

    /** The saved address, in the app's own files. */
    fun file(context: Context) = File(context.filesDir, "mac.json")
}
