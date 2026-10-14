package dev.omalauncher.assist

import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import org.junit.Assert.*
import org.junit.Test

/**
 * What a project thread on the Mac makes of the Oma thread, which of its requests wait for Boss, and when a
 * request may be sent again (docs/MAC-RUNNER-SPEC.md). Nothing here reaches the Mac or reads its config.
 */
class MacThreadsTest {
    private val id = "t1a2b3c4d"
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val write = MacApproval("a0123456789ab", "n-secret", "Write", """{"file_path":"/p/a.txt","content":"hi"}""", "needs to write")
    private val shell = MacApproval("a0000000000cd", "n-other", "Bash", """{"command":"rm -rf build"}""", "")

    private fun thread(status: MacThreadStatus = MacThreadStatus.Working, lastEvent: Long = 0, pending: List<MacApproval> = emptyList(), name: String = "FF4 mod", threadId: String = id) =
        MacThread(threadId, name, "/Users/boss/Projects/ff4", ThreadHome.Mac, "claude", "", status, null, "", 0, lastEvent, pending)

    private fun handed(time: Long, prompt: String = "prompt $time") = macHandOver(ChatTask(time, UNDECIDED_AGENT, prompt), thread())

    private fun taken(time: Long, prompt: String = "prompt $time") = macTaken(handed(time, prompt))

    @Test fun theEntryCarriesAReceiptAndWaitsUntilTheMacTakesIt() {
        val entry = handed(5, "fix the menu")
        assertEquals(ChatTask(5, "→ FF4 mod", "fix the menu", status = TaskStatus.Queued, thread = id, threadName = "FF4 mod"), entry)
        assertTrue(entry.mac); assertFalse(entry.build); assertFalse(entry.undecided)
        assertEquals(TaskStatus.Running, macTaken(entry).status)
        // The thread may have ended it before the answer to sending it is read: that is never undone.
        val done = entry.copy(status = TaskStatus.Done, result = "Fixed.")
        assertEquals(done, macTaken(done))
        assertEquals("→ Mac", macReceipt("  ")); assertEquals("Mac", macName(""))
        // Two threads can share a name: the entry is tied to the id.
        val twin = macHandOver(ChatTask(6, UNDECIDED_AGENT, "x"), thread(threadId = "t99999999"))
        assertEquals(entry.threadName, twin.threadName); assertEquals("t99999999", twin.thread)
    }

    @Test fun aTurnFillsItsOwnEntryAndNoOther() {
        val phone = ChatTask(1, "Claude Code", "phone work")
        var tasks = listOf(phone, taken(5, "fix the menu"))
        fun hear(event: MacEvent) { tasks = macApply(tasks, id, event) }
        hear(MacEvent.Status(1, 0, MacThreadStatus.Working)); hear(MacEvent.Message(2, 0, "fix the menu"))
        assertEquals(listOf(phone, taken(5, "fix the menu")), tasks)
        hear(MacEvent.Text(3, 0, "Looking.")); assertEquals("Looking.\n", tasks[1].result)
        hear(MacEvent.Step(4, 0, "Read: menu.c", "Read")); hear(MacEvent.Step(5, 0, "Read: menu.c", "Read"))
        assertEquals(listOf("Read: menu.c"), tasks[1].steps)
        hear(MacEvent.Other(6, 0, "new-kind")); assertEquals(TaskStatus.Running, tasks[1].status)
        hear(MacEvent.Result(7, 0, "Fixed.", failed = false))
        assertEquals(TaskStatus.Done, tasks[1].status); assertEquals("Fixed.", tasks[1].result)
        assertEquals(phone, tasks[0])
        // What the thread says with no request of his running, such as work begun at the Mac, changes nothing here.
        val after = tasks
        hear(MacEvent.Text(8, 0, "more")); hear(MacEvent.Result(9, 0, "other", failed = false)); hear(MacEvent.Error(10, 0, "x", 1))
        assertEquals(after, tasks)
        // Another thread's events never reach this thread's entry.
        assertEquals(listOf(taken(5)), macApply(listOf(taken(5)), "t99999999", MacEvent.Text(1, 0, "theirs")))
    }

