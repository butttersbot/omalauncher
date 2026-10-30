package dev.omalauncher.assist

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/**
 * Boss, 2026-10-29 22:2x: same question by meaning, not words. Tonight's pair: Phone A's "Were those 3 skills removed on
 * the Mac Pro on purpose?" (Restore them / Yes, keep them deleted) and the MacBook's own wording of the same decision.
 */
class SameQuestionTest {
    private fun form(q: String, vararg labels: String, text: Boolean = true) = JSONObject().put("questions", JSONArray().put(JSONObject()
        .put("question", q).put("options", JSONArray(labels.map { JSONObject().put("label", it) })).apply { if (!text) put("isOtherAllowed", false) }))

    private val phoneAQuestion = "MacBook skills sync is on, and the theme tool now matches the Mac Pro. But the first sync deleted 3 skills on the " +
        "MacBook (decision-artifacts, impeccable, layout) because the Mac Pro had them marked as deleted. The MacBook agent paused the " +
        "folder and has backups of all of them. Were those 3 skills removed on the Mac Pro on purpose?"
    val phoneA = QuestionMember("nonce-a", "Phone A", "phone:A", form(phoneAQuestion, "Restore them (Recommended)", "Yes, keep them deleted"))
    val macbook = QuestionMember("ticket-mb", "Soma builds on the MacBook", "macbook-pro/t1703ae4f",
        form("Syncthing's first pass removed decision-artifacts, impeccable and layout from ~/.claude/skills here, since the Mac Pro lists " +
            "them as deleted. I paused the claude-skills folder and kept a backup. Should I put them back, or were they meant to be gone?",
            "Put them back from the backup", "They were meant to be gone"))
    val unrelated = QuestionMember("other", "Phone C", "phone:C", form("Which font should Console's home cards use?", "Taller cards", "System mono"))
    val answer = JSONObject().put(phoneAQuestion, "Restore them (Recommended)")

    private fun claudeSaid(result: String) = JSONObject().put("type", "result").put("is_error", false).put("result", result).toString()

    @Test fun tonightsPairIsAnsweredWithTheMacBooksOwnOptionAndTheUnrelatedOneIsLeft() {
        // What Haiku answered to this prompt on the phone, 2026-10-29 (6 s, $0.001).
        val out = claudeSaid("""{"verdicts":[{"key":"ticket-mb","same":"yes","mapping":"clear","picks":["Put them back from the backup"]},{"key":"other","same":"no","mapping":"unsure","picks":[]}]}""")
        val verdicts = sameQuestionVerdicts(out, listOf(macbook, unrelated), answer, "Phone A")
        val mb = verdicts.single { it.key == "ticket-mb" }
        assertTrue(mb.same && mb.sure)
        assertEquals("Put them back from the backup", mb.picks!!.getString(macbook.input.getJSONArray("questions").getJSONObject(0).getString("question")))
        assertFalse(verdicts.single { it.key == "other" }.same)
    }

    @Test fun anInventedOptionOrUnsureMatchIsNeverSentOnlyOffered() {
        val invented = sameQuestionVerdicts(claudeSaid("""```json
            {"verdicts":[{"key":"ticket-mb","same":"yes","mapping":"clear","picks":["Restore them"]}]}
            ```"""), listOf(macbook), answer, "Phone A").single()
        assertTrue(invented.same); assertFalse(invented.sure); assertNull(invented.picks)
        val unsure = sameQuestionVerdicts(claudeSaid("""{"verdicts":[{"key":"ticket-mb","same":"unsure","mapping":"clear","picks":["Put them back from the backup"]}]}"""),
            listOf(macbook), answer, "Phone A").single()
        assertTrue(unsure.same); assertFalse(unsure.sure)
        assertNotNull("its valid pick is kept, to be offered as one tap", unsure.picks)
        assertTrue("a key it wasn't given is ignored", sameQuestionVerdicts(claudeSaid("""{"verdicts":[{"key":"nope","same":"yes"}]}"""), listOf(macbook), answer, "x").isEmpty())
        assertTrue("an error is nothing", sameQuestionVerdicts("""{"is_error":true,"result":"x"}""", listOf(macbook), answer, "x").isEmpty())
    }

    @Test fun hisOwnWordsGoOnlyWhereTheCardTakesText() {
        val words = "Restore only impeccable"
        assertEquals("Restore only impeccable (Boss answered this in Phone A)",
            checkedPicks(macbook.input, JSONArray().put("USE_HIS_WORDS"), words, "Phone A")!!.getString(macbook.input.getJSONArray("questions").getJSONObject(0).getString("question")))
        assertNull(checkedPicks(form("Restore?", "Yes", text = false), JSONArray().put("USE_HIS_WORDS"), words, "Phone A"))
        assertNull("one pick per question", checkedPicks(macbook.input, JSONArray(), words, "Phone A"))
    }

    @Test fun thePromptCarriesBothCardsHisAnswerAndTheCommandKeepsNoSession() {
        val prompt = sameQuestionPrompt(phoneA, answer, listOf(macbook))
        assertTrue(prompt.contains("Put them back from the backup")); assertTrue(prompt.contains("Restore them (Recommended)")); assertTrue(prompt.contains("ticket-mb"))
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
        assertEquals("Put them back from the backup", mb.picks!!.getString(macbook.input.getJSONArray("questions").getJSONObject(0).getString("question")))
        assertFalse(verdicts.firstOrNull { it.key == "other" }?.sure == true && verdicts.first { it.key == "other" }.same)
    }
}
