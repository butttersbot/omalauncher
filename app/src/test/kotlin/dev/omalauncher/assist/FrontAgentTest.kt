package dev.omalauncher.assist

import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** One Oma (Boss, 2026-10-13): how the front agent's words are read, what it is told of the build lane, and what a failure means. */
class FrontAgentTest {
    @Test fun theMarkIsNotDecidedUntilItIsWholeOrCannotBeIt() {
        assertEquals("<<build>>", HAND_OVER); assertEquals(HAND_OVER, handOverMark(Route.BUILD))
        for (partial in listOf("", " ", "\n", "`", "<", "<<", "<<bu", "<<build>", " `<<build", "```\n<<b")) assertEquals(partial, FrontRead.Undecided, frontRead(partial))
        for (whole in listOf("<<build>>", " <<build>>\n", "`<<build>>`", "```\n<<build>>\n```", "<<build>> sure", "<<build>>\nI'll pass that on.", "<<build>><<build>>")) {
            assertEquals(whole, FrontRead.HandOver(Route.BUILD), frontRead(whole))
            assertEquals(whole, FrontRead.HandOver(Route.BUILD), frontRead(whole, ended = true))
        }
    }

    @Test fun anythingElseIsAnAnswerFromItsFirstWord() {
        assertEquals(FrontRead.Answer("P"), frontRead("P"))
        assertEquals(FrontRead.Answer("Paris."), frontRead("Paris."))
        assertEquals(FrontRead.Answer("<<bug>> is how I'd write it"), frontRead("<<bug>> is how I'd write it"))
        assertEquals(FrontRead.Answer("<b>"), frontRead("<b>"))
        // Close to the mark is not the mark.
        for (near in listOf("<<Build>>", "<< build >>", "<build>", "build")) assertTrue(near, frontRead(near, ended = true) is FrontRead.Answer)
    }

    @Test fun anAnswerThatOnlyMentionsTheMarkIsShownWithoutItAndNothingIsHandedOver() {
        assertEquals(FrontRead.Answer("Sure!"), frontRead("Sure! <<build>>"))
        assertEquals(FrontRead.Answer("Sure!"), frontRead("Sure! <<build>>", ended = true))
        assertEquals(FrontRead.Answer("I'd send  that on."), frontRead("I'd send <<build>> that on."))
        // While it is still arriving, the start of a mark at the end is held back; one that never completes is shown.
        assertEquals(FrontRead.Answer("Sure!"), frontRead("Sure! <<bu"))
        assertEquals(FrontRead.Answer("Sure! <<bu"), frontRead("Sure! <<bu", ended = true))
        assertEquals(FrontRead.Answer("1"), frontRead("1 <"))
        assertEquals(FrontRead.Answer("1 < 2"), frontRead("1 < 2"))
    }

    @Test fun aReplyThatEndsWithNothingOrHalfAMarkIsNotAHandOver() {
        assertEquals(FrontRead.Undecided, frontRead("", ended = true))
        assertEquals(FrontRead.Undecided, frontRead(" \n", ended = true))
        assertEquals(FrontRead.Answer("<<bu"), frontRead("<<bu", ended = true))
    }

    @Test fun theStreamOfARealHandOverIsDecidedAtTheMarkAndShowsNothing() {
        var said = QuickAnswer()
        val reads = listOf("<", "<bu", "ild>", ">", " sure").map { piece ->
            said = said.after(QuickEvent.Delta(piece))
            frontRead(said.text)
        }
        assertEquals(listOf(FrontRead.Undecided, FrontRead.Undecided, FrontRead.Undecided, FrontRead.HandOver(Route.BUILD), FrontRead.HandOver(Route.BUILD)), reads)
        // A mark that only comes as the last message, after a look, still hands over: the result is read whole.
        val after = QuickAnswer("Let me look.\n").after(QuickEvent.Result("<<build>>", failed = false, session = null))
        assertEquals(FrontRead.HandOver(Route.BUILD), frontRead(after.text, ended = true))
    }

