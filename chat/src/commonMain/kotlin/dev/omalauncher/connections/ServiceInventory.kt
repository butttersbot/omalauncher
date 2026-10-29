package dev.omalauncher.connections

import org.json.JSONObject

private val serviceCapabilities = mapOf(
    "runner" to Capability.Agents, "tailshare-serve" to Capability.Files, "tailshare-receive" to Capability.Files,
    "herd" to Capability.Activity, "embeddings" to Capability.Search, "whisper" to Capability.Voice,
    "soma" to Capability.Sync, "canvas" to Capability.Canvas, "imessage-api" to Capability.Messages,
    "imessage-transport" to Capability.Messages, "syncthing" to Capability.Sync, "tailscaled" to Capability.Network,
)

/** Health is the capability proof, never launchd running, pid presence, or the inventory endpoint's HTTP 200. */
fun serviceInventoryReadings(reply: ConnectionReply, displayHost: String, sourceHost: String, now: Long): List<ConnectionReading> {
    if (reply.code != 200) return emptyList()
    return runCatching {
        val body = JSONObject(reply.text)
        require(body.optBoolean("ok"))
        val host = body.getString("host"); require(host.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        val checked = body.getLong("checked_at")
        val services = body.getJSONArray("services"); require(services.length() <= CONNECTION_SERVICE_IDS.size)
        val seen = mutableSetOf<String>()
        (0 until services.length()).map { i ->
            val row = services.getJSONObject(i)
            val id = row.getString("id"); require(id in CONNECTION_SERVICE_IDS && seen.add(id))
            val capability = serviceCapabilities.getValue(id)
            val wireChecks = row.optJSONObject("probe")?.optJSONArray("checks")
            require((wireChecks?.length() ?: 0) <= 32)
            val checks = (0 until (wireChecks?.length() ?: 0)).map { j ->
                val check = wireChecks!!.getJSONObject(j)
                val fields = check.optJSONObject("fields") ?: JSONObject()
                ConnectionCheck(check.getString("check").take(100), "$host/$id", fields.optInt("http_status").takeIf { it in 100..599 },
                    check.getString("result").take(40), mapOf("code" to check.optString("code").take(100),
                        "launchd_state" to row.optString("state").take(100)), check.optString("stderr_tail").take(2048))
            }
            val state = when {
                checks.any { it.code in setOf(401, 403) || it.result == "auth" } -> ConnectionState.Auth
                checks.any { it.result == "fail" } -> ConnectionState.Failed
                checks.isNotEmpty() && checks.all { it.result == "pass" } -> ConnectionState.Ready
                else -> ConnectionState.Unknown
            }
            val detail = when (state) {
                ConnectionState.Ready -> "$id: all required capability checks passed."
                ConnectionState.Auth -> "$id: reachable, but authenticated capability is not verified."
                ConnectionState.Failed -> "$id: a required capability check failed."
                else -> "$id: capability evidence is incomplete; a running process is not sufficient."
            }
            ConnectionReading(capability, state, detail, checked,
                if (state in setOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Ready)) "service:$host:$id" else null,
                displayHost, sourceHost, checks).current(now)
        }
    }.getOrElse { emptyList() }
}
