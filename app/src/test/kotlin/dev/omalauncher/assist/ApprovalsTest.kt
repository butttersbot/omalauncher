package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovalsTest {
    private fun approval(id: String, tool: String = "Bash") = AgentApproval(id, tool, JSONObject().put("command", "echo $id"), "")
    private fun shown(ledger: ApprovalLedger, id: String) = (ledger.admit(approval(id)) as Admission.Shown).entry

    @Test fun liveApprovalsNeverShareANotificationIdOrARequestCode() {
        val ledger = ApprovalLedger()
        val live = (1..ApprovalIds.MAX_LIVE).map { shown(ledger, "request-$it") }.toMutableList()
        // Answer some in the middle and ask again, so slots are reused.
        for (round in 1..40) {
            val gone = live.removeAt((round * 7) % live.size)
            assertTrue(ledger.claim(gone.id, gone.nonce, locked = false, active = true) is Claim.Granted)
            live += shown(ledger, "again-$round")
            assertEquals(live.size, live.map { it.notificationId }.toSet().size)
            val codes = live.flatMap { entry -> ApprovalVerb.entries.map { ApprovalIds.requestCode(entry.slot, it) } }
            assertEquals(live.size * ApprovalVerb.entries.size, codes.toSet().size)
            assertEquals(live.size, live.map { it.nonce }.toSet().size)
            // Clear of the task notification (41), its short notices (42) and phone control (40).
            assertTrue((live.map { it.notificationId } + codes).all { it >= ApprovalIds.FIRST_NOTIFICATION })
        }
    }

    @Test fun aTapDecidesOnlyTheApprovalItWasDrawnFor() {
        val ledger = ApprovalLedger()
        val first = shown(ledger, "a"); val second = shown(ledger, "b")
        val claim = ledger.claim(second.id, second.nonce, locked = false, active = true)
        assertEquals(Claim.Granted(second), claim)
        assertEquals(listOf(first), ledger.live)
    }

    @Test fun anAnsweredWithdrawnOrStoppedApprovalHasExpired() {
        val ledger = ApprovalLedger()
        val answered = shown(ledger, "a")
        assertTrue(ledger.claim(answered.id, answered.nonce, false, true) is Claim.Granted)
        assertEquals(Claim.Expired, ledger.claim(answered.id, answered.nonce, false, true))

        val withdrawn = shown(ledger, "b")
        assertEquals(withdrawn, ledger.withdraw("b"))
        assertEquals(Claim.Expired, ledger.claim(withdrawn.id, withdrawn.nonce, false, true))

        val stopped = shown(ledger, "c")
        assertEquals(listOf(stopped), ledger.clear())
        assertEquals(Claim.Expired, ledger.claim(stopped.id, stopped.nonce, false, true))
        assertTrue(ledger.live.isEmpty())
    }

    @Test fun aTicketFromBeforeARestartAnswersNothing() {
        val before = shown(ApprovalLedger(), "request-1")
        val afterRestart = ApprovalLedger()
        assertEquals(Claim.Expired, afterRestart.claim(before.id, before.nonce, false, true))
        // Even when the agent asks again under the same id and gets the same slot.
        val again = shown(afterRestart, "request-1")
        assertEquals(before.slot, again.slot)
        assertEquals(Claim.Expired, afterRestart.claim(before.id, before.nonce, false, true))
        assertEquals(listOf(again), afterRestart.live)
    }

    @Test fun aLockedPhoneAnswersNothing() {
        val ledger = ApprovalLedger()
        val entry = shown(ledger, "a")
        assertEquals(Claim.Locked, ledger.claim(entry.id, entry.nonce, locked = true, active = true))
        assertEquals(listOf(entry), ledger.live)
    }

    @Test fun nothingIsAnsweredOnceTheTaskIsNoLongerActive() {
        val ledger = ApprovalLedger()
        val entry = shown(ledger, "a")
        assertEquals(Claim.Expired, ledger.claim(entry.id, entry.nonce, locked = false, active = false))
    }

    @Test fun idsFromTheAgentAreCheckedBeforeTheyAreUsed() {
        assertTrue(ApprovalIds.safe("request-7"))
        assertTrue(ApprovalIds.safe("9c2386fd-2736-408a-a815-6f6732bddf85"))
        for (bad in listOf("", " ", "a b", "a/b", "a\nb", "../x", "a#b", "a?b=c", "a%2Fb", "é", "x".repeat(ApprovalIds.MAX_LENGTH + 1)))
            assertFalse(bad, ApprovalIds.safe(bad))
        val ledger = ApprovalLedger()
        assertEquals(Admission.Refused, ledger.admit(approval("a/b")))
        assertEquals(Admission.Refused, ledger.admit(approval("x".repeat(500))))
        assertTrue(ledger.live.isEmpty())
    }

    @Test fun aRepeatedIdDoesNotReplaceTheRequestOnScreen() {
        val ledger = ApprovalLedger()
        val first = shown(ledger, "a")
        assertEquals(Admission.Duplicate, ledger.admit(approval("a", tool = "Write")))
        assertEquals(listOf(first), ledger.live)
        assertEquals("Bash", ledger.live.single().approval.tool)
    }

    @Test fun tooManyWaitingApprovalsAreRefusedNotShown() {
        val ledger = ApprovalLedger()
        repeat(ApprovalIds.MAX_LIVE) { shown(ledger, "r$it") }
        assertEquals(Admission.Refused, ledger.admit(approval("one-more")))
        assertEquals(ApprovalIds.MAX_LIVE, ledger.live.size)
        assertThrows(IllegalArgumentException::class.java) { ApprovalIds.notificationId(ApprovalIds.MAX_LIVE) }
        assertThrows(IllegalArgumentException::class.java) { ApprovalIds.requestCode(-1, ApprovalVerb.Approve) }
    }

    @Test fun ticketsRoundTripAndRejectAnythingElse() {
        val entry = shown(ApprovalLedger(), "request-7:a_b.c")
        for (verb in ApprovalVerb.entries) assertEquals(entry.ticket(verb), ApprovalTicket.parse(entry.ticket(verb).encode()))
        // Approve and Deny for one approval, and the same verb for two approvals, are different intents.
        assertNotEquals(entry.ticket(ApprovalVerb.Approve).encode(), entry.ticket(ApprovalVerb.Deny).encode())
        val good = entry.ticket(ApprovalVerb.Approve).encode()
        val nonce = entry.nonce
        for (bad in listOf(null, "", "oma-approval:", good.replace("approve", "allow"), good.replace("oma-approval", "other"),
            "oma-approval:approve/99/$nonce/a", "oma-approval:approve/-1/$nonce/a", "oma-approval:approve/+1/$nonce/a",
            "oma-approval:approve/0/not-a-nonce/a", "oma-approval:approve/0/$nonce/a/b", "oma-approval:approve/0/$nonce/",
            "oma-approval:approve/0/$nonce/a b", "oma-approval:approve/0/$nonce", good + "x".repeat(400)))
            assertNull(bad, ApprovalTicket.parse(bad))
    }

    @Test fun blockedNotificationsSendTheApprovalToItsScreen() {
        assertEquals(ApprovalSurface.Notification, approvalSurface(notificationsEnabled = true, channelBlocked = false))
        assertEquals(ApprovalSurface.Screen, approvalSurface(notificationsEnabled = false, channelBlocked = false))
        assertEquals(ApprovalSurface.Screen, approvalSurface(notificationsEnabled = true, channelBlocked = true))
        assertEquals(ApprovalSurface.Screen, approvalSurface(notificationsEnabled = false, channelBlocked = true))
    }

    @Test fun buttonsOnlyDecideWhereAndroidCanDemandAnUnlock() {
        assertFalse(actionDecidesDirectly(29)); assertFalse(actionDecidesDirectly(30))
        assertTrue(actionDecidesDirectly(31)); assertTrue(actionDecidesDirectly(37))
    }

    @Test fun theTaskNotificationShowsTheStepThenTheResult() {
        val step = taskNotice(working = true, label = "Using Bash", answer = "Looking at the build.\n\n", pending = 0)!!
        assertEquals("Using Bash", step.title); assertEquals("Looking at the build.", step.text)
        assertTrue(step.ongoing); assertNull(step.expanded)
        assertEquals("Request received", taskNotice(true, "Claude Code is working", "", 0)!!.text)
        assertEquals("1 request is waiting for your answer", taskNotice(true, "Approval needed", "text", 1)!!.text)
        assertEquals("3 requests are waiting for your answer", taskNotice(true, "Approval needed", "text", 3)!!.text)

        val result = taskNotice(working = false, label = "Done", answer = "x".repeat(5000), pending = 0)!!
        assertFalse(result.ongoing); assertEquals(180, result.text.length); assertEquals(1500, result.expanded!!.length)
        // Nothing to say after a new thread: the notification goes away.
        assertNull(taskNotice(working = false, label = "Ready", answer = "", pending = 0))
    }
}
