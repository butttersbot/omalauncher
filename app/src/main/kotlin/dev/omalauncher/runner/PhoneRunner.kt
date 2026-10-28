package dev.omalauncher.runner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.json.JSONArray
import dev.omalauncher.assist.tailscaleAddress

interface PhoneTailnet {
    fun devices(): List<String>
    fun whois(address: String): RunnerPeer?
    fun lookup(address: String): RunnerIdentityLookup = whois(address)?.let { RunnerIdentityLookup.Confirmed(it, System.currentTimeMillis()) } ?: RunnerIdentityLookup.Rejected
    fun confirmDevice(device: String): RunnerCachedIdentity? = null
    fun taildrop(device: String, filename: String, content: String)
}

/** No raw key is persisted; unpair takes effect on the next request, including a stream heartbeat. */
class PhoneRunnerKeys(context: Context) {
    private val prefs = context.getSharedPreferences("phone-runner-devices", Context.MODE_PRIVATE)
    private val ownPrefs = context.getSharedPreferences("phone-runner-self", Context.MODE_PRIVATE)
    fun keys(): List<RunnerKey> = prefs.all.mapNotNull { (device, record) -> (record as? String)?.let { runnerKeyFromRecord(device, it) } }
    @Synchronized fun put(key: RunnerKey) {
        check(prefs.edit().putString(key.device, runnerKeyRecord(key)).commit()) { "Could not save pairing" }
    }
    /** Recheck after whois: unpair/rekey during a lookup cannot resurrect an old credential. */
    @Synchronized fun confirm(expected: RunnerKey, identity: RunnerCachedIdentity?): Boolean {
        val current = keys().firstOrNull { it.device == expected.device } ?: return false
        if (current.digest != expected.digest) return false
        if (current.identity != identity) put(current.copy(identity = identity))
        return true
    }
    @Synchronized fun remove(device: String) {
        check(prefs.edit().remove(device).commit()) { "Could not remove pairing" }
    }
    fun ownIdentity(): RunnerCachedIdentity? = runCatching {
        val json = JSONObject(ownPrefs.getString("identity", "").orEmpty())
        json.getString("address").takeIf(::runnerTailnetIPv4)?.let { RunnerCachedIdentity(it, json.getLong("confirmed_at")) }
    }.getOrNull()
    @Synchronized fun ownIdentity(identity: RunnerCachedIdentity?) {
        if (identity == ownIdentity()) return
        val editor = ownPrefs.edit()
        if (identity == null) editor.remove("identity")
        else editor.putString("identity", JSONObject().put("address", identity.address).put("confirmed_at", identity.at).toString())
        check(editor.commit()) { "Could not save phone identity" }
    }
}

