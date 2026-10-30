package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Boss, 2026-10-29: the same decision as two cards (Phone A and the MacBook's t1703ae4f); answered once, answered both. */
class QuestionLinksTest {
    private fun form(q: String, labels: List<String>, link: String? = null, other: Boolean = true) = JSONObject().put("questions",
        org.json.JSONArray().put(JSONObject().put("question", q).put("header", "Skills")
            .put("options", org.json.JSONArray(labels.map { JSONObject().put("label", it) })).apply { if (!other) put("isOtherAllowed", false) }))
        .apply { if (link != null) put("metadata", JSONObject().put("source", "link:$link")) }

    private val macbook = QuestionMember("ticket-mb", "Skills cleanup", "macbook-pro/t1703ae4f",
        form("Restore the 3 deleted skills or leave them deleted?", listOf("Restore them", "Leave deleted")))
    private val phoneA = QuestionMember("nonce-a", "Phone A", "phone:A",
        form("The MacBook thread deleted 3 skills: restore them or leave them deleted?", listOf("Restore them", "Leave deleted"), link = "t1703ae4f"))

    @Test fun aLinkNamingAThreadGroupsItsWaitingQuestionBothWays() {
        assertEquals("t1703ae4f", questionLink(phoneA.input))
        assertNull(questionLink(macbook.input))
        assertEquals(listOf("ticket-mb"), linkedQuestions(phoneA, listOf(phoneA, macbook)).map { it.key })
        assertEquals(listOf("nonce-a"), linkedQuestions(macbook, listOf(phoneA, macbook)).map { it.key })
        val stranger = QuestionMember("other", "Phone C", "phone:C", form("Which font?", listOf("Manga")))
        assertTrue(linkedQuestions(stranger, listOf(phoneA, macbook, stranger)).isEmpty())
    }

    @Test fun sameSharedKeyIsOneGroup() {
        val a = QuestionMember("a", "Phone A", "phone:A", form("Ship it?", listOf("Yes"), link = "mail-20261029-221219"))
        val b = QuestionMember("b", "Mac thread", "mac/t9", form("Ship it now?", listOf("Yes"), link = "mail-20261029-221219"))
        assertEquals(listOf("b"), linkedQuestions(a, listOf(a, b)).map { it.key })
    }

    @Test fun hisPickedChoiceIsTheSameChoiceThereAndWordsSayWhereTheyWereGiven() {
        val picked = JSONObject().put(phoneA.input.getJSONArray("questions").getJSONObject(0).getString("question"), "Leave deleted")
        val mapped = mapQuestionAnswers(phoneA.input, picked, macbook.input, "Phone A")!!
        assertEquals("Leave deleted", mapped.getString("Restore the 3 deleted skills or leave them deleted?"))
        val typed = JSONObject().put(phoneA.input.getJSONArray("questions").getJSONObject(0).getString("question"), "Restore only the design one")
        assertEquals("Restore only the design one (Boss answered this in Phone A)",
            mapQuestionAnswers(phoneA.input, typed, macbook.input, "Phone A")!!.getString("Restore the 3 deleted skills or leave them deleted?"))
        // Where his words can't go (no text allowed there), that form keeps waiting for him.
        val strict = form("Restore the 3 deleted skills?", listOf("Restore them"), other = false)
        assertNull(mapQuestionAnswers(phoneA.input, typed, strict, "Phone A"))
    }

    @Test fun muchAlikeQuestionsAreFoundWithoutALinkButNotUnlikeOnes() {
        val alike = QuestionMember("x", "MacBook", "mac/t2", form("Restore the deleted skills, or leave the 3 skills deleted?", listOf("Restore them")))
        val unlike = QuestionMember("y", "Phone C", "phone:C", form("Which theme font for Console cards?", listOf("Taller")))
        assertTrue(similarQuestions(macbook.input, alike.input))
        assertEquals(listOf("x"), similarElsewhere(macbook, listOf(macbook, alike, unlike)).map { it.key })
        GivenAnswers.clear()
        GivenAnswers.add(GivenAnswer(macbook, JSONObject().put("Restore the 3 deleted skills or leave them deleted?", "Restore them"), 1_000))
        assertEquals("ticket-mb", GivenAnswers.alike(alike, now = 2_000)?.member?.key)
        assertNull("a day later it is forgotten", GivenAnswers.alike(alike, now = 1_000 + 25 * 60 * 60 * 1000L))
        GivenAnswers.clear()
    }
}
