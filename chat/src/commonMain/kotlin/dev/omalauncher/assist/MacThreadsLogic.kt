package dev.omalauncher.assist

import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLater
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import java.util.UUID
import org.json.JSONObject

/*
 * The plain functions of the phone's view of the project threads on the Mac, shared with the Mac app; MacThreads
 * (in the phone app) does what they say. See MacThreads.kt.
 */

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
const val FRESH_NAME_CHARS = 60

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
    is MacAnswer.Failed -> "The Mac could not make a new thread (HTTP ${answer.code})." + answer.error.trim().takeIf { it.isNotEmpty() }?.let { " It said: ${it.take(200)}" }.orEmpty()
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
    val command = input.optString("command").ifBlank { return input.toString(2) }
    return if (request.canRemember) command + "\nDirectory: " + input.macText("cwd") +
        "\nRequested access: " + (input.optJSONObject("access")?.toString(2) ?: "{}") else command
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
                    // Agent mail the runner handed the thread is an entry of its own, labeled as the phone's own mail is
                    // (Boss, 2026-10-21: "the same UX as our other agent mail threads"): what the thread does next is its turn.
                    val mail = if (next == null) macMail(event.text) else null
                    if (mail != null) waiting + macMailEntry(waiting, threadId, event, mail)
                    else if (next == null) between(ChatLater(MAC_SENT_ELSEWHERE + event.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()), waiting)
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
        is MacEvent.ApprovalResult -> macAnswered(tasks, threadId, event.approval, approvalAnswer(event.decision)).let { answered ->
            // Who answered, on every device, so one that sees the card go knows it was the Fold (or a desktop), not an error.
            val by = deviceName(event.device)
            val said = when (event.decision) {
                MacDecision.Allow -> "allowed on $by"
                MacDecision.Deny -> "denied on $by"
                else -> null
            }
            if (said == null || event.device.isBlank() || current == null) answered
            else answered.map { if (it.time == current.time) ChatHistory.withStep(it, said) else it }
        }
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

/**
 * What stands beside an entry of a computer's while it is not over; [fresh] while a new thread is made for it. [on] is
 * the computer its thread runs on, as he calls it ("MG1"), so a thread on one box never says it works on another.
 */
fun macEntryLabel(status: TaskStatus, sending: Boolean, reachable: Boolean, approvals: Boolean, fresh: Boolean = false, on: String = "the Mac"): String = when {
    status == TaskStatus.Queued && fresh -> if (on == "the Mac") MAC_MAKING else "Starting a new thread on $on"
    status == TaskStatus.Queued -> if (sending) "Sending to $on" else if (reachable) (if (on == "the Mac") MAC_QUEUED else "Queued on $on") else "Sent; $on is out of reach"
    approvals -> "Approval needed"
    reachable -> "Working on $on"
    else -> "${if (on == "the Mac") "Mac" else on} out of reach; still working there"
}

/** Whether [label] is [macEntryLabel]'s queued one, on whichever computer. */
fun macQueuedLabel(label: String) = label.startsWith("Queued on ")

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
    is MacAnswer.Failed -> when {
        answer.code == 200 -> null
        // A refused resume has no event stream: keep the runner's explanation in the unsent entry.
        answer.code == 409 && answer.error.isNotBlank() -> answer.error.trim().take(1200)
        else -> "The Mac could not take this (HTTP ${answer.code})."
    }
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
 * What the phone last heard of the Mac: its [threads], whether it answered at the last try, and when it last did.
 * [listed] is whether [threads] is the Mac's own whole list, heard since this process began: until then a
 * thread missing from it is only not heard of yet, and nothing kept for it is taken for gone. With more than one
 * Mac ([macs], MacMachines.kt) [threads] holds them all, each under the phone's id for it, and the rest is said of
 * them all ([macWith]); what one Mac answers is in its own [MacSeen].
 */
data class MacView(val threads: List<MacThread> = emptyList(), val reachable: Boolean = false, val seen: Long? = null, val listed: Boolean = false, val macs: List<MacSeen> = emptyList())

/**
 * [thread] with the summary the board should carry. The runner's summary is the first line of what it was last
 * sent, which for a request handed over with context or with a note about shared work is that heading or note:
 * the first line of his own words, from the entry of that request, stands there instead. Any other summary is
 * left as the Mac gave it.
 */
fun macOwnSummary(thread: MacThread, tasks: List<ChatTask>): MacThread =
    if (!thread.summary.startsWith(CONTEXT_OPEN) && !thread.summary.startsWith(SHARED_NOTE_OPEN)) thread
    else thread.copy(summary = tasks.lastOrNull { threadGot(it, thread.id) }?.prompt?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty())

/** A device as Boss calls it, from its tailnet name: "the Fold", "the Mac Pro", "the MacBook", else the name itself. */
fun deviceName(device: String): String = when {
    "fold" in device.lowercase() -> "the Fold"
    "mac-pro" in device.lowercase() || "macpro" in device.lowercase() -> "the Mac Pro"
    "macbook" in device.lowercase() -> "the MacBook"
    else -> device
}

/** Agent mail as a Mac thread is handed it: who it is from, for the entry's label, and the words themselves. */
data class MacMail(val label: String, val text: String)

private val MAC_MAIL_HEAD = Regex("""^\[Agent mail from ([A-Za-z0-9][A-Za-z0-9.-]*)(?: \(([^)]*)\))?, id [^\]]*\]\s*""")

/**
 * The words of agent mail as the runner gives them to a thread (oma-runner's mail_words: "[Agent mail from DEVICE
 * (agent, "title"), id …]" and a blank line), or null for words that aren't mail.
 */
fun macMail(text: String): MacMail? {
    val head = MAC_MAIL_HEAD.find(text) ?: return null
    val title = head.groupValues[2].substringAfter('"', "").substringBeforeLast('"', "").trim()
    val label = listOf("Agent mail", "from " + head.groupValues[1], title).filter { it.isNotEmpty() }.joinToString(" · ")
    return MacMail(label, text.substring(head.range.last + 1).trim().ifEmpty { text.trim() })
}

/**
 * A running entry for mail [thread] was handed, in the tab its newest entry is in (a new chat's, or the thread's own),
 * after every entry there is: what the thread says next fills it.
 */
fun macMailEntry(tasks: List<ChatTask>, threadId: String, event: MacEvent.Message, mail: MacMail): ChatTask {
    val latest = tasks.lastOrNull { it.thread == threadId && !it.fresh }
    val time = maxOf(event.time * 1000, (tasks.maxOfOrNull { it.time } ?: 0L) + 1)
    return ChatTask(
        time, latest?.agent ?: macReceipt(threadId), mail.text, thread = threadId, threadName = latest?.threadName.orEmpty(),
        status = TaskStatus.Running, direct = true, chat = latest?.chat.orEmpty(), mail = mail.label,
    )
}
