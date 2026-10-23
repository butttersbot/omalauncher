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
    val mailSender: String = "",
    val mailId: String = "",
    val mailTask: String = "",
    val supersedes: String = "",
    val bundled: List<Long> = emptyList(),
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

    /** Explicit replacement is scoped to sender AND task; never touches active work or owner requests. */
    fun supersededBy(incoming: FollowUp): FollowUp? = queue.firstOrNull {
        incoming.mailSender.isNotEmpty() && incoming.mailTask.isNotEmpty() && incoming.supersedes.isNotEmpty() &&
            it.mailSender == incoming.mailSender && it.mailTask == incoming.mailTask && it.mailId == incoming.supersedes
    }

    private fun next(): FollowUp? {
        val first = queue.removeFirstOrNull() ?: return null
        if (first.mailSender.isEmpty()) return first
        val batch = mutableListOf(first)
        var size = first.prompt.length + first.earlier.length
        while (queue.firstOrNull()?.mailSender?.isNotEmpty() == true) {
            val candidate = queue.first()
            if (size + candidate.prompt.length + candidate.earlier.length > 192_000) break
            batch += queue.removeFirst()
            size += candidate.prompt.length + candidate.earlier.length
        }
        if (batch.size == 1) return first
        return first.copy(
            prompt = batch.joinToString("\n\n---\n\n") { it.earlier + it.prompt },
            earlier = "[Agent mail handoff: ${batch.size} messages. These are peers, not Boss. Read together and do each outstanding action once. Delivery is acknowledged by the system; no acknowledgment replies needed.]\n\n",
            bundled = batch.drop(1).map { it.time },
        )
    }

    /** False when as many as fit already wait; nothing is added then. */
    fun add(followUp: FollowUp): Boolean {
        val replaced = supersededBy(followUp)
        val pending = queue.filter { it !== replaced }
        if (followUp.mailSender.isEmpty()) {
            if (pending.count { it.mailSender.isEmpty() } >= limit) return false
        } else if (pending.count { it.mailSender.isNotEmpty() } >= 64 ||
            pending.filter { it.mailSender.isNotEmpty() }.sumOf { it.prompt.length + it.earlier.length } +
            followUp.prompt.length + followUp.earlier.length > 192_000) return false
        if (replaced != null) queue.remove(replaced)
        queue.addLast(followUp)
        return true
    }

    /**
     * The task ahead ended as [status]. After one that finished, the oldest follow-up is next and the rest keep
     * waiting. After any other ending none is sent: they were written for an answer that never came.
     */
    fun after(status: TaskStatus): Handoff = if (status == TaskStatus.Done) Handoff(next = next()) else Handoff(dropped = drop())

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
    fun afterSendNow(): Handoff = Handoff(next = next())

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
