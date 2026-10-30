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

    private open inner class Host(link: String?) : SampleHost() {
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

    @org.junit.Before fun clean() { BossTouch.forget(); GivenAnswers.clear(); CarriedAnswers.clear(); SameSuggestions.clear() }

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
        assertTrue(host.receipts.last().question.endsWith("(answered in Phone A: Leave deleted)"))
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

    /**
     * By meaning, a Mac thread's question: its runner decides (POST /questions/match; Soma's own model isn't asked) and
     * Soma delivers only deliver:true, as his, once; undo tells the thread.
     */
    @Test fun aMacThreadsSameQuestionIsMatchedByItsRunnerAndDeliveredOnceWithUndo() {
        val mbQuestion = "The Mac Pro's skills folder has 3 skills deleted, so joining it removed them here too. Were they removed on purpose?"
        val mbForm = JSONObject().put("questions", JSONArray().put(JSONObject().put("question", mbQuestion)
            .put("options", JSONArray().put(JSONObject().put("label", "Restore them (Recommended)")).put(JSONObject().put("label", "Keep them deleted")).put(JSONObject().put("label", "Leave it paused")))))
        val said = mutableListOf<String>()
        var modelAsked = 0
        val host = object : Host(link = null) {
            init { macPendingList = listOf(MacPending("macbook-pro/t1703ae4f", "MacBook builds", MacApproval("mb-req", "mb-nonce", "AskUserQuestion", mbForm.toString(), ""), "ticket-mb", 0)) }
            override fun askSameQuestion(prompt: String): String? { modelAsked++; return null }
            override fun matchMacQuestions(onThread: String, card: JSONObject, answers: JSONObject, where: String, answeredThread: String?, answeredApproval: String?): JSONArray {
                assertEquals("macbook-pro/t1703ae4f", onThread); assertEquals("Phone A", where); assertNull("his was on the phone", answeredApproval)
                assertEquals("Yes, keep them deleted", answers.getString(asked))
                return JSONArray().put(JSONObject().put("thread", "t1703ae4f").put("approval", "mb-req").put("nonce", "mb-nonce").put("match", "meaning")
                    .put("deliver", true).put("answers", JSONObject().put(mbQuestion, "Keep them deleted")))
            }
            override fun sayToMac(prompt: String, thread: String, name: String, chat: String, images: List<java.io.File>) { said += "$thread|$prompt" }
        }
        BossTouch.touched(physical = true)
        host.sendWaitingAnswers("nonce-a", JSONObject().put(asked, "Yes, keep them deleted")) {}
        val until = System.currentTimeMillis() + 5_000
        while (host.toMac.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("Keep them deleted", host.toMac.single().getString(mbQuestion))
        assertEquals("no phone builder waits, so Soma's own model isn't asked", 0, modelAsked)
        assertTrue(host.receipts.last().question.endsWith("(answered in Phone A: Yes, keep them deleted)"))
        val carried = CarriedAnswers.list.single()
        assertFalse("once only", CarriedAnswers.claim("ticket-mb"))
        BossTouch.touched(physical = true)
        host.undoCarried(carried)
        assertTrue(said.single().startsWith("macbook-pro/t1703ae4f|Undo: the answer \"Yes, keep them deleted\""))
        assertTrue(CarriedAnswers.list.isEmpty())
    }

    @Test fun aRunnersHintIsOfferedNotSent() {
        val host = object : Host(link = null) {
            override fun matchMacQuestions(onThread: String, card: JSONObject, answers: JSONObject, where: String, answeredThread: String?, answeredApproval: String?) =
                JSONArray().put(JSONObject().put("approval", "mb-req").put("match", "hint").put("deliver", false).put("answers", JSONObject().put(asked, "Leave deleted")))
        }
        BossTouch.touched(physical = true)
        host.sendWaitingAnswers("nonce-a", JSONObject().put(asked, "Leave deleted")) {}
        val until = System.currentTimeMillis() + 5_000
        while (SameSuggestions.get("ticket-mb") == null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(host.toMac.isEmpty())
        val hint = host.questionHint("ticket-mb")!!
        assertEquals("Phone A", hint.answeredIn)
        BossTouch.touched(physical = true)
        hint.answerThereToo!!()
        assertEquals("Leave deleted", host.toMac.single().getString(asked))
    }

    /** By meaning, a phone builder's question: Soma's own check decides (no runner holds it). He answers the MacBook's card. */
    @Test fun aPhoneBuildersSameQuestionIsMatchedBySomasOwnCheck() {
        val host = object : Host(link = null) {
            override fun matchMacQuestions(onThread: String, card: JSONObject, answers: JSONObject, where: String, answeredThread: String?, answeredApproval: String?): JSONArray? = error("no Mac question waits besides his own")
            override fun askSameQuestion(prompt: String) =
                JSONObject().put("is_error", false).put("result", """{"verdicts":[{"key":"nonce-a","same":"yes","mapping":"clear","picks":["Leave deleted"]}]}""").toString()
        }
        BossTouch.touched(physical = true)
        host.sendWaitingAnswers("ticket-mb", JSONObject().put(asked, "Leave deleted")) {}
        val until = System.currentTimeMillis() + 5_000
        while (host.toPhone.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("Leave deleted", host.toPhone.single().getString(asked))
        assertEquals("answered in Skills cleanup: Leave deleted", "answered in " + CarriedAnswers.list.single().from + ": " + CarriedAnswers.list.single().words)
    }
}
