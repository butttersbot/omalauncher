package dev.omalauncher.oma

import dev.omalauncher.linux.MAX_PROMPT_IMAGES

/** The agents Oma runs in the background under its approvals; the others still open in a terminal. */
val BACKGROUND_AGENTS = setOf("claude", "codex")

/** Where Send takes a message. [id] is saved and is the word the composer shows: never rename. */
enum class Lane(val id: String, val does: String) {
    /** Answered at once by a fast model that can look and change nothing, beside whatever is being built. */
    Quick("quick", "answers now, looks only"),

    /** The agent that does the work, one task at a time. */
    Build("build", "does the work"),
    ;

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Build }
}

/** What Send does with what's in the composer. */
sealed interface Outgoing {
    /** Nothing to send, or something is still in the way (images being added, dictation). */
    data object Nothing : Outgoing

    /** Can't be sent as it is; [reason] is shown above the field and the draft stays. */
    data class Refused(val reason: String) : Outgoing

    data class Background(val prompt: String) : Outgoing

    /** A task is running: this waits behind it and is sent when it has finished. */
    data class Queued(val prompt: String) : Outgoing

    data class Terminal(val prompt: String) : Outgoing

    /** A question for the quick lane, which answers whether or not a task is running. */
    data class Quick(val prompt: String) : Outgoing
}

/**
 * [agent] is the default agent's command, null when none is installed. Images alone ask for a description, as on home.
 * [working] is whether a background task is running: what would have been sent then waits as a follow-up.
 * In [Lane.Quick] neither matters: [quickReady] is whether Claude Code, which answers there, is installed, and
 * [quickBusy] whether it is still on the question before.
 */
fun outgoing(
    draft: String,
    images: Int,
    imageMissing: Boolean,
    busy: Boolean,
    linux: Boolean,
    agent: String?,
    working: Boolean = false,
    lane: Lane = Lane.Build,
    quickReady: Boolean = true,
    quickBusy: Boolean = false,
): Outgoing {
    val prompt = draft.trim().ifEmpty { if (images > 0) "Describe these images." else "" }
    return when {
        busy || prompt.isEmpty() -> Outgoing.Nothing
        !linux -> Outgoing.Refused("Open Omalauncher and finish Linux setup first.")
        lane == Lane.Quick -> when {
            images > 0 -> Outgoing.Refused(QUICK_NO_IMAGES)
            !quickReady -> Outgoing.Refused(QUICK_NEEDS_CLAUDE)
            quickBusy -> Outgoing.Refused(QUICK_BUSY)
            else -> Outgoing.Quick(prompt)
        }
        agent == null -> Outgoing.Refused("Install and sign in to an agent in Omalauncher first.")
        images > 0 && agent !in BACKGROUND_AGENTS -> Outgoing.Refused("Choose Claude Code or Codex to send images.")
        imageMissing -> Outgoing.Refused("An attached image is no longer available. Remove it and attach it again.")
        agent in BACKGROUND_AGENTS -> if (working) Outgoing.Queued(prompt) else Outgoing.Background(prompt)
        working -> Outgoing.Refused("Wait for the running task or stop it first.")
        else -> Outgoing.Terminal(prompt)
    }
}

const val QUICK_NO_IMAGES = "Images go to the build lane. Switch to build, or remove them to ask this quickly."
const val QUICK_NEEDS_CLAUDE = "The quick lane is answered by Claude Code. Install and sign in to it in Omalauncher, or send this in build."
const val QUICK_BUSY = "A quick question is still being answered. Wait for it or stop it, then send this."

/** How many of [picked] new images still fit beside the [have] already attached, and what to say if some don't. */
fun imagesToTake(have: Int, picked: Int): Pair<Int, String?> {
    val room = (MAX_PROMPT_IMAGES - have).coerceAtLeast(0)
    return minOf(room, picked.coerceAtLeast(0)) to if (picked > room) "You can attach up to $MAX_PROMPT_IMAGES images." else null
}
