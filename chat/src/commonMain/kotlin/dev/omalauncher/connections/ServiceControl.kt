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
    fun operation(id: String): ConnectionReply {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        return get(endpoint("/services/operations/$id"))
    }
}
