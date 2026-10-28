package dev.omalauncher.runner

import dev.omalauncher.assist.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneRunnerApprovalTest {
    private fun question() = AgentApproval("tool-1", "AskUserQuestion", JSONObject("""{"questions":[{"question":"Layout?","isOtherAllowed":false,"options":[{"label":"Cover"}]}]}"""), "")
    @Test fun denyQuestionIsOneAllowReplyWithDelegationAndNotStop() {
        val ledger = ApprovalLedger { "nonce" }
        val pending = (ledger.admit(question(), emptySet()) as Admission.Shown).entry
        val skip = phoneRunnerDecision(pending, "nonce", "deny", null)
        assertTrue(skip.allow)
        assertEquals(QUESTION_SKIP_ANSWER, skip.answers!!.getString("Layout?"))
        val response = ClaudeProtocol.decision(pending.approval, skip.allow, skip.answers).getJSONObject("response").getJSONObject("response")
        assertEquals("allow", response.getString("behavior"))
        assertEquals(QUESTION_SKIP_ANSWER, response.getJSONObject("updatedInput").getJSONObject("answers").getString("Layout?"))
        assertTrue(ledger.claim(pending.id, pending.nonce, locked = false, active = true) is Claim.Granted)
        assertEquals(Claim.Expired, ledger.claim(pending.id, pending.nonce, locked = false, active = true))
    }
    @Test fun invalidFormAndWrongNonceLeaveTheRealLedgerWaiting() {
        val ledger = ApprovalLedger { "nonce" }
        val pending = (ledger.admit(question(), emptySet()) as Admission.Shown).entry
        for ((nonce, body, code) in listOf(Triple("wrong", JSONObject().put("Layout?", "Cover"), 403), Triple("nonce", JSONObject().put("Layout?", "unlisted"), 400))) {
            val error = runCatching { phoneRunnerDecision(pending, nonce, "allow", body) }.exceptionOrNull()
            assertEquals(code, (error as RunnerFault).code)
            assertEquals(1, ledger.live.size)
        }
        assertEquals(Claim.Locked, ledger.claim(pending.id, pending.nonce, locked = true, active = true))
        assertEquals(1, ledger.live.size)
    }
    @Test fun ordinaryPermissionDenyRemainsDenyAndRejectsQuestionAnswers() {
        val pending = PendingApproval(AgentApproval("tool", "Bash", JSONObject().put("command", "pwd"), ""), "nonce", 0)
        assertFalse(phoneRunnerDecision(pending, "nonce", "deny", null).allow)
        assertEquals(400, (runCatching { phoneRunnerDecision(pending, "nonce", "allow", JSONObject().put("Q", "A")) }.exceptionOrNull() as RunnerFault).code)
    }
}
