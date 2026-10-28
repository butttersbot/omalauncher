package dev.omalauncher.oma

import org.json.JSONArray
import org.json.JSONObject

/** Runner events live in the task log's own file, with its save/lock, rather than a second history. */
class PhoneTaskEvents(saved: JSONObject? = null) {
    var revision = 0L; private set
    private var bytes = 0L
    private val rows = linkedMapOf<String, MutableList<JSONObject>>()
    private val last = linkedMapOf<String, Long>()
    init {
        saved?.keys()?.forEach { id ->
            val session = saved.optJSONObject(id) ?: return@forEach
            val events = session.optJSONArray("events")
            rows[id] = (0 until (events?.length() ?: 0)).mapNotNull { events?.optJSONObject(it) }.toMutableList()
            bytes += rows[id].orEmpty().sumOf { it.toString().length.toLong() }
            last[id] = maxOf(session.optLong("last"), rows[id]?.lastOrNull()?.optLong("id") ?: 0)
        }
    }
    fun append(id: String, event: JSONObject) {
        val n = (last[id] ?: 0) + 1
        last[id] = n
        val copy = JSONObject(event.toString()).put("id", n)
        if (!copy.has("time")) copy.put("time", System.currentTimeMillis() / 1000)
        rows.getOrPut(id) { mutableListOf() }.add(copy)
        revision++
        bytes += copy.toString().length
        // Same finite retention as the task history. Kept ids never change when an older task is pruned.
        while (bytes > 2_000_000 || rows.values.sumOf { it.size } > 10_000) {
            val oldest = rows.values.filter { it.isNotEmpty() }.minByOrNull { it.first().optLong("time") } ?: break
            bytes -= oldest.removeAt(0).toString().length
        }
    }
    fun events(id: String, after: Long): List<JSONObject> = rows[id].orEmpty().filter { it.optLong("id") > after }.map { JSONObject(it.toString()) }
    fun last(id: String): Long = last[id] ?: 0
    fun encode(): JSONObject = JSONObject().apply { rows.forEach { (id, events) -> put(id, JSONObject().put("last", last(id)).put("events", JSONArray(events))) } }
    fun changed(before: List<ChatTask>, after: List<ChatTask>, sourceType: String = "") {
        val previous = before.associateBy { it.time }
        after.forEach { task ->
            val id = phoneTaskSession(task) ?: return@forEach
            val old = previous[task.time]
            fun event(type: String, text: String = "") = JSONObject().put("type", type).put("text", text).put("task", task.time)
            if (old == null) append(id, event(if (task.mail.isEmpty()) "message" else "note", task.prompt).put("time", task.time / 1000))
            val previousSteps = old?.steps.orEmpty()
            val overlap = (minOf(previousSteps.size, task.steps.size) downTo 0).first { n -> previousSteps.takeLast(n) == task.steps.take(n) }
            (if (sourceType == "step") emptyList() else task.steps.drop(overlap)).forEach { step ->
                val artifact = stepArtifacts(listOf(step)).firstOrNull()
                if (artifact == null) append(id, event("step", step))
                else append(id, event("artifact").put("name", artifact.name).put("version", artifact.version)
                    .put("title", artifact.name).put("mime", "").put("state", "ready"))
            }
            if (sourceType != "text" && task.result != old?.result && task.result.isNotEmpty()) {
                if (task.status == TaskStatus.Running) {
                    // The UI's running result is cumulative; the wire text is an append, not repeated prose.
                    val prefix = old?.result.orEmpty()
                    val delta = phoneTextDelta(prefix, task.result)
                    if (delta.isNotEmpty()) append(id, event("text", delta))
                }
            }
            if (old == null || old.status != task.status || old.approvals != task.approvals) {
                if ((old == null || old.status != task.status) && task.status !in setOf(TaskStatus.Running, TaskStatus.Queued)) {
                    append(id, event(if (task.status == TaskStatus.Stopped) "stopped" else "result", task.result)
                        .put("failed", task.status in setOf(TaskStatus.Failed, TaskStatus.Interrupted)).put("kind", task.agent).put("model", task.answeredBy))
                }
                val session = after.filter { phoneTaskSession(it) == id }
                append(id, event("status").put("status", when {
                    session.any { it.status == TaskStatus.Running && it.approvals.any { a -> a.answer == ApprovalAnswer.Waiting } } -> "waiting"
                    session.any { it.status in setOf(TaskStatus.Running, TaskStatus.Queued) } -> "working"
                    task.status == TaskStatus.Interrupted -> "interrupted"
                    else -> "idle"
                }))
            }
        }
    }
}

/** Mac handoffs and the front agent's own Soma stream are never advertised twice. */
fun phoneTaskSession(task: ChatTask): String? = when {
    task.mac -> null
    task.chat.isNotEmpty() -> "chat-${task.chat}"
    task.build -> if (task.builder.equals("D", true)) "phone-d" else "builder-${task.builder.ifBlank { "A" }.lowercase()}"
    else -> null
}

/** The task log keeps a bounded result tail. Find the suffix overlap instead of repeating that tail. */
fun phoneTextDelta(previous: String, next: String): String {
    if (next.startsWith(previous)) return next.removePrefix(previous)
    val joined = next + '\u0000' + previous
    val prefix = IntArray(joined.length)
    for (i in 1 until joined.length) {
        var n = prefix[i - 1]
        while (n > 0 && joined[i] != joined[n]) n = prefix[n - 1]
        if (joined[i] == joined[n]) n++
        prefix[i] = n
    }
    return next.drop(prefix.lastOrNull()?.coerceAtMost(next.length) ?: 0)
}
