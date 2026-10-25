package dev.omalauncher.assist

import dev.omalauncher.oma.ChatLater
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.NewChat
import dev.omalauncher.oma.NEW_CHAT_NAME
import org.junit.Assert.*
import org.junit.Test

/**
 * Every thread Boss is shown, as the chat's dots and home's tiles read it (Boss, 2026-10-13): how each stands,
 * what it says it is doing, and the order they come in. Nothing here reaches the Mac or the builder.
 */
class AgentThreadsTest {
    private val id = "t5e121ba2"
    private val write = MacApproval("a1", "n-secret", "Write", """{"file_path":"/Users/boss/ClaudeSync/projects/pond/stickers/frog-pack/manifest.json","content":"SECRET BODY"}""", "why")

    private fun thread(
        status: MacThreadStatus = MacThreadStatus.Idle, threadId: String = id, name: String = "FF4 better menu mod", summary: String = "", tail: String = "",
        lastWorked: Long? = null, waitingTurns: Int = 0, pending: List<MacApproval> = emptyList(), background: List<String> = emptyList(),
    ) = MacThread(threadId, name, "/p", ThreadHome.Mac, "claude", summary, status, lastWorked, tail, waitingTurns, 0, pending, background)

    private fun build(time: Long, status: TaskStatus, prompt: String = "prompt $time", steps: List<String> = emptyList(), result: String = "") =
        ChatTask(time, "Claude Code", prompt, steps = steps, result = result, status = status)

    private fun mac(time: Long, status: TaskStatus, prompt: String = "prompt $time", steps: List<String> = emptyList(), result: String = "", threadId: String = id) =
        ChatTask(time, "→ FF4", prompt, steps = steps, result = result, status = status, thread = threadId, threadName = "FF4")

    @Test fun aKeyNamesTheBuilderOrOneMacThreadAndNeverBoth() {
        assertEquals("mac:t5e121ba2", macThreadKey(id))
        assertEquals(id, macThreadId(macThreadKey(id)))
        // A thread the Mac chose to call "phone" is still a Mac thread.
        assertEquals("phone", macThreadId(macThreadKey(PHONE_THREAD)))
        assertNotEquals(PHONE_THREAD, macThreadKey(PHONE_THREAD))
        for (other in listOf(PHONE_THREAD, "oma", "", "mac:", "thread:mac:x")) assertNull(other, macThreadId(other))
    }

    @Test fun anApprovalIsKnownByItsToolAndTargetAndNothingElse() {
        assertEquals("stickers/frog-pack/manifest.json", askTarget(write.input))
        assertEquals("a.txt", askTarget("""{"path":"a.txt"}"""))
        // Of a command only the program: its arguments are the approval's content.
        assertEquals("rm", askTarget("""{"command":"  /bin/rm -rf ~/secret-folder"}"""))
        assertEquals("example.com", askTarget("""{"url":"https://example.com/private?token=abc"}"""))
        assertEquals("", askTarget("""{"query":"something private"}"""))
        assertEquals("", askTarget("not json"))
        assertEquals("Wants to write stickers/frog-pack/manifest.json", wantsLine(ThreadAsk("Write", askTarget(write.input))))
        assertEquals("Wants to run git", wantsLine(ThreadAsk("Bash", "git")))
        assertEquals("Wants to edit a.kt", wantsLine(ThreadAsk("Edit", "a.kt")))
        assertEquals("Wants to use WebSearch", wantsLine(ThreadAsk("WebSearch", "")))
        assertEquals("Wants to use a tool", wantsLine(ThreadAsk("", "")))
        val shown = macThreadNow(thread(MacThreadStatus.Waiting, pending = listOf(write)), id, emptyList(), emptyList(), reachable = true)
        assertFalse(shown.toString().contains("SECRET")); assertFalse(shown.toString().contains("n-secret"))
    }

