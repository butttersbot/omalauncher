package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject
import java.io.File

// Boss talks to one Oma (Boss, 2026-10-13). The front agent, which is the fast model of [QuickLane], hears a
// request first and answers it, hands his words on unchanged, or says which task he has ordered to stop.
// Everything that decides that is here and pure; QuickLane runs the process and does what these say.

/**
 * Where a request can go. An id and not an enum, because every project thread on the Mac is one more: the
 * front agent answers, hands on to the phone's builder, or hands on to one of those threads.
 */
object Route {
    const val FRONT = "front"
    const val BUILD = "build"

    /** What every route to a project thread on the Mac starts with; the thread's id follows a space. */
    const val MAC = "mac"

    fun mac(threadId: String) = "$MAC $threadId"

    /**
     * What a route to a fresh thread starts with: separate or urgent work on the project of the thread whose id
     * follows a space, which is busy. The launcher makes the new thread there, or sends to that one if it is free.
     */
    const val MAC_NEW = "mac-new"

    fun macNew(threadId: String) = "$MAC_NEW $threadId"

    /** The id of the busy thread [route] names for a fresh thread on its project, or null for any other route. */
    fun macNewThread(route: String): String? = if (route.startsWith("$MAC_NEW ")) route.removePrefix("$MAC_NEW ") else null

    /** The route to one of the phone's builders by name: a follow-up to what that builder asked or is doing. */
    fun builder(id: BuilderId) = "$BUILD ${id.letter}"

    /** The builder [route] names, or null for a route that names none, [BUILD] itself included. */
    fun builderOf(route: String): BuilderId? = if (route.startsWith("$BUILD ")) BuilderId.named(route.removePrefix("$BUILD ")) else null

    /** The id of the thread [route] names, or null for a route that is not to the Mac. */
    fun macThread(route: String): String? = if (route.startsWith("$MAC ")) route.removePrefix("$MAC ") else null
}

/** The whole reply the front agent gives to hand a request to [route]. Never shown and never saved. */
fun handOverMark(route: String) = "<<$route>>"

val HAND_OVER = handOverMark(Route.BUILD)

/** How a mark for a thread on the Mac begins. Whatever follows up to the closing `>>` is the id it names. */
private const val MAC_OPEN = "<<" + Route.MAC

/** The longest id a mark can name. The runner's ids are nine characters; a thread with a longer one is not on the board. */
const val MAC_ID_CHARS = 64

/** A whole mark for a thread, wherever it stands, and one still arriving at the end of what has been said. */
private val MAC_MARK = Regex(Regex.escape(MAC_OPEN) + "[^<>\\n]{0,80}>>")
private val MAC_ARRIVING = Regex(Regex.escape(MAC_OPEN) + "[^<>\\n]{0,80}>?")

/**
 * How a mark for a fresh thread begins. It starts as a thread's mark does, so while it arrives, and wherever it is
 * only mentioned, it is held back and taken out by the same two readings; it is told apart only once it is read.
 */
private const val MAC_NEW_OPEN = "<<" + Route.MAC_NEW

/**
 * How a mark for one of the phone's builders begins, and the same two readings of it as a thread's has:
 * `<<build B>>` hands his words to builder B, whatever else it is doing.
 */
private const val BUILD_NAMED_OPEN = "<<" + Route.BUILD + " "
private val BUILD_NAMED_MARK = Regex(Regex.escape(BUILD_NAMED_OPEN) + "[^<>\\n]{0,80}>>")
private val BUILD_NAMED_ARRIVING = Regex(Regex.escape(BUILD_NAMED_OPEN) + "[^<>\\n]{0,80}>?")

/**
 * The whole reply the front agent gives when his words are an order to stop what [route] is working on: the
 * phone's builders, one of them by name, or one thread on the Mac. Never shown and never saved, as a
 * hand-over's mark is not.
 */
fun stopMark(route: String) = when {
    route == Route.BUILD -> "<<stop>>"
    Route.builderOf(route) != null -> "<<stop ${Route.builderOf(route)?.letter}>>"
    else -> "<<stop $route>>"
}

val STOP = stopMark(Route.BUILD)

/**
 * How a mark that stops a thread on the Mac begins, and the same two readings of it as a hand-over's has. The
 * real model now and then leaves the word for the Mac out and writes the id alone (seen 2026-10-13), so
 * whatever stands after the space is read as the thread it names, with or without that word before it.
 */
private const val STOP_NAMED_OPEN = "<<stop "
private val STOP_NAMED_MARK = Regex(Regex.escape(STOP_NAMED_OPEN) + "[^<>\\n]{0,80}>>")
private val STOP_NAMED_ARRIVING = Regex(Regex.escape(STOP_NAMED_OPEN) + "[^<>\\n]{0,80}>?")