    @Test fun aRequestThatWaitedBeginsWhenTheThreadTakesItsWords() {
        var tasks = listOf(taken(5, "go on"), handed(6, "go on"), handed(7, "and test it"))
        fun hear(event: MacEvent) { tasks = macApply(tasks, id, event) }
        // The same words twice: while the first runs, its own message event starts nothing.
        hear(MacEvent.Message(1, 0, "go on"))
        assertEquals(listOf(TaskStatus.Running, TaskStatus.Queued, TaskStatus.Queued), tasks.map { it.status })
        hear(MacEvent.Result(2, 0, "One.", failed = false)); hear(MacEvent.Message(3, 0, "go on"))
        assertEquals(listOf(TaskStatus.Done, TaskStatus.Running, TaskStatus.Queued), tasks.map { it.status })
        hear(MacEvent.Text(4, 0, "Two."))
        assertEquals(listOf("One.", "Two.\n", ""), tasks.map { it.result })
        // Words that are no waiting entry's were sent from somewhere else.
        hear(MacEvent.Result(5, 0, "", failed = false)); hear(MacEvent.Message(6, 0, "from the Mac itself"))
        assertEquals(listOf(TaskStatus.Done, TaskStatus.Done, TaskStatus.Queued), tasks.map { it.status })
        assertEquals("Two.\n", tasks[1].result)
        // Taken before the answer to sending it was read: the entry still begins.
        assertEquals(TaskStatus.Running, macApply(listOf(handed(9, "now")), id, MacEvent.Message(1, 0, "now")).single().status)
    }

    /** The thread says back all it was sent; the entry holds his words alone and still begins, and still shows only them. */
    @Test fun aRequestSentWithContextBeginsWhenTheThreadSaysItBack() {
        val sent = handOverText(handOverPreface(listOf(FrontExchange("can we build apps here?", "Yes."))), "go on")
        assertNotEquals("go on", sent)
        val begun = macApply(listOf(handed(6, "go on"), handed(7, "and test it")), id, MacEvent.Message(1, 0, sent))
        assertEquals(listOf(TaskStatus.Running, TaskStatus.Queued), begun.map { it.status })
        assertEquals(listOf("go on", "and test it"), begun.map { it.prompt })
        // Context ahead of other words is no entry's.
        assertEquals(listOf(handed(6, "go on")), macApply(listOf(handed(6, "go on")), id, MacEvent.Message(1, 0, sent + " then")))
    }

    /** The runner's summary of a request sent with context is the context's heading: the board gets his words instead. */
    @Test fun theBoardSaysHisWordsWhereTheMacOnlyKeptTheContextsHeading() {
        val plain = thread().copy(summary = "fix the menu")
        assertEquals(plain, macOwnSummary(plain, listOf(taken(5, "something else"))))
        val headed = thread().copy(summary = CONTEXT_OPEN)
        val tasks = listOf(taken(4, "older"), taken(5, "\n  yes do the magic menu too\nand test it"), macUnsent(handed(6, "never got there"), MAC_UNREACHABLE), macHandOver(ChatTask(7, UNDECIDED_AGENT, "theirs"), thread(threadId = "t99999999")))
        assertEquals("yes do the magic menu too", macOwnSummary(headed, tasks).summary)
        assertEquals("", macOwnSummary(headed, emptyList()).summary)
        assertEquals(headed.copy(summary = ""), macOwnSummary(headed, emptyList()))
    }

    @Test fun aTurnThatFailsStopsOrErrsEndsItsEntryThatWay() {
        val waiting = ChatApproval("Write", "{}", ApprovalAnswer.Waiting, "a1")
        val running = taken(5).copy(result = "half\n", approvals = listOf(waiting))
        val failed = macApply(listOf(running), id, MacEvent.Result(1, 0, "", failed = true)).single()
        assertEquals(TaskStatus.Failed, failed.status); assertEquals("half\n", failed.result)
        assertEquals(ApprovalAnswer.Unanswered, failed.approvals.single().answer)
        assertEquals("The agent needs attention.", macApply(listOf(taken(5)), id, MacEvent.Result(1, 0, "", failed = true)).single().result)
        assertEquals("Task completed.", macApply(listOf(taken(5)), id, MacEvent.Result(1, 0, " ", failed = false)).single().result)
        val erred = macApply(listOf(running), id, MacEvent.Error(1, 0, "The agent ended without an answer.", 1)).single()
        assertEquals(TaskStatus.Failed, erred.status); assertEquals("half\n\nThe agent ended without an answer.", erred.result)
        assertEquals(MAC_NO_ANSWER, macApply(listOf(taken(5)), id, MacEvent.Error(1, 0, "", null)).single().result)
        // Stop ends the task and what waited behind it on the Mac, but not words still on their way there.
        val stopped = macApply(listOf(running, handed(6), handed(7)), id, MacEvent.Stopped(1, 0, 1), inFlight = setOf(7L))
        assertEquals(listOf(TaskStatus.Stopped, TaskStatus.Stopped, TaskStatus.Queued), stopped.map { it.status })
        assertEquals(listOf("half\n", MAC_STOPPED_UNTAKEN, ""), stopped.map { it.result })
        assertEquals(ApprovalAnswer.Unanswered, stopped[0].approvals.single().answer)
    }

