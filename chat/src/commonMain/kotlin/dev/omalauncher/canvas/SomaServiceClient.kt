package dev.omalauncher.canvas

import dev.omalauncher.assist.MacMachine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/*
 * How a device reaches the canvas service (Soma Canvas slice 8a): at a Mac's runner address on the service's own port
 * (8481), with the same key the device holds for that Mac's runner, since the service trusts the runner's own table of
 * paired devices. The device doesn't need to know which Mac runs the service: it asks each it is paired with, in order,
 * and the one that answers as the service is it.
 */

/** The service's port when the runner doesn't say (8479 and 8480 are herd's). */
const val SOMA_SERVICE_PORT = 8481

/** The service's address on the Mac whose runner is at [runnerUrl]: the same host, the service's port. */
fun somaServiceUrl(runnerUrl: String): String? = runCatching {
    val u = URI(runnerUrl.trim())
    if (u.scheme != "http" && u.scheme != "https" || u.host.isNullOrEmpty()) null else URI(u.scheme, null, u.host, SOMA_SERVICE_PORT, null, null, null).toString()
}.getOrNull()

/**
 * The service's address as the runner at [runnerUrl] advertises it in its /health ("soma"), asked with [key]; null when it
 * says none, or names another host than the runner's own: the phone's key for this Mac goes to this Mac and nowhere else.
 */
fun advertisedService(runnerUrl: String, key: String): String? = runCatching {
    val c = URI(runnerUrl.trimEnd('/') + "/health").toURL().openConnection() as HttpURLConnection
    c.connectTimeout = 4000; c.readTimeout = 6000
    c.setRequestProperty("Authorization", "Bearer $key")
    if (c.responseCode != 200) null else JSONObject(c.inputStream.use { String(it.readBytes()) }).optString("soma").ifEmpty { null }
        ?.takeIf { (it.startsWith("http://") || it.startsWith("https://")) && URI(it).host.equals(URI(runnerUrl).host, ignoreCase = true) }
}.getOrNull()

/** A POST of [body] to [url] with [key]: the status and the answer, or -1 when nobody answered. */
fun somaPost(url: String, key: String, body: String): Pair<Int, String> = runCatching {
    val c = URI(url).toURL().openConnection() as HttpURLConnection
    c.connectTimeout = 4000; c.readTimeout = 15000; c.requestMethod = "POST"; c.doOutput = true
    c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write(body.toByteArray()) }
    val code = c.responseCode
    code to ((if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: "")
}.getOrElse { -1 to "" }

/**
 * Installs the widget [name] ([text]) into the shared library through the first of [machines] whose service answers;
 * it is checked there and waits in quarantine on every machine until he approves it. The service's answer, or why
 * none took it.
 */
fun installOnSomaService(
    machines: List<MacMachine>, name: String, text: String,
    post: (String, String, String) -> Pair<Int, String> = ::somaPost,
    advertised: (String, String) -> String? = ::advertisedService,
): JSONObject {
    val body = JSONObject().put("name", name).put("text", text).toString()
    for (m in machines) {
        // Where the Mac's runner says its service is; only a Mac that says so is asked, so nothing else on a port gets the file.
        val url = advertised(m.config.url, m.config.key)?.trimEnd('/') ?: continue
        val (code, answer) = post("$url/widgets", m.config.key, body)
        if (code == 200) return runCatching { JSONObject(answer) }.getOrElse { JSONObject().put("ok", false).put("error", "the service answered oddly") }.put("on", m.name)
    }
    return JSONObject().put("ok", false).put("error", "No Mac answered as the canvas service. It runs on the Mac Pro once its service is installed (soma --serve).")
}
