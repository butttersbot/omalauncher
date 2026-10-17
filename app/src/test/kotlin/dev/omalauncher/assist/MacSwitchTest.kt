package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacSwitchTest {
    private fun thread(id: String, kind: String, model: String = "", project: String = "~/Projects/soma") =
        MacThread(id, "Soma", project, ThreadHome.Mac, kind, "", MacThreadStatus.Idle, null, "", 0, 0, emptyList(), model = model)

    @Test fun typedSwitchesAreReadOnlyWhenTheWholeMessageIsOne() {
        assertEquals(MacPick("codex"), macSwitchAsked("switch to codex"))
        assertEquals(MacPick("grok"), macSwitchAsked("/switch grok"))
        assertEquals(MacPick("grok"), macSwitchAsked("  Switch to Grok. "))
        assertEquals(MacPick("claude", "opus"), macSwitchAsked("switch to claude opus"))
        assertEquals(MacPick("claude", "sonnet"), macSwitchAsked("switch to sonnet"))
        assertEquals(MacPick("claude", "claude-fable-5-1"), macSwitchAsked("switch to fable"))
        assertEquals(MacPick("codex", "gpt-6.1-sol"), macSwitchAsked("switch to codex gpt-6.1-sol"))
        assertEquals(MacPick("claude", "claude-opus-5-5"), macSwitchAsked("switch to claude on claude-opus-5-5"))
        // A closing full stop is the sentence's, not the model's.
        assertEquals(MacPick("claude", "opus"), macSwitchAsked("switch to claude opus."))
        assertEquals(MacPick("codex", "gpt-5"), macSwitchAsked("switch to codex gpt-5."))
        assertEquals(MacPick("grok", "grok-5"), macSwitchAsked("switch to grok grok-5!"))
        assertEquals(MacPick("grok", "grok-code-fast"), macSwitchAsked("switch to grok grok-code-fast"))
        // An ordinary word after the agent's name is his words to the thread, never a model (the Critic, 2026-10-16).
        for (words in listOf("switch to codex please", "switch to claude code", "switch to grok instead", "switch to codex now", "/switch claude thinking", "switch to Grok 4", "switch to codex."))
            assertEquals(words, if (words == "switch to codex.") MacPick("codex") else null, macSwitchAsked(words))
        // Ordinary words that mention switching go to the thread as they are.
        for (words in listOf("switch to the other branch", "can you switch to codex style comments", "switch", "switch to", "switch to sonnet please", "please switch to codex", "switch to codex; rm -rf"))
            assertNull(words, macSwitchAsked(words))
    }

    @Test fun claudeOffersItsAliasesAndOthersTheModelsTheyWereSeenOn() {
        assertEquals(MAC_CLAUDE_MODELS, macModels("claude", emptyList()))
        assertEquals(listOf(""), macModels("codex", emptyList()))
        val seen = listOf(thread("a", "codex", "gpt-6.1-sol"), thread("b", "codex", "gpt-6.1-sol"), thread("c", "grok", "grok-5"), thread("d", "cursor", "cursor-auto"), thread("e", "codex", "bad model;"))
        assertEquals(listOf("", "gpt-6.1-sol"), macModels("codex", seen))
        assertEquals(listOf("", "grok-5"), macModels("grok", seen))
        assertEquals(listOf(""), macModels("cursor", seen))
    }

    @Test fun picksAreNamedAsTheChatShowsThem() {
        assertEquals("Codex", macPickName(MacPick("codex")))
        assertEquals("Claude · Opus", macPickName(MacPick("claude", "opus")))
        assertEquals("Claude · Fable", macPickName(MacPick("claude", "claude-fable-5-1")))
        assertEquals("Codex · gpt-6.1-sol", macPickName(MacPick("codex", "gpt-6.1-sol")))
        assertEquals("Grok · grok-5", macPickName(MacPick("grok", "grok-5")))
        assertEquals("default", macModelName(""))
    }

    @Test fun eachProjectRemembersItsLastPickAndOnlyUsesItWhenItCan() {
        var defaults = macDefaultsWith(emptyMap(), "~/Projects/soma/", MacPick("Codex", "gpt-6.1-sol"))
        defaults = macDefaultsWith(defaults, "~/Projects/cove", MacPick("grok"))
        val back = macDefaultsFrom(macDefaultsText(defaults))
        assertEquals(defaults, back)
        assertEquals(MacPick("codex", "gpt-6.1-sol"), macDefaultFor(back, "~/Projects/soma", listOf("claude", "codex"), emptySet()))
        // A computer without its agent, or the agent at its limit, uses the first that can, as ever.
        assertNull(macDefaultFor(back, "~/Projects/cove", listOf("claude", "codex"), emptySet()))
        assertNull(macDefaultFor(back, "~/Projects/soma", listOf("claude", "codex"), setOf("codex")))
        assertNull(macDefaultFor(back, "~/Projects/other", listOf("claude", "codex"), emptySet()))
        // A model the runner wouldn't take is never kept.
        assertEquals(MacPick("codex"), macDefaultsWith(emptyMap(), "x", MacPick("codex", "a b"))["x"])
        // A switch on a thread in /Users/<him>/Projects is the default new chats (in ~/Projects) then use.
        assertEquals(MacPick("codex"), macDefaultFor(macDefaultsWith(emptyMap(), "/Users/jackkim/Projects", MacPick("codex")), MAC_DEFAULT_PROJECT, listOf("claude", "codex"), emptySet()))
        assertTrue(macDefaultsFrom("not json").isEmpty())
        assertTrue(macDefaultsFrom("""{"x":{"model":"opus"}}""").isEmpty())
    }

    @Test fun aThreadCanSwitchToEveryAgentAndModelButTheOneItRuns() {
        val targets = macSwitchTargets(MacPick("claude", "opus"), listOf("claude", "codex", "grok"), listOf(thread("a", "codex", "gpt-6.1-sol")))
        assertFalse(MacPick("claude", "opus") in targets)
        assertTrue(MacPick("claude", "sonnet") in targets && MacPick("claude") in targets)
        assertTrue(MacPick("codex") in targets && MacPick("codex", "gpt-6.1-sol") in targets && MacPick("grok") in targets)
        // Codex first after Claude, as work goes from one to the next.
        assertEquals(listOf("claude", "codex", "grok"), targets.map { it.kind }.distinct())
        assertFalse(macSwitchTargets(MacPick("claude"), listOf("claude"), emptyList()).any { it.kind != "claude" })
    }

    @Test fun theHandoffIsTheAgentsOwnAnswerElseTheEndOfTheRecord() {
        val ask = ChatTask(10, "→ Soma", "switch to codex", thread = "t1", status = TaskStatus.Done, result = "Goal: ship the projects view. Done: registry. Left: the rail. Files: Projects.kt.")
        assertEquals(ask.result, macHandoffWritten(ask))
        assertNull(macHandoffWritten(ask.copy(status = TaskStatus.Dropped)))
        assertNull(macHandoffWritten(ask.copy(result = "ok")))
        assertTrue(macHandoffEnded(ask) && macHandoffEnded(ask.copy(status = TaskStatus.Stopped)) && !macHandoffEnded(ask.copy(status = TaskStatus.Running)))
        val tasks = (1..40L).map { ChatTask(it, "→ Soma", "request $it " + "x".repeat(300), thread = if (it % 2 == 0L) "t1" else "t2", result = "answer $it " + "y".repeat(400), status = TaskStatus.Done) }
        val record = macHandoffFromRecord(tasks, "t1")
        assertTrue(record.length <= MAC_HANDOFF_CHARS)
        assertTrue("newest kept", record.contains("request 40 ") && record.contains("answer 40 "))
        assertFalse("other threads left out", record.contains("request 39 "))
        assertTrue(record.indexOf("request 38 ") < record.indexOf("request 40 "))
        assertEquals("(Nothing in the chat from this thread yet.)", macHandoffFromRecord(tasks, "t9"))
    }

    @Test fun theSeedSaysWhoItTakesOverFromAndIsBounded() {
        val seed = macHandoffSeed("Claude", "z".repeat(MAC_HANDOFF_CHARS * 2), written = true)
        assertTrue(seed.startsWith("You're taking over this thread from Claude"))
        assertTrue(seed.contains("HANDOFF.md"))
        assertTrue(seed.length < MAC_HANDOFF_CHARS + 600)
        assertTrue(macHandoffSeed("Codex", "rec", written = false).contains("couldn't write a handoff"))
        assertTrue(macHandoffAsk(MacPick("grok")).contains("Grok") && macHandoffAsk(MacPick("grok")).contains("change nothing"))
    }

    @Test fun switchesUnderWayRoundTrip() {
        val switches = mapOf(10L to MacPick("codex", "gpt-6.1-sol"), 11L to MacPick("claude"))
        assertEquals(switches, macSwitchesFrom(macSwitchesText(switches)))
        assertTrue(macSwitchesFrom("{\"x\":{}}").isEmpty())
    }

    @Test fun whoCanWriteItsOwnHandoff() {
        val t = thread("t1", "claude")
        assertTrue(macCanWriteHandoff(t, reachable = true, limited = emptySet(), busy = false))
        assertFalse(macCanWriteHandoff(t, reachable = false, limited = emptySet(), busy = false))
        assertFalse(macCanWriteHandoff(t, reachable = true, limited = setOf("claude"), busy = false))
        assertFalse(macCanWriteHandoff(t, reachable = true, limited = emptySet(), busy = true))
    }
}
