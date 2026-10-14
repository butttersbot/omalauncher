package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.Markdown
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject

/*
 * Every agent Boss can see at work, as one list (Boss, 2026-10-13: "see the threads and drill into one"): the
 * phone's builder and each project thread on the Mac, each with a state, a few words on what it is doing and
 * when it last worked. The Oma chat makes its tabs and header dots from this list and home makes its thread
 * tiles from it, so a dot and a tile can never disagree. Everything that decides is a plain function, unit
 * tested; agentThreadsNow at the end only reads what the builder, the chat and MacThreads already publish.
 * Nothing here sends, stops or answers anything, and of an approval it knows the tool and its target alone.
 */

/** The key of the phone's builder A among the threads: what its tile and the chat's intent call it. Saved: never rename. */
const val PHONE_THREAD = "phone"

/** What the builder's tile and tab are called. */
const val PHONE_THREAD_NAME = "Phone"

/** Ahead of a Mac thread's id in its key, so no id the Mac gives can be taken for the phone's builder. Saved: never rename. */
const val MAC_THREAD_PREFIX = "mac:"

/** The key of the thread on the Mac with the id [id]. */
fun macThreadKey(id: String) = MAC_THREAD_PREFIX + id

/** The Mac's id of the thread [key] names; null for the phone's builder and for anything that is no thread's key. */
fun macThreadId(key: String): String? = key.takeIf { it.startsWith(MAC_THREAD_PREFIX) }?.removePrefix(MAC_THREAD_PREFIX)?.takeIf { it.isNotEmpty() }

/** How a thread stands, in the order its dot is coloured by. Only [Idle] lets a tile grow old and leave. */
enum class ThreadState {
    Working,

    /** Working, with more of his requests waiting their turn behind the task. */
    Queued,

    /** A step waits for his approval. */
    NeedsOk,

    /** Its last request failed, never arrived, or the runner restarted under it. */
    Attention,
    Idle,
    ;

    val active get() = this != Idle
}

/** A waiting approval as a tile or a dot may know it: which tool, and what it is pointed at. Never its input, its nonce or its id. */
data class ThreadAsk(val tool: String, val target: String)

/** How many of a path's last parts name an approval's target: enough to know the file, not where the project lives. */
const val ASK_PATH_PARTS = 3

/**
 * What a tool's [input] is pointed at, in a few words: the end of the path of the file it would touch, the
 * host of an address, or the program a shell command starts with. A command's arguments are never shown:
 * they are the approval's content, which only its card in the chat lays out.
 */
fun askTarget(input: String): String {
    val json = runCatching { JSONObject(input) }.getOrNull() ?: return ""
    for (key in listOf("file_path", "path", "notebook_path")) {
        val path = json.optString(key).trim()
        if (path.isNotEmpty()) return path.split('/').filter { it.isNotEmpty() }.takeLast(ASK_PATH_PARTS).joinToString("/").take(ASK_TARGET_CHARS)
    }
    val url = json.optString("url").trim()
    if (url.isNotEmpty()) return runCatching { java.net.URI(url).host }.getOrNull().orEmpty().take(ASK_TARGET_CHARS)
    val program = json.optString("command").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    return program.substringAfterLast('/').take(ASK_TARGET_CHARS)
}

const val ASK_TARGET_CHARS = 80

/** The line a tile shows for a waiting approval: "Wants to write stickers/frog-pack/manifest.json". */
fun wantsLine(ask: ThreadAsk): String {
    val verb = when (ask.tool) {
        "Bash" -> "run"
        "Write" -> "write"
        "Edit", "MultiEdit", "NotebookEdit" -> "edit"
        "Read" -> "read"
        "WebFetch" -> "fetch"
        else -> null
    }
    return if (verb == null || ask.target.isBlank()) "Wants to use ${ask.tool.ifBlank { "a tool" }}".take(TILE_DOING_CHARS) else "Wants to $verb ${ask.target}".take(TILE_DOING_CHARS)
}

/** As much as three lines of a tile hold. */
const val TILE_DOING_CHARS = 140

/**
 * The end of [text] as plain words on one line, for a tile: an answer's last sentences say how it came out,
 * and are what the Mac keeps of one. Cut at a word, with a mark that there was more before it.
 */
fun endOf(text: String, chars: Int = TILE_DOING_CHARS): String {
    val plain = Markdown.plain(text.trim()).replace(Regex("\\s+"), " ").trim()
    if (plain.length <= chars) return plain
    val tail = plain.takeLast(chars)
    return "…" + tail.substringAfter(' ', tail).trim()
}