    @Test fun theBuilderWorksQueuesWaitsFailsAndRests() {
        val idle = phoneThread(emptyList(), BuilderNow())
        assertEquals(AgentThread(PHONE_THREAD, "Phone", ThreadState.Idle, "", "idle", null), idle)
        assertFalse(idle.mac)

        val running = build(10, TaskStatus.Running, "add thread tiles", steps = listOf("Edit: ui/AgentTile.kt", "Bash: oma-build"))
        val working = phoneThread(listOf(running), BuilderNow(working = true, label = "Using Bash"))
        assertEquals(ThreadState.Working, working.state); assertEquals("Bash: oma-build", working.doing); assertEquals("working", working.label)
        // Its turn is counted from when the builder began it, which a request that waited in the queue was sent before.
        assertEquals(10L, working.since)
        assertEquals(15L, phoneThread(listOf(running), BuilderNow(working = true, since = 15L)).since)
        // Before it has reported a step, the builder's own label stands.
        assertEquals("Using Bash", phoneThread(listOf(running.copy(steps = emptyList())), BuilderNow(true, "Using Bash")).doing)

        val queued = phoneThread(listOf(running, build(11, TaskStatus.Queued, "make the clock tile bigger\nplease"), build(12, TaskStatus.Queued)), BuilderNow(true, "Using Bash"))
        assertEquals(ThreadState.Queued, queued.state); assertEquals("2 queued", queued.label)
        assertEquals("Bash: oma-build · then: “make the clock tile bigger”", queued.doing)
        assertEquals(12L, queued.worked)

        val asking = phoneThread(listOf(running, build(11, TaskStatus.Queued)), BuilderNow(true, "Approval needed", listOf(ThreadAsk("Bash", "oma-build"))))
        assertEquals(ThreadState.NeedsOk, asking.state); assertEquals("Wants to run oma-build", asking.doing); assertEquals(NEEDS_OK, asking.label)

        val failed = phoneThread(listOf(build(10, TaskStatus.Failed, result = "The agent exited with status 1.")), BuilderNow(label = "Agent needs attention"))
        assertEquals(ThreadState.Attention, failed.state); assertEquals("The agent exited with status 1.", failed.doing); assertEquals(NEEDS_ATTENTION, failed.label)

        val done = phoneThread(listOf(build(10, TaskStatus.Done, result = "**Done.** The clock is bigger.")), BuilderNow(label = "Done"))
        assertEquals(ThreadState.Idle, done.state); assertEquals("Done. The clock is bigger.", done.doing); assertEquals("done", done.label)
        assertNull(done.since)
        assertEquals("stopped", phoneThread(listOf(build(10, TaskStatus.Stopped)), BuilderNow()).label)
        // Closing the app under a task is not his to mend: it reads as interrupted and lets the tile grow old.
        val cut = phoneThread(listOf(build(10, TaskStatus.Interrupted)), BuilderNow())
        assertEquals(ThreadState.Idle, cut.state); assertEquals("interrupted", cut.label)
    }

    @Test fun onlyTheBuildLanesEntriesAreTheBuilders() {
        val tasks = listOf(ChatTask(1, QUICK_AGENT, "what is 15% of 240?", result = "36", status = TaskStatus.Failed), mac(2, TaskStatus.Failed))
        val phone = phoneThread(tasks, BuilderNow())
        assertEquals(ThreadState.Idle, phone.state); assertNull(phone.worked); assertEquals("idle", phone.label)
    }

