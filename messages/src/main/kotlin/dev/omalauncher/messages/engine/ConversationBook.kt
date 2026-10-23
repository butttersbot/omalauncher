package dev.omalauncher.messages.engine

import org.json.JSONArray
import org.json.JSONObject

/** One message as Soma Messages keeps it. [sender] is the other person's name, or empty for the owner's own. */
data class Message(
    val text: String,
    val time: Long,
    val sender: String,
    /** Sent from this app; it stays until OpenBubbles shows it back, then the two are one message. */
    val sentHere: Boolean = false,
    /** The message this one answered ("Mina: are we still on…"), kept on this phone: OpenBubbles' reply action sends words only. */
    val quote: String? = null,
    /** Handed to OpenBubbles' own chat to send there (photos); it stays marked until OpenBubbles shows it back. */
    val handedOff: Boolean = false,
    /**
     * The Mac's id for it, when the conversation comes from the Mac (MacLink): "c:" and the client id while one sent
     * from here waits for the Mac to show it back.
     */
    val id: String? = null,
    /** Sent from here, but the Mac didn't take it. */
    val failed: Boolean = false,
    /** The Mac's attachments this message stands for ("2 Photos"), each "id|mime", to show them here. */
    val files: List<String> = emptyList(),
) {
    val fromMe get() = sender.isEmpty()
}

/** A conversation: what OpenBubbles has shown of it since Soma Messages started listening. */
data class Conversation(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val messages: List<Message>,
    val unread: Int,
) {
    val last get() = messages.lastOrNull()
    val lastTime get() = last?.time ?: 0L
}

/** A message as a notification shows it: [sender] null means the owner wrote it. */
data class SeenMessage(
    val text: String,
    val time: Long,
    val sender: String?,
)

/** What one OpenBubbles notification says about a conversation: its id (the chat's shortcut), title and recent messages. */
data class SeenConversation(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val messages: List<SeenMessage>,
)

/**
 * Every conversation seen so far, merged from OpenBubbles' notifications. A notification repeats the conversation's
 * recent messages each time it is posted, so a message already here is not counted twice; one the owner sent from this
 * app is matched to the copy OpenBubbles shows back.
 */
