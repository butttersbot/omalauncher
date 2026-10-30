package dev.omalauncher.messages.engine

import org.json.JSONObject

/** One explicitly paged server thread. A partial or unavailable result never means “no replies”. */
data class ThreadSlice(
    val rootId: String,
    val messages: List<Message>,
    val nextCursor: String?,
    val complete: Boolean,
    val rootUnavailable: Boolean,
    val unresolved: Int,
) {
    fun adding(next: ThreadSlice) = if (rootId != next.rootId) next else next.copy(messages = merge(messages, next.messages))

    /** Preserve paging progress only when a fresh head overlaps known replies, not merely the repeated root. */
    fun refreshing(next: ThreadSlice): ThreadSlice {
        if (rootId != next.rootId) return next
        val known = messages.mapNotNull { it.id }.toSet()
        val overlap = next.messages.any { it.id?.removeSuffix("#a") != rootId && it.id in known }
        val covered = next.complete || overlap && complete && next.unresolved == 0
        return next.copy(messages = merge(messages, next.messages), complete = covered,
            nextCursor = if (covered) null else if (overlap) nextCursor else next.nextCursor)
    }

    companion object {
        fun merge(before: List<Message>, after: List<Message>): List<Message> {
            val items = before.toMutableList()
            for (message in after) {
                val base = message.id?.removeSuffix("#a")
                if (message.unsent && base != null) {
                    val prior = items.firstOrNull { it.id?.removeSuffix("#a") == base }
                    items.removeAll { it.id?.removeSuffix("#a") == base }
                    items += message.copy(uiKey = prior?.uiKey ?: prior?.renderKey ?: message.uiKey)
                    continue
                }
                if (base != null && items.any { it.unsent && it.id == base }) continue
                val index = items.indexOfFirst { prior ->
                    prior.id != null && prior.id == message.id || prior.renderKey == message.renderKey ||
                        message.clientId != null && prior.id == "c:${message.clientId}"
                }
                if (index < 0) { items += message; continue }
                val prior = items[index]
                val stale = prior.editedAt != null && (message.editedAt == null || message.editedAt < prior.editedAt) ||
                    prior.editCount != null && message.editCount != null && message.editCount < prior.editCount
                items[index] = if (stale) prior.copy(delivery = prior.delivery?.keeping(message.delivery) ?: message.delivery)
                    else message.copy(uiKey = prior.uiKey ?: prior.renderKey, quote = message.quote ?: prior.quote,
                        replyTo = message.replyTo ?: prior.replyTo, delivery = prior.delivery?.keeping(message.delivery) ?: message.delivery)
            }
            val targets = items.filter { it.id != null }.associateBy { it.id!!.removeSuffix("#a") }
            return items.map { item ->
                val target = targets[item.replyTo]
                if (target == null || item.unsent) item else item.copy(quote = if (target.unsent) "Message was unsent" else
                    (if (target.fromMe) "You" else target.sender) + ": " + target.text.take(90))
            }.sortedWith(compareBy<Message> { it.time }.thenBy { it.id })
        }

        fun read(o: JSONObject, convert: (MacWire.Msg, List<MacWire.Msg>) -> List<Message>): ThreadSlice {
            val raw = buildList {
                o.optJSONObject("root")?.let { add(MacWire.msg(it)) }
                o.optJSONArray("messages")?.let { a -> for (i in 0 until a.length()) add(MacWire.msg(a.getJSONObject(i))) }
            }.distinctBy { it.id }
            return ThreadSlice(o.getString("rootId"), merge(emptyList(), raw.flatMap { convert(it, raw) }),
                o.optString("nextCursor").takeUnless { o.isNull("nextCursor") || it.isBlank() },
                o.optBoolean("complete"), o.optBoolean("rootUnavailable"), o.optInt("unresolved"))
        }
    }
}
