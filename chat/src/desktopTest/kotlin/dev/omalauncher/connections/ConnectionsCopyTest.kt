package dev.omalauncher.connections

import org.junit.Assert.*
import org.junit.Test

class ConnectionsCopyTest {
    private val now = 100_000L
    private fun outage(route: Boolean, state: ConnectionState = ConnectionState.Failed, root: String = "runner:mac"): ConnectionsSnapshot =
        ConnectionsSnapshot(Capability.entries.map { c ->
            val hit = c == Capability.Agents || c == Capability.Builds
            ConnectionReading(c, if (hit) state else ConnectionState.Ready, "x", now, if (hit) root else null, if (hit) "Mac Pro" else "", if (hit) "mac-pro" else "")
        } + listOfNotNull(if (route) ConnectionReading(Capability.Network, ConnectionState.Ready, "route", now, "network", "Mac Pro", "mac-pro") else null), now)

    @Test fun statusWordsAndColoursFollowTheCards() {
        val s = outage(true)
        val rows = s.rows(now).associateBy { it.capability }
        val incidents = s.incidents(now)
        assertEquals(ConnectionStatus("✓", "Connected", ConnectionTone.Good), connectionStatus(rows.getValue(Capability.Network), incidents, card = true))
        assertEquals("✓ Ready", connectionStatus(rows.getValue(Capability.Network), incidents).text)
        assertEquals("✓ Synced", connectionStatus(rows.getValue(Capability.Sync), incidents).text)
        assertEquals("✓ Synced", connectionStatus(rows.getValue(Capability.Canvas), incidents).text)
        assertEquals("✓ Available", connectionStatus(rows.getValue(Capability.Optional), incidents).text)
        assertEquals(ConnectionStatus("△", "Needs repair", ConnectionTone.Bad), connectionStatus(rows.getValue(Capability.Agents), incidents))
        assertEquals("△ Affected", connectionStatus(rows.getValue(Capability.Builds), incidents).text)
        val unknown = ConnectionReading(Capability.Voice, ConnectionState.Unknown, "Not checked yet.", now)
        assertEquals(ConnectionTone.Quiet, connectionStatus(unknown, incidents).tone)
    }
    @Test fun headlineSaysConnectedOnlyWithAFreshRouteToThatMachine() {
        val with = outage(true).let { incidentCopy(it.incidents(now).single(), it, now) }
        assertEquals("Your Mac Pro is connected.\nIts runner needs a restart.", with.headline)
        assertEquals("Agent runs and builds on Mac Pro are unavailable. Other Soma features are ready.", with.impact)
        assertEquals(listOf("Check device & active jobs", "Restart the agent runner", "Verify agent & build connections"), with.steps)
        assertEquals("One repair. Two features back.", with.note)
        val without = outage(false).let { incidentCopy(it.incidents(now).single(), it, now) }
        assertFalse(without.headline.contains("connected"))
        // A stale route reading is no proof either.
        val stale = outage(true).let { s -> s.copy(readings = s.readings.map { if (it.root == "network") it.copy(checkedAt = now - 200_000) else it }) }
        assertFalse(incidentCopy(stale.incidents(now).single(), stale, now).headline.contains("connected"))
    }
    @Test fun signInAndWholeHostFailuresAreNotCalledRestarts() {
        val auth = outage(true, ConnectionState.Auth).let { incidentCopy(it.incidents(now).single(), it, now) }
        assertTrue(auth.headline.contains("sign-in"))
        assertFalse(auth.steps.any { it.contains("Restart") })
        val host = outage(false, root = "host:mac-pro").let { incidentCopy(it.incidents(now).single(), it, now) }
        assertTrue(host.headline.startsWith("Mac Pro isn't answering."))
    }
    @Test fun causeLineIsShortEnoughForTheSmallTile() {
        val s = outage(true)
        val bad = s.incidents(now).single()
        assertEquals("Mac Pro · runner not responding", connectionCause(bad.readings.first(), bad.key))
    }
    @Test fun progressStageComesFromTheRestorersWords() {
        assertEquals(-1, repairStage(null, false))
        assertEquals(0, repairStage(null, true))
        assertEquals(1, repairStage("Owner approval requested", false))
        assertEquals(2, repairStage("Verifying authenticated access", false))
        assertEquals(3, repairStage("Restored: agent access verified", false))
        assertEquals(-1, repairStage("Blocked: active jobs cannot be verified.", false))
    }
}
