package dev.omalauncher.oma

import java.io.File

/**
 * A message sent while the agent was still on the task before it. [time] is its task in the thread, where it
 * already shows as his message; [prompt] is kept whole here, since the thread only keeps the start of a long one.
 * [page] is the home page he was on when he sent it. [earlier] is sent to the agent ahead of [prompt] and is
 * never shown: what he and the front agent said before a request it handed over. [screen] is the screenshot
 * that came with a spoken request, kept until the request is sent or dropped. [errand] is a plain errand on the
 * phone, run on the fast model in a session of its own (dev.omalauncher.assist.ERRAND_MODEL).
 */
data class FollowUp(
    val time: Long,
    val prompt: String,
    val images: List<String> = emptyList(),
    val page: String? = null,
    val earlier: String = "",
    val screen: File? = null,
    val errand: Boolean = false,
)

/** What the end of a task means for the follow-ups behind it: [next] is sent now, the [dropped] never are. */
data class Handoff(val next: FollowUp? = null, val dropped: List<FollowUp> = emptyList())

/**
 * The follow-ups waiting for the running task, oldest first. They are never given to a running agent: each is
 * sent as a request of its own once the one ahead has finished, so none can reach an agent that is waiting for
 * an approval. Not safe to share between threads; AgentTasks holds its lock around every call.
 */
class FollowUpQueue(private val limit: Int = MAX_WAITING) {
    private val queue = ArrayDeque<FollowUp>()

    val waiting: List<FollowUp> get() = queue.toList()

    /** False when as many as fit already wait; nothing is added then. */
    fun add(followUp: FollowUp): Boolean {
        if (queue.size >= limit) return false
        queue.addLast(followUp)
        return true
    }

    /**
     * The task ahead ended as [status]. After one that finished, the oldest follow-up is next and the rest keep
     * waiting. After any other ending none is sent: they were written for an answer that never came.
     */
    fun after(status: TaskStatus): Handoff = if (status == TaskStatus.Done) Handoff(next = queue.removeFirstOrNull()) else Handoff(dropped = drop())

    /**
     * Puts the one filed at [time] first, for "send now": the running task is stopped and this goes next, the rest
     * still waiting behind it. False when it no longer waits.
     */
    fun first(time: Long): Boolean {
        val it = queue.firstOrNull { f -> f.time == time } ?: return false
        queue.remove(it)
        queue.addFirst(it)
        return true
    }

    /** After a task stopped for "send now": the one put first goes, and the rest keep waiting. */
    fun afterSendNow(): Handoff = Handoff(next = queue.removeFirstOrNull())

    /** Takes out the one filed at [time], which he cancelled; null when it no longer waits, and nothing changes then. */
    fun cancel(time: Long): FollowUp? = queue.firstOrNull { it.time == time }?.also { queue.remove(it) }

    /** Empties the queue and returns what was waiting, for the thread to say so. */
    fun drop(): List<FollowUp> = queue.toList().also { queue.clear() }

    companion object {
        /** More than this waiting is a backlog he can no longer keep in his head. */
        const val MAX_WAITING = 5

        const val FULL = "$MAX_WAITING follow-ups are already waiting. Send this one when the agent has caught up."
        const val STOPPED = "You stopped the task before this one, so it was not sent."
        const val FAILED = "The task before this one did not finish, so it was not sent."
        const val CANCELLED = "You cancelled this before it was sent."
        const val RESTARTED = "The app was closed before the agent got to this, so it was not sent."
    }
}