/**
 * The whole reply the front agent gives when his words ask for the phone's builder to be mended: [RESTART] stops
 * its stuck task and sends the same request again, [CLEAN] ends builder processes no task owns. The launcher
 * carries both out, never the front agent, and only as [BuilderWatch] finds things when the mark arrives.
 */
const val RESTART = "<<restart>>"
const val CLEAN = "<<clean>>"

/**
 * With two builders, the mark that restarts the one named, as [RESTART] does the one that is stuck: what the
 * front agent replies once he has said which. Carried out under the same checks, and only on that builder.
 */
fun restartMark(builder: BuilderId) = "<<restart ${builder.letter}>>"

/** Every mark that mends, each read whole: no part of one is ever shown as an answer. */
private val MENDING_MARKS = listOf(RESTART, CLEAN) + BuilderId.entries.map(::restartMark)

/** Whether a thread with this id can be named by a mark at all: no spaces, no brackets, no backticks, not too long. */
fun macNameable(threadId: String) = threadId.length in 1..MAC_ID_CHARS && threadId.none { it.isWhitespace() || it in "<>`" }

/** What the front agent's words so far amount to. */
sealed interface FrontRead {
    /** Nothing yet, or only the start of what could still be the mark. */
    data object Undecided : FrontRead

    /** An answer of its own; [text] is what may be shown of it. */
    data class Answer(val text: String) : FrontRead

    /** The request goes to [to], as he said it. */
    data class HandOver(val to: String) : FrontRead

    /** His words were an order to stop: the task [of] is on is stopped, and his words go nowhere. */
    data class Stop(val of: String) : FrontRead

    /** He asked for the stuck builder to be restarted. */
    data object Restart : FrontRead

    /** He asked for [builder], by name, to be restarted: the answer to being asked which. */
    data class RestartOne(val builder: BuilderId) : FrontRead

    /** He asked for leftover builder processes to be ended. */
    data object Clean : FrontRead
}

/** Whether [tail], the end of what has been said, could still grow into a mark. */
private fun opensMark(tail: String) =
    (tail.length < HAND_OVER.length && HAND_OVER.startsWith(tail)) || MAC_OPEN.startsWith(tail) || MAC_ARRIVING.matches(tail) ||
        BUILD_NAMED_OPEN.startsWith(tail) || BUILD_NAMED_ARRIVING.matches(tail) ||
        (tail.length < STOP.length && STOP.startsWith(tail)) || STOP_NAMED_OPEN.startsWith(tail) || STOP_NAMED_ARRIVING.matches(tail) ||
        MENDING_MARKS.any { tail.length < it.length && it.startsWith(tail) }

/** How many characters at the end of [said] are the start of a mark that may still be arriving. */
private fun arrivingMark(said: String): Int {
    val open = said.lastIndexOf("<<")
    if (open >= 0 && opensMark(said.substring(open))) return said.length - open
    return if (said.endsWith("<")) 1 else 0
}

/**
 * The thread a mark for the Mac at the start of [lead] names, [open] being how that mark begins; null while
 * its closing `>>` may still be coming. One that never closes, or is too long to be an id, names nothing.
 */
private fun macNamed(lead: String, open: String, arriving: Regex, ended: Boolean): String? {
    val close = lead.indexOf(">>")
    if (close < 0 && !ended && arriving.matches(lead)) return null
    val named = if (close < 0) "" else lead.substring(open.length, close).trim()
    return named.takeIf { it.length <= MAC_ID_CHARS }.orEmpty()
}

/**
 * Reads [text], everything the front agent has said so far; [ended] once no more is coming. A reply that
 * starts with a mark hands over, whatever follows it and whatever spaces or backticks stand before it. One
 * that only mentions a mark is an answer, shown without it: handing over on a mention would send work he
 * never asked for. While more may come, the start of a mark at the end is held back so it never flickers by.
 * A mark for a thread on the Mac is read up to its closing `>>` and names whatever stands there; whether
 * that is a thread at all is [frontRoute]'s to say. One that never closes names nothing, and is still a
 * hand-over, to nowhere: it is never shown to him as an answer. A mark that stops is read by the same rules,
 * so a mention of one stops nothing and one for a thread that never closes stops nothing either.
 */
