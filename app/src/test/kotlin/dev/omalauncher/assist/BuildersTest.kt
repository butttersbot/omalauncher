package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The phone's pool of builders (Boss, 2026-10-13: "we can start with 2 Builders"): which one gets a request,
 * how each is named, and that the approvals of one are never answered for the other.
 */
class BuildersTest {
    private fun approval(id: String) = AgentApproval(id, "Bash", JSONObject().put("command", "echo $id"), "")

    @Test fun thereAreTwoBuildersAndAIsTheOneThereAlwaysWas() {
        assertEquals(2, PHONE_BUILDERS)
        assertEquals(listOf(BuilderId.A, BuilderId.B), AgentTasks.builders.map { it.id })
        // What was saved before there were two is A's: entries, tile keys and a notification's stop without a builder.
        assertEquals("", BuilderId.A.saved); assertEquals("B", BuilderId.B.saved)
        assertEquals(PHONE_THREAD, BuilderId.A.thread); assertEquals("phone-b", BuilderId.B.thread)
        for (none in listOf(null, "", "C", "x", "builder")) assertEquals(BuilderId.A, BuilderId.of(none))
        assertEquals(BuilderId.B, BuilderId.of("B"))
        for (b in listOf("B", "b", " B ", "2")) assertEquals(b, BuilderId.B, BuilderId.named(b))
        for (a in listOf("A", "a", "1")) assertEquals(a, BuilderId.A, BuilderId.named(a))
        for (none in listOf(null, "", "C", "3", "AB", "t5e121ba2")) assertNull(none, BuilderId.named(none))
        assertEquals(BuilderId.A, ChatTask(1, "Claude Code", "x").builderId)
        assertEquals(BuilderId.B, ChatTask(1, "Claude Code", "x", builder = "B").builderId)
    }

    @Test fun newWorkGoesToAFreeBuilderAndOnlyWaitsWhenBothAreBusy() {
        fun loads(a: BuilderLoad, b: BuilderLoad) = mapOf(BuilderId.A to a, BuilderId.B to b)
        val free = BuilderLoad(working = false)
        // With A free nothing differs from before there were two.
        assertEquals(BuilderId.A, pickBuilder(null, loads(free, free)))
        assertEquals(BuilderId.A, pickBuilder(null, emptyMap()))
        // A at work: the next request starts B rather than waiting behind A.
        assertEquals(BuilderId.B, pickBuilder(null, loads(BuilderLoad(true, 3), free)))
        assertEquals(BuilderId.A, pickBuilder(null, loads(free, BuilderLoad(true))))
        // Both at work: it waits behind the one with fewer waiting, A on a tie.
        assertEquals(BuilderId.A, pickBuilder(null, loads(BuilderLoad(true, 1), BuilderLoad(true, 1))))
        assertEquals(BuilderId.B, pickBuilder(null, loads(BuilderLoad(true, 2), BuilderLoad(true, 1))))
        assertEquals(BuilderId.A, pickBuilder(null, loads(BuilderLoad(true, 0), BuilderLoad(true, 4))))
    }

    @Test fun aFollowUpGoesToItsOwnBuilderWhateverTheOtherIsDoing() {
        val busyA = mapOf(BuilderId.A to BuilderLoad(true, 2), BuilderId.B to BuilderLoad(false))
        // A reply to what A asked waits behind A's task, and never goes to the free B.
        assertEquals(BuilderId.A, pickBuilder(BuilderId.A, busyA))
        assertEquals(BuilderId.B, pickBuilder(BuilderId.B, busyA))
        assertEquals(BuilderId.B, pickBuilder(BuilderId.B, mapOf(BuilderId.A to BuilderLoad(false), BuilderId.B to BuilderLoad(true))))
    }

    @Test fun theNotificationFollowsOneBuilderAndSaysWhenTheOtherWorksToo() {
        assertEquals("Using Bash", poolLabel(BuilderId.A, "Using Bash", 0))
        assertEquals("Using Bash · and 1 more", poolLabel(BuilderId.A, "Using Bash", 1))
        assertEquals("Builder B · Approval needed", poolLabel(BuilderId.B, "Approval needed", 0))
        // A finished on its own reads as it always did.
        assertEquals("Done", poolLabel(BuilderId.A, "Done", 0))
    }

    @Test fun builderBIsShownOnceItHasWorked() {
        val a = ChatTask(1, "Claude Code", "x", status = TaskStatus.Done)
        assertFalse(builderBShown(listOf(a), working = false))
        assertTrue(builderBShown(listOf(a), working = true))
        assertTrue(builderBShown(listOf(a, a.copy(time = 2, builder = "B")), working = false))
        // An answer of the front agent's is nobody's builder work.
        assertFalse(builderBShown(listOf(a.copy(agent = QUICK_AGENT, builder = "B")), working = false))
    }

