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
                .put("probe", JSONObject().put("checks", checks)))).toString())
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
}
