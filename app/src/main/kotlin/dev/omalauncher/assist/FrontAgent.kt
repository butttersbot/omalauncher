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
 * Where a request can go. An id and not an enum, because project threads and a runner on the Mac will be more
 * of them; today the front agent answers or hands on to the one builder.
 */
object Route {
    const val FRONT = "front"
    const val BUILD = "build"
}

/** The whole reply the front agent gives to hand a request to [route]. Never shown and never saved. */
fun handOverMark(route: String) = "<<$route>>"

val HAND_OVER = handOverMark(Route.BUILD)

/** What the front agent's words so far amount to. */
sealed interface FrontRead {
    /** Nothing yet, or only the start of what could still be the mark. */
    data object Undecided : FrontRead

    /** An answer of its own; [text] is what may be shown of it. */
    data class Answer(val text: String) : FrontRead

    /** The request goes to [to], as he said it. */
    data class HandOver(val to: String) : FrontRead
}

/**
 * Reads [text], everything the front agent has said so far; [ended] once no more is coming. A reply that
 * starts with the mark hands over, whatever follows it and whatever spaces or backticks stand before it. One
 * that only mentions the mark is an answer, shown without it: handing over on a mention would send work he
 * never asked for. While more may come, the start of a mark at the end is held back so it never flickers by.
 */
fun frontRead(text: String, ended: Boolean = false): FrontRead {
    val lead = text.trimStart { it.isWhitespace() || it == '`' }
    if (lead.startsWith(HAND_OVER)) return FrontRead.HandOver(Route.BUILD)
    if (lead.isEmpty() || (!ended && HAND_OVER.startsWith(lead))) return FrontRead.Undecided
    val said = text.replace(HAND_OVER, "")
    val arriving = if (ended) 0 else (HAND_OVER.length - 1 downTo 1).firstOrNull { said.endsWith(HAND_OVER.take(it)) } ?: 0
    return FrontRead.Answer(said.dropLast(arriving).trimEnd())
}

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

/** The board in the words the front agent reads, ending where his own words begin. He never sees it. */
fun boardText(board: BuildBoard): String {
    val request = oneLine(board.request)
    val answer = oneLine(board.lastAnswer)
    val builder = when {
        board.working -> "Builder: working (${oneLine(board.label).take(80)})" +
            (if (request.isEmpty()) "." else " on \"${request.take(BOARD_REQUEST_CHARS)}${if (request.length > BOARD_REQUEST_CHARS) "..." else ""}\".")
        answer.isNotEmpty() -> "Builder: idle. Its last answer ended: \"${if (answer.length > BOARD_ANSWER_CHARS) "..." else ""}${answer.takeLast(BOARD_ANSWER_CHARS)}\""
        board.spokeAgo == null -> "Builder: idle. It has not been asked anything yet."
        else -> "Builder: idle."
    }
    return listOfNotNull(
        "[Board, for you only; Boss does not see it]",
        builder,
        board.spokeAgo?.let { "Boss last spoke to the builder ${ago(it)}." },
        "[Boss says]",
    ).joinToString("\n")
}

/** What the front agent is sent: the board, then [request] exactly as he said it. */
fun frontPrompt(board: BuildBoard, request: String): String = boardText(board) + "\n" + request

/** What the corrections log says an entry was before he sent it to build himself. */
const val FRONT_ANSWERED = "front-answered"

const val ROUTING_LOG_LINES = 200
const val ROUTING_LOG_REQUEST_CHARS = 500

/** One line of the corrections log: when, the start of the request, and how it had been routed. */
fun routingCorrection(time: Long, request: String, was: String = FRONT_ANSWERED): String =
    JSONObject().put("time", time).put("request", request.take(ROUTING_LOG_REQUEST_CHARS)).put("was", was).toString()

/** The log with [line] added, keeping the newest [ROUTING_LOG_LINES]. */
fun routingLog(lines: List<String>, line: String): List<String> = (lines.filter { it.isNotBlank() } + line).takeLast(ROUTING_LOG_LINES)

/**
 * The requests the front agent answered that he then sent to build: each one a misroute, kept in the app's
 * private files so they can be counted and fed back as examples. Nothing reads it yet.
 */
object RoutingLog {
    @Synchronized fun corrected(context: Context, request: String) {
        runCatching {
            val file = File(context.filesDir, "oma-routing.jsonl")
            val kept = if (file.isFile) file.readLines() else emptyList()
            file.writeText(routingLog(kept, routingCorrection(System.currentTimeMillis(), request)).joinToString("\n", postfix = "\n"))
        }
    }
}