    /** An approval card, notification and screen name the builder that asks. */
    @Test fun anApprovalNamesItsBuilder() {
        assertEquals("Allow Bash for Builder A?", builderApprovalTitle("Bash", BuilderId.A))
        assertEquals("Allow Write for Builder B?", builderApprovalTitle("Write", BuilderId.B))
        assertEquals(60, builderApprovalTitle("x".repeat(200), BuilderId.B).removePrefix("Allow ").removeSuffix(" for Builder B?").length)
    }

    /**
     * Boss 2026-10-13: two builders. Each builder keeps a ledger of its own; one slot is one notification, so
     * no two live approvals of the two builders share a notification id or a button's request code, and a tap
     * is answered only by the builder that holds its nonce, never by the other.
     */
    @Test fun twoBuildersApprovalsNeverShareASlotAndATapOnlyAnswersItsOwn() {
        val a = ApprovalLedger(); val b = ApprovalLedger()
        val live = mutableListOf<PendingApproval>()
        repeat(ApprovalIds.MAX_LIVE / 2) {
            live += (a.admit(approval("a$it"), b.live.map { e -> e.slot }.toSet()) as Admission.Shown).entry
            live += (b.admit(approval("b$it"), a.live.map { e -> e.slot }.toSet()) as Admission.Shown).entry
        }
        assertEquals(live.size, live.map { it.notificationId }.toSet().size)
        // Between them they hold every slot, and nothing more is shown by either.
        assertEquals(Admission.Refused, a.admit(approval("more"), b.live.map { it.slot }.toSet()))
        // The same id from both agents is still two approvals, told apart by their nonces.
        val a2 = ApprovalLedger(); val b2 = ApprovalLedger()
        val fromA = (a2.admit(approval("same")) as Admission.Shown).entry
        val fromB = (b2.admit(approval("same"), a2.live.map { it.slot }.toSet()) as Admission.Shown).entry
        assertNotEquals(fromA.slot, fromB.slot)
        val waiting = mapOf(BuilderId.A to a2.live, BuilderId.B to b2.live)
        assertEquals(BuilderId.B, approvalHolder(waiting, "same", fromB.nonce))
        assertEquals(BuilderId.A, approvalHolder(waiting, "same", fromA.nonce))
        assertNull(approvalHolder(waiting, "same", "00000000-0000-0000-0000-000000000000"))
        assertNull(approvalHolder(waiting, "other", fromB.nonce))
        // Answering B's leaves A's waiting.
        assertTrue(b2.claim("same", fromB.nonce, locked = false, active = true) is Claim.Granted)
        assertEquals(listOf(fromA), a2.live)
        assertNull(approvalHolder(mapOf(BuilderId.A to a2.live, BuilderId.B to b2.live), "same", fromB.nonce))
    }

    /** The phone control guard counts what waits for every builder, as it already adds the Mac's. */
    @Test fun thePhoneControlGuardCountsEveryBuildersApprovals() {
        val root = dev.omalauncher.theme.pack.moduleFile("src/main/kotlin/dev/omalauncher")
        val guard = java.io.File(root, "phone/PhoneService.kt").readText()
        assertTrue(guard.contains("dev.omalauncher.assist.AgentTasks.pending.size + dev.omalauncher.assist.MacThreads.pending.size"))
        val tasks = java.io.File(root, "assist/AgentTasks.kt").readText()
        assertTrue(tasks.contains("val pending: List<PendingApproval> get() = builders.flatMap { it.pending }"))
        assertTrue(AgentTasks.pending.isEmpty())
    }

    @Test fun aBuilderGetsAWorktreeOnlyBesideTheOtherAndOnlyAsClaude() {
        val bothBusy = mapOf(BuilderId.A to true, BuilderId.B to true)
        assertTrue(builderWorktreeWanted(BuilderId.B, mapOf(BuilderId.A to true, BuilderId.B to false), claude = true))
        assertTrue(builderWorktreeWanted(BuilderId.A, bothBusy, claude = true))
        // Alone it works in the shared checkout as it always has; another agent keeps the note it always had.
        assertFalse(builderWorktreeWanted(BuilderId.A, mapOf(BuilderId.A to true, BuilderId.B to false), claude = true))
        assertFalse(builderWorktreeWanted(BuilderId.B, emptyMap(), claude = true))
        assertFalse(builderWorktreeWanted(BuilderId.B, bothBusy, claude = false))
        assertEquals(WorktreePlan(PHONE_REPO, "/root/wt/builder-b", "builder-b"), builderWorktree(BuilderId.B))
        assertEquals(WorktreePlan(PHONE_REPO, "/root/wt/builder-a", "builder-a"), builderWorktree(BuilderId.A))
    }