/** TCP lives only on the VPN's concrete address. No wildcard, LAN or loopback listener is ever opened. */
class PhoneRunner(private val context: Context, private val tailnet: PhoneTailnet) : dev.omalauncher.ui.PhoneRunnerPairing {
    val keys = PhoneRunnerKeys(context)
    private val protocol = PhoneRunnerProtocol(PhoneSessions(context))
    private val wake = Object()
    @Volatile private var started = false
    @Volatile private var server: ServerSocket? = null
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { job -> Thread(job, "phone-runner-request").apply { isDaemon = true } })

    private val streams = ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { job -> Thread(job, "phone-runner-stream").apply { isDaemon = true } })

    @Synchronized fun start() {
        if (started) return
        started = true
        thread(name = "phone-runner-listener", isDaemon = true) {
            while (true) {
                synchronized(wake) { while (keys.keys().isEmpty()) wake.wait() }
                val address = ownAddress()
                if (address == null) { synchronized(wake) { wake.wait(30_000) }; continue }
                runCatching {
                    ServerSocket().use { listener ->
                        listener.reuseAddress = true
                        listener.bind(java.net.InetSocketAddress(address, 8478))
                        listener.soTimeout = 30_000
                        server = listener
                        while (keys.keys().isNotEmpty() && ownAddress() == address) {
                            val socket = try { listener.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                            try { workers.execute { try { if (!serve(socket)) socket.close() } catch (_: Exception) { socket.close() } } } catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
                        }
                    }
                }
                server = null
                Thread.sleep(5_000)
            }
        }
    }
    override fun devices() = tailnet.devices()
    override fun paired() = keys.keys().map { it.device }
    override fun unpair(device: String) { keys.remove(device); if (keys.keys().isEmpty()) runCatching { server?.close() } }
    /** Called only by the setting's native Pair tap, never an HTTP route. */
    @Synchronized override fun pair(device: String) {
        require(device != PHONE_RUNNER_DEVICE && device in tailnet.devices()) { "That computer is not one of your tailnet devices" }
        val address = ownAddress() ?: error("Phone is not on Tailscale")
        val confirmed = tailnet.confirmDevice(device) ?: error("Computer identity could not be confirmed")
        val local = localAddresses() ?: error("Phone network identity unavailable")
        require(runnerRemoteSource(confirmed.address, local + address)) { "This phone cannot pair itself" }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val config = JSONObject().put("url", "http://$address:8478").put("key", key).put("name", "Fold").put("runner", "galaxy-z-fold8")
        // Retain the old pairing until the transfer succeeds; a failed re-pair must not strand its computer.
        tailnet.taildrop(device, "soma-runner-galaxy-z-fold8.json", config.toString())
        keys.put(RunnerKey(device, runnerDigest(key), confirmed))
        start()
        synchronized(wake) { wake.notifyAll() }
        // Wake a listener which was sleeping with no pairings. It is safe to start it immediately here.
        runCatching { server?.close() }
    }
    private fun caller(token: String?, address: String, local: Set<String>): RunnerPeer? {
        val result = runnerCallerDecision(token, address, local, keys.keys(), tailnet::lookup, System.currentTimeMillis(), System::currentTimeMillis)
        val key = result.key ?: return null
        if (!keys.confirm(key, result.cache)) return null
        return result.peer
    }
    private fun localAddresses(): Set<String>? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()
    }.getOrNull()
    /** True transfers ownership to a separate stream worker; ordinary requests never wait behind streams. */
    private fun serve(socket: Socket): Boolean {
        socket.soTimeout = 10_000
        try {
            val request = readRunnerRequest(socket)
            val address = socket.inetAddress.hostAddress.orEmpty()
            val local = localAddresses()?.plus(socket.localAddress.hostAddress.orEmpty()) ?: emptySet()
            val peer = if (local.isEmpty()) null else caller(request.token, address, local)
            val response = protocol.handle(request.method, request.path, request.body, peer != null, peer?.device.orEmpty())
            val stream = peer != null && request.method == "GET" && request.path.substringBefore('?').endsWith("/stream") && response.code == 200
            if (!stream) { writeRunnerResponse(socket, response); return false }
            try {
                streams.execute { socket.use { runCatching { serveStream(it, request, address, local) } } }
                return true
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                writeRunnerResponse(socket, RunnerResponse(503, JSONObject().put("ok", false).put("error", "stream capacity reached")))
            }
        } catch (_: Exception) {
            // Do not log request bodies, authorization headers, keys or question answers.
            runCatching { writeRunnerResponse(socket, RunnerResponse(400, JSONObject().put("ok", false).put("error", "request failed"))) }
        }
        return false
    }
    private fun serveStream(socket: Socket, request: RunnerRequest, address: String, local: Set<String>) {
        val out = socket.getOutputStream()
        writeRunnerStreamHeaders(out)
        var cursor = request.path.substringAfter("after=", "0").toLongOrNull() ?: 0L
        val path = request.path.substringBefore('?')
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < until && caller(request.token, address, local) != null) {
            val page = protocol.handle("GET", "$path?after=$cursor", "", true)
            if (page.code != 200) break
            val events = page.body.optJSONArray("events") ?: JSONArray()
            cursor = writeRunnerStreamPage(out, events, cursor)
            Thread.sleep(1000)
        }
    }
    @Suppress("DEPRECATION")
    private fun ownAddress(): String? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val addresses = connectivity.allNetworks.filter {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .mapNotNull { it.address.hostAddress }
        val result = runnerBindIdentity(addresses, keys.ownIdentity(), tailnet::lookup, System.currentTimeMillis(), System::currentTimeMillis)
        keys.ownIdentity(result.cache)
        result.address
    }.getOrNull()
}

class RunnerRequest(val method: String, val path: String, val token: String?, val body: String) {
    override fun toString() = "RunnerRequest($method $path, credentials and body hidden)"
}
fun readRunnerRequest(socket: Socket): RunnerRequest {
    val input = socket.getInputStream()
    val header = StringBuilder()
    while (!header.endsWith("\r\n\r\n")) {
        val byte = input.read()
        require(byte >= 0 && header.length < 8192)
        header.append(byte.toChar())
    }
    val lines = header.toString().split("\r\n")
    val first = lines.first().split(' ')
    require(first.size == 3 && first[0] in setOf("GET", "POST") && first[1].startsWith('/') && first[2] == "HTTP/1.1")
    fun values(name: String) = lines.drop(1).filter { it.substringBefore(':').equals(name, true) }.map { it.substringAfter(':').trim() }
    require(values("Transfer-Encoding").isEmpty())
    val auth = values("Authorization"); require(auth.size <= 1)
    val lengths = values("Content-Length"); require(lengths.size <= 1)
    val size = if (lengths.isEmpty()) 0 else lengths.single().toInt()
    require(size in 0..262_144)
    val bytes = ByteArray(size)
    var read = 0
    while (read < size) { val n = input.read(bytes, read, size - read); require(n > 0); read += n }
    return RunnerRequest(first[0], first[1], auth.singleOrNull()?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer "), bytes.toString(Charsets.UTF_8))
}
fun writeRunnerResponse(socket: Socket, response: RunnerResponse) {
    val data = response.body.toString().toByteArray(Charsets.UTF_8)
    socket.getOutputStream().apply {
        write("HTTP/1.1 ${response.code} Response\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
        write(data); flush()
    }
}