    @Test fun aMacThreadReadsFromTheMacsWordAndThePhonesEntries() {
        val running = mac(20, TaskStatus.Running, "make the magic menu remember the cursor too", steps = listOf("Read: src/menu/magic.c", "Edit: src/menu/menu_cursor.c"))
        val working = macThreadNow(thread(MacThreadStatus.Working, lastWorked = 5), id, listOf(running), emptyList(), reachable = true)
        // It last worked at whichever is later: what the Mac says, in seconds, or the last request the phone sent it.
        assertEquals(AgentThread("mac:$id", "FF4 better menu mod", ThreadState.Working, "Edit: src/menu/menu_cursor.c", "working", 5_000L, since = 20L), working)
        assertTrue(working.mac)
        // Its turn is counted from the request the phone sent; a turn begun at the Mac has no start the phone saw.
        assertNull(macThreadNow(thread(MacThreadStatus.Working), id, emptyList(), emptyList(), true).since)
        // Work begun at the Mac has no entry here: the Mac's summary of what it was asked stands.
        assertEquals("fix the build", macThreadNow(thread(MacThreadStatus.Working, summary = "fix the build"), id, emptyList(), emptyList(), true).doing)
        assertEquals("Working", macThreadNow(thread(MacThreadStatus.Working), id, emptyList(), emptyList(), true).doing)
        // A summary that is only the hand-over's context heading gives way to his own words.
        assertEquals("fix the menu", macThreadNow(thread(MacThreadStatus.Working, summary = CONTEXT_OPEN), id, listOf(mac(20, TaskStatus.Running, "fix the menu")), emptyList(), true).doing)

        val queued = macThreadNow(thread(MacThreadStatus.Working, waitingTurns = 1), id, listOf(running, mac(21, TaskStatus.Queued, "and the item menu")), emptyList(), true)
        assertEquals(ThreadState.Queued, queued.state); assertEquals("1 queued", queued.label)
        assertEquals("Edit: src/menu/menu_cursor.c · then: “and the item menu”", queued.doing)
        // Turns that wait on the Mac for work this phone did not send are counted, with nothing to quote.
        assertEquals("fix it · 2 waiting", macThreadNow(thread(MacThreadStatus.Working, summary = "fix it", waitingTurns = 2), id, emptyList(), emptyList(), true).doing)

        val asking = macThreadNow(thread(MacThreadStatus.Waiting, pending = listOf(write)), id, listOf(running), emptyList(), true)
        assertEquals(ThreadState.NeedsOk, asking.state); assertEquals("Wants to write stickers/frog-pack/manifest.json", asking.doing)
        // What the phone itself holds as waiting comes before the Mac's older list.
        assertEquals("Wants to run git", macThreadNow(thread(MacThreadStatus.Waiting, pending = listOf(write)), id, emptyList(), listOf(ThreadAsk("Bash", "git")), true).doing)
        assertEquals("Waiting for your OK", macThreadNow(thread(MacThreadStatus.Waiting), id, emptyList(), emptyList(), true).doing)

        val idle = macThreadNow(thread(tail = "Done — phone-test.txt created in the scratch folder.", lastWorked = 100), id, listOf(mac(20, TaskStatus.Done, result = "older")), emptyList(), true)
        assertEquals(ThreadState.Idle, idle.state); assertEquals("Done — phone-test.txt created in the scratch folder.", idle.doing); assertEquals("done", idle.label)
        assertEquals(100_000L, idle.worked)
        assertEquals("idle", macThreadNow(thread(), id, emptyList(), emptyList(), true).label)
        assertNull(macThreadNow(thread(), id, emptyList(), emptyList(), true).worked)
    }

    @Test fun aMacThreadNeedsAttentionWhenItsLastRequestFailedOrNeverArrived() {
        val failed = macThreadNow(thread(lastWorked = 1), id, listOf(mac(20, TaskStatus.Failed, result = "Failed to authenticate: OAuth session expired")), emptyList(), true)
        assertEquals(ThreadState.Attention, failed.state); assertEquals("Failed to authenticate: OAuth session expired", failed.doing); assertEquals(NEEDS_ATTENTION, failed.label)
        assertEquals(ThreadState.Attention, macThreadNow(thread(), id, listOf(mac(20, TaskStatus.Dropped, result = MAC_UNREACHABLE)), emptyList(), false).state)
        val cut = macThreadNow(thread(MacThreadStatus.Interrupted), id, emptyList(), emptyList(), true)
        assertEquals(ThreadState.Attention, cut.state); assertEquals(MAC_INTERRUPTED, cut.doing)
        // Working again, an old failure is over.
        assertEquals(ThreadState.Working, macThreadNow(thread(MacThreadStatus.Working), id, listOf(mac(20, TaskStatus.Failed), mac(21, TaskStatus.Running)), emptyList(), true).state)
    }

