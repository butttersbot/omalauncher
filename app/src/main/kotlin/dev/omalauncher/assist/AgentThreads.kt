package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.Markdown
import dev.omalauncher.oma.NEW_CHAT_NAME
import dev.omalauncher.oma.NewChat
import dev.omalauncher.oma.TabBook
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

    /** Its last request failed, never arrived, or the runner restarted under it before it was answered. */
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
    /** When the turn it is at began, in milliseconds, so a tile can count it up; null while it rests, or when the phone did not see it begin. */
    val since: Long? = null,
    /** Whether it runs on the Mac: a Mac thread, or a new chat, which is the Mac's even before it has a thread there. */
    val mac: Boolean = macThreadId(key) != null,
    /** A new chat with no thread on the Mac yet: nothing sent, its thread being made, or its words waiting for the Mac. */
    val unplaced: Boolean = false,
)

/** One of the phone's builders as it stands: whether a task runs, its label, what waits for his approval, and when the task began ([since], 0 if none). */
data class BuilderNow(val working: Boolean = false, val label: String = "", val asks: List<ThreadAsk> = emptyList(), val since: Long = 0L)

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
    val since = if (builder.working) builder.since.takeIf { it > 0 } ?: running?.time else null
    return AgentThread(which.thread, name, state, doing, label, entries.maxOfOrNull { it.time }, since = since)
}

/**
 * The phone's builders as threads: builder A always, as "Phone" while it is the only one that ever worked, and
 * builder B beside it once [builderBShown], when the two are "Phone A" and "Phone B". [builders] is how each stands.
 * A builder's thread is the entries of no new chat: one an earlier version gave a new chat stays in that chat.
 */
fun phoneThreads(tasks: List<ChatTask>, builders: Map<BuilderId, BuilderNow>): List<AgentThread> {
    val own = tasks.filter { it.chat.isEmpty() }
    fun thread(id: BuilderId, name: String) = phoneThread(own, builders[id] ?: BuilderNow(), id, name)
    val b = builders[BuilderId.B] ?: BuilderNow()
    if (!builderBShown(own, b.working || b.asks.isNotEmpty())) return listOf(thread(BuilderId.A, PHONE_THREAD_NAME))
    return listOf(thread(BuilderId.A, PHONE_THREAD_A_NAME), thread(BuilderId.B, PHONE_THREAD_B_NAME))
}

/** What builder A's tab and tile are called once builder B is shown beside it. */
const val PHONE_THREAD_A_NAME = "Phone A"

const val NEEDS_OK = "needs your OK"

/** The label of a thread that answered and left work running on the Mac (its `background`). */
const val MAC_BACKGROUND = "running on the Mac"

/**
 * What a resting thread's tile and tab say of the work it left running on the Mac, in the agent's own words
 * ("running on the Mac: copy the games"); null when nothing is left running.
 */
fun macBackgroundLine(background: List<String>): String? =
    background.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        ?.let { (MAC_BACKGROUND + ": " + it.joinToString("; ")).take(TILE_DOING_CHARS) }
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
        thread?.status == MacThreadStatus.Interrupted -> if (macLastAnswered(last, thread.tail)) ThreadState.Idle else ThreadState.Attention
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
        // An answered request the restart cut keeps the restart's line after its answer: a resting tile shows the answer.
        ThreadState.Idle -> macBackgroundLine(thread?.background.orEmpty()) ?: endOf(thread?.tail?.takeIf { it.isNotBlank() } ?: last?.result.orEmpty().trim().removeSuffix(MAC_INTERRUPTED))
    }
    val label = when (state) {
        ThreadState.NeedsOk -> NEEDS_OK
        ThreadState.Working -> "working"
        ThreadState.Queued -> "$waits queued"
        ThreadState.Attention -> NEEDS_ATTENTION
        ThreadState.Idle -> if (thread?.background.orEmpty().isNotEmpty()) MAC_BACKGROUND else restLabel(last, worked = thread?.lastWorked != null)
    }
    val worked = listOfNotNull(thread?.lastWorked?.let { it * 1000 }, entries.maxOfOrNull { it.time }).maxOrNull()
    // The request the Mac is at was sent from here; one he typed on the Mac itself has no start the phone saw.
    val since = if (working) running?.time else null
    return AgentThread(macThreadKey(id), name, state, doing, label, worked, reachable, since)
}

/**
 * Whether his newest request to a Mac thread the runner calls interrupted was answered, so that nothing of his
 * is left unfinished and the thread can rest like any other. A restart that ends a turn after its work is done,
 * as when the turn's last step restarts the runner itself (Boss, 2026-10-14), is no reason to call him over.
 * [last] is his newest entry there that is not waiting; [tail] is what the Mac says the agent said last.
 * Words he sent from elsewhere are his newest request when the thread names them after [last]: only words of
 * the agent after them answer them. Otherwise [last] is answered once it ended with words from the agent, or
 * failed only because of the restart after some; one still running, dropped, cut by the phone's own restart or
 * failed for any other reason is not. With no entry here, the Mac's [tail] is all there is to go by.
 */
fun macLastAnswered(last: ChatTask?, tail: String): Boolean {
    if (last == null) return tail.isNotBlank()
    val elsewhere = last.later.indexOfLast { !it.words && it.text.startsWith(MAC_SENT_ELSEWHERE) }
    if (elsewhere >= 0) return last.later.drop(elsewhere + 1).any { it.words && it.text.isNotBlank() }
    val result = last.result.trim()
    val said = result.removeSuffix(MAC_INTERRUPTED).isNotBlank() || last.later.any { it.words && it.text.isNotBlank() }
    return when (last.status) {
        TaskStatus.Done, TaskStatus.Stopped -> said
        TaskStatus.Failed -> result.endsWith(MAC_INTERRUPTED) && said
        else -> false
    }
}