    @Test fun anApprovalIsALineOnTheRunningEntryAndEndsAsTheMacSays() {
        var tasks = listOf(taken(5))
        tasks = macApply(tasks, id, MacEvent.Approval(1, 0, write)); tasks = macAsked(tasks, id, write)
        val line = tasks.single().approvals.single()
        assertEquals("Write", line.tool); assertEquals(ApprovalAnswer.Waiting, line.answer); assertEquals(write.approval, line.key)
        assertTrue(line.detail, line.detail.contains("a.txt"))
        // Neither the line nor what it is keyed by is the Mac's nonce.
        assertFalse(line.toString().contains("n-secret"))
        assertEquals("rm -rf build", macApprovalDetail(shell))
        assertEquals("not json", macApprovalDetail(shell.copy(input = "not json")))
        tasks = macApply(tasks, id, MacEvent.ApprovalResult(2, 0, write.approval, MacDecision.Deny))
        assertEquals(ApprovalAnswer.Denied, tasks.single().approvals.single().answer)
        // What it was answered with is not changed by anything later.
        assertEquals(tasks, macAnswered(tasks, id, write.approval, ApprovalAnswer.Approved))
        assertEquals(tasks, macAnswered(tasks, id, "another", ApprovalAnswer.Approved))
        // A card that went without a word can still learn what became of it.
        val gone = macAnswered(macAsked(listOf(taken(5)), id, shell), id, shell.approval, ApprovalAnswer.Unanswered)
        assertEquals(ApprovalAnswer.Withdrawn, macApply(gone, id, MacEvent.ApprovalResult(3, 0, shell.approval, MacDecision.Withdrawn)).single().approvals.single().answer)
        // With no request of his running there is no entry to put it on; the card is still his, from the book.
        assertEquals(listOf(handed(5)), macAsked(listOf(handed(5)), id, write))
    }

    @Test fun aRestingThreadLeavesNothingWorkingAndOnlyWhatWasNeverTakenCanBeSentAgain() {
        val other = macHandOver(ChatTask(9, UNDECIDED_AGENT, "elsewhere"), thread(threadId = "t99999999"))
        val tasks = listOf(taken(5).copy(result = "half\n"), handed(6), other)
        for (busy in listOf(MacThreadStatus.Working, MacThreadStatus.Waiting, MacThreadStatus.Unknown)) assertEquals(tasks, macSettle(tasks, id, busy, 0))
        val idle = macSettle(tasks, id, MacThreadStatus.Idle, 0)
        assertEquals(listOf(TaskStatus.Failed, TaskStatus.Dropped, TaskStatus.Queued), idle.map { it.status })
        assertEquals(listOf("half\n\n$MAC_NO_ANSWER", MAC_NOT_TAKEN, ""), idle.map { it.result })
        assertEquals("prompt 6", idle[1].prompt)
        assertTrue(MAC_NOT_TAKEN.startsWith(MAC_UNREACHABLE)); assertEquals("Mac unreachable", MAC_UNREACHABLE)
        // The runner restarted with a turn still waiting: it may be this one, so it is left alone.
        val restarted = macSettle(tasks, id, MacThreadStatus.Interrupted, 1)
        assertEquals(listOf(TaskStatus.Failed, TaskStatus.Queued, TaskStatus.Queued), restarted.map { it.status })
        assertTrue(restarted[0].result.endsWith(MAC_INTERRUPTED))
        // Words still on their way when the Mac was asked: nothing is known of them yet.
        assertEquals(TaskStatus.Queued, macSettle(tasks, id, MacThreadStatus.Idle, 0) { it.time != 6L }[1].status)

        // Sent again: only the one the Mac never got. Never one it took, finished or may still have.
        assertEquals(handed(6), macRetried(idle[1]))
        for (never in listOf(idle[0], tasks[0], tasks[1], taken(5).copy(status = TaskStatus.Done), taken(5).copy(status = TaskStatus.Stopped), ChatTask(1, "Claude Code", "x", status = TaskStatus.Dropped))) {
            assertNull(never.toString(), macRetried(never))
        }
    }

