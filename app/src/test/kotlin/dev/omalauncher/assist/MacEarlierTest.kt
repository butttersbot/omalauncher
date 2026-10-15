package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.junit.Assert.*
import org.junit.Test

/** OPEN-BUGS #11: a Mac thread's tab shows, only read, what was done in it before this phone came in. */
class MacEarlierTest {
    private fun message(id: Long, text: String, time: Long = id) = MacEvent.Message(id, time, text)
    private fun text(id: Long, text: String) = MacEvent.Text(id, id, text)
    private fun step(id: Long, text: String) = MacEvent.Step(id, id, text, "Bash")
    private fun result(id: Long, text: String, failed: Boolean = false) = MacEvent.Result(id, id, text, failed)
    private fun status(id: Long, word: MacThreadStatus) = MacEvent.Status(id, id, word)

    @Test fun theRecordIsCutIntoTurnsEachWithHisWordsItsStepsAndItsAnswer() {
        val events = listOf(
            text(1, "…the end of a turn older than what was read"),
            result(2, ""),
            status(3, MacThreadStatus.Working),
            message(4, "what files are here"),
            step(5, "ls"),
            step(6, "ls"),
            MacEvent.Approval(7, 7, MacApproval("a1", "n1", "Write", "{}", "write it")),
            MacEvent.ApprovalResult(8, 8, "a1", MacDecision.Allow),
            text(9, "Two files."),
            result(10, "Two files: a and b."),
            message(11, "go on"),
            text(12, "Working on it"),
            MacEvent.Stopped(13, 13, 0),
            message(14, "try once more"),
            text(15, "Half"),
            MacEvent.Error(16, 16, "the agent died", 1),
            MacEvent.Note(17, 17, "Claude Code reported on its background work, between your messages."),
            text(18, "The timer finished."),
            MacEvent.Other(19, 19, "something new"),
        )
        val turns = macTurns(events.shuffled() + events.take(3))
        assertEquals(listOf(1L, 4L, 11L, 14L), turns.map { it.id })
        assertEquals(MacTurn(1, 1, "", answer = "…the end of a turn older than what was read"), turns[0])
        assertEquals(MacTurn(4, 4, "what files are here", listOf("ls", "asked to use Write"), "Two files: a and b."), turns[1])
        // A turn with no result keeps the words it said; a stop says so.
        assertEquals(MacTurn(11, 11, "go on", answer = "Working on it", stopped = true), turns[2])
        // An error is a failure with what was said and why; what the agent says between his messages stays in that turn.
        assertTrue(turns[3].failed)
        assertEquals("Half\n\nthe agent died\n\nThe timer finished.", turns[3].answer)
        assertEquals(listOf("Claude Code reported on its background work, between your messages."), turns[3].steps)
    }

    @Test fun hisWordsAreShownWithoutWhatOmaPutAheadOfThem() {
        val sent = handOverText("$MAC_SHARED_NOTE\n", "make the menu blue")
        assertEquals("make the menu blue", macTurns(listOf(message(1, sent)))[0].words)
    }

    @Test fun onlyWhatThePhoneDoesNotShowIsLaidAboveItsEntries() {
        // The phone first sent this thread something at 100 s; what came after reached it by following.
        val mine = ChatTask(100_000, "→ Pond", "and now the tests", thread = "t1", threadName = "Pond", status = TaskStatus.Done)
        val other = ChatTask(50_000, "→ Lake", "elsewhere", thread = "t2", threadName = "Lake", status = TaskStatus.Done)
        val events = listOf(
            message(1, "start the pond", time = 40), result(2, "Started."),
            message(3, "and now the tests", time = 60), result(4, "Done, from an earlier copy of his words."),
            message(5, "a turn the phone heard", time = 100), result(6, "Heard."),
            message(7, "later at the Mac", time = 300), result(8, "Later."),
        )
        // Before his first entry, and not the same words as one of his entries there.
        assertEquals(listOf(1L), macEarlier(events, listOf(mine, other), "t1").map { it.id })
        // A thread the phone never sent anything shows everything it read; another thread's entries say nothing of it.
        assertEquals(listOf(1L, 3L, 5L, 7L), macEarlier(events, listOf(other), "t1").map { it.id })
        assertEquals(emptyList<MacTurn>(), macEarlier(emptyList(), listOf(mine), "t1"))
    }

    @Test fun theNewestTwoHundredEventsAreReadAPageAtATime() {
        assertEquals(0L, macEarlierAfter(0)); assertEquals(0L, macEarlierAfter(150)); assertEquals(50L, macEarlierAfter(250))
        val record = (1L..250L).map { text(it, "w$it") }
        // A runner that answers everything at once: one ask.
        var asks = 0
        val all = macEarlierEvents(50, 250) { from -> asks++; record.filter { it.id > from } }!!
        assertEquals((51L..250L).toList(), all.map { it.id }); assertEquals(1, asks)
        // One that answers 30 at a time: asked on from the last one each time, until the newest is in.
        asks = 0
        val paged = macEarlierEvents(50, 250) { from -> asks++; record.filter { it.id > from }.take(30) }!!
        assertEquals((51L..250L).toList(), paged.map { it.id }); assertEquals(7, asks)
        // Events come in while it reads: nothing past where it began to read, and never more than 200.
        assertEquals(250L, macEarlierEvents(50, 250) { from -> (record + text(251, "new")).filter { it.id > from } }!!.last().id)
        assertEquals(MAC_EARLIER_EVENTS, macEarlierEvents(0, 250) { from -> record.filter { it.id > from } }!!.size)
        // A page that brings nothing new ends it; repeats are shown once.
        assertEquals(listOf(1L, 2L), macEarlierEvents(0, 5) { from -> if (from == 0L) record.take(2) + record.take(1) else emptyList() }!!.map { it.id })
        // No answer to the first ask is nothing read; to a later one, what was read stands.
        assertNull(macEarlierEvents(0, 5) { null })
        assertEquals(listOf(1L, 2L), macEarlierEvents(0, 5) { from -> if (from == 0L) record.take(2) else null }!!.map { it.id })
        // An asking that never ends is cut off.
        asks = 0
        macEarlierEvents(0, 10_000) { from -> asks++; listOf(text(from + 1, "one")) }
        assertEquals(MAC_EARLIER_PAGES, asks)
    }

    @Test fun theCaptionSaysItIsTheMacsRecordAndOnlyRead() {
        assertEquals("Reading earlier work on the Mac…", macEarlierCaption(MacEarlierRead.Asking, 0))
        assertEquals("Earlier work on the Mac could not be read now", macEarlierCaption(MacEarlierRead.Unread, 0))
        assertNull(macEarlierCaption(MacEarlierRead.Read(emptyList(), cut = false), 0))
        assertEquals("Earlier on the Mac, only shown here", macEarlierCaption(MacEarlierRead.Read(emptyList(), cut = false), 2))
        assertTrue(macEarlierCaption(MacEarlierRead.Read(emptyList(), cut = true), 2)!!.contains("last $MAC_EARLIER_EVENTS events"))
    }

    @Test fun stepsAreOneQuietLine() {
        assertNull(macTurnSteps(MacTurn(1, 1, "x")))
        assertEquals("ls", macTurnSteps(MacTurn(1, 1, "x", listOf("ls"))))
        assertEquals("3 steps, the last: Edit a.kt", macTurnSteps(MacTurn(1, 1, "x", listOf("ls", "Read a.kt", "Edit a.kt"))))
    }
}
