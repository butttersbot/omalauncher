package dev.omalauncher.connections

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** A bounded GET; credentials are supplied only by the configured endpoint, never copied across hosts. */
data class ConnectionEndpoint(val url: String, val key: String = "", val header: String = "Authorization") {
    override fun toString() = "ConnectionEndpoint(host=${runCatching { URI(url).host }.getOrNull()})"
}
data class ConnectionReply(val code: Int, val text: String)
fun connectionGet(endpoint: ConnectionEndpoint): ConnectionReply {
    var connection: HttpURLConnection? = null
    var observedCode = -1
    return try {
        val uri = URI(endpoint.url)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null)
        connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2500; connection.readTimeout = 3500; connection.instanceFollowRedirects = false
        if (endpoint.key.isNotEmpty()) connection.setRequestProperty(endpoint.header, if (endpoint.header == "Authorization") "Bearer ${endpoint.key}" else endpoint.key)
        val code = connection.responseCode
        observedCode = code
        val body = (if (code < 400) connection.inputStream else connection.errorStream)?.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 262_144) { val count = it.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            val bytes = output.toByteArray()
            if (bytes.size > 262_144) return ConnectionReply(code, "")
            String(bytes, Charsets.UTF_8)
        }.orEmpty()
        ConnectionReply(code, body)
    } catch (_: Exception) { ConnectionReply(observedCode, "") } finally { connection?.disconnect() }
}

/** Schema validators require capability evidence, never just HTTP 200 or an open port. */
fun connectionState(reply: ConnectionReply, valid: (JSONObject) -> Boolean): ConnectionState = when (reply.code) {
    401, 403 -> ConnectionState.Auth
    -1 -> ConnectionState.Failed
    200 -> if (runCatching { valid(JSONObject(reply.text)) }.getOrDefault(false)) ConnectionState.Ready else ConnectionState.Unknown
    404 -> ConnectionState.Unknown
    else -> ConnectionState.Degraded
}
fun connectionDetail(reply: ConnectionReply, state: ConnectionState, verified: String): String = when (state) {
    ConnectionState.Ready -> verified
    ConnectionState.Auth -> "Reachable, but authenticated capability could not be verified (HTTP ${reply.code})."
    ConnectionState.Failed -> "No response within the check deadline. Sleep or an intentional disconnect is not assumed."
    ConnectionState.Unknown -> "Responded, but capability evidence is missing (HTTP ${reply.code})."
    else -> "Capability check returned HTTP ${reply.code}."
}

/** Read the runner's actual wire fields. A missing/ambiguous inventory can never authorize a restart. */
data class RunnerWorkCounts(val active: Int, val queued: Int)
fun runnerWorkCounts(reply: JSONObject): RunnerWorkCounts? = runCatching {
    val threads = reply.getJSONArray("threads")
    var active = 0; var queued = 0
    for (i in 0 until threads.length()) {
        val thread = threads.getJSONObject(i)
        val state = thread.getString("status")
        require(state in setOf("working", "waiting", "idle", "done", "error", "failed", "stopped"))
        val waiting = thread.getInt("waiting_turns"); require(waiting >= 0)
        val pending = thread.getJSONArray("pending")
        val background = thread.getJSONArray("background")
        if (state in setOf("working", "waiting") || pending.length() > 0 || background.length() > 0) active++
        queued += waiting
    }
    RunnerWorkCounts(active, queued)
}.getOrNull()