    /** Boss, 2026-10-14: a thread whose last step restarted the runner had finished; only something of his unfinished needs him. */
    @Test fun anInterruptedMacThreadNeedsHimOnlyWhenSomethingOfHisIsUnfinished() {
        val restarted = MacThreadStatus.Interrupted
        // Answered, then cut by the restart its own last step made: done, and it grows old as any resting thread.
        val answered = mac(20, TaskStatus.Failed, "install the new runner", result = "Runner updated; installing it now.\n\n$MAC_INTERRUPTED")
        val upkeep = macThreadNow(thread(restarted, lastWorked = 1), id, listOf(answered), emptyList(), true)
        assertEquals(ThreadState.Idle, upkeep.state); assertEquals("done", upkeep.label); assertEquals("Runner updated; installing it now.", upkeep.doing)
        assertEquals(ThreadState.Idle, macThreadNow(thread(restarted, tail = "All done."), id, listOf(mac(20, TaskStatus.Done, result = "All done.")), emptyList(), true).state)
        assertEquals("All done.", macThreadNow(thread(restarted, tail = "All done."), id, listOf(answered), emptyList(), true).doing)
        // Words said after the turn, between his messages, answer it too.
        val laterWords = mac(20, TaskStatus.Failed, result = MAC_INTERRUPTED).copy(later = listOf(ChatLater("Installed.", words = true)))
        assertEquals(ThreadState.Idle, macThreadNow(thread(restarted), id, listOf(laterWords), emptyList(), true).state)
        // A thread he only wrote to on the Mac goes by what the Mac says it said.
        assertEquals(ThreadState.Idle, macThreadNow(thread(restarted, tail = "Done."), id, emptyList(), emptyList(), true).state)

        // Unanswered: no words before the restart, still running, dropped, cut by the phone's own restart, or failed otherwise.
        for (left in listOf(
            mac(20, TaskStatus.Failed, result = MAC_INTERRUPTED),
            mac(20, TaskStatus.Running, result = "Looking at it"),
            mac(20, TaskStatus.Dropped, result = MAC_NOT_TAKEN),
            mac(20, TaskStatus.Interrupted, result = "Looking at it"),
            mac(20, TaskStatus.Failed, result = "Partly done.\n\nFailed to authenticate"),
            mac(20, TaskStatus.Stopped),
        )) {
            val cut = macThreadNow(thread(restarted, tail = "an older answer"), id, listOf(left), emptyList(), true)
            assertEquals(left.status.id, ThreadState.Attention, cut.state); assertEquals(NEEDS_ATTENTION, cut.label)
        }
        assertEquals(MAC_INTERRUPTED, macThreadNow(thread(restarted, tail = "an older answer"), id, listOf(mac(20, TaskStatus.Failed, result = MAC_INTERRUPTED)), emptyList(), true).doing)
        // Words sent from elsewhere after his last entry are his newest request: the earlier answer does not answer them.
        val elsewhere = mac(20, TaskStatus.Done, result = "Done.").copy(later = listOf(ChatLater(MAC_SENT_ELSEWHERE + "and restart it")))
        assertEquals(ThreadState.Attention, macThreadNow(thread(restarted, tail = "Done."), id, listOf(elsewhere), emptyList(), true).state)
        val elsewhereAnswered = elsewhere.copy(later = elsewhere.later + ChatLater("Restarting.", words = true))
        assertEquals(ThreadState.Idle, macThreadNow(thread(restarted, tail = "Restarting."), id, listOf(elsewhereAnswered), emptyList(), true).state)

        // Turns waiting and approvals still come first, answered or not.
        assertEquals(ThreadState.Queued, macThreadNow(thread(restarted, waitingTurns = 1), id, listOf(answered), emptyList(), true).state)
        assertEquals(ThreadState.Queued, macThreadNow(thread(restarted), id, listOf(answered, mac(21, TaskStatus.Queued, "and then this")), emptyList(), true).state)
        assertEquals(ThreadState.NeedsOk, macThreadNow(thread(restarted, pending = listOf(write)), id, listOf(answered), emptyList(), true).state)
        assertEquals(ThreadState.NeedsOk, macThreadNow(thread(restarted), id, listOf(answered), listOf(ThreadAsk("Bash", "git")), true).state)
    }

