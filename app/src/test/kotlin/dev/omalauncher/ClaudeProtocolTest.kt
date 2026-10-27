package dev.omalauncher

import dev.omalauncher.assist.ClaudeProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClaudeProtocolTest {
    private fun event() = JSONObject("""{"type":"control_request","request_id":"request-7","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"printf '%s' 'literal; $(touch unsafe)'"}}}""")
    @Test fun grantsExactlyThePendingCallWithoutPersistentPermissionUpdates() {
        val approval = ClaudeProtocol.approval(event())!!
        val reply = ClaudeProtocol.decision(approval, true).getJSONObject("response")
        assertEquals("request-7", reply.getString("request_id"))
        val decision = reply.getJSONObject("response")
        assertEquals("allow", decision.getString("behavior"))
        assertEquals(approval.input.toString(), decision.getJSONObject("updatedInput").toString())
        assertFalse(decision.has("updatedPermissions"))
    }
    @Test fun denialDoesNotIncludeRunnableInputOrPermissionUpdates() {
        val reply = ClaudeProtocol.decision(ClaudeProtocol.approval(event())!!, false)
            .getJSONObject("response").getJSONObject("response")
        assertEquals("deny", reply.getString("behavior"))
        assertFalse(reply.has("updatedInput")); assertFalse(reply.has("updatedPermissions"))
    }
    @Test fun incompleteOrUnrelatedEventsNeverBecomeApprovalRequests() {
        assertNull(ClaudeProtocol.approval(JSONObject("""{"type":"assistant"}""")))
        assertNull(ClaudeProtocol.approval(JSONObject("""{"type":"control_request","request":{"subtype":"can_use_tool"}}""")))
    }
    private fun question() = dev.omalauncher.assist.AgentApproval("q1", "AskUserQuestion", JSONObject("""{"questions":[{"question":"Pick checks","multiSelect":true,"options":[{"label":"Cover"},{"label":"Inner"}]}]}"""), "")
    @Test fun questionAnswersAreNormalizedWithoutMutatingOriginal() {
        val approval = question()
        val result = ClaudeProtocol.decision(approval, true, JSONObject("""{"Pick checks":["Cover","Inner"]}"""))
            .getJSONObject("response").getJSONObject("response").getJSONObject("updatedInput")
        assertEquals("Cover, Inner", result.getJSONObject("answers").getString("Pick checks"))
        assertFalse(approval.input.has("answers"))
    }
    @Test fun questionRequiresCompleteValidatedAnswers() {
        for (bad in listOf<JSONObject?>(null, JSONObject(), JSONObject("""{"Pick checks":["Unknown"]}"""))) {
            try { ClaudeProtocol.decision(question(), true, bad); fail("invalid answer accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("deny", ClaudeProtocol.decision(question(), false).getJSONObject("response").getJSONObject("response").getString("behavior"))
    }
}
