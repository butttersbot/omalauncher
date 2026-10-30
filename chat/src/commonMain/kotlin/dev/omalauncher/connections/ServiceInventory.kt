package dev.omalauncher.connections

import org.json.JSONObject

private val serviceCapabilities = mapOf(
    "runner" to Capability.Agents, "tailshare-serve" to Capability.Files, "tailshare-receive" to Capability.Files,
    "herd" to Capability.Activity, "embeddings" to Capability.Search, "whisper" to Capability.Voice,
    "soma" to Capability.Sync, "canvas" to Capability.Canvas, "imessage-api" to Capability.Messages,
    "imessage-transport" to Capability.Messages, "syncthing" to Capability.Optional, "tailscaled" to Capability.Network,
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
        (0 until services.length()).flatMap { i ->
            val row = services.getJSONObject(i)
            val id = row.getString("id"); require(id in CONNECTION_SERVICE_IDS && seen.add(id))
            val capability = serviceCapabilities.getValue(id)
            val probe = row.optJSONObject("probe")
            val proofTime = probe?.optLong("checked_at", 0) ?: 0
            val wireChecks = probe?.optJSONArray("checks")
            require((wireChecks?.length() ?: 0) <= 32)
            val checks = (0 until (wireChecks?.length() ?: 0)).map { j ->
                val check = wireChecks!!.getJSONObject(j)
                val fields = check.optJSONObject("fields") ?: JSONObject()
                val name = check.getString("check").take(100)
                val scope = if (id == "runner") when {
                    fields.optString("capability").equals("Sync", true) || name.startsWith("source_freshness") -> Capability.Sync
                    fields.optString("capability").equals("Builds", true) || name.startsWith("build_toolchain") -> Capability.Builds
                    else -> capability
                } else capability
                val evidence = linkedMapOf("code" to check.optString("code").take(100))
                for (key in fields.keys().asSequence().filter { it !in setOf("http_status", "capability") }.take(3))
                    evidence[key.take(80)] = fields.optString(key).take(2048)
                if (evidence.size < 4) evidence["launchd_state"] = row.optString("state").take(100)
                scope to ConnectionCheck(name, "$host/$id", fields.optInt("http_status").takeIf { it in 100..599 },
                    check.getString("result").take(40), evidence, check.optString("stderr_tail").take(2048))
            }
            val notInstalled = row.has("installed") && !row.optBoolean("installed") && row.optString("state") != "running"
            val groups = checks.groupBy({ it.first }, { it.second }).ifEmpty { mapOf(capability to emptyList()) }
            groups.map { (scope, evidence) ->
                // A check of something this host doesn't have (no Codex, no mirror agent) says nothing about the rest.
                val required = evidence.filter { it.result != "not_installed" }
                val state = when {
                    // Absence is not an outage, and not an unknown either: an Unknown would outrank another host's Ready.
                    notInstalled || (evidence.isNotEmpty() && required.isEmpty()) -> ConnectionState.Disabled
                    proofTime <= 0 -> ConnectionState.Unknown
                    evidence.any { it.code in setOf(401, 403) || it.result == "auth" } -> ConnectionState.Auth
                    evidence.any { it.result == "fail" } -> ConnectionState.Failed
                    required.isNotEmpty() && required.all { it.result == "pass" } -> ConnectionState.Ready
                    else -> ConnectionState.Unknown
                }
                val detail = when (state) {
                    ConnectionState.Ready -> "$id: all required ${scope.title.lowercase()} checks passed."
                    ConnectionState.Auth -> "$id: reachable, but authenticated ${scope.title.lowercase()} is not verified."
                    ConnectionState.Failed -> "$id: a required ${scope.title.lowercase()} check failed."
                    ConnectionState.Disabled -> if (id == "syncthing") "$id: optional service is not installed/configured on this host."
                        else if (id == "runner") "$id: this host has nothing to check for ${scope.title.lowercase()}."
                        else "$id: not installed on this host; ${scope.title.lowercase()} is checked where it runs."
                    else -> "$id: ${scope.title.lowercase()} evidence is incomplete; a running process is not sufficient."
                }
                ConnectionReading(scope, state, detail, if (proofTime > 0) proofTime else checked,
                    if (state in setOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Ready))
                        "service:$host:$id${if (id == "runner") ":${scope.name}" else ""}" else null,
                    displayHost, sourceHost, evidence).current(now)
            }
        }
    }.getOrElse { emptyList() }
}
