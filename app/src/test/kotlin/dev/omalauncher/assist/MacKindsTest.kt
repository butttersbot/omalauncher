package dev.omalauncher.assist

import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import org.junit.Assert.*
import org.junit.Test

/**
 * Never held up by one agent (Boss, 2026-10-13): a turn at its agent's limit goes on to the next agent on the same
 * project, and a limit is remembered for a while. docs/MAC-RUNNER-SPEC.md, "As built: Codex and Cursor threads".
 */
class MacKindsTest {
    private val all = listOf("claude", "codex", "cursor")

    private fun thread(id: String, kind: String, status: MacThreadStatus = MacThreadStatus.Idle, project: String = "~/ClaudeSync/projects/ff4", name: String = "FF4 better menu mod", worked: Long? = 100) =
        MacThread(id, name, project, ThreadHome.Mac, kind, "", status, worked, "", 0, 0, emptyList())

    @Test fun theAgentsAreClaudeCodexGrokCursorAndVeniceInThatOrder() {
        assertEquals(listOf("claude", "codex", "grok", "cursor", "venice"), MAC_KINDS)
        assertEquals("claude", macKindOf("")); assertEquals("codex", macKindOf(" Codex "))
        assertEquals(listOf("Claude", "Codex", "Grok", "Cursor", "Venice"), MAC_KINDS.map(::macKindName)); assertEquals("Claude", macKindName(""))
        // What the Mac has, in the order requests go from one to the next, whatever order /health lists them in.
        assertEquals(listOf("claude", "cursor"), macInstalled(listOf("cursor", "claude")))
        assertEquals(listOf("claude", "grok"), macInstalled(listOf("grok", "claude")))
        // A runner that has not said, or not been asked yet, has Claude, which every runner runs.
        assertEquals(listOf("claude"), macInstalled(emptyList())); assertEquals(listOf("claude"), macInstalled(listOf("opencode")))
    }

    @Test fun theNextAgentIsTheFirstInstalledThatIsNotAtItsLimit() {
        assertEquals("claude", macKind(all, emptySet()))
        assertEquals("codex", macKind(all, setOf("claude")))
        assertEquals("cursor", macKind(all, setOf("claude", "codex")))
        assertNull(macKind(all, setOf("claude", "codex", "cursor")))
        // One the Mac does not have is passed over.
        assertEquals("cursor", macKind(listOf("claude", "cursor"), setOf("claude")))
        assertNull(macKind(listOf("claude"), setOf("claude")))
        // Codex alone at its limit leaves Claude first.
        assertEquals("claude", macKind(all, setOf("codex")))
    }

    @Test fun onlyAnErrorWhoseCodeIsLimitEndsATurnAtALimit() {
        assertTrue(macLimited(MacEvent.Error(1, 0, "You've hit your usage limit", null, MAC_LIMIT)))
        assertFalse(macLimited(MacEvent.Error(1, 0, "refused", null, "agent")))
        assertFalse(macLimited(MacEvent.Error(1, 0, "runner failed", null, "runner")))
        assertFalse(macLimited(MacEvent.Error(1, 0, "The agent ended without an answer.", 1)))
        // An answer that only talks about a limit is an answer: nothing is sent again for it.
        assertFalse(macLimited(MacEvent.Result(1, 0, "usage limit reached", failed = true)))
        assertFalse(macLimited(MacEvent.Text(1, 0, "rate limit")))
    }

    @Test fun aRequestForALimitedAgentsThreadGoesToTheFirstAgentThatIsNot() {
        assertEquals("codex", macInstead("claude", all, setOf("claude")))
        assertEquals("codex", macInstead("", all, setOf("claude")))
        assertNull(macInstead("claude", all, emptySet()))
        assertNull(macInstead("codex", all, setOf("claude")))
        // With every agent at its limit the thread is tried as it is: a limit remembered may be over already.
        assertNull(macInstead("claude", all, all.toSet()))
        assertNull(macInstead("claude", listOf("claude"), setOf("claude")))
    }

