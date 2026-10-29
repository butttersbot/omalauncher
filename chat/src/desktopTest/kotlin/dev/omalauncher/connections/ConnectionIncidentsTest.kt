package dev.omalauncher.connections

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ConnectionIncidentsTest {
    @Test fun redactsStructuredKeysKnownSecretsHeadersAndQuotedAssignments() {
        val redactor = ConnectionRedactor(listOf("known-device-secret"))
        val value = JSONObject().put("access_token", "raw-access").put("checks", "Bearer hidden-bearer known-device-secret https://name:pass@example/health token=hidden-query {\"refresh_token\":\"hidden-refresh\"}")
            .put("stderr", "Cookie: session=hidden-cookie; csrf=hidden-csrf")
        val safe = (redactor.json(value) as JSONObject).toString()
        for (secret in listOf("raw-access", "hidden-bearer", "known-device-secret", "name:pass", "hidden-query", "hidden-refresh", "hidden-cookie", "hidden-csrf")) assertFalse(secret, safe.contains(secret))
    }
    @Test fun deduplicatesAcrossInstancesAndCanonicalFieldOrderAndKeepsNewestReadable() {
        val directory = Files.createTempDirectory("connection-incidents").toFile()
        try {
            val first = ConnectionIncidentJournal(directory).record("runner", "Mac", "blocked", "Busy", "active_work",
                listOf(ConnectionCheck("launchd", "Mac", 409, "blocked", linkedMapOf("pid" to "1", "state" to "active"))), emptyList(), 100)
            val second = ConnectionIncidentJournal(directory).record("runner", "Mac", "blocked", "Busy", "active_work",
                listOf(ConnectionCheck("launchd", "Mac", 409, "blocked", linkedMapOf("state" to "active", "pid" to "1"))), emptyList(), 200)
            assertEquals(first.getString("id"), second.getString("id"))
            assertEquals(2L, second.getLong("occurrences"))
            assertEquals(100L, second.getLong("time")); assertEquals(200L, second.getLong("last_seen"))
            assertEquals(second.getString("id"), ConnectionIncidentJournal(directory).read()!!.getString("id"))
            try { ConnectionIncidentJournal(directory).read("../secrets"); fail("accepted traversal") } catch (_: IllegalArgumentException) {}
        } finally { directory.deleteRecursively() }
    }
    @Test fun writesRedactedEvidenceNotApprovalMaterial() {
        val directory = Files.createTempDirectory("connection-incidents").toFile()
        try {
            val saved = ConnectionIncidentJournal(directory) { listOf("owner-secret") }.record("canvas", "Mac", "failed", "Auth refused", "auth_required",
                listOf(ConnectionCheck("GET boards", "http://Mac/api/apps", 401, "auth_required", mapOf("Authorization" to "Bearer owner-secret"), "password=private-password")),
                listOf(ConnectionNextStep("agent", "inspect_pairing", "No restart attempted")), 1000)
            assertEquals(401, saved.getJSONArray("checks").getJSONObject(0).getInt("code"))
            for (file in directory.listFiles().orEmpty().filter { it.extension == "json" }) {
                assertFalse(file.readText().contains("owner-secret")); assertFalse(file.readText().contains("private-password"))
            }
        } finally { directory.deleteRecursively() }
    }
}
