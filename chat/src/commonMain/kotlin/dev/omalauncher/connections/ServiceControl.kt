package dev.omalauncher.connections

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

val CONNECTION_SERVICE_IDS = setOf("runner", "tailshare-serve", "tailshare-receive", "herd", "embeddings", "whisper", "soma", "canvas", "imessage-api", "imessage-transport", "syncthing", "tailscaled")

data class ServiceApproval(val id: String, val nonce: String, val service: String, val host: String, val expiresAt: Long) {
    val payload: String get() = listOf("soma-service-restart-v1", id, nonce, service, host, expiresAt.toString()).joinToString("\n")
    fun fresh(now: Long) = expiresAt > now && expiresAt - now <= 120_000
    companion object {
        fun parse(json: JSONObject, expectedService: String, expectedHost: String, now: Long): ServiceApproval {
            val result = ServiceApproval(json.getString("id"), json.getString("nonce"), json.getString("service"), json.getString("host"), json.getLong("expires_at"))
            require(result.id.matches(Regex("[A-Za-z0-9_-]{8,128}")) && result.nonce.matches(Regex("[A-Za-z0-9_-]{20,128}"))) { "Invalid approval identity" }
            require(result.service in CONNECTION_SERVICE_IDS && result.service == expectedService && result.host == expectedHost) { "Approval target changed" }
            require(result.host.matches(Regex("[A-Za-z0-9._-]{1,100}")) && result.fresh(now)) { "Approval expired or clock mismatch" }
            require(json.getString("payload") == result.payload) { "Approval payload differs from the native target" }
            return result
        }
    }
}

data class OwnerServicePlan(val approvals: List<ServiceApproval>) {
    init { require(approvals.isNotEmpty() && approvals.size <= 16 && approvals.distinct().size == approvals.size) }
    val encodedPayloads get() = approvals.map { Base64.getEncoder().encodeToString(it.payload.toByteArray(Charsets.UTF_8)) }.sorted()
    val payload get() = (listOf("soma-service-restart-plan-v1", approvals.size.toString()) + encodedPayloads).joinToString("\n")
    fun fresh(now: Long) = approvals.all { it.fresh(now) }
}

/** Fixed-path JSON requests, no redirects, no diagnostic logging of approvals, keys or signatures. */
fun serviceControlPost(endpoint: ConnectionEndpoint, json: JSONObject): ConnectionReply {
    var connection: HttpURLConnection? = null
    var code = -1
    return try {
        val uri = URI(endpoint.url)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null)
        connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000; connection.readTimeout = 8000; connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${endpoint.key}")
        connection.setRequestProperty("Content-Type", "application/json")
        val bytes = json.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 65_536)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        val body = stream?.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (output.size() <= 262_144) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            require(output.size() <= 262_144); output.toString("UTF-8")
        }.orEmpty()
        ConnectionReply(code, body)
    } catch (_: Exception) { ConnectionReply(code, "") } finally { connection?.disconnect() }
}

class ServiceControlClient(private val base: ConnectionEndpoint,
    private val get: (ConnectionEndpoint) -> ConnectionReply = ::connectionGet,
    private val post: (ConnectionEndpoint, JSONObject) -> ConnectionReply = ::serviceControlPost) {
    private fun endpoint(path: String) = ConnectionEndpoint(base.url.trimEnd('/') + path, base.key)
    fun inventory(): ConnectionReply = get(endpoint("/services"))
    fun challenge(service: String): ConnectionReply {
        require(service in CONNECTION_SERVICE_IDS)
        return post(endpoint("/services/$service/restart"), JSONObject())
    }
    /** Only native owner-authenticated code may supply a signature; CLI intentionally has no equivalent method. */
    fun settle(approval: ServiceApproval, plan: OwnerServicePlan, keyId: String, signature: String, now: Long): ConnectionReply {
        require(plan.approvals.contains(approval) && plan.fresh(now))
        require(keyId.matches(Regex("[0-9a-f]{64}")) && signature.length in 80..128)
        return post(endpoint("/services/${approval.service}/restart"), JSONObject().put("approval", approval.id).put("nonce", approval.nonce)
            .put("decision", "allow").put("owner_key_id", keyId).put("owner_signature", signature).put("owner_payloads", JSONArray(plan.encodedPayloads)))
    }
    fun operationForApproval(id: String): ConnectionReply {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        return get(endpoint("/services/approvals/$id/operation"))
    }
    fun operation(id: String): ConnectionReply {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        return get(endpoint("/services/operations/$id"))
    }
}

