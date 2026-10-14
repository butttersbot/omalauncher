package dev.omalauncher.oma

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a task in the thread ended. [id] is saved: never rename. */
enum class TaskStatus(val id: String) {
    Running("running"), Done("done"), Stopped("stopped"), Failed("failed"),

    /** The app's process died while the task ran. */
    Interrupted("interrupted"),

    /**
     * A follow-up sent while another task ran, waiting for it to finish; or a request sent while the front agent
     * was reading the one before, waiting to be read.
     */
    Queued("queued"),

    /** A follow-up that was never sent to the agent; the task's result says why. */
    Dropped("dropped"),
    ;

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Interrupted }
}

/** What became of an approval. [id] is saved: never rename. */
enum class ApprovalAnswer(val id: String, val label: String) {
    Waiting("waiting", "waiting for you"), Approved("approved", "approved once"), Denied("denied", "denied"),

    /** The agent took the request back. */
    Withdrawn("withdrawn", "withdrawn"),

    /** The task stopped or ended, or the app restarted, before an answer. */
    Unanswered("unanswered", "not answered"),

    /** Denied without asking: its id couldn't be shown safely, or too many were waiting. */
    Refused("refused", "refused"),
    ;

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Unanswered }
}

/** An approval in the thread. [key] ties a waiting one to its buttons while the app runs; it is never saved. */
data class ChatApproval(val tool: String, val detail: String, val answer: ApprovalAnswer, val key: String = "")

/**
 * One request in the Oma thread: what was asked, the steps the agent reported, the approvals and how they
 * were answered, and the result. [images] are the guest paths the app already keeps, never image bytes;
 * [screen] only records that the screen was attached. [agent] names who was asked: the build lane's agent,
 * [QUICK_AGENT] once the front agent has answered it itself, [UNDECIDED_AGENT] while that is still open, and
 * the receipt he is shown ("→ FF4 mod") for a request handed to a project thread on the Mac.
 */
data class ChatTask(
    /** When it was sent; also what identifies it in the store. */
    val time: Long,
    val agent: String,
    val prompt: String,
    val images: List<String> = emptyList(),
    val screen: Boolean = false,
    /** Starts a conversation rather than following up on the one above. */
    val newThread: Boolean = false,
    val steps: List<String> = emptyList(),
    val approvals: List<ChatApproval> = emptyList(),
    val result: String = "",
    val status: TaskStatus = TaskStatus.Running,
    /** He sent this request, which the front agent had answered, to the build lane himself. Once only. */
    val sentToBuild: Boolean = false,
    /** The id of the project thread on the Mac this request was handed to; empty for every other request. */
    val thread: String = "",
    /** That thread's name as it was when he asked, which is what the entry and its approval cards call it. */
    val threadName: String = "",
    /** He sent this straight to its thread from the thread's tab: the front agent was not asked and nothing was put ahead of his words. */
    val direct: Boolean = false,
    /** Who wrote the reply, as the chat names it ("Opus", "Haiku", "Codex"); empty while that is not known. See AgentNames.kt. */
    val answeredBy: String = "",
    /** Which of the phone's builders a request of the build lane's went to: empty for builder A, the letter of any other. */
    val builder: String = "",
    /**
     * Separate work for the project of [thread], which was busy: a new thread is made there and his words go to it.
     * Until it is made the entry stands under the busy thread's id, and once made it is that new thread's and this is false.
     * For a request of the build lane's it is the first word of a new chat waiting for a free builder, which starts
     * a fresh session for it; once a builder has begun on it, this is false.
     */
    val fresh: Boolean = false,
    /**
     * The id of the new chat this request was sent in or belongs to ([NewChat]); empty for every request of the
     * Oma stream and of a thread's own tab from before there were new chats.
     */
    val chat: String = "",
) {
    /** Answered by the front agent itself, beside the build task: never by it or in its queue. */
    val quick get() = agent == QUICK_AGENT

    /**
     * With the front agent, which has neither answered it nor handed it to the build lane yet: it is reading
     * it, or, while this is [TaskStatus.Queued], has not come to it.
     */
    val undecided get() = agent == UNDECIDED_AGENT

    /**
     * Handed to a thread on the Mac. The Mac does the work, so [TaskStatus.Queued] here is a request it has not
     * taken yet, and [TaskStatus.Dropped] one it never got, which he can send again.
     */
    val mac get() = thread.isNotEmpty()

    /** A request of the build lane's: sent to it, or waiting for it. */
    val build get() = !quick && !undecided && !mac
}

