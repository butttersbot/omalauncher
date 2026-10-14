package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import org.json.JSONObject
import java.io.File

// Boss talks to one Oma (Boss, 2026-10-13). The front agent, which is the fast model of [QuickLane], hears a
// request first and does one of two things (Boss, 2026-10-14: "Shrink the front agent to two jobs: answer, or
// say which project"): it answers, or it hands his words on unchanged to the phone's builders or a project on
// the Mac. Stopping, restarting and cleaning up are buttons on a tab's long-press menu, never its to do.
// Everything that decides that is here and pure; QuickLane runs the process and does what these say.

/**
 * Where a request can go. An id and not an enum, because every project thread on the Mac is one more: the
 * front agent answers, hands on to the phone's builders, or hands on to one of those threads.
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
 * The words of the phone's marks: [Route.BUILD], and the ones the prompt taught before 2026-10-14 (a builder by
 * letter, stop, restart, clean). A front conversation begun before then may still say one of those, so each is
 * read as [HAND_OVER] is, and is a plain hand-over: never carried out, and never shown to him.
 */
private val PHONE_WORDS = listOf(Route.BUILD, "stop", "restart", "clean")

/** A whole phone mark, wherever it stands, and one still arriving at the end of what has been said. */
private val PHONE_MARK = Regex("<<(?:${PHONE_WORDS.joinToString("|")})(?: [^<>\\n]{0,80})?>>")
private val PHONE_ARRIVING = Regex("<<(?:${PHONE_WORDS.joinToString("|")})(?: [^<>\\n]{0,80})?>?")

/** How every mark begins, for holding back the start of one while it may still be arriving. */
private val OPENS = PHONE_WORDS.map { "<<$it" } + MAC_OPEN

/** Whether [lead] opens with a phone mark: its word, then the closing brackets or a space before what it named. */
private fun phoneMark(lead: String) = PHONE_WORDS.any { lead.startsWith("<<$it>") || lead.startsWith("<<$it ") }

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
private fun opensMark(tail: String) = OPENS.any { it.startsWith(tail) } || PHONE_ARRIVING.matches(tail) || MAC_ARRIVING.matches(tail)

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
private fun macNamed(lead: String, open: String, ended: Boolean): String? {
    val close = lead.indexOf(">>")
    if (close < 0 && !ended && MAC_ARRIVING.matches(lead)) return null
    val named = if (close < 0) "" else lead.substring(open.length, close).trim()
    return named.takeIf { it.length <= MAC_ID_CHARS }.orEmpty()
}

/**
 * Reads [text], everything the front agent has said so far; [ended] once no more is coming. A reply that
 * starts with a mark hands over, whatever follows it and whatever spaces or backticks stand before it. One
 * that only mentions a mark is an answer, shown without it: handing over on a mention would send work he
 * never asked for. While more may come, the start of a mark at the end is held back so it never flickers by.
 * A phone mark is read once it closes, or once nothing more comes, and is new work for the phone's builders
 * whatever it named. A mark for a thread on the Mac is read up to its closing `>>` and names whatever stands
 * there; whether that is a thread at all is [frontRoute]'s to say. One that never closes names nothing, and is
 * still a hand-over, to nowhere: it is never shown to him as an answer.
 */
fun frontRead(text: String, ended: Boolean = false): FrontRead {
    val lead = text.trimStart { it.isWhitespace() || it == '`' }
    if (phoneMark(lead)) return if (ended || lead.contains(">>") || !PHONE_ARRIVING.matches(lead)) FrontRead.HandOver(Route.BUILD) else FrontRead.Undecided
    if (lead.startsWith(MAC_NEW_OPEN)) return macNamed(lead, MAC_NEW_OPEN, ended)?.let { FrontRead.HandOver(Route.macNew(it)) } ?: FrontRead.Undecided
    if (lead.startsWith(MAC_OPEN)) return macNamed(lead, MAC_OPEN, ended)?.let { FrontRead.HandOver(Route.mac(it)) } ?: FrontRead.Undecided
    if (lead.isEmpty() || (!ended && OPENS.any { it.startsWith(lead) })) return FrontRead.Undecided
    val said = text.replace(PHONE_MARK, "").replace(MAC_MARK, "")
    return FrontRead.Answer(said.dropLast(if (ended) 0 else arrivingMark(said)).trimEnd())
}

