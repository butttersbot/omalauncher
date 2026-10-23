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
                val echo = messages.indexOfFirst { it.sentHere && it.text == m.text && kotlin.math.abs(it.time - m.time) <= ECHO_MS }
                if (echo >= 0) {
                    messages[echo] = Message(m.text, m.time, "")
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
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages + Message(text, time, "", sentHere = true))))
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
                                        JSONObject().put("text", m.text).put("time", m.time).put("sender", m.sender).put("here", m.sentHere)
                                    },
                                ),
                            )
                    },
                ),
            ).toString()

    companion object {
        /** How far apart a message sent from here and OpenBubbles' copy of it may be. */
        const val ECHO_MS = 2 * 60_000L

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
                            Message(m.getString("text"), m.getLong("time"), m.getString("sender"), m.optBoolean("here"))
                        }
                    val id = c.getString("id")
                    byId[id] = Conversation(id, c.getString("title"), c.optBoolean("group"), messages, c.optInt("unread"))
                }
                ConversationBook(byId)
            }.getOrDefault(ConversationBook())
    }
}
