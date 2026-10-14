package dev.omalauncher.assist

import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus

/*
 * Never held up by one agent (Boss, 2026-10-13: "if we hit our limit on claude code concurrent sessions on the
 * mac pro, then we should have codex available … or cursor … the idea is to never be bottlenecked by one agent
 * that's working"). A turn on the Mac that ends at its agent's limit is sent again, the same words, to a new
 * thread of the next agent on the same project, and the limit is remembered for a while so that new requests
 * start on an agent that is not at one. Every decision is a plain function here, unit tested; MacThreads does
 * what they say. The runner's side is "As built: Codex and Cursor threads" in docs/MAC-RUNNER-SPEC.md.
 */

const val MAC_CLAUDE = "claude"
const val MAC_CODEX = "codex"
const val MAC_CURSOR = "cursor"

/** The agents a thread on the Mac can run, in the order a request goes from one at its limit to the next. */
val MAC_KINDS = listOf(MAC_CLAUDE, MAC_CODEX, MAC_CURSOR)

/** What the runner's `error` gives as its code when a turn ended at the agent's limit. */
const val MAC_LIMIT = "limit"

/** How long an agent that hit its limit is taken to still be at it: new requests start on another meanwhile. */
const val MAC_LIMIT_MEMORY_MS = 15 * 60_000L

/** A thread's kind as it is compared here: the runner's word in lower case. None at all is Claude, the runner's default. */
fun macKindOf(kind: String): String = kind.trim().lowercase().ifEmpty { MAC_CLAUDE }

/** What an agent is called on an entry. */
fun macKindName(kind: String): String = when (val agent = macKindOf(kind)) {
    MAC_CLAUDE -> "Claude"
    MAC_CODEX -> "Codex"
    MAC_CURSOR -> "Cursor"
    else -> agent.replaceFirstChar { it.uppercaseChar() }
}

/**
 * The agents of [MAC_KINDS] the Mac has, in their order, from `/health`'s `kinds`. A runner that has not said,
 * or not been asked yet, has Claude alone, which every runner runs.
 */
fun macInstalled(kinds: List<String>): List<String> {
    val have = kinds.map(::macKindOf).toSet()
    return MAC_KINDS.filter { it in have }.ifEmpty { listOf(MAC_CLAUDE) }
}

/** The first agent the Mac has ([installed], as `/health` gave it) that is not known to be at its limit ([limited]); null when every one is. */
fun macKind(installed: List<String>, limited: Set<String>): String? = macInstalled(installed).firstOrNull { it !in limited }

/** Whether [event] ends a turn at the agent's limit: the only ending after which the same words are sent anywhere again. */
fun macLimited(event: MacEvent) = event is MacEvent.Error && event.cause == MAC_LIMIT

/**
 * The agent a request for a thread that runs [kind] goes to instead, because [kind] is known to be at its limit:
 * the first that is not. Null when [kind] is not known to be limited, and when every agent is, since then the
 * thread may as well be tried: a limit remembered may already be over.
 */
fun macInstead(kind: String, installed: List<String>, limited: Set<String>): String? =
    if (macKindOf(kind) !in limited) null else macKind(installed, limited)

/** The limits heard, each until [MAC_LIMIT_MEMORY_MS] after it was. Kept in memory only; not thread safe: MacThreads guards it. */
class MacLimits(private val memory: Long = MAC_LIMIT_MEMORY_MS) {
    private val until = HashMap<String, Long>()

    /** [kind] ended a turn at its limit at [now]. */
    fun hit(kind: String, now: Long) {
        until[macKindOf(kind)] = now + memory
    }

    /** The agents taken to be at their limit at [now]. */
    fun at(now: Long): Set<String> = until.filterValues { it > now }.keys
}

/** The step on an entry whose turn on [from] ended at its limit, and which was sent on to [to]. */
fun macLimitStep(from: String, to: String) = "${macKindName(from)} hit its limit · sent to ${macKindName(to)}"

/** The step on an entry that starts on [to] because [from], the agent it was for, is known to be at its limit. */
fun macLimitedStep(from: String, to: String) = "${macKindName(from)} is at its limit · using ${macKindName(to)}"

/** What an entry says when every agent the Mac has ([installed], as `/health` gave it) is at its limit. */
fun macAllLimited(installed: List<String>) = "Every agent on the Mac is at its limit (${macInstalled(installed).joinToString(", ") { macKindName(it) }})."

/** The agent a thread made at a limit is named after, at the end of its name, and the number after that. */
private val KIND_MARK = Regex(" · (" + MAC_KINDS.joinToString("|") { macKindName(it) } + ")( \\d+)?$", RegexOption.IGNORE_CASE)

/** [name] without the agent ([KIND_MARK]) or the number a new thread's name was given: the name of the project's first thread. */
fun macBaseName(name: String): String = macName(name).replace(KIND_MARK, "").replace(Regex(" \\d+$"), "").trim().ifEmpty { macName("") }

/**
 * The name of a new thread of [kind] for the project of the thread called [name]: "FF4 better menu mod · Codex",
 * numbered when a thread in [names] has it already. The agent and number of a name that has them are replaced.
 */
fun macKindThreadName(name: String, kind: String, names: List<String>): String {
    val base = macBaseName(name)
    val named = "$base · ${macKindName(kind)}"
    val taken = names.map { it.trim().lowercase() }.toSet()
    val suffix = (sequenceOf(named) + generateSequence(2) { it + 1 }.map { "$named $it" }).first { it.lowercase() !in taken }.removePrefix(base)
    return base.take(FRESH_NAME_CHARS - suffix.length).trimEnd() + suffix
}

/**
 * A thread that runs [kind] in the folder of [project] and has no work on it ([macBusy], [owed] as there), the
 * one that worked last: where a request goes when it is sent to that agent instead, before a new one is made.
 */
fun macKindThread(project: String, kind: String, threads: List<MacThread>, owed: Set<String> = emptySet()): MacThread? {
    val folder = macProjectKey(project)
    return threads.filter { macProjectKey(it.project) == folder && macKindOf(it.kind) == macKindOf(kind) && !macBusy(it, it.id in owed) }
        .maxByOrNull { it.lastWorked ?: Long.MIN_VALUE }
}

private fun ChatTask.unanswerable() = approvals.map { if (it.answer == ApprovalAnswer.Waiting) it.copy(answer = ApprovalAnswer.Unanswered) else it }

/**
 * The entry of a request whose turn ended at its agent's limit, on its way to a new thread called [name]: it
 * waits as separate work does while its new thread is made ([macFreshHandOver]), under the id it had, and
 * [step] says what happened. What the agent said before its limit goes; a request of its that waited for his OK
 * can no longer be answered.
 */
fun macLimitHandOver(task: ChatTask, name: String, step: String): ChatTask =
    ChatHistory.withStep(task, step).copy(agent = macReceipt(name), threadName = macName(name), status = TaskStatus.Queued, result = "", fresh = true, approvals = task.unanswerable())

/**
 * The entry of a request whose turn ended at its limit with every agent the Mac has ([installed]) at theirs:
 * sent nowhere else, needing his attention, and his to send again with `[ try again ]`.
 */
fun macAllLimitedEntry(task: ChatTask, installed: List<String>): ChatTask =
    task.copy(status = TaskStatus.Dropped, result = macAllLimited(installed), approvals = task.unanswerable())
