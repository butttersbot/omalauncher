package dev.omalauncher.messages.engine

/**
 * The owner's tapback while the Mac's copy catches up. The Direct service sends through its transport but reads history
 * from chat.db, which has a tapback only once Messages.app has received Apple's copy; a copy of the message read before
 * then says it isn't there (Boss, 0.8.10: "it lands and then it'll disappear and then land again"). Until a copy agrees,
 * or [PATIENCE_MS] pass, the owner's own tapback on that message stays as he left it; everyone else's come as the Mac says.
 */
internal class TapbacksKept {
    data class Kept(val mine: String?, val reactions: List<Reaction>, val until: Long)

    private val held = HashMap<String, Kept>()

    fun hold(chat: String, target: String, mine: String?, reactions: List<Reaction>, now: Long): Kept =
        Kept(mine, reactions, now + PATIENCE_MS).also { held[key(chat, target)] = it }

    /** A tapback the Mac refused or never answered: only that one, a later tap on the message has its own. */
    fun drop(chat: String, target: String, kept: Kept) {
        if (held[key(chat, target)] === kept) held.remove(key(chat, target))
    }

    /** [incoming] with the owner's tapbacks that are still on their way. */
    fun applied(chat: String, incoming: List<Message>, now: Long): List<Message> {
        if (held.isEmpty()) return incoming
        held.entries.removeAll { it.value.until <= now }
        val waiting = held.filterKeys { it.startsWith(chat + SEPARATOR) }
        if (waiting.isEmpty()) return incoming
        return incoming.map { message ->
            val target = message.id?.removeSuffix("#a") ?: return@map message
            val kept = waiting[key(chat, target)] ?: return@map message
            // The Mac puts a message's tapbacks on its last part, so a part with none says nothing about the rest.
            val parts = incoming.filter { it.id?.removeSuffix("#a") == target }
            if (parts.any { part -> part.tapbacks.firstOrNull { it.startsWith("me:") } == kept.mine && (kept.mine != null || part === parts.last()) }) {
                held.remove(key(chat, target))
                return@map message
            }
            if (message !== parts.last()) return@map message
            message.copy(
                tapbacks = message.tapbacks.filterNot { it.startsWith("me:") } + listOfNotNull(kept.mine),
                reactions = message.reactions.filterNot { it.fromMe } + kept.reactions,
            )
        }
    }

    private fun key(chat: String, target: String) = chat + SEPARATOR + target

    private companion object {
        const val SEPARATOR = "\u0000"
        const val PATIENCE_MS = 60_000L
    }
}
