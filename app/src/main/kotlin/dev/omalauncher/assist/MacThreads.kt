package dev.omalauncher.assist

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.omalauncher.App
import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/*
 * The phone's view of the project threads on the Mac (docs/MAC-RUNNER-SPEC.md): which there are, what each
 * says while it works, which of its requests wait for Boss, and what becomes of a request handed to one. What a
 * thread's events make of the Oma thread, which approval a tap answers and when a request may be sent again
 * are plain functions at the top, unit tested; MacThreads at the end does what they say over MacRunnerClient.
 * A thread is known by its id everywhere: two threads can share a name and a project.
 */

/**
 * What the phone last heard of the Mac: its [threads], whether it answered at the last try, and when it last did.
 * [listed] is whether [threads] is the Mac's own whole list, heard since this process began: until then a
 * thread missing from it is only not heard of yet, and nothing kept for it is taken for gone.
 */
data class MacView(val threads: List<MacThread> = emptyList(), val reachable: Boolean = false, val seen: Long? = null, val listed: Boolean = false)

/**
 * [thread] with the summary the board should carry. The runner's summary is the first line of what it was last
 * sent, which for a request handed over with context is the context's heading: the first line of his own
 * words, from the entry of that request, stands there instead. Any other summary is left as the Mac gave it.
 */
fun macOwnSummary(thread: MacThread, tasks: List<ChatTask>): MacThread =
    if (!thread.summary.startsWith(CONTEXT_OPEN)) thread
    else thread.copy(summary = tasks.lastOrNull { threadGot(it, thread.id) }?.prompt?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty())

/**
 * The guest's `oma-thread`, which lists the Mac's threads and makes one in a folder Boss names. A command and
 * not a power of the front agent's: the phone's builder runs it under his approval rules, or he does in a
 * terminal, and the runner alone decides which folders it takes. Installed with the other guest tools.
 */
fun macGuestTools(context: Context, rootfs: File): List<Managed> =
    listOf(Managed(File(rootfs, "usr/local/bin/oma-thread"), Policy.Owned, executable = true) { context.assets.open("oma-thread.sh").use { it.readBytes() } })

/** A thread's name as he is shown it; one without a name is still somewhere on the Mac. */
fun macName(name: String) = name.trim().ifEmpty { "Mac" }

/** The receipt on a request handed to a thread: where his words went. */
fun macReceipt(name: String) = "→ " + macName(name)

const val MAC_UNREACHABLE = "Mac unreachable"
const val MAC_KEY_REFUSED = "The Mac turned this phone's key down. Nothing was sent."
const val MAC_NOT_SET_UP = "The Mac is no longer set up on this phone."
const val MAC_THREAD_GONE = "That thread is no longer on the Mac."
const val MAC_NOT_TAKEN = "Mac unreachable: it did not take this."
const val MAC_NO_ANSWER = "The Mac ended this without an answer."
const val MAC_INTERRUPTED = "The runner on the Mac restarted while this ran. Nothing is started again until you say."
const val MAC_STOPPED_UNTAKEN = "Stopped before the Mac took it."

/** How long to wait before asking a Mac that did not answer again: 2 s, 5 s, 15 s, then every 30 s. [misses] counts from 1. */
fun macRetryDelay(misses: Int): Long = when {
    misses <= 1 -> 2_000L
    misses == 2 -> 5_000L
    misses == 3 -> 15_000L
    else -> 30_000L
}

/** Whether a thread in this state has no task: nothing more will come from it until it is sent something. */
fun macResting(status: MacThreadStatus) = status == MacThreadStatus.Idle || status == MacThreadStatus.Interrupted

/** Whether [event] can be the last of a turn, after which the thread may rest: the follower then asks the Mac how it stands. */
fun macTurnsOver(event: MacEvent) = event is MacEvent.Result || event is MacEvent.Stopped || event is MacEvent.Error || (event is MacEvent.Status && macResting(event.status))

/** A request the Mac has, or may have, and has not finished. */
fun macUnfinished(task: ChatTask, threadId: String) = task.thread == threadId && (task.status == TaskStatus.Running || task.status == TaskStatus.Queued)

/**
 * The entry of a request on its way to [thread]: it carries the receipt, the thread's id and name, and waits
 * until the Mac says it took it. Done before anything is sent, so what the thread says about it finds it.
 */
fun macHandOver(task: ChatTask, thread: MacThread) =
    task.copy(agent = macReceipt(thread.name), thread = thread.id, threadName = macName(thread.name), status = TaskStatus.Queued, result = "")

/**
 * The entry of words he sent straight to a thread from its tab in the chat, filed at [time]: the same entry a
 * hand-over leaves, receipt and all, so it stands in the Oma stream too. It is marked [ChatTask.direct], and so
 * is sent, now and at any `[ try again ]`, as his words alone.
 */
fun macDirect(time: Long, prompt: String, threadId: String, name: String) =
    ChatTask(time, macReceipt(name), prompt, thread = threadId, threadName = macName(name), status = TaskStatus.Queued, direct = true)

/** The Mac took the request at once. One the thread has already ended, or begun, stays as it is. */
fun macTaken(task: ChatTask) = if (task.mac && task.status == TaskStatus.Queued) task.copy(status = TaskStatus.Running) else task

/** The request never reached the Mac: it keeps his words, says [reason], and can be sent again. */
fun macUnsent(task: ChatTask, reason: String) = if (task.mac && task.status == TaskStatus.Queued) task.copy(status = TaskStatus.Dropped, result = reason) else task

