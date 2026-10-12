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
    fun decision(approval: AgentApproval, allow: Boolean): JSONObject {
        val decision = JSONObject().put("behavior", if (allow) "allow" else "deny")
        if (allow) decision.put("updatedInput", approval.input)
        else decision.put("message", "The user declined this action. Do not retry it through another tool.")
        return JSONObject().put("type", "control_response").put("response", JSONObject()
            .put("subtype", "success").put("request_id", approval.id).put("response", decision))
    }
    /** [jpeg] is a base64 JPEG sent inside the message itself, so no file has to outlive the request. */
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