class ConversationBook(
    private val byId: Map<String, Conversation> = emptyMap(),
) {
    val conversations: List<Conversation> get() = byId.values.sortedByDescending { it.lastTime }

    operator fun get(id: String): Conversation? = byId[id]

    /** Merges what a notification shows. New messages from others count as unread. */
    fun take(seen: SeenConversation): ConversationBook {
        val old = byId[seen.id]
        val messages = old?.messages?.toMutableList() ?: mutableListOf()
        var fresh = 0
        for (m in seen.messages.sortedBy { it.time }) {
            val sender = m.sender.orEmpty()
            if (messages.any { it.time == m.time && it.text == m.text && it.sender == sender }) continue
            if (sender.isEmpty()) {
                // OpenBubbles showing back what was sent from here: the same words within a couple of minutes.
                val echo =
                    messages.indexOfFirst {
                        it.sentHere && it.text == m.text && (kotlin.math.abs(it.time - m.time) <= ECHO_MS || it.handedOff && m.time >= it.time - ECHO_MS && m.time - it.time <= HANDOFF_MS)
                    }
                if (echo >= 0) {
                    messages[echo] = Message(m.text, m.time, "", quote = messages[echo].quote)
                    continue
                }
            } else {
                fresh++
            }
            messages += Message(m.text, m.time, sender)
        }
        messages.sortBy { it.time }
        val conversation =
            Conversation(
                id = seen.id,
                title = seen.title.ifBlank { old?.title ?: seen.id },
                isGroup = seen.isGroup,
                messages = messages.takeLast(MAX_MESSAGES),
                unread = (old?.unread ?: 0) + fresh,
            )
        return ConversationBook(byId + (seen.id to conversation))
    }

    /** The owner sent [text] from this app at [time]. */
    fun sent(
        id: String,
        text: String,
        time: Long,
        quote: String? = null,
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages + Message(text, time, "", sentHere = true, quote = quote))))
    }

    /** The owner handed [text] to OpenBubbles' own chat [id] at [time], to send there. */
    fun handedOff(
        id: String,
        text: String,
        time: Long,
        quote: String? = null,
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages + Message(text, time, "", sentHere = true, quote = quote, handedOff = true))))
    }

    /** Takes back a handed-off message the owner didn't send after all. */
    fun drop(
        id: String,
        message: Message,
    ): ConversationBook {
        val old = byId[id] ?: return this
        if (!message.handedOff) return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages - message)))
    }

    /**
     * What the Mac says about conversation [id]: its name, and [incoming] messages, each with the Mac's id. One already
     * here is replaced. One the owner sent from here (`c:`, waiting) gives way to the Mac's copy of the same words,
     * keeping its quote: the copy is the owner's own, with the same text, from close to when it went. [unread] is the
     * Mac's count when it gave one, else new messages from others are counted, unless [counting] is off (history).
     */
    fun fromMac(
        id: String,
        title: String?,
        isGroup: Boolean?,
        incoming: List<Message>,
        unread: Int? = null,
        counting: Boolean = true,
    ): ConversationBook {
        val old = byId[id]
        val messages = old?.messages?.toMutableList() ?: mutableListOf()
        var fresh = 0
        for (m in incoming) {
            val at = messages.indexOfFirst { it.id == m.id }
            if (at >= 0) {
                messages[at] = m.copy(quote = m.quote ?: messages[at].quote)
                continue
            }
            val echo =
                if (m.fromMe) {
                    messages.indexOfFirst { it.id?.startsWith("c:") == true && !it.failed && it.text == m.text && kotlin.math.abs(it.time - m.time) <= ECHO_MS }
                } else {
                    -1
                }
            if (echo >= 0) {
                messages[echo] = m.copy(quote = m.quote ?: messages[echo].quote)
                continue
            }
            messages += m
            if (!m.fromMe && counting) fresh++
        }
        messages.sortBy { it.time }
        val conversation =
            Conversation(
                id = id,
                title = title?.takeIf { it.isNotBlank() } ?: old?.title ?: id,
                isGroup = isGroup ?: old?.isGroup ?: false,
                messages = messages.takeLast(MAX_MESSAGES),
                unread = unread ?: ((old?.unread ?: 0) + fresh),
            )
        return ConversationBook(byId + (id to conversation))
    }

    /** The Mac took back messages [ids] in [id] (undo send); an attachment's part ("#a") goes with its message. */
    fun removed(
        id: String,
        ids: Collection<String>,
    ): ConversationBook {
        val old = byId[id] ?: return this
        val gone = ids.flatMap { listOf(it, "$it#a") }.toSet()
        return ConversationBook(byId + (id to old.copy(messages = old.messages.filter { it.id !in gone })))
    }

    /** The owner sent [text] from here through the Mac, as [clientId]; it waits until the Mac shows it back. */
    fun waiting(
        id: String,
        clientId: String,
        text: String,
        time: Long,
        quote: String? = null,
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages + Message(text, time, "", sentHere = true, quote = quote, id = "c:$clientId"))))
    }

    /** The Mac didn't take [clientId]'s messages. */
    fun failed(
        id: String,
        clientId: String,
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages.map { if (it.id == "c:$clientId") it.copy(failed = true) else it })))
    }

    /** Takes [message] out of [id]: a failed send about to be tried again. */
    fun without(
        id: String,
        message: Message,
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages - message)))
    }

    /** The owner has looked at [id]. */
    fun read(id: String): ConversationBook {
        val old = byId[id] ?: return this
        if (old.unread == 0) return this
        return ConversationBook(byId + (id to old.copy(unread = 0)))
    }

    fun toJson(): String =
        JSONObject()
            .put("version", 1)
            .put(
                "conversations",
                JSONArray(
                    byId.values.map { c ->
                        JSONObject().put("id", c.id).put("title", c.title).put("group", c.isGroup).put("unread", c.unread)
                            .put(
                                "messages",
                                JSONArray(
                                    c.messages.map { m ->
                                        JSONObject().put("text", m.text).put("time", m.time).put("sender", m.sender).put("here", m.sentHere).apply {
                                            m.quote?.let { put("quote", it) }
                                            if (m.handedOff) put("off", true)
                                            m.id?.let { put("id", it) }
                                            if (m.failed) put("failed", true)
                                            if (m.files.isNotEmpty()) put("files", JSONArray(m.files))
                                        }
                                    },
                                ),
                            )
                    },
                ),
            ).toString()

    companion object {
        /** How far apart a message sent from here and OpenBubbles' copy of it may be. */
        const val ECHO_MS = 2 * 60_000L

        /**
         * How long a handed-off message waits for OpenBubbles to show it back. OpenBubbles reposts a conversation only
         * when something arrives, so its copy may come much later than a reply's.
         */
        const val HANDOFF_MS = 12 * 60 * 60_000L

        /** The most messages kept per conversation. */
        const val MAX_MESSAGES = 2_000

        /** The book saved by [toJson]; an unreadable file is an empty book, never a crash. */
        fun fromJson(text: String): ConversationBook =
            runCatching {
                val list = JSONObject(text).getJSONArray("conversations")
                val byId = LinkedHashMap<String, Conversation>()
                for (i in 0 until list.length()) {
                    val c = list.getJSONObject(i)
                    val ms = c.getJSONArray("messages")
                    val messages =
                        (0 until ms.length()).map { j ->
                            val m = ms.getJSONObject(j)
                            Message(
                                m.getString("text"),
                                m.getLong("time"),
                                m.getString("sender"),
                                m.optBoolean("here"),
                                m.optString("quote").ifEmpty { null },
                                m.optBoolean("off"),
                                m.optString("id").ifEmpty { null },
                                m.optBoolean("failed"),
                                m.optJSONArray("files")?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty(),
                            )
                        }
                    val id = c.getString("id")
                    byId[id] = Conversation(id, c.getString("title"), c.optBoolean("group"), messages, c.optInt("unread"))
                }
                ConversationBook(byId)
            }.getOrDefault(ConversationBook())
    }
}
