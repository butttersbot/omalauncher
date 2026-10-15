package dev.omalauncher.assist

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.omalauncher.App
import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLater
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TabBook
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.chatKey
import dev.omalauncher.oma.onMac
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
 * sent, which for a request handed over with context or with a note about shared work is that heading or note:
 * the first line of his own words, from the entry of that request, stands there instead. Any other summary is
 * left as the Mac gave it.
 */
fun macOwnSummary(thread: MacThread, tasks: List<ChatTask>): MacThread =
    if (!thread.summary.startsWith(CONTEXT_OPEN) && !thread.summary.startsWith(SHARED_NOTE_OPEN)) thread
    else thread.copy(summary = tasks.lastOrNull { threadGot(it, thread.id) }?.prompt?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty())

/**
 * The guest's `oma threads`, which lists the Mac's threads and makes one in a folder Boss names. A command and
 * not a power of the front agent's: the phone's builder runs it under his approval rules, or he does in a
 * terminal, and the runner alone decides which folders it takes. Installed with the other guest tools.
 */
fun macGuestTools(context: Context, rootfs: File): List<Managed> =
    listOf(Managed(File(rootfs, "usr/local/lib/oma/thread"), Policy.Owned, executable = true) { context.assets.open("oma-thread.sh").use { it.readBytes() } })

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
const val MAC_SENT_ELSEWHERE = "Sent from elsewhere: "
const val MAC_EMPTY_TURN = "The Mac ended this turn without an answer."

/** How long to wait before asking a Mac that did not answer again: 2 s, 5 s, 15 s, then every 30 s. [misses] counts from 1. */
fun macRetryDelay(misses: Int): Long = when {
    misses <= 1 -> 2_000L
    misses == 2 -> 5_000L
    misses == 3 -> 15_000L
    else -> 30_000L
}

/** Whether a thread in this state has no task: nothing more will come from it until it is sent something. */
fun macResting(status: MacThreadStatus) = status == MacThreadStatus.Idle || status == MacThreadStatus.Interrupted

/** How the runner begins its note on work an answer left running ("Still running on the Mac: copy the games. …"). */
const val MAC_STILL_RUNNING = "Still running on the Mac: "

/** How long after its last turn a resting thread of his is still listened to: Claude Code may report on its own soon after. */
const val MAC_AWAKE_MS = 5 * 60_000L

/**
 * Whether a resting [thread] may still say something between his messages at [now]: it lists work left running
 * ([MacThread.background]), whose end Claude Code reports, or its last turn was within [MAC_AWAKE_MS]. Its
 * [MacThread.lastWorked] is the Mac's clock; a minute either way changes only how long it is listened to.
 */
fun macAwake(thread: MacThread, now: Long): Boolean =
    thread.background.isNotEmpty() || thread.lastWorked?.let { now - it * 1000 < MAC_AWAKE_MS } == true

/**
 * The threads to follow after the Mac listed [threads] at [now]: every one that works or waits, every one [owed]
 * a request of his, and of those [his] requests went to, one that may still say something between his messages
 * ([macAwake]) or said something since the event the phone last showed ([after]), so a report made while nobody
 * listened is caught up. One with no place kept is not caught up: all it ever said would come under his newest
 * entry. A new chat's thread still being made is nothing on the Mac yet, so it is never followed.
 */
fun macFollowed(threads: List<MacThread>, owed: Collection<String>, his: Set<String>, after: (String) -> Long, now: Long): List<String> {
    val busy = threads.filter { !macResting(it.status) || it.pending.isNotEmpty() }
    val since = threads.filter { it.id in his && (macAwake(it, now) || after(it.id) in 1 until it.lastEvent) }
    return ((busy + since).map { it.id } + owed).distinct() - MAC_NEW_THREAD
}

/** Whether [event] can be the last of a turn, after which the thread may rest: the follower then asks the Mac how it stands. */
fun macTurnsOver(event: MacEvent) = event is MacEvent.Result || event is MacEvent.Stopped || event is MacEvent.Error || (event is MacEvent.Status && macResting(event.status))

/** A request the Mac has, or may have, and has not finished. */
fun macUnfinished(task: ChatTask, threadId: String) = task.thread == threadId && (task.status == TaskStatus.Running || task.status == TaskStatus.Queued)

/**
 * The entry of a request on its way to [thread]: it carries the receipt, the thread's id and name, and waits
 * until the Mac says it took it. Done before anything is sent, so what the thread says about it finds it.
 */
fun macHandOver(task: ChatTask, thread: MacThread) =
    task.copy(agent = macReceipt(thread.name), thread = thread.id, threadName = macName(thread.name), status = TaskStatus.Queued, result = "", fresh = false)

/** The last part of a project's path, which is enough for the front agent to see that two threads share one. */
fun macFolder(project: String): String = project.trim().trimEnd('/').substringAfterLast('/')

/**
 * A project's path as two threads on it can be compared by: the runner understands `~`, so a home written out
 * in full and one written as `~` are the same folder, and a closing slash makes no difference.
 */
fun macProjectKey(project: String): String =
    project.trim().trimEnd('/').replace(Regex("^/(Users|home)/[^/]+(?=/|$)"), "~")

/**
 * Whether [thread] has work on it: a task or a step waiting for Boss, or turns waiting on the Mac; [owed] is
 * whether the phone has a request of his there that is not finished, which the Mac may not have said yet.
 */
