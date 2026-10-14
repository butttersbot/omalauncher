package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.macThreadKey
import org.junit.Assert.*
import org.junit.Test

/** The Oma chat's tabs (Boss, 2026-10-13): which there are, which one opens, what each shows and whom its field talks to. */
class ChatTabsTest {
    private val phone = AgentThread(PHONE_THREAD, "Phone", ThreadState.Idle, "", "idle")
    private val ff4 = AgentThread(macThreadKey("t-ff4"), "FF4 better menu mod", ThreadState.Working, "Editing", "working")
    private val pond = AgentThread(macThreadKey("t-pond"), "Pond", ThreadState.NeedsOk, "Wants to write x", "needs your OK")
    private val threads = listOf(phone, ff4, pond)

    private val built = ChatTask(1, "Claude Code", "phone work", status = TaskStatus.Done)
    private val waiting = ChatTask(2, "Claude Code", "more phone work", status = TaskStatus.Queued)
    private val answered = ChatTask(3, QUICK_AGENT, "what is 15% of 240?", result = "36", status = TaskStatus.Done)
    private val reading = ChatTask(4, UNDECIDED_AGENT, "hm")
    private val toFf4 = ChatTask(5, "→ FF4 better menu mod", "fix the menu", thread = "t-ff4", threadName = "FF4 better menu mod")
    private val toPond = ChatTask(6, "→ Pond", "add frogs", thread = "t-pond", threadName = "Pond", direct = true)
    private val tasks = listOf(built, waiting, answered, reading, toFf4, toPond)

    @Test fun omaComesFirstThenTheThreadsInTheirOrder() {
        assertEquals(listOf(OMA_TAB, PHONE_THREAD, "mac:t-ff4", "mac:t-pond"), chatTabs(threads))
        assertEquals(listOf(OMA_TAB), chatTabs(emptyList()))
        assertEquals("Oma", tabTitle(OMA_TAB, threads)); assertEquals("Pond", tabTitle("mac:t-pond", threads)); assertEquals("Phone", tabTitle(PHONE_THREAD, threads))
    }

    @Test fun anIntentOpensItsThreadsTabAndAnythingElseOpensOma() {
        assertEquals(PHONE_THREAD, openTab("phone", threads))
        assertEquals("mac:t-pond", openTab("mac:t-pond", threads))
        // The dock, voice and a notification name no thread.
        assertEquals(OMA_TAB, openTab(null, threads))
        for (unknown in listOf("mac:t-gone", "t-pond", "oma", "", "Phone", "mac:")) assertEquals(unknown, OMA_TAB, openTab(unknown, threads))
        // A thread the Mac has not listed yet opens when it is heard of, and until then it is Oma.
        assertEquals(OMA_TAB, openTab("mac:t-pond", listOf(phone)))
    }

    @Test fun aDotOpensItsThreadAndTheOpenOneGoesBackToOma() {
        assertEquals("mac:t-ff4", dotTap(OMA_TAB, "mac:t-ff4"))
        assertEquals(PHONE_THREAD, dotTap("mac:t-ff4", PHONE_THREAD))
        assertEquals(OMA_TAB, dotTap("mac:t-ff4", "mac:t-ff4"))
    }

    @Test fun aTabShowsItsOwnEntriesOldestFirstAndOmaShowsThemAll() {
        assertEquals(tasks, tabEntries(tasks, OMA_TAB))
        assertEquals(listOf(built, waiting), tabEntries(tasks, PHONE_THREAD))
        assertEquals(listOf(toFf4), tabEntries(tasks, "mac:t-ff4"))
        assertEquals(listOf(toPond), tabEntries(tasks, "mac:t-pond"))
        assertEquals(emptyList<ChatTask>(), tabEntries(tasks, "mac:t-none"))
        assertEquals(emptyList<ChatTask>(), tabEntries(tasks, "nonsense"))
        // What the front agent answered or still holds is nobody's but Oma's.
        for (tab in chatTabs(threads) - OMA_TAB) assertTrue(tabEntries(tasks, tab).none { it.quick || it.undecided })
        // Sent straight to a thread, an entry is in the Oma stream too, with its receipt.
        assertTrue(toPond in tabEntries(tasks, OMA_TAB)); assertEquals("→ Pond", toPond.agent)
    }

    @Test fun aReceiptOpensItsThreadsTab() {
        assertEquals("mac:t-ff4", receiptTab(toFf4))
        for (other in listOf(built, waiting, answered, reading)) assertNull(receiptTab(other))
    }

    @Test fun theFieldTalksStraightToTheOpenTabsThread() {
        assertEquals(SendTo.Oma, sendTo(OMA_TAB, threads))
        assertEquals(SendTo.Phone, sendTo(PHONE_THREAD, threads))
        assertEquals(SendTo.Mac("t-pond", "Pond"), sendTo("mac:t-pond", threads))
        // A tab that is not there talks to Oma, never to a thread guessed from its key.
        assertEquals(SendTo.Oma, sendTo("mac:t-gone", threads))
        assertNull(straightLine(SendTo.Oma)); assertNull(fieldHint(SendTo.Oma))
        assertEquals("straight to Pond · Oma is not asked", straightLine(SendTo.Mac("t-pond", "Pond")))
        assertEquals("to Pond…", fieldHint(SendTo.Mac("t-pond", "Pond")))
        assertEquals("straight to Phone · Oma is not asked", straightLine(SendTo.Phone))
        assertEquals("to Phone…", fieldHint(SendTo.Phone))
    }

    @Test fun theFieldsStopIsTheBuildersAndNeverShowsOnAMacTab() {
        assertTrue(fieldStop(SendTo.Oma, working = true, deciding = false)); assertTrue(fieldStop(SendTo.Oma, working = false, deciding = true))
        assertFalse(fieldStop(SendTo.Oma, working = false, deciding = false))
        assertTrue(fieldStop(SendTo.Phone, working = true, deciding = true)); assertFalse(fieldStop(SendTo.Phone, working = false, deciding = true))
        for (working in listOf(true, false)) for (deciding in listOf(true, false)) assertFalse(fieldStop(SendTo.Mac("t", "T"), working, deciding))
    }
}
