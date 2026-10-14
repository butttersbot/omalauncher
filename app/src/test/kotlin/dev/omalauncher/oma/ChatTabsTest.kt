package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PHONE_THREAD_B
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
        assertNull(fieldHint(SendTo.Oma))
        assertEquals("to Pond…", fieldHint(SendTo.Mac("t-pond", "Pond")))
        assertEquals("to Phone…", fieldHint(SendTo.Phone))
    }

    @Test fun theFieldsStopIsTheBuildersAndNeverShowsOnAMacTab() {
        assertTrue(fieldStop(SendTo.Oma, working = true, deciding = false)); assertTrue(fieldStop(SendTo.Oma, working = false, deciding = true))
        assertFalse(fieldStop(SendTo.Oma, working = false, deciding = false))
        assertTrue(fieldStop(SendTo.Phone, working = true, deciding = true)); assertFalse(fieldStop(SendTo.Phone, working = false, deciding = true))
        for (working in listOf(true, false)) for (deciding in listOf(true, false)) assertFalse(fieldStop(SendTo.Mac("t", "T"), working, deciding))
    }

    /** Boss 2026-10-13: two builders. Builder B has a tab of its own once it has worked, and its field talks to B alone. */
    @Test fun builderBsTabShowsItsEntriesAndTalksToItAlone() {
        val phoneB = AgentThread(PHONE_THREAD_B, "Phone B", ThreadState.Working, "Editing", "working")
        val both = listOf(phone.copy(name = "Phone A"), phoneB, ff4)
        val toB = ChatTask(8, "Claude Code", "fix the dock", builder = "B")
        val all = tasks + toB
        assertEquals(listOf(OMA_TAB, PHONE_THREAD, PHONE_THREAD_B, "mac:t-ff4"), chatTabs(both))
        assertEquals(PHONE_THREAD_B, openTab("phone-b", both)); assertEquals(OMA_TAB, openTab("phone-b", threads))
        assertEquals(listOf(toB), tabEntries(all, PHONE_THREAD_B))
        // A's tab keeps A's entries alone, and the Oma tab has them all.
        assertEquals(listOf(built, waiting), tabEntries(all, PHONE_THREAD))
        assertEquals(all, tabEntries(all, OMA_TAB))
        assertEquals(SendTo.PhoneB, sendTo(PHONE_THREAD_B, both)); assertEquals(SendTo.Phone, sendTo(PHONE_THREAD, both))
        assertEquals(BuilderId.B, SendTo.PhoneB.builder); assertEquals(BuilderId.A, SendTo.Phone.builder)
        assertNull(SendTo.Oma.builder); assertNull(SendTo.Mac("t", "T").builder)
        assertEquals("to Phone B…", fieldHint(SendTo.PhoneB))
        assertTrue(fieldStop(SendTo.PhoneB, working = true, deciding = false)); assertFalse(fieldStop(SendTo.PhoneB, working = false, deciding = true))
        assertEquals("Phone B", tabTitle(PHONE_THREAD_B, both))
    }

    // New chats, pins, archive and the long-press menu (Boss, 2026-10-13).
    private val chatA = NewChat("c1", 10, builder = BuilderId.A)
    private val chatMac = NewChat("c2", 11, mac = "t-new")
    private val chatNew = NewChat("c3", 12)
    private val book = TabMeta(chats = listOf(chatA, chatMac, chatNew))
    private fun chatThread(chat: NewChat, name: String = NEW_CHAT_NAME, state: ThreadState = ThreadState.Idle) = AgentThread(chat.key, name, state, "", "")

    @Test fun archivedTabsAreHiddenAndPinnedOnesComeFirstAfterOma() {
        val shown = shownThreads(threads, pinned = listOf("mac:t-pond"), archived = setOf("mac:t-ff4"))
        assertEquals(listOf(OMA_TAB, "mac:t-pond", PHONE_THREAD), chatTabs(shown))
        assertEquals(threads, shownThreads(threads, emptyList(), emptySet()))
        // A pin of a tab that is not shown changes nothing of the others' order.
        assertEquals(listOf(phone, pond), shownThreads(threads, listOf("mac:t-ff4"), setOf("mac:t-ff4")))
    }

    @Test fun aNewChatsTabShowsWhatWasSaidInItAndNoOtherTabTakesItsEntries() {
        val quickInChat = ChatTask(20, QUICK_AGENT, "what is a worktree?", result = "a second checkout", status = TaskStatus.Done, chat = "c1")
        val builtInChat = ChatTask(21, "Claude Code", "make one", status = TaskStatus.Done, chat = "c1")
        val onNewThread = ChatTask(22, "→ FF4 2", "go", thread = "t-new", threadName = "FF4 2", chat = "c2")
        val fromOmaToNewThread = ChatTask(23, "→ FF4 2", "and this", thread = "t-new", threadName = "FF4 2")
        val waitingForThread = ChatTask(24, "→ FF4 3", "other", thread = "t-ff4", threadName = "FF4 3", fresh = true, status = TaskStatus.Queued, chat = "c3")
        val all = tasks + listOf(quickInChat, builtInChat, onNewThread, fromOmaToNewThread, waitingForThread)
        assertEquals(listOf(quickInChat, builtInChat), tabEntries(all, chatA.key, book))
        assertEquals(listOf(onNewThread, fromOmaToNewThread), tabEntries(all, chatMac.key, book))
        assertEquals(listOf(waitingForThread), tabEntries(all, chatNew.key, book))
        // Builder A's own tab keeps its own conversation, and the busy thread's tab does not take a new chat's words.
        assertEquals(listOf(built, waiting), tabEntries(all, PHONE_THREAD, book))
        assertEquals(listOf(toFf4), tabEntries(all, "mac:t-ff4", book))
        assertEquals(all, tabEntries(all, OMA_TAB, book))
        // Its receipt opens the chat, and so does one of the thread made for it sent from the Oma tab.
        assertEquals(chatMac.key, receiptTab(onNewThread, book)); assertEquals(chatMac.key, receiptTab(fromOmaToNewThread, book))
        assertEquals("mac:t-ff4", receiptTab(toFf4, book))
        assertEquals("t-new", tabMacThread(chatMac.key, book)); assertEquals("t-ff4", tabMacThread("mac:t-ff4", book))
        assertNull(tabMacThread(chatA.key, book)); assertNull(tabMacThread(chatNew.key, book)); assertNull(tabMacThread(OMA_TAB, book))
    }

    @Test fun aNewChatsFieldGoesFirstToOmaThenToTheSessionItIsBoundTo() {
        val shown = threads + listOf(chatThread(chatA, "Worktrees"), chatThread(chatMac, "Menu fix"), chatThread(chatNew))
        val serving = mapOf(BuilderId.A to "c1")
        assertEquals(SendTo.Chat("c3", NEW_CHAT_NAME), sendTo(chatNew.key, shown, book, serving))
        assertEquals(SendTo.ChatOn("c1", "Worktrees", BuilderId.A), sendTo(chatA.key, shown, book, serving))
        assertEquals(SendTo.Mac("t-new", "Menu fix", "c2"), sendTo(chatMac.key, shown, book, serving))
        // Builder A went on to the new chat: its own tab has ended, and so does the chat once A goes on again.
        assertEquals(SendTo.Ended("Phone"), sendTo(PHONE_THREAD, shown, book, serving))
        assertEquals(SendTo.Ended("Worktrees"), sendTo(chatA.key, shown, book, mapOf(BuilderId.A to "c9")))
        assertEquals(SendTo.Phone, sendTo(PHONE_THREAD, shown, book))
        // While its first words wait for a fresh session they say so, on the phone or on the Mac.
        val waitsForBuilder = ChatTask(30, "Claude Code", "x", fresh = true, status = TaskStatus.Queued, chat = "c3")
        val waitsForThread = ChatTask(31, "→ FF4 2", "x", thread = "t-ff4", threadName = "FF4 2", fresh = true, status = TaskStatus.Queued, chat = "c3")
        assertEquals(SendTo.ChatStarting("c3", NEW_CHAT_NAME, mac = false), sendTo(chatNew.key, shown, book, tasks = listOf(waitsForBuilder)))
        assertEquals(SendTo.ChatStarting("c3", NEW_CHAT_NAME, mac = true), sendTo(chatNew.key, shown, book, tasks = listOf(waitsForThread)))
        assertEquals(SendTo.Chat("c3", NEW_CHAT_NAME), sendTo(chatNew.key, shown, book, tasks = listOf(waitsForThread.copy(status = TaskStatus.Dropped))))
        // Who it goes to.
        assertEquals(BuilderId.A, SendTo.ChatOn("c1", "W", BuilderId.A).builder); assertNull(SendTo.Chat("c3", "N").builder)
        assertEquals("c2", SendTo.Mac("t-new", "M", "c2").chat); assertEquals("", SendTo.Phone.chat); assertEquals("c3", SendTo.ChatStarting("c3", "N", false).chat)
    }

    @Test fun theFieldsHintAndStopOnANewChat() {
        assertEquals("start this chat…", fieldHint(SendTo.Chat("c", "N")))
        assertEquals("to Worktrees…", fieldHint(SendTo.ChatOn("c", "Worktrees", BuilderId.B)))
        assertEquals(ENDED_HINT, fieldHint(SendTo.Ended("Phone")))
        assertEquals("starting a new thread on the Mac…", fieldHint(SendTo.ChatStarting("c", "N", mac = true)))
        assertTrue(fieldStop(SendTo.Chat("c", "N"), working = false, deciding = true)); assertFalse(fieldStop(SendTo.Chat("c", "N"), working = true, deciding = false))
        assertTrue(fieldStop(SendTo.ChatOn("c", "N", BuilderId.A), working = true, deciding = false))
        for (working in listOf(true, false)) for (deciding in listOf(true, false)) assertFalse(fieldStop(SendTo.Ended("Phone"), working, deciding))
    }

    @Test fun theMenuOffersWhatMakesSenseAndArchivedIsAlwaysLast() {
        assertEquals(
            listOf(TabAction.Rename, TabAction.Pin, TabAction.Stop, TabAction.Copy, TabAction.Archive, TabAction.Archived),
            tabMenu("mac:t-ff4", pinned = false, stoppable = true, answered = true, archived = 2),
        )
        assertEquals(listOf(TabAction.Rename, TabAction.Unpin, TabAction.Archive), tabMenu(PHONE_THREAD, pinned = true, stoppable = false, answered = false, archived = 0))
        // The Oma tab: nothing that renames, pins or puts it away.
        assertEquals(listOf(TabAction.Copy, TabAction.Archived), tabMenu(OMA_TAB, pinned = false, stoppable = true, answered = true, archived = 1))
        assertEquals(emptyList<TabAction>(), tabMenu(OMA_TAB, pinned = false, stoppable = true, answered = false, archived = 0))
        assertEquals("Archived (3)", archivedRow(3))
        // No row of any menu deletes, and none answers an approval.
        assertTrue(TabAction.entries.none { "delete" in it.label.lowercase() || "approve" in it.label.lowercase() || "deny" in it.label.lowercase() })
    }

    @Test fun stopInTheMenuIsTheTabsOwnTaskAndOnlyWhileItHasOne() {
        val working = ThreadState.Working
        val serving = mapOf(BuilderId.A to "c1")
        assertEquals(TabStop.Mac("t-ff4"), tabStop("mac:t-ff4", ff4, TabMeta(), emptyMap()))
        assertEquals(TabStop.Builder(BuilderId.A), tabStop(PHONE_THREAD, phone.copy(state = working), TabMeta(), emptyMap()))
        assertEquals(TabStop.Builder(BuilderId.A), tabStop(chatA.key, chatThread(chatA, state = working), book, serving))
        assertEquals(TabStop.Mac("t-new"), tabStop(chatMac.key, chatThread(chatMac, state = ThreadState.NeedsOk), book, serving))
        // Resting, needing attention, ended, or a chat whose first words have gone nowhere: nothing to stop.
        assertNull(tabStop(PHONE_THREAD, phone, TabMeta(), emptyMap()))
        assertNull(tabStop("mac:t-ff4", ff4.copy(state = ThreadState.Attention), TabMeta(), emptyMap()))
        assertNull(tabStop(PHONE_THREAD, phone.copy(state = working), book, serving))
        assertNull(tabStop(chatA.key, chatThread(chatA, state = working), book, mapOf(BuilderId.A to "c9")))
        assertNull(tabStop(chatNew.key, chatThread(chatNew, state = working), book, serving))
        assertNull(tabStop("mac:t-gone", null, TabMeta(), emptyMap()))
    }

    @Test fun copyTakesTheNewestFinishedAnswer() {
        val first = ChatTask(1, QUICK_AGENT, "a", result = " one ", status = TaskStatus.Done)
        val failed = ChatTask(2, "Claude Code", "b", result = "error", status = TaskStatus.Failed)
        val running = ChatTask(3, "Claude Code", "c", result = "half", status = TaskStatus.Running)
        assertEquals("one", lastAnswer(listOf(first, failed, running)))
        assertNull(lastAnswer(listOf(failed, running)))
        assertNull(lastAnswer(emptyList()))
    }
}