fun frontRead(text: String, ended: Boolean = false): FrontRead {
    val lead = text.trimStart { it.isWhitespace() || it == '`' }
    if (lead.startsWith(HAND_OVER)) return FrontRead.HandOver(Route.BUILD)
    // A builder's mark that names no builder is still work for the phone: it goes as new work, never nowhere.
    if (lead.startsWith(BUILD_NAMED_OPEN)) {
        val named = macNamed(lead, BUILD_NAMED_OPEN, BUILD_NAMED_ARRIVING, ended) ?: return FrontRead.Undecided
        return FrontRead.HandOver(BuilderId.named(named)?.let(Route::builder) ?: Route.BUILD)
    }
    if (lead.startsWith(STOP)) return FrontRead.Stop(Route.BUILD)
    if (lead.startsWith(RESTART)) return FrontRead.Restart
    BuilderId.entries.firstOrNull { lead.startsWith(restartMark(it)) }?.let { return FrontRead.RestartOne(it) }
    if (lead.startsWith(CLEAN)) return FrontRead.Clean
    if (lead.startsWith(STOP_NAMED_OPEN)) {
        val named = macNamed(lead, STOP_NAMED_OPEN, STOP_NAMED_ARRIVING, ended) ?: return FrontRead.Undecided
        // A builder's letter is never a thread's id, which is nine characters.
        BuilderId.named(named.removePrefix(Route.BUILD + " "))?.let { return FrontRead.Stop(Route.builder(it)) }
        return FrontRead.Stop(Route.mac(named.removePrefix(Route.MAC + " ").trim()))
    }
    if (lead.startsWith(MAC_NEW_OPEN)) return macNamed(lead, MAC_NEW_OPEN, MAC_ARRIVING, ended)?.let { FrontRead.HandOver(Route.macNew(it)) } ?: FrontRead.Undecided
    if (lead.startsWith(MAC_OPEN)) return macNamed(lead, MAC_OPEN, MAC_ARRIVING, ended)?.let { FrontRead.HandOver(Route.mac(it)) } ?: FrontRead.Undecided
    if (lead.isEmpty() || (!ended && (listOf(HAND_OVER, BUILD_NAMED_OPEN, MAC_OPEN, STOP, STOP_NAMED_OPEN) + MENDING_MARKS).any { it.startsWith(lead) })) return FrontRead.Undecided
    val said = MENDING_MARKS.fold(text.replace(HAND_OVER, "").replace(BUILD_NAMED_MARK, "").replace(STOP, "")) { left, mark -> left.replace(mark, "") }
        .replace(STOP_NAMED_MARK, "").replace(MAC_MARK, "")
    return FrontRead.Answer(said.dropLast(if (ended) 0 else arrivingMark(said)).trimEnd())
}

/**
 * The words that can make his request an order to stop. The front agent alone decides that it is one; this is
 * only what the launcher asks for besides, because stopping throws work away and the real model was seen to
 * answer a plain "thanks" with the mark that stops (2026-10-13).
 */
private val STOP_WORDS = Regex("\\b(stop|cancel|abort|halt|kill|quit|pause|scrap|enough|wait|never ?mind|forget (it|that|about it)|hold on)\\b", RegexOption.IGNORE_CASE)

/** Whether [words], what he said, could be an order to stop at all: they have a word of stopping in them. */
fun wordsOfStopping(words: String) = STOP_WORDS.containsMatchIn(words)

/**
 * The words that can make his request one to mend the builder: about it being stuck, frozen, slow or broken, or
 * asking to restart, fix or clean it up. As with stopping, the front agent decides; this is what the launcher
 * asks for besides, because a restart throws the work under way away.
 */
private val MENDING_WORDS = Regex(
    "\\b(stuck|frozen|freez\\w*|froze|hung|hang\\w*|slow\\w*|broke\\w*|dead|crash\\w*|unresponsive|wedged|restart\\w*|reboot|" +
        "unstick|unfreeze|kick|fix\\w*|repair|reset|mend|clean\\w*|leftover\\w*|orphan\\w*|zombie\\w*)\\b",
    RegexOption.IGNORE_CASE,
)

/** Whether [words], what he said, could ask for the builder to be mended at all. */
fun wordsOfMending(words: String) = MENDING_WORDS.containsMatchIn(words)

/**
 * [read] as the launcher acts on it for [words], his own. A mark that stops which his words do not bear out
 * stops nothing: it is read as a hand-over to the same place, which is where the front agent's mistaken marks
 * have always gone, and his words then wait there like any other request. A mark that restarts or cleans which
 * his words do not bear out is a hand-over to the builder the same way.
 */
fun borneOut(read: FrontRead, words: String): FrontRead = when {
    read is FrontRead.Stop && !wordsOfStopping(words) -> FrontRead.HandOver(read.of)
    (read == FrontRead.Restart || read == FrontRead.Clean) && !wordsOfMending(words) -> FrontRead.HandOver(Route.BUILD)
    read is FrontRead.RestartOne && !wordsOfMending(words) -> FrontRead.HandOver(Route.builder(read.builder))
    else -> read
}

/** Where a hand-over goes, once the mark is checked against the threads the front agent was shown. */
sealed interface FrontRoute {
    /** New work for the phone's builders: whichever is free takes it ([pickBuilder]). */
    data object Build : FrontRoute

    /** One builder by name: his words follow up on what it asked or is doing, and wait behind its task if it has one. */
    data class Builder(val builder: BuilderId) : FrontRoute

    /**
     * A thread on the Mac. [fresh] is the front agent's word that his request is separate work on that thread's
     * project, which it is busy with: [macFresh] then says where it goes.
     */
    data class Mac(val thread: MacThread, val fresh: Boolean = false) : FrontRoute

