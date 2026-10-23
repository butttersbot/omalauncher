package dev.omalauncher.messages.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Mac's iMessage service says, read: its chats, its messages and its stream of events (Server-Sent Events).
 * The service runs beside Messages on Boss's Mac (Beeper's platform-imessage); MacLink asks it, this reads the answers.
 * Nothing here touches the network, so all of it is tested on the JVM.
 */
object MacWire {
    class Attachment(
        val id: String,
        val name: String,
        val mime: String,
    )

    class Msg(
        val id: String,
        val chatId: String,
        val fromMe: Boolean,
        val sender: String?,
        val senderName: String?,
        val text: String,
        val at: Long,
        val attachments: List<Attachment>,
        /** The phone's own id for a message it sent, echoed back once the Mac has it. */
        val clientId: String?,
    )

    class Chat(
        val id: String,
        val name: String,
        val isGroup: Boolean,
        val unread: Int,
        val last: Msg?,
    )

    /** One event off the stream: [type] is message.new, message.updated, chat.updated or typing. */
    data class Event(
        val id: String?,
        val type: String,
        val data: String,
    )

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    fun msg(o: JSONObject): Msg {
        val list = o.optJSONArray("attachments") ?: JSONArray()
        val attachments =
            (0 until list.length()).map { i ->
                val a = list.getJSONObject(i)
                Attachment(a.getString("id"), a.optString("name"), a.optString("mime"))
            }
        return Msg(
            id = o.getString("id"),
            chatId = o.getString("chatId"),
            fromMe = o.optBoolean("fromMe"),
            sender = o.str("sender"),
            senderName = o.str("senderName"),
            text = o.optString("text"),
            at = o.getLong("at"),
            attachments = attachments,
            clientId = o.str("clientId"),
        )
    }

    fun chats(text: String): List<Chat> {
        val list = JSONObject(text).getJSONArray("chats")
        return (0 until list.length()).map { i ->
            val c = list.getJSONObject(i)
            val participants = c.optJSONArray("participants") ?: JSONArray()
            Chat(
                id = c.getString("id"),
                name = c.str("name") ?: (0 until participants.length()).joinToString(", ") { participants.getString(it) },
                isGroup = c.optBoolean("isGroup"),
                unread = c.optInt("unread"),
                last = if (c.isNull("lastMessage") || !c.has("lastMessage")) null else msg(c.getJSONObject("lastMessage")),
            )
        }
    }

    fun messages(text: String): List<Msg> {
        val list = JSONObject(text).getJSONArray("messages")
        return (0 until list.length()).map { msg(list.getJSONObject(it)) }
    }

    /** What the attachments are, worded the way the app shows them: "1 Photo", "2 Photos & 1 Video". */
    fun summary(attachments: List<Attachment>): String? {
        if (attachments.isEmpty()) return null
        val kinds =
            attachments.map {
                when {
                    it.mime == "image/gif" -> "GIF"
                    it.mime.startsWith("image/") -> "Photo"
                    it.mime.startsWith("video/") -> "Video"
                    it.mime.startsWith("audio/") -> "Audio message"
                    it.mime == "application/pdf" -> "PDF"
                    else -> "File"
                }
            }
        return kinds.distinct().joinToString(" & ") { k ->
            val n = kinds.count { it == k }
            "$n $k${if (n > 1) "s" else ""}"
        }
    }

    /**
     * [m] as the app keeps it: its attachments as one message of their own ("2 Photos"), before its words, as iMessage
     * shows them. The words keep the Mac's id; the attachments' message adds "#a" to it.
     */
    fun toMessages(m: Msg): List<Message> {
        val sender = if (m.fromMe) "" else m.senderName ?: m.sender ?: "Unknown"
        return listOfNotNull(
            summary(m.attachments)?.let { Message(it, m.at, sender, id = m.id + "#a") },
            m.text.takeIf { it.isNotBlank() }?.let { Message(it, m.at, sender, id = m.id) },
        )
    }

    /** Reads the event stream a line at a time; a blank line ends an event, which is then answered. */
    class Stream {
        private var id: String? = null
        private var type = "message"
        private val data = StringBuilder()

        /** The id of the last whole event, to resume after it (Last-Event-ID). */
        var lastId: String? = null
            private set

        /** Drops a half-read event, after the stream broke; [lastId] stays, to resume from. */
        fun reset() {
            id = null
            type = "message"
            data.setLength(0)
        }

        fun line(line: String): Event? {
            if (line.isEmpty()) {
                if (data.isEmpty()) {
                    type = "message"
                    return null
                }
                val e = Event(id, type, data.toString())
                if (id != null) lastId = id
                id = null
                type = "message"
                data.setLength(0)
                return e
            }
            // A comment: the service's keep-alive.
            if (line.startsWith(":")) return null
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "id" -> id = value
                "event" -> type = value
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
            }
            return null
        }
    }
}
