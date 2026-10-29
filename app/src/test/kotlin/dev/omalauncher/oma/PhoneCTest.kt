package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuildBoard
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.PHONE_THREAD_C
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.agentsStatus
import dev.omalauncher.assist.askingBuilder
import dev.omalauncher.assist.boardText
import dev.omalauncher.assist.mailBuilder
import org.junit.Assert.*
import org.junit.Test

/** Boss, 2026-10-24: a third phone agent, Phone C, set up as A and B are: its tab, its entries, its stop, its board lines and status. */
class PhoneCTest {
    private val cThread = AgentThread(PHONE_THREAD_C, "Phone C", ThreadState.Working, "", "working")

    @Test fun itsTabTalksStraightToItAndShowsOnlyItsOwnEntries() {
        assertEquals(SendTo.PhoneC, sendTo(PHONE_THREAD_C, listOf(cThread)))
        assertEquals(BuilderId.C, SendTo.PhoneC.builder)
        assertEquals("to Phone C…", fieldHint(SendTo.PhoneC))
        assertTrue(fieldStop(SendTo.PhoneC, working = true, deciding = false))
        assertFalse(fieldStop(SendTo.PhoneC, working = false, deciding = true))
        val toA = ChatTask(1, "Claude Code", "a", status = TaskStatus.Done)
        val toB = ChatTask(2, "Codex", "b", status = TaskStatus.Done, builder = "B")
        val toC = ChatTask(3, "Claude Code", "c", status = TaskStatus.Done, builder = "C")
        assertEquals(listOf(toC), tabEntries(listOf(toA, toB, toC), PHONE_THREAD_C))
        assertEquals(listOf(toB), tabEntries(listOf(toA, toB, toC), "phone-b"))
        assertEquals(PHONE_THREAD_C, lastTurnTab(listOf(toA, toB, toC)))
        assertEquals(BuilderId.C, tabBuilder(PHONE_THREAD_C))
        assertEquals(TabStop.Builder(BuilderId.C), tabStop(PHONE_THREAD_C, cThread, TabMeta()))
        // Always on the strip; active C precedes idle threads, then the rest are newest first.
        val mac = AgentThread("mac:t1234abcd", "work", ThreadState.Idle, "", "idle", worked = 9)
        val strip = shownThreads(listOf(mac), emptyList(), listOf(cThread, AgentThread("phone", "Phone A", ThreadState.Idle, "", "idle"), AgentThread("phone-b", "Phone B", ThreadState.Idle, "", "idle")))
        assertEquals(listOf(PHONE_THREAD_C, "mac:t1234abcd", "phone", "phone-b"), strip.map { it.key })
    }

    @Test fun theFrontAgentAndTheStatusFileKnowItOnceItHasWorked() {
        val a = BuildBoard(working = false)
        val c = BuildBoard(working = false, lastAnswer = "Shall I push it?", spokeAgo = 60_000)
        val text = boardText(a, null, null, null, c)
        assertTrue(text, text.contains("Builder A: idle."))
        assertTrue(text, text.contains("Builder C: idle. Its last answer ended: \"Shall I push it?\""))
        assertFalse(text, text.contains("Builder B"))
        assertEquals(BuilderId.C, askingBuilder(mapOf(BuilderId.A to a, BuilderId.B to BuildBoard(false), BuilderId.C to c)))
        val facts = BuilderFacts(1_000_000, working = false)
        assertFalse(agentsStatus(facts, null, "x").has("builderC"))
        assertTrue(agentsStatus(facts, null, "x", third = facts).has("builderC"))
        // Mail it signed as C (oma msg, oma-macbuild) comes back to it.
        for (s in listOf("C", "c", "phone-c", "builder-c")) assertEquals(s, BuilderId.C, mailBuilder(s, emptyMap()))
    }
}