fun macBusy(thread: MacThread, owed: Boolean = false): Boolean =
    owed || thread.status == MacThreadStatus.Working || thread.status == MacThreadStatus.Waiting || thread.waitingTurns > 0

/** The threads the phone has an unfinished request of his in, the entry filed at [except] left out. */
fun macOwed(tasks: List<ChatTask>, except: Long? = null): Set<String> =
    tasks.filter { it.time != except && !it.fresh && macUnfinished(it, it.thread) }.map { it.thread }.toSet()

/**
 * The most threads working at once in one project folder that separate work may lead to. Past it, a new
 * thread would only add another agent to the same files: the request waits on one that works instead.
 */
const val MAC_THREADS_PER_PROJECT = 3

/** Where separate work for the project of a busy thread goes. */
sealed interface MacFresh {
    /** The thread named is free after all: the request goes to it, as a hand-over to it would. */
    data class Same(val thread: MacThread) : MacFresh

    /** A new thread called [name] is made in [project], and the request goes to it. */
    data class Make(val name: String, val project: String) : MacFresh

    /** [working] threads already work in that folder, as many as may: the request waits its turn on [thread]. */
    data class Queue(val thread: MacThread, val working: Int) : MacFresh
}

/**
 * Where separate work for the project of [named] goes, from the Mac's [threads] and the ones [owed] a request of
 * his. A [named] that is not busy takes it. Otherwise a new thread is made in its folder, unless as many as
 * [MAC_THREADS_PER_PROJECT] work there already: then it waits on the one of them with the fewest turns waiting,
 * [named] on a tie, since that is the one he was talking about. The first words of a new chat ([newChat]) always
 * get a thread of their own (Boss, 2026-10-13: a new chat never goes into a conversation that has anything in it).
 */
fun macFresh(named: MacThread, threads: List<MacThread>, owed: Set<String> = emptySet(), newChat: Boolean = false): MacFresh {
    if (newChat) return MacFresh.Make(macFreshName(named.name, threads.map { it.name } + named.name), named.project)
    if (!macBusy(named, named.id in owed)) return MacFresh.Same(named)
    val folder = macProjectKey(named.project)
    val working = (listOf(named) + threads.filter { it.id != named.id && macProjectKey(it.project) == folder && macBusy(it, it.id in owed) })
    if (working.size >= MAC_THREADS_PER_PROJECT) return MacFresh.Queue(working.minBy { it.waitingTurns }, working.size)
    return MacFresh.Make(macFreshName(named.name, threads.map { it.name } + named.name), named.project)
}

/** The longest name given to a new thread, number included: what an entry keeps of a thread's name. */
internal const val FRESH_NAME_CHARS = 60

/**
 * The name of a new thread beside the one called [name]: the same, with the next number no thread in [names]
 * has ("FF4 better menu mod 2", then 3). The number of a thread that already has one is replaced, not added to,
 * and so is the agent a thread made at a limit is named after ([macKindThreadName]).
 */
fun macFreshName(name: String, names: List<String>): String {
    val base = macBaseName(name)
    val taken = names.map { it.trim().lowercase() }.toSet()
    val number = generateSequence(2) { it + 1 }.first { "$base $it".lowercase() !in taken }
    return base.take(FRESH_NAME_CHARS - 1 - number.toString().length).trimEnd() + " " + number
}

/**
 * Whether thread [receiverId], handed work now, gets [MAC_SHARED_NOTE] ahead of it: another thread in the same
 * project folder has work on it ([macBusy], [owed] as there). Not when it is the only one at work there.
 */
fun macShares(receiverId: String, threads: List<MacThread>, owed: Set<String> = emptySet()): Boolean {
    val receiver = threads.firstOrNull { it.id == receiverId } ?: return false
    val folder = macProjectKey(receiver.project).ifEmpty { return false }
    return threads.any { it.id != receiverId && macProjectKey(it.project) == folder && macBusy(it, it.id in owed) }
}

/**
 * The entry of separate work while a new thread called [name] is made for the project of [busy]: it carries the
 * receipt of the new thread and waits under [busy]'s id, marked [ChatTask.fresh], until that thread is there.
 */
fun macFreshHandOver(task: ChatTask, busy: MacThread, name: String) =
    task.copy(agent = macReceipt(name), thread = busy.id, threadName = macName(name), status = TaskStatus.Queued, result = "", fresh = true)

/** The new thread was made: the entry is [made]'s from now on. One that is no longer waiting for it stays as it is. */
fun macMade(task: ChatTask, made: MacThread) =
    if (task.fresh && task.status == TaskStatus.Queued) task.copy(agent = macReceipt(made.name), thread = made.id, threadName = macName(made.name), fresh = false) else task

/**
 * The line an entry carries when its request waits on a busy thread because [working] threads already work in
 * [folder]. A step, so it stands above whatever the thread then says and no answer replaces it.
 */
fun macCappedStep(working: Int, folder: String) = "$working threads already work in ${folder.ifEmpty { "this project" }}, so this waits its turn"

