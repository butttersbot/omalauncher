package dev.omalauncher.oma

import dev.omalauncher.linux.MAX_PROMPT_IMAGES

/** The agents Oma runs in the background under its approvals; the others still open in a terminal. */
val BACKGROUND_AGENTS = setOf("claude", "codex")

/** What Send does with what's in the composer. */
sealed interface Outgoing {
    /** Nothing to send, or something is still in the way (a running task, images being added, dictation). */
    data object Nothing : Outgoing

    /** Can't be sent as it is; [reason] is shown above the field and the draft stays. */
    data class Refused(val reason: String) : Outgoing

    data class Background(val prompt: String) : Outgoing

    data class Terminal(val prompt: String) : Outgoing
}

/** [agent] is the default agent's command, null when none is installed. Images alone ask for a description, as on home. */
fun outgoing(draft: String, images: Int, imageMissing: Boolean, busy: Boolean, linux: Boolean, agent: String?): Outgoing {
    val prompt = draft.trim().ifEmpty { if (images > 0) "Describe these images." else "" }
    return when {
        busy || prompt.isEmpty() -> Outgoing.Nothing
        !linux -> Outgoing.Refused("Open Omalauncher and finish Linux setup first.")
        agent == null -> Outgoing.Refused("Install and sign in to an agent in Omalauncher first.")
        images > 0 && agent !in BACKGROUND_AGENTS -> Outgoing.Refused("Choose Claude Code or Codex to send images.")
        imageMissing -> Outgoing.Refused("An attached image is no longer available. Remove it and attach it again.")
        agent in BACKGROUND_AGENTS -> Outgoing.Background(prompt)
        else -> Outgoing.Terminal(prompt)
    }
}

/** How many of [picked] new images still fit beside the [have] already attached, and what to say if some don't. */
fun imagesToTake(have: Int, picked: Int): Pair<Int, String?> {
    val room = (MAX_PROMPT_IMAGES - have).coerceAtLeast(0)
    return minOf(room, picked.coerceAtLeast(0)) to if (picked > room) "You can attach up to $MAX_PROMPT_IMAGES images." else null
}
