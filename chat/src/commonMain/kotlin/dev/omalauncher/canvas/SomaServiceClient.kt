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

/**
 * One sync round's exchange with the service at [service] for [screen] ([columns] by [rows]), with [key]: what was sent
 * since [since], and the log's tail back; null when the service can't be reached or says no.
 */
fun syncExchange(service: String, key: String, screen: String, columns: Int, rows: Int, since: Long, sent: List<SentVerb>): JSONObject? {
    val body = JSONObject().put("since", since).put("columns", columns).put("rows", rows).put("verbs", org.json.JSONArray(sent.map { SyncWire.sent(it) }))
    val (code, answer) = somaPost(service.trimEnd('/') + "/screens/" + screen + "/sync", key, body.toString())
    // Not reachable: null, and the next round tries again. Refused: said, so it isn't taken for being offline.
    return when (code) {
        200 -> runCatching { JSONObject(answer) }.getOrNull()
        -1 -> null
        else -> JSONObject().put("refused", code).put("error", runCatching { JSONObject(answer).optString("error") }.getOrNull()?.ifEmpty { null } ?: "HTTP $code")
    }
}

/** The first of [machines] whose runner says where a canvas service is: that address and the key for it; null when none does. */
fun findSomaService(machines: List<MacMachine>, advertised: (String, String) -> String? = ::advertisedService): Pair<String, String>? =
    machines.firstNotNullOfOrNull { m -> advertised(m.config.url, m.config.key)?.let { it to m.config.key } }

/**
 * How the service's public door (Funnel: the car and Grok come in there) stood when last asked: [up] or not, [since] when
 * (the service's clock, milliseconds). Only from a service that has a public address; one with none says nothing.
 */
data class PublicDoor(val up: Boolean, val since: Long)

/** [health], the service's /health answer, as its public door stands; null when it has no public address or hasn't looked yet. */
fun publicDoorOf(health: JSONObject?): PublicDoor? {
    val p = health?.optJSONObject("public") ?: return null
    if (p.isNull("url") || p.isNull("up")) return null
    return PublicDoor(p.getBoolean("up"), p.optLong("since"))
}

/** The service's /health at [service], asked with this device's [key]; null when it doesn't answer as the service. */
fun somaHealth(service: String, key: String): JSONObject? = runCatching {
    val c = URI(service.trimEnd('/') + "/health").toURL().openConnection() as HttpURLConnection
    c.connectTimeout = 4000; c.readTimeout = 6000
    c.setRequestProperty("Authorization", "Bearer $key")
    if (c.responseCode != 200) null else JSONObject(c.inputStream.use { String(it.readBytes()) }).takeIf { it.optString("service") == "soma-serve" }
}.getOrNull()

/**
 * How the phone reads the public door each refresh: the service's address is remembered once a runner has said it, and asked
 * for again only when the service stops answering there (round-1 review: asking every runner each 30 s cost a timeout per Mac
 * that was off the net).
 */
class PublicDoorReader(
    private val machines: () -> List<MacMachine>,
    private val advertised: (String, String) -> String? = ::advertisedService,
    private val health: (String, String) -> JSONObject? = ::somaHealth,
) {
    @Volatile private var at: Pair<String, String>? = null

    fun read(): PublicDoor? {
        at?.let { (service, key) -> health(service, key)?.let { return publicDoorOf(it) } }
        val found = findSomaService(machines(), advertised)
        at = found
        return found?.let { (service, key) -> publicDoorOf(health(service, key)) }
    }
}