/** A response is evidence only for the exact operation submitted; transport success is never restoration. */
data class ServiceOperation(val id: String, val approval: String, val service: String, val host: String,
    val stage: String, val verified: Boolean, val checks: List<ConnectionCheck>) {
    val terminal get() = stage in setOf("restored", "failed", "blocked", "interrupted")
    val restored get() = stage == "restored" && verified && checks.isNotEmpty() && checks.all { it.result == "pass" }
    companion object {
        fun parse(json: JSONObject, expectedId: String?, approval: ServiceApproval): ServiceOperation {
            val id = json.getString("id")
            require(id.matches(Regex("op-[A-Za-z0-9_-]{8,100}")) && (expectedId == null || id == expectedId)) { "Operation identity changed" }
            require(json.getString("approval") == approval.id && json.getString("service") == approval.service && json.getString("host") == approval.host) { "Operation target changed" }
            val stage = json.getString("stage")
            require(stage in setOf("checking", "restoring", "verifying", "restored", "failed", "blocked", "interrupted")) { "Unknown operation state" }
            val a = json.optJSONArray("checks") ?: JSONArray()
            require(a.length() <= 128) { "Too many verification checks" }
            val checks = (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                val fields = c.optJSONObject("fields") ?: JSONObject()
                val details = fields.keys().asSequence().take(16).associateWith { fields.optString(it).take(2048) }
                ConnectionCheck(c.getString("check").take(100), c.optString("target", approval.host).take(100),
                    fields.optInt("http_status").takeIf { it in 100..599 }, c.getString("result").take(40),
                    details + ("code" to c.optString("code").take(100)), c.optString("stderr_tail").take(2048))
            }
            val result = ServiceOperation(id, approval.id, approval.service, approval.host, stage, json.optBoolean("verified", false), checks)
            require(stage != "restored" || result.restored) { "Restoration lacks complete capability verification" }
            return result
        }
    }
}