private fun firstLine(text: String) = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** One agent at work as he is shown it: a tab and a dot in the chat, and a tile on home. */
data class AgentThread(
    /** [PHONE_THREAD] or [PHONE_THREAD_B], or [macThreadKey] of a thread on the Mac. */
    val key: String,
    val name: String,
    val state: ThreadState,
    /** What it is doing, or the end of what it last said; up to three lines of a tile. */
    val doing: String,
    /** The state in words, beside its dot: "working", "1 queued", "needs your OK", "needs attention", "done". */
    val label: String,
    /** When it last worked or was last sent something, as far as the phone knows, in milliseconds; null if never. */
    val worked: Long? = null,
    /** False for a thread on a Mac the phone could not reach at its last try: what is shown is what was last heard. */
    val reachable: Boolean = true,
) {
    val mac get() = macThreadId(key) != null
}

/** One of the phone's builders as it stands: whether a task runs, its label, and what waits for his approval. */
data class BuilderNow(val working: Boolean = false, val label: String = "", val asks: List<ThreadAsk> = emptyList())

private fun queuedLine(running: String, waiting: List<ChatTask>, waits: Int): String {
    val next = waiting.firstOrNull()?.let { firstLine(it.prompt) }.orEmpty()
    return (if (next.isEmpty()) "$running · $waits waiting" else "$running · then: “$next”").take(TILE_DOING_CHARS)
}

private fun restLabel(last: ChatTask?, worked: Boolean) = when {
    last?.status == TaskStatus.Stopped -> "stopped"
    last?.status == TaskStatus.Interrupted -> "interrupted"
    last != null || worked -> "done"
    else -> "idle"
}

/**
 * One of the phone's builders as a thread, from its own entries in the Oma thread ([ChatTask.build] and
 * [ChatTask.builderId]) and from [builder]. Working with requests waiting behind the task reads as queued; a
 * waiting approval comes before either; a last request that failed needs him, until the next one is sent.
 * [which] is the builder, and [name] what its tab and tile are called.
 */
fun phoneThread(tasks: List<ChatTask>, builder: BuilderNow, which: BuilderId = BuilderId.A, name: String = PHONE_THREAD_NAME): AgentThread {
    val entries = tasks.filter { it.build && it.builderId == which }
    val running = entries.lastOrNull { it.status == TaskStatus.Running }
    val waiting = entries.filter { it.status == TaskStatus.Queued }
    val last = entries.lastOrNull { it.status != TaskStatus.Queued }
    val state = when {
        builder.asks.isNotEmpty() -> ThreadState.NeedsOk
        builder.working && waiting.isNotEmpty() -> ThreadState.Queued
        builder.working -> ThreadState.Working
        last?.status == TaskStatus.Failed -> ThreadState.Attention
        else -> ThreadState.Idle
    }
    val step = running?.steps?.lastOrNull() ?: builder.label.ifBlank { "Working" }
    val doing = when (state) {
        ThreadState.NeedsOk -> wantsLine(builder.asks.first())
        ThreadState.Working -> step.take(TILE_DOING_CHARS)
        ThreadState.Queued -> queuedLine(step, waiting, waiting.size)
        ThreadState.Attention, ThreadState.Idle -> endOf(last?.result.orEmpty())
    }
    val label = when (state) {
        ThreadState.NeedsOk -> NEEDS_OK
        ThreadState.Working -> "working"
        ThreadState.Queued -> "${waiting.size} queued"
        ThreadState.Attention -> NEEDS_ATTENTION
        ThreadState.Idle -> restLabel(last, worked = false)
    }
    return AgentThread(which.thread, name, state, doing, label, entries.maxOfOrNull { it.time })
}

/**
 * The phone's builders as threads: builder A always, as "Phone" while it is the only one that ever worked, and
 * builder B beside it once [builderBShown], when the two are "Phone A" and "Phone B". [builders] is how each stands.
 */
fun phoneThreads(tasks: List<ChatTask>, builders: Map<BuilderId, BuilderNow>): List<AgentThread> {
    val a = builders[BuilderId.A] ?: BuilderNow()
    val b = builders[BuilderId.B] ?: BuilderNow()
    if (!builderBShown(tasks, b.working || b.asks.isNotEmpty())) return listOf(phoneThread(tasks, a, BuilderId.A, PHONE_THREAD_NAME))
    return listOf(phoneThread(tasks, a, BuilderId.A, PHONE_THREAD_A_NAME), phoneThread(tasks, b, BuilderId.B, PHONE_THREAD_B_NAME))
}

/** What builder A's tab and tile are called once builder B is shown beside it. */
const val PHONE_THREAD_A_NAME = "Phone A"

const val NEEDS_OK = "needs your OK"
const val NEEDS_ATTENTION = "needs attention"

/**
 * The thread [id] on the Mac as he is shown it. [thread] is what the Mac last said of it, null for a thread
 * the phone only knows from the requests it sent there; [tasks] is the Oma thread, whose entries for [id] say
 * what the phone itself sent and what became of it; [asks] are its requests the phone holds as waiting.
 * The Mac's word on whether it works comes first, and the entries fill in what it works on. With no word from
 * the Mac at all, the entries alone say how it stands.
 */