    @Test fun aLimitIsRememberedForAFewMinutes() {
        assertEquals(15 * 60_000L, MAC_LIMIT_MEMORY_MS)
        val limits = MacLimits()
        assertEquals(emptySet<String>(), limits.at(0))
        limits.hit("Claude", 1_000)
        assertEquals(setOf("claude"), limits.at(1_000)); assertEquals(setOf("claude"), limits.at(1_000 + MAC_LIMIT_MEMORY_MS - 1))
        assertEquals(emptySet<String>(), limits.at(1_000 + MAC_LIMIT_MEMORY_MS))
        // Hit again, it is remembered from the newest.
        limits.hit("claude", 600_000); limits.hit("codex", 700_000)
        assertEquals(setOf("claude", "codex"), limits.at(1_000 + MAC_LIMIT_MEMORY_MS))
        assertEquals(setOf("codex"), limits.at(600_000 + MAC_LIMIT_MEMORY_MS))
        // A thread with no kind is Claude's.
        val blank = MacLimits(10).apply { hit("", 0) }
        assertEquals(setOf("claude"), blank.at(5))
    }

    /** OPEN-BUGS #12: a launcher restart forgot the limits, and the next request went to the agent still at one. */
    @Test fun aLimitOutlivesARestartUntilItsWallClockEnd() {
        val before = MacLimits().apply { hit("claude", 1_000); hit("codex", 2_000) }
        val kept = before.text(3_000)
        // A restart five minutes on: both are still at their limit, each until the same moment as before.
        val after = MacLimits().apply { restore(kept, 300_000) }
        assertEquals(setOf("claude", "codex"), after.at(300_000))
        assertEquals(setOf("codex"), after.at(1_000 + MAC_LIMIT_MEMORY_MS))
        assertEquals(emptySet<String>(), after.at(2_000 + MAC_LIMIT_MEMORY_MS))
        // Restarted after they ended, nothing is held off; what had ended is not kept.
        assertEquals(emptySet<String>(), MacLimits().apply { restore(kept, 2_000 + MAC_LIMIT_MEMORY_MS) }.at(2_000 + MAC_LIMIT_MEMORY_MS))
        assertEquals("{\"codex\":${2_000 + MAC_LIMIT_MEMORY_MS}}", before.text(1_000 + MAC_LIMIT_MEMORY_MS))
        // A clock set back since never holds an agent off longer than a limit lasts.
        val early = MacLimits().apply { restore(kept, -10 * MAC_LIMIT_MEMORY_MS) }
        assertEquals(emptySet<String>(), early.at(-9 * MAC_LIMIT_MEMORY_MS))
        // A limit heard since the restart stands when it ends later; nothing readable is no limit.
        val heard = MacLimits().apply { hit("claude", 600_000); restore(kept, 600_000) }
        assertEquals(setOf("claude", "codex"), heard.at(1_000 + MAC_LIMIT_MEMORY_MS))
        assertEquals(setOf("claude"), heard.at(2_000 + MAC_LIMIT_MEMORY_MS))
        for (bad in listOf(null, "", "nonsense", "{\"claude\":\"soon\"}")) assertEquals(emptySet<String>(), MacLimits().apply { restore(bad, 0) }.at(0))
    }

    @Test fun theEntrySaysPlainlyWhatHappened() {
        assertEquals("Claude hit its limit · sent to Codex", macLimitStep("claude", "codex"))
        assertEquals("Codex hit its limit · sent to Cursor", macLimitStep("codex", "cursor"))
        assertEquals("Claude is at its limit · using Codex", macLimitedStep("claude", "codex"))
        assertEquals("Every agent on the Mac is at its limit (Claude, Codex, Cursor).", macAllLimited(all))
        assertEquals("Every agent on the Mac is at its limit (Claude).", macAllLimited(emptyList()))
        assertEquals("Every agent on the Mac is at its limit (Claude, Cursor).", macAllLimited(listOf("cursor", "claude")))
    }