    /**
     * A mark that names no thread on the board. Nothing is guessed from it: the request goes nowhere, and he
     * is asked which project it is for with his words kept.
     */
    data class Unknown(val named: String) : FrontRoute
}

/** [to] is what [FrontRead.HandOver] or [FrontRead.Stop] carries; [mac] is the board the front agent read, null when no Mac is set up. */
fun frontRoute(to: String, mac: MacBoard?): FrontRoute {
    Route.builderOf(to)?.let { return FrontRoute.Builder(it) }
    val fresh = Route.macNewThread(to)
    val named = fresh ?: Route.macThread(to) ?: return FrontRoute.Build
    val thread = mac?.threads?.firstOrNull { it.id == named } ?: return FrontRoute.Unknown(named)
    return FrontRoute.Mac(thread, fresh = fresh != null)
}

/** What he reads in place of an answer when the front agent named a thread that is not there. */
const val WHICH_PROJECT = "Which project is this for? Say it again with the project's name."

/** What he reads when the front agent would stop a thread that is not there. Nothing is stopped on a guess. */
const val WHICH_TASK = "Which task should stop? Say it again with the project's name."

const val NOTHING_RUNNING = "Nothing was running."

/** How the phone's builder is called in the lines about where his words wait and what was stopped. */
const val BUILDER = "the builder"

/** What he reads once an order to stop was carried out: [what] is the builder or a thread's name, [running] whether it had a task. */
fun stoppedText(what: String, running: Boolean) = if (running) "Stopped $what." else NOTHING_RUNNING

/** How a request to the front agent ended. */
enum class FrontOutcome {
    /** It handed the request on, or had a task stopped, which is done by the time this is known. */
    HandedOver,

    /** It answered. */
    Answered,

    /** He stopped it: nothing more is shown and nothing is sent anywhere. */
    Stopped,

    /** It gave no answer, so the build lane gets the request as if there were no front agent. */
    FellBack,

    /** It failed part of the way through an answer: what it said stays, marked as failed. */
    Broken,
}

/**
 * The rule for a front agent that fails: with nothing of an answer to show (not installed, an error, a
 * timeout, a lost conversation, no words at all) the request goes to the build lane, so nothing he says is
 * dropped. [failed] covers every such failure; [answered] is whether any of an answer was shown.
 */
fun frontOutcome(handed: Boolean, stopped: Boolean, failed: Boolean, answered: Boolean): FrontOutcome = when {
    handed -> FrontOutcome.HandedOver
    stopped -> FrontOutcome.Stopped
    !answered -> FrontOutcome.FellBack
    failed -> FrontOutcome.Broken
    else -> FrontOutcome.Answered
}

/**
 * The longest request the front agent is asked first. The thread keeps this much of a prompt, and the
 * `[ send to build ]` under a front answer sends the request from the thread: so it is always the whole of it.
 */
const val FRONT_MAX_REQUEST = ChatHistory.MAX_PROMPT

/**
 * Whether the front agent hears a request before the build lane does. Images are the builder's to read.
 * Whether it is still on the request before makes no difference (Boss, 2026-10-13): this one then waits its
 * turn in [FrontQueue], and is never sent past the front agent to the builder because of it.
 */
fun frontFirst(prompt: String, images: Int, ready: Boolean): Boolean = images == 0 && ready && prompt.length <= FRONT_MAX_REQUEST

/**
 * A request he sent while the front agent was still reading the one before. [time] is its entry in the thread,
 * where it already shows as his, waiting. [page] is the home page he was on and [away] whether he spoke it on
 * the listening screen, [chat] the new chat he sent it in (empty for the Oma tab). Nothing of the board is kept
 * with it: it is read with the board as it stands then.
 */
data class FrontWaiting(val time: Long, val prompt: String, val page: String? = null, val away: Boolean = false, val chat: String = "")

/**
 * The requests waiting their turn for the front agent, oldest first. Each is read as a request of its own once
 * the one ahead is answered or handed on, so the order he sent them in is the order they are decided in. Kept
 * in memory only: after a restart there is none, and the entries say they were interrupted. Not safe to share
 * between threads; QuickLane holds its lock around every call.
 */
class FrontQueue(private val limit: Int = MAX_WAITING) {
    private val queue = ArrayDeque<FrontWaiting>()

    val waiting: List<FrontWaiting> get() = queue.toList()

    /** False when as many as fit already wait; nothing is added then. */
    fun add(request: FrontWaiting): Boolean {
        if (queue.size >= limit) return false
        queue.addLast(request)
        return true
    }

    /** The oldest, which is read now; null when none waits. */
    fun next(): FrontWaiting? = queue.removeFirstOrNull()

    /** Takes out the one filed at [time]; null when it does not wait any more, and nothing changes then. */
    fun cancel(time: Long): FrontWaiting? = queue.firstOrNull { it.time == time }?.also { queue.remove(it) }