    @Test fun theNoteNamesTheWorktreeAndHowToMergeBackAndTakesTheAdvicesPlace() {
        val plan = builderWorktree(BuilderId.B)
        assertEquals(BuilderWorktree(plan, "phone/fold-development", current = true), plan.made("OMA-WORKTREE current phone/fold-development"))
        assertEquals(BuilderWorktree(plan, "main", current = false), plan.made("OMA-WORKTREE kept main\n"))
        for (other in listOf("", "OMA-WORKTREE", "OMA-WORKTREE current", "OMA-WORKTREE made main", "{\"type\":\"system\"}", "say OMA-WORKTREE current main")) assertNull(other, plan.made(other))
        val note = plan.made("OMA-WORKTREE current phone/fold-development")!!.note
        assertTrue(note.startsWith(SHARED_NOTE_OPEN) && note.endsWith("]") && '\n' !in note)
        for (said in listOf("/root/wt/builder-b", "branch builder-b", "cd /root/wt/builder-b", "git -C /root/Projects/omalauncher merge --ff-only builder-b", "stop and tell Boss")) assertTrue(said, said in note)
        assertTrue("do not throw it away" in plan.made("OMA-WORKTREE kept main")!!.note)
        assertFalse("do not throw it away" in note)
        // The advice the front agent put ahead of the request gives way to it; anything else stays under it.
        assertEquals("$note\nhello", withWorktreeNote(PHONE_SHARED_NOTE + "\nhello", note))
        assertEquals("$note\nhello", withWorktreeNote("hello", note))
    }

    private fun sh(dir: java.io.File, script: String): String {
        val run = ProcessBuilder("sh", "-c", script).directory(dir).redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        assertEquals(out, 0, run.waitFor())
        return out
    }

    @Test fun theWorktreeIsMadeBroughtUpOnlyWhenCleanAndNeverMadeOfAStrangeFolder() {
        org.junit.Assume.assumeTrue(runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val top = kotlin.io.path.createTempDirectory("worktree").toFile().canonicalFile
        try {
            val main = java.io.File(top, "repo").apply { mkdirs() }
            val git = "git -c user.name=t -c user.email=t@t -c init.defaultBranch=shared"
            sh(main, "$git init -q . && echo one > a && git add a && $git commit -qm one")
            val plan = builderWorktree(BuilderId.B, main.path, "${top.path}/wt")
            val wt = java.io.File(plan.path)
            // Made from the shared branch as it is now, on a branch of the builder's own.
            assertEquals("OMA-WORKTREE current shared\n", sh(main, plan.script()))
            assertEquals("one\n", java.io.File(wt, "a").readText())
            assertEquals("builder-b\n", sh(wt, "git rev-parse --abbrev-ref HEAD"))
            // The shared branch moved on: a clean worktree follows it there.
            sh(main, "echo two > a && $git commit -qam two")
            assertEquals("OMA-WORKTREE current shared\n", sh(main, plan.script()))
            assertEquals("two\n", java.io.File(wt, "a").readText())
            // Work left in it is left alone, uncommitted or committed and not merged.
            java.io.File(wt, "a").writeText("mine\n")
            assertEquals("OMA-WORKTREE kept shared\n", sh(main, plan.script()))
            sh(main, "echo three > b && git add b && $git commit -qm three")
            assertEquals("OMA-WORKTREE kept shared\n", sh(main, plan.script()))
            assertEquals("mine\n", java.io.File(wt, "a").readText())
            sh(wt, "$git commit -qam mine")
            assertEquals("OMA-WORKTREE kept shared\n", sh(main, plan.script()))
            assertEquals("mine\n", java.io.File(wt, "a").readText())
            // Merged back, it is current again.
            sh(main, "$git merge -q --no-edit builder-b")
            assertEquals("OMA-WORKTREE current shared\n", sh(main, plan.script()))
            // Its folder gone, it is made again on the branch it had.
            sh(main, "git worktree remove --force ${wt.path}")
            assertEquals("OMA-WORKTREE current shared\n", sh(main, plan.script()))
            // A folder there that is not its worktree is never touched, and the shared branch is never taken.
            val strange = builderWorktree(BuilderId.A, main.path, "${top.path}/wt")
            java.io.File(strange.path).apply { mkdirs(); java.io.File(this, "keep").writeText("x") }
            assertEquals("", sh(main, strange.script()))
            assertEquals(listOf("keep"), java.io.File(strange.path).list()!!.toList())
            assertEquals("", sh(main, WorktreePlan(main.path, "${top.path}/wt/shared", "shared").script()))
            // No git checkout at all: nothing is said, and what follows still runs.
            val plain = java.io.File(top, "plain").apply { mkdirs() }
            assertEquals("after\n", sh(plain, builderWorktree(BuilderId.B, plain.path, "${top.path}/wt2").script() + "\necho after"))
            assertFalse(java.io.File(top, "wt2").exists())
        } finally { top.deleteRecursively() }
    }
}
