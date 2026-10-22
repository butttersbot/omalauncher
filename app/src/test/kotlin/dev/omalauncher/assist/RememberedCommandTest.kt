package dev.omalauncher.assist

import dev.omalauncher.oma.TabAction
import dev.omalauncher.oma.tabMenu
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RememberedCommandTest {
    private fun card(json: JSONObject) = (parseMacEvent(json.put("id", 1).put("time", 0).put("type", "approval").toString()) as MacEvent.Approval).request
    @Test fun onlyTheRunnersExplicitCapabilityExposesRemembering() {
        val base = JSONObject("""{"approval":"a1","nonce":"n1","tool":"Bash","input":{"command":"git status","cwd":"/project"}}""")
        assertFalse(card(base).canRemember)
        assertFalse(card(JSONObject(base.toString()).put("can_remember", "true")).canRemember)
        val card = card(base.put("can_remember", true))
        assertTrue(card.canRemember)
        assertTrue(macApprovalDetail(card).contains("Directory: /project"))
    }

    @Test fun savingUsesTheSameNonceAndAnExplicitRememberFlag() {
        val body = JSONObject(MacRequests.answerApproval("t1", "a1", true, "n1", remember = true).body!!)
        assertEquals("allow", body.getString("decision"))
        assertEquals("n1", body.getString("nonce"))
        assertTrue(body.getBoolean("remember"))
        assertFalse(JSONObject(MacRequests.answerApproval("t1", "a1", true, "n1").body!!).has("remember"))
        assertEquals("/threads/t%2F1/command-approvals/clear", MacRequests.clearCommands("t/1").path)
    }

    @Test fun revocationIsOfferedForAnExplicitlySelectedCodexThread() {
        assertFalse(TabAction.ForgetCommands in tabMenu("mac:t1", false, false, false, 0))
        assertTrue(TabAction.ForgetCommands in tabMenu("mac:t1", false, false, false, 0, forgetCommands = true))
    }
}