/**
 * [task] as it is sent again; null when it must not be: only a request the Mac never got is, and never one it
 * took, finished or may still have.
 */
fun macRetried(task: ChatTask): ChatTask? = if (task.mac && task.status == TaskStatus.Dropped) task.copy(status = TaskStatus.Queued, result = "") else null

private fun ChatTask.over(status: TaskStatus, result: String) =
    copy(status = status, result = result, approvals = approvals.map { if (it.answer == ApprovalAnswer.Waiting) it.copy(answer = ApprovalAnswer.Unanswered) else it })

private fun withReason(said: String, reason: String) = listOf(said.trim(), reason.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

/** What an approval would do, as its card says it: the command for a shell, otherwise the tool's whole input. */
fun macApprovalDetail(request: MacApproval): String {
    val input = runCatching { JSONObject(request.input) }.getOrNull() ?: return request.input
    return input.optString("command").ifBlank { input.toString(2) }
}

/** The request [threadId] is working on gets a line for [request], waiting; once only, however often it is heard of. */
fun macAsked(tasks: List<ChatTask>, threadId: String, request: MacApproval): List<ChatTask> {
    val current = tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running } ?: return tasks
    if (current.approvals.any { it.key == request.approval }) return tasks
    val line = ChatApproval(request.tool.ifBlank { "a tool" }, macApprovalDetail(request), ApprovalAnswer.Waiting, request.approval)
    return tasks.map { if (it.time == current.time) it.copy(approvals = it.approvals + line) else it }
}

/**
 * How the request [approval] ended. A line that already says allowed, denied or withdrawn keeps saying it; one
 * marked not answered because the card went can still learn what the Mac says became of it.
 */
fun macAnswered(tasks: List<ChatTask>, threadId: String, approval: String, answer: ApprovalAnswer): List<ChatTask> = tasks.map { task ->
    if (task.thread != threadId || task.approvals.none { it.key == approval }) task
    else task.copy(approvals = task.approvals.map { if (it.key == approval && (it.answer == ApprovalAnswer.Waiting || it.answer == ApprovalAnswer.Unanswered)) it.copy(answer = answer) else it })
}

private fun approvalAnswer(decision: MacDecision) = when (decision) {
    MacDecision.Allow -> ApprovalAnswer.Approved
    MacDecision.Deny -> ApprovalAnswer.Denied
    MacDecision.Withdrawn -> ApprovalAnswer.Withdrawn
    MacDecision.Unknown -> ApprovalAnswer.Unanswered
}

/**
 * What one event of [threadId] makes of the Oma thread. A thread works on one request at a time, so what it
 * says belongs to the one entry of its that is running. A `message` event is the thread taking his words: the
 * oldest waiting entry with exactly those words begins, unless one is already running, which is then the one
 * meant. The thread says back all it was sent, so the words are looked for after the context too ([handOverWords]). Events of a task that was not sent from this phone find no entry and change nothing. [inFlight] are
 * the entries still on their way to the Mac, which a stop that came before them does not end. [answeredBy] is
 * what the reply is named, given to the entry with its first words unless it already has a name.
 */
fun macApply(tasks: List<ChatTask>, threadId: String, event: MacEvent, inFlight: Set<Long> = emptySet(), answeredBy: String = ""): List<ChatTask> {
    val current = tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running }
    fun running(change: (ChatTask) -> ChatTask) = if (current == null) tasks else tasks.map { if (it.time == current.time) change(it) else it }
    return when (event) {
        is MacEvent.Message -> {
            val words = handOverWords(event.text)
            val next = if (current != null) null else tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Queued && (it.prompt == event.text || it.prompt == words) }
            if (next == null) tasks else tasks.map { if (it.time == next.time) it.copy(status = TaskStatus.Running) else it }
        }
        is MacEvent.Text -> running { it.copy(result = it.result + event.text.trimEnd() + "\n", answeredBy = it.answeredBy.ifEmpty { answeredBy }) }
        is MacEvent.Step -> running { ChatHistory.withStep(it, event.text) }
        is MacEvent.Approval -> macAsked(tasks, threadId, event.request)
        is MacEvent.ApprovalResult -> macAnswered(tasks, threadId, event.approval, approvalAnswer(event.decision))
        // The result is the whole answer, as it is for the phone's builder; with no words, what was said so far stays.
        is MacEvent.Result -> running {
            val none = if (event.failed) "The agent needs attention." else "Task completed."
            it.over(if (event.failed) TaskStatus.Failed else TaskStatus.Done, event.text.ifBlank { it.result.ifBlank { none } }).copy(answeredBy = it.answeredBy.ifEmpty { answeredBy })
        }
        is MacEvent.Stopped -> tasks.map {
            when {
                it.thread != threadId -> it
                it.status == TaskStatus.Running -> it.over(TaskStatus.Stopped, it.result)
                it.status == TaskStatus.Queued && it.time !in inFlight -> it.over(TaskStatus.Stopped, MAC_STOPPED_UNTAKEN)
                else -> it
            }
        }
        is MacEvent.Error -> running { it.over(TaskStatus.Failed, withReason(it.result, event.text).ifEmpty { MAC_NO_ANSWER }) }
        is MacEvent.Status, is MacEvent.Other -> tasks
    }
}

