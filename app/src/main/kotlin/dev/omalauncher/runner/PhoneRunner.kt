package dev.omalauncher.runner

import android.content.Context
import java.net.InetAddress
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
    fun taildrop(device: String, filename: String, content: String)
}

/** No raw key is persisted; unpair takes effect on the next request, including a stream heartbeat. */
class PhoneRunnerKeys(context: Context) {
    private val prefs = context.getSharedPreferences("phone-runner-devices", Context.MODE_PRIVATE)
    fun keys(): List<RunnerKey> = prefs.all.mapNotNull { (device, digest) ->
        (digest as? String)?.takeIf { Regex("[0-9a-f]{64}").matches(it) }?.let { RunnerKey(device, it) }
    }
    @Synchronized fun put(device: String, digest: String) {
        check(prefs.edit().putString(device, digest).commit()) { "Could not save pairing" }
    }
    @Synchronized fun remove(device: String) {
        check(prefs.edit().remove(device).commit()) { "Could not remove pairing" }
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
        require(device in tailnet.devices()) { "That computer is not one of your tailnet devices" }
        val address = ownAddress() ?: error("Phone is not on Tailscale")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val config = JSONObject().put("url", "http://$address:8478").put("key", key).put("name", "Fold").put("runner", "galaxy-z-fold8")
        // Retain the old pairing until the transfer succeeds; a failed re-pair must not strand its computer.
        tailnet.taildrop(device, "soma-runner-galaxy-z-fold8.json", config.toString())
        keys.put(device, runnerDigest(key))
        start()
        synchronized(wake) { wake.notifyAll() }
        // Wake a listener which was sleeping with no pairings. It is safe to start it immediately here.
        runCatching { server?.close() }
    }
    private fun caller(token: String?, address: String): RunnerPeer? {
        val entries = keys.keys()
        if (!tailscaleAddress(address) || token.isNullOrBlank() || token.length > 512 || entries.none {
                java.security.MessageDigest.isEqual(runnerDigest(token).toByteArray(), it.digest.toByteArray())
            }) return null
        val peer = runCatching { tailnet.whois(address) }.getOrNull()
        return peer?.takeIf { runnerAuthorized(token, it, keys.keys()) }
    }
    /** True transfers ownership to a separate stream worker; ordinary requests never wait behind streams. */
    private fun serve(socket: Socket): Boolean {
        socket.soTimeout = 10_000
        try {
            val request = readRunnerRequest(socket)
            val address = socket.inetAddress.hostAddress.orEmpty()
            val peer = caller(request.token, address)
            val response = protocol.handle(request.method, request.path, request.body, peer != null, peer?.device.orEmpty())
            val stream = peer != null && request.method == "GET" && request.path.substringBefore('?').endsWith("/stream") && response.code == 200
            if (!stream) { writeRunnerResponse(socket, response); return false }
            try {
                streams.execute { socket.use { runCatching { serveStream(it, request, address) } } }
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
    private fun serveStream(socket: Socket, request: RunnerRequest, address: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        var cursor = request.path.substringAfter("after=", "0").toLongOrNull() ?: 0L
        val path = request.path.substringBefore('?')
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < until && caller(request.token, address) != null) {
            val page = protocol.handle("GET", "$path?after=$cursor", "", true)
            if (page.code != 200) break
            val events = page.body.optJSONArray("events") ?: JSONArray()
            for (n in 0 until events.length()) {
                val event = events.getJSONObject(n)
                cursor = event.getLong("id")
                out.write("id: $cursor\nevent: ${event.optString("type")}\ndata: $event\n\n".toByteArray(Charsets.UTF_8))
            }
            out.write(": keepalive\n\n".toByteArray()); out.flush()
            Thread.sleep(1000)
        }
    }
    companion object {
        fun ownAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .mapNotNull(InetAddress::getHostAddress).firstOrNull { tailscaleAddress(it) && ':' !in it }
        }.getOrNull()
    }
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