/** What [ChatTask.agent] says for a request the front agent answered, where a build task has its agent's name. Saved: never rename. */
const val QUICK_AGENT = "quick"

/**
 * What [ChatTask.agent] says while the front agent decides. Saved, so a restart finds the request in the
 * thread as interrupted; nothing else remembers it, so it is not sent later behind his back. Never rename.
 */
const val UNDECIDED_AGENT = "oma"

/** What a request reads that was to go to a new thread on the Mac when the app closed before the thread was made. */
const val FRESH_UNMADE = "Oma closed before the new thread was made. Nothing was sent."

/** The limits of the saved thread and its JSON form. */
object ChatHistory {
    const val MAX_TASKS = 50
    const val MAX_PROMPT = 4_000
    const val MAX_STEPS = 40
    const val MAX_STEP = 160
    const val MAX_APPROVALS = 20
    const val MAX_DETAIL = 600
    const val MAX_RESULT = 8_000
    const val MAX_IMAGES = 4
    const val MAX_THREAD = 128
    const val MAX_BUILDER = 8
    const val MAX_CHAT = 32

    /** Several times the largest file [encode] writes; anything bigger isn't ours and isn't read. */
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    private const val VERSION = 1

    /** A task cut to the limits: the newest steps and approvals, the start of the prompt, the end of the result. */
    fun bounded(task: ChatTask) = task.copy(
        agent = task.agent.take(60),
        prompt = task.prompt.take(MAX_PROMPT),
        images = task.images.take(MAX_IMAGES).map { it.take(300) },
        steps = task.steps.takeLast(MAX_STEPS).map { it.take(MAX_STEP) },
        approvals = task.approvals.takeLast(MAX_APPROVALS).map { it.copy(tool = it.tool.take(60), detail = it.detail.take(MAX_DETAIL)) },
        result = task.result.takeLast(MAX_RESULT),
        thread = task.thread.take(MAX_THREAD),
        threadName = task.threadName.take(60),
        answeredBy = task.answeredBy.take(40),
        builder = task.builder.take(MAX_BUILDER),
        chat = task.chat.take(MAX_CHAT),
    )

    /** The thread with [task] added, keeping the newest [MAX_TASKS]. */
    fun append(tasks: List<ChatTask>, task: ChatTask) = (tasks + bounded(task)).takeLast(MAX_TASKS)

    /** A step, unless it only repeats the one before. */
    fun withStep(task: ChatTask, step: String): ChatTask {
        val text = step.trim().replace(Regex("\\s+"), " ").take(MAX_STEP)
        return if (text.isEmpty() || task.steps.lastOrNull() == text) task else task.copy(steps = (task.steps + text).takeLast(MAX_STEPS))
    }

    /** When a task sent at [now] is filed: after every task already in the thread, since the time is what identifies it. */
    fun stamp(tasks: List<ChatTask>, now: Long) = maxOf(now, (tasks.maxOfOrNull { it.time } ?: 0L) + 1)

    /** A waiting follow-up that will never be sent, with [reason] where its answer would have been. */
    fun dropped(task: ChatTask, reason: String) = if (task.status == TaskStatus.Queued) task.copy(status = TaskStatus.Dropped, result = reason) else task

    /**
     * [task] marked as sent to the build lane by him; null when that can't be done: it isn't an answer of the
     * front agent's, it is still being answered, or he has sent it already.
     */
    fun sentToBuild(task: ChatTask): ChatTask? = if (task.quick && !task.sentToBuild && task.status != TaskStatus.Running) task.copy(sentToBuild = true) else null

    /**
     * The thread without the request sent at [time], while the front agent still has it: one it hands over
     * is filed by the build lane as its own task, and this is how the two stay one entry.
     */
    fun handedOver(tasks: List<ChatTask>, time: Long) = tasks.filterNot { it.time == time && (it.quick || it.undecided) }

