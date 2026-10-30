package dev.omalauncher.messages.engine

/** Facts from the service, never inferred from elapsed time or a typing indicator. */
enum class Delivery(val label: String) {
    Sending("Sending…"), Sent("Sent"), Delivered("Delivered"), Read("Read");

    fun keeping(newer: Delivery?) = if (newer == null || ordinal > newer.ordinal) this else newer
}

data class Typist(val chat: String, val sender: String, val name: String, val expires: Long)

/** Immutable presence with a bounded lifetime, including when the remote stop event gets lost. */
data class TypingState(val people: List<Typist> = emptyList()) {
    fun event(chat: String, sender: String, name: String, on: Boolean, now: Long): TypingState =
        TypingState(people.filter { it.expires > now && !(it.chat == chat && it.sender == sender) } +
            if (on) listOf(Typist(chat, sender, name, now + 8_000)) else emptyList())

    fun expire(now: Long) = copy(people = people.filter { it.expires > now })
    fun names(chat: String, now: Long) = people.filter { it.chat == chat && it.expires > now }.map { it.name }.distinct()
}

/** Start immediately, refresh at most every three seconds, stop once. */
class TypingPulse {
    private var active = false
    private var last = 0L
    fun changed(text: String, now: Long): Boolean? {
        if (text.isBlank()) return stop()
        if (!active || now - last >= 3_000) { active = true; last = now; return true }
        return null
    }
    fun stop(): Boolean? = if (active) { active = false; false } else null
}
