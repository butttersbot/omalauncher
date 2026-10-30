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
    /** Display context ("Mina: are we still on…"). The wire identity is kept separately in [replyTo]. */
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
    /** Tapbacks on it, from the Mac: each a kind ("love", "like"… or an emoji), "me:" before the owner's own. */
    val tapbacks: List<String> = emptyList(),
    /** The phone's id for one sent from here, as the Mac echoes it back: matches the waiting "c:" message. Not kept. */
    val clientId: String? = null,
    /** Stable across a local send becoming its server echo and later updates. */
    val uiKey: String? = null,
    val delivery: Delivery? = null,
    val richLinks: List<RichLink> = emptyList(),
    val hiddenLinks: Set<String> = emptySet(),
    val reactions: List<Reaction> = emptyList(),
    /** Exact server message/part ID answered by this message. Never reconstructed from quote text. */
    val replyTo: String? = null,
    val edited: Boolean = false,
    val editedAt: Long? = null,
    val editCount: Int? = null,
    val editHistory: List<MessageRevision> = emptyList(),
    val editHistoryComplete: Boolean = false,
    val unsent: Boolean = false,
    val unsentAt: Long? = null,
    val changes: AllowedChanges = AllowedChanges(),
) {
    val fromMe get() = sender.isEmpty()
    val renderKey get() = uiKey ?: id ?: "legacy:$time:$sender:$text"
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
     * keeping its quote: the copy is the owner's own, with the same text, from close to when it went. New messages from
     * others are counted, unless [counting] is off (history). [unread] is the Mac's count when it gave one: it starts a
     * conversation first seen, and later may only lower the count (read on the Mac or elsewhere), never raise it past what
     * came in. An update about the owner's own message leaves the count as it was (Boss, 2026-10-24: his own messages put
     * a red dot on the conversation; the Mac counts its unread sends too). Neither made nor cleared by an outgoing one.
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
            if (m.unsent) {
                if (m.id == null) continue
                val replaced = messages.firstOrNull { it.id?.removeSuffix("#a") == m.id }
                messages.removeAll { it.id?.removeSuffix("#a") == m.id }
                messages += m.copy(uiKey = replaced?.uiKey ?: replaced?.renderKey ?: m.uiKey, replyTo = m.replyTo ?: replaced?.replyTo)
                continue
            }
            // A stale history page must never resurrect the contents or media of an unsent part.
            if (messages.any { it.unsent && it.id == m.id?.removeSuffix("#a") }) continue
            val at = messages.indexOfFirst { it.id == m.id }
            if (at >= 0) {
                val prior = messages[at]
                val staleEdit = prior.editedAt != null && (m.editedAt == null || m.editedAt < prior.editedAt) ||
                    prior.editCount != null && m.editCount != null && m.editCount < prior.editCount
                if (staleEdit) {
                    messages[at] = prior.copy(delivery = prior.delivery?.keeping(m.delivery) ?: m.delivery)
                    continue
                }
                messages[at] = m.copy(quote = m.quote ?: messages[at].quote, replyTo = m.replyTo ?: messages[at].replyTo, uiKey = messages[at].uiKey, hiddenLinks = messages[at].hiddenLinks, delivery = messages[at].delivery?.keeping(m.delivery) ?: m.delivery, richLinks = m.richLinks.ifEmpty { messages[at].richLinks.filter { it.url in Parts.links(m.text).map(Parts::openable) } })
                continue
            }
            // The Mac's copy of one sent from here: by the client id it echoes, else by the same words close in time.
            // A "failed" one counts too: a send that timed out here can still have gone (the Mac asleep, then awake),
            // and its "Not sent" stayed beside the real message (Boss, 2026-10-23: errors stay on earlier messages).
            val echo =
                if (m.fromMe) {
                    m.clientId?.let { c -> messages.indexOfFirst { it.id == "c:$c" } }?.takeIf { it >= 0 }
                        ?: messages.indexOfFirst { it.id?.startsWith("c:") == true && it.text == m.text && kotlin.math.abs(it.time - m.time) <= (if (it.failed) FAILED_ECHO_MS else ECHO_MS) }
                } else {
                    -1
                }
            if (echo >= 0) {
                messages[echo] = m.copy(quote = m.quote ?: messages[echo].quote, replyTo = m.replyTo ?: messages[echo].replyTo, uiKey = messages[echo].renderKey, hiddenLinks = messages[echo].hiddenLinks, delivery = m.delivery ?: messages[echo].delivery, richLinks = m.richLinks.ifEmpty { messages[echo].richLinks.filter { it.url in Parts.links(m.text).map(Parts::openable) } })
                continue
            }
            messages += m
            if (!m.fromMe && counting) fresh++
        }
        val targets = messages.filter { it.id != null }.associateBy { it.id!!.removeSuffix("#a") }
        for (i in messages.indices) {
            val item = messages[i]
            val target = item.replyTo?.let(targets::get) ?: continue
            if (!item.unsent) messages[i] = item.copy(quote = if (target.unsent) "Message was unsent" else
                (if (target.fromMe) "You" else target.sender) + ": " + target.text.take(90))
        }
        messages.sortBy { it.time }
        val counted = (old?.unread ?: 0) + fresh
        val mine = incoming.lastOrNull()?.fromMe == true
        val conversation =
            Conversation(
                id = id,
                title = title?.takeIf { it.isNotBlank() } ?: old?.title ?: id,
                isGroup = isGroup ?: old?.isGroup ?: false,
                messages = messages.takeLast(MAX_MESSAGES),
                unread =
                    when {
                        unread == null || mine -> counted
                        old == null -> unread
                        else -> minOf(unread, counted)
                    },
            )
        return ConversationBook(byId + (id to conversation))
    }

    fun quoteFacts(id: String, facts: List<Message>): ConversationBook {
        val old = byId[id] ?: return this
        val targets = (facts + old.messages).associateBy { it.id?.removeSuffix("#a") }
        val updated = old.messages.map { item ->
            val target = targets[item.replyTo]
            if (target == null || item.unsent) item else item.copy(quote = if (target.unsent) "Message was unsent" else
                (if (target.fromMe) "You" else target.sender) + ": " + target.text.take(90))
        }
        return ConversationBook(byId + (id to old.copy(messages = updated)))
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
        richLinks: List<RichLink> = emptyList(),
        hiddenLinks: Set<String> = emptySet(),
        replyTo: String? = null,
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages + Message(text, time, "", sentHere = true, quote = quote, id = "c:$clientId", delivery = Delivery.Sending, richLinks = richLinks, hiddenLinks = hiddenLinks, replyTo = replyTo))))
    }

    /** Message [messageId] in [id] stands for [files] (a photo picked here, until the Mac shows it back). */
    fun accepted(id: String, clientId: String): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages.map { m ->
            if (m.renderKey == "c:$clientId" && m.delivery == Delivery.Sending) m.copy(delivery = Delivery.Sent) else m
        })))
    }

    fun withFiles(
        id: String,
        messageId: String,
        files: List<String>,
    ): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(messages = old.messages.map { if (it.id == messageId) it.copy(files = files) else it })))
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

    /**
     * Only the conversations that came from the Mac: one whose messages all lack the Mac's ids came from OpenBubbles'
     * notifications, and would stand beside the Mac's own copy. The same book when there is nothing to drop.
     */
    fun fromMacOnly(): ConversationBook {
        val kept = byId.filterValues { c -> c.messages.isEmpty() || c.messages.any { it.id != null } }
        return if (kept.size == byId.size) this else ConversationBook(kept)
    }

    /** The owner has looked at [id]. */
    fun unreadCount(id: String, count: Int): ConversationBook {
        val old = byId[id] ?: return this
        return ConversationBook(byId + (id to old.copy(unread = count.coerceAtLeast(0))))
    }

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
                                            m.uiKey?.let { put("uiKey", it) }
                                            m.delivery?.let { put("delivery", it.name) }
                                            if (m.hiddenLinks.isNotEmpty()) put("hiddenLinks", JSONArray(m.hiddenLinks.toList()))
                                            if (m.richLinks.isNotEmpty()) put("richLinks", JSONArray(m.richLinks.map { it.json() }))
                                            m.quote?.let { put("quote", it) }
                                            m.replyTo?.let { put("replyTo", it) }
                                            if (m.edited) put("edited", true)
                                            m.editedAt?.let { put("editedAt", it) }
                                            m.editCount?.let { put("editCount", it) }
                                            if (m.editHistory.isNotEmpty()) put("editHistory", JSONArray(m.editHistory.map { it.json() }))
                                            if (m.editHistoryComplete) put("editHistoryComplete", true)
                                            if (m.unsent) put("unsent", true)
                                            m.unsentAt?.let { put("unsentAt", it) }
                                            if (m.handedOff) put("off", true)
                                            m.id?.let { put("id", it) }
                                            if (m.failed) put("failed", true)
                                            if (m.files.isNotEmpty()) put("files", JSONArray(m.files))
                                            if (m.reactions.isNotEmpty()) put("reactions", JSONArray(m.reactions.map { it.json() }))
                                            if (m.tapbacks.isNotEmpty()) put("tapbacks", JSONArray(m.tapbacks))
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

        /** How long after a send that failed here the Mac's copy may still show it went (a sleeping Mac, woken). */
        const val FAILED_ECHO_MS = 30 * 60_000L

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
                                m.optJSONArray("tapbacks")?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty(),
                                uiKey = m.optString("uiKey").ifEmpty { null },
                                delivery = runCatching { Delivery.valueOf(m.optString("delivery")) }.getOrNull(),
                                richLinks = RichLink.read(m.optJSONArray("richLinks")),
                                hiddenLinks = m.optJSONArray("hiddenLinks")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty(),
                                reactions = Reaction.read(m.optJSONArray("reactions")),
                                replyTo = m.optString("replyTo").ifEmpty { null },
                                edited = m.optBoolean("edited"), editedAt = m.optLong("editedAt").takeIf { m.has("editedAt") },
                                editCount = m.optInt("editCount").takeIf { m.has("editCount") },
                                editHistory = MessageRevision.read(m.optJSONArray("editHistory")), editHistoryComplete = m.optBoolean("editHistoryComplete"),
                                unsent = m.optBoolean("unsent"), unsentAt = m.optLong("unsentAt").takeIf { m.has("unsentAt") },
                            )
                        }
                    val id = c.getString("id")
                    // Unread with no message from anyone else among those kept: the owner's own sends counted before 0.8.2.
                    val unread = if (messages.none { !it.fromMe }) 0 else c.optInt("unread")
                    byId[id] = Conversation(id, c.getString("title"), c.optBoolean("group"), messages, unread)
                }
                ConversationBook(byId)
            }.getOrDefault(ConversationBook())
    }
}
