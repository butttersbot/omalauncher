package dev.omalauncher.assist

import org.json.JSONObject

/** Anthropic's bidirectional stream-json permission protocol. Grants apply to one call only. */
data class AgentApproval(val id: String, val tool: String, val input: JSONObject, val explanation: String) {
    val detail: String get() = input.optString("command").ifBlank { input.toString(2) }
}

object ClaudeProtocol {
    fun approval(event: JSONObject): AgentApproval? {
        if (event.optString("type") != "control_request") return null
        val request = event.optJSONObject("request") ?: return null
        if (request.optString("subtype") != "can_use_tool") return null
        val id = event.optString("request_id"); if (id.isBlank()) return null
        return AgentApproval(id, request.optString("tool_name"), request.optJSONObject("input") ?: JSONObject(),
            request.optString("description").ifBlank { request.optString("decision_reason") })
    }
    fun decision(approval: AgentApproval, allow: Boolean, answers: JSONObject? = null): JSONObject {
        require(allow || answers == null) { "Denial cannot contain answers" }
        val updated = JSONObject(approval.input.toString())
        if (allow && approval.tool == "AskUserQuestion") {
            val questions = approval.input.optJSONArray("questions")
            require(questions != null && questions.length() > 0) { "Question form is empty" }
            val keys = (0 until questions.length()).map { questions.getJSONObject(it).getString("question") }
            require(keys.all { it.isNotBlank() } && keys.toSet().size == keys.size) { "Question identifiers must be unique" }
            require(answers != null && answers.keys().asSequence().toSet() == keys.toSet()) { "Answer every question exactly" }
            val normalized = JSONObject()
            keys.forEachIndexed { index, key ->
                val q = questions.getJSONObject(index)
                val options = q.optJSONArray("options")
                val labels = if (options == null) emptyList() else (0 until options.length()).map { options.getJSONObject(it).getString("label") }
                val raw = answers.get(key)
                val value = if (raw is org.json.JSONArray) {
                    val selected = (0 until raw.length()).map { raw.get(it) }
                    require(q.optBoolean("multiSelect") && selected.isNotEmpty() && selected.all { it is String && it in labels } && selected.toSet().size == selected.size) { "Invalid multi-select options" }
                    selected.joinToString(", ")
                } else { require(raw is String) { "Answer must be text" }; raw }
                require(value.isNotBlank() && value.length <= 4096) { "Answer must be nonempty and at most 4096 characters" }
                require(q.opt("isOtherAllowed") != false || raw is org.json.JSONArray || value in labels) { "Choose a listed option" }
                normalized.put(key, value)
            }
            updated.put("answers", normalized)
        } else require(answers == null) { "Answers require a question form" }
        val decision = JSONObject().put("behavior", if (allow) "allow" else "deny")
        if (allow) decision.put("updatedInput", updated)
        else decision.put("message", "The user declined this action. Do not retry it through another tool.")
        return JSONObject().put("type", "control_response").put("response", JSONObject()
            .put("subtype", "success").put("request_id", approval.id).put("response", decision))
    }
    /**
     * [jpeg] is a base64 JPEG sent inside the message itself, so no file has to outlive the request.
     * It goes with this request and stays in this conversation until New thread: Claude Code keeps the
     * transcript in the guest, and resuming sends all of it, image included, to the provider with each follow-up.
     */
    fun user(prompt: String, jpeg: String? = null): JSONObject {
        val content: Any = if (jpeg == null) prompt else org.json.JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpeg)))
        return JSONObject().put("type", "user").put("session_id", "")
            .put("message", JSONObject().put("role", "user").put("content", content))
    }
    /** A tool call as one line of the thread: the tool and what it was pointed at. */
    fun step(block: JSONObject): String {
        val input = block.optJSONObject("input")
        val target = listOf("command", "file_path", "path", "pattern", "url", "description")
            .firstNotNullOfOrNull { key -> input?.optString(key)?.takeIf { it.isNotBlank() } }
        val name = block.optString("name").ifBlank { "a tool" }.take(60)
        return if (target == null) name else "$name: ${target.lines().first().take(120)}"
    }
}
