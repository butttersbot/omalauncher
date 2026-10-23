package dev.omalauncher.messages.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Mac's iMessage service says, read. The service (soma-imessage-service, on Boss's Mac, over Beeper's
 * platform-imessage) serves the phone's own contract: chats, messages and a stream of events (Server-Sent Events).
 * MacLink asks it; this reads the answers. Nothing here touches the network, so all of it is tested on the JVM.
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
        /** The sender's handle, a phone number or an email; null for the owner's own. */
        val sender: String?,
        /** The sender's name from the Mac's Contacts, when the Mac may read them. */
        val senderName: String?,
        val text: String,
        val at: Long,
        val attachments: List<Attachment>,
        /** The message this one replies to, in a threaded reply. */
        val replyTo: String?,
        /** The phone's own id for a message it sent, echoed back once the Mac has it. */
        val clientId: String?,
        val unsent: Boolean,
        /** A group change or rename, not something anyone wrote. */
        val system: Boolean,
        /** Tapbacks: each a kind, "me:" before the owner's own. */
        val tapbacks: List<String> = emptyList(),
    )

    class Chat(
        val id: String,
        /** The chat's own name; null for a 1:1 chat, named from the phone's contacts instead. */
        val name: String?,
        val participants: List<String>,
        val isGroup: Boolean,
        val unread: Int,
        val last: Msg?,
    )

    /** One event off the stream: message.new, message.updated, message.deleted, chat.updated, chat.deleted, typing, ready or resync. */
    data class Event(
        val id: String?,
        val type: String,
        val data: String,
    )

    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

    fun msg(o: JSONObject): Msg {
        val list = o.optJSONArray("attachments") ?: JSONArray()
        return Msg(
            id = o.getString("id"),
            chatId = o.str("chatId").orEmpty(),
            fromMe = o.optBoolean("fromMe"),
            sender = o.str("sender"),
            senderName = o.str("senderName"),
            text = o.optString("text"),
            at = o.optLong("at"),
            attachments =
                (0 until list.length()).map {
                    val a = list.getJSONObject(it)
                    Attachment(a.getString("id"), a.optString("name"), a.optString("mime"))
                },
            replyTo = o.str("replyTo"),
            clientId = o.str("clientId"),
            unsent = o.optBoolean("unsent"),
            system = o.optBoolean("system"),
            tapbacks =
                (o.optJSONArray("tapbacks") ?: JSONArray()).let { t ->
                    (0 until t.length()).mapNotNull { t.optJSONObject(it) }.map { (if (it.optBoolean("fromMe")) "me:" else "") + it.optString("kind") }.filter { !it.endsWith(":") && it.isNotEmpty() }
                },
        )
    }

    fun chat(c: JSONObject): Chat {
        val people = c.optJSONArray("participants") ?: JSONArray()
        return Chat(
            id = c.getString("id"),
            name = c.str("name"),
            participants = (0 until people.length()).map { people.getString(it) },
            isGroup = c.optBoolean("isGroup"),
            unread = c.optInt("unread"),
            last = c.optJSONObject("lastMessage")?.let(::msg),
        )
    }

    /** GET /v1/chats. */
    fun chats(text: String): List<Chat> {
        val list = JSONObject(text).getJSONArray("chats")
        return (0 until list.length()).map { chat(list.getJSONObject(it)) }
    }

    /** GET /v1/chats/{id}/messages, oldest first, each once (a page's boundary can repeat). */
    fun messages(text: String): List<Msg> {
        val list = JSONObject(text).getJSONArray("messages")
        return (0 until list.length()).map { msg(list.getJSONObject(it)) }.distinctBy { it.id }.sortedBy { it.at }
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
     * shows them. The words keep the Mac's id; the attachments' message adds "#a" to it. [nameOf] turns a handle into
     * the person's name; [quoteOf] gives the quote line for the message a reply answers, when it is known here. An
     * unsent message and a group change are nothing to show.
     */
    fun toMessages(
        m: Msg,
        nameOf: (String) -> String? = { null },
        quoteOf: (String) -> String? = { null },
    ): List<Message> {
        if (m.unsent || m.system) return emptyList()
        val sender = if (m.fromMe) "" else m.sender?.let { nameOf(it) } ?: m.senderName ?: m.sender ?: "Unknown"
        val quote = m.replyTo?.let(quoteOf)
        val parts =
            listOfNotNull(
                summary(m.attachments)?.let { Message(it, m.at, sender, id = m.id + "#a", files = m.attachments.map { a -> a.id + "|" + a.mime }) },
                // The object replacement character stands where an attachment sat in the words.
                m.text.replace("￼", "").trim().takeIf { it.isNotEmpty() }?.let { Message(it, m.at, sender, id = m.id) },
            )
        // The quote goes on the first part, the tapbacks on the last (under the message, as iMessage shows them).
        return parts.mapIndexed { i, p ->
            var q = if (i == 0 && quote != null) p.copy(quote = quote) else p
            if (i == parts.lastIndex && m.tapbacks.isNotEmpty()) q = q.copy(tapbacks = m.tapbacks)
            q
        }
    }

    /** Reads the event stream a line at a time; a blank line ends an event, which is then answered. */
    class Stream {
        private var id: String? = null
        private var type = "message"
        private val data = StringBuilder()

        /** The id of the last whole event, to resume after it (Last-Event-ID). */
        var lastId: String? = null

        /** Drops a half-read event, after the stream broke; [lastId] stays, to resume from. */
        fun reset() {
            id = null
            type = "message"
            data.setLength(0)
        }

        fun line(line: String): Event? {
            if (line.isEmpty()) {
                // An event with no data ("ready", "resync" may come so) still counts.
                if (data.isEmpty() && type == "message") return null
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
