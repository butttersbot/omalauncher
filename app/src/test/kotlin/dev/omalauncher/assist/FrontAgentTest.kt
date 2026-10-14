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


    // Project threads on the Mac

    private val now = 1_760_000_600_000L
    private fun onMac(id: String, name: String = "FF4 mod", status: MacThreadStatus = MacThreadStatus.Idle, worked: Long? = null, summary: String = "", tail: String = "", pending: List<MacApproval> = emptyList()) =
        MacThread(id, name, "/Users/boss/Projects/x", ThreadHome.Mac, "claude", summary, status, worked, tail, 0, 0, pending)

    @Test fun aMarkForAThreadIsNotDecidedUntilItCloses() {
        assertEquals("<<mac t1a2b3c4d>>", handOverMark(Route.mac("t1a2b3c4d")))
        assertEquals("t1a2b3c4d", Route.macThread("mac t1a2b3c4d")); assertNull(Route.macThread("build")); assertNull(Route.macThread("mac"))
        for (partial in listOf("<<m", "<<mac", "<<mac ", "<<mac t1a2", "<<mac t1a2b3c4d>", " `<<mac t1")) assertEquals(partial, FrontRead.Undecided, frontRead(partial))
        for (whole in listOf("<<mac t1a2b3c4d>>", " <<mac t1a2b3c4d>>\n", "`<<mac t1a2b3c4d>>`", "<<mac  t1a2b3c4d >> sure", "<<mac t1a2b3c4d>>\nOn it.")) {
            assertEquals(whole, FrontRead.HandOver("mac t1a2b3c4d"), frontRead(whole))
            assertEquals(whole, FrontRead.HandOver("mac t1a2b3c4d"), frontRead(whole, ended = true))
        }
        var said = QuickAnswer()
        val reads = listOf("<<m", "ac t1", ">", ">").map { piece ->
            said = said.after(QuickEvent.Delta(piece))
            frontRead(said.text)
        }
        assertEquals(listOf(FrontRead.Undecided, FrontRead.Undecided, FrontRead.Undecided, FrontRead.HandOver("mac t1")), reads)
    }

    @Test fun aMarkGoesOnlyToAThreadOnTheBoardByItsExactId() {
        // Two threads with one name and one project, as the real runner has: only the id tells them apart.
        val board = macBoard(listOf(onMac("t1a2b3c4d", "Scratch"), onMac("t9z8y7x6w", "Scratch")), reachable = true, seen = now, now = now)
        assertEquals(FrontRoute.Build, frontRoute(Route.BUILD, board)); assertEquals(FrontRoute.Build, frontRoute(Route.BUILD, null))
        assertEquals("t9z8y7x6w", (frontRoute("mac t9z8y7x6w", board) as FrontRoute.Mac).thread.id)
        // Anything else names no thread. Nothing is guessed: not a near id, not a name, not the only thread there is.
        for (named in listOf("", "T1A2B3C4D", "t1a2b3c4", "t1a2b3c4d ", "Scratch", "t1a2b3c4d; rm -rf /", "../t1a2b3c4d", "t1a2b3c4d\nt9z8y7x6w")) {
            assertEquals(named, FrontRoute.Unknown(named), frontRoute(Route.mac(named), board))
        }
        assertEquals(FrontRoute.Unknown("t1a2b3c4d"), frontRoute("mac t1a2b3c4d", null))
        assertEquals(FrontRoute.Unknown("t1a2b3c4d"), frontRoute("mac t1a2b3c4d", macBoard(listOf(onMac("t9z8y7x6w")), true, now, now)))
        assertTrue(WHICH_PROJECT.startsWith("Which project is this for?"))
    }

    @Test fun aMarkWithOddCharactersOrNoEndNamesNothingAndIsNeverShown() {
        assertEquals(FrontRead.HandOver("mac t1; rm -rf /"), frontRead("<<mac t1; rm -rf />>"))
        assertEquals(FrontRead.HandOver("mac a<b"), frontRead("<<mac a<b>>"))
        // Never closed, broken over a line, or far too long: a hand-over to nowhere, not an answer with a mark in it.
        for (broken in listOf("<<mac t1", "<<mac t1>", "<<mac")) assertEquals(broken, FrontRead.HandOver("mac "), frontRead(broken, ended = true))
        assertEquals(FrontRead.HandOver("mac "), frontRead("<<mac t1\nand more"))
        assertEquals(FrontRead.HandOver("mac "), frontRead("<<mac " + "x".repeat(200)))
        assertEquals(FrontRead.HandOver("mac "), frontRead("<<mac " + "y".repeat(MAC_ID_CHARS + 1) + ">>"))
        assertEquals(64, MAC_ID_CHARS)
        assertTrue(macNameable("t1a2b3c4d")); for (bad in listOf("", "a b", "a>b", "a<b", "a`b", "x".repeat(65))) assertFalse(bad, macNameable(bad))
    }

    @Test fun anAnswerThatOnlyMentionsAThreadsMarkIsShownWithoutIt() {
        assertEquals(FrontRead.Answer("Sent to  now."), frontRead("Sent to <<mac t1a2b3c4d>> now.", ended = true))
        assertEquals(FrontRead.Answer("Sure!"), frontRead("Sure! <<mac t1a2b3c4d>>"))
        assertEquals(FrontRead.Answer("Sure!"), frontRead("Sure! <<mac t1"))
        assertEquals(FrontRead.Answer("Sure!"), frontRead("Sure! <<m"))
        assertEquals(FrontRead.Answer("Sure! <<mac t1"), frontRead("Sure! <<mac t1", ended = true))
    }

    /** Pinned: a phone with no Mac set up sends the front agent exactly what it always did. */
    @Test fun withNoMacTheBoardIsByteForByteWhatItWas() {
        val idle = BuildBoard(working = false)
        assertEquals("[Board, for you only; Boss does not see it]\nBuilder: idle. It has not been asked anything yet.\n[Boss says]", boardText(idle, null))
        val working = BuildBoard(working = true, label = "Using Bash", request = "make the clock tile", spokeAgo = 3 * minute)
        assertEquals("[Board, for you only; Boss does not see it]\nBuilder: working (Using Bash) on \"make the clock tile\".\nBoss last spoke to the builder 3 min ago.\n[Boss says]", boardText(working, null))
        for (board in listOf(idle, working)) {
            assertEquals(boardText(board), boardText(board, null))
            assertEquals(frontPrompt(board, "hello"), frontPrompt(board, "hello", null))
        }
    }

    @Test fun theBoardListsTheMacsThreadsAfterTheBuilder() {
        val threads = listOf(
            onMac("t9z8y7x6w", "Phone proof"),
            onMac("t1a2b3c4d", "Scratch", MacThreadStatus.Working, worked = 1_760_000_420, summary = "what files\nare \"here\"", tail = "Files here: notes.md"),
        )
        assertEquals(
            "[Board, for you only; Boss does not see it]\nBuilder: idle. It has not been asked anything yet.\nMac: reachable.\nProject threads on the Mac:\n" +
                "- \"Scratch\" id t1a2b3c4d: working, last worked 3 min ago; Boss last asked it \"what files are 'here'\"; it last said \"Files here: notes.md\"\n" +
                "- \"Phone proof\" id t9z8y7x6w: idle\n[Boss says]",
            boardText(BuildBoard(working = false), macBoard(threads, reachable = true, seen = now, now = now)),
        )
        val away = boardText(BuildBoard(working = false), macBoard(threads, reachable = false, seen = now - 5 * minute, now = now))
        assertTrue(away, away.contains("\nMac: not reachable, last seen 5 min ago.\nProject threads on the Mac:\n- \"Scratch\" id t1a2b3c4d: "))
        assertTrue(boardText(BuildBoard(false), macBoard(emptyList(), false, null, now)).contains("\nMac: not reachable, not seen yet.\nProject threads on the Mac: none.\n[Boss says]"))
        assertTrue(frontPrompt(BuildBoard(false), "  go on  ", macBoard(threads, true, now, now)).endsWith("\n[Boss says]\n  go on  "))
    }

    @Test fun theBoardIsCappedAndNeverCarriesAnApproval() {
        val many = (1..12).map { onMac("t$it", "Project $it", worked = 1_760_000_000L + it) } + onMac("bad id", "Unnameable", worked = 1_760_000_500)
        val board = macBoard(many, reachable = true, seen = now, now = now)
        assertEquals(8, BOARD_THREADS); assertEquals((12 downTo 5).map { "t$it" }, board.threads.map { it.id })
        val waiting = onMac(
            "t1a2b3c4d", "n".repeat(500), MacThreadStatus.Waiting, worked = 1_760_000_000, summary = "s".repeat(500), tail = "start " + "e".repeat(500),
            pending = listOf(MacApproval("approval-xyz", "n-secret", "Write", """{"content":"hush"}""", "because")),
        )
        val text = boardText(BuildBoard(false), macBoard(listOf(waiting), true, now, now))
        val line = text.lines().single { it.startsWith("- ") }
        assertTrue(line.length <= BOARD_THREAD_CHARS); assertEquals(320, BOARD_THREAD_CHARS)
        assertTrue(line, line.contains("waiting for Boss to approve a step")); assertFalse(line.contains("start"))
        for (never in listOf("n-secret", "approval-xyz", "hush", "because", "Write")) assertFalse(never, text.contains(never))
    }

    @Test fun theRoutingLogSaysWhichKindOfWrongTurnItWas() {
        assertEquals("unknown-thread", UNKNOWN_THREAD); assertEquals("mac-tried-again", MAC_TRIED_AGAIN); assertEquals("front-answered", FRONT_ANSWERED)
        for (was in listOf(UNKNOWN_THREAD, MAC_TRIED_AGAIN)) {
            val read = JSONObject(routingCorrection(5, "fix the menu", was))
            assertEquals(setOf("time", "request", "was"), read.keys().asSequence().toSet()); assertEquals(was, read.getString("was"))
        }
    }
}
