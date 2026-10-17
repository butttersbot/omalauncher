package dev.omalauncher.oma.ring

/** What the light around the screen's edge is saying. */
enum class RingState { Off, Listening, Working, NeedsYou, Done }

/** What the agent is doing, as far as the ring cares. Plain values, so the state is worked out and tested without Android. */
data class RingSignals(
    /** Oma is taking a request: the assistant is up, or a microphone is open. */
    val listening: Boolean = false,
    /** A task is running. It lights nothing by itself: only using the phone does ([phone]). */
    val working: Boolean = false,
    /**
     * An agent is using the phone right now (Boss, 2026-10-17: the ring is "only supposed to be active when there's
     * active phone use"): it moved the screen a moment ago, and its work has not ended. This is what lights working.
     */
    val phone: Boolean = false,
    /** The phone was used during the task now running or just ended: only then does its ending show done. */
    val phoneUsed: Boolean = false,
    /** Approvals waiting for the owner. */
    val pending: Int = 0,
    /** When the last task finished well, on the clock [ringState] is asked with; null once anything new began. */
    val doneAt: Long? = null,
)

/** How long done shows when the pack's done light doesn't breathe. */
const val DONE_MILLIS = 3000L

/**
 * The ring's state at [now]. Needs you beats working, working beats listening, and done shows only when
 * nothing else does, for [doneFor] milliseconds after the task finished. Working is an agent using the phone,
 * not any task running: work on code, here or on a Mac, leaves the ring dark.
 */
fun ringState(signals: RingSignals, now: Long, doneFor: Long = DONE_MILLIS): RingState = when {
    signals.pending > 0 -> RingState.NeedsYou
    signals.phone -> RingState.Working
    signals.listening -> RingState.Listening
    signals.doneAt != null && now - signals.doneAt in 0 until doneFor -> RingState.Done
    else -> RingState.Off
}

/**
 * The signals after the agent's task changed. Done starts when a running task that used the phone ends and
 * [succeeded]; a task that was stopped or failed, or never touched the phone, just goes dark, and a new task forgets
 * the last one's ending and whether it used the phone.
 */
fun RingSignals.afterTask(working: Boolean, pending: Int, succeeded: Boolean, now: Long): RingSignals = copy(
    working = working,
    pending = maxOf(0, pending),
    phoneUsed = if (working && !this.working) phone else phoneUsed,
    doneAt = when {
        working -> null
        this.working -> now.takeIf { succeeded && phoneUsed }
        else -> doneAt
    },
)

/** The signals after an agent started or stopped using the phone ([RingSignals.phone]). */
fun RingSignals.afterPhone(inUse: Boolean): RingSignals = copy(phone = inUse, phoneUsed = phoneUsed || inUse, doneAt = if (inUse) null else doneAt)

/** The signals after Oma started or stopped taking a request. A new request puts an earlier done away. */
fun RingSignals.afterHearing(listening: Boolean): RingSignals = copy(listening = listening, doneAt = if (listening) null else doneAt)
