package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionSkipTest {
    @Test fun skipAnswersEveryQuestionUsingTheProtocolsOwnKeys() {
        val input = JSONObject("""{"questions":[{"id":"layout","question":"Layout?","isOtherAllowed":false},{"id":"checks","question":"Checks?","multiSelect":true}]}""")
        val claude = questionSkipAnswers(input.toString())
        assertEquals(setOf("Layout?", "Checks?"), claude.keys().asSequence().toSet())
        assertTrue(questionAnswersSkipped(claude))
        val codex = questionSkipAnswers(input.put("codex_request", "questions").toString())
        assertEquals(setOf("layout", "checks"), codex.keys().asSequence().toSet())
        assertTrue(questionAnswersSkipped(codex))
        assertFalse(questionAnswersSkipped(JSONObject()))
        assertFalse(questionAnswersSkipped(codex.put("layout", "Cover")))
    }

    @Test fun strictQuestionSkipAllowsContinuationWithoutWeakeningOrdinaryAnswers() {
        val input = JSONObject("""{"questions":[{"question":"Layout?","options":[{"label":"Cover"}],"isOtherAllowed":false}]}""")
        val approval = AgentApproval("q", "AskUserQuestion", input, "")
        val reply = ClaudeProtocol.decision(approval, true, questionSkipAnswers(input.toString()))
            .getJSONObject("response").getJSONObject("response")
        assertEquals("allow", reply.getString("behavior"))
        assertEquals(QUESTION_SKIP_ANSWER, reply.getJSONObject("updatedInput").getJSONObject("answers").getString("Layout?"))
        assertThrows(IllegalArgumentException::class.java) {
            ClaudeProtocol.decision(approval, true, JSONObject().put("Layout?", "arbitrary other"))
        }
        assertFalse(input.has("answers"))
    }

    @Test fun runnerSkipEventPreservesAllowAndUpdatesOtherDevicesChatLine() {
        val json = JSONObject().put("id", 3).put("time", 0).put("type", "approval_result")
            .put("approval", "q").put("decision", "allow").put("skipped", true)
        val event = parseMacEvent(json.toString()) as MacEvent.ApprovalResult
        assertEquals(MacDecision.Allow, event.decision)
        assertTrue(event.skipped)
        assertEquals("skipped", dev.omalauncher.oma.ApprovalAnswer.Skipped.label)
    }
}
