package dev.omalauncher.runner

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Identity is from Tailscale whois, never from a request header or a reverse DNS lookup. */
data class RunnerPeer(val device: String, val owner: Boolean)
data class RunnerKey(val device: String, val digest: String)
fun runnerDigest(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun runnerAuthorized(token: String?, peer: RunnerPeer?, keys: List<RunnerKey>): Boolean {
    if (token.isNullOrBlank() || token.length > 512 || peer?.owner != true) return false
    val supplied = runnerDigest(token).toByteArray(Charsets.US_ASCII)
    return keys.any { MessageDigest.isEqual(supplied, it.digest.toByteArray(Charsets.US_ASCII)) && it.device == peer.device }
}

interface PhoneRunnerSessions {
    fun list(): List<JSONObject>
    fun events(id: String, after: Long): List<JSONObject>
    fun message(id: String, text: String): JSONObject
    fun answer(id: String, approval: String, nonce: String, decision: String, answers: JSONObject?, device: String): JSONObject
    fun stop(id: String)
}
class RunnerFault(val code: Int, override val message: String) : RuntimeException(message)
data class RunnerResponse(val code: Int, val body: JSONObject)

/** Auth happens before route dispatch: an unpaired caller learns nothing, even from /health. */
class PhoneRunnerProtocol(private val sessions: PhoneRunnerSessions) {
    fun handle(method: String, path: String, body: String, authorized: Boolean, device: String = ""): RunnerResponse {
        if (!authorized) return RunnerResponse(401, JSONObject().put("ok", false).put("error", "unauthorized"))
        return try {
            val uri = java.net.URI(path)
            val parts = uri.path.split('/').filter(String::isNotEmpty)
            fun ok(value: JSONObject) = RunnerResponse(200, value.put("ok", true))
            if (method == "GET" && parts == listOf("health")) {
                val rows = sessions.list()
                return ok(JSONObject().put("runner", "oma-phone").put("threads", rows.size)
                    .put("working", rows.count { it.optString("status") == "working" })
                    .put("waiting", rows.count { it.optString("status") == "waiting" })
                    .put("kinds", JSONArray(rows.map { it.optString("kind") }.filter(String::isNotEmpty).distinct())))
            }
            if (method == "GET" && parts == listOf("threads")) return ok(JSONObject().put("threads", JSONArray(sessions.list())))
            if (parts.size !in 2..4 || parts[0] != "threads") throw RunnerFault(404, "not found")
            val id = parts[1]
            val row = sessions.list().firstOrNull { it.optString("id") == id } ?: throw RunnerFault(404, "no such thread")
            if (method == "GET" && parts.size == 2) return ok(JSONObject().put("thread", row))
            if (method == "GET" && parts.size == 3 && parts[2] in setOf("events", "stream")) {
                val query = uri.rawQuery.orEmpty()
                val after = if (query.isEmpty()) 0L else Regex("after=([0-9]{1,18})").matchEntire(query)?.groupValues?.get(1)?.toLongOrNull()
                    ?: throw RunnerFault(400, "invalid cursor")
                return ok(JSONObject().put("status", row.optString("status")).put("events", JSONArray(sessions.events(id, after))))
            }
            if (method != "POST") throw RunnerFault(404, "not found")
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            when {
                parts.size == 3 && parts[2] == "messages" -> {
                    if (id == "phone-d") throw RunnerFault(403, "Phone D takes agent mail only")
                    val text = json.opt("text") as? String ?: throw RunnerFault(400, "text required")
                    if (text.isBlank() || text.length > 100_000) throw RunnerFault(400, "invalid text")
                    ok(sessions.message(id, text))
                }
                parts.size == 3 && parts[2] == "stop" -> { sessions.stop(id); ok(JSONObject()) }
                parts.size == 4 && parts[2] == "approvals" -> {
                    val decision = json.optString("decision")
                    if (decision !in setOf("allow", "deny")) throw RunnerFault(400, "invalid decision")
                    if (json.has("answers") && json.optJSONObject("answers") == null) throw RunnerFault(400, "invalid answers")
                    ok(sessions.answer(id, parts[3], json.optString("nonce"), decision, json.optJSONObject("answers"), device))
                }
                else -> throw RunnerFault(404, "not found")
            }
        } catch (e: RunnerFault) {
            RunnerResponse(e.code, JSONObject().put("ok", false).put("error", e.message))
        } catch (_: org.json.JSONException) {
            RunnerResponse(400, JSONObject().put("ok", false).put("error", "invalid JSON"))
        } catch (_: java.net.URISyntaxException) {
            RunnerResponse(400, JSONObject().put("ok", false).put("error", "invalid path"))
        }
    }
}
