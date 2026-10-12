package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Oma widget reads from the status file. */
class AgentStatusTest {
    @Test fun aRunningTaskSaysWhatItIsDoing() {
        val status = agentStatus(working = true, label = "Using Bash", answer = "", approvals = 0, now = 1_800_000_000)
        assertTrue(status.getBoolean("working"))
        assertEquals("working", status.getString("state"))
        assertEquals("using Bash…", status.getString("doing"))
        assertEquals(1_800_000_000, status.getLong("updated"))
    }

    /** An approval outranks the work it interrupts, as on the ring. */
    @Test fun aWaitingApprovalIsTheState() {
        val status = agentStatus(working = true, label = "Approval needed", answer = "", approvals = 2, now = 0)
        assertEquals("waiting", status.getString("state"))
        assertEquals(2, status.getInt("approvals"))
        assertEquals("needs your OK", status.getString("doing"))
    }

    @Test fun aFinishedTaskCarriesTheStartOfItsAnswerAndNothingItIsDoing() {
        val status = agentStatus(working = false, label = "Done", answer = "  " + "a".repeat(1000), approvals = 0, now = 0)
        assertFalse(status.getBoolean("working"))
        assertEquals("done", status.getString("state"))
        assertEquals("", status.getString("doing"))
        assertEquals(AGENT_STATUS_ANSWER, status.getString("answer").length)
    }

    @Test fun theOtherEndingsAreToldApart() {
        fun state(label: String) = agentStatus(false, label, "", 0, 0).getString("state")
        assertEquals("ready", state("Ready"))
        assertEquals("stopped", state("Stopped"))
        assertEquals("failed", state("Agent needs attention"))
        assertEquals("failed", state("Could not run agent"))
    }
}