    @Test fun aThreadMadeForAnotherAgentIsNamedAfterIt() {
        assertEquals("FF4 better menu mod · Codex", macKindThreadName("FF4 better menu mod", "codex", emptyList()))
        assertEquals("FF4 better menu mod · Codex 2", macKindThreadName("FF4 better menu mod", "codex", listOf("ff4 better menu mod · codex")))
        // From a thread made at a limit, or a numbered one, the next is named after the project's first thread.
        assertEquals("FF4 better menu mod · Cursor", macKindThreadName("FF4 better menu mod · Codex", "cursor", emptyList()))
        assertEquals("FF4 better menu mod · Codex", macKindThreadName("FF4 better menu mod 2", "codex", emptyList()))
        assertEquals("FF4 better menu mod · Cursor", macKindThreadName("FF4 better menu mod · Codex 3", "cursor", emptyList()))
        assertEquals("Mac · Codex", macKindThreadName(" ", "codex", emptyList()))
        val long = macKindThreadName("x".repeat(80), "cursor", listOf("x".repeat(80)))
        assertTrue(long.length <= 60); assertTrue(long.endsWith(" · Cursor"))
        // A Claude thread beside one made at a limit is numbered from the project's first name, not the agent's.
        assertEquals("FF4 better menu mod 2", macFreshName("FF4 better menu mod · Codex", listOf("FF4 better menu mod", "FF4 better menu mod · Codex")))
        assertEquals("FF4 better menu mod", macBaseName("FF4 better menu mod · codex 2"))
        // A name that only has a dot in it keeps it.
        assertEquals("Phone · Mac", macBaseName("Phone · Mac"))
    }

    @Test fun aRequestSentToAnotherAgentTakesAFreeThreadOfItsOnTheSameProjectFirst() {
        val claude = thread("t1", "claude")
        val codexIdle = thread("t2", "codex", worked = 50)
        val codexNewer = thread("t3", "codex", worked = 90)
        val codexBusy = thread("t4", "codex", MacThreadStatus.Working, worked = 200)
        val elsewhere = thread("t5", "codex", project = "~/Projects/pond", worked = 300)
        val threads = listOf(claude, codexIdle, codexNewer, codexBusy, elsewhere)
        assertEquals(codexNewer, macKindThread(claude.project, "codex", threads))
        // One with a request of his unfinished there is busy too.
        assertEquals(codexIdle, macKindThread(claude.project, "codex", threads, owed = setOf("t3")))
        assertNull(macKindThread(claude.project, "cursor", threads))
        assertEquals(claude, macKindThread("/Users/boss/ClaudeSync/projects/ff4/", "", threads))
    }

    @Test fun aTurnAtItsLimitWaitsForTheNextAgentsThreadAndNothingTheOldOneSaysEndsIt() {
        val old = thread("t1", "claude")
        val running = macTaken(macHandOver(ChatTask(5, UNDECIDED_AGENT, "fix the menu"), old))
            .copy(result = "Looking at", approvals = listOf(ChatApproval("Edit", "menu.c", ApprovalAnswer.Waiting, "a1")))
        val moved = macLimitHandOver(running, "FF4 better menu mod · Codex", macLimitStep("claude", "codex"))
        assertEquals("→ FF4 better menu mod · Codex", moved.agent); assertEquals("FF4 better menu mod · Codex", moved.threadName)
        assertEquals(listOf("Claude hit its limit · sent to Codex"), moved.steps)
        assertEquals(TaskStatus.Queued, moved.status); assertTrue(moved.fresh); assertEquals("", moved.result); assertEquals("fix the menu", moved.prompt)
        assertEquals(ApprovalAnswer.Unanswered, moved.approvals.single().answer)
        // It stands under the old thread's id until the new one is made: what the old thread says after does not touch it.
        for (event in listOf(MacEvent.Error(9, 0, "limit", null, MAC_LIMIT), MacEvent.Message(10, 0, "fix the menu"), MacEvent.Result(11, 0, "x", false))) {
            assertEquals(listOf(moved), macApply(listOf(moved), "t1", event))
        }
        assertEquals(listOf(moved), macSettle(listOf(moved), "t1", MacThreadStatus.Idle, 0))
        assertEquals(listOf(moved), macAbandon(listOf(moved), "t1", "gone"))
        // Made, it is the new thread's, and the Mac's taking the same words begins it there.
        val made = macMade(moved, thread("t2", "codex", name = "FF4 better menu mod · Codex"))
        assertEquals("t2", made.thread); assertFalse(made.fresh)
        assertEquals(TaskStatus.Running, macApply(listOf(made), "t2", MacEvent.Message(1, 0, "fix the menu")).single().status)
        // With every agent at its limit it needs him, and is his to send again.
        val stuck = macAllLimitedEntry(running, all)
        assertEquals(TaskStatus.Dropped, stuck.status); assertEquals("Every agent on the Mac is at its limit (Claude, Codex, Cursor).", stuck.result)
        assertEquals(ApprovalAnswer.Unanswered, stuck.approvals.single().answer)
        assertEquals(TaskStatus.Queued, macRetried(stuck)?.status)
    }
}