/**
 * What is left to say once everything a resting thread has said is shown. An entry still running will get no
 * result, and says so. One still waiting was never taken if the Mac holds no waiting turns ([waitingTurns]),
 * and can be sent again; while the Mac holds some, it may be among them and is left alone. [sent] is false for
 * an entry whose sending had not ended when the Mac was asked: nothing is known of it yet.
 */
fun macSettle(tasks: List<ChatTask>, threadId: String, status: MacThreadStatus, waitingTurns: Int, sent: (ChatTask) -> Boolean = { true }): List<ChatTask> {
    if (!macResting(status)) return tasks
    val why = if (status == MacThreadStatus.Interrupted) MAC_INTERRUPTED else MAC_NO_ANSWER
    return tasks.map { task ->
        when {
            task.thread != threadId || !sent(task) -> task
            task.status == TaskStatus.Running -> task.over(TaskStatus.Failed, withReason(task.result, why))
            task.status == TaskStatus.Queued && waitingTurns == 0 -> task.copy(status = TaskStatus.Dropped, result = MAC_NOT_TAKEN)
            else -> task
        }
    }
}

/**
 * The thread can no longer be followed at all ([reason] says why): its unfinished entries need his attention.
 * They are not offered to be sent again, since the Mac may have them. [threadId] null is every thread.
 */
fun macAbandon(tasks: List<ChatTask>, threadId: String?, reason: String): List<ChatTask> = tasks.map { task ->
    if (task.mac && (threadId == null || task.thread == threadId) && (task.status == TaskStatus.Running || task.status == TaskStatus.Queued)) task.over(TaskStatus.Failed, withReason(task.result, reason))
    else task
}

/** What an entry says once the Mac holds its request behind the task the thread is on. */
const val MAC_QUEUED = "Queued on the Mac"

/** What stands beside an entry of the Mac's while it is not over. */
fun macEntryLabel(status: TaskStatus, sending: Boolean, reachable: Boolean, approvals: Boolean): String = when {
    status == TaskStatus.Queued -> if (sending) "Sending to the Mac" else if (reachable) MAC_QUEUED else "Sent; the Mac is out of reach"
    approvals -> "Approval needed"
    reachable -> "Working on the Mac"
    else -> "Mac out of reach; still working there"
}

/** Why a request was not sent, from asking the Mac how it is. */
fun macUnreachableReason(health: MacHealthResult): String = when (health) {
    is MacHealthResult.Refused -> MAC_KEY_REFUSED
    MacHealthResult.NotConfigured -> MAC_NOT_SET_UP
    else -> MAC_UNREACHABLE
}

/**
 * Why the Mac did not take a request, when it is certain that it did not; null when it took it, or when no
 * answer came and it cannot be known: such a request is never sent again on a guess.
 */
fun macUnsentReason(answer: MacAnswer<MacTaken>): String? = when (answer) {
    is MacAnswer.Ok, MacAnswer.Unreachable -> null
    MacAnswer.Unauthorized -> MAC_KEY_REFUSED
    MacAnswer.NotFound -> MAC_THREAD_GONE
    is MacAnswer.Refused -> "The Mac refused this request."
    is MacAnswer.Failed -> if (answer.code == 200) null else "The Mac could not take this (HTTP ${answer.code})."
}

fun macRefusedText(code: Int) = if (code == 401) "The Mac turned this phone's key down." else if (code == 404) MAC_THREAD_GONE else "The Mac refused to be followed (HTTP $code)."

/** The last event shown per thread, as it is kept across a restart. */
fun macCursorsText(cursors: Map<String, Long>): String = JSONObject().apply { for ((thread, last) in cursors) put(thread, last) }.toString()

fun macCursorsFrom(text: String?): Map<String, Long> {
    val saved = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return emptyMap()
    return saved.keys().asSequence().mapNotNull { thread -> saved.macWhole(thread)?.takeIf { it > 0 }?.let { thread to it } }.toMap()
}

/**
 * A request of a thread on the Mac that waits for Boss. [request] holds the runner's nonce, which stays in
 * this process: a button carries only [ticket], made on the phone for this one request. [slot] gives the
 * notification its id; null when too many wait at once, and the request is then answered from the chat alone.
 */
data class MacPending(val threadId: String, val threadName: String, val request: MacApproval, val ticket: String, val slot: Int?)

/** Ids for the notifications of the Mac's approvals, apart from the phone builder's ([ApprovalIds]). */
object MacApprovalIds {
    const val FIRST_NOTIFICATION = 4300
    const val TAG_PREFIX = "oma-mac-approval:"

    fun notificationId(slot: Int): Int {
        require(ApprovalIds.validSlot(slot)) { "No such approval slot: $slot" }
        return FIRST_NOTIFICATION + slot
    }

    fun requestCode(slot: Int, verb: ApprovalVerb): Int {
        require(ApprovalIds.validSlot(slot)) { "No such approval slot: $slot" }
        return FIRST_NOTIFICATION + slot * ApprovalVerb.entries.size + verb.ordinal
    }

    fun tag(ticket: String) = TAG_PREFIX + ticket
}

/** What a button of a Mac approval carries, as the data URI of its intent: `oma-mac-approval:<verb>/<slot>/<ticket>`. Never the nonce. */
data class MacApprovalTicket(val verb: ApprovalVerb, val slot: Int, val ticket: String) {
    fun encode() = "$SCHEME:${verb.word}/$slot/$ticket"

