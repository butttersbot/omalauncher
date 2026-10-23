package dev.omalauncher.messages.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Mac's iMessage service says, read. The service (soma-imessage-service, on Boss's Mac) answers in Beeper's
 * platform-imessage shapes: threads and messages, and on its event stream (Server-Sent Events) the library's own
 * state_sync events. MacLink asks it; this reads the answers. Nothing here touches the network, so all of it is tested
 * on the JVM.
 */
object MacWire {
    class Attachment(
        val id: String,
        val name: String,
        val mime: String,
        /** Where the service serves it: a path to put after the base address. */
        val src: String?,
    )

    class Msg(
        val id: String,
        val chatId: String,
        val fromMe: Boolean,
        /** The sender's handle: a phone number or an email. */
        val sender: String?,
        val text: String,
        val at: Long,
        val attachments: List<Attachment>,
        /** The message this one replies to, in a threaded reply. */
        val replyTo: String?,
    )

    class Person(
        val handle: String,
        val name: String,
    )

    class Chat(
        val id: String,
        val title: String?,
        val isGroup: Boolean,
        val unread: Boolean,
        val people: List<Person>,
        val last: Msg?,
    ) {
        /** The chat's own name, or the people in it. */
        val name get() = title?.takeIf { it.isNotBlank() } ?: people.joinToString(", ") { it.name }.ifEmpty { id }
    }

    /** One event off the stream: [type] is imessage, ready or resync. */
    data class Event(
        val id: String?,
        val type: String,
        val data: String,
    )

    /** A platform-imessage state_sync event: what changed ([objectName], [mutation]) in which thread. */
    class Sync(
        val objectName: String,
        val mutation: String,
        val chatId: String,
        val entries: JSONArray,
    )

    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

    private fun items(o: JSONObject?): JSONArray = o?.optJSONArray("items") ?: JSONArray()

    /** A participant's name: as Contacts has it, else the handle. */
    fun person(o: JSONObject): Person {
        val handle = o.str("id") ?: o.str("phoneNumber") ?: o.str("email") ?: ""
        return Person(handle, o.str("fullName") ?: o.str("nickname") ?: o.str("displayText") ?: o.str("phoneNumber") ?: o.str("email") ?: handle)
    }

    fun attachment(a: JSONObject): Attachment {
        val name = a.str("fileName").orEmpty()
        val mime =
            a.str("mimeType") ?: when (a.str("type")) {
                "img" -> if (name.endsWith(".gif", true)) "image/gif" else "image/*"
                "video" -> "video/*"
                "audio" -> "audio/*"
                else -> mimeOf(name)
            }
        return Attachment(a.str("id") ?: name, name, mime, a.str("srcURL"))
    }

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "heic", "heif", "webp" -> "image/*"
            "gif" -> "image/gif"
            "mov", "mp4", "m4v" -> "video/*"
            "m4a", "caf", "mp3", "aac" -> "audio/*"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

    fun msg(o: JSONObject): Msg {
        val list = o.optJSONArray("attachments") ?: JSONArray()
        return Msg(
            id = o.getString("id"),
            chatId = o.str("threadID").orEmpty(),
            fromMe = o.optBoolean("isSender"),
            sender = o.str("senderID"),
            text = o.optString("text"),
            at = o.optLong("timestamp"),
            attachments = (0 until list.length()).map { attachment(list.getJSONObject(it)) },
            replyTo = o.str("linkedMessageID"),
        )
    }

    fun thread(o: JSONObject): Chat {
        val people = items(o.optJSONObject("participants")).let { p -> (0 until p.length()).map { person(p.getJSONObject(it)) } }
        val last = items(o.optJSONObject("messages")).let { m -> if (m.length() == 0) null else msg(m.getJSONObject(m.length() - 1)) }
        return Chat(
            id = o.getString("id"),
            title = o.str("title"),
            isGroup = o.optString("type") == "group",
            unread = o.optBoolean("isUnread"),
            people = people,
            last = last,
        )
    }

    /** GET /v1/threads. */
    fun chats(text: String): List<Chat> {
        val list = items(JSONObject(text))
        return (0 until list.length()).map { thread(list.getJSONObject(it)) }
    }

    /** GET /v1/threads/:thread/messages, oldest first. */
    fun messages(text: String): List<Msg> {
        val list = items(JSONObject(text))
        return (0 until list.length()).map { msg(list.getJSONObject(it)) }.sortedBy { it.at }
    }

    /** What a send answered: the sent messages, or nothing when the service only says it went ({"result":true}). */
    fun sent(text: String): List<Msg> =
        runCatching {
            val list = JSONArray(text)
            (0 until list.length()).map { msg(list.getJSONObject(it)) }
        }.getOrDefault(emptyList())

    /** An imessage event's data, when it is a state_sync. */
    fun sync(data: String): Sync? {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
        if (o.optString("type") != "state_sync") return null
        val chat = o.optJSONObject("objectIDs")?.str("threadID") ?: return null
        return Sync(o.optString("objectName"), o.optString("mutationType"), chat, o.optJSONArray("entries") ?: JSONArray())
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
     * the person's name; [quoteOf] gives the quote line for the message a reply answers, when it is known here.
     */
    fun toMessages(
        m: Msg,
        nameOf: (String) -> String? = { null },
        quoteOf: (String) -> String? = { null },
    ): List<Message> {
        val sender = if (m.fromMe) "" else m.sender?.let { nameOf(it) ?: it } ?: "Unknown"
        val quote = m.replyTo?.let(quoteOf)
        val parts =
            listOfNotNull(
                summary(m.attachments)?.let { Message(it, m.at, sender, id = m.id + "#a") },
                // The object replacement character stands where an attachment sat in the words.
                m.text.replace("￼", "").trim().takeIf { it.isNotEmpty() }?.let { Message(it, m.at, sender, id = m.id) },
            )
        return parts.mapIndexed { i, p -> if (i == 0 && quote != null) p.copy(quote = quote) else p }
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
