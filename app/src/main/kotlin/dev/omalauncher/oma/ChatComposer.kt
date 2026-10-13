package dev.omalauncher.oma

import dev.omalauncher.assist.frontFirst
import dev.omalauncher.linux.MAX_PROMPT_IMAGES

/** The agents Oma runs in the background under its approvals; the others still open in a terminal. */
val BACKGROUND_AGENTS = setOf("claude", "codex")

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

    /** For the front agent to hear first: it answers, or hands his words to the build lane as [Background] or [Queued] would. */
    data class Front(val prompt: String) : Outgoing
}

/**
 * [agent] is the default agent's command, null when none is installed. Images alone ask for a description, as on home.
 * [working] is whether a background task is running: what would have been sent then waits as a follow-up.
 * There is no lane to choose (Boss, 2026-10-13): what a background agent would get goes to the front agent
 * first when [frontFirst] says so, [frontReady] being whether Claude Code, which is the front agent, is
 * installed and [frontBusy] whether it is still on the request before. Otherwise it goes straight on as it always did.
 */
fun outgoing(
    draft: String,
    images: Int,
    imageMissing: Boolean,
    busy: Boolean,
    linux: Boolean,
    agent: String?,
    working: Boolean = false,
    frontReady: Boolean = false,
    frontBusy: Boolean = false,
): Outgoing {
    val prompt = draft.trim().ifEmpty { if (images > 0) "Describe these images." else "" }
    return when {
        busy || prompt.isEmpty() -> Outgoing.Nothing
        !linux -> Outgoing.Refused("Open Omalauncher and finish Linux setup first.")
        agent == null -> Outgoing.Refused("Install and sign in to an agent in Omalauncher first.")
        images > 0 && agent !in BACKGROUND_AGENTS -> Outgoing.Refused("Choose Claude Code or Codex to send images.")
        imageMissing -> Outgoing.Refused("An attached image is no longer available. Remove it and attach it again.")
        agent in BACKGROUND_AGENTS -> when {
            frontFirst(prompt, images, frontReady, frontBusy) -> Outgoing.Front(prompt)
            working -> Outgoing.Queued(prompt)
            else -> Outgoing.Background(prompt)
        }
        working -> Outgoing.Refused("Wait for the running task or stop it first.")
        else -> Outgoing.Terminal(prompt)
    }
}

/** How many of [picked] new images still fit beside the [have] already attached, and what to say if some don't. */
fun imagesToTake(have: Int, picked: Int): Pair<Int, String?> {
    val room = (MAX_PROMPT_IMAGES - have).coerceAtLeast(0)
    return minOf(room, picked.coerceAtLeast(0)) to if (picked > room) "You can attach up to $MAX_PROMPT_IMAGES images." else null
}
