package dev.omalauncher.oma.ring

/** What the light around the screen's edge is saying. */
enum class RingState { Off, Listening, Working, NeedsYou, Done }

/** What the agent is doing, as far as the ring cares. Plain values, so the state is worked out and tested without Android. */
data class RingSignals(
    /** Oma is taking a request: the assistant is up, or a microphone is open. */
    val listening: Boolean = false,
    /** A task is running. */
    val working: Boolean = false,
    /** Approvals waiting for the owner. */
    val pending: Int = 0,
    /** When the last task finished well, on the clock [ringState] is asked with; null once anything new began. */
    val doneAt: Long? = null,
)

/** How long done shows when the pack's done light doesn't breathe. */
const val DONE_MILLIS = 3000L

/**
 * The ring's state at [now]. Needs you beats working, working beats listening, and done shows only when
 * nothing else does, for [doneFor] milliseconds after the task finished.
 */
fun ringState(signals: RingSignals, now: Long, doneFor: Long = DONE_MILLIS): RingState = when {
    signals.pending > 0 -> RingState.NeedsYou
    signals.working -> RingState.Working
    signals.listening -> RingState.Listening
    signals.doneAt != null && now - signals.doneAt in 0 until doneFor -> RingState.Done
    else -> RingState.Off
}

/**
 * The signals after the agent's task changed. Done starts when a running task ends and [succeeded]; a task
 * that was stopped or failed just goes dark, and a new task forgets the last one's ending.
 */
fun RingSignals.afterTask(working: Boolean, pending: Int, succeeded: Boolean, now: Long): RingSignals = copy(
    working = working,
    pending = maxOf(0, pending),
    doneAt = when {
        working -> null
        this.working -> now.takeIf { succeeded }
        else -> doneAt
    },
)

/** The signals after Oma started or stopped taking a request. A new request puts an earlier done away. */
fun RingSignals.afterHearing(listening: Boolean): RingSignals = copy(listening = listening, doneAt = if (listening) null else doneAt)
