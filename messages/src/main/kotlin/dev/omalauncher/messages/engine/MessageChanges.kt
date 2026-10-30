package dev.omalauncher.messages.engine

import org.json.JSONArray
import org.json.JSONObject

/** Only server-qualified actions are offered. Permissions from disk never authorize a mutation. */
data class AllowedChanges(
    val edit: Boolean = false,
    val unsend: Boolean = false,
    val editRemaining: Long = 0,
    val unsendRemaining: Long = 0,
    val receivedAt: Long = 0,
) {
    fun canEdit(now: Long) = edit && now >= receivedAt && now - receivedAt < editRemaining
    fun canUnsend(now: Long) = unsend && now >= receivedAt && now - receivedAt < unsendRemaining

    companion object {
        fun read(o: JSONObject?, now: Long = System.currentTimeMillis()): AllowedChanges {
            if (o == null) return AllowedChanges()
            val server = o.optLong("asOf")
            return AllowedChanges(o.optBoolean("edit"), o.optBoolean("unsend"),
                (o.optLong("editUntil") - server).coerceIn(0, 900_000),
                (o.optLong("unsendUntil") - server).coerceIn(0, 120_000), now)
        }
    }
}

data class MessageRevision(val text: String?, val at: Long?, val available: Boolean) {
    fun json() = JSONObject().put("text", text).put("at", at).put("available", available)
    companion object {
        fun read(a: JSONArray?): List<MessageRevision> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").takeUnless { o.isNull("text") }
            MessageRevision(text, o.optLong("at").takeUnless { o.isNull("at") }, o.optBoolean("available", text != null))
        }
    }
}

data class PendingMessageChange(val id: String, val text: String?, val accepted: Boolean = false) {
    val unsend get() = text == null
    fun confirmedBy(message: Message): Boolean = message.id?.removeSuffix("#a") == id &&
        if (unsend) message.unsent else !message.unsent && message.edited && message.text == text
}
