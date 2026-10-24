package dev.omalauncher.phone

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** A short semantic plan, validated entirely before the first action. No shell, arbitrary intents or nested plans. */
data class PhonePlan(val id: String, val steps: List<JSONObject>, val timeoutMs: Long, val display: String = "main") {
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(canonical(JSONObject().put("steps", JSONArray(steps)).put("timeoutMs", timeoutMs).put("display", display)).toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_AGE_MS = 86_400_000L
        const val MAX_STEPS = 40
        private val fields = mapOf(
            "open" to setOf("command", "package"), "url" to setOf("command", "url", "app"),
            "screen" to setOf("command", "until", "in"), "press" to setOf("command", "label", "in"),
            "fill" to setOf("command", "label", "text", "in"),
            "submit" to setOf("command", "in"), "back" to setOf("command", "in"),
        )
        fun parse(input: JSONObject, now: Long): PhonePlan {
            require(input.toString().toByteArray().size <= 60_000) { "Plan exceeds60000 bytes." }
            val id = input.getString("id")
            require(id.matches(Regex("[0-9]{13}-[A-Za-z0-9-]{8,64}"))) { "Plan id must be epoch milliseconds followed by '-' and a unique token." }
            val created = id.substringBefore('-').toLong()
            require(created <= now + 120_000 && now - created <= MAX_AGE_MS) { "Plan id is expired or from the future. Do not replay old actions." }
            val array = input.getJSONArray("steps")
            require(array.length() in 1..MAX_STEPS) { "A plan has 1..$MAX_STEPS steps." }
            val steps = (0 until array.length()).map { n ->
                val step = JSONObject(array.getJSONObject(n).toString())
                val command = step.getString("command")
                val allowed = fields[command] ?: error("Unsupported plan command at step ${n + 1}: $command")
                require(step.keys().asSequence().all { it in allowed }) { "Unsupported field at step ${n + 1}." }
                if (command in setOf("press", "fill", "submit", "back", "screen")) {
                    require(step.getString("in").isNotBlank()) { "Plan actions and screen assertions require an explicit expected package." }
                }
                when (command) {
                    "open" -> { require(step.getString("package").isNotBlank()); PhoneSettings.action(step.getString("package")) }
                    "url" -> require(PhoneGuard.webUrl(step.getString("url")).isSuccess) { "Invalid web URL." }
                    "press" -> require(step.getString("label").isNotBlank() && step.getString("label").length <= PhoneFind.MAX_LABEL)
                    "fill" -> { require(step.getString("label").length <= PhoneFind.MAX_LABEL); require(step.getString("text").length <= 10_000) }
                    "screen" -> require(step.getString("until").isNotBlank() && step.getString("until").length <= 200) { "Plan screens require expected words." }
                }
                step
            }
            require(steps.last().getString("command") == "screen") { "End a plan with a screen assertion." }
            val timeout = input.optLong("timeoutMs", 15_000)
            require(timeout in 1_000..30_000) { "Plan timeout must be 1000..30000 milliseconds." }
            val display = input.optString("display", "main")
            require(display in setOf("main", "bg")) { "Invalid plan display." }
            return PhonePlan(id, steps, timeout, display)
        }
        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }
}