    companion object {
        const val SCHEME = "oma-mac-approval"
        private val TICKET = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Null for anything this app wouldn't have written itself. */
        fun parse(text: String?): MacApprovalTicket? {
            val parts = text?.takeIf { it.startsWith("$SCHEME:") && it.length < 200 }?.removePrefix("$SCHEME:")?.split('/') ?: return null
            if (parts.size != 3) return null
            val verb = ApprovalVerb.entries.firstOrNull { it.word == parts[0] } ?: return null
            val slot = parts[1].takeIf { it.length in 1..2 && it.all(Char::isDigit) }?.toInt()?.takeIf(ApprovalIds::validSlot) ?: return null
            if (!TICKET.matches(parts[2])) return null
            return MacApprovalTicket(verb, slot, parts[2])
        }
    }
}

/** What reading a thread's own list of waiting requests changed. */
data class MacSynced(val added: List<MacPending>, val removed: List<MacPending>)

/**
 * The Mac's requests waiting for Boss. Nothing in here answers one: it only knows which wait. Two sources feed
 * it, a thread's events and the thread's own list of what waits; the list is the Mac's word as of its last
 * event, so an event no newer than the last list read changes nothing here. Not thread safe: MacThreads guards it.
 */
class MacApprovalBook(private val newTicket: () -> String = { UUID.randomUUID().toString() }) {
    private val waiting = LinkedHashMap<String, MacPending>()
    private val asOf = HashMap<String, Long>()

    private fun key(threadId: String, approval: String) = threadId + "\n" + approval

    /** Oldest first. */
    val live: List<MacPending> get() = waiting.values.toList()

    private fun admit(threadId: String, threadName: String, request: MacApproval): MacPending {
        val taken = waiting.values.mapNotNull { it.slot }.toSet()
        val entry = MacPending(threadId, threadName, request, newTicket(), (0 until ApprovalIds.MAX_LIVE).firstOrNull { it !in taken })
        waiting[key(threadId, request.approval)] = entry
        return entry
    }

    /** An `approval` event: the new card, or null for one already waiting or older than the list last read. */
    fun offer(threadId: String, threadName: String, eventId: Long, request: MacApproval): MacPending? {
        if (eventId <= (asOf[threadId] ?: 0L) || key(threadId, request.approval) in waiting) return null
        return admit(threadId, threadName, request)
    }

    /** An `approval_result` event: the card that goes, if it was still there. */
    fun resolve(threadId: String, eventId: Long, approval: String): MacPending? =
        if (eventId <= (asOf[threadId] ?: 0L)) null else waiting.remove(key(threadId, approval))

    /** The turn ended at event [eventId]: the Mac keeps none of its requests waiting past that. */
    fun ended(threadId: String, eventId: Long): List<MacPending> = if (eventId <= (asOf[threadId] ?: 0L)) emptyList() else drop(threadId)

    /** He answered it, or the Mac said it is no longer waiting. */
    fun remove(threadId: String, approval: String): MacPending? = waiting.remove(key(threadId, approval))

    /**
     * The thread's own list, read while none of its events were arriving: what it names waits and nothing else
     * of that thread does. A request that still waits keeps its ticket; one whose nonce changed is a new request.
     */
    fun sync(thread: MacThread): MacSynced {
        asOf[thread.id] = thread.lastEvent
        val named = thread.pending.associateBy { it.approval }
        val removed = waiting.values.filter { it.threadId == thread.id && named[it.request.approval] != it.request }
        removed.forEach { waiting.remove(key(it.threadId, it.request.approval)) }
        val added = named.values.filter { key(thread.id, it.approval) !in waiting }.map { admit(thread.id, macName(thread.name), it) }
        return MacSynced(added, removed)
    }

    fun drop(threadId: String): List<MacPending> {
        val gone = waiting.values.filter { it.threadId == threadId }
        gone.forEach { waiting.remove(key(it.threadId, it.request.approval)) }
        return gone
    }

    fun clear(): List<MacPending> = live.also { waiting.clear(); asOf.clear() }

    fun find(ticket: String): MacPending? = waiting.values.firstOrNull { it.ticket == ticket }
}

/** What a tap on a Mac approval came to. */
enum class MacApprovalOutcome {
    /** The Mac took his answer. */
    Decided,

    /** Answered, withdrawn or gone with its task: nothing waits any more. */
    Expired,

    /** The Mac turned the answer down as not this request's; the request goes on waiting. */
    WrongNonce,

    /** The Mac turned this phone's key down; the request goes on waiting. */
    KeyRefused,

    /** No answer from the Mac; the request goes on waiting there. */
    Unreachable,

    /** The Mac answered something else; the request is taken to go on waiting. */
    Failed,

    /** The phone is locked; nothing was sent. */
    Locked,

    /** His earlier tap on this request is still on its way; this one was not sent. */
    Answering,
    ;

    /** Whether the card goes. Everything else leaves the request waiting for him. */
    val over get() = this == Decided || this == Expired
}

/**
 * How the Mac's answer to his tap is read. Only the Mac saying it took the decision is [MacApprovalOutcome.Decided];
 * the runner answers 409 when the task ended under the request, which like 404 leaves nothing to answer.
 */
