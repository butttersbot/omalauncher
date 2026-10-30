package dev.omalauncher.messages.engine

/** Display quotes are never used as wire identities. Preserve native multipart `_N` suffixes. */
object Replies {
    fun draftKey(chat: String, root: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        return "thread:" + encoder.encodeToString(chat.toByteArray(Charsets.UTF_8)) + ":" + encoder.encodeToString(root.toByteArray(Charsets.UTF_8))
    }

    fun target(id: String?, messages: List<Message> = emptyList()): String? {
        val part = id?.removeSuffix("#a") ?: return null
        val resolved = if (part.startsWith("c:")) messages.firstOrNull { it.renderKey == part }?.id else part
        return resolved?.removeSuffix("#a")?.takeIf {
            it.isNotBlank() && it.length <= 1024 && !it.startsWith("c:") && it.none { c -> c == '\r' || c == '\n' || c == '\u0000' }
        }
    }

    fun missing(quote: String?, target: String?) = quote != null && target == null

    /** Adds live/local descendants to an already server-verified page without using quote text. */
    fun members(root: String, messages: List<Message>): List<Message> {
        val byID = messages.associateBy { it.id?.removeSuffix("#a") ?: it.renderKey }
        return messages.filter { m ->
            var current: Message? = m
            val seen = mutableSetOf<String>()
            var found = false
            for (step in 0 until 64) {
                val item = current ?: break
                val id = item.id?.removeSuffix("#a") ?: item.renderKey
                if (!seen.add(id)) break
                if (id == root || item.replyTo == root) { found = true; break }
                current = item.replyTo?.let(byID::get)
            }
            found
        }
    }
}