/** Why a new thread was not made, from what the Mac answered; null when it was. */
fun macMadeReason(answer: MacAnswer<MacThread>): String? = when (answer) {
    is MacAnswer.Ok -> null
    MacAnswer.Unreachable -> MAC_UNREACHABLE
    MacAnswer.Unauthorized -> MAC_KEY_REFUSED
    is MacAnswer.Refused -> "The Mac would not make a new thread in that folder" + (if (answer.roots.isEmpty()) "." else ": it takes only " + answer.roots.joinToString(" and ") + ".")
    MacAnswer.NotFound -> "The Mac could not make a new thread (HTTP 404)."
    is MacAnswer.Failed -> "The Mac could not make a new thread (HTTP ${answer.code})."
}

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
 * [task] as it is sent again; null when it must not be: only a request the Mac never got, or whose turn ended with
 * nothing in it ([MAC_EMPTY_TURN]), is, and never one it took and answered or may still have.
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

/**
 * The entry what [threadId] says goes to while no request of his is running there (Claude Code's own turn about
 * background work, between his messages): its newest one the Mac has had. Null when the phone sent it nothing.
 */
fun macLatest(tasks: List<ChatTask>, threadId: String): ChatTask? = tasks.lastOrNull { it.thread == threadId && !it.fresh && it.status != TaskStatus.Queued }

/** Whether nothing of [task]'s turn has been heard: no words, step or card yet, as when it runs only because the Mac said it took it. */
fun macUnheard(task: ChatTask) = task.result.isEmpty() && task.steps.isEmpty() && task.approvals.isEmpty()

/**
 * [task] with [said] added to what its thread said after it. The agent's words run on into the words before
 * them, as a turn's do into its answer; a quiet line that only repeats the one before is left out.
 */
fun macLater(task: ChatTask, said: ChatLater): ChatTask {
    val text = if (said.words) said.text.trimEnd() else said.text.trim().replace(Regex("\\s+"), " ")
    if (text.isBlank()) return task
    val last = task.later.lastOrNull()
    val later = when {
        said.words && last?.words == true -> task.later.dropLast(1) + last.copy(text = last.text + "\n" + text)
        !said.words && last?.words == false && last.text == text -> return task
        else -> task.later + ChatLater(text, said.words)
    }
    return task.copy(later = later.takeLast(ChatHistory.MAX_LATER))
}

/**
 * The request [threadId] is working on gets a line for [request], waiting; once only, however often it is heard
 * of. With none running, a card asked for in the agent's own turn between his messages goes to [macLatest].
 */
fun macAsked(tasks: List<ChatTask>, threadId: String, request: MacApproval): List<ChatTask> {
    val current = tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running } ?: macLatest(tasks, threadId) ?: return tasks
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
 * meant. The runner gives a message no id of the phone's, so his words are known by their text alone; the thread
 * says back all it was sent, so they are looked for after the context too ([handOverWords]). Events of a task
 * that was not sent from this phone find no entry of their own and come under his newest one. [inFlight] are
 * the entries still on their way to the Mac, which a stop that came before them does not end. [answeredBy] is
 * what the reply is named, given to the entry with its first words unless it already has a name.
 */
fun macApply(tasks: List<ChatTask>, threadId: String, event: MacEvent, inFlight: Set<Long> = emptySet(), answeredBy: String = ""): List<ChatTask> {
    val current = tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running }
    fun running(change: (ChatTask) -> ChatTask) = if (current == null) tasks else tasks.map { if (it.time == current.time) change(it) else it }
    // While none of his requests runs there, what the thread says is the agent's own (its report on background
    // work, or the runner's notes on it) and goes under the newest entry, after its answer.
    fun between(said: ChatLater, from: List<ChatTask> = tasks, change: (ChatTask) -> ChatTask = { it }): List<ChatTask> {
        val latest = macLatest(from, threadId) ?: return from
        return from.map { if (it.time == latest.time) macLater(change(it), said) else it }
    }
    return when (event) {
        is MacEvent.Message -> {
            val words = handOverWords(event.text)
            fun said(task: ChatTask) = task.prompt == event.text || task.prompt == words
            when {
                // Its own words said back start nothing more.
                current != null && said(current) -> tasks
                // Other words while one of his has heard nothing yet: it runs only because the Mac said it took it at
                // once, and this is an earlier turn the phone hears only now, as when his first words to a thread it
                // had not followed bring that thread's past with them. It waits for its own words again.
                current != null && !macUnheard(current) -> tasks
                else -> {
                    val waiting = if (current == null) tasks else tasks.map { if (it.time == current.time) it.copy(status = TaskStatus.Queued) else it }
                    // One waiting for a new thread is not this thread's, even while it stands under its id.
                    val next = waiting.firstOrNull { it.thread == threadId && !it.fresh && it.status == TaskStatus.Queued && said(it) }
                    // Words no entry of his has were sent from somewhere else: what the thread says next is their answer,
                    // and stands under this line rather than reading as more of his last answer.
                    if (next == null) between(ChatLater(MAC_SENT_ELSEWHERE + event.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()), waiting)
                    else waiting.map { if (it.time == next.time) it.copy(status = TaskStatus.Running) else it }
                }
            }
        }
        is MacEvent.Text -> if (current == null) between(ChatLater(event.text, words = true)) else running { it.copy(result = it.result + event.text.trimEnd() + "\n", answeredBy = it.answeredBy.ifEmpty { answeredBy }) }
        is MacEvent.Step -> if (current == null) between(ChatLater(event.text)) else running { ChatHistory.withStep(it, event.text) }
        // Any other word on work left running (it ended, was reported or was stopped) means the line saying it still runs no longer holds.
        is MacEvent.Note -> if (current != null) running { ChatHistory.withStep(it, event.text) }
            else if (event.text.startsWith(MAC_STILL_RUNNING)) between(ChatLater(event.text))
            else between(ChatLater(event.text)) { entry -> entry.copy(later = entry.later.filterNot { !it.words && it.text.startsWith(MAC_STILL_RUNNING) }) }
        is MacEvent.Approval -> macAsked(tasks, threadId, event.request)
        is MacEvent.ApprovalResult -> macAnswered(tasks, threadId, event.approval, approvalAnswer(event.decision))
        // The result is the whole answer, as it is for the phone's builder; with no words, what was said so far stays.
        // An empty one ending a turn in which nothing at all happened answered nothing (a runner before 2026-10-14
        // ended his turn on Claude Code's own turn about background work): his to send again, never "Task completed.".
        is MacEvent.Result -> running {
            if (!event.failed && event.text.isBlank() && it.result.isBlank() && it.steps.isEmpty() && it.approvals.isEmpty()) it.over(TaskStatus.Dropped, MAC_EMPTY_TURN)
            else {
                val none = if (event.failed) "The agent needs attention." else "Task completed."
                it.over(if (event.failed) TaskStatus.Failed else TaskStatus.Done, event.text.ifBlank { it.result.ifBlank { none } }).copy(answeredBy = it.answeredBy.ifEmpty { answeredBy })
            }
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
            // One waiting for a new thread only stands under this one's id: it was never sent here.
            task.thread != threadId || task.fresh || !sent(task) -> task
            task.status == TaskStatus.Running -> task.over(TaskStatus.Failed, withReason(task.result, why))
            task.status == TaskStatus.Queued && waitingTurns == 0 -> task.copy(status = TaskStatus.Dropped, result = MAC_NOT_TAKEN)
            else -> task
        }
    }
}