/** Where a hand-over goes, once the mark is checked against the threads the front agent was shown. */
sealed interface FrontRoute {
    /** Work for the phone's builders: the one that asked him, if [askingBuilder] finds one, else whichever is free ([pickBuilder]). */
    data object Build : FrontRoute

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

/** [to] is what [FrontRead.HandOver] carries; [mac] is the board the front agent read, null when no Mac is set up. */
fun frontRoute(to: String, mac: MacBoard?): FrontRoute {
    val fresh = Route.macNewThread(to)
    val named = fresh ?: Route.macThread(to) ?: return FrontRoute.Build
    val thread = mac?.threads?.firstOrNull { it.id == named } ?: return FrontRoute.Unknown(named)
    return FrontRoute.Mac(thread, fresh = fresh != null)
}

/** What he reads in place of an answer when the front agent named a thread that is not there. */
const val WHICH_PROJECT = "Which project is this for? Say it again with the project's name."

/** How the phone's builder is called in the line about where his words wait. */
const val BUILDER = "the builder"

/** How a request to the front agent ended. */
enum class FrontOutcome {
    /** It handed the request on. */
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

/** How a plain question opens: the words he starts one with, so "Is it raining in Seoul" is one even without its "?". */
private val QUESTION_OPENERS = setOf(
    "what", "whats", "who", "whos", "whom", "whose", "when", "where", "wheres", "why", "how", "hows", "which",
    "is", "isnt", "are", "arent", "was", "were", "do", "does", "doesnt", "did", "can", "could", "would", "should", "will", "has", "have", "am",
)

/**
 * Words that make a request work, whatever its form: the verbs the front agent is told to hand over for, more
 * verbs of doing, and what work is done on. "Can you fix the build?" is a question in form and work in fact.
 */
private val WORK_WORDS = setOf(
    "build", "make", "change", "fix", "install", "write", "edit", "run", "set", "setup", "continue", "create", "add", "remove",
    "delete", "update", "refactor", "implement", "list", "commit", "push", "merge", "deploy", "debug", "rename", "move", "finish",
    "start", "restart", "clean", "generate", "draft", "open", "send", "put", "review",
    "project", "repo", "repository", "file", "folder", "directory", "code", "codebase", "branch", "thread", "script", "bug", "feature",
)

/** Whether [word] is [stem] or one of its plain endings: fixes, fixed, fixing; makes, making; runs, running. */
private fun inflects(word: String, stem: String) =
    word == stem || word == stem + "s" || word == stem + "es" || word == stem + "ed" || word == stem + "d" || word == stem + "ing" ||
        (stem.endsWith("e") && word == stem.dropLast(1) + "ing") || word == stem + stem.last() + "ing" || word == stem + stem.last() + "ed"

/**
 * Whether [words] ask for work rather than ask a plain question (Boss, 2026-10-13: "The plus button should always
 * open up a Mac session"). A plain question opens as one or ends in "?" and has no word of [WORK_WORDS] in it, so
 * "What's the capital of Peru?" is a question and "In the Scratch project, list the files by name." is work, as
 * are "Can you fix the build?" and "Hi". Anything that is not plainly a question is work: in doubt it goes to the Mac.
 */
fun asksForWork(words: String): Boolean {
    val said = words.lowercase().replace("'", "").replace("’", "").split(Regex("[^\\p{L}\\p{N}?]+")).filter { it.isNotEmpty() }
    val plain = said.map { it.trimEnd('?') }.filter { it.isNotEmpty() }
    if (plain.isEmpty()) return true
    val question = plain.first() in QUESTION_OPENERS || words.trimEnd().endsWith("?")
    return !question || plain.any { word -> WORK_WORDS.any { inflects(word, it) } }
}

/**
 * Whether the front agent's answer of its own is held back from him and his words go to the Mac instead: in a
 * new chat ([chat] not empty) when [prompt] asks for work ([asksForWork]). Only a mark can send a new chat's
 * work anywhere; prose in reply to it is never shown, whatever the model was told. On the Oma tab it answers as ever.
 */
fun frontHolds(chat: String, prompt: String): Boolean = chat.isNotEmpty() && asksForWork(prompt)

/**
 * [task] as `[ try again ]` leaves it: a new chat's words the Mac was out of reach for ([MAC_CHAT_UNREACHABLE]),
 * with Oma again and waiting to be read, his words kept and what was said under them gone. Null for any other
 * entry: one he cancelled before Oma read it is not Oma's, and the Oma tab's are never sent again from here.
 */
fun chatRetried(task: ChatTask): ChatTask? =
    if (task.chat.isNotEmpty() && task.quick && task.status == TaskStatus.Dropped) task.copy(agent = UNDECIDED_AGENT, result = "", status = TaskStatus.Queued, answeredBy = "", steps = emptyList()) else null

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
 */
fun waitingLine(task: ChatTask, ahead: Int = 0, macLabel: String = ""): WaitingLine? = when {
    task.status != TaskStatus.Queued -> null
    task.undecided -> WaitingLine(if (ahead <= 0) "Oma will read this next" else "Oma will read this after $ahead more", cancel = true)
    task.mac -> WaitingLine(if (macLabel == MAC_QUEUED) "queued → ${task.threadName} · queued on the Mac" else "$macLabel…", cancel = false)
    else -> WaitingLine("queued → " + if (task.builderId == BuilderId.A) BUILDER else builderCalled(task.builderId), cancel = true)
}

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
 * null if it never has). [tailscale] says, while the Mac is out of reach, that Tailscale was found off on this
 * phone and is being turned back on ([healNote]), so the front agent can tell Boss the cause and that it is in
 * hand. Nothing of an approval is ever part of it: the threads' waiting requests are left out of the text, and
 * only their status says that one waits for Boss.
 */
data class MacBoard(val threads: List<MacThread>, val reachable: Boolean, val seen: Long?, val now: Long, val tailscale: String? = null)

const val BOARD_THREADS = 8
const val BOARD_THREAD_CHARS = 320
private const val BOARD_NAME_CHARS = 40
private const val BOARD_SUMMARY_CHARS = 80
private const val BOARD_TAIL_CHARS = 120
private const val BOARD_FOLDER_CHARS = 40

/** The board's part about the Mac: the newest worked [BOARD_THREADS] of the threads a mark can name. */
fun macBoard(threads: List<MacThread>, reachable: Boolean, seen: Long?, now: Long, tailscale: String? = null) =
    MacBoard(threads.filter { macNameable(it.id) }.sortedByDescending { it.lastWorked ?: Long.MIN_VALUE }.take(BOARD_THREADS), reachable, seen, now, tailscale)

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
    val away = when {
        mac.reachable -> null
        mac.seen == null -> "Mac: not reachable, not seen yet"
        else -> "Mac: not reachable, last seen ${ago((mac.now - mac.seen).coerceAtLeast(0))}"
    }
    val link = if (away == null) "Mac: reachable." else away + mac.tailscale?.let { " — $it" }.orEmpty() + "."
    val threads = if (mac.threads.isEmpty()) listOf("Project threads on the Mac: none.") else listOf("Project threads on the Mac:") + mac.threads.map { macThreadLine(it, mac.now) }
    return listOf(link) + threads
}

/** Someone who has told him something and now rests: [who] as the board calls them, how long [since], and whether they [asked]. */
private data class Spoke(val who: String, val since: Long, val asked: Boolean)

/** Whether [said] leaves him with a question: it ends in one, whatever Markdown or quote marks close it. */
private fun asks(said: String) = said.trimEnd { it.isWhitespace() || it in "*_`\"')" }.endsWith("?")

/**
 * The builder a plain hand-over goes to when it is not new work: the one builder that has finished and asked him
 * something he has not answered, of [boards], each builder's board. His words then answer it, in its own
 * conversation, without the front agent having to name it. Null with none asking, or with both, when it cannot
 * be told which he means; [pickBuilder] then gives his words to whichever is free. One that asks is idle, so the
 * words never wait behind work for it.
 */
fun askingBuilder(boards: Map<BuilderId, BuildBoard>): BuilderId? =
    boards.filterValues { !it.working && it.lastAnswer.isNotBlank() && asks(it.lastAnswer) }.keys.singleOrNull()

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
