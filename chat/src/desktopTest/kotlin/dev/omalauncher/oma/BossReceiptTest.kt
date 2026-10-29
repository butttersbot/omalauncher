package dev.omalauncher.oma

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.omalauncher.assist.AgentApproval
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.MacApprovalOutcome
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.QUESTION_SKIP_ANSWER
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Boss's own words, signed (DESIGN-GATE.md amendment 77d43019): what he types and how he answers a question card can be
 * shown to Canvas as his, and an agent's own text never can.
 */
@OptIn(ExperimentalTestApi::class)
class BossReceiptTest : ChatScreenTestBase() {
    private val device = "ab".repeat(32)

    @Test fun aChatReceiptCarriesHisWordsTheirHashAndWhereTheyWent() {
        val words = BossWords.chat("phone:A", "approve all", vouched = true)
        val r = BossReceipt.receipt(device, 1_793_317_000_000, "n-1", words)!!
        assertEquals(setOf("v", "kind", "deviceId", "nonce", "messageId", "at", "conversation", "text", "textSha256"), r.keySet())
        assertEquals("chat-message", r.getString("kind"))
        assertEquals("phone:A", r.getString("conversation"))
        assertEquals("approve all", r.getString("text"))
        assertEquals(CanvasReceipt.sha256("approve all".toByteArray()), r.getString("textSha256"))
        // The server checks the signature over exactly these bytes: a key pair stands in for the Keystore's.
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val bytes = CanvasReceipt.canonical(r).toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(bytes) }.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(bytes) }.verify(sig))
        assertTrue(CanvasReceipt.canonical(r).startsWith("{\"at\":1793317000000,\"conversation\":\"phone:A\""))
    }

    @Test fun nothingIsVouchedForWithoutHisFingerOrSoonAfterAnAgentActed() {
        assertNull(BossReceipt.receipt(device, 1L, "n", BossWords.chat("soma", "approve all", vouched = false)))
        assertNull(BossReceipt.receipt(device, 1L, "n", BossWords.chat("soma", "   ", vouched = true)))
        val now = 1_000_000L
        assertTrue(BossTouch.mayVouch(now, realTouch = now - 300, agentAction = now - 60_000))
        assertFalse("no touch of his", BossTouch.mayVouch(now, realTouch = Long.MIN_VALUE / 2, agentAction = Long.MIN_VALUE / 2))
        assertFalse("his touch too long before", BossTouch.mayVouch(now, realTouch = now - 5_000, agentAction = Long.MIN_VALUE / 2))
        assertFalse("phone control just acted", BossTouch.mayVouch(now, realTouch = now - 300, agentAction = now - 4_000))
        assertFalse("a touch from the future is no touch", BossTouch.mayVouch(now, realTouch = now + 50, agentAction = Long.MIN_VALUE / 2))
        // An injected touch (adb input, an accessibility gesture) counts as an agent's, never as his.
        BossTouch.touched(physical = true); BossTouch.touched(physical = false)
        assertFalse(BossTouch.mayVouch())
    }

    @Test fun aQuestionAnswerHashesTheAnswersAsServerReadsThem() {
        val answers = JSONObject().put("Approve these 9 designs?", "Approve all").put("Which?", JSONArray().put("a").put("b"))
        val r = BossReceipt.receipt(device, 2L, "n", BossWords.answered("mac:t1", "ticket-1", "Approve these 9 designs?", answers, true))!!
        assertEquals("question-answer", r.getString("kind"))
        assertEquals("ticket-1", r.getString("messageId"))
        assertEquals(CanvasReceipt.sha256(CanvasReceipt.canonical(answers).toByteArray()), r.getString("textSha256"))
    }

    private fun sendInComposer(before: () -> Unit): List<BossWords> {
        val said = mutableListOf<BossWords>()
        runComposeUiTest {
            val host = object : SampleHost() {
                override fun askFront(prompt: String, chat: String) {}
                override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) {}
                override fun bossSaid(words: BossWords) { said += words }
            }
            mainClock.autoAdvance = false
            setContent { OmaChatScreen(host, null, {}, null, {}) }
            mainClock.advanceTimeBy(600)
            onNode(hasSetTextAction()).performTextInput("approve all")
            mainClock.advanceTimeBy(100)
            before()
            onNodeWithText("[ send ]").performClick()
            mainClock.advanceTimeBy(600)
        }
        return said
    }

    @Test fun theComposerHandsOnWhatHeTypedVouchedOnlyWhenHisFingerSentIt() {
        val his = sendInComposer { BossTouch.touched(physical = true) }
        assertEquals(1, his.size)
        assertEquals("approve all", his[0].text); assertEquals("soma", his[0].conversation); assertTrue(his[0].vouched)
        // An agent drove the chat (phone control acted, as PhoneService marks every command that touches the screen).
        val agents = sendInComposer { BossTouch.touched(physical = true); BossTouch.agentActed() }
        assertEquals(1, agents.size); assertFalse(agents[0].vouched)
        assertNull(BossReceipt.receipt(device, 1L, "n", agents[0]))
        // No touch at all (a test click, the desktop's Enter, an injected tap): not vouched.
        val none = sendInComposer { BossTouch.touched(physical = false) }
        assertFalse(none.single().vouched)
    }

    @Test fun aDeliveredAnswerIsHandedOnAndASkipIsNot() {
        val input = JSONObject("""{"questions":[{"question":"Approve these 9 designs?","options":[{"label":"Approve all"}]}]}""")
        val entry = PendingApproval(AgentApproval("q", "AskUserQuestion", input, ""), "ticket-9", 0)
        val said = mutableListOf<BossWords>()
        var outcome = ApprovalOutcome.Decided
        val host = object : SampleHost() {
            override fun builders() = mapOf(BuilderId.C to BuilderState(true, listOf(entry)))
            override fun answerBuilderQuestions(entry: PendingApproval, answers: JSONObject) = outcome
            override fun bossSaid(words: BossWords) { said += words }
        }
        BossTouch.touched(physical = true)
        host.sendWaitingAnswers("ticket-9", JSONObject().put("Approve these 9 designs?", "Approve all")) {}
        assertEquals("ticket-9", said.single().messageId)
        assertEquals("Approve these 9 designs?", said.single().question)
        assertTrue(said.single().vouched)
        host.sendWaitingAnswers("ticket-9", JSONObject().put("Approve these 9 designs?", QUESTION_SKIP_ANSWER)) {}
        assertEquals(1, said.size)
        outcome = ApprovalOutcome.Expired
        host.sendWaitingAnswers("ticket-9", JSONObject().put("Approve these 9 designs?", "Approve all")) {}
        assertEquals("an answer that didn't go is nobody's", 1, said.size)
    }

    @Test fun aMacAnswerIsVouchedAsOfTheTapNotWhenTheMacAnswers() {
        val asked = dev.omalauncher.assist.MacApproval("a1", "n1", "AskUserQuestion",
            """{"questions":[{"question":"Approve the Connections cards?","options":[{"label":"Approve"}]}]}""", "")
        var reply: ((MacApprovalOutcome) -> Unit)? = null
        val said = mutableListOf<BossWords>()
        val host = object : SampleHost() {
            override fun macPending() = listOf(dev.omalauncher.assist.MacPending("t42", "Canvas", asked, "ticket-1", 0))
            override fun answerMacQuestions(ticket: String, answers: JSONObject, done: (MacApprovalOutcome) -> Unit) { reply = done }
            override fun bossSaid(words: BossWords) { said += words }
        }
        BossTouch.touched(physical = true)
        host.sendWaitingAnswers("ticket-1", JSONObject().put("Approve the Connections cards?", "Approve")) {}
        BossTouch.agentActed() // the Mac answers later, after something else touched the phone
        reply!!(MacApprovalOutcome.Decided)
        assertEquals("mac:t42", said.single().conversation)
        assertTrue(said.single().vouched)
    }
}