/**
 * A new chat as a thread, called [name]. Bound to a thread on the Mac, it is that thread. Unbound, its own
 * entries say how it stands: the front agent reading it or a new thread being made for it is work, a first
 * message that failed or that the Mac was out of reach for needs him, and a chat with nothing in it is "new".
 * Bound or not it is the Mac's (Boss, 2026-10-13: "The plus button should always open up a Mac session"), and
 * unbound it is as reachable as the Mac was at the last try.
 */
fun chatThread(
    chat: NewChat,
    name: String,
    tasks: List<ChatTask>,
    mac: MacView,
    asks: Map<String, List<ThreadAsk>> = emptyMap(),
): AgentThread {
    if (chat.mac.isNotEmpty()) {
        return macThreadNow(mac.threads.firstOrNull { it.id == chat.mac }, chat.mac, tasks, asks[chat.mac].orEmpty(), mac.reachable).copy(key = chat.key, name = name)
    }
    val entries = tasks.filter { it.chat == chat.id }
    val running = entries.lastOrNull { it.status == TaskStatus.Running }
    val waiting = entries.lastOrNull { it.status == TaskStatus.Queued }
    val last = entries.lastOrNull { it.status != TaskStatus.Queued }
    val state = when {
        running != null || waiting != null -> ThreadState.Working
        last?.status == TaskStatus.Failed || last?.status == TaskStatus.Dropped -> ThreadState.Attention
        else -> ThreadState.Idle
    }
    val doing = when (state) {
        ThreadState.Working -> (running?.steps?.lastOrNull() ?: if (waiting?.fresh == true) MAC_MAKING else "Reading this").take(TILE_DOING_CHARS)
        else -> endOf(last?.result.orEmpty())
    }
    val label = when (state) {
        ThreadState.Working -> "working"
        ThreadState.Attention -> NEEDS_ATTENTION
        else -> if (entries.isEmpty()) "new" else restLabel(last, worked = false)
    }
    // Last used when he started it, renamed or restored it, or last wrote in it: an empty chat lives its hours as any
    // thread does rather than leaving the strip as soon as another tab is open, and the book puts it away after them (retired).
    val used = listOfNotNull(chat.made.takeIf { it > 0L }, chat.used.takeIf { it > 0L }, entries.maxOfOrNull { it.time }).maxOrNull()
    return AgentThread(chat.key, name, state, doing, label, used, mac.reachable, since = running?.time, mac = true, unplaced = true)
}

/**
 * Every thread there is to show, in the order the chat's tabs stand in: the phone's builders ([phoneThreads],
 * [builder] being A and [second] B), then the Mac's threads, the one that worked last first, then the new
 * [chats] he started, oldest first, so the newest stands beside ＋. A Mac thread is one the Mac lists, or one a
 * request in the Oma thread was handed to, so its entries can be opened before the Mac has answered and after
 * it has gone; one made for a new chat is shown as that chat. [asks] are the Mac's waiting requests by thread
 * id, and [names] the names tabs are shown by, by key.
 */
fun agentThreads(
    tasks: List<ChatTask>,
    builder: BuilderNow,
    mac: MacView,
    asks: Map<String, List<ThreadAsk>> = emptyMap(),
    second: BuilderNow = BuilderNow(),
    chats: List<NewChat> = emptyList(),
    names: Map<String, String> = emptyMap(),
): List<AgentThread> {
    val listed = mac.threads.associateBy { it.id }
    val bound = chats.map { it.mac }.filter { it.isNotEmpty() }.toSet()
    val ids = (mac.threads.map { it.id } + tasks.filter { it.mac && it.chat.isEmpty() }.map { it.thread }).distinct() - bound
    val threads = ids.map { id -> macThreadNow(listed[id], id, tasks.filter { it.chat.isEmpty() }, asks[id].orEmpty(), mac.reachable) }
    val builders = mapOf(BuilderId.A to builder, BuilderId.B to second)
    val chatThreads = chats.map { chatThread(it, names[it.key] ?: NEW_CHAT_NAME, tasks, mac, asks) }
    val all = phoneThreads(tasks, builders) + threads.sortedWith(compareByDescending<AgentThread> { it.worked ?: Long.MIN_VALUE }.thenBy { it.name }.thenBy { it.key }) + chatThreads
    return all.map { thread -> names[thread.key]?.let { thread.copy(name = it) } ?: thread }
}

/**
 * The threads as they stand this moment, from what the builder, the Oma thread and [MacThreads] already
 * publish. It asks the Mac nothing, waits for nothing and takes no lock, so the chat and home can read it as
 * often as they draw; they restore [AgentTasks] and [MacThreads] once, before the first read.
 */
fun agentThreadsNow(context: Context): List<AgentThread> {
    fun now(builder: Builder) = BuilderNow(builder.working, builder.label, builder.pending.map { ThreadAsk(it.approval.tool, askTarget(it.approval.input.toString())) }, builder.startedAt)
    val asks = MacThreads.pending.groupBy({ it.threadId }) { ThreadAsk(it.request.tool, askTarget(it.request.input)) }
    val book = TabBook.of(context)
    return agentThreads(
        ChatLog.of(context).tasks, now(AgentTasks.of(BuilderId.A)), MacThreads.view, asks, now(AgentTasks.of(BuilderId.B)),
        book.chats, book.names,
    )
}
