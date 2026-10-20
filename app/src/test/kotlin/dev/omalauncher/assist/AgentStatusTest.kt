package dev.omalauncher.assist

import dev.omalauncher.phone.PHONE_DISCONNECTED
import dev.omalauncher.phone.PhoneLink
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

    @Test fun theAnswerIsCarriedAsPlainWords() {
        val status = agentStatus(working = false, label = "Done", answer = "## Display test\n\n| Element | Example |\n|---|---|\n| Bold | **strong** |", approvals = 0, now = 0)
        assertEquals("Display test\nElement · Example\nBold · strong", status.getString("answer"))
    }

    @Test fun phoneControlLeftDisconnectedNeedsAttentionBetweenTasks() {
        val idle = agentStatus(working = false, label = "Done", answer = "All good.", approvals = 0, now = 0, phone = PhoneLink.Disconnected)
        assertEquals("failed", idle.getString("state"))
        assertEquals(PHONE_DISCONNECTED, idle.getString("answer"))
        assertEquals("disconnected", idle.getString("phone"))
        // A task in hand keeps the tile: what it is doing matters more.
        val busy = agentStatus(working = true, label = "Using Bash", answer = "", approvals = 0, now = 0, phone = PhoneLink.Disconnected)
        assertEquals("working", busy.getString("state"))
        assertEquals("disconnected", busy.getString("phone"))
        val fine = agentStatus(working = false, label = "Done", answer = "All good.", approvals = 0, now = 0)
        assertEquals("done", fine.getString("state")); assertEquals("All good.", fine.getString("answer")); assertEquals("on", fine.getString("phone"))
    }

    @Test fun theOtherEndingsAreToldApart() {
        fun state(label: String) = agentStatus(false, label, "", 0, 0).getString("state")
        assertEquals("ready", state("Ready"))
        assertEquals("stopped", state("Stopped"))
        assertEquals("failed", state("Agent needs attention"))
        assertEquals("failed", state("Could not run agent"))
    }

    private fun thread(id: String, name: String, status: MacThreadStatus, background: List<String> = emptyList()) =
        MacThread(id, name, "/Users/boss/$id", ThreadHome.Mac, "claude", "", status, null, "", 0, 1, emptyList(), background)

    private fun asks(vararg names: String) =
        names.mapIndexed { n, name -> MacPending("t$n", name, MacApproval("req-$n", "nonce-$n", "Bash", "{}", ""), "ticket-$n", n) }

    /** OPEN-BUGS #10: the oma tile, the ring and the lock widget read the phone's builders only. */
    @Test fun theMacsWorkIsGlancedAtByThread() {
        val ff4 = thread("t1", "FF4 better menu mod", MacThreadStatus.Working)
        val games = thread("t2", "Copy the games", MacThreadStatus.Idle, listOf("copy the games"))
        val view = MacView(listOf(ff4, games, thread("t3", "Resting", MacThreadStatus.Idle)), reachable = true)
        assertEquals(MacGlance(working = "FF4 better menu mod", background = "Copy the games", workingThread = "t1", backgroundThread = "t2"), macGlance(view, emptyList()))
        // Each line names the thread it tells of first, so a tap on the tile opens that thread's tab (2026-10-20).
        assertEquals(
            MacGlance(waiting = "Site fix +1", approvals = 3, working = "FF4 better menu mod", background = "Copy the games", waitingThread = "t0", workingThread = "t1", backgroundThread = "t2"),
            macGlance(view, asks("Site fix", "Site fix", "Other")),
        )
        // A thread the Mac lists as waiting needs him even before its request has reached the phone.
        assertEquals("Waits", macGlance(MacView(listOf(thread("t4", "Waits", MacThreadStatus.Waiting)), reachable = true), emptyList()).waiting)
        // A Mac out of reach says nothing: what it last said may be long over.
        assertEquals(MacGlance(), macGlance(view.copy(reachable = false), asks("Site fix")))
        assertEquals(MacGlance(), macGlance(MacView(reachable = true), emptyList()))
    }

    @Test fun theTileSaysTheMacsWorkWhenNoBuilderWorksOrWaits() {
        val idle = agentStatus(working = false, label = "Done", answer = "All good.", approvals = 0, now = 0)
        val busy = agentStatus(working = true, label = "Using Bash", answer = "", approvals = 0, now = 0)
        val asking = agentStatus(working = true, label = "Approval needed", answer = "", approvals = 1, now = 0)
        val working = MacGlance(working = "FF4 better menu mod")
        // Nothing on the Mac: the builders' own say, unchanged but for an empty `mac`.
        val alone = omaStatus(idle, MacGlance())
        assertEquals(idle.put("mac", "").put("thread", "").toString(), alone.toString())
        // A tap opens the tab of what the tile shows: the builders' when theirs, a Mac thread's when it tells of one.
        assertEquals("phone", omaStatus(idle, MacGlance(), builderTab = "phone").getString("thread"))
        assertEquals("mac:t1", omaStatus(idle, MacGlance(working = "FF4", workingThread = "t1"), builderTab = "phone").getString("thread"))
        // No builder at work: the Mac's thread at work, waiting, or with work running, by name.
        val onMac = omaStatus(idle, working)
        assertEquals("working", onMac.getString("state")); assertEquals("Working on the Mac · FF4 better menu mod", onMac.getString("doing"))
        assertEquals("Working on the Mac · FF4 better menu mod", onMac.getString("mac")); assertFalse(onMac.getBoolean("working"))
        val running = omaStatus(idle, MacGlance(background = "Copy the games"))
        assertEquals("working", running.getString("state")); assertEquals("Running on the Mac · Copy the games", running.getString("mac"))
        val waiting = omaStatus(idle, MacGlance(waiting = "Site fix", approvals = 2, working = "FF4 better menu mod"))
        assertEquals("waiting", waiting.getString("state")); assertEquals("Needs your OK · Site fix", waiting.getString("doing")); assertEquals(2, waiting.getInt("approvals"))
        // A builder at work is told first, unless the Mac waits for his OK, which outranks work.
        assertEquals("working", omaStatus(busy, working).getString("state")); assertEquals("", omaStatus(busy, working).getString("mac"))
        assertEquals("using Bash…", omaStatus(busy, working).getString("doing"))
        assertEquals("Needs your OK · Site fix", omaStatus(busy, MacGlance(waiting = "Site fix", approvals = 1)).getString("mac"))
        // A builder waiting for him stands, and every approval counts.
        val both = omaStatus(asking, MacGlance(waiting = "Site fix", approvals = 2))
        assertEquals("waiting", both.getString("state")); assertEquals("", both.getString("mac")); assertEquals(3, both.getInt("approvals"))
        // The builders' status is not changed under it.
        assertEquals(1, asking.getInt("approvals")); assertFalse(asking.has("mac")); assertFalse(asking.has("thread"))
    }

    @Test fun theActivityTileFollowsTheSameOrder() {
        assertEquals("Oma is working", activityLine(true, 0, "Oma is working", MacGlance(working = "FF4")))
        assertEquals("Working on the Mac · FF4", activityLine(false, 0, "Ready", MacGlance(working = "FF4")))
        assertEquals("Needs your OK · Site fix", activityLine(true, 0, "Oma is working", MacGlance(waiting = "Site fix", approvals = 1)))
        assertEquals("Approval needed", activityLine(true, 1, "Approval needed", MacGlance(waiting = "Site fix", approvals = 1)))
        assertEquals("Ready", activityLine(false, 0, "Ready", MacGlance()))
    }
}