    @Test fun aThreadThatCannotBeFollowedNeedsAttentionAndIsNeverSentAgain() {
        val phone = ChatTask(1, "Claude Code", "phone work")
        val other = macTaken(macHandOver(ChatTask(9, UNDECIDED_AGENT, "elsewhere"), thread(threadId = "t99999999")))
        val tasks = listOf(phone, taken(5), handed(6), taken(7).copy(status = TaskStatus.Done), other)
        val gone = macAbandon(tasks, id, MAC_THREAD_GONE)
        assertEquals(listOf(TaskStatus.Running, TaskStatus.Failed, TaskStatus.Failed, TaskStatus.Done, TaskStatus.Running), gone.map { it.status })
        assertEquals(MAC_THREAD_GONE, gone[2].result)
        assertTrue(gone.all { macRetried(it) == null })
        val none = macAbandon(tasks, null, MAC_NOT_SET_UP)
        assertEquals(listOf(TaskStatus.Running, TaskStatus.Failed, TaskStatus.Failed, TaskStatus.Done, TaskStatus.Failed), none.map { it.status })
        assertEquals(phone, none[0])
        assertTrue(macRefusedText(401).contains("key")); assertEquals(MAC_THREAD_GONE, macRefusedText(404)); assertTrue(macRefusedText(500).contains("500"))
    }

    @Test fun aMacThatDoesNotAnswerKeepsHisWordsAndNothingGoesToThePhonesBuilder() {
        for (health in listOf(MacHealthResult.Unreachable, MacHealthResult.Refused(401), MacHealthResult.NotConfigured)) {
            assertEquals(MacRoute.TellOwnerUnreachable, macRoute(ThreadHome.Mac, health, startedOnMac = false, phoneHasClone = false))
        }
        assertEquals(MacRoute.RunOnMac, macRoute(ThreadHome.Mac, MacHealthResult.Up(MacHealth(2, 0, 0)), startedOnMac = false, phoneHasClone = false))
        assertEquals("Mac unreachable", macUnreachableReason(MacHealthResult.Unreachable))
        assertEquals(MAC_KEY_REFUSED, macUnreachableReason(MacHealthResult.Refused(401)))
        val unsent = macUnsent(handed(5, "fix the menu"), MAC_UNREACHABLE)
        assertEquals(TaskStatus.Dropped, unsent.status); assertEquals("Mac unreachable", unsent.result); assertEquals("fix the menu", unsent.prompt)
        assertEquals(id, unsent.thread); assertNotNull(macRetried(unsent))
        // One the Mac has taken is never turned into one to send again.
        assertEquals(taken(5), macUnsent(taken(5), MAC_UNREACHABLE))
    }

    @Test fun onlyACertainRefusalSaysTheMacDidNotTakeIt() {
        assertNull(macUnsentReason(MacAnswer.Ok(MacTaken.Now))); assertNull(macUnsentReason(MacAnswer.Ok(MacTaken.Queued(2))))
        // No answer, or one that cannot be read: the Mac may have his words, so nothing offers to send them again.
        assertNull(macUnsentReason(MacAnswer.Unreachable)); assertNull(macUnsentReason(MacAnswer.Failed(200, "not an answer")))
        assertEquals(MAC_KEY_REFUSED, macUnsentReason(MacAnswer.Unauthorized))
        assertEquals(MAC_THREAD_GONE, macUnsentReason(MacAnswer.NotFound))
        assertNotNull(macUnsentReason(MacAnswer.Refused("no", emptyList())))
        assertTrue(macUnsentReason(MacAnswer.Failed(500, "boom"))!!.contains("500"))
    }

