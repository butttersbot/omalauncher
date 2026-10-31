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
    private const val ACTION_RETRY_MS = 1_500L
    private const val TAG = "SomaMessages"

    /** The conversations fetched with their history when the stream (re)connects; the rest load when opened. */
    private const val PRIMED = 15

    /** What the link is doing, for the screens: empty when all is well. */
    val trouble = MutableStateFlow("")
    val typing = MutableStateFlow(TypingState())
    private val presence = Executors.newSingleThreadExecutor()
    private val actions = Executors.newSingleThreadExecutor()
    val previews = MutableStateFlow<Map<String, RichLink>>(emptyMap())
    private val previewIO = Executors.newFixedThreadPool(2)
    private val previewAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val previewRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var richLinksSupported = false
    /** The Mac answers a repeated clientId with its first answer, doing nothing twice. */
    @Volatile private var actionRepeatsSafe = false

    fun preview(url: String) {
        val cached = previews.value[url]
        val ttl = if (cached?.state == "ready") 86_400_000L else 300_000L
        if (!richLinksSupported || (cached != null && System.currentTimeMillis() - (previewAt[url] ?: 0L) < ttl) || previewRequests.size >= 4 || !previewRequests.add(url)) return
        val at = address ?: run { previewRequests.remove(url); return }
        previewIO.execute {
            try {
                val data = post(at, "/v1/link-previews", JSONObject().put("url", url), timeout = 10_000)
                val item = data.optJSONObject("preview")?.let { RichLink.read(it) }
                if (item != null && address == at) synchronized(this) {
                    previewAt[url] = System.currentTimeMillis()
                    previews.value = previews.value.entries.toList().takeLast(127).associate { it.toPair() } + (url to item)
                    previewAt.keys.retainAll(previews.value.keys)
                }
            } catch (_: Exception) { /* Plain URL remains usable; no send waits on metadata. */ }
            finally { previewRequests.remove(url) }
        }
    }
    @Volatile private var knownNames: Map<String, String> = emptyMap()
    @Volatile var searchSupported: Boolean = false
    @Volatile var threadsSupported: Boolean = false
        private set
    @Volatile var messageActionsSupported: Boolean = false
        private set
    @Volatile private var ownHandles: Set<String> = emptySet()
    private fun normalized(handle: String) = handle.removePrefix("tel:").removePrefix("mailto:").lowercase()

    @Synchronized fun expireTyping(now: Long = System.currentTimeMillis()) { typing.value = typing.value.expire(now) }
    @Synchronized private fun takeTyping(o: JSONObject) {
        val sender = o.optString("sender").takeUnless { o.isNull("sender") }.orEmpty()
        if (normalized(sender) in ownHandles) return
        val chat = o.optString("chatId").takeIf { it.isNotEmpty() } ?: return
        typing.value = typing.value.event(chat, sender, o.optString("senderName").takeUnless { o.isNull("senderName") || it.isBlank() } ?: sender.ifEmpty { "Someone" }, o.optBoolean("on"), System.currentTimeMillis())
    }
    fun typing(chat: String, on: Boolean) {
        val at = address ?: return
        presence.execute { runCatching {
            val c = open(at, "/v1/chats/${path(chat)}/typing", "POST", timeout = 3_000)
            try { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("on", on).toString().toByteArray()) }
                if (c.responseCode in 200..299) c.inputStream.close()
            } finally { c.disconnect() }
        } }
    }

    private val io = Executors.newFixedThreadPool(2)

    /** Sends, one at a time in the order made, so photos and words arrive as written. */
    private val sends = Executors.newSingleThreadExecutor()
    private var running = false
    private var address: MacAddress? = null
    private var app: Context? = null
    private val stream = MacWire.Stream()

    @Synchronized
    fun start(
        context: Context,
        to: MacAddress,
    ) {
        app = context.applicationContext
        if (address != to) { previews.value = emptyMap(); previewAt.clear(); richLinksSupported = false; actionRepeatsSafe = false; threadsSupported = false; searchSupported = false; messageActionsSupported = false; ownHandles = emptySet(); knownNames = emptyMap(); typing.value = TypingState() }
        address = to
        if (running) return
        running = true
        thread(name = "mac-link", isDaemon = true) { loop() }
    }

    /** Everything again, now that names can come from the phone's contacts. */
    fun refresh() {
        Names.clear()
        val at = address ?: return
        io.execute { runCatching { sync(at) }.onFailure { Log.w(TAG, "refresh", it) } }
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
                typing.value = TypingState()
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
            connectTimeout = minOf(10_000, timeout)
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
            if (c.responseCode !in 200..299) throw refusal(c)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Posts [body] and answers with what came back; a {ok:false} answer is a failure with the service's words. */
    private fun post(
        at: MacAddress,
        path: String,
        body: JSONObject = JSONObject(),
        timeout: Int = 60_000,
    ): JSONObject {
        val c = open(at, path, "POST", timeout = timeout)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray()
            if (body.has("clientId")) {
                // Android's HTTP stack silently posts a buffered body again when a connection dies before the answer,
                // and the Mac has often sent it by then: a doubled real message. A streamed body is never repeated.
                c.setFixedLengthStreamingMode(bytes.size)
            }
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode !in 200..299) throw refusal(c)
            return answer(c)
        } finally {
            c.disconnect()
        }
    }

    private fun answer(c: HttpURLConnection): JSONObject {
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (!o.optBoolean("ok", true)) throw MacRefusal.of(c.responseCode, o.optString("error").ifEmpty { "Your Mac said no." }, o.optString("code"))
        return o
    }

    /** The service's own words when it says no: {ok:false, error, code}. */
    /**
     * A tapback, edit, unsend or read mark, which isn't left for him to try again as a send is. When no answer comes and
     * the Mac honours ids, asks once more with the same id: done already, it says so; not yet, it is done once.
     */
    private fun postAction(at: MacAddress, path: String, body: JSONObject): JSONObject =
        try {
            post(at, path, body, timeout = 10_000)
        } catch (e: java.io.IOException) {
            if (!actionRepeatsSafe || !body.has("clientId")) throw e
            Thread.sleep(ACTION_RETRY_MS)
            post(at, path, body, timeout = 10_000)
        }

    private fun refusal(c: HttpURLConnection): MacRefusal {
        val text = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val o = runCatching { JSONObject(text) }.getOrNull()
        return MacRefusal.of(c.responseCode, o?.optString("error"), o?.optString("code"))
    }

    private fun path(id: String) = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun nameOf(handle: String): String? = app?.let { Names.of(it, handle) } ?: knownNames[normalized(handle)]

    private fun messagesOf(m: MacWire.Msg, page: List<MacWire.Msg> = emptyList()): List<Message> {
        val book = Messages.book.value[m.chatId]
        return MacWire.toMessages(
            m,
            nameOf = ::nameOf,
            quoteOf = { id ->
                page.firstOrNull { it.id == id }?.let { q -> (if (q.fromMe) "You" else q.sender?.let(::nameOf) ?: q.senderName ?: q.sender.orEmpty()) + ": " + MacWire.quoteText(q) }
                    ?: book?.messages?.lastOrNull { it.id == id || it.id == "$id#a" }?.let { q -> (if (q.fromMe) "You" else q.sender) + ": " + q.text.take(90) }
            },
        )
    }

    private fun title(c: MacWire.Chat) = c.name ?: c.participants.joinToString(", ") { nameOf(it) ?: it }.ifEmpty { null }

    private fun take(c: MacWire.Chat) = Messages.fromMac(c.id, title(c), c.isGroup, c.last?.let(::messagesOf).orEmpty(), c.unread)

    /** The chats, and the latest messages of the first few. */
    private fun sync(at: MacAddress) {
        runCatching {
            val health = JSONObject(get(at, "/v1/health"))
            richLinksSupported = health.optJSONObject("capabilities")?.optBoolean("richLinks") == true
            actionRepeatsSafe = health.optJSONObject("capabilities")?.optBoolean("clientIdDeduplication") == true
            searchSupported = health.optJSONObject("capabilities")?.optBoolean("conversationSearch") == true
            threadsSupported = health.optJSONObject("capabilities")?.optString("replyThreads") == "paged-history-scan"
            messageActionsSupported = health.optJSONObject("capabilities")?.optBoolean("guardedMessageActions") == true && health.optBoolean("sendReady")
            val handles = health.optJSONObject("account")?.optJSONArray("handles")
            ownHandles = if (handles == null) emptySet() else (0 until handles.length()).map { normalized(handles.optString(it)) }.toSet()
        }
        val chats = MacWire.chats(get(at, "/v1/chats?limit=50"))
        knownNames = MacWire.participantNames(chats).mapKeys { normalized(it.key) }
        chats.forEach(::take)
        chats.take(PRIMED).forEach { c -> history(at, c.id) }
        trouble.value = ""
    }

    private fun history(
        at: MacAddress,
        id: String,
    ) {
        val ms = MacWire.messages(get(at, "/v1/chats/${path(id)}/messages?limit=50"))
        // Oldest first, so a reply finds the message it quotes.
        Messages.fromMac(id, null, null, ms.flatMap { messagesOf(it, ms) }, unread = null, counting = false)
    }

    /** A page of [chat]'s history before [before]; [done] says whether there was any it didn't have. */
    fun earlier(
        chat: String,
        before: Long,
        done: (Boolean) -> Unit,
    ) {
        val at = address ?: return done(false)
        io.execute {
            val more =
                runCatching {
                    val had = Messages.book.value[chat]?.messages?.size ?: 0
                    // Oldest first, as history is, so a reply finds the message it quotes. A failed fetch counts as more to come.
                    val page = MacWire.messages(get(at, "/v1/chats/${path(chat)}/messages?limit=50&before=$before"))
                    Messages.fromMac(chat, null, null, page.flatMap { messagesOf(it, page) }, unread = null, counting = false)
                    (Messages.book.value[chat]?.messages?.size ?: 0) > had
                }.onFailure { Log.w(TAG, "earlier", it) }.getOrDefault(true)
            done(more)
        }
    }

    /** Holds the event stream open until it drops. */
    private fun listen(at: MacAddress) {
        // The service sends a keep-alive every 20 s; silence for longer than this is a dead link.
        val c = open(at, "/v1/events", timeout = 90_000)
        c.setRequestProperty("Accept", "text/event-stream")
        stream.lastId?.let { c.setRequestProperty("Last-Event-ID", it) }
        try {
            if (c.responseCode !in 200..299) throw refusal(c)
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
            "typing" -> takeTyping(JSONObject(e.data))
            "message.new", "message.updated" -> {
                val m = MacWire.msg(JSONObject(e.data))
                if (!m.fromMe) { synchronized(this) { typing.value = typing.value.copy(people = typing.value.people.filterNot { it.chat == m.chatId && (m.sender == null || normalized(it.sender) == normalized(m.sender)) }) } }
                val known = Messages.book.value[m.chatId] != null
                Messages.fromMac(m.chatId, null, null, messagesOf(m), counting = e.type == "message.new" && !m.unsent && !m.edited,
                    notify = e.type == "message.new" && !m.unsent && !m.edited,
                    transientUpdate = e.type == "message.updated" || m.unsent || m.edited)
                // A conversation not seen before: its name and people come with the chat list.
                if (!known) io.execute { runCatching { sync(at) } }
            }
            "message.deleted" -> {
                val o = JSONObject(e.data)
                Messages.removed(o.getString("chatId"), listOf(o.getString("id")))
            }
            "chat.updated" -> take(MacWire.chat(JSONObject(e.data)))
            // The events since the last one seen are gone (the service restarted): everything again.
            "resync" -> {
                stream.lastId = null
                sync(at)
            }
        }
    }

    /** Sends [text] to [chat], as a threaded reply to [replyTo] when given; [onFail] when the Mac doesn't take it. */
    fun send(
        chat: String,
        text: String,
        replyTo: String?,
        clientId: String,
        hiddenLinks: Set<String> = emptySet(),
        onAccepted: () -> Unit = {},
        onFail: (ActionFailure) -> Unit,
    ) {
        val at = address ?: return onFail(ActionFailure("Not linked to a Mac.", Outcome.Refused))
        sends.execute {
            runCatching {
                val body = JSONObject().put("text", text).put("clientId", clientId)
                replyTo?.let { body.put("replyTo", it) }
                linkPreviewSelection(text, hiddenLinks)?.let { body.put("linkPreview", it) }
                post(at, "/v1/chats/${path(chat)}/messages", body)
                onAccepted()
            }.onFailure {
                Log.w(TAG, "sending", it)
                onFail(ActionFailure(reason(at, it), MacRefusal.outcomeOf(it)))
            }
        }
    }

    /** Sends one [photo] to [chat] as [clientId]; [onFail] with the Mac's reason when it doesn't take it. */
    fun sendFile(
        context: Context,
        chat: String,
        photo: Uri,
        clientId: String,
        replyTo: String? = null,
        onAccepted: () -> Unit = {},
        onFail: (ActionFailure) -> Unit,
    ) {
        val at = address ?: return onFail(ActionFailure("Not linked to a Mac.", Outcome.Refused))
        val target = Replies.target(replyTo)
        if (replyTo != null && target == null) return onFail(ActionFailure("Reply target is not ready.", Outcome.Refused))
        val resolver = context.contentResolver
        sends.execute {
            runCatching {
                val type = resolver.getType(photo) ?: "image/jpeg"
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
                val boundary = "soma" + System.nanoTime()
                val c = open(at, "/v1/chats/${path(chat)}/attachments", "POST", timeout = 120_000)
                try {
                    c.doOutput = true
                    c.setChunkedStreamingMode(64 * 1024)
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    c.outputStream.buffered().use { out ->
                        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"clientId\"\r\n\r\n$clientId\r\n".toByteArray())
                        target?.let { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"replyTo\"\r\n\r\n$it\r\n".toByteArray()) }
                        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.$ext\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
                        resolver.openInputStream(photo)!!.use { it.copyTo(out) }
                        out.write("\r\n--$boundary--\r\n".toByteArray())
                    }
                    if (c.responseCode !in 200..299) throw refusal(c)
                    answer(c)
                    onAccepted()
                } finally {
                    c.disconnect()
                }
            }.onFailure {
                Log.w(TAG, "sending a photo", it)
                onFail(ActionFailure(reason(at, it), MacRefusal.outcomeOf(it)))
            }
        }
    }

    /**
     * A voice message recorded here, [caf] (Opus in CAF, as an iPhone records them), to [chat]: sent as "Audio
     * Message.caf" with voiceNote=1, so the Mac sends it as a voice message an iPhone plays in the conversation rather
     * than as a file (Boss, 2026-10-24). A Mac that doesn't know voiceNote yet sends it as an audio file.
     */
    fun sendVoice(
        chat: String,
        caf: java.io.File,
        clientId: String,
        replyTo: String? = null,
        onAccepted: () -> Unit = {},
        onFail: (ActionFailure) -> Unit,
    ) {
        val at = address ?: return onFail(ActionFailure("Not linked to a Mac.", Outcome.Refused))
        val target = Replies.target(replyTo)
        if (replyTo != null && target == null) return onFail(ActionFailure("Reply target is not ready.", Outcome.Refused))
        sends.execute {
            runCatching {
                val boundary = "soma" + System.nanoTime()
                val c = open(at, "/v1/chats/${path(chat)}/attachments", "POST", timeout = 120_000)
                try {
                    c.doOutput = true
                    c.setChunkedStreamingMode(64 * 1024)
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    c.outputStream.buffered().use { out ->
                        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"clientId\"\r\n\r\n$clientId\r\n".toByteArray())
                        target?.let { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"replyTo\"\r\n\r\n$it\r\n".toByteArray()) }
                        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"voiceNote\"\r\n\r\n1\r\n".toByteArray())
                        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"Audio Message.caf\"\r\nContent-Type: audio/x-caf\r\n\r\n".toByteArray())
                        caf.inputStream().use { it.copyTo(out) }
                        out.write("\r\n--$boundary--\r\n".toByteArray())
                    }
                    if (c.responseCode !in 200..299) throw refusal(c)
                    answer(c)
                    onAccepted()
                } finally {
                    c.disconnect()
                }
            }.onFailure {
                Log.w(TAG, "sending a voice message", it)
                onFail(ActionFailure(reason(at, it), MacRefusal.outcomeOf(it)))
            }
        }
    }

    /** Why a send failed, in words: the Mac's own, and that it is locked when it is (what Messages can't do then). */
    private fun reason(
        at: MacAddress,
        e: Throwable,
    ): String {
        if (e is MacRefusal && e.theMacs) return e.message.orEmpty()
        val locked = runCatching { JSONObject(get(at, "/v1/health")).optBoolean("screenLocked") }.getOrDefault(false)
        return if (locked) "your Mac is locked. Unlock it and tap to try again." else e.message.orEmpty().ifEmpty { "your Mac didn't take it." }
    }

    /** A tapback of [kind] on message [id] in [chat], or off again with [remove]; [onFail] when the Mac doesn't take it. */
    fun tapback(
        chat: String,
        id: String,
        kind: String,
        remove: Boolean,
        clientId: String,
        onFail: (ActionFailure) -> Unit,
    ) {
        val at = address ?: return onFail(ActionFailure("Not linked to a Mac.", Outcome.Refused))
        actions.execute {
            runCatching { postAction(at, "/v1/messages/${path(id)}/tapback", JSONObject().put("kind", kind).put("remove", remove).put("chatId", chat).put("clientId", clientId)) }
                .onFailure {
                    Log.w(TAG, "tapback", it)
                    onFail(ActionFailure(it.message.orEmpty(), MacRefusal.outcomeOf(it)))
                }
        }
    }

    /** With [clientId] for one he asked for. Opening a chat sends none: it is harmless twice, and would use up the Mac's list of recent actions. */
    fun setRead(chat: String, read: Boolean, clientId: String? = null, onDone: (String?) -> Unit = {}) {
        val at = address ?: return onDone("Not linked to a Mac.")
        actions.execute {
            val failure = runCatching { postAction(at, "/v1/chats/${path(chat)}/${if (read) "read" else "unread"}", JSONObject().apply { clientId?.let { put("clientId", it) } }) }.exceptionOrNull()
            onDone(failure?.let { it.message ?: "Your Mac did not take the action." })
        }
    }

    /** Dedicated normalized read; never a local-only filter or raw debug API. */
    fun replyThread(chat: String, root: String, cursor: String? = null, onDone: (Result<ThreadSlice>) -> Unit) {
        val at = address ?: return onDone(Result.failure(IllegalStateException("Not linked to a Mac.")))
        if (!threadsSupported) return onDone(Result.failure(IllegalStateException("Thread viewing isn't available from this Mac yet.")))
        io.execute {
            val result = runCatching {
                val query = cursor?.let { "&cursor=${path(it)}" }.orEmpty()
                ThreadSlice.read(JSONObject(get(at, "/v1/chats/${path(chat)}/threads/${path(root)}?limit=50$query")), ::messagesOf)
            }.recoverCatching { throw IllegalStateException(conversationProblem(reason(at, it))) }
            onDone(result)
        }
    }

    fun search(chat: String, query: String, cursor: String?, onDone: (Result<SearchPage>) -> Unit) {
        val at = address ?: return onDone(Result.failure(IllegalStateException("Not linked to a Mac.")))
        if (!searchSupported) return onDone(Result.failure(IllegalStateException("Conversation search isn't available from this Mac yet.")))
        io.execute {
            onDone(runCatching {
                SearchPage.read(JSONObject(get(at, "/v1/chats/${path(chat)}/search?q=${path(query)}&limit=50" +
                    cursor?.let { "&cursor=${path(it)}" }.orEmpty())))
            }.recoverCatching { throw IllegalStateException(conversationProblem(reason(at, it))) })
        }
    }

    fun contextWindow(chat: String, message: String, onDone: (Result<ContextWindow>) -> Unit) {
        val at = address ?: return onDone(Result.failure(IllegalStateException("Not linked to a Mac.")))
        if (!threadsSupported) return onDone(Result.failure(IllegalStateException("Message context isn't available from this Mac yet.")))
        io.execute {
            onDone(runCatching {
                val response = JSONObject(get(at, "/v1/messages/${path(message)}/context?chatId=${path(chat)}&limit=40"))
                val records = response.getJSONArray("messages").let { a -> (0 until a.length()).map { MacWire.msg(a.getJSONObject(it)) } }
                ContextWindow(records.flatMap { messagesOf(it, records) }, response.optBoolean("hasOlder"), response.optBoolean("hasNewer"))
            }.recoverCatching { throw IllegalStateException(conversationProblem(reason(at, it))) })
        }
    }

    fun messageContext(chat: String, message: String, onDone: (Result<List<Message>>) -> Unit) {
        val at = address ?: return onDone(Result.failure(IllegalStateException("Not linked to a Mac.")))
        if (!threadsSupported) return onDone(Result.failure(IllegalStateException("Message context isn't available from this Mac yet.")))
        io.execute {
            val result = runCatching {
                val response = JSONObject(get(at, "/v1/messages/${path(message)}/context?chatId=${path(chat)}&limit=40"))
                val records = response.getJSONArray("messages").let { a -> (0 until a.length()).map { MacWire.msg(a.getJSONObject(it)) } }
                records.flatMap { messagesOf(it, records) }
            }.recoverCatching { throw IllegalStateException(conversationProblem(reason(at, it))) }
            onDone(result)
        }
    }

    /** No fallback to the old unguarded endpoint: an old Mac must refuse rather than silently downgrade. */
    fun changeMessage(chat: String, message: String, text: String?, clientId: String, onDone: (ActionFailure?) -> Unit) {
        val at = address ?: return onDone(ActionFailure("Not linked to a Mac.", Outcome.Refused))
        if (!messageActionsSupported) return onDone(ActionFailure("Message changes aren't available from this Mac yet.", Outcome.Refused))
        actions.execute {
            val result = runCatching {
                val body = JSONObject().put("chatId", chat).put("clientId", clientId)
                text?.let { body.put("text", it) }
                postAction(at, "/v1/messages/${path(message)}/actions/${if (text == null) "unsend" else "edit"}", body)
            }
            onDone(result.exceptionOrNull()?.let { ActionFailure(conversationProblem(reason(at, it)), MacRefusal.outcomeOf(it)) })
        }
    }

    /** Read actions remain ordered when opening immediately after marking unread. */
    fun opened(chat: String) {
        val at = address ?: return
        setRead(chat, true)
        io.execute { runCatching { history(at, chat) }.onFailure { Log.w(TAG, "history", it) } }
    }

    /**
     * Attachment [id] from the Mac, kept in the app's cache (where its file provider can show it in another app); a
     * picture comes as JPEG, whatever it was taken as. Off the main thread; null when the Mac can't give it.
     */
    private val fileLocks = Array(64) { Any() }
    fun file(context: Context, id: String, picture: Boolean): File? =
        synchronized(fileLocks[(id.hashCode() and Int.MAX_VALUE) % fileLocks.size]) { fetchFile(context, id, picture) }

    private fun fetchFile(
        context: Context,
        id: String,
        picture: Boolean,
    ): File? {
        val at = address ?: return null
        val f = cached(context, id, picture)
        if (f.isFile && f.length() > 0) return f
        val dir = f.parentFile!!
        val name = f.name.removeSuffix(".jpg")
        val c = open(at, "/v1/attachments/${path(id)}" + if (picture) "?jpeg=1" else "", timeout = 60_000)
        try {
            if (c.responseCode !in 200..299) return null
            val tmp = File(dir, "$name.part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return if (tmp.renameTo(f)) f else null
        } catch (e: Exception) {
            Log.w(TAG, "attachment", e)
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Where attachment [id] is kept once fetched ([file]); it may not be there yet. */
    fun cached(
        context: Context,
        id: String,
        picture: Boolean,
    ): File {
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "shared/mac").apply { mkdirs() }
        return File(dir, name + if (picture) ".jpg" else "")
    }

    /** The saved address, in the app's own files. */
    fun file(context: Context) = File(context.filesDir, "mac.json")
}
