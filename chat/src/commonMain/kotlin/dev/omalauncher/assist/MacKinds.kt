package dev.omalauncher.assist

import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject

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
const val MAC_GROK = "grok"

/** Venice (venice.ai): a chat with Venice's models, not a coding agent. Its thread answers and touches nothing on the computer. */
const val MAC_VENICE = "venice"

/** The agents that only talk: never where a request goes on its own (a new thread nothing picked, or one at its limit), only when picked. */
val MAC_CHAT_ONLY = setOf(MAC_VENICE)

/**
 * The agents a thread on the Mac can run, in the order a request goes from one at its limit to the next: Boss's own
 * three (Claude, Codex, Grok; 2026-10-16), then Cursor. Venice last, and only ever when picked ([MAC_CHAT_ONLY]).
 */
val MAC_KINDS = listOf(MAC_CLAUDE, MAC_CODEX, MAC_GROK, MAC_CURSOR, MAC_VENICE)

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
    MAC_GROK -> "Grok"
    MAC_VENICE -> "Venice"
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

/**
 * The first agent the Mac has ([installed], as `/health` gave it) that is not known to be at its limit ([limited]); null when
 * every one is. Never one that only talks ([MAC_CHAT_ONLY]): work sent on its own goes to an agent that can do it.
 */
fun macKind(installed: List<String>, limited: Set<String>): String? = macInstalled(installed).firstOrNull { it !in limited && it !in MAC_CHAT_ONLY }

/**
 * The agent a new chat's thread runs: the one he picked ([chosen]), always, never another in its place. A computer that
 * lacks it refuses, and the entry says so with `[ try again ]`; one at its limit hands the turn on with a step saying
 * why, as any thread's does. With no pick, the first [installed] agent not at its limit ([limited]), as for any new
 * thread ([macKind]).
 */
fun macChatKind(chosen: String, installed: List<String>, limited: Set<String>): String? =
    if (chosen.isNotBlank()) macKindOf(chosen) else macKind(installed, limited)

/** Whether [event] ends a turn at the agent's limit: the only ending after which the same words are sent anywhere again. */
fun macLimited(event: MacEvent) = event is MacEvent.Error && event.cause == MAC_LIMIT

/**
 * The agent a request for a thread that runs [kind] goes to instead, because [kind] is known to be at its limit:
 * the first that is not. Null when [kind] is not known to be limited, and when every agent is, since then the
 * thread may as well be tried: a limit remembered may already be over.
 */
fun macInstead(kind: String, installed: List<String>, limited: Set<String>): String? =
    if (macKindOf(kind) !in limited) null else macKind(installed, limited)

/**
 * The limits heard, each until [MAC_LIMIT_MEMORY_MS] after it was by the wall clock. Kept in prefs too ([text],
 * [restore]), so a launcher restart does not send the next request to an agent still at its limit (OPEN-BUGS #12).
 * Not thread safe: MacThreads guards it.
 */
class MacLimits(private val memory: Long = MAC_LIMIT_MEMORY_MS) {
    private val until = HashMap<String, Long>()

    /** [kind] ended a turn at its limit at [now]. */
    fun hit(kind: String, now: Long) {
        until[macKindOf(kind)] = now + memory
    }

    /** The agents taken to be at their limit at [now]. */
    fun at(now: Long): Set<String> = until.filterValues { it > now }.keys

    /** What is kept: each agent still at its limit at [now] and until when. */
    fun text(now: Long): String = JSONObject().apply { for ((kind, end) in until) if (end > now) put(kind, end) }.toString()

    /**
     * Takes back what [text] kept, at [now]: what is over is dropped, and nothing is kept longer than [memory]
     * from [now], so a clock set back since it was saved cannot hold an agent off for longer than a limit lasts.
     * Unreadable text is no limit at all. A limit heard since stands if it ends later.
     */
    fun restore(text: String?, now: Long) {
        val saved = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return
        for (kind in saved.keys()) {
            val end = minOf(saved.optLong(kind, 0L), now + memory)
            if (end > now && end > (until[kind] ?: 0L)) until[kind] = end
        }
    }
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

/*
 * A new thread's mode (desktop parity slice 6, Boss 2026-10-28: modes beside agent and model, phone and desktop alike).
 * It only says how the thread is made (the runner's POST /threads mode); nothing here answers a request. Claude and Codex
 * take either; the runner keeps Cursor and Grok manual and Venice has no tools, so they have no pick.
 */
const val MAC_MODE_AUTO = "auto"
const val MAC_MODE_MANUAL = "manual"

/** The modes a new thread of [agent] can be made in, the default first; empty where there is nothing to pick. */
fun macModes(agent: String): List<String> =
    if (macKindOf(agent) in setOf(MAC_CLAUDE, MAC_CODEX)) macModeDefault(agent).let { first -> listOf(first) + listOf(MAC_MODE_AUTO, MAC_MODE_MANUAL).filter { it != first } } else emptyList()

/**
 * The mode a new thread of [agent] is made in when he picks none: auto, as the runner makes them (Boss, 2026-10-21:
 * Codex auto with full access; 2026-10-22: "it's bombarding me with approval cards"). One place, to change with his word.
 */
fun macModeDefault(agent: String): String = MAC_MODE_AUTO

/** What a mode means, in the pick row and its menu. */
fun macModeName(mode: String): String = when (mode) {
    MAC_MODE_MANUAL -> "manual · asks before edits and commands"
    else -> "auto · acts without asking"
}

/** The mode a new thread is made in: [picked] when it is one [agent] takes, else its default; empty for an agent with none. */
fun macModeFor(agent: String, picked: String): String = macModes(agent).let { modes -> picked.takeIf { it in modes } ?: modes.firstOrNull().orEmpty() }

