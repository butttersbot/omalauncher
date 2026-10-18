package dev.omalauncher.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/*
 * Who may use the service's tailnet door (Soma Canvas slice 8a, PRD §6 "Tailnet door: device key plus `tailscale whois`,
 * the runner's rule unchanged"): a device Boss paired with this Mac's runner, calling from that very device. The table is
 * the runner's own (oma-runner.keys.json: each device's name and the digest of its key, never the key), read again when
 * it changes, so pairing a device for the runner pairs it here. A car key, kept apart, is never in it. Agents never hold
 * a device key: they get a per-thread token their runner registers here, for verbs on the screens it names and nothing more.
 */

/** "sha256:" and the hex SHA-256 of [key], as the runner keeps keys. */
fun keyDigest(key: String): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

/** The runner's table of paired devices and who is calling, by tailnet address. */
class PairedDevices(
    private val keysFile: File,
    /**
     * The runners' own keys for this service (slice 11), apart from the devices': a runner's key registers its threads'
     * tokens and nothing else, and lives in a file of the service's own, so no change to the runner's device table ever
     * turns it into a device's key.
     */
    private val runnerKeys: File? = null,
    /** The device at a caller's address, as `tailscale whois` names it under Boss's own login; null for anyone else. */
    private val whois: (String) -> String?,
) {
    private var runnerStamp: Pair<Long, Long>? = null
    private var runners: List<Pair<String, String>> = emptyList()

    @Synchronized private fun runnerTable(): List<Pair<String, String>> {
        val f = runnerKeys ?: return emptyList()
        val now = f.lastModified() to f.length()
        if (now != runnerStamp) {
            runners = runCatching {
                val a = JSONObject(f.readText()).optJSONArray("runners") ?: JSONArray()
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { d -> d.optString("name") to d.optString("digest") } }
                    .filter { (name, digest) -> DEVICE.matches(name) && DIGEST.matches(digest) }
            }.getOrDefault(emptyList())
            runnerStamp = now
        }
        return runners
    }

    /** The runner calling from [address] with [key]: only when the key is a runner's and that runner's machine is calling. */
    fun runnerCaller(key: String, address: String): String? {
        val given = keyDigest(key).toByteArray()
        var found: String? = null
        for ((name, digest) in runnerTable()) if (MessageDigest.isEqual(given, digest.toByteArray())) found = name
        return found?.takeIf { it == whois(address) }
    }

    private var stamp: Pair<Long, Long>? = null
    private var devices: List<Pair<String, String>> = emptyList()

    @Synchronized private fun table(): List<Pair<String, String>> {
        val now = keysFile.lastModified() to keysFile.length()
        if (now != stamp) {
            devices = runCatching {
                val a = JSONObject(keysFile.readText()).optJSONArray("devices") ?: JSONArray()
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { d -> d.optString("name") to d.optString("digest") } }
                    .filter { (name, digest) -> DEVICE.matches(name) && DIGEST.matches(digest) }
            }.getOrDefault(emptyList())
            stamp = now
        }
        return devices
    }

    /** The device [key] was given to, or null. Every digest is compared in full, so timing says nothing. */
    fun ownerOf(key: String): String? {
        val given = keyDigest(key).toByteArray()
        var found: String? = null
        for ((name, digest) in table()) if (MessageDigest.isEqual(given, digest.toByteArray())) found = name
        return found
    }

    /** The paired device calling from [address] with [key]: only when the key is that device's own. */
    fun caller(key: String, address: String): String? = ownerOf(key)?.takeIf { it == whois(address) }

    /** The digest of [device]'s key, which a signed approval from it is keyed with (CommandGate.applySigned). */
    fun digestOf(device: String): String? = table().firstOrNull { it.first == device }?.second

    companion object {
        private val DEVICE = Regex("[a-z0-9][a-z0-9-]{0,62}")
        private val DIGEST = Regex("sha256:[0-9a-f]{64}")
    }
}

/** `tailscale whois`, as the runner asks it: the caller's machine name when it is signed in as Boss; kept a minute. */
class TailscaleWhois(private val tailscale: String = find()) {
    private val known = ConcurrentHashMap<String, Pair<String?, Long>>()
    @Volatile private var mine: String? = null

