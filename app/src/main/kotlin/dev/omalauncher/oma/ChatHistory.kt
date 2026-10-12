package dev.omalauncher.oma

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a task in the thread ended. [id] is saved: never rename. */
enum class TaskStatus(val id: String) {
    Running("running"), Done("done"), Stopped("stopped"), Failed("failed"),

    /** The app's process died while the task ran. */
    Interrupted("interrupted"),
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
 * [screen] only records that the screen was attached.
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
)

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
    private const val VERSION = 1

    /** A task cut to the limits: the newest steps and approvals, the start of the prompt, the end of the result. */
    fun bounded(task: ChatTask) = task.copy(
        agent = task.agent.take(60),
        prompt = task.prompt.take(MAX_PROMPT),
        images = task.images.take(MAX_IMAGES).map { it.take(300) },
        steps = task.steps.takeLast(MAX_STEPS).map { it.take(MAX_STEP) },
        approvals = task.approvals.takeLast(MAX_APPROVALS).map { it.copy(tool = it.tool.take(60), detail = it.detail.take(MAX_DETAIL)) },
        result = task.result.takeLast(MAX_RESULT),
    )

    /** The thread with [task] added, keeping the newest [MAX_TASKS]. */
    fun append(tasks: List<ChatTask>, task: ChatTask) = (tasks + bounded(task)).takeLast(MAX_TASKS)

    /** A step, unless it only repeats the one before. */
    fun withStep(task: ChatTask, step: String): ChatTask {
        val text = step.trim().replace(Regex("\\s+"), " ").take(MAX_STEP)
        return if (text.isEmpty() || task.steps.lastOrNull() == text) task else task.copy(steps = (task.steps + text).takeLast(MAX_STEPS))
    }

    /** What a restart leaves of a thread: nothing is running any more and nothing can still be answered. */
    fun afterRestart(tasks: List<ChatTask>) = tasks.map { task ->
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
                ))
            }.getOrNull()
        }.takeLast(MAX_TASKS)
    }
}

/** The thread on disk, in the app's private files. Read and written from the agent's thread and the screen. */
class ChatStore(private val file: File) {
    @Volatile var tasks: List<ChatTask> = emptyList(); private set

    /** Reads the thread a previous run left, as [ChatHistory.afterRestart] sees it. */
    @Synchronized fun load() {
        tasks = ChatHistory.afterRestart(runCatching { ChatHistory.decode(file.readText()) }.getOrDefault(emptyList()))
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

    private fun save() {
        runCatching {
            val next = File(file.parentFile, file.name + ".new")
            next.writeText(ChatHistory.encode(tasks))
            check(next.renameTo(file)) { "Could not replace ${file.name}" }
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
