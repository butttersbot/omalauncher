package dev.omalauncher.artifactpreview

import dev.omalauncher.connections.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** In-memory runner transport, ONLY in the separate preview APK. No real host, keys or restart API. */
internal class ConnectionsFakeRunner(private val scenario: String) {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val keyId = MessageDigest.getInstance("SHA-256").digest(keys.public.encoded).joinToString("") { "%02x".format(it) }
    private val approval = ServiceApproval("fixture_approval_123", "fictional_nonce_123456789012345", "runner", "fictional-mac", System.currentTimeMillis() + 120_000)
    private var submitted = false
    private var operation: JSONObject? = null
    var posts = 0; private set
    var backendCalls = 0; private set
    private val client = ServiceControlClient(ConnectionEndpoint("http://fictional.invalid", "fictional-key"), get = { endpoint ->
        require(endpoint.url.endsWith("/services/operations/op-fixture_12345678"))
        ConnectionReply(200, JSONObject().put("ok", true).put("operation", operation).toString())
    }, post = { endpoint, body ->
        require(endpoint.url == "http://fictional.invalid/services/runner/restart")
        if (body.length() == 0) ConnectionReply(202, JSONObject().put("ok", true).put("approval", JSONObject()
            .put("id", approval.id).put("nonce", approval.nonce).put("service", approval.service).put("host", approval.host)
            .put("expires_at", approval.expiresAt).put("payload", approval.payload)).toString())
        else {
            require(!submitted) { "Fixture refuses replay" }
            require(body.getString("approval") == approval.id && body.getString("nonce") == approval.nonce && body.getString("decision") == "allow")
            val plan = OwnerServicePlan(listOf(approval))
            require(body.getJSONArray("owner_payloads").toString() == JSONArray(plan.encodedPayloads).toString())
            require(body.getString("owner_key_id") == keyId)
            val signature = Base64.getDecoder().decode(body.getString("owner_signature"))
            require(Signature.getInstance("SHA256withECDSA").apply { initVerify(keys.public); update(plan.payload.toByteArray()) }.verify(signature))
            submitted = true; posts++
            val stage = when (scenario) { "Active jobs" -> "blocked"; "Failed repair" -> "failed"; else -> "restored" }
            if (stage != "blocked") backendCalls++
            val checks = JSONArray()
            for (name in listOf("work_idle", "runner_identity", "provider_auth", "build_toolchain", "source_freshness")) checks.put(JSONObject()
                .put("check", name).put("target", "fictional-mac").put("code", if (stage == "restored") "ok" else "fixture_refusal")
                .put("result", if (stage == "restored") "pass" else "fail").put("fields", JSONObject().put("fixture_only", true)))
            operation = JSONObject().put("id", "op-fixture_12345678").put("approval", approval.id).put("service", approval.service).put("host", approval.host)
                .put("stage", stage).put("verified", stage == "restored").put("checks", checks)
            ConnectionReply(202, JSONObject().put("ok", true).put("operation", operation).toString())
        }
    })
    fun run(): ServiceOperation {
        val response = client.challenge("runner")
        val challenge = ServiceApproval.parse(JSONObject(response.text).getJSONObject("approval"), "runner", "fictional-mac", System.currentTimeMillis())
        val plan = OwnerServicePlan(listOf(challenge))
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(plan.payload.toByteArray()) }.sign()
        val accepted = client.settle(challenge, plan, keyId, Base64.getEncoder().encodeToString(signature), System.currentTimeMillis())
        val pending = ServiceOperation.parse(JSONObject(accepted.text).getJSONObject("operation"), null, challenge)
        val checked = client.operation(pending.id)
        return ServiceOperation.parse(JSONObject(checked.text).getJSONObject("operation"), pending.id, challenge).also { require(posts == 1) }
    }
}