/** No contents or selectors stored. Unexpired IDs are never evicted to make room for another mutation. */
class PhonePlanJournal(private val file: File, private val now: () -> Long = System::currentTimeMillis,
    private val processToken: String = PROCESS_TOKEN) {
    companion object { private val PROCESS_TOKEN = java.util.UUID.randomUUID().toString() }
    @Synchronized fun begin(plan: PhonePlan): JSONObject? {
        val records = load()
        records.optJSONObject(plan.id)?.let {
            check(it.getString("fingerprint") == plan.fingerprint) { "Plan id already belongs to different steps. Nothing was run." }
            return JSONObject(it.toString()).put("id", plan.id).put("replayed", true).put("ok", it.optString("state") == "done")
        }
        check(records.length() < 1024) { "Plan receipt storage is full; nothing was run." }
        records.put(plan.id, JSONObject().put("fingerprint", plan.fingerprint).put("state", "running").put("completed", 0).put("owner", processToken))
        // Receipt is durable before any side effect; a different process token resolves running to interrupted.
        save(records)
        return null
    }
    @Synchronized fun end(plan: PhonePlan, state: String, completed: Int) {
        val records = load()
        val entry = records.getJSONObject(plan.id)
        entry.put("state", state).put("completed", completed)
        save(records)
    }
    @Synchronized fun receipt(id: String): JSONObject = load().optJSONObject(id)?.let {
        JSONObject(it.toString()).put("id", id).put("ok", true)
    } ?: JSONObject().put("ok", false).put("error", "No retained receipt for this id. Do not infer that no action ran.")
    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject()
        check(file.length() <= 512_000) { "Plan receipt storage is oversized; no actions allowed." }
        val records = JSONObject(file.readText()) // Corruption is fail-closed, never an empty retry cache.
        records.keys().asSequence().toList().forEach { id ->
            val created = id.substringBefore('-').toLong()
            if (now() - created > PhonePlan.MAX_AGE_MS) records.remove(id)
            else records.getJSONObject(id).let { entry ->
                if (entry.optString("state") == "running" && entry.optString("owner") != processToken) entry.put("state", "interrupted")
            }
        }
        return records
    }
    private fun save(records: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".new")
        FileOutputStream(tmp).use { it.write(records.toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file)) { "Could not save plan receipt; no further actions allowed." }
    }
}

/** One local decision loop. A failed assertion, pause, timeout or double callback never advances the plan. */
class PhonePlanRunner(
    private val plan: PhonePlan,
    private val clock: () -> Long,
    private val guard: () -> Unit,
    private val action: (JSONObject, (JSONObject) -> Unit) -> Unit,
    private val finish: (JSONObject) -> Unit,
) {
    private val began = clock()
    private var completed = 0
    private var stopped = false
    private var inFlight = false
    private val trace = JSONArray()
    var schedule: (() -> Unit) -> Unit = { it() }
    fun start() = next()
    fun status(): JSONObject = JSONObject().put("id", plan.id).put("completed", completed).put("inFlight", inFlight)
    fun cancel(state: String = "cancelled", reason: String = "Phone plan stopped") {
        end(false, state, reason, null)
    }
    private fun end(ok: Boolean, state: String, error: String?, last: JSONObject?) {
        if (stopped) return
        stopped = true
        finish(JSONObject().put("ok", ok).put("id", plan.id).put("state", state).put("completed", completed)
            .put("failedStep", if (ok) JSONObject.NULL else completed + 1).put("actionMayHaveRun", !ok && inFlight)
            .put("trace", trace).apply { if (error != null) put("error", error); if (last != null) put("screen", last) })
    }
    private fun next() {
        if (stopped) return
        try {
            guard()
            if (clock() - began >= plan.timeoutMs) { cancel("timed-out", "Plan deadline reached. Do not replay mutations."); return }
            if (completed == plan.steps.size) { end(true, "done", null, null); return }
            val step = plan.steps[completed]
            val started = clock()
            var answered = false
            inFlight = true
            action(step) result@ { output ->
                if (!stopped && !answered) {
                    answered = true
                    try { guard() } catch (failure: Exception) {
                        end(false, "failed", failure.message ?: "Phone plan stopped", null); return@result
                    }
                    if (clock() - began >= plan.timeoutMs) {
                        cancel("timed-out", "Plan deadline reached. Do not replay mutations."); return@result
                    }
                    val ok = output.optBoolean("ok") && (!output.has("shows") || output.optBoolean("shows")) &&
                        (!output.has("inFront") || output.optBoolean("inFront"))
                    trace.put(JSONObject().put("command", step.getString("command")).put("elapsedMs", clock() - started).put("ok", ok))
                    if (!ok) end(false, "failed", output.optString("error", "Expected screen was not reached"), output)
                    else {
                        inFlight = false
                        completed++
                        if (completed == plan.steps.size) end(true, "done", null, output) else schedule { next() }
                    }
                }
            }
        } catch (failure: Exception) { end(false, "failed", failure.message ?: "Phone plan failed", null) }
    }
}
