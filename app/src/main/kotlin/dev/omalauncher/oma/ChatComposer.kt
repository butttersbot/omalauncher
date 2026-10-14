package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PHONE_THREAD_B
import dev.omalauncher.assist.frontFirst
import dev.omalauncher.assist.macThreadId
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

    /**
     * For the front agent to hear first: it answers, or hands his words to the build lane as [Background] or
     * [Queued] would. If it is still on the request before, this one waits for it and is read next.
     */
    data class Front(val prompt: String) : Outgoing

    /**
     * Straight to the thread [thread] on the Mac, called [name], from that thread's own tab: his words as they
     * are, with the front agent not asked.
     */
    data class Mac(val prompt: String, val thread: String, val name: String) : Outgoing
}

/** Whom the field talks to, which is the tab that is open. */
sealed interface SendTo {
    /** The Oma tab: the front agent hears it first, as before there were tabs. */
    data object Oma : SendTo

    /** Builder A's tab: straight to the build lane, to builder A. */
    data object Phone : SendTo

    /** Builder B's tab: straight to builder B. */
    data object PhoneB : SendTo

    /** A Mac thread's tab: straight to that thread. */
    data class Mac(val thread: String, val name: String) : SendTo
}

/** The builder the field on a builder's tab sends to; null on every other tab. */
val SendTo.builder: BuilderId? get() = when (this) {
    SendTo.Phone -> BuilderId.A
    SendTo.PhoneB -> BuilderId.B
    else -> null
}

/** Whom the field talks to on [tab], which is one of [threads] or the Oma tab. A tab that is no thread's talks to Oma. */
fun sendTo(tab: String, threads: List<AgentThread>): SendTo {
    val thread = threads.firstOrNull { it.key == tab } ?: return SendTo.Oma
    if (thread.key == PHONE_THREAD) return SendTo.Phone
    if (thread.key == PHONE_THREAD_B) return SendTo.PhoneB
    return macThreadId(thread.key)?.let { SendTo.Mac(it, thread.name) } ?: SendTo.Oma
}

/** Why images are not sent from a Mac thread's tab. */
fun noImagesTo(name: String) = "$name is on the Mac, which takes no images from here. Remove them, or send this from the Oma tab."

/**
 * [agent] is the default agent's command, null when none is installed. Images alone ask for a description, as on home.
 * [working] is whether a background task is running: what would have been sent then waits as a follow-up.
 * There is no lane to choose (Boss, 2026-10-13): what a background agent would get goes to the front agent
 * first when [frontFirst] says so, [frontReady] being whether Claude Code, which is the front agent, is
 * installed. Nothing here asks whether the front agent or the builder is busy: his words are taken at once,
 * and wait their turn wherever they go. Otherwise it goes straight on as it always did.
 *
 * [to] is the open tab. On a thread's own tab the front agent is skipped whatever [frontReady] says: the
 * phone builder's tab sends as the build lane always took it, and a Mac thread's tab sends to that thread,
 * which needs neither Linux nor an agent on the phone, and takes no images.
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
    to: SendTo = SendTo.Oma,
): Outgoing {
    val prompt = draft.trim().ifEmpty { if (images > 0) "Describe these images." else "" }
    return when {
        busy || prompt.isEmpty() -> Outgoing.Nothing
        to is SendTo.Mac -> if (images > 0) Outgoing.Refused(noImagesTo(to.name)) else Outgoing.Mac(prompt, to.thread, to.name)
        !linux -> Outgoing.Refused("Open Omalauncher and finish Linux setup first.")
        agent == null -> Outgoing.Refused("Install and sign in to an agent in Omalauncher first.")
        images > 0 && agent !in BACKGROUND_AGENTS -> Outgoing.Refused("Choose Claude Code or Codex to send images.")
        imageMissing -> Outgoing.Refused("An attached image is no longer available. Remove it and attach it again.")
        agent in BACKGROUND_AGENTS -> when {
            to == SendTo.Oma && frontFirst(prompt, images, frontReady) -> Outgoing.Front(prompt)
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
