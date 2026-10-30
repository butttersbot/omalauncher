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
    /** Boss, 2026-10-30: everything showed Stale, because the computers' clocks stand a little ahead of the phone's. */
    @Test fun aStampSlightlyAheadIsFreshAndOneFarAheadOrOldIsStale() {
        val at = 1_000_000L
        fun state(now: Long) = ConnectionReading(Capability.Builds, ConnectionState.Ready, "checked", at).current(now).state
        assertEquals("174 ms ahead, as measured", ConnectionState.Ready, state(at - 174))
        assertEquals("just inside the tolerance", ConnectionState.Ready, state(at - 10_000))
        assertEquals("a wrong clock", ConnectionState.Stale, state(at - 10_001))
        assertEquals(ConnectionState.Ready, state(at))
        assertEquals("the 90 s limit stays", ConnectionState.Ready, state(at + 90_000))
        assertEquals(ConnectionState.Stale, state(at + 90_001))
    }

    @Test fun partialSuccessNeverMasksMissingSubchecksOrStaleness() {
        val snapshot = ConnectionsSnapshot(listOf(row(Capability.Canvas, ConnectionState.Ready), row(Capability.Canvas, ConnectionState.Unknown)))
        assertEquals(12, snapshot.rows(100_000).size)
        assertEquals(ConnectionState.Unknown, snapshot.rows(100_000).first { it.capability == Capability.Canvas }.state)
        assertEquals(ConnectionState.Stale, snapshot.rows(200_000).first { it.capability == Capability.Canvas }.state)
        assertEquals(ConnectionState.Sleeping, row(Capability.Phone, ConnectionState.Sleeping).current(100_000).state)
        assertEquals(ConnectionState.Stale, row(Capability.Phone, ConnectionState.Sleeping, at = 1).current(200_000).state)
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
    @Test(timeout = 10_000) fun redirectsNeverForwardCredentialsAndOversizedBodiesStayUnverified() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val targetHits = java.util.concurrent.atomic.AtomicInteger()
        val observedAuth = java.util.concurrent.atomic.AtomicReference<String>()
        server.createContext("/redirect") { exchange ->
            observedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.set("Location", "/target")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/target") { exchange ->
            targetHits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        server.createContext("/large") { exchange ->
            val bytes = ByteArray(262_145) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(302, connectionGet(ConnectionEndpoint("$base/redirect", "fictional-test-key")).code)
            assertEquals("Bearer fictional-test-key", observedAuth.get())
            assertEquals(0, targetHits.get())
            val oversized = connectionGet(ConnectionEndpoint("$base/large"))
            assertEquals(200, oversized.code)
            assertEquals(ConnectionState.Unknown, connectionState(oversized) { true })
        } finally { server.stop(0) }
    }
    @Test fun repairAdviceTargetsNetworkPhoneAndAuthWithoutBlindRestart() {
        val net = ConnectionsSnapshot(listOf(row(Capability.Network, ConnectionState.Failed, root = "network")))
        assertEquals(ConnectionGuide.Tailscale, connectionRepairAdvice(net, 100_000, null, null).guide)
        val phone = ConnectionsSnapshot(listOf(row(Capability.Phone, ConnectionState.Failed, root = "phone-binding")))
        assertEquals(ConnectionGuide.PhoneControl, connectionRepairAdvice(phone, 100_000, null, null).guide)
        val auth = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Auth)))
        assertTrue(connectionRepairAdvice(auth, 100_000, null, null).text.contains("pairing"))
        val runner = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Failed)))
        assertTrue(connectionRepairAdvice(runner, 100_000, 1, 0).text.contains("active or queued"))
    }
    @Test fun endpointNeverPrintsCredentials() {
        assertFalse(ConnectionEndpoint("http://example/health?token=secret", "secret").toString().contains("secret"))
    }

    @Test fun aStaleReadingNeverHidesARealFailureFromIncidents() {
        val failed = ConnectionsSnapshot(listOf(row(Capability.Agents, ConnectionState.Failed, at = 1).copy(root = "runner:mac")), 1)
        // Long after its check, the row says Stale, but the failure is still an incident until a fresh check says otherwise.
        assertEquals(ConnectionState.Stale, failed.rows(1_000_000).first { it.capability == Capability.Network }.state)
        val incident = failed.incidents(1_000_000).single()
        assertEquals("runner:mac", incident.key)
        assertTrue(incident.readings.single().detail.startsWith("Last check is no longer current."))
        assertEquals(ConnectionState.Failed, incident.readings.single().state)
    }
}
