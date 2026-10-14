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

    @Test fun aBareStopStopsTheOneAtWorkAndAsksWhichWhenBothAre() {
        assertEquals(BareStop.Nothing, bareStop(emptyList()))
        assertEquals(BareStop.One(BuilderId.B), bareStop(listOf(BuilderId.B)))
        assertEquals(BareStop.One(BuilderId.A), bareStop(listOf(BuilderId.A)))
        assertEquals(BareStop.Which, bareStop(listOf(BuilderId.A, BuilderId.B)))
        assertTrue(WHICH_BUILDER.endsWith("A or B?"))
        assertEquals("Stopped builder B.", stoppedText(builderCalled(BuilderId.B), running = true))
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
}
