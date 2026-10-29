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

    @Test fun bundledMailBecomesOneRowAndSurvivesReload() {
        val queue = FollowUpQueue()
        val pending = (1L..4L).map { time ->
            queue.add(FollowUp(time, "update $time", mailSender = "fictional", mailId = "m$time"))
            ChatTask(time, "Codex", "update $time", status = TaskStatus.Queued, mail = "Agent mail", builder = "B")
        }
        assertEquals(4, tabEntries(pending, PHONE_THREAD_B).size)
        val next = queue.after(TaskStatus.Done).next!!
        val history = bundledMailHistory(pending, next)
        val reloaded = ChatHistory.decode(ChatHistory.encode(history))
        for (tab in listOf(OMA_TAB, PHONE_THREAD_B)) {
            val visible = tabEntries(reloaded, tab)
            assertEquals(1, visible.size)
            assertTrue((1..4).all { "update $it" in visible.single().prompt })
            assertEquals("Agent mail · 4 messages bundled", visible.single().mail)
        }
        assertEquals(4, reloaded.size) // Receipts and original text are retained.
    }

    @Test fun oldBundledReceiptsDisappearButOwnerAndUnsentMailRemain() {
        val old = ChatTask(10, "Codex", "old update", status = TaskStatus.Done,
            mail = "Agent mail", result = "Delivered in the bundled agent handoff.")
        val owner = old.copy(time = 11, mail = "")
        val unsent = old.copy(time = 12, status = TaskStatus.Queued)
        assertEquals(listOf(owner, unsent), tabEntries(listOf(old, owner, unsent), OMA_TAB))
    }

    @Test fun omaComesFirstThenTheThreadsInTheirOrder() {
        assertEquals(listOf(OMA_TAB, PHONE_THREAD, "mac:t-ff4", "mac:t-pond"), chatTabs(threads))
        assertEquals(listOf(OMA_TAB), chatTabs(emptyList()))
        assertEquals("Soma", tabTitle(OMA_TAB, threads)); assertEquals("Pond", tabTitle("mac:t-pond", threads)); assertEquals("Phone", tabTitle(PHONE_THREAD, threads))
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
    /** A chat a builder took in an earlier version: it loads unbound, with its entries still its own. */
    private val chatA = NewChat("c1", 10)
    private val chatMac = NewChat("c2", 11, mac = "t-new")
    private val chatNew = NewChat("c3", 12)
    private val book = TabMeta(chats = listOf(chatA, chatMac, chatNew))
    private fun chatThread(chat: NewChat, name: String = NEW_CHAT_NAME, state: ThreadState = ThreadState.Idle) = AgentThread(chat.key, name, state, "", "")

    @Test fun liveThreadsUseActionPriorityAndPinsKeepMembership() {
        // The live set (liveThreads, AgentTilesTest) has the archived ones out already.
        val live = listOf(pond, phone)
        assertEquals(listOf(OMA_TAB, "mac:t-pond", PHONE_THREAD), chatTabs(shownThreads(live, pinned = listOf("mac:t-pond"))))
        assertEquals(listOf(pond, ff4, phone), shownThreads(threads, emptyList()))
        // A pin of a tab that is not shown changes nothing of the others' order.
        assertEquals(live, shownThreads(live, listOf("mac:t-ff4")))
    }

    /** A thread past its 12 hours has no dot, but its tab still opens from a receipt or a tile he keeps on another page. */
    @Test fun theTabAskedForIsShownWhileItIsOpenThoughItsThreadRested() {
        val live = listOf(pond, phone)
        assertEquals(listOf(pond, ff4, phone), shownThreads(live, emptyList(), threads, emptySet(), wanted = "mac:t-ff4"))
        assertEquals("mac:t-ff4", openTab("mac:t-ff4", shownThreads(live, emptyList(), threads, emptySet(), wanted = "mac:t-ff4")))
        // Not twice, not an archived one, and not one that is no thread's.
        assertEquals(live, shownThreads(live, emptyList(), threads, emptySet(), wanted = "mac:t-pond"))
        assertEquals(live, shownThreads(live, emptyList(), threads, setOf("mac:t-ff4"), wanted = "mac:t-ff4"))
        assertEquals(live, shownThreads(live, emptyList(), threads, emptySet(), wanted = "mac:t-gone"))
        assertEquals(live, shownThreads(live, emptyList(), threads, emptySet(), wanted = null))
        // A new chat ＋ just opened has said nothing, so it is not live, and shows because it is open.
        val fresh = chatThread(chatNew)
        assertEquals(listOf(phone, fresh), shownThreads(listOf(phone), emptyList(), listOf(phone, fresh), emptySet(), wanted = chatNew.key))
    }

    /** Boss, 2026-10-21: Phone B's tab always on, to be written to while idle; a builder that rested past its hours stays. */
    @Test fun theBuildersTabsAreAlwaysShown() {
        val b = AgentThread(dev.omalauncher.assist.PHONE_THREAD_B, "Phone B", ThreadState.Idle, "", "idle")
        assertEquals(listOf(pond, phone, b), shownThreads(listOf(pond), emptyList(), listOf(phone, b, pond)))
        assertEquals(listOf(pond, phone, b), shownThreads(listOf(phone, pond), emptyList(), listOf(phone, b, pond)))
    }

    /** The critic's case: after ＋ the new tab showed as "NEW C…" and the ＋ was off the strip. */
    @Test fun theStripScrollsTheOpenTabWhollyIntoViewAndNoFurther() {
        // In view already: it stays where it is.
        assertEquals(40, stripScroll(now = 40, view = 300, start = 100, end = 200))
        // Past the right edge: just enough to bring its end in.
        assertEquals(150, stripScroll(now = 0, view = 300, start = 380, end = 450))
        // Before the left edge: its start at the edge.
        assertEquals(20, stripScroll(now = 200, view = 300, start = 20, end = 90))
        // Wider than the strip: its start first.
        assertEquals(500, stripScroll(now = 0, view = 300, start = 500, end = 900))
        val tabs = listOf(OMA_TAB, PHONE_THREAD, chatNew.key)
        val spans = mapOf(OMA_TAB to (0 to 60), PHONE_THREAD to (60 to 140), chatNew.key to (140 to 260), NEW_TAB to (260 to 300))
        // The last tab, a chat just started, takes the ＋ beside it into view too; any other tab only itself.
        assertEquals(140 to 300, stripSpan(chatNew.key, tabs, spans))
        assertEquals(60 to 140, stripSpan(PHONE_THREAD, tabs, spans))
        assertEquals(60, stripScroll(now = 0, view = 240, start = 140, end = 300))
        // Not laid out yet: nothing to scroll to.
        assertNull(stripSpan("mac:t-ff4", tabs, spans))
        assertEquals(140 to 260, stripSpan(chatNew.key, tabs, spans - NEW_TAB))
    }

    @Test fun backStepsOutOfAMenuPageThenClosesTheMenu() {
        assertEquals(MenuPage.Rows, menuBack(MenuPage.Rename))
        assertEquals(MenuPage.Rows, menuBack(MenuPage.Archived))
        assertNull(menuBack(MenuPage.Rows))
    }

    @Test fun theArchivedListsCaptionIsShort() {
        assertEquals("Archived (1) · tap to restore", archivedCaption(1))
        // What a menu's caption holds on one line at its narrowest.
        assertTrue(archivedCaption(99).length <= 30)
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

    @Test fun aNewChatsFieldGoesFirstToOmaThenToTheMacThreadMadeForIt() {
        val shown = threads + listOf(chatThread(chatA, "Worktrees"), chatThread(chatMac, "Menu fix"), chatThread(chatNew))
        assertEquals(SendTo.Chat("c3", NEW_CHAT_NAME), sendTo(chatNew.key, shown, book))
        // A chat a builder took in an earlier version goes to Oma first, as any new chat does; the builder's tab is its own.
        assertEquals(SendTo.Chat("c1", "Worktrees"), sendTo(chatA.key, shown, book))
        assertEquals(SendTo.Mac("t-new", "Menu fix", "c2"), sendTo(chatMac.key, shown, book))
        assertEquals(SendTo.Phone, sendTo(PHONE_THREAD, shown, book))
        // While its words wait for a new thread on the Mac it says so.
        val waitsForThread = ChatTask(31, "→ FF4 2", "x", thread = "t-ff4", threadName = "FF4 2", fresh = true, status = TaskStatus.Queued, chat = "c3")
        assertEquals(SendTo.ChatStarting("c3", NEW_CHAT_NAME), sendTo(chatNew.key, shown, book, listOf(waitsForThread)))
        assertEquals(SendTo.Chat("c3", NEW_CHAT_NAME), sendTo(chatNew.key, shown, book, listOf(waitsForThread.copy(status = TaskStatus.Dropped))))
        // A chat an earlier version left asking which project is a new chat's again: its next words start it on the Mac.
        val question = ChatTask(32, QUICK_AGENT, "fix the menu", result = "Which project on the Mac? ff4, pond", status = TaskStatus.Done, chat = "c3")
        val wasAsking = tabMetaFrom(tabMetaText(book).replace("\"id\":\"c3\"", "\"id\":\"c3\",\"asking\":32"))
        assertEquals(book, wasAsking)
        assertEquals(SendTo.Chat("c3", NEW_CHAT_NAME), sendTo(chatNew.key, shown, wasAsking, listOf(question)))
        // Who it goes to.
        assertNull(SendTo.Chat("c3", "N").builder); assertEquals(BuilderId.A, SendTo.Phone.builder)
        assertEquals("c2", SendTo.Mac("t-new", "M", "c2").chat); assertEquals("", SendTo.Phone.chat); assertEquals("c3", SendTo.ChatStarting("c3", "N").chat)
    }

    @Test fun theFieldsHintAndStopOnANewChat() {
        assertEquals("start this chat on the Mac…", fieldHint(SendTo.Chat("c", "N")))
        assertEquals("starting a new thread on the Mac…", fieldHint(SendTo.ChatStarting("c", "N")))
        assertTrue(fieldStop(SendTo.Chat("c", "N"), working = false, deciding = true)); assertFalse(fieldStop(SendTo.Chat("c", "N"), working = true, deciding = false))
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
        assertEquals(TabStop.Mac("t-ff4"), tabStop("mac:t-ff4", ff4, TabMeta()))
        assertEquals(TabStop.Builder(BuilderId.A), tabStop(PHONE_THREAD, phone.copy(state = working), TabMeta()))
        assertEquals(TabStop.Builder(BuilderId.A), tabStop(PHONE_THREAD, phone.copy(state = working), book))
        assertEquals(TabStop.Mac("t-new"), tabStop(chatMac.key, chatThread(chatMac, state = ThreadState.NeedsOk), book))
        // Resting, needing attention, or a chat whose first words have gone nowhere: nothing to stop.
        assertNull(tabStop(PHONE_THREAD, phone, TabMeta()))
        assertNull(tabStop("mac:t-ff4", ff4.copy(state = ThreadState.Attention), TabMeta()))
        assertNull(tabStop(chatA.key, chatThread(chatA, state = working), book))
        assertNull(tabStop(chatNew.key, chatThread(chatNew, state = working), book))
        assertNull(tabStop("mac:t-gone", null, TabMeta()))
    }

    @Test fun copyTakesTheNewestFinishedAnswer() {
        val first = ChatTask(1, QUICK_AGENT, "a", result = " one ", status = TaskStatus.Done)
        val failed = ChatTask(2, "Claude Code", "b", result = "error", status = TaskStatus.Failed)
        val running = ChatTask(3, "Claude Code", "c", result = "half", status = TaskStatus.Running)
        assertEquals("one", lastAnswer(listOf(first, failed, running)))
        assertNull(lastAnswer(listOf(failed, running)))
        assertNull(lastAnswer(emptyList()))
    }

    /** The Critic's case (2026-10-14): words typed in a ＋ chat stayed in the field on the Oma tab and could be sent from there. */
    @Test fun eachTabKeepsItsOwnDraft() {
        val typed = withDraft(emptyMap(), "chat:c1", "half a thought")
        assertEquals("", typed[OMA_TAB].orEmpty())
        val both = withDraft(typed, OMA_TAB, "for oma")
        assertEquals(mapOf("chat:c1" to "half a thought", OMA_TAB to "for oma"), both)
        // Sent or cleared, a tab keeps nothing, and the other tab's words are untouched.
        assertEquals(mapOf(OMA_TAB to "for oma"), withDraft(both, "chat:c1", ""))
    }

    /** OPEN-BUGS #3, #17: `oma-phone fill` said ok and the field never kept the words; the draft drawn next overwrote them. */
    @Test fun wordsSetFromOutsideBecomeTheOpenTabsDraft() {
        val drafts = mapOf("chat:c1" to "half a thought")
        assertEquals(mapOf("chat:c1" to "half a thought", "mac:t-ff4" to "say hi"), setDraft(drafts, "mac:t-ff4", "say hi", listening = false))
        // Setting replaces what was there, as the field shows it; set empty, the tab keeps nothing.
        assertEquals(mapOf("chat:c1" to "a whole thought"), setDraft(drafts, "chat:c1", "a whole thought", listening = false))
        assertEquals(emptyMap<String, String>(), setDraft(drafts, "chat:c1", "", listening = false))
        // While he speaks the field shows his speech and takes nothing else, and says so.
        assertNull(setDraft(drafts, "chat:c1", "x", listening = true))
    }

    /** OPEN-BUGS #16: a thread named by an intent opens its tab, whether the chat starts or is already open. */
    @Test fun anIntentOpensTheTabOfTheThreadItNames() {
        assertEquals("mac:t-ff4", askedTab("mac:t-ff4", book))
        assertEquals(PHONE_THREAD, askedTab(PHONE_THREAD, book))
        // A Mac thread a new chat lives on is shown as that chat's tab.
        assertEquals(chatMac.key, askedTab(macThreadKey("t-new"), book))
        assertEquals(chatMac.key, openTab(askedTab(macThreadKey("t-new"), book), listOf(chatThread(chatMac))))
        // Naming none, or nothing, opens Oma.
        assertNull(askedTab(null, book)); assertNull(askedTab("  ", book))
        assertEquals(OMA_TAB, openTab(askedTab(null, book), threads))
    }

    /** Boss, 2026-10-20: the chat widget opens where the last turn was, not always on Soma. */
    @Test fun withNoThreadNamedTheChatOpensWhereTheLastTurnWas() {
        val builderA = ChatTask(1, "Claude Code", "make a lock screen", result = "Done.", status = TaskStatus.Done)
        val builderB = ChatTask(2, "Claude Code", "look at this", result = "Done.", status = TaskStatus.Done, builder = "B")
        val front = ChatTask(3, QUICK_AGENT, "what time is it", result = "2 AM.", status = TaskStatus.Done)
        val onMac = ChatTask(4, "→ FF4", "build it", thread = "t-ff4", threadName = "FF4")
        val waiting = ChatTask(5, UNDECIDED_AGENT, "and then?")
        assertEquals(PHONE_THREAD, lastTurnTab(listOf(builderA), book))
        assertEquals(PHONE_THREAD_B, lastTurnTab(listOf(builderA, builderB), book))
        assertEquals("mac:t-ff4", lastTurnTab(listOf(builderA, onMac), book))
        // A request still being read doesn't count: the turn before it does.
        assertEquals(PHONE_THREAD, lastTurnTab(listOf(builderA, waiting), book))
        // The front agent's own answer, or nothing at all, opens Soma.
        assertNull(lastTurnTab(listOf(builderA, front), book))
        assertNull(lastTurnTab(emptyList(), book))
    }
}
