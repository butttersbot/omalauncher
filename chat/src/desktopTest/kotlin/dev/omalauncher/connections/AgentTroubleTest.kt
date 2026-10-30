package dev.omalauncher.connections

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTroubleTest {
    private val now = 100_000L
    private fun inventory(vararg checks: JSONObject) = ConnectionReply(200, JSONObject().put("ok", true).put("host", "mac").put("checked_at", now)
        .put("services", JSONArray().put(JSONObject().put("id", "runner").put("state", "running").put("probe", JSONObject()
            .put("checks", JSONArray(checks.toList())).put("checked_at", now)))).toString())
    private fun self() = JSONObject().put("check", "runner_self").put("code", "ok").put("result", "pass")
    private fun turn(result: String, code: String, error: String) = JSONObject().put("check", "agent_turn.claude").put("code", code).put("result", result)
        .put("fields", JSONObject().put("capability", "Agents").put("agent", "claude").put("error", error))

    @Test fun nothingHeardFromAnAgentIsNothingWrong() {
        assertEquals(ConnectionState.Ready, serviceInventoryReadings(inventory(self()), "Mac Pro", "mac", now).single().state)
    }
    @Test fun aFailedTurnNamesTheAgentAndTheErrorAndNoRestartIsOffered() {
        val snapshot = ConnectionsSnapshot(serviceInventoryReadings(inventory(self(), turn("fail", "overloaded", "API Error: 529 overloaded_error")), "Mac Pro", "mac", now), now)
        val reading = snapshot.readings.single()
        assertEquals(ConnectionState.Failed, reading.state)
        assertEquals("Claude: overloaded. API Error: 529 overloaded_error", reading.detail)
        val incident = snapshot.incidents(now).single()
        val copy = incidentCopy(incident, snapshot, now)
        assertEquals("Claude is failing on Mac Pro.\nOverloaded.", copy.headline)
        assertFalse(copy.steps.any { it.contains("restart", ignoreCase = true) })
        assertEquals("Mac Pro · Claude · overloaded", connectionCause(reading, incident.key))
        val refused = serviceInventoryReadings(inventory(self(), turn("auth", "sign_in", "Not logged in")), "Mac Pro", "mac", now).single()
        assertEquals(ConnectionState.Auth, refused.state)
        assertEquals("Claude: sign-in refused. Not logged in", refused.detail)
    }
    @Test fun commonErrorsAreKnownAndOtherFailuresSayNothing() {
        assertEquals("sign_in", agentError("Not logged in · Please run /login"))
        assertEquals("usage_limit", agentError("You've hit your weekly limit · resets Oct 18"))
        assertEquals("overloaded", agentError("API Error: 529 overloaded_error"))
        assertEquals("no_connection", agentError("API Error: Connection lost mid-response"))
        assertEquals("server_error", agentError("API Error: 500"))
        for (other in listOf("the build failed", "API Error: safeguards flagged this session", "")) assertNull(other, agentError(other))
    }
    @Test fun onThisPhoneTheNewestTurnThatSaysAnythingDecides() {
        fun task(time: Long, status: TaskStatus, result: String, agent: String = "Claude Code", thread: String = "") = ChatTask(time, agent, "x", status = status, result = result, thread = thread)
        assertTrue(phoneAgentReadings(emptyList(), now).isEmpty())
        val failed = phoneAgentReadings(listOf(task(1, TaskStatus.Done, "fine"), task(2, TaskStatus.Failed, "API Error: 529 overloaded_error")), now).single()
        assertEquals(ConnectionState.Failed, failed.state)
        assertEquals("Claude: overloaded. API Error: 529 overloaded_error", failed.detail)
        // A later good turn clears it, for that agent only; a failure that isn't the service's says nothing; a Mac's turn is its runner's to report.
        assertEquals(ConnectionState.Ready, phoneAgentReadings(listOf(task(2, TaskStatus.Failed, "API Error: 529"), task(3, TaskStatus.Done, "fine")), now).single().state)
        assertEquals(ConnectionState.Ready, phoneAgentReadings(listOf(task(2, TaskStatus.Done, "fine"), task(3, TaskStatus.Failed, "the build failed")), now).single().state)
        assertEquals(ConnectionState.Failed, phoneAgentReadings(listOf(task(2, TaskStatus.Failed, "API Error: 529"), task(3, TaskStatus.Done, "fine", "Codex")), now).first().state)
        assertTrue(phoneAgentReadings(listOf(task(2, TaskStatus.Failed, "API Error: 529", thread = "t1")), now).isEmpty())
    }
    @Test fun aDownDeviceIsNamedAndAHealthyOneIsNot() {
        val down = listOf(Capability.Network, Capability.Agents, Capability.Builds).map { ConnectionReading(it, ConnectionState.Failed, "no reply", now, "runner:macbook", "MacBook", "macbook") }
        val up = listOf(ConnectionReading(Capability.Network, ConnectionState.Ready, "ok", now, "runner:pro", "Mac Pro", "pro"))
        val snapshot = ConnectionsSnapshot(groupConnectionFailures(down + up), now)
        val incident = snapshot.incidents(now).single()
        assertEquals("host:macbook", incident.key)
        assertEquals("MacBook isn't answering.\nIt may be asleep or offline.", incidentCopy(incident, snapshot, now).headline)
        assertEquals("MacBook · not answering", connectionCause(incident.readings.first(), incident.key))
    }
    @Test fun restoreOnlyTouchesWhatIsReallyDown() {
        fun service(vararg checks: JSONObject, supported: Boolean = true) = JSONObject().put("id", "imessage-api").put("state", "running")
            .put("restart", JSONObject().put("supported", supported)).put("probe", JSONObject().put("checks", JSONArray(checks.toList())).put("checked_at", now))
        fun check(result: String, capability: String? = null) = JSONObject().put("check", "c").put("code", "x").put("result", result)
            .put("fields", JSONObject().apply { capability?.let { put("capability", it) } })
        // Healthy: no restart, and nothing asked of the owner.
        assertEquals(RestoreTriage(false), restoreTriage(service(check("pass")), "mac", now))
        assertEquals(RestoreTriage(false), restoreTriage(service(check("pass"), check("quiet")), "mac", now))
        // Down: restarted, if restarts are allowed for it.
        assertTrue(restoreTriage(service(check("fail")), "mac", now).restart)
        assertFalse(restoreTriage(service(check("fail"), supported = false), "mac", now).restart)
        // The runner answering with a missing JDK or an agent's error is not the runner down.
        assertFalse(restoreTriage(service(check("pass"), check("fail", "Builds"), check("fail", "Agents")), "mac", now).restart)
        // Unproven is never restarted.
        assertFalse(restoreTriage(service(check("unverified")), "mac", now).restart)
        assertFalse(restoreTriage(service(check("fail"), check("unverified")), "mac", now).restart)
        assertFalse(restoreTriage(service(check("fail")), "mac", now + 60_000).restart)
    }
}