fun macApprovalOutcome(answer: MacAnswer<MacDecision>): MacApprovalOutcome = when (answer) {
    is MacAnswer.Ok -> MacApprovalOutcome.Decided
    is MacAnswer.Refused -> MacApprovalOutcome.WrongNonce
    MacAnswer.NotFound -> MacApprovalOutcome.Expired
    MacAnswer.Unauthorized -> MacApprovalOutcome.KeyRefused
    is MacAnswer.Failed -> if (answer.code == 409) MacApprovalOutcome.Expired else MacApprovalOutcome.Failed
    MacAnswer.Unreachable -> MacApprovalOutcome.Unreachable
}

/** What he is told about a tap; null when it simply worked. */
fun macApprovalNotice(outcome: MacApprovalOutcome): String? = when (outcome) {
    MacApprovalOutcome.Decided -> null
    MacApprovalOutcome.Expired -> "That request has expired"
    MacApprovalOutcome.WrongNonce -> "The Mac did not take that answer. The request is still waiting"
    MacApprovalOutcome.KeyRefused -> "The Mac turned this phone's key down. The request is still waiting"
    MacApprovalOutcome.Unreachable -> "Mac unreachable. The request is still waiting there"
    MacApprovalOutcome.Failed -> "The Mac could not take that answer. The request is still waiting"
    MacApprovalOutcome.Locked -> "Unlock your phone to answer that request"
    MacApprovalOutcome.Answering -> "Your answer is on its way"
}

/**
 * The Mac as this phone sees it. Nothing here runs on the caller's thread except reading what is already
 * known: asking the Mac, sending, following and answering each have a thread of their own. Those threads live
 * as the phone builder's task does, in the process the Linux foreground service keeps alive, so a thread is
 * followed whether or not the chat is open. With no config file there is no Mac: nothing is asked, the board
 * has no Mac on it, and the launcher is as it was before there were threads.
 */
object MacThreads {
    @Volatile var view = MacView(); private set

    /** The Mac's requests waiting for Boss, oldest first. One immutable list, as [AgentTasks.pending] is, for the same readers. */
    @Volatile var pending: List<MacPending> = emptyList(); private set

    /** The entries whose words are on their way to the Mac right now. */
    @Volatile var sending: Set<Long> = emptySet(); private set

    private val book = MacApprovalBook()
    @Volatile private var cursors = MacCursors()
    private val followers = HashMap<String, Follower>()

    /** Whether a thread on the Mac is being followed now: its answer or its request to approve is still to come. */
    val following: Boolean get() = synchronized(this) { followers.isNotEmpty() }

    /** Per entry, when its sending ended by [clock]; [IN_FLIGHT] until then. An entry not in here was sent by an earlier run. */
    private val sentAt = HashMap<Long, Long>()
    private val clock = AtomicLong()
    private val answering = HashSet<String>()
    private val refreshing = AtomicBoolean()
    private var restored = false
    @Volatile private var checked = 0L
    private val main = Handler(Looper.getMainLooper())

    /** One thread's follower. [renewed] is set when it is asked for again, so one about to leave stays. */
    private class Follower { var renewed = false }

    private const val PREFS = "mac-threads"
    private const val IN_FLIGHT = Long.MAX_VALUE
    private const val STALE_MS = 60_000L
    private const val NOT_SENT = "Not sent"