fun macThreadNow(thread: MacThread?, id: String, tasks: List<ChatTask>, asks: List<ThreadAsk>, reachable: Boolean): AgentThread {
    // One waiting for a new thread only stands under this one's id: it is not queued here.
    val entries = tasks.filter { it.thread == id && !it.fresh }
    val running = entries.lastOrNull { it.status == TaskStatus.Running }
    val waiting = entries.filter { it.status == TaskStatus.Queued }
    val last = entries.lastOrNull { it.status != TaskStatus.Queued }
    val name = thread?.let { macName(it.name) } ?: entries.lastOrNull()?.threadName?.takeIf { it.isNotEmpty() } ?: macName("")
    val wants = asks.ifEmpty { thread?.pending.orEmpty().map { ThreadAsk(it.tool, askTarget(it.input)) } }
    val working = if (thread != null) !macResting(thread.status) && thread.status != MacThreadStatus.Unknown else running != null
    val waits = maxOf(thread?.waitingTurns ?: 0, waiting.size)
    val state = when {
        wants.isNotEmpty() || thread?.status == MacThreadStatus.Waiting -> ThreadState.NeedsOk
        waits > 0 -> ThreadState.Queued
        working -> ThreadState.Working
        thread?.status == MacThreadStatus.Interrupted -> ThreadState.Attention
        last?.status == TaskStatus.Failed || last?.status == TaskStatus.Dropped -> ThreadState.Attention
        else -> ThreadState.Idle
    }
    val asked = thread?.let { macOwnSummary(it, tasks).summary }?.takeIf { it.isNotBlank() } ?: running?.let { firstLine(it.prompt) }.orEmpty()
    val step = running?.steps?.lastOrNull() ?: asked.ifBlank { if (working) "Working" else "Sending to the Mac" }
    val doing = when (state) {
        ThreadState.NeedsOk -> wants.firstOrNull()?.let(::wantsLine) ?: "Waiting for your OK"
        ThreadState.Working -> step.take(TILE_DOING_CHARS)
        ThreadState.Queued -> queuedLine(step, waiting, waits)
        ThreadState.Attention -> endOf(last?.result?.takeIf { it.isNotBlank() } ?: if (thread?.status == MacThreadStatus.Interrupted) MAC_INTERRUPTED else "")
        ThreadState.Idle -> endOf(thread?.tail?.takeIf { it.isNotBlank() } ?: last?.result.orEmpty())
    }
    val label = when (state) {
        ThreadState.NeedsOk -> NEEDS_OK
        ThreadState.Working -> "working"
        ThreadState.Queued -> "$waits queued"
        ThreadState.Attention -> NEEDS_ATTENTION
        ThreadState.Idle -> restLabel(last, worked = thread?.lastWorked != null)
    }
    val worked = listOfNotNull(thread?.lastWorked?.let { it * 1000 }, entries.maxOfOrNull { it.time }).maxOrNull()
    return AgentThread(macThreadKey(id), name, state, doing, label, worked, reachable)
}

/**
 * Every thread there is to show, in the order the chat's tabs stand in: the phone's builders ([phoneThreads],
 * [builder] being A and [second] B), then the Mac's threads, the one that worked last first. A Mac thread is one the Mac lists, or one a request in the Oma
 * thread was handed to, so its entries can be opened before the Mac has answered and after it has gone.
 * [asks] are the Mac's waiting requests by thread id.
 */
fun agentThreads(tasks: List<ChatTask>, builder: BuilderNow, mac: MacView, asks: Map<String, List<ThreadAsk>> = emptyMap(), second: BuilderNow = BuilderNow()): List<AgentThread> {
    val listed = mac.threads.associateBy { it.id }
    val ids = (mac.threads.map { it.id } + tasks.filter { it.mac }.map { it.thread }).distinct()
    val threads = ids.map { id -> macThreadNow(listed[id], id, tasks, asks[id].orEmpty(), mac.reachable) }
    return phoneThreads(tasks, mapOf(BuilderId.A to builder, BuilderId.B to second)) + threads.sortedWith(compareByDescending<AgentThread> { it.worked ?: Long.MIN_VALUE }.thenBy { it.name }.thenBy { it.key })
}

/**
 * The threads as they stand this moment, from what the builder, the Oma thread and [MacThreads] already
 * publish. It asks the Mac nothing, waits for nothing and takes no lock, so the chat and home can read it as
 * often as they draw; they restore [AgentTasks] and [MacThreads] once, before the first read.
 */
fun agentThreadsNow(context: Context): List<AgentThread> {
    fun now(builder: Builder) = BuilderNow(builder.working, builder.label, builder.pending.map { ThreadAsk(it.approval.tool, askTarget(it.approval.input.toString())) })
    val asks = MacThreads.pending.groupBy({ it.threadId }) { ThreadAsk(it.request.tool, askTarget(it.request.input)) }
    return agentThreads(ChatLog.of(context).tasks, now(AgentTasks.of(BuilderId.A)), MacThreads.view, asks, now(AgentTasks.of(BuilderId.B)))
}
