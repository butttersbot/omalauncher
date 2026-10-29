package dev.omalauncher.connections

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ConnectionsTest {
    private fun row(c: Capability, s: ConnectionState, root: String = "runner:mac", at: Long = 100_000) = ConnectionReading(c, s, "evidence", at, root, "Mac")
    @Test fun authenticatedReplyMustProveCapability() {
        assertEquals(ConnectionState.Auth, connectionState(ConnectionReply(401, "{}")) { true })
        assertEquals(ConnectionState.Unknown, connectionState(ConnectionReply(200, "<html>login</html>")) { true })
        assertEquals(ConnectionState.Unknown, connectionState(ConnectionReply(200, "{}")) { it.optBoolean("ok") })
        assertEquals(ConnectionState.Ready, connectionState(ConnectionReply(200, "{\"ok\":true}")) { it.optBoolean("ok") })
    }
    @Test fun dependentFailuresProduceOneIncidentAndOneDebouncedNotice() {
        val ledger = ConnectionAlerts()
        fun snapshot(at: Long) = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Failed, at = at), row(Capability.Builds, ConnectionState.Failed, at = at)), at)
        assertEquals(1, snapshot(100_000).incidents(100_000).size)
        assertTrue(ledger.update(snapshot(100_000), 100_000, false).isEmpty())
        assertTrue(ledger.update(snapshot(159_999), 159_999, false).isEmpty())
        assertEquals(listOf(ConnectionAlerts.Notice("runner:mac", false)), ledger.update(snapshot(160_000), 160_000, false))
        assertTrue(ledger.update(snapshot(200_000), 200_000, false).isEmpty())
    }
    @Test fun recoveryRequiresFreshExplicitEvidenceAndDoesNotRepeat() {
        val ledger = ConnectionAlerts()
        ledger.pending["runner:mac"] = ConnectionAlerts.Pending(1, true)
        val unknown = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Unknown)))
        assertTrue(ledger.update(unknown, 100_000, false).isEmpty())
        val stale = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Ready, at = 1)))
        assertTrue(ledger.update(stale, 100_000, false).isEmpty())
        val ready = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Ready)))
        assertEquals(listOf(ConnectionAlerts.Notice("runner:mac", true)), ledger.update(ready, 100_000, false))
        assertTrue(ledger.update(ready, 100_001, false).isEmpty())
    }
    @Test fun missingObservationInterruptsAnUnannouncedFailureWindow() {
        val ledger = ConnectionAlerts()
        ledger.update(ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Failed))), 100_000, false)
        ledger.update(ConnectionsSnapshot(), 130_000, false)
        assertTrue(ledger.update(ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Failed, at = 160_000))), 160_000, false).isEmpty())
    }
    @Test fun plannedPauseAndClockRollbackDoNotPage() {
        val ledger = ConnectionAlerts()
        val failed = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Failed)))
        assertTrue(ledger.update(failed, 100_000, true).isEmpty())
        assertTrue(ledger.pending.isEmpty())
        ledger.pending["runner:mac"] = ConnectionAlerts.Pending(150_000, false)
        assertTrue(ledger.update(failed, 100_000, false).isEmpty())
    }
    @Test fun partialSuccessNeverMasksMissingSubchecksOrStaleness() {
        val snapshot = ConnectionsSnapshot(listOf(row(Capability.Canvas, ConnectionState.Ready), row(Capability.Canvas, ConnectionState.Unknown)))
        assertEquals(12, snapshot.rows(100_000).size)
        assertEquals(ConnectionState.Unknown, snapshot.rows(100_000).first { it.capability == Capability.Canvas }.state)
        assertEquals(ConnectionState.Stale, snapshot.rows(200_000).first { it.capability == Capability.Canvas }.state)
        assertEquals(ConnectionState.Sleeping, row(Capability.Phone, ConnectionState.Sleeping, at = 1).current(200_000).state)
    }
    @Test fun restoreRequiresKnownIdleJobsAndSupportedControl() {
        assertTrue(restoreGuard(null, 0, true)!!.contains("cannot be verified"))
        assertTrue(restoreGuard(1, 0, true)!!.contains("active or queued"))
        assertTrue(restoreGuard(0, 2, true)!!.contains("active or queued"))
        assertTrue(restoreGuard(0, 0, false)!!.contains("no supported"))
        assertNull(restoreGuard(0, 0, true))
    }
    @Test fun monitorGapRestartsSustainedFailureWindow() {
        val ledger = ConnectionAlerts()
        ledger.pending["runner:mac"] = ConnectionAlerts.Pending(1, false)
        assertTrue(ledger.update(ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Failed))), 100_000, false).isEmpty())
    }
    @Test fun removedDependentCheckDoesNotProveRecovery() {
        val ledger = ConnectionAlerts()
        ledger.pending["runner:mac"] = ConnectionAlerts.Pending(1, true, 1, setOf("Agents:Mac", "Builds:Mac"))
        assertTrue(ledger.update(ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Ready))), 100_000, false).isEmpty())
    }
    @Test fun waitingQuestionsQueuesAndBackgroundJobsBlockRestore() {
        val wire = JSONObject("""{"threads":[{"status":"waiting","waiting_turns":3,"pending":[{}],"background":[]},{"status":"idle","waiting_turns":0,"pending":[],"background":["build"]}]}""")
        assertEquals(RunnerWorkCounts(2, 3), runnerWorkCounts(wire))
        assertNull(runnerWorkCounts(JSONObject("""{"threads":[{"status":"idle","queued":0}]}""")))
    }
    @Test fun sharedHostOutageGroupsDependentsWithoutInventingSleep() {
        val failed = listOf(row(Capability.Agents, ConnectionState.Failed).copy(sourceHost = "mac"),
            row(Capability.Voice, ConnectionState.Failed, root = "voice").copy(sourceHost = "mac"))
        assertEquals(1, ConnectionsSnapshot(groupConnectionFailures(failed)).incidents(100_000).size)
        assertTrue(groupConnectionFailures(failed).all { it.root == "host:mac" && it.state == ConnectionState.Failed })
        val partial = failed + row(Capability.Canvas, ConnectionState.Ready, root = "canvas").copy(sourceHost = "mac")
        assertEquals(2, ConnectionsSnapshot(groupConnectionFailures(partial)).incidents(100_000).size)
    }
    @Test fun endpointNeverPrintsCredentials() {
        assertFalse(ConnectionEndpoint("http://example/health?token=secret", "secret").toString().contains("secret"))
    }
}
