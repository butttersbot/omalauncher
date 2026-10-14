package dev.omalauncher.assist

import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLater
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
        // What the thread says with no request of his running (Claude Code's own report on background work) goes under
        // his newest entry, after its answer; a result or an error of such a turn ends nothing of his.
        val answer = tasks[1]
        hear(MacEvent.Text(8, 0, "more")); hear(MacEvent.Result(9, 0, "other", failed = false)); hear(MacEvent.Error(10, 0, "x", 1))
        assertEquals(answer.copy(later = listOf(ChatLater("more", words = true))), tasks[1])
        assertEquals(phone, tasks[0])
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
        // They are marked, so their answer does not read as more of his.
        assertEquals(listOf(ChatLater(MAC_SENT_ELSEWHERE + "from the Mac itself")), tasks[1].later)
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
        // A turn that did something and said nothing is still done.
        assertEquals("Task completed.", macApply(listOf(ChatHistory.withStep(taken(5), "Bash: make")), id, MacEvent.Result(1, 0, " ", failed = false)).single().result)
        val erred = macApply(listOf(running), id, MacEvent.Error(1, 0, "The agent ended without an answer.", 1)).single()
        assertEquals(TaskStatus.Failed, erred.status); assertEquals("half\n\nThe agent ended without an answer.", erred.result)
        assertEquals(MAC_NO_ANSWER, macApply(listOf(taken(5)), id, MacEvent.Error(1, 0, "", null)).single().result)
        // Stop ends the task and what waited behind it on the Mac, but not words still on their way there.
        val stopped = macApply(listOf(running, handed(6), handed(7)), id, MacEvent.Stopped(1, 0, 1), inFlight = setOf(7L))
        assertEquals(listOf(TaskStatus.Stopped, TaskStatus.Stopped, TaskStatus.Queued), stopped.map { it.status })
        assertEquals(listOf("half\n", MAC_STOPPED_UNTAKEN, ""), stopped.map { it.result })
        assertEquals(ApprovalAnswer.Unanswered, stopped[0].approvals.single().answer)
    }

    /** Thread t91462875: a result with no words ended a turn in which nothing happened, and he read "Task completed." for words no one answered. */
    @Test fun anEmptyTurnNeedsHimAndCanBeSentAgain() {
        val ended = macApply(listOf(taken(5, "flip to auto mode")), id, MacEvent.Result(1, 0, "", failed = false)).single()
        assertEquals(TaskStatus.Dropped, ended.status); assertEquals(MAC_EMPTY_TURN, ended.result)
        assertEquals("The Mac ended this turn without an answer.", MAC_EMPTY_TURN)
        assertEquals(taken(5, "flip to auto mode").copy(status = TaskStatus.Queued), macRetried(ended))
        // A waiting card was something in the turn: its end is the turn's, not one to send again.
        val asked = taken(5).copy(approvals = listOf(ChatApproval("Write", "{}", ApprovalAnswer.Waiting, "a1")))
        assertEquals(TaskStatus.Done, macApply(listOf(asked), id, MacEvent.Result(1, 0, "", failed = false)).single().status)
        // Notes and other events the phone does not show do not count as something said.
        val noted = macApply(listOf(taken(5)), id, MacEvent.Other(1, 0, "note"))
        assertEquals(TaskStatus.Dropped, macApply(noted, id, MacEvent.Result(2, 0, "", failed = false)).single().status)
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

    @Test fun aThreadsReplyIsNamedWithItsFirstWords() {
        var tasks = listOf(taken(5, "fix the menu"))
        fun hear(event: MacEvent) { tasks = macApply(tasks, id, event, answeredBy = "Claude") }
        // The receipt says where his words went; the name comes with the answer, not before it.
        assertEquals("", tasks.single().answeredBy)
        hear(MacEvent.Step(1, 0, "Read: menu.c", "Read")); assertEquals("", tasks.single().answeredBy)
        hear(MacEvent.Text(2, 0, "Looking.")); assertEquals("Claude", tasks.single().answeredBy)
        assertEquals("→ FF4 mod", tasks.single().agent)
        // A name it already has stays; a result with no words before it names the entry too.
        assertEquals("Opus", macApply(listOf(taken(6).copy(answeredBy = "Opus")), id, MacEvent.Text(1, 0, "x"), answeredBy = "Claude").single().answeredBy)
        assertEquals("Claude", macApply(listOf(taken(7)), id, MacEvent.Result(1, 0, "Done.", failed = false), answeredBy = "Claude").single().answeredBy)
        // With nothing known of who runs the thread, it stays unnamed.
        assertEquals("", macApply(listOf(taken(8)), id, MacEvent.Text(1, 0, "x")).single().answeredBy)
    }

    // Separate work on a busy project (Boss 2026-10-13: "spin up a fresh thread" ... "make sure there's no conflicts")

    private fun on(threadId: String, project: String = "~/ClaudeSync/projects/ffiv-ds-better-menu-mod", status: MacThreadStatus = MacThreadStatus.Working, name: String = "FF4 better menu mod", turns: Int = 0) =
        MacThread(threadId, name, project, ThreadHome.Mac, "claude", "", status, null, "", turns, 0, emptyList())

    @Test fun separateWorkForAFreeThreadGoesToItAndForABusyOneToANewThreadInItsFolder() {
        val ff4 = on("t5e121ba2")
        // Free after all: the thread named takes it, as its own mark would have sent it.
        for (resting in listOf(MacThreadStatus.Idle, MacThreadStatus.Interrupted, MacThreadStatus.Unknown)) {
            val free = ff4.copy(status = resting)
            assertEquals(resting.toString(), MacFresh.Same(free), macFresh(free, listOf(free)))
        }
        // Busy in any way: a new thread beside it, in the same folder exactly as the Mac gave it.
        for (busy in listOf(ff4, ff4.copy(status = MacThreadStatus.Waiting), ff4.copy(status = MacThreadStatus.Idle, waitingTurns = 1))) {
            assertEquals(MacFresh.Make("FF4 better menu mod 2", "~/ClaudeSync/projects/ffiv-ds-better-menu-mod"), macFresh(busy, listOf(busy)))
        }
        // The Mac may not have said yet that it works on what the phone just sent it.
        val idle = ff4.copy(status = MacThreadStatus.Idle)
        assertTrue(macFresh(idle, listOf(idle), owed = setOf("t5e121ba2")) is MacFresh.Make)
        assertTrue(macBusy(idle, owed = true)); assertFalse(macBusy(idle))
    }

    @Test fun aNewThreadIsNamedAfterTheBusyOneWithTheNextFreeNumber() {
        assertEquals("FF4 better menu mod 2", macFreshName("FF4 better menu mod", listOf("FF4 better menu mod", "Scratch")))
        assertEquals("FF4 better menu mod 3", macFreshName("FF4 better menu mod", listOf("FF4 better menu mod", "ff4 better menu mod 2")))
        assertEquals("FF4 better menu mod 4", macFreshName("FF4 better menu mod 2", listOf("FF4 better menu mod", "FF4 better menu mod 2", "FF4 better menu mod 3")))
        assertEquals("FF4 2", macFreshName("FF4", listOf("FF4")))
        assertEquals("Mac 2", macFreshName("  ", emptyList()))
        val long = macFreshName("n".repeat(80), emptyList())
        assertEquals(60, long.length); assertTrue(long.endsWith(" 2"))
        // The name goes from the board to the Mac and back to the chat's tabs: no line breaks, nothing else added.
        assertEquals("Scratch 2", macFreshName(" Scratch ", listOf("Scratch")))
    }

    @Test fun noMoreThanThreeWorkInOneFolderThisWayAndTheRestWaitOnTheLeastLoaded() {
        assertEquals(3, MAC_THREADS_PER_PROJECT)
        val ff4 = on("t5e121ba2", turns = 2)
        val two = on("t6f232cb3", "/Users/boss/ClaudeSync/projects/ffiv-ds-better-menu-mod/", name = "FF4 better menu mod 2", turns = 1)
        val three = on("t7a343dc4", status = MacThreadStatus.Waiting, name = "FF4 better menu mod 3", turns = 1)
        val elsewhere = on("t8b454ed5", "~/Projects/other", name = "Other")
        // Two at work there: one more may be made, and the name passes the ones taken.
        assertEquals(MacFresh.Make("FF4 better menu mod 3", ff4.project), macFresh(ff4, listOf(ff4, two, elsewhere, elsewhere.copy(id = "t9", name = "Other 2"))))
        // Three: it waits on the one with the fewest turns waiting, the first of them on a tie, and says how many work there.
        assertEquals(MacFresh.Queue(two, 3), macFresh(ff4, listOf(ff4, two, three, elsewhere)))
        assertEquals(MacFresh.Queue(ff4.copy(waitingTurns = 0), 3), macFresh(ff4.copy(waitingTurns = 0), listOf(ff4, two, three)))
        // A resting thread in the folder, or a busy one in another folder, does not count.
        assertTrue(macFresh(ff4, listOf(ff4, two, three.copy(status = MacThreadStatus.Idle, waitingTurns = 0), elsewhere)) is MacFresh.Make)
        assertEquals("3 threads already work in ffiv-ds-better-menu-mod, so this waits its turn", macCappedStep(3, macFolder(ff4.project)))
        assertEquals("3 threads already work in this project, so this waits its turn", macCappedStep(3, macFolder("")))
    }

    @Test fun aFolderIsKnownWhetherTheHomeIsWrittenOutOrNot() {
        assertEquals("~/Projects/x", macProjectKey("/Users/boss/Projects/x/")); assertEquals("~/Projects/x", macProjectKey(" ~/Projects/x "))
        assertEquals("~/Projects/x", macProjectKey("/home/boss/Projects/x")); assertEquals("~", macProjectKey("/Users/boss"))
        assertEquals("/opt/Users/boss/x", macProjectKey("/opt/Users/boss/x"))
        assertEquals("ffiv-ds-better-menu-mod", macFolder("~/ClaudeSync/projects/ffiv-ds-better-menu-mod/")); assertEquals("", macFolder(""))
    }

    @Test fun theNoteAboutSharedWorkGoesOnlyToAThreadThatIsNotAloneInItsFolder() {
        val ff4 = on("t5e121ba2")
        val fresh = on("t6f232cb3", "/Users/boss/ClaudeSync/projects/ffiv-ds-better-menu-mod", status = MacThreadStatus.Idle, name = "FF4 better menu mod 2")
        val other = on("t8b454ed5", "~/Projects/other", name = "Other")
        // A new thread beside a busy one gets it; so does the busy one when the new one works.
        assertTrue(macShares(fresh.id, listOf(ff4, fresh, other)))
        assertTrue(macShares(ff4.id, listOf(ff4, fresh.copy(status = MacThreadStatus.Working))))
        // Alone at work in its folder it does not, however busy it is itself, or others elsewhere are.
        assertFalse(macShares(ff4.id, listOf(ff4, fresh, other)))
        assertFalse(macShares(fresh.id, listOf(ff4.copy(status = MacThreadStatus.Idle), fresh, other)))
        // A request the phone has not seen finished there counts as work, and one not on the list shares nothing.
        assertTrue(macShares(fresh.id, listOf(ff4.copy(status = MacThreadStatus.Idle), fresh), owed = setOf(ff4.id)))
        assertFalse(macShares("t0", listOf(ff4, fresh))); assertFalse(macShares("t1", listOf(on("t1", ""), on("t2", ""))))
        // What is owed is read from the entries: unfinished ones only, never the one being sent, never one still waiting for a new thread.
        val tasks = listOf(
            macHandOver(ChatTask(1, UNDECIDED_AGENT, "a"), ff4),
            macTaken(macHandOver(ChatTask(2, UNDECIDED_AGENT, "b"), other)),
            macHandOver(ChatTask(3, UNDECIDED_AGENT, "c"), fresh).copy(status = TaskStatus.Done),
            macFreshHandOver(ChatTask(4, UNDECIDED_AGENT, "d"), ff4.copy(id = "t9"), "x 2"),
        )
        assertEquals(setOf(ff4.id, other.id), macOwed(tasks)); assertEquals(setOf(other.id), macOwed(tasks, except = 1))
    }

    @Test fun anEntryWaitingForANewThreadCarriesItsReceiptAndBecomesThatThreadsOnceMade() {
        val ff4 = on("t5e121ba2")
        val entry = macFreshHandOver(ChatTask(5, UNDECIDED_AGENT, "add save anywhere"), ff4, "FF4 better menu mod 2")
        assertEquals(ChatTask(5, "→ FF4 better menu mod 2", "add save anywhere", thread = ff4.id, threadName = "FF4 better menu mod 2", status = TaskStatus.Queued, fresh = true), entry)
        assertTrue(entry.mac); assertFalse(entry.build)
        assertEquals(MAC_MAKING, macEntryLabel(entry.status, sending = true, reachable = true, approvals = false, fresh = true))
        assertEquals("Starting a new thread on the Mac…", waitingLine(entry, macLabel = MAC_MAKING)?.text)
        // Nothing the busy thread says is about it: not its words taken, not a stop, not a context's receiver.
        val tasks = listOf(entry)
        assertEquals(tasks, macApply(tasks, ff4.id, MacEvent.Message(1, 0, "add save anywhere")))
        assertEquals(tasks, macApply(tasks, ff4.id, MacEvent.Stopped(2, 0, 0), inFlight = setOf(5)))
        assertFalse(threadGot(entry, ff4.id))
        assertEquals(tasks, macAbandon(tasks, ff4.id, MAC_THREAD_GONE))
        // With no Mac at all, it needs his attention like every other.
        assertEquals(TaskStatus.Failed, macAbandon(tasks, null, MAC_NOT_SET_UP).single().status)
        // Made: the entry is the new thread's, and its words are taken as that thread's.
        val made = on("t6f232cb3", name = "FF4 better menu mod 2", status = MacThreadStatus.Idle)
        val now = macMade(entry, made)
        assertEquals(ChatTask(5, "→ FF4 better menu mod 2", "add save anywhere", thread = made.id, threadName = "FF4 better menu mod 2", status = TaskStatus.Queued), now)
        assertEquals(TaskStatus.Running, macApply(listOf(now), made.id, MacEvent.Message(1, 0, MAC_SHARED_NOTE + "\nadd save anywhere")).single().status)
        // One that ended meanwhile, or was never waiting for a thread, stays as it is.
        assertEquals(entry.copy(status = TaskStatus.Failed), macMade(entry.copy(status = TaskStatus.Failed), made))
        assertEquals(handed(6), macMade(handed(6), made))
        // A hand-over to the busy thread after all, at a second try, is an ordinary entry.
        assertFalse(macHandOver(entry, ff4).fresh)
    }

    @Test fun aNewThreadThatCouldNotBeMadeKeepsHisWordsSaysWhyAndCanBeTriedAgain() {
        assertNull(macMadeReason(MacAnswer.Ok(on("t6f232cb3"))))
        assertEquals(MAC_UNREACHABLE, macMadeReason(MacAnswer.Unreachable))
        assertEquals(MAC_KEY_REFUSED, macMadeReason(MacAnswer.Unauthorized))
        assertEquals("The Mac would not make a new thread in that folder: it takes only ~/Projects and ~/ClaudeSync/projects.", macMadeReason(MacAnswer.Refused("outside", listOf("~/Projects", "~/ClaudeSync/projects"))))
        assertEquals("The Mac would not make a new thread in that folder.", macMadeReason(MacAnswer.Refused("outside", emptyList())))
        assertEquals("The Mac could not make a new thread (HTTP 500).", macMadeReason(MacAnswer.Failed(500, "")))
        val entry = macFreshHandOver(ChatTask(5, UNDECIDED_AGENT, "add save anywhere"), on("t5e121ba2"), "FF4 better menu mod 2")
        val unsent = macUnsent(entry, macMadeReason(MacAnswer.Unauthorized)!!)
        assertEquals(TaskStatus.Dropped, unsent.status); assertEquals("add save anywhere", unsent.prompt); assertEquals(MAC_KEY_REFUSED, unsent.result)
        // Sent again, it waits for a new thread once more, under the same busy thread.
        assertEquals(entry, macRetried(unsent))
        // The Mac's own summary of a request sent with the note is his first line, as with context.
        val noted = on("t6f232cb3").copy(summary = MAC_SHARED_NOTE)
        assertEquals("add save anywhere", macOwnSummary(noted, listOf(macMade(entry, noted))).summary)
    }

    /** Boss 2026-10-13: a new chat never goes into a conversation that has anything in it, on the Mac either. */
    @Test fun aNewChatsFirstWordsAlwaysGetANewThread() {
        val idle = MacThread("t5e121ba2", "FF4 better menu mod", "~/p/ff4", ThreadHome.Mac, "claude", "", MacThreadStatus.Idle, null, "", 0, 0, emptyList())
        assertEquals(MacFresh.Make("FF4 better menu mod 2", "~/p/ff4"), macFresh(idle, listOf(idle), newChat = true))
        assertEquals(MacFresh.Same(idle), macFresh(idle, listOf(idle)))
        val two = idle.copy(id = "t2", name = "FF4 better menu mod 2", status = MacThreadStatus.Working)
        val three = idle.copy(id = "t3", name = "FF4 better menu mod 3", status = MacThreadStatus.Working)
        val busy = idle.copy(status = MacThreadStatus.Working)
        // Past the limit of threads at work in one folder, a new chat still gets its own, never a busy one's queue.
        assertTrue(macFresh(busy, listOf(busy, two, three)) is MacFresh.Queue)
        assertEquals(MacFresh.Make("FF4 better menu mod 4", "~/p/ff4"), macFresh(busy, listOf(busy, two, three), newChat = true))
    }

    // Background work (OPEN-BUGS #9)

    @Test fun aNoteDuringATurnIsAStepAndAfterItALineUnderTheAnswer() {
        var tasks = listOf(taken(5, "copy the games"))
        fun hear(event: MacEvent) { tasks = macApply(tasks, id, event) }
        hear(MacEvent.Note(1, 0, "Claude Code ended a turn of its own before this message; its answer is still to come."))
        assertEquals(listOf("Claude Code ended a turn of its own before this message; its answer is still to come."), tasks[0].steps)
        hear(MacEvent.Result(2, 0, "Copying in the background.", failed = false))
        // The runner says what is left running after the answer, when the entry has ended.
        hear(MacEvent.Note(3, 0, "Still running on the Mac: copy the games. Claude Code stays open for it and says when it ends."))
        hear(MacEvent.Note(4, 0, "Still running on the Mac: copy the games. Claude Code stays open for it and says when it ends."))
        assertEquals(TaskStatus.Done, tasks[0].status); assertEquals("Copying in the background.", tasks[0].result)
        assertEquals(listOf(ChatLater("Still running on the Mac: copy the games. Claude Code stays open for it and says when it ends.")), tasks[0].later)
    }

    @Test fun claudeCodesReportBetweenHisMessagesAppearsUnderHisLastEntry() {
        var tasks = listOf(macTaken(handed(4, "earlier")).copy(status = TaskStatus.Done, result = "Old."), taken(5, "copy the games").copy(status = TaskStatus.Done, result = "Started."))
        fun hear(event: MacEvent) { tasks = macApply(tasks, id, event) }
        hear(MacEvent.Note(1, 0, "Copied 12 games"))
        hear(MacEvent.Text(2, 0, "The copy finished."))
        hear(MacEvent.Step(3, 0, "Bash: ls /games", "Bash"))
        hear(MacEvent.Text(4, 0, "All 12 games are there."))
        hear(MacEvent.Text(5, 0, "Nothing else to do."))
        hear(MacEvent.Note(6, 0, "Claude Code reported on its background work, between your messages."))
        hear(MacEvent.Status(7, 0, MacThreadStatus.Idle))
        assertEquals(listOf(
            ChatLater("Copied 12 games"),
            ChatLater("The copy finished.", words = true),
            ChatLater("Bash: ls /games"),
            ChatLater("All 12 games are there.\nNothing else to do.", words = true),
            ChatLater("Claude Code reported on its background work, between your messages."),
        ), tasks[1].later)
        // His answer and his earlier entry are left as they were.
        assertEquals("Started.", tasks[1].result); assertEquals(TaskStatus.Done, tasks[1].status)
        assertEquals(emptyList<ChatLater>(), tasks[0].later)
        // A card asked for in that turn is a line on the same entry, so it can be answered there; once only.
        tasks = macApply(tasks, id, MacEvent.Approval(8, 0, write)); tasks = macApply(tasks, id, MacEvent.Approval(9, 0, write))
        assertEquals(listOf(ApprovalAnswer.Waiting), tasks[1].approvals.map { it.answer }); assertEquals(write.approval, tasks[1].approvals.single().key)
        tasks = macApply(tasks, id, MacEvent.ApprovalResult(10, 0, write.approval, MacDecision.Allow))
        assertEquals(ApprovalAnswer.Approved, tasks[1].approvals.single().answer)
        // Stopped with the work unfinished: said the same way.
        tasks = macApply(tasks, id, MacEvent.Note(11, 0, "Stopped, unfinished: copy the games"))
        assertEquals(ChatLater("Stopped, unfinished: copy the games"), tasks[1].later.last())
    }

    @Test fun whatAThreadSaysBetweenMessagesNeedsAnEntryOfHisThere() {
        // Nothing sent from this phone, or only words still waiting for the Mac or for a new thread: nothing to put it under.
        assertEquals(emptyList<ChatTask>(), macApply(emptyList(), id, MacEvent.Note(1, 0, "Still running on the Mac: x")))
        val waiting = listOf(handed(5))
        assertEquals(waiting, macApply(waiting, id, MacEvent.Text(1, 0, "report")))
        val other = listOf(taken(5).copy(status = TaskStatus.Done))
        assertEquals(other, macApply(other, "t99999999", MacEvent.Note(1, 0, "theirs")))
        // A blank note is nothing to show.
        assertEquals(other, macApply(other, id, MacEvent.Step(1, 0, "  ", "")))
    }

    @Test fun whatComesBetweenMessagesIsKeptWithinItsLimit() {
        var task = taken(5).copy(status = TaskStatus.Done)
        for (i in 1..100) task = macLater(task, ChatLater("note $i"))
        assertEquals(ChatHistory.MAX_LATER, task.later.size); assertEquals("note 100", task.later.last().text)
        assertEquals("a b", macLater(task.copy(later = emptyList()), ChatLater("  a\n  b ")).later.single().text)
    }

    // Seen on the real launcher 2026-10-14 16:28-16:32, thread "Background keep-alive check" (OPEN-BUGS #9).

    private val still = "Still running on the Mac: Wait 90 seconds then print done. Claude Code stays open for it and says when it ends."

    /** The runner's events for the second message, as they came; the report after the turn lands under it and the "still running" line goes. */
    @Test fun theReportAfterTheTurnComesUnderHisEntryAndEndsTheStillRunningLine() {
        val asked = "in the background run sleep 90 then echo done, tell me it started, then report when it finishes"
        var tasks = listOf(taken(5, asked))
        fun hear(event: MacEvent) { tasks = macApply(tasks, id, event) }
        hear(MacEvent.Status(20, 0, MacThreadStatus.Working)); hear(MacEvent.Message(21, 0, asked))
        hear(MacEvent.Step(22, 0, "Bash: sleep 90; echo done", "Bash"))
        hear(MacEvent.Text(23, 0, "The 90-second timer is running in the background."))
        hear(MacEvent.Result(24, 0, "The 90-second timer is running in the background.", failed = false))
        hear(MacEvent.Note(25, 0, still)); hear(MacEvent.Status(26, 0, MacThreadStatus.Idle))
        assertEquals(listOf(ChatLater(still)), tasks.single().later)
        hear(MacEvent.Note(27, 0, "Background command \"sleep 90; echo done\" completed (exit code 0)"))
        hear(MacEvent.Step(28, 0, "Read: /tmp/out", "Read"))
        hear(MacEvent.Text(29, 0, "The 90-second timer finished without errors and printed \"done\"."))
        hear(MacEvent.Note(30, 0, "Claude Code reported on its background work, between your messages."))
        val entry = tasks.single()
        assertEquals(TaskStatus.Done, entry.status); assertEquals("The 90-second timer is running in the background.", entry.result)
        assertEquals(listOf(
            ChatLater("Background command \"sleep 90; echo done\" completed (exit code 0)"),
            ChatLater("Read: /tmp/out"),
            ChatLater("The 90-second timer finished without errors and printed \"done\".", words = true),
            ChatLater("Claude Code reported on its background work, between your messages."),
        ), entry.later)
        // Work left running again by a later answer says so again; stopped unfinished, the line goes the same way.
        hear(MacEvent.Note(31, 0, still)); assertEquals(ChatLater(still), tasks.single().later.last())
        hear(MacEvent.Note(32, 0, "Stopped, unfinished: Wait 90 seconds then print done"))
        assertFalse(tasks.single().later.any { it.text == still })
    }

    /**
     * His first words to a thread this phone had not followed: the follower caught up on the thread's past first, and
     * that past's message ("say THREE", sent by curl earlier) and answer were taken as his, his own message then read
     * as "Sent from elsewhere".
     */
    @Test fun hisFirstWordsToAThreadAreHisThoughItsPastIsHeardFirst() {
        val hi = "say hi and list the files in this folder"
        var tasks = listOf(taken(5, hi))
        fun hear(event: MacEvent) { tasks = macApply(tasks, id, event) }
        hear(MacEvent.Status(1, 0, MacThreadStatus.Working)); hear(MacEvent.Message(2, 0, "say THREE"))
        hear(MacEvent.Text(3, 0, "THREE.")); hear(MacEvent.Result(4, 0, "THREE", failed = false)); hear(MacEvent.Status(5, 0, MacThreadStatus.Idle))
        // The past's turn is no entry of his and has none to stand under.
        assertEquals(listOf(handed(5, hi)), tasks)
        hear(MacEvent.Status(6, 0, MacThreadStatus.Working)); hear(MacEvent.Message(7, 0, hi))
        hear(MacEvent.Step(8, 0, "Bash: ls -la", "Bash")); hear(MacEvent.Text(9, 0, "Hi Boss! There are 3 files."))
        hear(MacEvent.Result(10, 0, "Hi Boss! There are 3 files.", failed = false))
        val entry = tasks.single()
        assertEquals(TaskStatus.Done, entry.status); assertEquals("Hi Boss! There are 3 files.", entry.result)
        assertEquals(listOf("Bash: ls -la"), entry.steps); assertEquals(emptyList<ChatLater>(), entry.later)
        // With an earlier entry of his there, the past's turn stands under it, marked as sent from elsewhere.
        val earlier = taken(2, "earlier").copy(status = TaskStatus.Done, result = "Old.")
        tasks = listOf(earlier, taken(5, hi))
        hear(MacEvent.Message(11, 0, "say THREE")); hear(MacEvent.Text(12, 0, "THREE.")); hear(MacEvent.Result(13, 0, "THREE", failed = false))
        assertEquals(listOf(ChatLater(MAC_SENT_ELSEWHERE + "say THREE"), ChatLater("THREE.", words = true)), tasks[0].later)
        hear(MacEvent.Message(14, 0, hi))
        assertEquals(TaskStatus.Running, tasks[1].status); assertEquals(emptyList<ChatLater>(), tasks[1].later)
        // Other words while his turn has said something are not his past: nothing of his changes.
        val speaking = listOf(taken(5, hi).copy(result = "Hi.\n"))
        assertEquals(speaking, macApply(speaking, id, MacEvent.Message(1, 0, "other")))
    }

    /** His message heard before the answer to sending it, or after: either way his entry, never "Sent from elsewhere". */
    @Test fun hisMessageIsHisWhicheverIsHeardFirst() {
        val hi = "say hi"
        // The follower already listening hears the message before the Mac's answer to the sending is read.
        val early = macApply(listOf(handed(5, hi)), id, MacEvent.Message(1, 0, hi))
        assertEquals(TaskStatus.Running, early.single().status)
        assertEquals(early, early.map(::macTaken))
        // The answer first, then the message.
        val late = macApply(listOf(taken(5, hi)), id, MacEvent.Message(1, 0, hi))
        assertEquals(listOf(taken(5, hi)), late)
        // Sent with what went ahead of it, it is still his.
        val sent = handOverText(handOverPreface(listOf(FrontExchange("q", "a"))), hi)
        assertEquals(listOf(taken(5, hi)), macApply(listOf(taken(5, hi)), id, MacEvent.Message(1, 0, sent)))
    }

    @Test fun aThreadOfHisIsFollowedWhileItMayStillReport() {
        val now = 1_000_000_000_000L
        val idle = thread(MacThreadStatus.Idle, lastEvent = 30).copy(lastWorked = (now - 10 * 60_000) / 1000)
        val his = setOf(id)
        fun followed(vararg threads: MacThread, after: Long = 30, owed: List<String> = emptyList(), mine: Set<String> = his) = macFollowed(threads.toList(), owed, mine, { after }, now)
        // Long at rest and all heard: nothing to follow.
        assertEquals(emptyList<String>(), followed(idle))
        // Work left running, or a turn a moment ago: Claude Code may report between his messages.
        assertEquals(listOf(id), followed(idle.copy(background = listOf("copy the games"))))
        assertEquals(listOf(id), followed(idle.copy(lastWorked = (now - 60_000) / 1000)))
        assertTrue(macAwake(idle.copy(lastWorked = (now - MAC_AWAKE_MS + 1000) / 1000), now)); assertFalse(macAwake(idle, now))
        // It said something since the event last shown: caught up. Never followed from here: its past is not caught up.
        assertEquals(listOf(id), followed(idle.copy(lastEvent = 34)))
        assertEquals(emptyList<String>(), followed(idle.copy(lastEvent = 34), after = 0))
        // A thread no request of his went to is only followed while it works or waits.
        assertEquals(emptyList<String>(), followed(idle.copy(background = listOf("x")), mine = emptySet()))
        assertEquals(listOf("t2"), followed(idle.copy(id = "t2", status = MacThreadStatus.Working), mine = emptySet()))
        assertEquals(listOf("t3"), followed(idle.copy(id = "t3", pending = listOf(write)), mine = emptySet()))
        // Owed a request of his, it is followed; a new chat's thread still being made never is.
        assertEquals(listOf("t4"), followed(idle, owed = listOf("t4", MAC_NEW_THREAD)))
    }

    @Test fun aThreadNeverFollowedIsFollowedFromWhereItStood() {
        val cursors = MacCursors(mapOf("known" to 7L))
        cursors.start("fresh", 41); cursors.start("known", 99); cursors.start("empty", 0)
        assertEquals(41, cursors.after("fresh")); assertEquals(7, cursors.after("known")); assertEquals(0, cursors.after("empty"))
        assertEquals(MacOffer.Show, cursors.offer("fresh", MacEvent.Text(42, 0, "his answer")))
    }
}
