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
    fun user(prompt: String) = JSONObject().put("type", "user").put("session_id", "")
        .put("message", JSONObject().put("role", "user").put("content", prompt))
}
