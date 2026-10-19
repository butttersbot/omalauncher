package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject

/*
 * Switching agents and models (cockpit/DESIGN.md §4). A new chat picks the agent and the model its thread runs, and
 * each project remembers the last pick as its default. "Switch to X" on a thread makes a new thread of X in the same
 * project and on the same computer, seeded with a handoff the outgoing agent writes (or, when it can't, the thread's
 * own recent record), and the chat goes on there. Agents can't resume each other's sessions, so this is the honest
 * version. Every decision is a plain function here, unit tested; MacThreadsEngine does what they say.
 */

/** An agent and the model it runs; an empty [model] is the agent's own default. */
data class MacPick(val agent: String, val model: String = "") {
    val kind get() = macKindOf(agent)
}

/** What the runner takes as a model name (oma-runner's `POST /threads`); anything else is never sent. */
private val MODEL_NAME = Regex("[A-Za-z0-9][\\w.:=,\\[\\]-]{0,99}")

fun macModelOk(model: String) = model.isEmpty() || MODEL_NAME.matches(model)

/**
 * The models Claude Code is asked for by name: the aliases it takes, and Fable by its id (no alias names it).
 * The first is the agent's own default, which names none.
 */
val MAC_CLAUDE_MODELS = listOf("", "claude-fable-5-1", "opus", "sonnet", "haiku")

/**
 * The models a thread of [kind] can be made with, the agent's own default first. Claude's are [MAC_CLAUDE_MODELS]; the
 * runner lists no others, so for Codex, Grok and Cursor they are the default and the models their threads were seen
 * running ([seen]), as the runner reported them.
 */
fun macModels(kind: String, seen: List<MacThread>): List<String> {
    val agent = macKindOf(kind)
    val known = seen.filter { macKindOf(it.kind) == agent }.map { it.model.trim() }.filter { it.isNotEmpty() && macModelOk(it) && it != "cursor-auto" }
    return if (agent == MAC_CLAUDE) MAC_CLAUDE_MODELS else (listOf("") + known).distinct()
}

/** A model as a pick shows it: "Fable", "Opus", "gpt-6.1-sol", or "default" for the agent's own. */
fun macModelName(model: String): String = if (model.isBlank()) "default" else agentName(model)?.takeIf { it !in setOf("Codex", "Grok") } ?: model

/** What a pick is called: "Codex", "Claude · Opus". The agent's own default model is not named. */
fun macPickName(pick: MacPick): String = macKindName(pick.kind) + if (pick.model.isBlank()) "" else " · " + macModelName(pick.model)

/**
 * Where [pick] (a new chat's, or a switch's) goes on a computer that runs [installed]: the pick itself when its model is
 * one the runner would take, else its agent on the agent's default. Never another agent: one the computer lacks is
 * refused by the runner, and the entry says so.
 */
fun macPickClean(pick: MacPick): MacPick = if (macModelOk(pick.model)) pick.copy(agent = pick.kind) else MacPick(pick.kind)

/** The pref each project's default is kept under, as [macDefaultsText] writes it. */
const val MAC_DEFAULTS_PREF = "defaults"

/** Each project's default agent and model, by [macProjectKey]: the last one he picked for it, kept as he picked it. */
fun macDefaultsFrom(text: String?): Map<String, MacPick> {
    val saved = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return emptyMap()
    return saved.keys().asSequence().mapNotNull { key ->
        val o = saved.optJSONObject(key) ?: return@mapNotNull null
        val agent = o.optString("agent").trim().lowercase().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val model = o.optString("model").takeIf(::macModelOk).orEmpty()
        key to MacPick(agent, model)
    }.toMap()
}

fun macDefaultsText(defaults: Map<String, MacPick>): String = JSONObject().apply {
    for ((key, pick) in defaults) put(key, JSONObject().put("agent", pick.kind).apply { if (pick.model.isNotEmpty()) put("model", pick.model) })
}.toString()

/** [defaults] with [pick] as [project]'s from now on. */
fun macDefaultsWith(defaults: Map<String, MacPick>, project: String, pick: MacPick): Map<String, MacPick> = defaults + (macProjectKey(project) to macPickClean(pick))