/**
 * The thread can no longer be followed at all ([reason] says why): its unfinished entries need his attention.
 * They are not offered to be sent again, since the Mac may have them. [threadId] null is every thread. One
 * waiting for a new thread beside it was never sent to it, and is left to the making of that thread.
 */
fun macAbandon(tasks: List<ChatTask>, threadId: String?, reason: String): List<ChatTask> = tasks.map { task ->
    val its = threadId == null || (task.thread == threadId && !task.fresh)
    if (task.mac && its && (task.status == TaskStatus.Running || task.status == TaskStatus.Queued)) task.over(TaskStatus.Failed, withReason(task.result, reason))
    else task
}

/** What an entry says once the Mac holds its request behind the task the thread is on. */
const val MAC_QUEUED = "Queued on the Mac"

/** What an entry says while a new thread is made for it. */
const val MAC_MAKING = "Starting a new thread on the Mac"

/** What stands beside an entry of the Mac's while it is not over; [fresh] while a new thread is made for it. */
fun macEntryLabel(status: TaskStatus, sending: Boolean, reachable: Boolean, approvals: Boolean, fresh: Boolean = false): String = when {
    status == TaskStatus.Queued && fresh -> MAC_MAKING
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
    /** The agents the Mac has, as its `/health` last said; empty until it has. */
    @Volatile private var kinds: List<String> = emptyList()
    /** The agents heard to be at their limit lately, kept in prefs under "limits". Only touched under the lock. */
    private val limits = MacLimits()
    /**
     * What each entry was last sent, his words with whatever went ahead of them, so that a turn ending at a limit
     * is sent on exactly as it went. In memory only: after a restart the same is made again from the thread.
     */
    private val sentWords = HashMap<Long, String>()
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
    private const val RESTING_LISTEN_MS = 60_000L
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
        limits.restore(saved.getString("limits", null), System.currentTimeMillis())
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
        return macBoard(known.threads.map { macOwnSummary(it, tasks) }, known.reachable, known.seen, now, TailscaleHeal.note(now))
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
    fun label(task: ChatTask): String = macEntryLabel(task.status, task.time in sending, view.reachable, pending.any { it.threadId == task.thread }, task.fresh)

    /** What asking `/health` came to: whether the Mac answered, and which agents it has when it did. */
    private fun heard(context: Context, health: MacHealthResult) {
        if (health is MacHealthResult.Up) kinds = health.health.kinds
        reached(context, health is MacHealthResult.Up)
        TailscaleHeal.after(context, macLink(health))
    }

    /** The agents taken to be at their limit now. */
    private fun limited(): Set<String> = synchronized(this) { limits.at(System.currentTimeMillis()) }

    private fun reached(context: Context, reachable: Boolean) {
        val now = System.currentTimeMillis()
        see(context) { if (reachable) it.copy(reachable = true, seen = now) else it.copy(reachable = false) }
        if (reachable) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("seen", now).apply()
    }

    /**
     * Changes what the phone knows of the Mac. Anything more than when it last answered goes to the status file at
     * once, so `oma status` says what a thread does now and not what it did at the builder's last look.
     */
    private fun see(context: Context, change: (MacView) -> MacView) {
        val moved = synchronized(this) {
            val before = view
            view = change(before)
            before.copy(seen = null) != view.copy(seen = null)
        }
        if (!moved) return
        BuilderWatch.macChanged(context)
        told(context)
    }

    /** One thread as the Mac just described it, put into the list the board is made from. */
    private fun noted(context: Context, thread: MacThread) = see(context) { known ->
        known.copy(threads = if (known.threads.any { it.id == thread.id }) known.threads.map { if (it.id == thread.id) thread else it } else known.threads + thread)
    }

    private fun name(context: Context, threadId: String): String =
        view.threads.firstOrNull { it.id == threadId }?.let { macName(it.name) }
            ?: ChatLog.of(context).tasks.lastOrNull { it.thread == threadId }?.threadName?.takeIf { it.isNotEmpty() }
            ?: macName("")

    private fun unfinished(context: Context, threadId: String) = ChatLog.of(context).tasks.any { macUnfinished(it, threadId) }

    private fun saveCursors(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cursors", macCursorsText(cursors.snapshot())).apply()

    /** Under the lock: republishes the book for readers on other threads, and the tiles, ring and lock widget count its requests. */
    private fun published(context: Context) {
        pending = book.live
        told(context)
    }

    /** The Mac's work may look different now: the tiles, the ring and the lock widget are told, off this thread and lock. */
    private fun told(context: Context) = AgentTasks.macChanged(context)

    /**
     * Asks the Mac for its threads, off the caller's thread and one asking at a time. The threads [macFollowed]
     * names are then followed: those at work, those owed a request of his, and those of his that may still say
     * something between his messages or said something nobody heard.
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
                    val health = client.health()
                    // Also how the launcher's start turns Tailscale back on after a reboot: Runtime.start opens this.
                    TailscaleHeal.after(app, macLink(health))
                    if (health is MacHealthResult.Up) kinds = health.health.kinds
                    val listed = if (health is MacHealthResult.Up) client.threads() else MacAnswer.Unreachable
                    val threads = if (listed is MacAnswer.Ok) listed.value else emptyList()
                    if (listed is MacAnswer.Ok) see(app) { it.copy(threads = threads, listed = true) }
                    reached(app, listed is MacAnswer.Ok)
                    val tasks = ChatLog.of(app).tasks
                    val owed = tasks.filter { it.mac && (it.status == TaskStatus.Running || it.status == TaskStatus.Queued) }.map { it.thread }
                    val his = tasks.filter { it.mac && !it.fresh }.map { it.thread }.toSet()
                    for (threadId in macFollowed(threads, owed, his, cursors::after, System.currentTimeMillis())) follow(app, threadId)
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** The config is gone: there is no Mac to follow or answer, and what was unfinished there says so. */
    private fun noMac(context: Context) {
        ChatLog.of(context).revise { macAbandon(it, null, MAC_NOT_SET_UP) }
        val gone = synchronized(this) { book.clear().also { published(context) } }
        see(context) { MacView() }
        gone.forEach { AgentNotices.macWithdraw(context, it) }
    }

    /**
     * Hands [prompt], his words exactly, to [thread]. The entry sent at [asked] becomes that thread's before
     * anything is sent; the Mac is then asked how it is and sent the words on a thread of its own. [away] is a
     * request spoken on the listening screen: if it cannot be sent he is told over whatever is in front.
     * [fresh] is separate work on the project [thread] is busy with, which goes where [place] says; [newChat] is
     * the first words of a new chat, which always get a new thread on that project. Either way, while the agent
     * it would go to is known to be at its limit, another takes it ([deliver]).
     */
    fun handOver(context: Context, asked: Long, prompt: String, thread: MacThread, away: Boolean, fresh: Boolean = false, newChat: Boolean = false) {
        restore(context)
        val app = context.applicationContext
        if (fresh || newChat) return place(app, asked, prompt, thread, away, newChat)
        deliver(app, asked, prompt, thread, away)
    }

    /**
     * Gives the request filed at [time] to [thread], with [step] on its entry. While the agent [thread] runs is
     * known to be at its limit and another the Mac has is not ([macInstead]), it goes to that one instead, on the
     * same project: to a thread of that agent there with nothing to do, or to a new one made for it, and the entry
     * says why in place of [step].
     */
    private fun deliver(context: Context, time: Long, prompt: String, thread: MacThread, away: Boolean, step: String? = null) {
        val log = ChatLog.of(context)
        val instead = macInstead(thread.kind, kinds, limited())
        if (instead == null) {
            log.update(time, save = true) { macHandOver(it, thread).let { entry -> if (step == null) entry else ChatHistory.withStep(entry, step) } }
            return send(context, time, sentText(context, time, prompt, thread.id), thread.id, away)
        }
        val why = macLimitedStep(thread.kind, instead)
        val free = macKindThread(thread.project, instead, view.threads, macOwed(log.tasks, except = time))
        if (free != null) {
            log.update(time, save = true) { ChatHistory.withStep(macHandOver(it, free), why) }
            return send(context, time, sentText(context, time, prompt, free.id), free.id, away)
        }
        val name = macKindThreadName(thread.name, instead, view.threads.map { it.name })
        log.update(time, save = true) { ChatHistory.withStep(macFreshHandOver(it, thread, name), why) }
        make(context, time, MacFresh.Make(name, thread.project), away, instead) { sentText(context, time, prompt, it) }
    }

    /**
     * Separate work for the project of [busy], filed at [time]: [macFresh] decides from the threads as last heard,
     * with [busy] as the Mac last described it. A thread that is free takes it as a hand-over; past the limit it
     * waits on a working one and the entry says why; otherwise a new thread is made and the words go to that. A
     * new thread runs the first agent the Mac has that is not known to be at its limit, and is named after it
     * when that is not Claude.
     */
    private fun place(context: Context, time: Long, prompt: String, busy: MacThread, away: Boolean, newChat: Boolean = false) {
        val log = ChatLog.of(context)
        val threads = view.threads.let { known -> if (known.any { it.id == busy.id }) known else known + busy }
        val named = threads.first { it.id == busy.id }
        when (val place = macFresh(named, threads, macOwed(log.tasks, except = time), newChat)) {
            is MacFresh.Same -> deliver(context, time, prompt, place.thread, away)
            is MacFresh.Queue -> deliver(context, time, prompt, place.thread, away, macCappedStep(place.working, macFolder(named.project)))
            is MacFresh.Make -> {
                val limited = limited()
                val kind = macKind(kinds, limited) ?: macInstalled(kinds).first()
                val name = if (kind == MAC_CLAUDE) place.name else macKindThreadName(named.name, kind, threads.map { it.name })
                // Said only when a limit is why: a thread of another agent's is otherwise simply made as Claude's.
                val why = macLimitedStep(named.kind, kind).takeIf { kind != macKindOf(named.kind) && macKindOf(named.kind) in limited }
                log.update(time, save = true) { macFreshHandOver(it, named, name).let { entry -> if (why == null) entry else ChatHistory.withStep(entry, why) } }
                make(context, time, place.copy(name = name), away, kind) { sentText(context, time, prompt, it) }
            }
        }
    }

    /**
     * Makes the thread [place] names, running the agent [kind], off the caller's thread, and sends it what [words]
     * makes for its id as any hand-over is sent. The entry counts as on its way the whole time, so nothing the
     * thread it waited under says can end it. A Mac that does not answer, turns the key down or refuses the
     * folder leaves the entry saying so with `[ try again ]`.
     */
    private fun make(context: Context, time: Long, place: MacFresh.Make, away: Boolean, kind: String, words: (String) -> String) {
        synchronized(this) {
            sentAt[time] = IN_FLIGHT
            sending = sending + time
        }
        thread(name = "oma-mac-make", isDaemon = true) {
            val log = ChatLog.of(context)
            var made: MacThread? = null
            var problem = MAC_UNREACHABLE
            var sendOn = false
            try {
                val client = MacRunnerClient(config(context))
                val health = client.health()
                heard(context, health)
                if (health !is MacHealthResult.Up) problem = macUnreachableReason(health)
                else {
                    val answer = client.makeThread(place.name, place.project, kind)
                    made = (answer as? MacAnswer.Ok)?.value
                    problem = macMadeReason(answer) ?: problem
                }
                made?.let { thread ->
                    noted(context, thread)
                    log.update(time, save = true) { macMade(it, thread) }
                    // Made for a new chat's words, its first or ones another agent's limit sent on: the chat lives here from now on.
                    log.tasks.firstOrNull { it.time == time }?.chat?.takeIf { it.isNotEmpty() }?.let { chat -> TabBook.change(context) { it.onMac(chat, thread.id) } }
                    // Ended meanwhile, as when the busy thread could no longer be followed: then nothing is sent.
                    sendOn = log.tasks.any { it.time == time && it.thread == thread.id && it.status == TaskStatus.Queued }
                }
            } finally {
                // One sent on stays on its way until [send] is done with it; for any other its time on the way ends here.
                if (!sendOn) synchronized(this) {
                    sentAt[time] = clock.incrementAndGet()
                    sending = sending - time
                }
            }
            val thread = made
            when {
                thread != null && sendOn -> send(context, time, words(thread.id), thread.id, away)
                thread == null -> {
                    log.update(time, save = true) { macUnsent(it, problem) }
                    if (away) AgentNotices.answered(context, NOT_SENT, problem, AgentNotices.FRONT)
                    refresh(context)
                }
                else -> refresh(context)
            }
        }
    }

    /**
     * His words typed on a thread's own tab in the chat: they go to the thread [threadId], called [name], as
     * they are. The front agent is not asked and nothing is put ahead of them. The entry is filed first, under
     * the build lane's lock as every entry's time is taken, and the words then go the way a hand-over's do:
     * the Mac is asked how it is, and one that does not answer leaves the entry saying so with `[ try again ]`.
     */
    fun say(context: Context, prompt: String, threadId: String, name: String, chat: String = "") {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val time = synchronized(AgentTasks) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { log.begin(macDirect(it, prompt, threadId, name).copy(chat = chat)) }
        }
        send(app, time, prompt, threadId, away = false)
    }

    /**
     * What the thread is sent for the request filed at [asked]: his words, behind what he and the front agent
     * said since that thread last got anything, and before that [MAC_SHARED_NOTE] while another thread works in
     * the same folder ([macShares]). The entry and the routing log are given [prompt] alone. Words he sent
     * straight to the thread go as they are, however often they are sent again.
     */
    private fun sentText(context: Context, asked: Long, prompt: String, threadId: String): String {
        val tasks = ChatLog.of(context).tasks
        if (tasks.any { it.time == asked && it.direct }) return prompt
        val shared = macShares(threadId, view.threads, macOwed(tasks, except = asked))
        // Only what was said where he asked: in his new chat, or in the Oma stream.
        val chat = tasks.firstOrNull { it.time == asked }?.chat.orEmpty()
        val said = tasks.filter { it.chat == chat }
        return handOverText(sharedPreface(shared, MAC_SHARED_NOTE, handOverPreface(handOverContext(said, asked) { threadGot(it, threadId) })), prompt)
    }

    /**
     * `[ try again ]` on a request the Mac never got or ended with nothing: the same step once more, and a line in the routing log.
     * One whose new thread was never made is placed again from how the threads stand now.
     */
    fun tryAgain(context: Context, time: Long) {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val task = log.tasks.firstOrNull { it.time == time }?.takeIf { macRetried(it) != null } ?: return
        log.update(time, save = true) { macRetried(it) ?: it }
        RoutingLog.corrected(app, task.prompt, MAC_TRIED_AGAIN)
        val busy = view.threads.firstOrNull { it.id == task.thread }
        // Sent again as it was, unless its agent is known to be at its limit, as when every agent was.
        if (!task.fresh) return if (busy != null) deliver(app, time, task.prompt, busy, away = false) else send(app, time, sentText(app, time, task.prompt, task.thread), task.thread, away = false)
        // A new chat's first words whose thread in the default folder was never made: it is made again.
        if (task.thread == MAC_NEW_THREAD) return makeForChat(app, time, task.prompt, away = false, task.chat)
        // A new chat's first words that never got their thread are given a new one again, never the busy one.
        val newChat = task.chat.isNotEmpty() && TabBook.of(app).chat(task.chat)?.bound == false
        if (busy != null) return place(app, time, task.prompt, busy, away = false, newChat)
        // Until the Mac has listed its threads in this run, a thread missing from the list is only not heard of yet.
        log.update(time, save = true) { macUnsent(it, if (view.listed) MAC_THREAD_GONE else MAC_UNREACHABLE) }
        refresh(app)
    }

    /**
     * Asks the Mac how it is, inside the two-second limit, and sends [text] if it answers. A Mac that does not
     * leaves the entry saying so with his words kept; nothing is given to the phone's builder instead. When the
     * words were sent and no answer came back, it is not known whether the Mac has them: the entry waits, and
     * the thread's follower finds out from the Mac itself.
     */
    private fun send(context: Context, time: Long, text: String, threadId: String, away: Boolean) {
        val kept = ChatLog.of(context).tasks.map { it.time }.toSet()
        // A thread never followed from here is followed from where it stood: its past would otherwise be read as this turn's.
        view.threads.firstOrNull { it.id == threadId }?.let { cursors.start(threadId, it.lastEvent) }
        synchronized(this) {
            sentAt[time] = IN_FLIGHT
            sending = sending + time
            // Only the entries the chat still keeps can be sent on.
            sentWords.keys.retainAll(kept)
            sentWords[time] = text
        }
        thread(name = "oma-mac-send", isDaemon = true) {
            val log = ChatLog.of(context)
            var problem: String? = MAC_UNREACHABLE
            try {
                val client = MacRunnerClient(config(context))
                val health = client.health()
                heard(context, health)
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
     * Follows one thread until it rests with nothing of his unfinished in it and nothing more to say between his
     * messages ([macAwake]). Each round first asks the Mac how the thread stands, with no stream open, so what it
     * says of its last event, its waiting requests and its waiting turns is one moment's truth: a record that was
     * cleared resets the place, the cards are made to match, events missed are fetched, and a resting thread is
     * settled. A thread with a task is then listened to until its turn ends or the link drops; a resting one that
     * may still report, for a minute at a time. Nothing in here sends his words anywhere.
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
                    TailscaleHeal.after(context, MacLink.Answered)
                    noted(context, seen)
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
                    val resting = macResting(seen.status)
                    if (resting) {
                        val late = synchronized(this) { sentAt.filterValues { it >= asked }.keys.toSet() }
                        log.revise { tasks -> macSettle(tasks, threadId, seen.status, seen.waitingTurns) { it.time !in late } }
                        if (unfinished(context, threadId)) {
                            pause(macRetryDelay(++misses))
                            continue
                        }
                        // Nothing of his is left there, but the thread may still report on its own between his messages: it
                        // is listened to until it lists no work left running and its last turn is a while ago.
                        if (!macAwake(seen, System.currentTimeMillis()) || log.tasks.none { it.thread == threadId && !it.fresh }) {
                            if (leave(mine, threadId)) {
                                refresh(context)
                                return
                            }
                            continue
                        }
                    }
                    var heard = false
                    val turned = AtomicBoolean()
                    // A resting thread is listened to a while at a time, then asked again how it stands.
                    val until = System.currentTimeMillis() + RESTING_LISTEN_MS
                    val end = client.stream(threadId, cursors.after(threadId), { turned.get() || (resting && System.currentTimeMillis() > until) }) { event ->
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
                    // A thread waiting out a dark link is often where a reboot's Tailscale shows: it is healed from here too.
                    if (known is MacAnswer.Unreachable) TailscaleHeal.after(context, MacLink.NoAnswer)
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
        val gone = synchronized(this) { book.drop(threadId).also { published(context) } }
        gone.forEach { AgentNotices.macWithdraw(context, it) }
    }

    /** Makes the cards match the thread's own list of what waits, and the entries with them. */
    private fun synced(context: Context, seen: MacThread) {
        val change = synchronized(this) { book.sync(seen).also { published(context) } }
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
        // A turn that ended at its agent's limit goes on elsewhere before anything else is made of its end.
        if (macLimited(event)) fallBack(context, threadId)
        val flying = synchronized(this) { sentAt.filterValues { it == IN_FLIGHT }.keys.toSet() }
        // The reply is named after the agent and model the event says answered, the thread's agent when it says neither.
        val kind = view.threads.firstOrNull { it.id == threadId }?.kind.orEmpty()
        val by = when (event) {
            is MacEvent.Text -> macReplyName(event.kind.ifEmpty { kind }, event.model)
            is MacEvent.Result -> macReplyName(event.kind.ifEmpty { kind }, event.model)
            else -> null
        }.orEmpty()
        log.revise { macApply(it, threadId, event, flying, by) }
        when (event) {
            is MacEvent.Approval -> {
                val threadName = name(context, threadId)
                val added = synchronized(this) { book.offer(threadId, threadName, event.id, event.request).also { published(context) } }
                if (added != null) AgentNotices.macRequest(context, added)
            }
            is MacEvent.ApprovalResult -> {
                val gone = synchronized(this) { book.resolve(threadId, event.id, event.approval).also { published(context) } }
                if (gone != null) AgentNotices.macWithdraw(context, gone)
            }
            is MacEvent.Result, is MacEvent.Error, is MacEvent.Stopped -> {
                val gone = synchronized(this) { book.ended(threadId, event.id).also { published(context) } }
                gone.forEach { AgentNotices.macWithdraw(context, it) }
                // A request of his that just ended comes to him as the phone builder's does; one he stopped says nothing more.
                // Dropped here when every agent was at its limit or the turn ended empty, which needs him as a failure does.
                val ended = log.tasks.firstOrNull { now ->
                    now.thread == threadId && now.status in ENDINGS && before.any { it.time == now.time && it.status == TaskStatus.Running }
                }
                if (ended != null) AgentNotices.answered(context, if (ended.status == TaskStatus.Done) ended.threadName else "${ended.threadName} needs attention", ended.result, AgentNotices.MAC)
            }
            is MacEvent.Status -> see(context) { known -> known.copy(threads = known.threads.map { if (it.id == threadId) it.copy(status = event.status) else it }) }
            is MacEvent.Message, is MacEvent.Text, is MacEvent.Step, is MacEvent.Note, is MacEvent.Other -> Unit
        }
        saveCursors(context)
    }

    private val ENDINGS = setOf(TaskStatus.Done, TaskStatus.Failed, TaskStatus.Dropped)

    /**
     * The turn [threadId] was on ended at its agent's limit. The limit is remembered ([MAC_LIMIT_MEMORY_MS]), and
     * the request of his that turn was on goes, the same words with whatever went ahead of them the first time,
     * to a new thread of the next agent the Mac has that is not at its limit, on the same project; the entry
     * says so. With none left it needs his attention and can be tried again. A turn that was not his, or of a
     * thread whose project is not known, is left to end as any error does.
     */
    private fun fallBack(context: Context, threadId: String) {
        val log = ChatLog.of(context)
        val task = log.tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running } ?: return
        val thread = view.threads.firstOrNull { it.id == threadId }?.takeIf { it.project.isNotBlank() } ?: return
        val from = macKindOf(thread.kind)
        val now = System.currentTimeMillis()
        val (next, words) = synchronized(this) {
            limits.hit(from, now)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("limits", limits.text(now)).apply()
            macKind(kinds, limits.at(now)) to sentWords[task.time]
        }
        if (next == null) return log.update(task.time, save = true) { macAllLimitedEntry(it, kinds) }
        val name = macKindThreadName(thread.name, next, view.threads.map { it.name })
        val text = words ?: sentText(context, task.time, task.prompt, threadId)
        log.update(task.time, save = true) { macLimitHandOver(it, name, macLimitStep(from, next)) }
        make(context, task.time, MacFresh.Make(name, thread.project), away = false, next) { text }
    }

    /**
     * The first words of the new chat [chat], filed at [asked], with no thread named for them: they go to a new
     * thread in [MAC_DEFAULT_PROJECT], called by the chat's name, and nothing asks him which project (Boss,
     * 2026-10-14: "i shouldn't be asked which project"). With no Mac set up it says so; with the Mac out of reach
     * his words are kept, unsent, with `[ try again ]` under them ([chatPlace]).
     */
    fun startChat(context: Context, asked: Long, prompt: String, away: Boolean, chat: String) {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        when (chatPlace(config(app) != null, view.reachable)) {
            ChatPlace.NotSetUp -> log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = MAC_NOT_SET_UP, status = TaskStatus.Failed) }
            ChatPlace.Unreachable -> {
                log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = MAC_CHAT_UNREACHABLE, status = TaskStatus.Dropped, answeredBy = OMA_NAME) }
                refresh(app)
            }
            ChatPlace.Start -> makeForChat(app, asked, prompt, away, chat)
        }
    }

    /**
     * Makes the thread for the new chat [chat] in [MAC_DEFAULT_PROJECT] and sends it the words filed at [time]. It
     * runs the first agent the Mac has that is not known to be at its limit, named after it when that is not Claude.
     */
    private fun makeForChat(context: Context, time: Long, prompt: String, away: Boolean, chat: String) {
        val name = chatThreadName(TabBook.of(context).names[chatKey(chat)])
        val kind = macKind(kinds, limited()) ?: macInstalled(kinds).first()
        val named = if (kind == MAC_CLAUDE) name else macKindThreadName(name, kind, view.threads.map { it.name })
        ChatLog.of(context).update(time, save = true) { macChatStart(it, named) }
        make(context, time, MacFresh.Make(named, MAC_DEFAULT_PROJECT), away, kind) { sentText(context, time, prompt, it) }
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
                    val gone = synchronized(this) { book.remove(entry.threadId, entry.request.approval).also { published(context) } }
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