    @Test fun aFrontAgentThatFailsWithNothingToShowFallsBackToTheBuildLane() {
        // Not installed, an error before any text, a timeout, a lost conversation: all are failed with nothing answered.
        assertEquals(FrontOutcome.FellBack, frontOutcome(handed = false, stopped = false, failed = true, answered = false))
        // No words at all from a run that did not fail.
        assertEquals(FrontOutcome.FellBack, frontOutcome(handed = false, stopped = false, failed = false, answered = false))
        assertEquals(FrontOutcome.Answered, frontOutcome(handed = false, stopped = false, failed = false, answered = true))
        // Part of an answer and then a failure: what he read stays, and `[ send to build ]` is under it.
        assertEquals(FrontOutcome.Broken, frontOutcome(handed = false, stopped = false, failed = true, answered = true))
    }

    @Test fun aStoppedRequestIsNeverSentOnAndAHandOverIsNeverSentTwice() {
        for (failed in listOf(false, true)) for (answered in listOf(false, true)) {
            assertEquals(FrontOutcome.Stopped, frontOutcome(handed = false, stopped = true, failed = failed, answered = answered))
            // Ending the process after the mark looks like a failure and a stop; it is neither.
            for (stopped in listOf(false, true)) assertEquals(FrontOutcome.HandedOver, frontOutcome(handed = true, stopped = stopped, failed = failed, answered = answered))
        }
    }

    @Test fun onlyWordsAloneWithAFreeFrontAgentAreAskedFirst() {
        assertTrue(frontFirst("hello", images = 0, ready = true, busy = false))
        assertFalse(frontFirst("hello", images = 1, ready = true, busy = false))
        assertFalse(frontFirst("hello", images = 0, ready = false, busy = false))
        assertFalse(frontFirst("hello", images = 0, ready = true, busy = true))
        assertEquals(ChatHistory.MAX_PROMPT, FRONT_MAX_REQUEST)
        assertTrue(frontFirst("a".repeat(FRONT_MAX_REQUEST), 0, ready = true, busy = false))
        assertFalse(frontFirst("a".repeat(FRONT_MAX_REQUEST + 1), 0, ready = true, busy = false))
    }

    private val minute = 60_000L
    private fun built(time: Long, prompt: String, result: String = "", status: TaskStatus = TaskStatus.Done) = ChatTask(time, "Claude Code", prompt, result = result, status = status)

    @Test fun theBoardOfAnIdleBuilderThatHasNeverBeenAsked() {
        val board = buildBoard(emptyList(), working = false, label = "Ready", now = 1_000)
        assertEquals(BuildBoard(working = false), board)
        assertEquals("[Board, for you only; Boss does not see it]\nBuilder: idle. It has not been asked anything yet.\n[Boss says]", boardText(board))
        // What the front agent answered or still has is no part of the build lane.
        val front = listOf(ChatTask(1, QUICK_AGENT, "capital?", result = "Paris.", status = TaskStatus.Done), ChatTask(2, UNDECIDED_AGENT, "hm"))
        assertEquals(board, buildBoard(front, working = false, label = "Ready", now = 1_000))
    }

    @Test fun theBoardOfAWorkingBuilderSaysWhatItIsOn() {
        val tasks = listOf(
            built(0, "earlier", result = "Earlier answer. Shall I go on?"),
            built(10 * minute, "make the clock tile\nshow \"seconds\"", status = TaskStatus.Running),
            built(11 * minute, "and bigger", status = TaskStatus.Queued),
        )
        val board = buildBoard(tasks, working = true, label = "Using Bash", now = 14 * minute)
        assertEquals(BuildBoard(working = true, label = "Using Bash", request = "make the clock tile\nshow \"seconds\"", spokeAgo = 3 * minute), board)
        assertEquals(
            "[Board, for you only; Boss does not see it]\nBuilder: working (Using Bash) on \"make the clock tile show 'seconds'\".\nBoss last spoke to the builder 3 min ago.\n[Boss says]",
            boardText(board),
        )
        val long = boardText(BuildBoard(working = true, label = "Approval needed", request = "x".repeat(900)))
        assertTrue(long.contains("Builder: working (Approval needed) on \"" + "x".repeat(BOARD_REQUEST_CHARS) + "...\"."))
        assertEquals(200, BOARD_REQUEST_CHARS)
    }