    /** Takes out what [which] picks, all of it unless told otherwise, and returns it for the thread to say so. */
    fun drop(which: (FrontWaiting) -> Boolean = { true }): List<FrontWaiting> = queue.filter(which).also { queue.removeAll(it.toSet()) }

    companion object {
        /** Past this the newest would wait minutes to be read at all, so he is told instead. */
        const val MAX_WAITING = 8

        const val FULL = "$MAX_WAITING messages are already waiting for Oma to read. Send this one in a moment."
        const val CANCELLED = "You cancelled this before Oma read it."
        const val STOPPED = "You pressed stop before Oma read this, so it was not sent."
        const val UNREAD = "Oma could not read this."
    }
}

/** As many requests as fit already wait for the front agent. The one that met this is nowhere: not filed, not sent. */
class FrontQueueFull : IllegalStateException(FrontQueue.FULL)

/** What an entry that waits says about where, and whether `[ cancel ]` stands beside it. */
data class WaitingLine(val text: String, val cancel: Boolean)

/**
 * The line under [task] while it waits, null when it does not. One the front agent has not read says when it
 * will be, [ahead] being how many wait before it. One handed to the phone's builder says so, and either can
 * still be taken back. One on the Mac says which thread has it once the Mac holds it, and before that what
 * [macLabel] says of its sending; the runner cannot take a single waiting request back, so no cancel is offered.
 * A new chat's first words waiting for a free builder say that it will start afresh, and can be taken back.
 */
fun waitingLine(task: ChatTask, ahead: Int = 0, macLabel: String = ""): WaitingLine? = when {
    task.status != TaskStatus.Queued -> null
    task.undecided -> WaitingLine(if (ahead <= 0) "Oma will read this next" else "Oma will read this after $ahead more", cancel = true)
    task.mac -> WaitingLine(if (macLabel == MAC_QUEUED) "queued → ${task.threadName} · queued on the Mac" else "$macLabel…", cancel = false)
    task.fresh -> WaitingLine(FRESH_WAITING, cancel = true)
    else -> WaitingLine("queued → " + if (task.builderId == BuilderId.A) BUILDER else builderCalled(task.builderId), cancel = true)
}

/** What a new chat's first words say while both builders are busy: they wait for the first free one, which starts afresh. */
const val FRESH_WAITING = "both builders are busy · starts a fresh session on the first one free"

/**
 * What the front agent is told of the build lane before each request: whether it is [working], its [label]
 * and the start of the [request] it is on; otherwise the end of its [lastAnswer], which may have asked him
 * something. [spokeAgo] is how many milliseconds ago he last sent it anything, null if he never has. [health]
 * is [healthLine]: null when the builder is idle and nothing is wrong, and the board then is as it was before.
 */
data class BuildBoard(
    val working: Boolean,
    val label: String = "",
    val request: String = "",
    val lastAnswer: String = "",
    val spokeAgo: Long? = null,
    val health: String? = null,
)

const val BOARD_REQUEST_CHARS = 200
const val BOARD_ANSWER_CHARS = 300

/**
 * The board of [builder] as the thread and its state have it at [now]. Front answers are no part of the build
 * lane, and another builder's requests are no part of this one's.
 */
fun buildBoard(tasks: List<ChatTask>, working: Boolean, label: String, now: Long, builder: BuilderId = BuilderId.A): BuildBoard {
    val sent = tasks.filter { it.build && it.builderId == builder && it.status != TaskStatus.Dropped }
    val ended = sent.lastOrNull { it.status != TaskStatus.Running && it.status != TaskStatus.Queued && it.result.isNotBlank() }
    return BuildBoard(
        working = working,
        label = if (working) label else "",
        request = if (working) sent.lastOrNull { it.status == TaskStatus.Running }?.prompt.orEmpty() else "",
        lastAnswer = if (working) "" else ended?.result.orEmpty(),
        spokeAgo = sent.lastOrNull()?.let { (now - it.time).coerceAtLeast(0) },
    )
}

private fun oneLine(text: String) = text.trim().replace(Regex("\\s+"), " ").replace('"', '\'')

private fun ago(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 120 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} hours ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/**
 * What the front agent is told of the Mac: its project [threads], already cut to the ones the board lists,
 * whether it was [reachable] at the last check, and when it last answered ([seen], in milliseconds like [now];
 * null if it never has). Nothing of an approval is ever part of it: the threads' waiting requests are left out
 * of the text, and only their status says that one waits for Boss.
 */
data class MacBoard(val threads: List<MacThread>, val reachable: Boolean, val seen: Long?, val now: Long)

const val BOARD_THREADS = 8
const val BOARD_THREAD_CHARS = 320
private const val BOARD_NAME_CHARS = 40
private const val BOARD_SUMMARY_CHARS = 80
private const val BOARD_TAIL_CHARS = 120
private const val BOARD_FOLDER_CHARS = 40