/**
 * The default for a new thread in [project] that nothing else picked: the project's own while the computer runs its agent
 * ([installed], as `/health` gave it) and that agent is not known to be at its limit ([limited]); else null, and the
 * first agent not at its limit is used as ever ([macKind]).
 */
fun macDefaultFor(defaults: Map<String, MacPick>, project: String, installed: List<String>, limited: Set<String>): MacPick? =
    defaults[macProjectKey(project)]?.takeIf { it.kind in macInstalled(installed) && it.kind !in limited }

/**
 * Every pick a thread running [current] on a computer that runs [installed] can switch to: each agent the computer has,
 * with each of its models ([macModels]), but the one it runs now.
 */
fun macSwitchTargets(current: MacPick, installed: List<String>, seen: List<MacThread>): List<MacPick> =
    macInstalled(installed).flatMap { kind -> macModels(kind, seen).map { MacPick(kind, it) } }
        .filterNot { it.kind == current.kind && it.model == current.model }

/** The model words he may type for Claude alone: "switch to sonnet" is Claude on Sonnet. */
private val CLAUDE_WORDS = mapOf("fable" to "claude-fable-5-1", "opus" to "opus", "sonnet" to "sonnet", "haiku" to "haiku")

private val SWITCH = Regex("""^\s*/?switch(?:\s+to)?\s+([A-Za-z]+)(?:\s+(?:on\s+)?([A-Za-z0-9][\w.:=,\[\]-]{0,99}?))?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)

/**
 * Whether [word], typed after an agent's name, is a model id rather than an ordinary word: it has a letter and a digit
 * ("gpt-6.1-sol", "grok-5", "claude-opus-5-5"), or a hyphen inside it ("grok-code-fast"). "please", "code", "now", "instead" and a bare "4" are words, so a
 * message with one is his words to the thread, never a switch.
 */
private fun modelShaped(word: String) = word.any { it.isLetter() } && (word.any { it.isDigit() } || '-' in word.trimEnd('-'))

/**
 * The pick a message typed on a thread's tab asks to switch to, when the whole message is such a request: "switch to
 * codex", "/switch grok", "switch to claude opus", "switch to sonnet". Null for anything else, so ordinary words that
 * mention switching go to the thread as they are.
 */
fun macSwitchAsked(text: String): MacPick? {
    val match = SWITCH.matchEntire(text) ?: return null
    val first = match.groupValues[1].lowercase()
    // A closing full stop or bang is the sentence's, never the model's.
    val second = match.groupValues[2].trimEnd('.', '!')
    CLAUDE_WORDS[first]?.let { return if (second.isEmpty()) MacPick(MAC_CLAUDE, it) else null }
    if (first !in MAC_KINDS) return null
    val model = when {
        second.isEmpty() -> ""
        first == MAC_CLAUDE && second.lowercase() in CLAUDE_WORDS -> CLAUDE_WORDS.getValue(second.lowercase())
        modelShaped(second) -> second
        else -> return null
    }
    return MacPick(first, model).takeIf { macModelOk(model) }
}

/** What the outgoing thread is asked for, as his request on its tab: a handoff the next agent can start from. */
fun macHandoffAsk(to: MacPick): String =
    "Boss is moving this thread to ${macPickName(to)}, which can't read your session. Write the handoff it will start from: " +
        "the goal, what is done, what is left, the files that matter, decisions made and still open, and anything it must not undo. " +
        "Plain text, under 400 words. Reply with the handoff only; change nothing."

/** The longest handoff passed on: enough for a real summary, never a whole session pasted over. */
const val MAC_HANDOFF_CHARS = 6000

/**
 * What the new thread's agent is sent first: who it takes over from, the [handoff], and the project's own handoff file.
 * [from] is the outgoing agent's name ("Claude").
 */
fun macHandoffSeed(from: String, handoff: String, written: Boolean): String =
    "You're taking over this thread from $from (Boss switched agents; you can't see its session). " +
        (if (written) "Its handoff:\n\n" else "It couldn't write a handoff, so here is the end of the thread as it stood:\n\n") +
        handoff.trim().take(MAC_HANDOFF_CHARS) +
        "\n\nRead HANDOFF.md in the project folder too, if there is one. Then reply in one or two lines: what you understand the next step to be. Change nothing yet."

/**
 * The end of [threadId]'s record as the chat has it, oldest first: his requests and the answers, for a handoff the
 * outgoing agent could not write (at its limit, out of reach, or it failed). Never more than [MAC_HANDOFF_CHARS].
 */
fun macHandoffFromRecord(tasks: List<ChatTask>, threadId: String): String {
    val lines = tasks.filter { it.thread == threadId && !it.fresh }.flatMap { task ->
        listOfNotNull("Boss: " + task.prompt.trim().take(800), task.result.trim().takeIf { it.isNotEmpty() }?.let { "Agent: " + it.take(1200) })
    }
    val kept = ArrayList<String>()
    var size = 0
    for (line in lines.asReversed()) {
        if (size + line.length + 2 > MAC_HANDOFF_CHARS) break
        kept += line
        size += line.length + 2
    }
    return kept.asReversed().joinToString("\n\n").ifEmpty { "(Nothing in the chat from this thread yet.)" }
}

/** The step on the outgoing thread's entry while it writes the handoff, and once the new thread has it. */
fun macSwitchingStep(to: MacPick) = "handing off to ${macPickName(to)}"

fun macSwitchedStep(name: String) = "moved to $name"

/** The step on a handoff he stopped: the switch is off and the thread stays. */
const val MAC_SWITCH_OFF = "switch called off"

/** What a switch that never got there says once a later switch of its thread has taken its place. */
const val MAC_SWITCH_REPLACED = "$MAC_SWITCH_OFF: a later switch took its place"

/** The step on a switch whose new thread couldn't be made: the thread stays where it is, and why. */
fun macSwitchFailedStep(why: String) = "couldn't move: $why"

/** What an entry says when a switch of the thread is already under way. */
fun macSwitchBusy(to: MacPick) = "This thread is already moving to ${macPickName(to)}; wait for that to finish."

/** What an entry says when he asked to switch a thread the chat doesn't know, or one with no project. */
const val MAC_SWITCH_UNKNOWN = "That thread isn't known here yet, so it can't be switched. Try again once it has answered."

/**
 * Whether the outgoing thread can write its own handoff: it is heard from, its agent is not known to be at its limit, and
 * it is not in the middle of other work (its handoff would then queue behind that, and he asked to move now).
 */
fun macCanWriteHandoff(thread: MacThread, reachable: Boolean, limited: Set<String>, busy: Boolean): Boolean =
    reachable && macKindOf(thread.kind) !in limited && !busy

/** What the switches under way are kept as: the ask entry's time, and where it goes. Kept in prefs so a restart finishes them. */
const val MAC_SWITCHES_PREF = "switches"

/** Where the new threads' first entries a switch made are kept, in the same form, for `[ try again ]`. */
const val MAC_SEEDS_PREF = "seeds"

fun macSwitchesFrom(text: String?): Map<Long, MacPick> {
    val saved = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return emptyMap()
    return saved.keys().asSequence().mapNotNull { key ->
        val time = key.toLongOrNull() ?: return@mapNotNull null
        val o = saved.optJSONObject(key) ?: return@mapNotNull null
        val agent = o.optString("agent").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        time to MacPick(agent, o.optString("model").takeIf(::macModelOk).orEmpty())
    }.toMap()
}

fun macSwitchesText(switches: Map<Long, MacPick>): String = JSONObject().apply {
    for ((time, pick) in switches) put(time.toString(), JSONObject().put("agent", pick.kind).put("model", pick.model))
}.toString()

/** Whether the ask entry [task] has ended, one way or another: then the switch goes on, with its answer or without. */
fun macHandoffEnded(task: ChatTask) = task.status in setOf(TaskStatus.Done, TaskStatus.Failed, TaskStatus.Dropped, TaskStatus.Stopped)

/** The handoff an ended ask entry gives: its answer when it finished with one, else null and the record is used. */
fun macHandoffWritten(task: ChatTask): String? = task.result.trim().takeIf { task.status == TaskStatus.Done && it.length >= 40 }
