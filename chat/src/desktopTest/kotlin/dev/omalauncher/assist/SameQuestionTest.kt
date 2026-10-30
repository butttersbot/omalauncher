package dev.omalauncher.assist

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/**
 * Boss, 2026-10-29 22:2x: same question by meaning, not words. Tonight's REAL pair: Phone A's card (from its session) and
 * the MacBook's (t1703ae4f, verbatim from its runner; test-linked.py TONIGHT_MACBOOK). He picked "Yes, keep them deleted"
 * on Phone A's; the MacBook's must get "Keep them deleted". Soma's one fast-model call decides for every thread.
 */
class SameQuestionTest {
    private fun form(q: String, vararg labels: String, text: Boolean = true) = JSONObject().put("questions", JSONArray().put(JSONObject()
        .put("question", q).put("options", JSONArray(labels.map { JSONObject().put("label", it) })).apply { if (!text) put("isOtherAllowed", false) }))

    private val phoneAQuestion = "MacBook skills sync is on, and the theme tool now matches the Mac Pro. But the first sync deleted 3 skills on the " +
        "MacBook (decision-artifacts, impeccable, layout) because the Mac Pro had them marked as deleted. The MacBook agent paused the " +
        "folder and has backups of all of them. Were those 3 skills removed on the Mac Pro on purpose?"
    private val macbookQuestion = "The Mac Pro's skills folder has decision-artifacts, impeccable and layout deleted, so joining it removed them " +
        "here too. Were they removed on the Mac Pro on purpose?"
    val phoneA = QuestionMember("nonce-a", "Phone A", "phone:A", form(phoneAQuestion, "Restore them (Recommended)", "Yes, keep them deleted"))
    val macbook = QuestionMember("ticket-mb", "Soma builds on the MacBook", "macbook-pro/t1703ae4f",
        form(macbookQuestion, "Restore them (Recommended)", "Keep them deleted", "Leave it paused"))
    val unrelated = QuestionMember("other", "Phone C", "phone:C", form("Which colour for the Hunch header?", "Red", "Blue"))
    val answer = JSONObject().put(phoneAQuestion, "Yes, keep them deleted")

    private fun claudeSaid(result: String) = JSONObject().put("type", "result").put("is_error", false).put("result", result).toString()

    @Test fun tonightsPairIsAnsweredWithTheMacBooksOwnOptionAndTheUnrelatedOneIsLeft() {
        // What Haiku answered to this prompt on the phone, twice, 2026-10-29.
        val out = claudeSaid("""{"verdicts":[{"key":"ticket-mb","same":"yes","mapping":"clear","picks":["Keep them deleted"]},{"key":"other","same":"no","mapping":"clear","picks":[]}]}""")
        val verdicts = sameQuestionVerdicts(out, listOf(macbook, unrelated), answer, "Phone A")
        val mb = verdicts.single { it.key == "ticket-mb" }
        assertTrue(mb.same && mb.sure)
        assertEquals("Keep them deleted", mb.picks!!.getString(macbookQuestion))
        assertFalse(verdicts.single { it.key == "other" }.same)
    }

    @Test fun andTheOtherWayRound() {
        // He answers the MacBook's card: Phone A's gets its own wording (Haiku on the phone, 2026-10-29).
        val out = claudeSaid("""{"verdicts":[{"key":"nonce-a","same":"yes","mapping":"clear","picks":["Yes, keep them deleted"]}]}""")
        val a = sameQuestionVerdicts(out, listOf(phoneA), JSONObject().put(macbookQuestion, "Keep them deleted"), macbook.where).single()
        assertTrue(a.sure)
        assertEquals("Yes, keep them deleted", a.picks!!.getString(phoneAQuestion))
    }

    @Test fun anInventedOptionOrUnsureMatchIsNeverSentOnlyOffered() {
        // Phone A's own label is not one of the MacBook card's: never sent.
        val invented = sameQuestionVerdicts(claudeSaid("""```json
            {"verdicts":[{"key":"ticket-mb","same":"yes","mapping":"clear","picks":["Yes, keep them deleted"]}]}
            ```"""), listOf(macbook), answer, "Phone A").single()
        assertTrue(invented.same); assertFalse(invented.sure); assertNull(invented.picks)
        val unsure = sameQuestionVerdicts(claudeSaid("""{"verdicts":[{"key":"ticket-mb","same":"unsure","mapping":"clear","picks":["Keep them deleted"]}]}"""),
            listOf(macbook), answer, "Phone A").single()
        assertTrue(unsure.same); assertFalse(unsure.sure)
        assertNotNull("its valid pick is kept, to be offered as one tap", unsure.picks)
        assertTrue("a key it wasn't given is ignored", sameQuestionVerdicts(claudeSaid("""{"verdicts":[{"key":"nope","same":"yes"}]}"""), listOf(macbook), answer, "x").isEmpty())
        assertTrue("an error is nothing", sameQuestionVerdicts("""{"is_error":true,"result":"x"}""", listOf(macbook), answer, "x").isEmpty())
    }

    @Test fun hisOwnWordsGoOnlyWhereTheCardTakesText() {
        val words = "Restore only impeccable"
        assertEquals("Restore only impeccable (Boss answered this in Phone A)",
            checkedPicks(macbook.input, JSONArray().put("USE_HIS_WORDS"), words, "Phone A")!!.getString(macbookQuestion))
        assertNull(checkedPicks(form("Restore?", "Yes", text = false), JSONArray().put("USE_HIS_WORDS"), words, "Phone A"))
        assertNull("one pick per question", checkedPicks(macbook.input, JSONArray(), words, "Phone A"))
    }

    @Test fun thePromptCarriesBothCardsHisAnswerAndTheCommandKeepsNoSession() {
        val prompt = sameQuestionPrompt(phoneA, answer, listOf(macbook))
        assertTrue(prompt.contains("Leave it paused")); assertTrue(prompt.contains("Yes, keep them deleted")); assertTrue(prompt.contains("ticket-mb"))
        val command = sameQuestionCommand(prompt)
        assertTrue(command.contains("--no-session-persistence")); assertTrue(command.contains(".soma-internal")); assertTrue(command.contains("--tools ''"))
    }

    /** The regression with the real fast model: SOMA_LIVE_MODEL=1 and Claude Code on PATH (skipped otherwise). */
    @Test fun liveModelResolvesTonightsPair() {
        Assume.assumeTrue(System.getenv("SOMA_LIVE_MODEL") == "1")
        val proc = ProcessBuilder("/bin/sh", "-c", sameQuestionCommand(sameQuestionPrompt(phoneA, answer, listOf(macbook, unrelated)), desktop = true)).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText(); proc.waitFor()
        val verdicts = sameQuestionVerdicts(out, listOf(macbook, unrelated), answer, "Phone A")
        val mb = verdicts.single { it.key == "ticket-mb" }
        assertTrue("must auto-resolve: $out", mb.sure)
        assertEquals("Keep them deleted", mb.picks!!.getString(macbookQuestion))
        assertFalse(verdicts.firstOrNull { it.key == "other" }?.let { it.same && it.sure } == true)
    }
}
