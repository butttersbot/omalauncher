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
            // A runner from before 2026-10-30 still sends sign-in checks: Boss doesn't want them, agents' real errors say it.
            val checks = (0 until (wireChecks?.length() ?: 0)).map { wireChecks!!.getJSONObject(it) }.filterNot { it.getString("check").startsWith("provider_auth") }.map { check ->
                val fields = check.optJSONObject("fields") ?: JSONObject()
                val name = check.getString("check").take(100)
                // A runner's own checks say which capability they belong to (a JDK problem is Builds, a login problem GitHub).
                val declared = Capability.entries.firstOrNull { it.name.equals(fields.optString("capability"), true) }
                val scope = if (id == "runner") when {
                    declared != null && declared != Capability.Network -> declared
                    name.startsWith("source_freshness") -> Capability.Sync
                    name.startsWith("build_toolchain") -> Capability.Builds
                    else -> capability
                } else if (declared == Capability.Messages) declared else capability
                val evidence = linkedMapOf("code" to check.optString("code").take(100))
                for (key in fields.keys().asSequence().filter { it !in setOf("http_status", "capability") }.take(6))
                    evidence[key.take(80)] = fields.optString(key).take(2048)
                if (evidence.size < 7) evidence["launchd_state"] = row.optString("state").take(100)
                scope to ConnectionCheck(name, "$host/$id", fields.optInt("http_status").takeIf { it in 100..599 },
                    check.getString("result").take(40), evidence, check.optString("stderr_tail").take(2048))
            }
            val notInstalled = row.has("installed") && !row.optBoolean("installed") && row.optString("state") != "running"
            val groups = checks.groupBy({ it.first }, { it.second }).ifEmpty { mapOf(capability to emptyList()) }
            groups.map { (scope, evidence) ->
                // A check of something this host doesn't have (no mirror agent), or of traffic there hasn't been lately, says
                // nothing about the rest.
                val required = evidence.filter { it.result !in NEUTRAL_RESULTS }
                val state = when {
                    // Absence is not an outage, and not an unknown either: an Unknown would outrank another host's Ready.
                    notInstalled || (evidence.isNotEmpty() && evidence.all { it.result == "not_installed" }) -> ConnectionState.Disabled
                    // Nothing here could be checked (a Linux host with no checks for it): neither a pass nor a failure, and it says so.
                    proofTime > 0 && evidence.isNotEmpty() && evidence.all { it.result in setOf("not_checked", "not_installed") } -> ConnectionState.NotChecked
                    proofTime <= 0 -> ConnectionState.Unknown
                    required.any { it.code in setOf(401, 403) || it.result == "auth" } -> ConnectionState.Auth
                    evidence.any { it.result == "fail" } -> ConnectionState.Failed
                    required.all { it.result == "pass" } && evidence.isNotEmpty() -> ConnectionState.Ready
                    else -> ConnectionState.Unknown
                }
                val trouble = evidence.firstNotNullOfOrNull { it.agentTrouble() }
                val specific = evidence.firstNotNullOfOrNull { it.deviceDetail(displayHost, id) }
                val detail = when {
                    specific != null -> specific
                    trouble != null -> trouble.sentence
                    else -> when (state) {
                    ConnectionState.Ready -> "$id: all required ${scope.title.lowercase()} checks passed." + evidence.mapNotNull { it.takeIf { c -> c.result == "not_checked" }?.deviceDetail(displayHost, id) }.joinToString("") { " $it" }
                    ConnectionState.NotChecked -> evidence.firstNotNullOfOrNull { it.deviceDetail(displayHost, id) } ?: "$id: not checked on $displayHost."
                    ConnectionState.Auth -> "$id: reachable, but authenticated ${scope.title.lowercase()} is not verified."
                    ConnectionState.Failed -> "$id: a required ${scope.title.lowercase()} check failed."
                    ConnectionState.Disabled -> if (id == "syncthing") "$id: optional service is not installed/configured on this host."
                        else if (id == "runner") "$id: this host has nothing to check for ${scope.title.lowercase()}."
                        else "$id: not installed on this host; ${scope.title.lowercase()} is checked where it runs."
                    else -> "$id: ${scope.title.lowercase()} evidence is incomplete; a running process is not sufficient."
                    }
                }
                // A restart that only real traffic can prove: no Ready until it has passed, and the row says why.
                val note = if (scope == capability) row.optJSONObject("restart_note") else null
                val unconfirmed = note?.optString("state") == "unconfirmed" && state == ConnectionState.Ready
                val told = when (note?.optString("state")) {
                    "unconfirmed" -> "$id was restarted; can't confirm until real traffic passes."
                    "confirmed" -> "$id was restarted, and real traffic has passed since."
                    else -> null
                }
                // A failing service whose host can't restart it says so, so nothing tells him to restart what only he can fix.
                val unsupported = row.optJSONObject("restart")?.let { it.has("supported") && !it.optBoolean("supported") } == true && state == ConnectionState.Failed
                val evidenceR = if (unsupported) evidence + ConnectionCheck("restart_unsupported", "$host/$id", result = "quiet",
                    fields = mapOf("reason" to row.optJSONObject("restart")?.optString("blocked_reason").orEmpty().take(80))) else evidence
                val noted = if (told == null) evidenceR else evidenceR + ConnectionCheck(if (note?.optString("state") == "confirmed") "restart_confirmed" else "restart_unconfirmed",
                    "$host/$id", result = if (note?.optString("state") == "confirmed") "pass" else "unverified", fields = mapOf("service" to id, "host" to host))
                ConnectionReading(scope, if (unconfirmed) ConnectionState.Unknown else state, told?.let { "$it $detail" } ?: detail, if (proofTime > 0) proofTime else checked,
                    if (state in setOf(ConnectionState.Failed, ConnectionState.Auth, ConnectionState.Ready))
                        "service:$host:$id${if (id == "runner") ":${scope.name}" else ""}" else null,
                    displayHost, sourceHost, noted)
            }
        }
    }.getOrElse { emptyList() }
}

/** What a runner's GitHub, disk, not-checked and incoming-message checks say in words, from their own fields; null for any other check. */
internal fun ConnectionCheck.deviceDetail(host: String, id: String): String? = when {
    name == "github_access" -> when (result) {
        "pass" -> "GitHub is reachable and this device's login is valid."
        "fail" -> "$host: GitHub login expired. Run gh auth login."
        "not_installed" -> "$host doesn't use GitHub."
        else -> if (fields["code"] == "github_unreachable") "$host can't reach GitHub right now (GitHub or the network); that is not a login problem."
            else "$host: GitHub could not be checked yet."
    }
    name == "disk_space" -> {
        val free = fields["free_gb"]
        (if (free != null) "$host has $free GB free." else "$host's free space could not be read.") + if (result == "fail") " That is low enough to break builds or sync." else ""
    }
    name == "not_checked" -> "$id isn't checked on $host yet."
    name == "imessage_incoming" -> if (result == "fail") "$host: the iMessage watcher isn't running, so nothing incoming can arrive." else fields["detail"]
    else -> null
}