/** The board's part about the Mac: the newest worked [BOARD_THREADS] of the threads a mark can name. */
fun macBoard(threads: List<MacThread>, reachable: Boolean, seen: Long?, now: Long) =
    MacBoard(threads.filter { macNameable(it.id) }.sortedByDescending { it.lastWorked ?: Long.MIN_VALUE }.take(BOARD_THREADS), reachable, seen, now)

private fun macThreadLine(thread: MacThread, now: Long): String {
    val status = when (thread.status) {
        MacThreadStatus.Idle -> "idle"
        MacThreadStatus.Working -> "working"
        MacThreadStatus.Waiting -> "waiting for Boss to approve a step"
        MacThreadStatus.Interrupted -> "interrupted"
        MacThreadStatus.Unknown -> "state unknown"
    }
    val summary = oneLine(thread.summary)
    val tail = oneLine(thread.tail)
    val worked = thread.lastWorked?.let { ", last worked ${ago((now - it * 1000).coerceAtLeast(0))}" }.orEmpty()
    val asked = if (summary.isEmpty()) "" else "; Boss last asked it \"${summary.take(BOARD_SUMMARY_CHARS)}${if (summary.length > BOARD_SUMMARY_CHARS) "..." else ""}\""
    val said = if (tail.isEmpty()) "" else "; it last said \"${if (tail.length > BOARD_TAIL_CHARS) "..." else ""}${tail.takeLast(BOARD_TAIL_CHARS)}\""
    // The folder, so two threads at work on one project can be seen to be: that is when separate work gets a fresh thread.
    val folder = macFolder(thread.project).takeIf { it.isNotEmpty() }?.let { " in folder ${oneLine(it).take(BOARD_FOLDER_CHARS)}" }.orEmpty()
    return "- \"${oneLine(thread.name).take(BOARD_NAME_CHARS)}\" id ${thread.id}$folder: $status$worked$asked$said".take(BOARD_THREAD_CHARS)
}

private fun macLines(mac: MacBoard?): List<String> {
    if (mac == null) return emptyList()
    val link = when {
        mac.reachable -> "Mac: reachable."
        mac.seen == null -> "Mac: not reachable, not seen yet."
        else -> "Mac: not reachable, last seen ${ago((mac.now - mac.seen).coerceAtLeast(0))}."
    }
    val threads = if (mac.threads.isEmpty()) listOf("Project threads on the Mac: none.") else listOf("Project threads on the Mac:") + mac.threads.map { macThreadLine(it, mac.now) }
    return listOf(link) + threads
}

/** Someone who has told him something and now rests: [who] as the board calls them, how long [since], and whether they [asked]. */
private data class Spoke(val who: String, val since: Long, val asked: Boolean)

/** Whether [said] leaves him with a question: it ends in one, whatever Markdown or quote marks close it. */
private fun asks(said: String) = said.trimEnd { it.isWhitespace() || it in "*_`\"')" }.endsWith("?")

/** How many others with a question still open the line names besides the last to speak. */
private const val BOARD_OPEN_QUESTIONS = 2

/**
 * One line that says who told him something most recently and whether it was a question, so that a bare "yes"
 * is not left to be worked out from the times scattered over the board; then who else still waits for an
 * answer of his. Made only from what the board already says: the builder's last answer and when he last spoke
 * to it, and each idle thread's last words and when it last worked. One that is working, or waiting for an
 * approval, has not finished speaking and is not named. Null when nobody has said anything.
 */
private fun lastToSpeak(board: BuildBoard, mac: MacBoard, second: BuildBoard?): String? {
    fun spoke(who: String, board: BuildBoard) = if (!board.working && board.lastAnswer.isNotBlank() && board.spokeAgo != null) Spoke(who, board.spokeAgo, asks(board.lastAnswer)) else null
    val builders = if (second == null) listOfNotNull(spoke("the builder", board)) else listOfNotNull(spoke("builder A", board), spoke("builder B", second))
    val threads = mac.threads.filter { it.status == MacThreadStatus.Idle && it.tail.isNotBlank() && it.lastWorked != null }.map {
        Spoke("thread \"${oneLine(it.name).take(BOARD_NAME_CHARS)}\" (id ${it.id})", (mac.now - (it.lastWorked ?: 0L) * 1000).coerceAtLeast(0), asks(it.tail))
    }
    val spoke = (builders + threads).sortedBy { it.since }
    val last = spoke.firstOrNull() ?: return null
    val open = spoke.drop(1).filter { it.asked }.take(BOARD_OPEN_QUESTIONS)
    val others = if (last.asked) " An older question also waits: " else " A question still waits for an answer from Boss: "
    return "Last to speak: ${last.who}, ${ago(last.since)}" + (if (last.asked) ", and it asked a question that Boss has not answered." else ", and it asked nothing.") +
        (if (open.isEmpty()) "" else others + open.joinToString("; ") { "${it.who}, ${ago(it.since)}" } + ".")
}

