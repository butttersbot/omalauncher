package dev.omalauncher.runner

import dev.omalauncher.assist.readMacMachines
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** Android's VPN app has no local whois API. An already-paired runner asks its Tailscale daemon.
 * A missing helper or ambiguous identity refuses authentication; a device list is never used as whois.
 */
class MacPhoneTailnet(private val configDir: File) : PhoneTailnet {
    private val known = mutableMapOf<String, Pair<Long, RunnerPeer?>>()
    private fun call(path: String, body: JSONObject? = null): JSONObject {
        for (machine in readMacMachines(configDir)) {
            val reply = runCatching {
                val connection = URL(machine.config.url.trimEnd('/') + path).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 2000; connection.readTimeout = 4000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Authorization", "Bearer ${machine.config.key}")
                    if (body != null) {
                        connection.requestMethod = "POST"; connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    if (connection.responseCode != 200) return@runCatching null
                    val bytes = connection.inputStream.use { it.readBytesLimited(262_144) }
                    JSONObject(bytes.toString(Charsets.UTF_8)).takeIf { it.optBoolean("ok") }
                } finally { connection.disconnect() }
            }.getOrNull()
            if (reply != null) return reply
        }
        error("Tailnet identity service unavailable; nothing was paired or opened")
    }
    override fun devices(): List<String> {
        val array = call("/phone-runner/tailnet").optJSONArray("devices") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
            .filter { Regex("[a-z0-9][a-z0-9-]{0,62}").matches(it) && it != "galaxy-z-fold8" }.distinct()
    }
    @Synchronized override fun whois(address: String): RunnerPeer? {
        val now = System.nanoTime()
        known[address]?.takeIf { now - it.first < 60_000_000_000L }?.let { return it.second }
        val peer = runCatching {
            val reply = call("/phone-runner/whois?address=" + URLEncoder.encode(address, "UTF-8"))
            reply.optString("device").takeIf { Regex("[a-z0-9][a-z0-9-]{0,62}").matches(it) }
                ?.let { RunnerPeer(it, reply.optBoolean("owner")) }
        }.getOrNull()
        if (known.size >= 64) known.clear()
        known[address] = now to peer
        return peer
    }
    override fun taildrop(device: String, filename: String, content: String) {
        call("/phone-runner/taildrop", JSONObject().put("device", device).put("name", filename).put("content", content))
    }
}
private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; check(out.size() + n <= limit); out.write(buffer, 0, n) }
    return out.toByteArray()
}
