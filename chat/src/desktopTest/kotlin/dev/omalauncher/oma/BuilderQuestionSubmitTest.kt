package dev.omalauncher.oma

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import dev.omalauncher.assist.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BuilderQuestionSubmitTest : ChatScreenTestBase() {
    @OptIn(ExperimentalTestApi::class)
    @Test fun fourLongAnswersUseTheBuilderAllowReplyAndLeaveSendVisibleInShortPanel() = runComposeUiTest {
        val input = JSONObject().put("questions", JSONArray((1..4).map { i -> JSONObject()
            .put("question", "Fictional decision $i?").put("header", "Choice $i")
            .put("options", JSONArray().put(JSONObject().put("label", "Long answer $i " + "both Fold screens and the conversation ".repeat(4)))) }))
        val approval = AgentApproval("request", "AskUserQuestion", input, "")
        val ledger = ApprovalLedger { "fixture-ticket" }
        val entry = (ledger.admit(approval) as Admission.Shown).entry
        var replies = 0
        var genericAnswers = 0
        var response: JSONObject? = null
        val host = object : SampleHost() {
            override fun builders() = mapOf(BuilderId.A to BuilderState(true, ledger.live))
            override fun answerBuilder(entry: PendingApproval, allow: Boolean): ApprovalOutcome { genericAnswers++; error("Question is not a permission button") }
            override fun answerBuilderQuestions(entry: PendingApproval, answers: JSONObject): ApprovalOutcome {
                response = ClaudeProtocol.decision(entry.approval, true, answers)
                assertTrue(ledger.claim(entry.id, entry.nonce, false, true) is Claim.Granted)
                replies++
                return ApprovalOutcome.Decided
            }
        }
        val draft = WaitingQuestionDraft().apply { answers = (1..4).associate { i ->
            "Fictional decision $i?" to listOf(input.getJSONArray("questions").getJSONObject(i-1).getJSONArray("options").getJSONObject(0).getString("label"))
        } }
        val question = WaitingQuestion(entry.nonce, "Phone A", input.toString()) { answers, done -> host.sendWaitingAnswers(entry.nonce, answers, done) }
        setContent { Box(Modifier.size(475.dp, 330.dp)) { WaitingQuestionPanel(question, draft) {} } }
        onNodeWithText("[ Send answers ]").assertIsDisplayed().performClick()
        onNodeWithText("Answers sent").assertExists()
        assertEquals(1, replies); assertEquals(0, genericAnswers)
        val outer = response!!.getJSONObject("response")
        assertEquals("request", outer.getString("request_id"))
        val decision = outer.getJSONObject("response")
        assertEquals("allow", decision.getString("behavior"))
        assertEquals(4, decision.getJSONObject("updatedInput").getJSONObject("answers").length())
        assertEquals(4, decision.getJSONObject("updatedInput").getJSONArray("questions").length())
        draft.send(question, JSONObject())
        assertEquals(1, replies)
        assertEquals(Claim.Expired, ledger.claim(entry.id, entry.nonce, false, true))
    }
}