/** A builder's two lines on the board: how it stands, called [name], and when he last spoke to it, called [spoken]. */
private fun builderLines(board: BuildBoard, name: String, spoken: String): List<String> {
    val request = oneLine(board.request)
    val answer = oneLine(board.lastAnswer)
    val builder = when {
        board.working -> "$name: working (${oneLine(board.label).take(80)})" +
            (if (request.isEmpty()) "." else " on \"${request.take(BOARD_REQUEST_CHARS)}${if (request.length > BOARD_REQUEST_CHARS) "..." else ""}\".")
        answer.isNotEmpty() -> "$name: idle. Its last answer ended: \"${if (answer.length > BOARD_ANSWER_CHARS) "..." else ""}${answer.takeLast(BOARD_ANSWER_CHARS)}\""
        board.spokeAgo == null -> "$name: idle. It has not been asked anything yet."
        else -> "$name: idle."
    }
    return listOfNotNull(builder, board.health, board.spokeAgo?.let { "Boss last spoke to $spoken ${ago(it)}." })
}

/**
 * The board in the words the front agent reads, ending where his own words begin. He never sees it. With no
 * Mac set up ([mac] null) and builder B never at work ([second] null) it is the builder's lines and nothing
 * else, exactly as it was before there were threads; once B is, each builder has its lines under its letter.
 */
fun boardText(board: BuildBoard, mac: MacBoard? = null, second: BuildBoard? = null): String {
    val builders = if (second == null) builderLines(board, "Builder", "the builder")
    else builderLines(board, "Builder A", "builder A") + builderLines(second, "Builder B", "builder B")
    val lines = listOf("[Board, for you only; Boss does not see it]") + builders
    return (lines + macLines(mac) + listOfNotNull(mac?.let { lastToSpeak(board, it, second) }) + "[Boss says]").joinToString("\n")
}

/** What the front agent is sent: the board, then [request] exactly as he said it. */
fun frontPrompt(board: BuildBoard, request: String, mac: MacBoard? = null, second: BuildBoard? = null): String = boardText(board, mac, second) + "\n" + request

/**
 * One request the front agent answered itself: [said] is his words and [answered] what it told him. The
 * receiver of a later hand-over was not there for it, and "put that on the list" means nothing without it.
 */
data class FrontExchange(val said: String, val answered: String)

const val CONTEXT_EXCHANGES = 4
const val CONTEXT_SIDE_CHARS = 400
const val CONTEXT_CHARS = 2_000

/** The line the context starts with. It says what follows is not his request, so a receiver never acts on it. */
const val CONTEXT_OPEN = "[Earlier, between Boss and Oma's quick answers; context only, not a request]"

/** The line the context ends with. Everything after it is his words, untouched. */
const val CONTEXT_CLOSE = "[Boss now says]"

/** Whether the phone's builders were sent [task]: a request of their own that was not dropped before it got there. */
fun builderGot(task: ChatTask) = task.build && task.status != TaskStatus.Dropped

/** Whether [builder] itself was sent a task, as [builderGot] has it: what another builder was sent, this one missed. */
fun gotBy(builder: BuilderId): (ChatTask) -> Boolean = { builderGot(it) && it.builderId == builder }

/** Whether the thread [threadId] on the Mac was sent [task], or is being sent it. One waiting for a new thread under its id is not. */
fun threadGot(task: ChatTask, threadId: String) = task.thread == threadId && !task.fresh && task.status != TaskStatus.Dropped

/**
 * What a receiver has missed when a request sent at [asked] is handed to it: the requests before that one which
 * the front agent answered itself, since the last request the receiver [got], newest [CONTEXT_EXCHANGES]. Only
 * his words and the front agent's answers are taken. A request handed to anyone is not an answer of the front
 * agent's, so no mark, no approval and nothing another receiver said can be among them.
 */
fun handOverContext(tasks: List<ChatTask>, asked: Long, got: (ChatTask) -> Boolean): List<FrontExchange> {
    val before = tasks.filter { it.time < asked }
    val since = before.lastOrNull(got)?.time ?: Long.MIN_VALUE
    return before.filter { it.time > since && it.quick && it.status != TaskStatus.Running && it.result.isNotBlank() }
        .takeLast(CONTEXT_EXCHANGES).map { FrontExchange(it.prompt, it.result) }
}

/** One side of an exchange on one line, so nothing in it can stand as a line of its own and pass for a heading. */
private fun contextSide(text: String): String {
    val line = text.trim().replace(Regex("\\s+"), " ")
    return if (line.length > CONTEXT_SIDE_CHARS) line.take(CONTEXT_SIDE_CHARS) + "..." else line
}

/**
 * What stands before his words when a request is handed over: [exchanges] between the two headings, the oldest
 * left out until it is no longer than [CONTEXT_CHARS]. Empty when there is nothing to tell, and the receiver
 * then gets his words and nothing else.
 */