    @Test fun aThreadTheMacHasNotListedIsReadFromItsEntriesAlone() {
        val running = macThreadNow(null, id, listOf(mac(20, TaskStatus.Running, steps = listOf("Read: a.c"))), emptyList(), reachable = false)
        assertEquals("FF4", running.name); assertEquals(ThreadState.Working, running.state); assertEquals("Read: a.c", running.doing); assertFalse(running.reachable)
        val sending = macThreadNow(null, id, listOf(mac(20, TaskStatus.Queued, "fix it")), emptyList(), false)
        assertEquals(ThreadState.Queued, sending.state); assertEquals("Sending to the Mac · then: “fix it”", sending.doing)
        assertEquals(ThreadState.Idle, macThreadNow(null, id, listOf(mac(20, TaskStatus.Done, result = "ok")), emptyList(), false).state)
        assertEquals("Mac", macThreadNow(null, id, emptyList(), emptyList(), false).name)
    }

    @Test fun theBuilderComesFirstThenTheMacsThreadsNewestWorkedFirst() {
        val view = MacView(
            listOf(thread(threadId = "t-old", name = "Old", lastWorked = 10), thread(threadId = "t-new", name = "New", lastWorked = 30), thread(threadId = "t-never", name = "Never")),
            reachable = true, listed = true,
        )
        val tasks = listOf(build(1, TaskStatus.Done), mac(25_000, TaskStatus.Done, threadId = "t-gone"), mac(40_000, TaskStatus.Done, threadId = "t-old"))
        val threads = agentThreads(tasks, BuilderNow(), view, mapOf("t-new" to listOf(ThreadAsk("Bash", "git"))))
        // t-old was sent something after t-new last worked; t-gone is known only by the request once handed to it.
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B, PHONE_THREAD_C, "mac:t-old", "mac:t-new", "mac:t-gone", "mac:t-never"), threads.map { it.key })
        assertEquals(ThreadState.NeedsOk, threads[4].state)
        assertEquals("FF4", threads[5].name)
        // With no Mac at all there are the builders alone.
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B, PHONE_THREAD_C), agentThreads(listOf(build(1, TaskStatus.Done)), BuilderNow(), MacView()).map { it.key })
    }

    @Test fun anAnswersEndIsPlainWordsCutAtAWord() {
        assertEquals("", endOf("  "))
        assertEquals("Done. See menu.c", endOf("**Done.**\n\nSee `menu.c`"))
        val long = endOf("one two three four five six seven eight nine ten", chars = 20)
        assertEquals("…eight nine ten", long)
        assertTrue(endOf("x".repeat(500)).length <= TILE_DOING_CHARS + 1)
    }

    /** Boss 2026-10-13: two builders, "Phone A" and "Phone B"; since 2026-10-21 B's tab is there always, to be written to while idle. */
    @Test fun builderBIsAThreadOfItsOwnAlways() {
        val toA = build(1, TaskStatus.Done, result = "Tile made.")
        // Only A has ever worked: B is there all the same, idle.
        val idle = phoneThreads(listOf(toA), emptyMap())
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B, PHONE_THREAD_C), idle.map { it.key })
        assertEquals(listOf("Phone A", "Phone B", "Phone C"), idle.map { it.name })
        assertEquals(ThreadState.Idle, idle[1].state)
        val toB = build(2, TaskStatus.Running, "fix the dock", steps = listOf("Edit: ui/Dock.kt")).copy(builder = "B")
        val queuedB = build(3, TaskStatus.Queued, "and the folder").copy(builder = "B")
        val threads = phoneThreads(listOf(toA, toB, queuedB), mapOf(BuilderId.B to BuilderNow(working = true, label = "Using Edit")))
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B, PHONE_THREAD_C), threads.map { it.key })
        assertEquals(listOf("Phone A", "Phone B", "Phone C"), threads.map { it.name })
        // Each reads its own entries and its own state.
        assertEquals(ThreadState.Idle, threads[0].state); assertEquals("Tile made.", threads[0].doing)
        assertEquals(ThreadState.Queued, threads[1].state); assertEquals("1 queued", threads[1].label); assertEquals(3L, threads[1].worked)
        assertFalse(threads[1].mac)
        // B working with no entry yet is shown, and its approval is its own.
        val asking = phoneThreads(listOf(toA), mapOf(BuilderId.B to BuilderNow(working = true, asks = listOf(ThreadAsk("Bash", "git")))))
        assertEquals(ThreadState.Idle, asking[0].state); assertEquals(ThreadState.NeedsOk, asking[1].state)
        // Among every thread the builders come first, A before B.
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B, PHONE_THREAD_C), agentThreads(listOf(toA, toB), BuilderNow(), MacView(), second = BuilderNow(working = true)).map { it.key })
    }

    /** Boss 2026-10-24: a third phone agent, "Phone C", there always as B is, reading only its own entries. */
    @Test fun builderCIsAThreadOfItsOwnAlways() {
        val toA = build(1, TaskStatus.Done, result = "Tile made.")
        val toC = build(2, TaskStatus.Running, "check the news").copy(builder = "C")
        val threads = agentThreads(listOf(toA, toC), BuilderNow(), MacView(), third = BuilderNow(working = true, label = "Using Bash"))
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B, PHONE_THREAD_C), threads.map { it.key })
        assertEquals("Phone C", threads[2].name)
        assertEquals(ThreadState.Working, threads[2].state); assertEquals(2L, threads[2].worked)
        assertEquals(ThreadState.Idle, threads[1].state); assertNull(threads[1].worked)
        assertEquals(ThreadState.Idle, threads[0].state)
        assertFalse(threads[2].mac)
    }

    @Test fun separateWorkWaitingForANewThreadIsNotQueuedOnTheBusyOne() {
        val busy = thread(MacThreadStatus.Working)
        val waiting = mac(5, TaskStatus.Queued, "add save anywhere").copy(fresh = true)
        val now = macThreadNow(busy, id, listOf(mac(4, TaskStatus.Running, "magic menu"), waiting), emptyList(), reachable = true)
        assertEquals(ThreadState.Working, now.state); assertEquals("working", now.label)
        // Not made, it needs attention in its own entry, not on the busy thread's tile.
        val failed = macThreadNow(thread(), id, listOf(mac(4, TaskStatus.Done, result = "Done."), waiting.copy(status = TaskStatus.Dropped, result = "Mac unreachable")), emptyList(), reachable = true)
        assertEquals(ThreadState.Idle, failed.state)
    }

    /**
     * Boss 2026-10-13: a new chat is a thread of its own, the Mac thread made for it is shown as it, and the
     * builders' threads are their own conversations whatever an earlier version gave a chat.
     */
    @Test fun aNewChatIsAThreadOfTheMacThreadMadeForIt() {
        val onMac = NewChat("c2", 2, mac = "t-new")
        val fresh = NewChat("c3", 3)
        val own = build(10, TaskStatus.Done, result = "old work done")
        // Filed in a chat by a builder of an earlier version: it stays in that chat, and out of the builder's thread.
        val older = build(20, TaskStatus.Done, result = "chat work").copy(chat = "c1")
        val onNew = mac(30, TaskStatus.Running, threadId = "t-new").copy(chat = "c2")
        val asked = ChatTask(40, QUICK_AGENT, "what is a worktree", result = "A second checkout.", status = TaskStatus.Done, chat = "c3")
        val tasks = listOf(own, older, onNew, asked)
        val busy = BuilderNow(working = true, label = "Using Edit", since = 20)
        val view = MacView(listOf(thread(MacThreadStatus.Working, threadId = "t-new", name = "FF4 2"), thread()), reachable = true)
        val all = agentThreads(tasks, busy, view, chats = listOf(onMac, fresh), names = mapOf("chat:c3" to "Worktrees"))
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B, PHONE_THREAD_C, macThreadKey(id), "chat:c2", "chat:c3"), all.map { it.key })
        val phone = all[0]
        assertEquals(ThreadState.Working, phone.state); assertEquals("working", phone.label)
        // The Mac thread made for a chat is that chat, and not a tab of its own besides.
        val menu = all[4]
        assertEquals(NEW_CHAT_NAME, menu.name); assertEquals(ThreadState.Working, menu.state); assertTrue(menu.mac)
        assertEquals("Worktrees", all[5].name); assertEquals("done", all[5].label); assertEquals("A second checkout.", all[5].doing)
        // A chat with nothing in it is new; one whose words wait for a new thread on the Mac is at work, and says so.
        assertEquals("new", agentThreads(emptyList(), BuilderNow(), MacView(), chats = listOf(fresh)).last().label)
        val starting = ChatTask(50, "→ FF4 2", "go", status = TaskStatus.Queued, thread = id, threadName = "FF4 2", fresh = true, chat = "c3")
        val making = agentThreads(listOf(starting), BuilderNow(), MacView(), chats = listOf(fresh)).last()
        assertEquals(ThreadState.Working, making.state); assertEquals(MAC_MAKING, making.doing)
    }

    /**
     * The critic's case (2026-10-14): a ＋ chat's tile said THIS PHONE before it had a thread. A new chat is the
     * Mac's from the start: unbound it is a Mac thread as reachable as the Mac was, waiting for a project, and
     * words the Mac was out of reach for need him.
     */
    @Test fun aNewChatIsTheMacsBeforeItHasAThreadThere() {
        val fresh = NewChat("c3", 3)
        val answered = ChatTask(40, QUICK_AGENT, "what is a worktree", result = "A second checkout.", status = TaskStatus.Done, chat = "c3")
        val waits = agentThreads(listOf(answered), BuilderNow(), MacView(reachable = true), chats = listOf(fresh)).last()
        assertTrue(waits.mac); assertTrue(waits.unplaced); assertTrue(waits.reachable)
        val empty = agentThreads(emptyList(), BuilderNow(), MacView(), chats = listOf(fresh)).last()
        assertTrue(empty.mac); assertFalse(empty.reachable)
        val unsent = ChatTask(41, QUICK_AGENT, "list the files", result = MAC_CHAT_UNREACHABLE, status = TaskStatus.Dropped, chat = "c3")
        val away = agentThreads(listOf(unsent), BuilderNow(), MacView(), chats = listOf(fresh)).last()
        assertEquals(ThreadState.Attention, away.state); assertEquals(NEEDS_ATTENTION, away.label)
        // Bound, it is its thread, and has a place on the Mac.
        val bound = agentThreads(emptyList(), BuilderNow(), MacView(reachable = true), chats = listOf(NewChat("c2", 2, mac = "t-new"))).last()
        assertTrue(bound.mac); assertFalse(bound.unplaced)
    }

    @Test fun aThreadWithWorkLeftRunningOnTheMacIsAtWork() {
        val done = listOf(mac(10, TaskStatus.Done, result = "Copying in the background."))
        val resting = macThreadNow(thread(MacThreadStatus.Idle, lastWorked = 5, tail = "Copying in the background.", background = listOf("copy the games")), id, done, emptyList(), reachable = true)
        // Not idle while it runs (Boss, 2026-10-24): green, and saying what runs.
        assertEquals(ThreadState.Working, resting.state)
        assertEquals("running on the Mac: copy the games", resting.doing); assertEquals(MAC_BACKGROUND, resting.label)
        // Once it is over the thread reads as done again.
        val over = macThreadNow(thread(MacThreadStatus.Idle, lastWorked = 5, tail = "Copying in the background."), id, done, emptyList(), reachable = true)
        assertEquals("Copying in the background.", over.doing); assertEquals("done", over.label)
        // At work, what it does now comes first.
        assertEquals("working", macThreadNow(thread(MacThreadStatus.Working, background = listOf("copy the games")), id, emptyList(), emptyList(), true).label)
        assertEquals("running on the Mac: copy the games; npm run dev", macBackgroundLine(listOf("copy the games", " npm  run dev ")))
        assertNull(macBackgroundLine(listOf(" ")))
        assertEquals(TILE_DOING_CHARS, macBackgroundLine(listOf("x".repeat(500)))!!.length)
    }
}