    @Test fun theBoardOfABuilderThatJustAskedCarriesTheEndOfItsAnswer() {
        val asked = "I found the cause.\n\nDo you want me to fix it now and bump the version?"
        val tasks = listOf(
            built(0, "why does the overview jump", result = asked),
            built(1 * minute, "never sent", result = "The app was closed", status = TaskStatus.Dropped),
            ChatTask(2 * minute, QUICK_AGENT, "capital?", result = "Paris.", status = TaskStatus.Done),
        )
        val board = buildBoard(tasks, working = false, label = "Done", now = 2 * minute + 30_000)
        assertEquals(BuildBoard(working = false, lastAnswer = asked, spokeAgo = 2 * minute + 30_000), board)
        assertEquals(
            "[Board, for you only; Boss does not see it]\nBuilder: idle. Its last answer ended: \"I found the cause. Do you want me to fix it now and bump the version?\"\n" +
                "Boss last spoke to the builder 2 min ago.\n[Boss says]",
            boardText(board),
        )
        val long = boardText(BuildBoard(working = false, lastAnswer = "start " + "y".repeat(900) + " Shall I?", spokeAgo = 0))
        assertTrue(long.contains("ended: \"..." + ("y".repeat(900) + " Shall I?").takeLast(BOARD_ANSWER_CHARS) + "\"\n"))
        assertFalse(long.contains("start"))
        assertTrue(long.contains("Boss last spoke to the builder just now."))
        assertEquals(300, BOARD_ANSWER_CHARS)
        assertTrue(boardText(BuildBoard(false, spokeAgo = 5 * 60 * minute)).contains("5 hours ago"))
        assertTrue(boardText(BuildBoard(false, spokeAgo = 72 * 60 * minute)).contains("3 days ago"))
    }

    @Test fun hisWordsFollowTheBoardUnchanged() {
        val words = "  yes do that\n<<build>> \"now\"  "
        val sent = frontPrompt(BuildBoard(working = false), words)
        assertTrue(sent.startsWith("[Board, for you only; Boss does not see it]\n"))
        assertTrue(sent.endsWith("\n[Boss says]\n$words"))
    }

    @Test fun aCorrectionIsOneLineWithTheStartOfTheRequest() {
        val line = routingCorrection(1_760_000_000_000, "add a star\nsticker \"here\" " + "z".repeat(900))
        assertFalse(line.contains("\n"))
        val read = JSONObject(line)
        assertEquals(setOf("time", "request", "was"), read.keys().asSequence().toSet())
        assertEquals(1_760_000_000_000, read.getLong("time"))
        assertEquals("front-answered", read.getString("was"))
        assertEquals(500, read.getString("request").length)
        assertTrue(read.getString("request").startsWith("add a star\nsticker \"here\" zzz"))
    }

    @Test fun theCorrectionsLogKeepsItsNewestTwoHundredLines() {
        var lines = emptyList<String>()
        for (i in 1..250) lines = routingLog(lines, routingCorrection(i.toLong(), "request $i"))
        assertEquals(ROUTING_LOG_LINES, lines.size); assertEquals(200, ROUTING_LOG_LINES)
        assertEquals(51L, JSONObject(lines.first()).getLong("time")); assertEquals(250L, JSONObject(lines.last()).getLong("time"))
        assertEquals(listOf("a", "b"), routingLog(listOf("a", "", " "), "b"))
    }
}
