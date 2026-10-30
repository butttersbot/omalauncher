package dev.omalauncher.oma

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import dev.omalauncher.assist.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BuilderQuestionSubmitTest : ChatScreenTestBase() {
    @Test fun finalSingleChoiceClearlySendsAllFourAnswersOnce() = exerciseFinalChoice(false)
    @Test fun finalMultiSelectWaitsForExplicitSend() = exerciseFinalChoice(true)

    @OptIn(ExperimentalTestApi::class)
    private fun exerciseFinalChoice(multi: Boolean) = runComposeUiTest {
        val input = JSONObject().put("questions", JSONArray((1..4).map { i -> JSONObject().put("question", "Q$i?")
            .put("multiSelect", i == 4 && multi).put("options", JSONArray().put(JSONObject().put("label", if (i == 4) "Last choice" else "Earlier $i"))) }))
        val draft = WaitingQuestionDraft().apply { answers = (1..3).associate { "Q$it?" to listOf("Earlier $it") } }
        var calls = 0
        val request = WaitingQuestion("fixture", "Phone A", input.toString()) { payload, done ->
            val reply = ClaudeProtocol.decision(AgentApproval("q", "AskUserQuestion", input, ""), true, payload)
            assertEquals(4, reply.getJSONObject("response").getJSONObject("response").getJSONObject("updatedInput").getJSONObject("answers").length())
            calls++; done(null)
        }
        setContent { Box(Modifier.size(475.dp, 751.dp)) { WaitingQuestionPanel(request, draft) {} } }
        onNodeWithText("[ Send answers ]").assertIsDisplayed().performClick()
        assertEquals(0, calls)
        onNodeWithText("Answer every remaining question first. Nothing has been sent.").assertExists()
        onNodeWithText(if (multi) "☐ Last choice" else "○ Last choice").performScrollTo().performClick()
        if (multi) {
            assertEquals(0, calls)
            onNodeWithText("[ Send answers ]").assertIsDisplayed().performClick()
        }
        assertEquals(1, calls)
        onNodeWithText("Answers sent").assertExists()
        draft.send(request, JSONObject())
        assertEquals(1, calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun notificationForAnotherBuilderWaitsForItsTabBeforeConsumingNonce() = runComposeUiTest {
        val approval = AgentApproval("c-request", "AskUserQuestion", JSONObject("""{"questions":[{"question":"Fictional C decision?","options":[{"label":"Keep fixture"}]}]}"""), "")
        val entry = PendingApproval(approval, "c-ticket", 0)
        var wanted by mutableStateOf<String?>(BuilderId.A.thread)
        var requested by mutableStateOf<String?>(null)
        var opened = 0
        var sent = 0
        val host = object : SampleHost() {
            override fun builders() = mapOf(BuilderId.C to BuilderState(true, listOf(entry)))
            override fun tasks() = listOf(ChatTask(now, "Claude Code", "Fictional C", builder = BuilderId.C.saved, status = TaskStatus.Running))
            override fun threads() = listOf(BuilderId.A, BuilderId.C).map { AgentThread(it.thread, it.title, ThreadState.Working, "", "", now) }
            override fun answerBuilderQuestions(entry: PendingApproval, answers: JSONObject): ApprovalOutcome { sent++; return ApprovalOutcome.Decided }
        }
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, null, {}, wanted, {}, wantedQuestion = requested, onQuestionOpened = { opened++; requested = null }) }
        mainClock.advanceTimeBy(1200)
        runOnIdle { wanted = BuilderId.C.thread; requested = entry.nonce }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("Before I continue").assertExists()
        onNodeWithText("Fictional C decision?").assertExists()
        assertEquals(1, opened); assertEquals(0, sent)
    }
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

    /**
     * Boss, 2026-10-29: a tap beside a waiting question landed on a queued mail's [ send now ], which ended the turn
     * and the question with it ("the question wasn't answered"). While a question of that builder waits, its
     * follow-ups offer [ cancel ] but no [ send now ]; once nothing waits, [ send now ] is back.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test fun sendNowIsNotOfferedWhileThatBuildersQuestionWaits() = runComposeUiTest {
        val approval = AgentApproval("q-request", "AskUserQuestion", JSONObject("""{"questions":[{"question":"Fictional font?","options":[{"label":"Taller cards"}]}]}"""), "")
        val entry = PendingApproval(approval, "q-ticket", 0)
        var waiting by mutableStateOf(listOf(entry))
        var sentNow = 0
        val host = object : SampleHost() {
            override fun builders() = mapOf(BuilderId.A to BuilderState(true, waiting))
            override fun tasks() = listOf(
                ChatTask(now, "Claude Code", "Fictional running turn", status = TaskStatus.Running,
                    approvals = listOf(ChatApproval("AskUserQuestion", "", ApprovalAnswer.Waiting, entry.nonce))),
                ChatTask(now + 1, "Claude Code", "Fictional mail behind it", status = TaskStatus.Queued))
            override fun sendNow(time: Long) { sentNow++ }
        }
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, null, {}, BuilderId.A.thread, {}) }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("Questions waiting").assertExists()
        onNodeWithText("[ cancel ]").assertExists()
        onAllNodesWithText("[ send now ]").assertCountEquals(0)
        assertEquals(0, sentNow)
        runOnIdle { waiting = emptyList() }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ send now ]").assertExists()
    }

    /** Boss, 2026-10-29: the card's [ skip ] asks "Skip this question?" first; keep it changes nothing. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun cardSkipAsksFirstAndKeepItLeavesTheQuestionWaiting() = runComposeUiTest {
        val approval = AgentApproval("s-request", "AskUserQuestion", JSONObject("""{"questions":[{"question":"Fictional skip?","options":[{"label":"Yes"}]}]}"""), "")
        val entry = PendingApproval(approval, "s-ticket", 0)
        val sent = mutableListOf<JSONObject>()
        val host = object : SampleHost() {
            override fun builders() = mapOf(BuilderId.A to BuilderState(true, listOf(entry)))
            override fun tasks() = listOf(ChatTask(now, "Claude Code", "Fictional turn", status = TaskStatus.Running,
                approvals = listOf(ChatApproval("AskUserQuestion", "", ApprovalAnswer.Waiting, entry.nonce))))
            override fun answerBuilderQuestions(entry: PendingApproval, answers: JSONObject): ApprovalOutcome { sent += answers; return ApprovalOutcome.Decided }
        }
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, null, {}, BuilderId.A.thread, {}) }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ skip ]").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithText("Skip this question?").assertExists()
        onNodeWithText("[ keep it ]").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithText("Skip this question?").assertDoesNotExist()
        onNodeWithText("[ answer questions ]").assertExists()
        assertTrue(sent.isEmpty())
        onNodeWithText("[ skip ]").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithText("[ skip it ]").performClick()
        mainClock.advanceTimeBy(300)
        assertEquals(1, sent.size)
        assertTrue(questionAnswersSkipped(sent.single()))
    }
}
