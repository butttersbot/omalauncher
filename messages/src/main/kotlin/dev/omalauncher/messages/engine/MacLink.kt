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

    /** Each conversation's people, handle to name, from the chat list: a message carries only the sender's handle. */
    private val names = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

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

    /** Sends [body] (JSON, or nothing) and answers with what came back. */
    private fun call(
        at: MacAddress,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): String {
        val c = open(at, path, method, timeout = 60_000)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException(error(c))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** The service's own words when it says no: {"error":{"message":…}}. */
    private fun error(c: HttpURLConnection): String {
        val text = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val said = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
        return said?.ifEmpty { null } ?: when (c.responseCode) {
            401 -> "Your Mac didn't take this phone's token."
            403 -> "Your Mac is set to read only: sending is off there."
            else -> "Your Mac answered ${c.responseCode}."
        }
    }

    private fun path(id: String) = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun messagesOf(m: MacWire.Msg): List<Message> {
        val people = names[m.chatId].orEmpty()
        val book = Messages.book.value[m.chatId]
        return MacWire.toMessages(
            m,
            nameOf = { people[it] },
            quoteOf = { id ->
                book?.messages?.lastOrNull { it.id == id || it.id == "$id#a" }?.let { q -> (if (q.fromMe) "You" else q.sender) + ": " + q.text.take(90) }
            },
        )
    }

    /** The chats, and the latest messages of the first few. */
    private fun sync(at: MacAddress) {
        val chats = MacWire.chats(get(at, "/v1/threads"))
        chats.forEach { c ->
            names[c.id] = c.people.associate { it.handle to it.name }
            val unread = if (c.unread) maxOf(1, Messages.book.value[c.id]?.unread ?: 0) else 0
            Messages.fromMac(c.id, c.name, c.isGroup, c.last?.let(::messagesOf).orEmpty(), unread)
        }
        chats.take(PRIMED).forEach { c -> history(at, c.id) }
        trouble.value = ""
    }

    private fun history(
        at: MacAddress,
        id: String,
    ) {
        val ms = MacWire.messages(get(at, "/v1/threads/${path(id)}/messages"))
        // Oldest first, so a reply finds the message it quotes.
        ms.forEach { Messages.fromMac(id, null, null, messagesOf(it), unread = null, counting = false) }
    }

    /** Holds the event stream open until it drops. */
    private fun listen(at: MacAddress) {
        // The service sends a keep-alive every 20 s; silence for longer than this is a dead link.
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
            // The events since the last one seen are gone (the service restarted): everything again.
            "resync" -> {
                stream.lastId = null
                sync(at)
            }
            "imessage" -> {
                val s = MacWire.sync(e.data) ?: return
                when (s.objectName to s.mutation) {
                    "message" to "upsert" -> {
                        val known = Messages.book.value[s.chatId] != null
                        val ms = (0 until s.entries.length()).mapNotNull { s.entries.optJSONObject(it) }.map(MacWire::msg)
                        ms.forEach { Messages.fromMac(s.chatId, null, null, messagesOf(it)) }
                        // A conversation not seen before: its name and people come with the chat list.
                        if (!known) io.execute { runCatching { sync(at) } }
                    }
                    "message" to "delete" -> Messages.removed(s.chatId, (0 until s.entries.length()).map { s.entries.get(it).toString() })
                    "thread" to "update" -> {
                        for (i in 0 until s.entries.length()) {
                            val patch = s.entries.optJSONObject(i) ?: continue
                            val unread = if (patch.has("isUnread")) (if (patch.optBoolean("isUnread")) 1 else 0) else null
                            val title = if (patch.has("title")) patch.optString("title").ifEmpty { null } else null
                            Messages.fromMac(s.chatId, title, null, emptyList(), unread)
                        }
                    }
                    // Patches to a message (delivered, read, a tapback) change nothing shown yet.
                }
            }
        }
    }

    /** Sends [text] to [chat], as a threaded reply to [replyTo] when given; [onFail] when the Mac doesn't take it. */
    fun send(
        chat: String,
        text: String,
        replyTo: String?,
        onFail: (String) -> Unit,
    ) {
        val at = address ?: return onFail("Not linked to a Mac.")
        io.execute {
            runCatching {
                val body = JSONObject().put("text", text)
                replyTo?.let { body.put("replyTo", it) }
                MacWire.sent(call(at, "POST", "/v1/threads/${path(chat)}/messages", body)).forEach { Messages.fromMac(chat, null, null, messagesOf(it)) }
            }.onFailure {
                Log.w(TAG, "sending", it)
                onFail(it.message.orEmpty())
            }
        }
    }

    /** Sends [photos] to [chat], one after another, then [text]; [onFail] at the first the Mac doesn't take. */
    fun sendFiles(
        context: Context,
        chat: String,
        photos: List<Uri>,
        text: String,
        onFail: (String) -> Unit,
    ) {
        val at = address ?: return onFail("Not linked to a Mac.")
        val resolver = context.contentResolver
        io.execute {
            runCatching {
                photos.forEachIndexed { i, uri ->
                    val type = resolver.getType(uri) ?: "image/jpeg"
                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
                    val c = open(at, "/v1/threads/${path(chat)}/attachments?fileName=photo-${i + 1}.$ext", "POST", timeout = 120_000)
                    try {
                        c.doOutput = true
                        c.setChunkedStreamingMode(64 * 1024)
                        c.setRequestProperty("Content-Type", type)
                        c.outputStream.use { out -> resolver.openInputStream(uri)!!.use { it.copyTo(out) } }
                        if (c.responseCode !in 200..299) throw IllegalStateException(error(c))
                        val answer = c.inputStream.bufferedReader().use { it.readText() }
                        MacWire.sent(answer).forEach { Messages.fromMac(chat, null, null, messagesOf(it)) }
                    } finally {
                        c.disconnect()
                    }
                }
                if (text.isNotBlank()) {
                    MacWire.sent(call(at, "POST", "/v1/threads/${path(chat)}/messages", JSONObject().put("text", text))).forEach { Messages.fromMac(chat, null, null, messagesOf(it)) }
                }
            }.onFailure {
                Log.w(TAG, "sending photos", it)
                onFail(it.message.orEmpty())
            }
        }
    }

    /** The owner opened [chat]: its history, and read on the Mac. */
    fun opened(chat: String) {
        val at = address ?: return
        io.execute {
            runCatching { history(at, chat) }.onFailure { Log.w(TAG, "history", it) }
            runCatching { call(at, "POST", "/v1/threads/${path(chat)}/read") }.onFailure { Log.w(TAG, "read", it) }
        }
    }

    /** The saved address, in the app's own files. */
    fun file(context: Context) = File(context.filesDir, "mac.json")
}
