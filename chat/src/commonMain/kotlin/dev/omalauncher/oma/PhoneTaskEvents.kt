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
        if (!copy.has("time")) copy.put("time", System.currentTimeMillis())
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
    fun changed(before: List<ChatTask>, after: List<ChatTask>) {
        val previous = before.associateBy { it.time }
        after.forEach { task ->
            val id = phoneTaskSession(task) ?: return@forEach
            val old = previous[task.time]
            fun event(type: String, text: String = "") = JSONObject().put("type", type).put("text", text).put("task", task.time)
            if (old == null) append(id, event(if (task.mail.isEmpty()) "message" else "note", task.prompt).put("time", task.time))
            task.steps.drop(old?.steps?.size ?: 0).forEach { append(id, event("step", it)) }
            if (task.result != old?.result && task.result.isNotEmpty()) {
                if (task.status == TaskStatus.Running) {
                    // The UI's running result is cumulative; the wire text is an append, not repeated prose.
                    val prefix = old?.result.orEmpty()
                    val delta = if (task.result.startsWith(prefix)) task.result.removePrefix(prefix) else task.result
                    if (delta.isNotEmpty()) append(id, event("text", delta))
                }
            }
            task.approvals.forEachIndexed { index, approval ->
                val earlier = old?.approvals?.getOrNull(index)
                if (approval.answer != ApprovalAnswer.Waiting && approval.answer != earlier?.answer) {
                    append(id, event("approval_result").put("approval", earlier?.key ?: approval.key)
                        .put("decision", if (approval.answer in setOf(ApprovalAnswer.Approved, ApprovalAnswer.Skipped)) "allow" else "deny")
                        .put("skipped", approval.answer == ApprovalAnswer.Skipped))
                }
            }
            if (old == null || old.status != task.status) {
                if (task.status !in setOf(TaskStatus.Running, TaskStatus.Queued)) {
                    append(id, event(if (task.status == TaskStatus.Stopped) "stopped" else "result", task.result)
                        .put("failed", task.status in setOf(TaskStatus.Failed, TaskStatus.Interrupted)).put("kind", task.agent).put("model", task.answeredBy))
                }
                append(id, event("status").put("status", when (task.status) {
                    TaskStatus.Running, TaskStatus.Queued -> "working"
                    TaskStatus.Interrupted -> "interrupted"
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
