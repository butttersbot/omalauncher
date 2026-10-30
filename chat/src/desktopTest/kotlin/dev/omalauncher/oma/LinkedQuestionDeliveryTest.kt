package dev.omalauncher.oma

import dev.omalauncher.assist.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** His answer to one linked question reaches the others, as his; a skip or an answer his finger didn't send doesn't. */
class LinkedQuestionDeliveryTest : ChatScreenTestBase() {
    private val asked = "Restore the 3 deleted skills or leave them deleted?"
    private fun form(link: String?) = JSONObject().put("questions", JSONArray().put(JSONObject().put("question", asked)
        .put("options", JSONArray().put(JSONObject().put("label", "Restore them")).put(JSONObject().put("label", "Leave deleted")))))
        .apply { if (link != null) put("metadata", JSONObject().put("source", "link:$link")) }

    private inner class Host(link: String?) : SampleHost() {
        val entry = PendingApproval(AgentApproval("a-req", "AskUserQuestion", form(link), ""), "nonce-a", 0)
        var macPendingList = listOf(MacPending("macbook-pro/t1703ae4f", "Skills cleanup", MacApproval("mb-req", "mb-nonce", "AskUserQuestion", form(null).toString(), ""), "ticket-mb", 0))
        val toMac = mutableListOf<JSONObject>()
        val toPhone = mutableListOf<JSONObject>()
        val receipts = mutableListOf<BossWords>()
        override fun builders() = mapOf(BuilderId.A to BuilderState(true, listOf(entry)))
        override fun macPending() = macPendingList
        override fun answerBuilderQuestions(entry: PendingApproval, answers: JSONObject): ApprovalOutcome { toPhone += answers; return ApprovalOutcome.Decided }
        override fun answerMacQuestions(ticket: String, answers: JSONObject, done: (MacApprovalOutcome) -> Unit) { assertEquals("ticket-mb", ticket); toMac += answers; done(MacApprovalOutcome.Decided) }
        override fun bossSaid(words: BossWords) { receipts += words }
    }

    @org.junit.Before fun clean() { BossTouch.forget(); GivenAnswers.clear() }

    @Test fun linkedAnswerReachesTheMacBooksCardAsHis() {
        val host = Host(link = "t1703ae4f")
        BossTouch.touched(physical = true)
        var failure: String? = "unset"
        host.sendWaitingAnswers("nonce-a", JSONObject().put(asked, "Leave deleted")) { failure = it }
        assertNull(failure)
        assertEquals(1, host.toPhone.size)
        assertEquals("Leave deleted", host.toMac.single().getString(asked))
        assertEquals(2, host.receipts.size)
        assertTrue(host.receipts.all { it.vouched })
        assertTrue(host.receipts.last().question.endsWith("(answered in Phone A)"))
    }

    @Test fun nothingGoesElsewhereWithoutHisFingerOrForASkipOrWithoutALink() {
        Host(link = "t1703ae4f").let { host ->
            host.sendWaitingAnswers("nonce-a", JSONObject().put(asked, "Leave deleted")) {}
            assertTrue("not his finger", host.toMac.isEmpty())
        }
        Host(link = "t1703ae4f").let { host ->
            BossTouch.touched(physical = true)
            host.sendWaitingAnswers("nonce-a", questionSkipAnswers(form(null).toString())) {}
            assertTrue("a skip", host.toMac.isEmpty())
        }
        Host(link = null).let { host ->
            BossTouch.touched(physical = true)
            host.sendWaitingAnswers("nonce-a", JSONObject().put(asked, "Leave deleted")) {}
            assertTrue("no link: only offered, never sent", host.toMac.isEmpty())
            val hint = host.questionHint("ticket-mb")!!
            assertEquals("Phone A", hint.answeredIn)
            assertEquals("Leave deleted", hint.answeredWith)
            BossTouch.touched(physical = true)
            hint.answerThereToo!!()
            assertEquals("Leave deleted", host.toMac.single().getString(asked))
        }
    }
}