    /**
     * What a restart leaves of a thread: nothing is running any more and nothing can still be answered. A
     * follow-up that was waiting is not sent later behind his back; it says it was dropped. A request handed to
     * the Mac is the exception: the Mac goes on with it whatever becomes of this app, so it loads as it was
     * saved and is followed again from its thread's last event. Only its waiting approvals go, to be drawn
     * again from what the Mac says is still waiting. A request that waited for the front agent to read it was
     * never anyone's follow-up: it loads as interrupted with his words kept, as one it was reading does. One that
     * waited for a new thread to be made on the Mac was sent nowhere: it says so and can be sent again.
     */
    fun afterRestart(tasks: List<ChatTask>) = tasks.map { saved ->
        // Making a new thread for it ended with the process, before anything was sent: his to send again.
        if (saved.mac && saved.fresh && saved.status == TaskStatus.Queued) return@map saved.copy(status = TaskStatus.Dropped, result = FRESH_UNMADE)
        if (saved.mac) return@map saved.copy(approvals = saved.approvals.filter { it.answer != ApprovalAnswer.Waiting }.map { it.copy(key = "") })
        if (saved.undecided && saved.status == TaskStatus.Queued) return@map saved.copy(status = TaskStatus.Interrupted)
        val task = dropped(saved, FollowUpQueue.RESTARTED)
        task.copy(
            status = if (task.status == TaskStatus.Running) TaskStatus.Interrupted else task.status,
            approvals = task.approvals.map { if (it.answer == ApprovalAnswer.Waiting) it.copy(answer = ApprovalAnswer.Unanswered, key = "") else it.copy(key = "") },
        )
    }

    fun encode(tasks: List<ChatTask>): String = JSONObject().put("version", VERSION).put("tasks", JSONArray(tasks.takeLast(MAX_TASKS).map(::bounded).map { task ->
        JSONObject().put("time", task.time).put("agent", task.agent).put("prompt", task.prompt)
            .put("images", JSONArray(task.images)).put("screen", task.screen).put("newThread", task.newThread)
            .put("steps", JSONArray(task.steps)).put("result", task.result).put("status", task.status.id)
            .put("approvals", JSONArray(task.approvals.map { JSONObject().put("tool", it.tool).put("detail", it.detail).put("answer", it.answer.id) }))
            // Written only where it is set, so every other task keeps the shape it has always had.
            .apply { if (task.sentToBuild) put("sentToBuild", true) }
            .apply { if (task.mac) put("thread", task.thread).put("threadName", task.threadName) }
            .apply { if (task.direct) put("direct", true) }
            .apply { if (task.answeredBy.isNotEmpty()) put("answeredBy", task.answeredBy) }
            .apply { if (task.builder.isNotEmpty()) put("builder", task.builder) }
            .apply { if (task.fresh) put("fresh", true) }
            .apply { if (task.chat.isNotEmpty()) put("chat", task.chat) }
    })).toString()

    /** The saved thread; an unreadable file is an empty thread, and an unreadable task is left out. */
    fun decode(text: String): List<ChatTask> {
        val saved = runCatching { JSONObject(text).getJSONArray("tasks") }.getOrNull() ?: return emptyList()
        fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf(String::isNotEmpty) }
        return (0 until saved.length()).mapNotNull { index ->
            runCatching {
                val task = saved.getJSONObject(index)
                val approvals = task.optJSONArray("approvals")
                bounded(ChatTask(
                    time = task.getLong("time"), agent = task.optString("agent"), prompt = task.getString("prompt"),
                    images = strings(task.optJSONArray("images")), screen = task.optBoolean("screen"), newThread = task.optBoolean("newThread"),
                    steps = strings(task.optJSONArray("steps")),
                    approvals = (0 until (approvals?.length() ?: 0)).mapNotNull { approvals?.optJSONObject(it) }
                        .map { ChatApproval(it.optString("tool"), it.optString("detail"), ApprovalAnswer.of(it.optString("answer"))) },
                    result = task.optString("result"), status = TaskStatus.of(task.optString("status")),
                    sentToBuild = task.optBoolean("sentToBuild"),
                    thread = task.optString("thread"), threadName = task.optString("threadName"),
                    direct = task.optBoolean("direct"),
                    answeredBy = task.optString("answeredBy"),
                    builder = task.optString("builder"),
                    fresh = task.optBoolean("fresh"),
                    chat = task.optString("chat"),
                ))
            }.getOrNull()
        }.takeLast(MAX_TASKS)
    }
}

