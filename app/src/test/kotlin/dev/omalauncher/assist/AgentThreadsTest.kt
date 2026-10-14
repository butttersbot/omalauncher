package dev.omalauncher.assist

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
        lastWorked: Long? = null, waitingTurns: Int = 0, pending: List<MacApproval> = emptyList(),
    ) = MacThread(threadId, name, "/p", ThreadHome.Mac, "claude", summary, status, lastWorked, tail, waitingTurns, 0, pending)

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
        assertEquals(listOf(PHONE_THREAD, "mac:t-old", "mac:t-new", "mac:t-gone", "mac:t-never"), threads.map { it.key })
        assertEquals(ThreadState.NeedsOk, threads[2].state)
        assertEquals("FF4", threads[3].name)
        // With no Mac at all there is the builder alone.
        assertEquals(listOf(PHONE_THREAD), agentThreads(listOf(build(1, TaskStatus.Done)), BuilderNow(), MacView()).map { it.key })
    }

    @Test fun anAnswersEndIsPlainWordsCutAtAWord() {
        assertEquals("", endOf("  "))
        assertEquals("Done. See menu.c", endOf("**Done.**\n\nSee `menu.c`"))
        val long = endOf("one two three four five six seven eight nine ten", chars = 20)
        assertEquals("…eight nine ten", long)
        assertTrue(endOf("x".repeat(500)).length <= TILE_DOING_CHARS + 1)
    }

    /** Boss 2026-10-13: two builders. B is a thread of its own once it has worked, and the two are then "Phone A" and "Phone B". */
    @Test fun builderBIsAThreadOfItsOwnOnceItHasWorked() {
        val toA = build(1, TaskStatus.Done, result = "Tile made.")
        // Only A has ever worked: one builder, called "Phone", as before there were two.
        assertEquals(listOf(AgentThread(PHONE_THREAD, "Phone", ThreadState.Idle, "Tile made.", "done", 1)), phoneThreads(listOf(toA), emptyMap()))
        val toB = build(2, TaskStatus.Running, "fix the dock", steps = listOf("Edit: ui/Dock.kt")).copy(builder = "B")
        val queuedB = build(3, TaskStatus.Queued, "and the folder").copy(builder = "B")
        val threads = phoneThreads(listOf(toA, toB, queuedB), mapOf(BuilderId.B to BuilderNow(working = true, label = "Using Edit")))
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B), threads.map { it.key })
        assertEquals(listOf("Phone A", "Phone B"), threads.map { it.name })
        // Each reads its own entries and its own state.
        assertEquals(ThreadState.Idle, threads[0].state); assertEquals("Tile made.", threads[0].doing)
        assertEquals(ThreadState.Queued, threads[1].state); assertEquals("1 queued", threads[1].label); assertEquals(3L, threads[1].worked)
        assertFalse(threads[1].mac)
        // B working with no entry yet is shown, and its approval is its own.
        val asking = phoneThreads(listOf(toA), mapOf(BuilderId.B to BuilderNow(working = true, asks = listOf(ThreadAsk("Bash", "git")))))
        assertEquals(ThreadState.Idle, asking[0].state); assertEquals(ThreadState.NeedsOk, asking[1].state)
        // Among every thread the builders come first, A before B.
        assertEquals(listOf(PHONE_THREAD, PHONE_THREAD_B), agentThreads(listOf(toA, toB), BuilderNow(), MacView(), second = BuilderNow(working = true)).map { it.key })
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

    /** Boss 2026-10-13: a new chat is a thread of its own, and the builder or Mac thread it took is shown as it. */
    @Test fun aNewChatIsAThreadOfTheSessionItTook() {
        val onA = NewChat("c1", 1, builder = BuilderId.A)
        val onMac = NewChat("c2", 2, mac = "t-new")
        val fresh = NewChat("c3", 3)
        val own = build(10, TaskStatus.Done, result = "old work done")
        val inChat = build(20, TaskStatus.Running, steps = listOf("Edit: Dock.kt")).copy(chat = "c1")
        val onNew = mac(30, TaskStatus.Running, threadId = "t-new").copy(chat = "c2")
        val asked = ChatTask(40, QUICK_AGENT, "what is a worktree", result = "A second checkout.", status = TaskStatus.Done, chat = "c3")
        val tasks = listOf(own, inChat, onNew, asked)
        val busy = BuilderNow(working = true, label = "Using Edit", since = 20)
        val view = MacView(listOf(thread(MacThreadStatus.Working, threadId = "t-new", name = "FF4 2"), thread()), reachable = true)
        val all = agentThreads(tasks, busy, view, chats = listOf(onA, onMac, fresh), owners = mapOf(BuilderId.A to "c1"), names = mapOf("chat:c1" to "Dock icons"))
        assertEquals(listOf(PHONE_THREAD, macThreadKey(id), "chat:c1", "chat:c2", "chat:c3"), all.map { it.key })
        // Builder A went on to the chat: its own thread rests as ended, and the chat has A's work.
        val phone = all[0]
        assertEquals(ThreadState.Idle, phone.state); assertEquals(ENDED, phone.label); assertEquals("old work done", phone.doing)
        val dock = all[2]
        assertEquals("Dock icons", dock.name); assertEquals(ThreadState.Working, dock.state); assertEquals("Edit: Dock.kt", dock.doing); assertFalse(dock.mac)
        // The Mac thread made for a chat is that chat, and not a tab of its own besides.
        val menu = all[3]
        assertEquals(NEW_CHAT_NAME, menu.name); assertEquals(ThreadState.Working, menu.state); assertTrue(menu.mac)
        assertEquals("done", all[4].label); assertEquals("A second checkout.", all[4].doing)
        // Once A has gone on to another chat, this one has ended.
        val ended = agentThreads(tasks, busy, view, chats = listOf(onA), owners = mapOf(BuilderId.A to "c9")).first { it.key == "chat:c1" }
        assertEquals(ThreadState.Idle, ended.state)
        // A chat with nothing in it is new; one whose first words wait for a builder is at work.
        assertEquals("new", agentThreads(emptyList(), BuilderNow(), MacView(), chats = listOf(fresh)).last().label)
        val starting = ChatTask(50, "Claude Code", "go", status = TaskStatus.Queued, fresh = true, chat = "c3")
        assertEquals(ThreadState.Working, agentThreads(listOf(starting), BuilderNow(), MacView(), chats = listOf(fresh)).last().state)
    }
}
