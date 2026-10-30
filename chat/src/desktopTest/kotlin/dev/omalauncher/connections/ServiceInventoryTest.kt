package dev.omalauncher.connections

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServiceInventoryTest {
    private fun reply(result: String, status: Int = 200, extra: Boolean = false): ConnectionReply {
        val checks = JSONArray().put(JSONObject().put("check", "real_capability").put("result", result)
            .put("fields", JSONObject().put("http_status", status)))
        if (extra) checks.put(JSONObject().put("check", "missing_scope").put("result", "unsupported"))
        return ConnectionReply(200, JSONObject().put("ok", true).put("host", "fictional-mac").put("checked_at", 100_000)
            .put("services", JSONArray().put(JSONObject().put("id", "canvas").put("state", "running").put("pid", 123)
                .put("probe", JSONObject().put("checks", checks).put("checked_at", 100_000).put("pending", false)))).toString())
    }
    private fun state(reply: ConnectionReply, now: Long = 100_000) = serviceInventoryReadings(reply, "Fictional Mac", "fictional", now).single().state
    @Test fun processAndHttpSuccessDoNotReplaceRequiredProof() {
        assertEquals(ConnectionState.Ready, state(reply("pass")))
        assertEquals(ConnectionState.Unknown, state(reply("unsupported")))
        assertEquals(ConnectionState.Unknown, state(reply("pass", extra = true)))
        assertEquals(ConnectionState.Failed, state(reply("fail")))
        assertEquals(ConnectionState.Auth, state(reply("fail", 401)))
        assertEquals(ConnectionState.Stale, state(reply("pass"), 200_000))
        assertTrue(serviceInventoryReadings(ConnectionReply(200, "{}"), "Mac", "mac", 100_000).isEmpty())
    }
    @Test fun aCheckOfSomethingThisHostLacksIsNeitherProofNorAnUnknown() {
        fun of(vararg results: String) = ConnectionReply(200, JSONObject().put("ok", true).put("host", "fictional-mac").put("checked_at", 100_000)
            .put("services", JSONArray().put(JSONObject().put("id", "canvas").put("state", "running").put("pid", 123)
                .put("probe", JSONObject().put("checks", JSONArray().also { checks -> results.forEachIndexed { i, r -> checks.put(JSONObject().put("check", "c$i").put("result", r)) } })
                    .put("checked_at", 100_000).put("pending", false)))).toString())
        assertEquals(ConnectionState.Ready, state(of("pass", "not_installed")))
        assertEquals(ConnectionState.Disabled, state(of("not_installed")))
        assertEquals(ConnectionState.Unknown, state(of("unverified", "not_installed")))
        assertEquals(ConnectionState.Failed, state(of("fail", "not_installed")))
        // A quiet night is no problem: an answering service with no traffic lately stays Ready, with or without other proof.
        assertEquals(ConnectionState.Ready, state(of("pass", "quiet")))
        assertEquals(ConnectionState.Ready, state(of("quiet")))
        assertEquals(ConnectionState.Failed, state(of("quiet", "fail")))
        assertEquals(ConnectionState.Unknown, state(of("quiet", "unverified")))
    }
    @Test fun cachedProofKeepsItsOwnAgeAndPendingHasNoInventedReadyState() {
        val body = JSONObject(reply("pass").text)
        body.put("checked_at", 200_000)
        assertEquals(ConnectionState.Stale, state(ConnectionReply(200, body.toString()), 200_000))
        body.getJSONArray("services").getJSONObject(0).getJSONObject("probe").put("checked_at", JSONObject.NULL).put("pending", true)
        assertEquals(ConnectionState.Unknown, state(ConnectionReply(200, body.toString()), 200_000))
    }

    @Test fun absentOptionalServiceIsNeutralAndAbsentCoreDoesNotInventAnOutage() {
        val body = JSONObject(reply("fail").text)
        val row = body.getJSONArray("services").getJSONObject(0)
        row.put("installed", false).put("state", "not_loaded")
        val core = serviceInventoryReadings(ConnectionReply(200, body.toString()), "MacBook", "macbook", 100_000).single()
        assertEquals(ConnectionState.Disabled, core.state)
        assertNull(core.root)
        // A host without the service must not hide the host that runs it.
        val ready = ConnectionReading(core.capability, ConnectionState.Ready, "ok", 100_000, null, "Mac Pro")
        assertEquals(ConnectionState.Ready, ConnectionsSnapshot(listOf(core, ready), 100_000).rows(100_000).single { it.capability == core.capability }.state)
        row.put("id", "syncthing")
        val optional = serviceInventoryReadings(ConnectionReply(200, body.toString()), "Mac", "mac", 100_000).single()
        assertEquals(Capability.Optional, optional.capability)
        assertEquals(ConnectionState.Disabled, optional.state)
        assertNull(optional.root)
    }

    @Test fun aToolchainFailureDoesNotInventAProviderOutage() {
        val body = JSONObject(reply("pass").text)
        val row = body.getJSONArray("services").getJSONObject(0).put("id", "runner")
        row.getJSONObject("probe").put("checks", JSONArray()
            .put(JSONObject().put("check", "runner_self").put("result", "pass"))
            .put(JSONObject().put("check", "provider_auth.claude").put("result", "unverified"))  // a runner not updated yet
            .put(JSONObject().put("check", "build_toolchain.jdk").put("result", "fail").put("fields", JSONObject().put("reason", "JDK missing")))
            .put(JSONObject().put("check", "source_freshness.mirror").put("result", "fail").put("fields", JSONObject().put("capability", "Sync"))))
        val readings = serviceInventoryReadings(ConnectionReply(200, body.toString()), "Mac", "mac", 100_000)
        assertEquals(ConnectionState.Ready, readings.single { it.capability == Capability.Agents }.state)
        val builds = readings.single { it.capability == Capability.Builds }
        assertEquals(ConnectionState.Failed, builds.state)
        assertEquals("JDK missing", builds.checks.single().fields["reason"])
        val sync = readings.single { it.capability == Capability.Sync }
        assertEquals(ConnectionState.Failed, sync.state)
        assertEquals("source_freshness.mirror", sync.checks.single().name)
        assertEquals(3, readings.map { it.root }.distinct().size)
    }

}