/** What a Restore does with one service of a Mac's inventory: only a service that is really down is restarted. */
data class RestoreTriage(val restart: Boolean, val blocked: List<ConnectionCheck> = emptyList())
fun restoreTriage(service: JSONObject, host: String, now: Long): RestoreTriage {
    val id = service.getString("id")
    if (service.has("installed") && !service.optBoolean("installed") && service.optString("state") != "running")
        return RestoreTriage(false, listOf(ConnectionCheck(id, host, result = "unverified", fields = mapOf("reason" to "Managed service is not installed on this host; no restart inferred."))))
    val probe = service.optJSONObject("probe")
    val proofAge = now - (probe?.optLong("checked_at", 0) ?: 0)
    if (probe == null || proofAge !in 0..30_000)
        return RestoreTriage(false, listOf(ConnectionCheck(id, host, result = "unverified", fields = mapOf("reason" to "Service proof is absent or stale; no repair inferred."))))
    val wire = probe.optJSONArray("checks") ?: JSONArray()
    // A scoped row (a build tool, an agent's error) isn't this service being down, and a restart wouldn't change it. A quiet
    // one is no problem at all.
    val checks = (0 until wire.length()).map { wire.getJSONObject(it) }
        .filter { it.optJSONObject("fields")?.has("capability") != true && it.optString("result") !in NEUTRAL_RESULTS }
    if (checks.none { it.optString("result") == "fail" }) // Healthy: left alone. Missing proof and authentication never infer a restart.
        return RestoreTriage(false, checks.filter { it.optString("result") != "pass" }.map { check ->
            val http = check.optJSONObject("fields")?.optInt("http_status")?.takeIf { it in 100..599 }
            ConnectionCheck(check.optString("check", id), host, http, if (http in setOf(401, 403)) "auth" else "unverified", mapOf("code" to check.optString("code")))
        })
    if (checks.any { it.optString("result") !in setOf("pass", "fail", "auth") })
        return RestoreTriage(false, listOf(ConnectionCheck(id, host, result = "unverified", fields = mapOf("reason" to "At least one required capability proof is unsupported or incomplete."))))
    if (checks.any { it.optString("result") == "auth" || it.optJSONObject("fields")?.optInt("http_status") in setOf(401, 403) })
        return RestoreTriage(false, listOf(ConnectionCheck(id, host, result = "auth", fields = mapOf("reason" to "Authentication requires a separate diagnostic; no restart inferred."))))
    if (service.optJSONObject("restart")?.optBoolean("supported") != true)
        return RestoreTriage(false, listOf(ConnectionCheck(id, host, result = "blocked", fields = mapOf("reason" to service.optJSONObject("restart")?.optString("blocked_reason").orEmpty()))))
    return RestoreTriage(true)
}

/** These errors are emitted only by challenge validation, before an operation is created or executed. */
fun definitiveServiceRefusal(reply: ConnectionReply): Boolean {
    if (reply.code !in setOf(400, 403, 404, 410, 423)) return false
    val reasons = setOf("bad_request", "no_challenge", "nonce_mismatch", "expired", "owner_trust_missing", "owner_trust_untrusted", "owner_key", "bad_plan", "bad_signature")
    return runCatching {
        val body = JSONObject(reply.text)
        body.has("ok") && body.get("ok") == false && !body.has("operation") && body.optString("error") in reasons
    }.getOrDefault(false)
}

/** A cheap early refusal avoids asking the owner to authenticate a repair that cannot currently run.
 * The server still reacquires its actual locks and rechecks all work immediately before execution. */
fun serviceWorkEvidence(work: JSONObject?, host: String): List<ConnectionCheck> {
    val names = listOf("working", "waiting", "queued", "background")
    val counts = names.associateWith { name -> work?.opt(name) }
    if (counts.values.any { it !is Int && it !is Long } || counts.values.any { (it as Number).toLong() !in 0..1_000_000 })
        return listOf(ConnectionCheck("active_work", host, result = "unverified", fields = mapOf("reason" to "Active-work counts missing or invalid")))
    val locks = work?.optJSONArray("locks") ?: return listOf(ConnectionCheck("build_walk_locks", host, result = "unverified"))
    if (locks.length() > 128) return listOf(ConnectionCheck("build_walk_locks", host, result = "unverified"))
    val held = mutableListOf<String>()
    for (i in 0 until locks.length()) {
        val lock = locks.optJSONObject(i) ?: return listOf(ConnectionCheck("build_walk_locks", host, result = "unverified"))
        if (lock.opt("held") !is Boolean) return listOf(ConnectionCheck("build_walk_locks", host, result = "unverified"))
        if (lock.getBoolean("held")) held += lock.optString("name").take(80)
    }
    return listOf(
        ConnectionCheck("active_work", host, result = if (counts.values.any { (it as Number).toLong() > 0 }) "blocked" else "pass",
            fields = counts.mapValues { it.value.toString() }),
        ConnectionCheck("build_walk_locks", host, result = if (held.isEmpty()) "pass" else "blocked", fields = mapOf("held" to held.joinToString(", "))),
        ConnectionCheck("restart_admission", host, result = if (work?.optString("admission") == "open") "pass" else "blocked"),
    )
}