    @Test fun aSilentMacIsAskedAgainMoreAndMoreSlowly() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 30_000L, 30_000L), (1..6).map(::macRetryDelay))
        assertEquals(2_000L, macRetryDelay(0))
        assertTrue(macTurnsOver(MacEvent.Result(1, 0, "", false))); assertTrue(macTurnsOver(MacEvent.Stopped(1, 0, 0))); assertTrue(macTurnsOver(MacEvent.Error(1, 0, "", null)))
        assertTrue(macTurnsOver(MacEvent.Status(1, 0, MacThreadStatus.Idle))); assertTrue(macTurnsOver(MacEvent.Status(1, 0, MacThreadStatus.Interrupted)))
        assertFalse(macTurnsOver(MacEvent.Status(1, 0, MacThreadStatus.Waiting))); assertFalse(macTurnsOver(MacEvent.Text(1, 0, "x"))); assertFalse(macTurnsOver(MacEvent.Approval(1, 0, write)))
        assertTrue(macUnfinished(taken(5), id)); assertTrue(macUnfinished(handed(5), id)); assertFalse(macUnfinished(taken(5), "t99999999"))
        assertFalse(macUnfinished(taken(5).copy(status = TaskStatus.Dropped), id)); assertFalse(macUnfinished(taken(5).copy(status = TaskStatus.Done), id))
    }

    @Test fun theEntrySaysWhereItsRequestIs() {
        assertEquals("Sending to the Mac", macEntryLabel(TaskStatus.Queued, sending = true, reachable = false, approvals = false))
        assertEquals("Queued on the Mac", macEntryLabel(TaskStatus.Queued, sending = false, reachable = true, approvals = true))
        assertEquals("Sent; the Mac is out of reach", macEntryLabel(TaskStatus.Queued, sending = false, reachable = false, approvals = false))
        assertEquals("Approval needed", macEntryLabel(TaskStatus.Running, sending = false, reachable = true, approvals = true))
        assertEquals("Working on the Mac", macEntryLabel(TaskStatus.Running, sending = false, reachable = true, approvals = false))
        assertEquals("Mac out of reach; still working there", macEntryLabel(TaskStatus.Running, sending = false, reachable = false, approvals = false))
    }

    @Test fun theLastEventPerThreadSurvivesBeingSaved() {
        val cursors = mapOf(id to 41L, "t99999999" to 3L)
        assertEquals(cursors, macCursorsFrom(macCursorsText(cursors)))
        assertEquals(cursors, MacCursors(macCursorsFrom(macCursorsText(cursors))).snapshot())
        assertEquals(emptyMap<String, Long>(), macCursorsFrom(null)); assertEquals(emptyMap<String, Long>(), macCursorsFrom("{not json"))
        assertEquals(mapOf("t3" to 4L), macCursorsFrom("""{"t1":0,"t2":"x","t3":4,"t4":null,"t5":-2}"""))
    }

    // Approvals: every one is Boss's alone.

    private fun book(): MacApprovalBook {
        var made = 0
        return MacApprovalBook { "ticket-${made++}" }
    }

    @Test fun aRequestWaitsFromItsEventUntilTheMacSaysItIsOver() {
        val book = book()
        val card = book.offer(id, "FF4 mod", 3, write)
        assertEquals(MacPending(id, "FF4 mod", write, "ticket-0", 0), card)
        assertNull(book.offer(id, "FF4 mod", 3, write)); assertNull(book.offer(id, "FF4 mod", 9, write))
        assertEquals(listOf(card), book.live); assertEquals(card, book.find("ticket-0")); assertNull(book.find("n-secret"))
        // The same approval id in another thread is another request.
        val twin = book.offer("t99999999", "FF4 mod", 1, write)
        assertEquals(1, twin!!.slot); assertEquals(2, book.live.size)
        assertEquals(card, book.resolve(id, 4, write.approval)); assertNull(book.resolve(id, 5, write.approval))
        assertEquals(listOf(twin), book.live)
        assertEquals(listOf(twin), book.ended("t99999999", 2)); assertTrue(book.live.isEmpty())
        // The nonce is in neither what a card prints nor what its buttons carry.
        assertFalse(card.toString().contains("n-secret"))
        assertFalse(MacApprovalTicket(ApprovalVerb.Approve, 0, uuid).encode().contains("n-secret"))
    }

    @Test fun theThreadsOwnListIsTheMacsWordAndOlderEventsDoNotArgueWithIt() {
        val book = book()
        // After a restart the cards are drawn again from what the thread says waits.
        val first = book.sync(thread(MacThreadStatus.Waiting, lastEvent = 6, pending = listOf(write)))
        assertEquals(listOf(MacPending(id, "FF4 mod", write, "ticket-0", 0)), first.added); assertTrue(first.removed.isEmpty())
        // Events up to the list's last one are already in it: they neither add a card again nor take one away.
        assertNull(book.offer(id, "FF4 mod", 5, shell)); assertNull(book.resolve(id, 6, write.approval)); assertTrue(book.ended(id, 6).isEmpty())
        assertEquals(1, book.live.size)
        val later = book.offer(id, "FF4 mod", 7, shell)
        assertEquals(1, later!!.slot)
        // Read again: a request that still waits keeps its ticket, and one the Mac no longer names goes.
        val second = book.sync(thread(MacThreadStatus.Waiting, lastEvent = 9, pending = listOf(write)))
        assertTrue(second.added.isEmpty()); assertEquals(listOf(later), second.removed)
        assertEquals("ticket-0", book.live.single().ticket)
        // The same id under a new nonce is a new request: the old card cannot answer it.
        val renewed = book.sync(thread(MacThreadStatus.Waiting, lastEvent = 12, pending = listOf(write.copy(nonce = "n-new"))))
        assertEquals(listOf("ticket-0"), renewed.removed.map { it.ticket }); assertEquals(listOf("n-new"), renewed.added.map { it.request.nonce })
        assertEquals(1, book.drop(id).size); assertTrue(book.live.isEmpty())
        assertTrue(book.sync(thread(MacThreadStatus.Idle, lastEvent = 13)).let { it.added.isEmpty() && it.removed.isEmpty() })
    }

    @Test fun noRequestIsEverRefusedOrAnsweredForWantOfRoom() {
        val book = book()
        val cards = (1..ApprovalIds.MAX_LIVE + 2).map { n -> book.offer(id, "FF4 mod", n.toLong(), write.copy(approval = "a$n")) }
        assertTrue(cards.all { it != null })
        assertEquals((0 until ApprovalIds.MAX_LIVE).toList<Int?>() + listOf<Int?>(null, null), cards.map { it!!.slot })
        assertEquals(ApprovalIds.MAX_LIVE + 2, book.live.size)
        // A slot comes free with its request.
        book.remove(id, "a3")
        assertEquals(2, book.offer(id, "FF4 mod", 99, write.copy(approval = "a99"))!!.slot)
        assertEquals(ApprovalIds.MAX_LIVE + 2, book.clear().size); assertTrue(book.live.isEmpty())
    }

    @Test fun aButtonNamesOneRequestOfTheMacsAndNothingElseReadsAsOne() {
        val ticket = MacApprovalTicket(ApprovalVerb.Deny, 3, uuid)
        assertEquals("oma-mac-approval:deny/3/$uuid", ticket.encode())
        assertEquals(ticket, MacApprovalTicket.parse(ticket.encode()))
        val bad = listOf(
            null, "", "oma-mac-approval:", "oma-mac-approval:deny/3", "oma-mac-approval:deny/16/$uuid", "oma-mac-approval:deny/-1/$uuid", "oma-mac-approval:allow/3/$uuid",
            "oma-mac-approval:deny/3/n-secret", "oma-mac-approval:deny/3/$uuid/extra", "oma-approval:deny/3/$uuid/request-7", "oma-mac-approval:deny/3/AAAAAAAA-2222-4333-8444-555555555555",
        )
        for (text in bad) assertNull(text, MacApprovalTicket.parse(text))
        // Neither kind of ticket is read as the other, so a button of one can never answer the other.
        assertNull(ApprovalTicket.parse(ticket.encode()))
        assertNull(MacApprovalTicket.parse(ApprovalTicket(ApprovalVerb.Deny, 3, uuid, "request-7").encode()))
        // Their notifications and buttons have ids of their own.
        val phone = (0 until ApprovalIds.MAX_LIVE).flatMap { slot -> ApprovalVerb.entries.map { ApprovalIds.requestCode(slot, it) } + ApprovalIds.notificationId(slot) }
        val mac = (0 until ApprovalIds.MAX_LIVE).flatMap { slot -> ApprovalVerb.entries.map { MacApprovalIds.requestCode(slot, it) } + MacApprovalIds.notificationId(slot) }
        assertTrue(phone.maxOrNull()!! < mac.minOrNull()!!)
        assertEquals(ApprovalIds.MAX_LIVE * ApprovalVerb.entries.size, (0 until ApprovalIds.MAX_LIVE).flatMap { slot -> ApprovalVerb.entries.map { MacApprovalIds.requestCode(slot, it) } }.toSet().size)
        assertFalse(MacApprovalIds.tag(uuid).startsWith(ApprovalIds.TAG_PREFIX))
    }

    /** Pinned: a request of the Mac's is over only when the Mac says so. Nothing else, and no silence, allows or removes it. */
    @Test fun onlyTheMacTakingHisAnswerDecidesARequest() {
        assertEquals(MacApprovalOutcome.Decided, macApprovalOutcome(MacAnswer.Ok(MacDecision.Allow)))
        assertEquals(MacApprovalOutcome.Decided, macApprovalOutcome(MacAnswer.Ok(MacDecision.Deny)))
        // A wrong nonce leaves it waiting, and he is told.
        assertEquals(MacApprovalOutcome.WrongNonce, macApprovalOutcome(MacAnswer.Refused("wrong nonce", emptyList())))
        assertEquals(MacApprovalOutcome.Expired, macApprovalOutcome(MacAnswer.NotFound))
        assertEquals(MacApprovalOutcome.Expired, macApprovalOutcome(MacAnswer.Failed(409, "the task is no longer running")))
        assertEquals(MacApprovalOutcome.KeyRefused, macApprovalOutcome(MacAnswer.Unauthorized))
        assertEquals(MacApprovalOutcome.Failed, macApprovalOutcome(MacAnswer.Failed(500, "")))
        assertEquals(MacApprovalOutcome.Unreachable, macApprovalOutcome(MacAnswer.Unreachable))
        assertEquals(listOf(MacApprovalOutcome.Decided, MacApprovalOutcome.Expired), MacApprovalOutcome.entries.filter { it.over })
        assertNull(macApprovalNotice(MacApprovalOutcome.Decided))
        for (outcome in MacApprovalOutcome.entries - MacApprovalOutcome.Decided) assertNotNull(outcome.name, macApprovalNotice(outcome))
        for (waits in listOf(MacApprovalOutcome.WrongNonce, MacApprovalOutcome.KeyRefused, MacApprovalOutcome.Unreachable, MacApprovalOutcome.Failed)) {
            assertTrue(waits.name, macApprovalNotice(waits)!!.contains("still waiting"))
        }
    }

    /**
     * Pinned by reading the source: the Mac is sent an answer from one place only, which his tap reaches, and
     * the front agent's side of the wiring never names an approval at all.
     */
    @Test fun nothingButHisTapSendsTheMacAnAnswer() {
        val root = dev.omalauncher.theme.pack.moduleFile("src/main/kotlin/dev/omalauncher")
        val calls = root.walkTopDown().filter { it.extension == "kt" }.associate { file -> file.name to Regex("""\.answerApproval\(""").findAll(file.readText()).count() }.filterValues { it > 0 }
        // MacThreads.answer, over the client's one request, for the Mac; the phone builder's own three callers for the phone's, as before.
        assertEquals(1, calls["MacThreads.kt"]); assertEquals(1, calls["MacRunner.kt"])
        assertEquals(setOf("MacThreads.kt", "MacRunner.kt", "AgentApprovalActivity.kt", "AgentNotices.kt", "OmaChatActivity.kt"), calls.keys)
        for (front in listOf("FrontAgent.kt", "QuickLane.kt")) {
            val code = java.io.File(root, "assist/$front").readLines().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }.joinToString("\n")
            for (never in listOf("answerApproval", "MacPending", "MacApprovalBook", ".nonce", ".pending", "MacThreads.answer")) assertFalse("$never in $front", code.contains(never))
        }
    }

    @Test fun wordsSentStraightToAThreadLeaveTheEntryAHandOverDoes() {
        val direct = macDirect(9, "fix the menu", id, "FF4 mod")
        // The same receipt, thread and waiting state: it is in the Oma stream marked "→ FF4 mod", and the thread's events find it.
        assertEquals(handed(9, "fix the menu").copy(direct = true), direct)
        assertTrue(direct.mac); assertTrue(threadGot(direct, id))
        assertEquals(TaskStatus.Running, macTaken(direct).status)
        assertEquals(TaskStatus.Running, macApply(listOf(direct), id, MacEvent.Message(1, 0, "fix the menu")).single().status)
        // One the Mac never got can be sent again, and stays marked as his words alone.
        val unsent = macUnsent(direct, MAC_UNREACHABLE)
        assertEquals(TaskStatus.Dropped, unsent.status)
        assertEquals(true, macRetried(unsent)?.direct)
        assertEquals("Mac", macDirect(9, "x", id, " ").threadName)
    }
}