fun handOverPreface(exchanges: List<FrontExchange>): String {
    fun text(kept: List<FrontExchange>) =
        (listOf(CONTEXT_OPEN) + kept.flatMap { listOf("Boss: " + contextSide(it.said), "Oma: " + contextSide(it.answered)) } + CONTEXT_CLOSE).joinToString("\n", postfix = "\n")
    var kept = exchanges.takeLast(CONTEXT_EXCHANGES)
    while (kept.isNotEmpty() && text(kept).length > CONTEXT_CHARS) kept = kept.drop(1)
    return if (kept.isEmpty()) "" else text(kept)
}

/** What a receiver is sent: [preface], then [words] exactly as he said them. Only ever sent, never shown or saved. */
fun handOverText(preface: String, words: String) = preface + words

/** How both notes about work in the same project begin. */
const val SHARED_NOTE_OPEN = "[Note from Oma: "

/**
 * What a thread on the Mac is sent first when another thread works in the same project folder right now, so the
 * two never write over each other's files in one checkout. One line, never shown to him or saved.
 */
const val MAC_SHARED_NOTE = SHARED_NOTE_OPEN + "another agent is working in this same project right now. Do this work on a new git branch in its own " +
    "worktree (git worktree add), not in the shared checkout, and merge it back when you are done. If the merge conflicts, stop and " +
    "tell Boss which files clash instead of overwriting.]"

/**
 * The same for one of the phone's builders while the other works. They share one home and whichever repositories
 * are in it, and which one the other is in is not known, so it says as much and asks for a worktree only in Git.
 */
const val PHONE_SHARED_NOTE = SHARED_NOTE_OPEN + "another agent is working on this phone right now and may be in the same project. If this work " +
    "is in a git repository, do it on a new git branch in its own worktree (git worktree add), not in the shared checkout, and merge it " +
    "back when you are done. If the merge conflicts, stop and tell Boss which files clash instead of overwriting.]"

/**
 * [preface] with [note] on its own line before it when [shared], which [builderShares] or [macShares] decides;
 * otherwise [preface] alone, so a receiver that works by itself gets what it always did.
 */
fun sharedPreface(shared: Boolean, note: String, preface: String) = if (shared) note + "\n" + preface else preface

/**
 * His words out of [sent], which may be a [handOverText], with a note about shared work before it ([sharedPreface]).
 * A thread on the Mac says back what it was sent, and this is how that is matched to the entry that holds his
 * words alone. The sides of an exchange are one line each and start with a name, so the first closing heading
 * on a line of its own is the real one.
 */
fun handOverWords(sent: String): String {
    val note = listOf(MAC_SHARED_NOTE, PHONE_SHARED_NOTE).firstOrNull { sent.startsWith(it + "\n") }
    val after = if (note == null) sent else sent.removePrefix(note + "\n")
    if (!after.startsWith(CONTEXT_OPEN + "\n")) return after
    val close = "\n" + CONTEXT_CLOSE + "\n"
    val at = after.indexOf(close)
    return if (at < 0) after else after.substring(at + close.length)
}

/** What the corrections log says an entry was before he sent it to build himself. */
const val FRONT_ANSWERED = "front-answered"

/** The front agent named a thread that is not on the board, and he was asked which project he meant. */
const val UNKNOWN_THREAD = "unknown-thread"

/** A request for a thread could not be sent because the Mac was out of reach, and he sent it again himself. */
const val MAC_TRIED_AGAIN = "mac-tried-again"

const val ROUTING_LOG_LINES = 200
const val ROUTING_LOG_REQUEST_CHARS = 500

/** One line of the corrections log: when, the start of the request, and how it had been routed. */
fun routingCorrection(time: Long, request: String, was: String = FRONT_ANSWERED): String =
    JSONObject().put("time", time).put("request", request.take(ROUTING_LOG_REQUEST_CHARS)).put("was", was).toString()

/** The log with [line] added, keeping the newest [ROUTING_LOG_LINES]. */
fun routingLog(lines: List<String>, line: String): List<String> = (lines.filter { it.isNotBlank() } + line).takeLast(ROUTING_LOG_LINES)

/**
 * The requests that did not go where he wanted at the first try: ones the front agent answered that he then
 * sent to build, ones it handed to a thread that is not there, and ones he had to send to the Mac a second
 * time. [was] says which. Kept in the app's private files so they can be counted and fed back as examples.
 * Nothing reads it yet.
 */
object RoutingLog {
    @Synchronized fun corrected(context: Context, request: String, was: String = FRONT_ANSWERED) {
        runCatching {
            val file = File(context.filesDir, "oma-routing.jsonl")
            val kept = if (file.isFile) file.readLines() else emptyList()
            file.writeText(routingLog(kept, routingCorrection(System.currentTimeMillis(), request, was)).joinToString("\n", postfix = "\n"))
        }
    }
}