    /** Read fresh each time, so the Mac is there the moment Boss puts the file in place and gone when he takes it out. */
    private fun config(context: Context): MacRunnerConfig? =
        runCatching { readMacRunnerConfig((context.applicationContext as App).runtime.proot.launcherConfig) }.getOrNull()

    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cursors = MacCursors(macCursorsFrom(saved.getString("cursors", null)))
        view = MacView(seen = saved.getLong("seen", 0L).takeIf { it > 0 })
        // No card of an earlier run can be answered: its ticket died with that process. They are drawn again from the Mac.
        AgentNotices.clearStaleMac(context)
    }

    /** The chat opened, or the launcher started: learn how the Mac stands and pick its working threads up again. */
    fun open(context: Context) {
        restore(context)
        refresh(context)
    }

    /**
     * The Mac's part of the front agent's board at [now], from what is already known; null with no Mac set up.
     * It never waits for the Mac: a list that has grown old is asked for again beside this request, for the next one.
     */
    fun board(context: Context, now: Long): MacBoard? {
        restore(context)
        if (config(context) == null) return null
        if (now - checked > STALE_MS) refresh(context)
        val known = view
        val tasks = ChatLog.of(context).tasks
        return macBoard(known.threads.map { macOwnSummary(it, tasks) }, known.reachable, known.seen, now)
    }

    /**
     * Home is showing its thread tiles at [now]: a list grown old is asked for again, as [board] does for the
     * front agent, so a thread that began work at the Mac itself gets its tile. With no Mac set up nothing is asked.
     */
    fun glance(context: Context, now: Long) {
        restore(context)
        if (now - checked > STALE_MS && config(context) != null) refresh(context)
    }

    /** What stands beside [task], an entry of the Mac's, while it is not over. */
    fun label(task: ChatTask): String = macEntryLabel(task.status, task.time in sending, view.reachable, pending.any { it.threadId == task.thread })

    private fun reached(context: Context, reachable: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(this) { view = if (reachable) view.copy(reachable = true, seen = now) else view.copy(reachable = false) }
        if (reachable) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("seen", now).apply()
    }

    /** One thread as the Mac just described it, put into the list the board is made from. */
    private fun noted(thread: MacThread) = synchronized(this) {
        view = view.copy(threads = if (view.threads.any { it.id == thread.id }) view.threads.map { if (it.id == thread.id) thread else it } else view.threads + thread)
    }

    private fun name(context: Context, threadId: String): String =
        view.threads.firstOrNull { it.id == threadId }?.let { macName(it.name) }
            ?: ChatLog.of(context).tasks.lastOrNull { it.thread == threadId }?.threadName?.takeIf { it.isNotEmpty() }
            ?: macName("")

    private fun unfinished(context: Context, threadId: String) = ChatLog.of(context).tasks.any { macUnfinished(it, threadId) }

    private fun saveCursors(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cursors", macCursorsText(cursors.snapshot())).apply()

    /** Under the lock: republishes the book for readers on other threads. */
    private fun published() { pending = book.live }

    /**
     * Asks the Mac for its threads, off the caller's thread and one asking at a time. A thread that works or
     * waits, and one a request of his is not finished in, is then followed.
     */
    fun refresh(context: Context) {
        restore(context)
        val app = context.applicationContext
        if (!refreshing.compareAndSet(false, true)) return
        thread(name = "oma-mac-refresh", isDaemon = true) {
            try {
                val config = config(app)
                if (config == null) noMac(app)
                else {
                    checked = System.currentTimeMillis()
                    val client = MacRunnerClient(config)
                    val listed = if (client.health() is MacHealthResult.Up) client.threads() else MacAnswer.Unreachable
                    val threads = if (listed is MacAnswer.Ok) listed.value else emptyList()
                    if (listed is MacAnswer.Ok) synchronized(this) { view = view.copy(threads = threads, listed = true) }
                    reached(app, listed is MacAnswer.Ok)
                    val busy = threads.filter { !macResting(it.status) || it.pending.isNotEmpty() }.map { it.id }
                    val owed = ChatLog.of(app).tasks.filter { it.mac && (it.status == TaskStatus.Running || it.status == TaskStatus.Queued) }.map { it.thread }
                    for (threadId in (busy + owed).distinct()) follow(app, threadId)
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** The config is gone: there is no Mac to follow or answer, and what was unfinished there says so. */
    private fun noMac(context: Context) {
        ChatLog.of(context).revise { macAbandon(it, null, MAC_NOT_SET_UP) }
        val gone = synchronized(this) {
            view = MacView()
            book.clear().also { published() }
        }
        gone.forEach { AgentNotices.macWithdraw(context, it) }
    }

    /**
     * Hands [prompt], his words exactly, to [thread]. The entry sent at [asked] becomes that thread's before
     * anything is sent; the Mac is then asked how it is and sent the words on a thread of its own. [away] is a
     * request spoken on the listening screen: if it cannot be sent he is told over whatever is in front.
     */
    fun handOver(context: Context, asked: Long, prompt: String, thread: MacThread, away: Boolean) {
        restore(context)
        val app = context.applicationContext
        ChatLog.of(app).update(asked, save = true) { macHandOver(it, thread) }
        send(app, asked, sentText(app, asked, prompt, thread.id), thread.id, away)
    }

    /**
     * His words typed on a thread's own tab in the chat: they go to the thread [threadId], called [name], as
     * they are. The front agent is not asked and nothing is put ahead of them. The entry is filed first, under
     * the build lane's lock as every entry's time is taken, and the words then go the way a hand-over's do:
     * the Mac is asked how it is, and one that does not answer leaves the entry saying so with `[ try again ]`.
     */
    fun say(context: Context, prompt: String, threadId: String, name: String) {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val time = synchronized(AgentTasks) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { log.begin(macDirect(it, prompt, threadId, name)) }
        }
        send(app, time, prompt, threadId, away = false)
    }

    /**
     * What the thread is sent for the request filed at [asked]: his words, behind what he and the front agent
     * said since that thread last got anything. The entry and the routing log are given [prompt] alone. Words he
     * sent straight to the thread go as they are, however often they are sent again.
     */
    private fun sentText(context: Context, asked: Long, prompt: String, threadId: String): String {
        val tasks = ChatLog.of(context).tasks
        if (tasks.any { it.time == asked && it.direct }) return prompt
        return handOverText(handOverPreface(handOverContext(tasks, asked) { threadGot(it, threadId) }), prompt)
    }

    /** `[ try again ]` on a request the Mac never got: the same step once more, and a line in the routing log. */
    fun tryAgain(context: Context, time: Long) {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val task = log.tasks.firstOrNull { it.time == time }?.takeIf { macRetried(it) != null } ?: return
        log.update(time, save = true) { macRetried(it) ?: it }
        RoutingLog.corrected(app, task.prompt, MAC_TRIED_AGAIN)
        send(app, time, sentText(app, time, task.prompt, task.thread), task.thread, away = false)
    }

    /**
     * Asks the Mac how it is, inside the two-second limit, and sends [text] if it answers. A Mac that does not
     * leaves the entry saying so with his words kept; nothing is given to the phone's builder instead. When the
     * words were sent and no answer came back, it is not known whether the Mac has them: the entry waits, and
     * the thread's follower finds out from the Mac itself.
     */
    private fun send(context: Context, time: Long, text: String, threadId: String, away: Boolean) {
        synchronized(this) {
            sentAt[time] = IN_FLIGHT
            sending = sending + time
        }
        thread(name = "oma-mac-send", isDaemon = true) {
            val log = ChatLog.of(context)
            var problem: String? = MAC_UNREACHABLE
            try {
                val client = MacRunnerClient(config(context))
                val health = client.health()
                reached(context, health is MacHealthResult.Up)
                problem = when (macRoute(ThreadHome.Mac, health, startedOnMac = false, phoneHasClone = false)) {
                    MacRoute.RunOnMac -> {
                        val taken = client.sendMessage(threadId, text)
                        if (taken is MacAnswer.Ok && taken.value == MacTaken.Now) log.update(time, save = true, change = ::macTaken)
                        macUnsentReason(taken)
                    }
                    else -> macUnreachableReason(health)
                }
            } finally {
                synchronized(this) {
                    sentAt[time] = clock.incrementAndGet()
                    sending = sending - time
                }
            }
            val why = problem
            if (why == null) follow(context, threadId)
            else {
                log.update(time, save = true) { macUnsent(it, why) }
                if (away) AgentNotices.answered(context, NOT_SENT, why, AgentNotices.FRONT)
            }
            refresh(context)
        }
    }

    /** Starts following [threadId], or tells the follower it has to stay. */
    private fun follow(context: Context, threadId: String) {
        val mine = synchronized(this) {
            val running = followers[threadId]
            if (running != null) {
                running.renewed = true
                return
            }
            Follower().also { followers[threadId] = it }
        }
        thread(name = "oma-mac-follow", isDaemon = true) {
            try {
                following(context, threadId, mine)
            } finally {
                synchronized(this) { if (followers[threadId] === mine) followers.remove(threadId) }
            }
        }
    }

    /** True when the follower may go; false when it was asked for again meanwhile and has to look once more. */
    private fun leave(mine: Follower, threadId: String): Boolean = synchronized(this) {
        if (mine.renewed) {
            mine.renewed = false
            false
        } else {
            followers.remove(threadId)
            true
        }
    }

    private fun pause(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }

    /**
     * Follows one thread until it rests with nothing of his unfinished in it. Each round first asks the Mac how
     * the thread stands, with no stream open, so what it says of its last event, its waiting requests and its
     * waiting turns is one moment's truth: a record that was cleared resets the place, the cards are made to
     * match, events missed are fetched, and a resting thread is settled. Only a thread with a task is then
     * listened to, until its turn ends or the link drops. Nothing in here sends his words anywhere.
     */
    private fun following(context: Context, threadId: String, mine: Follower) {
        val log = ChatLog.of(context)
        var misses = 0
        while (true) {
            val config = config(context)
            if (config == null) {
                noMac(context)
                return
            }
            val client = MacRunnerClient(config)
            val asked = clock.incrementAndGet()
            when (val known = client.thread(threadId)) {
                is MacAnswer.Ok -> {
                    val seen = known.value
                    reached(context, true)
                    noted(seen)
                    if (seen.lastEvent < cursors.after(threadId)) {
                        cursors.forget(threadId)
                        saveCursors(context)
                    }
                    synced(context, seen)
                    if (cursors.after(threadId) < seen.lastEvent) {
                        val page = client.events(threadId, cursors.after(threadId))
                        val fresh = if (page is MacAnswer.Ok) cursors.catchUp(threadId, page.value.events) else emptyList()
                        if (fresh.isEmpty()) pause(macRetryDelay(++misses))
                        else {
                            misses = 0
                            fresh.forEach { shown(context, threadId, it) }
                        }
                        continue
                    }
                    if (macResting(seen.status)) {
                        val late = synchronized(this) { sentAt.filterValues { it >= asked }.keys.toSet() }
                        log.revise { tasks -> macSettle(tasks, threadId, seen.status, seen.waitingTurns) { it.time !in late } }
                        if (!unfinished(context, threadId)) {
                            if (leave(mine, threadId)) {
                                refresh(context)
                                return
                            }
                        } else pause(macRetryDelay(++misses))
                        continue
                    }
                    var heard = false
                    val turned = AtomicBoolean()
                    val end = client.stream(threadId, cursors.after(threadId), { turned.get() }) { event ->
                        if (cursors.offer(threadId, event) == MacOffer.Show) {
                            heard = true
                            shown(context, threadId, event)
                            if (macTurnsOver(event)) turned.set(true)
                        }
                    }
                    when (end.why) {
                        MacStreamWhy.Refused -> {
                            abandon(context, threadId, macRefusedText(end.code))
                            return
                        }
                        MacStreamWhy.Dropped -> if (heard) misses = 0 else pause(macRetryDelay(++misses))
                        MacStreamWhy.Gap, MacStreamWhy.Stopped -> misses = 0
                    }
                }
                MacAnswer.NotFound -> {
                    abandon(context, threadId, MAC_THREAD_GONE)
                    return
                }
                MacAnswer.Unauthorized -> {
                    abandon(context, threadId, macRefusedText(401))
                    return
                }
                else -> {
                    reached(context, false)
                    // Out of reach with nothing of his there and no card of its to keep: nothing to wait for.
                    if (!unfinished(context, threadId) && pending.none { it.threadId == threadId } && leave(mine, threadId)) return
                    pause(macRetryDelay(++misses))
                }
            }
        }
    }

    /** The thread cannot be followed: its entries say why, and its cards go, since they could not be answered. */
    private fun abandon(context: Context, threadId: String, reason: String) {
        ChatLog.of(context).revise { macAbandon(it, threadId, reason) }
        val gone = synchronized(this) { book.drop(threadId).also { published() } }
        gone.forEach { AgentNotices.macWithdraw(context, it) }
    }

    /** Makes the cards match the thread's own list of what waits, and the entries with them. */
    private fun synced(context: Context, seen: MacThread) {
        val change = synchronized(this) { book.sync(seen).also { published() } }
        val log = ChatLog.of(context)
        for (gone in change.removed) {
            AgentNotices.macWithdraw(context, gone)
            log.revise { macAnswered(it, seen.id, gone.request.approval, ApprovalAnswer.Unanswered) }
        }
        for (added in change.added) {
            log.revise { macAsked(it, seen.id, added.request) }
            AgentNotices.macRequest(context, added)
        }
    }

    /**
     * One new event of a thread, in order: into the Oma thread and onto disk first, then the place it was read
     * to, so a restart between the two shows an event twice rather than never. An approval becomes a card for
     * Boss and nothing else; nothing here answers one.
     */
    private fun shown(context: Context, threadId: String, event: MacEvent) {
        val log = ChatLog.of(context)
        val before = log.tasks
        val flying = synchronized(this) { sentAt.filterValues { it == IN_FLIGHT }.keys.toSet() }
        // The runner says which agent a thread runs ("claude") but not which model answers, so its replies carry the agent's name.
        val by = agentName(view.threads.firstOrNull { it.id == threadId }?.kind).orEmpty()
        log.revise { macApply(it, threadId, event, flying, by) }
        when (event) {
            is MacEvent.Approval -> {
                val threadName = name(context, threadId)
                val added = synchronized(this) { book.offer(threadId, threadName, event.id, event.request).also { published() } }
                if (added != null) AgentNotices.macRequest(context, added)
            }
            is MacEvent.ApprovalResult -> {
                val gone = synchronized(this) { book.resolve(threadId, event.id, event.approval).also { published() } }
                if (gone != null) AgentNotices.macWithdraw(context, gone)
            }
            is MacEvent.Result, is MacEvent.Error, is MacEvent.Stopped -> {
                val gone = synchronized(this) { book.ended(threadId, event.id).also { published() } }
                gone.forEach { AgentNotices.macWithdraw(context, it) }
                // A request of his that just ended comes to him as the phone builder's does; one he stopped says nothing more.
                val ended = log.tasks.firstOrNull { now ->
                    now.thread == threadId && (now.status == TaskStatus.Done || now.status == TaskStatus.Failed) && before.any { it.time == now.time && it.status == TaskStatus.Running }
                }
                if (ended != null) AgentNotices.answered(context, if (ended.status == TaskStatus.Done) ended.threadName else "${ended.threadName} needs attention", ended.result, AgentNotices.MAC)
            }
            is MacEvent.Status -> synchronized(this) { view = view.copy(threads = view.threads.map { if (it.id == threadId) it.copy(status = event.status) else it }) }
            is MacEvent.Message, is MacEvent.Text, is MacEvent.Step, is MacEvent.Other -> Unit
        }
        saveCursors(context)
    }

    /**
     * `[ stop ]` on an entry of the Mac's: stops that thread's task, which also drops what waited behind it
     * there. The entries end when the thread says it stopped. [told] gets what there is to tell him, null when
     * the Mac simply did it.
     */
    fun stop(context: Context, threadId: String, told: (String?) -> Unit) {
        restore(context)
        val app = context.applicationContext
        thread(name = "oma-mac-stop", isDaemon = true) {
            val said = when (val stopped = MacRunnerClient(config(app)).stop(threadId)) {
                is MacAnswer.Ok -> if (stopped.value) null else "Nothing was running on the Mac for this."
                MacAnswer.Unreachable -> "Mac unreachable. It is still working there."
                else -> "The Mac did not stop it."
            }
            follow(app, threadId)
            main.post { told(said) }
        }
    }

    /**
     * His tap on one waiting request: the card in the chat, the notification's buttons and the approval screen
     * all come through here, and nothing else calls the Mac with an answer. Nothing is answered from a locked
     * phone. The answer goes with that request's own nonce; the card goes only when the Mac says it took the
     * answer or that nothing waits any more, and otherwise stays for him. [told] is called on the main thread.
     */
    fun answer(context: Context, ticket: String, allow: Boolean, told: (MacApprovalOutcome) -> Unit) {
        restore(context)
        val app = context.applicationContext
        if (AgentNotices.deviceLocked(app)) return told(MacApprovalOutcome.Locked)
        val entry = synchronized(this) {
            val waiting = book.find(ticket) ?: return told(MacApprovalOutcome.Expired)
            if (!answering.add(ticket)) return told(MacApprovalOutcome.Answering)
            waiting
        }
        thread(name = "oma-mac-answer", isDaemon = true) {
            var outcome = MacApprovalOutcome.Unreachable
            try {
                outcome = macApprovalOutcome(MacRunnerClient(config(app)).answerApproval(entry.threadId, entry.request.approval, allow, entry.request.nonce))
                if (outcome.over) {
                    val gone = synchronized(this) { book.remove(entry.threadId, entry.request.approval).also { published() } }
                    if (gone != null) AgentNotices.macWithdraw(app, gone)
                    val answered = if (outcome != MacApprovalOutcome.Decided) ApprovalAnswer.Unanswered else if (allow) ApprovalAnswer.Approved else ApprovalAnswer.Denied
                    ChatLog.of(app).revise { macAnswered(it, entry.threadId, entry.request.approval, answered) }
                }
            } finally {
                synchronized(this) { answering.remove(ticket) }
            }
            val result = outcome
            main.post { told(result) }
        }
    }
}