    private fun run(vararg args: String): JSONObject? = runCatching {
        // The app's binary won't run without a TERM (the runner sets one too).
        val p = ProcessBuilder(listOf(tailscale) + args).redirectErrorStream(false).apply { environment().putIfAbsent("TERM", "dumb") }.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); null } else JSONObject(out)
    }.getOrNull()

    fun name(address: String): String? {
        known[address]?.takeIf { System.currentTimeMillis() - it.second < 60_000 }?.let { return it.first }
        val login = mine ?: run("status", "--json")?.let { s -> s.getJSONObject("User").getJSONObject(s.getJSONObject("Self").get("UserID").toString()).getString("LoginName") }?.also { mine = it }
        val who = run("whois", "--json", address)
        val name = if (login != null && who?.optJSONObject("UserProfile")?.optString("LoginName") == login) {
            val node = who.optJSONObject("Node")
            (node?.optString("ComputedName")?.ifEmpty { null } ?: node?.optString("Name")?.substringBefore('.'))?.lowercase()?.ifEmpty { null }
        } else null
        known[address] = name to System.currentTimeMillis()
        return name
    }

    companion object {
        /**
         * Where the Tailscale command is, looked for as the runner looks: named outright, the App Store app, then Homebrew's
         * (round: this Mac has only Homebrew's, and every caller was refused).
         */
        fun find(env: (String) -> String? = System::getenv, exists: (String) -> Boolean = { File(it).canExecute() }): String =
            listOfNotNull(env("OMA_RUNNER_TAILSCALE"), env("OMA_RUNNER_TAILSCALE_APP"), "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
                "/opt/homebrew/bin/tailscale", "/usr/local/bin/tailscale").firstOrNull(exists) ?: "tailscale"

        /** This Mac's own tailnet address (100.64.0.0/10), found without running anything; null off the tailnet. */
        fun address(): String? = runCatching {
            DatagramSocket().use { probe ->
                probe.connect(InetSocketAddress(InetAddress.getByName("100.100.100.100"), 53))
                probe.localAddress.hostAddress.takeIf { a -> a.split('.').let { it.size == 4 && it[0] == "100" && it[1].toInt() in 64..127 } }
            }
        }.getOrNull()
    }
}

/** What a thread's token allows: verbs on [screens], for [thread], until [expires]. Never approvals, keys or widgets' commands. */
data class ThreadGrant(val thread: String, val screens: Set<String>, val expires: Long, val by: String)

/**
 * The tokens runners mint for their threads (PRD §6: "the runner mints a per-thread canvas token into the thread's
 * environment"), registered here by the runner's own device, kept as digests only, and gone when the runner says the thread
 * ended or when they expire. Kept in [file] so a restart of the service doesn't strand a running thread.
 */
class ThreadTokens(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val grants = ConcurrentHashMap<String, ThreadGrant>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            for (digest in o.keys()) o.getJSONObject(digest).let { g ->
                grants[digest] = ThreadGrant(g.getString("thread"), g.getJSONArray("screens").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }, g.getLong("expires"), g.getString("by"))
            }
        }
    }

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        val o = JSONObject()
        grants.forEach { (d, g) -> o.put(d, JSONObject().put("thread", g.thread).put("screens", JSONArray(g.screens.toList())).put("expires", g.expires).put("by", g.by)) }
        val fresh = File(file.parentFile, "." + file.name + ".new")
        fresh.writeText(o.toString())
        fresh.setReadable(false, false); fresh.setReadable(true, true); fresh.setWritable(false, false); fresh.setWritable(true, true)
        if (!fresh.renameTo(file)) { file.delete(); fresh.renameTo(file) }
    }

    /** Registers the token whose digest is [digest] for [grant]. At most a day: a thread that outlives it gets a new one. */
    @Synchronized fun register(digest: String, grant: ThreadGrant) {
        require(Regex("sha256:[0-9a-f]{64}").matches(digest)) { "a token is registered by its digest" }
        grants[digest] = grant.copy(expires = minOf(grant.expires, now() + DAY))
        grants.entries.removeAll { it.value.expires < now() }
        save()
    }

    /** Forgets the token [digest] when [device] registered it; true when it did. Another device's tokens are not its to end. */
    @Synchronized fun revoke(digest: String, device: String): Boolean {
        if (grants[digest]?.by != device) return false
        grants.remove(digest)
        save()
        return true
    }

    /** What [token] allows now, or null. */
    fun grant(token: String): ThreadGrant? = grants[keyDigest(token)]?.takeIf { it.expires > now() }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L

        /** What a thread token looks like, so it is never taken for a device key. */
        const val PREFIX = "soma-thread-"
    }
}
