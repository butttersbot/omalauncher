package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject
import java.io.File

// Boss talks to one Oma (Boss, 2026-10-13). The front agent, which is the fast model of [QuickLane], hears a
// request first and either answers it or hands his words on unchanged. Everything that decides that is here
// and pure; QuickLane runs the process and does what these say.

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
}

/** Whether [tail], the end of what has been said, could still grow into a mark. */
private fun opensMark(tail: String) =
    (tail.length < HAND_OVER.length && HAND_OVER.startsWith(tail)) || MAC_OPEN.startsWith(tail) || MAC_ARRIVING.matches(tail)

/** How many characters at the end of [said] are the start of a mark that may still be arriving. */
private fun arrivingMark(said: String): Int {
    val open = said.lastIndexOf("<<")
    if (open >= 0 && opensMark(said.substring(open))) return said.length - open
    return if (said.endsWith("<")) 1 else 0
}

/**
 * Reads [text], everything the front agent has said so far; [ended] once no more is coming. A reply that
 * starts with a mark hands over, whatever follows it and whatever spaces or backticks stand before it. One
 * that only mentions a mark is an answer, shown without it: handing over on a mention would send work he
 * never asked for. While more may come, the start of a mark at the end is held back so it never flickers by.
 * A mark for a thread on the Mac is read up to its closing `>>` and names whatever stands there; whether
 * that is a thread at all is [frontRoute]'s to say. One that never closes names nothing, and is still a
 * hand-over, to nowhere: it is never shown to him as an answer.
 */
fun frontRead(text: String, ended: Boolean = false): FrontRead {
    val lead = text.trimStart { it.isWhitespace() || it == '`' }
    if (lead.startsWith(HAND_OVER)) return FrontRead.HandOver(Route.BUILD)
    if (lead.startsWith(MAC_OPEN)) {
        val close = lead.indexOf(">>")
        if (close < 0 && !ended && MAC_ARRIVING.matches(lead)) return FrontRead.Undecided
        val named = if (close < 0) "" else lead.substring(MAC_OPEN.length, close).trim()
        return FrontRead.HandOver(Route.mac(named.takeIf { it.length <= MAC_ID_CHARS }.orEmpty()))
    }
    if (lead.isEmpty() || (!ended && (HAND_OVER.startsWith(lead) || MAC_OPEN.startsWith(lead)))) return FrontRead.Undecided
    val said = text.replace(HAND_OVER, "").replace(MAC_MARK, "")
    return FrontRead.Answer(said.dropLast(if (ended) 0 else arrivingMark(said)).trimEnd())
}

/** Where a hand-over goes, once the mark is checked against the threads the front agent was shown. */
sealed interface FrontRoute {
    /** The phone's builder. */
    data object Build : FrontRoute

    data class Mac(val thread: MacThread) : FrontRoute

    /**
     * A mark that names no thread on the board. Nothing is guessed from it: the request goes nowhere, and he
     * is asked which project it is for with his words kept.
     */
    data class Unknown(val named: String) : FrontRoute
}

/** [to] is what [FrontRead.HandOver] carries; [mac] is the board the front agent read, null when no Mac is set up. */
fun frontRoute(to: String, mac: MacBoard?): FrontRoute {
    val named = Route.macThread(to) ?: return FrontRoute.Build
    val thread = mac?.threads?.firstOrNull { it.id == named } ?: return FrontRoute.Unknown(named)
    return FrontRoute.Mac(thread)
}

/** What he reads in place of an answer when the front agent named a thread that is not there. */
const val WHICH_PROJECT = "Which project is this for? Say it again with the project's name."

/** How a request to the front agent ended. */
enum class FrontOutcome {
    /** It handed the request on, which is done by the time this is known. */
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
 * Whether the front agent hears a request before the build lane does. Images are the builder's to read, and
 * the front agent takes one request at a time: what he sends while it is on another goes straight on, never refused.
 */
fun frontFirst(prompt: String, images: Int, ready: Boolean, busy: Boolean): Boolean =
    images == 0 && ready && !busy && prompt.length <= FRONT_MAX_REQUEST

/**
 * What the front agent is told of the build lane before each request: whether it is [working], its [label]
 * and the start of the [request] it is on; otherwise the end of its [lastAnswer], which may have asked him
 * something. [spokeAgo] is how many milliseconds ago he last sent it anything, null if he never has.
 */
data class BuildBoard(
    val working: Boolean,
    val label: String = "",
    val request: String = "",
    val lastAnswer: String = "",
    val spokeAgo: Long? = null,
)

const val BOARD_REQUEST_CHARS = 200
const val BOARD_ANSWER_CHARS = 300

/** The board as the thread and the build lane's state have it at [now]. Front answers are no part of the build lane. */
fun buildBoard(tasks: List<ChatTask>, working: Boolean, label: String, now: Long): BuildBoard {
    val sent = tasks.filter { it.build && it.status != TaskStatus.Dropped }
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
    return "- \"${oneLine(thread.name).take(BOARD_NAME_CHARS)}\" id ${thread.id}: $status$worked$asked$said".take(BOARD_THREAD_CHARS)
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

/**
 * The board in the words the front agent reads, ending where his own words begin. He never sees it. With no
 * Mac set up ([mac] null) it is the builder's lines and nothing else, exactly as it was before there were threads.
 */
fun boardText(board: BuildBoard, mac: MacBoard? = null): String {
    val request = oneLine(board.request)
    val answer = oneLine(board.lastAnswer)
    val builder = when {
        board.working -> "Builder: working (${oneLine(board.label).take(80)})" +
            (if (request.isEmpty()) "." else " on \"${request.take(BOARD_REQUEST_CHARS)}${if (request.length > BOARD_REQUEST_CHARS) "..." else ""}\".")
        answer.isNotEmpty() -> "Builder: idle. Its last answer ended: \"${if (answer.length > BOARD_ANSWER_CHARS) "..." else ""}${answer.takeLast(BOARD_ANSWER_CHARS)}\""
        board.spokeAgo == null -> "Builder: idle. It has not been asked anything yet."
        else -> "Builder: idle."
    }
    val lines = listOfNotNull(
        "[Board, for you only; Boss does not see it]",
        builder,
        board.spokeAgo?.let { "Boss last spoke to the builder ${ago(it)}." },
    )
    return (lines + macLines(mac) + "[Boss says]").joinToString("\n")
}

/** What the front agent is sent: the board, then [request] exactly as he said it. */
fun frontPrompt(board: BuildBoard, request: String, mac: MacBoard? = null): String = boardText(board, mac) + "\n" + request

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
