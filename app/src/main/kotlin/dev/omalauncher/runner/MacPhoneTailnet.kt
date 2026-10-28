package dev.omalauncher.runner

import dev.omalauncher.assist.readMacMachines
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** Only authoritative whois confirms identity. Service failure and a reachable helper's denial stay distinct. */
class MacPhoneTailnet(
    private val configDir: File,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val nanoNow: () -> Long = System::nanoTime,
) : PhoneTailnet {
    private data class Reply(val code: Int, val json: JSONObject)
    private val known = mutableMapOf<String, Pair<Long, RunnerIdentityLookup>>()
    @Volatile private var deviceAddresses: Map<String, List<String>> = emptyMap()
    private fun reply(path: String, body: JSONObject? = null): Reply? {
        for (machine in readMacMachines(configDir)) {
            val response = runCatching {
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
                    val code = connection.responseCode
                    // An unavailable/rate-limited identity service cannot make a new confirmation.
                    if (code >= 500 || code == 429) return@runCatching null
                    val stream = if (code == 200) connection.inputStream else connection.errorStream
                    val bytes = stream?.use { it.readBytesLimited(262_144) }
                    val json = bytes?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }
                    if (json == null) Reply(400, JSONObject()) else Reply(code, json)
                } finally { connection.disconnect() }
            }.getOrNull()
            if (response != null) return response
        }
        return null
    }
    private fun call(path: String, body: JSONObject? = null): JSONObject {
        val response = reply(path, body) ?: error("Tailnet helper unavailable")
        check(response.code == 200 && response.json.optBoolean("ok")) { "Tailnet helper refused" }
        return response.json
    }
    override fun devices(): List<String> {
        val array = call("/phone-runner/tailnet").optJSONArray("devices") ?: return emptyList()
        deviceAddresses = (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val name = row.optString("name")
            if (!Regex("[a-z0-9][a-z0-9-]{0,62}").matches(name) || name == PHONE_RUNNER_DEVICE) return@mapNotNull null
            val addresses = row.optJSONArray("addresses")
            name to (0 until (addresses?.length() ?: 0)).mapNotNull { addresses?.optString(it)?.takeIf(::runnerTailnetIPv4) }
        }.toMap()
        return deviceAddresses.keys.toList()
    }
    override fun confirmDevice(device: String): RunnerCachedIdentity? {
        if (device !in deviceAddresses) devices()
        for (address in deviceAddresses[device].orEmpty()) {
            val result = lookup(address)
            if (result is RunnerIdentityLookup.Confirmed && result.peer.owner && result.peer.device == device) return RunnerCachedIdentity(address, result.at)
        }
        return null
    }
    @Synchronized override fun lookup(address: String): RunnerIdentityLookup {
        if (!runnerTailnetIPv4(address)) return RunnerIdentityLookup.Rejected
        val nano = nanoNow()
        known[address]?.takeIf { nano >= it.first && nano - it.first < 60_000_000_000L }?.let { return it.second }
        val response = reply("/phone-runner/whois?address=" + URLEncoder.encode(address, "UTF-8"))
        val identity = when {
            response == null -> RunnerIdentityLookup.Unavailable
            response.code != 200 || !response.json.optBoolean("ok") || !response.json.optBoolean("owner") -> RunnerIdentityLookup.Rejected
            else -> response.json.optString("device").takeIf { Regex("[a-z0-9][a-z0-9-]{0,62}").matches(it) }
                ?.let { RunnerIdentityLookup.Confirmed(RunnerPeer(it, true), wallNow()) } ?: RunnerIdentityLookup.Rejected
        }
        if (known.size >= 64) known.clear()
        known[address] = nano to identity
        return identity
    }
    override fun whois(address: String): RunnerPeer? = (lookup(address) as? RunnerIdentityLookup.Confirmed)?.peer
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
