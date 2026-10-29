package dev.omalauncher.connections

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServiceControlTest {
    private fun approval(service: String = "runner", host: String = "fictional-mac") = ServiceApproval("approval_123", "n".repeat(32), service, host, 200_000)
    private fun wire(a: ServiceApproval) = JSONObject().put("id", a.id).put("nonce", a.nonce).put("service", a.service)
        .put("host", a.host).put("expires_at", a.expiresAt).put("payload", a.payload)
    private fun refuses(block: () -> Unit) { try { block(); fail("accepted unsafe request") } catch (_: IllegalArgumentException) {} }
    @Test fun approvalBindsExactNativeHostServiceNonceAndExpiry() {
        val a = approval()
        assertEquals(a, ServiceApproval.parse(wire(a), "runner", "fictional-mac", 100_000))
        refuses { ServiceApproval.parse(wire(a), "canvas", "fictional-mac", 100_000) }
        refuses { ServiceApproval.parse(wire(a), "runner", "other-mac", 100_000) }
        refuses { ServiceApproval.parse(wire(a), "runner", "fictional-mac", 200_000) }
        refuses { ServiceApproval.parse(wire(a).put("payload", "arbitrary command"), "runner", "fictional-mac", 100_000) }
    }
    @Test fun oneOwnerSignatureBindsTheWholeBoundedPlanWithoutOrderAmbiguity() {
        val a = approval(); val b = approval("canvas")
        assertEquals(OwnerServicePlan(listOf(a, b)).payload, OwnerServicePlan(listOf(b, a)).payload)
        refuses { OwnerServicePlan(listOf(a, a)) }
        val keys = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val payload = OwnerServicePlan(listOf(a, b)).payload.toByteArray()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(payload) }.sign()
        assertTrue(java.security.Signature.getInstance("SHA256withECDSA").apply { initVerify(keys.public); update(payload) }.verify(signature))
        assertFalse(java.security.Signature.getInstance("SHA256withECDSA").apply { initVerify(keys.public); update(OwnerServicePlan(listOf(a)).payload.toByteArray()) }.verify(signature))
    }
    @Test fun clientHasFixedPathsAndNeverPostsAChangedOrExpiredApproval() {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val client = ServiceControlClient(ConnectionEndpoint("http://fictional-mac:8478", "fictional-key"), get = { ConnectionReply(200, "{}") }, post = { e, j -> calls += e.url to j; ConnectionReply(202, "{}") })
        client.challenge("runner")
        assertEquals("http://fictional-mac:8478/services/runner/restart", calls.single().first)
        assertEquals(0, calls.single().second.length())
        refuses { client.challenge("../../shell") }
        val a = approval(); val plan = OwnerServicePlan(listOf(a))
        refuses { client.settle(a, plan, "a".repeat(64), "s".repeat(96), 200_000) }
        assertEquals(1, calls.size)
        client.settle(a, plan, "a".repeat(64), "s".repeat(96), 100_000)
        assertEquals(a.id, calls.last().second.getString("approval"))
        assertEquals(1, calls.last().second.getJSONArray("owner_payloads").length())
    }
    @Test fun operationCannotClaimRestoredOnPortOrWrongTargetEvidence() {
        val a = approval()
        fun response() = JSONObject().put("id", "op-12345678").put("approval", a.id).put("service", a.service).put("host", a.host)
            .put("stage", "restored").put("verified", true).put("checks", org.json.JSONArray().put(JSONObject().put("check", "capability").put("result", "pass")))
        assertTrue(ServiceOperation.parse(response(), "op-12345678", a).restored)
        refuses { ServiceOperation.parse(response().put("verified", false), "op-12345678", a) }
        refuses { ServiceOperation.parse(response().put("checks", org.json.JSONArray()), "op-12345678", a) }
        refuses { ServiceOperation.parse(response().put("host", "another-mac"), "op-12345678", a) }
        refuses { ServiceOperation.parse(response(), "op-87654321", a) }
        refuses { ServiceOperation.parse(response().put("checks", org.json.JSONArray().put(JSONObject().put("check", "capability").put("result", "unsupported"))), "op-12345678", a) }
        val pending = ServiceOperation.parse(response().put("stage", "verifying").put("verified", false), "op-12345678", a)
        assertFalse(pending.terminal); assertFalse(pending.restored)
    }

    @Test fun onlyExplicitPreExecutionRefusalsClearAnUncertainSubmission() {
        assertTrue(definitiveServiceRefusal(ConnectionReply(403, """{"ok":false,"error":"bad_signature"}""")))
        assertFalse(definitiveServiceRefusal(ConnectionReply(500, """{"ok":false,"error":"bad_signature"}""")))
        assertFalse(definitiveServiceRefusal(ConnectionReply(400, """{"ok":false,"error":"restart_error"}""")))
        assertFalse(definitiveServiceRefusal(ConnectionReply(400, "gateway timeout")))
        assertFalse(definitiveServiceRefusal(ConnectionReply(-1, "")))
        assertFalse(definitiveServiceRefusal(ConnectionReply(403, """{"ok":false,"error":"bad_signature","operation":{}}""")))
    }

    @Test fun workGuardNeverAsksForOwnerApprovalWithUnknownQueuedWaitingOrLockedWork() {
        fun idle() = JSONObject().put("working", 0).put("waiting", 0).put("queued", 0).put("background", 0)
            .put("locks", org.json.JSONArray()).put("admission", "open")
        assertTrue(serviceWorkEvidence(idle(), "mac").all { it.result == "pass" })
        for (name in listOf("working", "waiting", "queued", "background")) {
            assertTrue(serviceWorkEvidence(idle().put(name, 1), "mac").any { it.result == "blocked" })
            assertTrue(serviceWorkEvidence(idle().apply { remove(name) }, "mac").any { it.result == "unverified" })
        }
        assertTrue(serviceWorkEvidence(idle().put("queued", "0"), "mac").any { it.result == "unverified" })
        assertTrue(serviceWorkEvidence(idle().put("admission", "closed"), "mac").any { it.result != "pass" })
        assertTrue(serviceWorkEvidence(idle().put("locks", org.json.JSONArray().put(JSONObject().put("held", true).put("name", "walks"))), "mac").any { it.result == "blocked" })
        assertTrue(serviceWorkEvidence(null, "mac").any { it.result == "unverified" })
    }

}