/** The thread on disk, in the app's private files. Read and written from the agent's thread and the screen. */
class ChatStore(private val file: File, private val maxBytes: Long = ChatHistory.MAX_FILE_BYTES) {
    @Volatile var tasks: List<ChatTask> = emptyList(); private set

    /** Reads the thread a previous run left, as [ChatHistory.afterRestart] sees it. */
    @Synchronized fun load() {
        val saved = runCatching { if (file.length() <= maxBytes)ChatHistory.decode(file.readText()) else emptyList() }
        tasks = ChatHistory.afterRestart(saved.getOrDefault(emptyList()))
    }

    /** A new task, saved at once so its prompt survives the process. */
    @Synchronized fun begin(task: ChatTask) {
        tasks = ChatHistory.append(tasks, task)
        save()
    }

    /** Changes the task sent at [time]; written to disk when [save], which steps don't need. */
    @Synchronized fun update(time: Long, save: Boolean = false, change: (ChatTask) -> ChatTask) {
        tasks = tasks.map { if (it.time == time) ChatHistory.bounded(change(it)) else it }
        if (save) save()
    }

    /**
     * Changes the thread as a whole and saves it: for what a project thread on the Mac says, which can end one
     * request and begin the next in a single step.
     */
    @Synchronized fun revise(change: (List<ChatTask>) -> List<ChatTask>) {
        val next = change(tasks).map(ChatHistory::bounded)
        if (next == tasks) return
        tasks = next
        save()
    }

    /** Takes out the request sent at [time] that the front agent handed over; see [ChatHistory.handedOver]. */
    @Synchronized fun handedOver(time: Long) {
        tasks = ChatHistory.handedOver(tasks, time)
        save()
    }

    private fun save() {
        runCatching {
            val next = File(file.parentFile, file.name + ".new")
            next.writeText(ChatHistory.encode(tasks))
            check(next.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }
}

/** The file names among [drafts] that no saved task refers to: copied in for the chat, never sent, and safe to delete. */
fun unsentDrafts(drafts: Collection<String>, saved: List<ChatTask>): List<String> {
    val sent = saved.flatMap { it.images }.map { it.substringAfterLast('/') }.toSet()
    return drafts.filter { ChatDrafts.NAME.matches(it) && it !in sent }.distinct()
}

/**
 * The images the chat copied into the guest's attachments folder and hasn't sent. The list is on disk so that
 * what a killed process left unsent is deleted at the next start; images of sent tasks, and files the list
 * never named, are not touched.
 */
class ChatDrafts(private val list: File, private val attachments: File) {
    private fun read(): Set<String> = runCatching { list.readLines().filter(NAME::matches).take(200).toSet() }.getOrDefault(emptySet())

    private fun write(names: Set<String>) {
        runCatching { if (names.isEmpty()) list.delete() else list.writeText(names.joinToString("\n")) }
    }

    @Synchronized fun add(files: Collection<File>) = write(read() + files.map { it.name })

    /** Sent or removed: no longer the chat's to clean up. */
    @Synchronized fun remove(files: Collection<File>) = write(read() - files.map { it.name }.toSet())

    /** At process start, before the chat can have added anything: whatever is listed was never sent. */
    @Synchronized fun sweep(saved: List<ChatTask>) {
        val listed = read()
        val unsent = unsentDrafts(listed, saved)
        for (name in unsent) File(attachments, name).delete()
        // Only what was listed when the sweep began comes off; an image attached meanwhile stays listed.
        write(read() - listed)
    }

    companion object {
        /** The names importPromptImage gives its copies; nothing else is ever listed or deleted. */
        val NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|gif)")

        @Volatile private var shared: ChatDrafts? = null

        fun of(context: android.content.Context): ChatDrafts = shared ?: synchronized(this) {
            shared ?: ChatDrafts(File(context.filesDir, "oma-chat-drafts.txt"), File(context.filesDir, "attachments")).also { shared = it }
        }
    }
}

/** The one thread of the Oma app. */
object ChatLog {
    @Volatile private var store: ChatStore? = null

    fun of(context: android.content.Context): ChatStore = store ?: synchronized(this) {
        store ?: ChatStore(File(context.filesDir, "oma-chat.json")).also { it.load(); store = it }
    }
}
