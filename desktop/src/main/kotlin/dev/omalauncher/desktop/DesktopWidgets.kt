package dev.omalauncher.desktop

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WidgetTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/*
 * What a widget reaches on a computer (Soma Canvas slices 7a and 7b): the web over HTTP, as on the phone, nothing of
 * Android's, and this machine's own shell for its commands. A widget never gets these bare: [gated] puts the run-time gate
 * in front of every command, so one he hasn't approved on this machine starts no process at all.
 */
class DesktopWidgetServices private constructor(
    /** An HTTP GET; a test gives canned answers. */
    private val get: (url: String, headers: Map<String, String>, timeoutSeconds: Int) -> ShellResult,
    /** The folder commands run in: one of their own, never Soma's or his home. */
    private val workDir: File,
    /** A web file's bytes, or null when it couldn't be had; a test gives canned ones. */
    private val download: (url: String) -> ByteArray?,
    /** Web images, sounds and fonts on disk, by the SHA-256 of their URL. */
    private val fileCache: File,
) : Services {
    override suspend fun cmd(command: String, timeoutSeconds: Int) = withContext(Dispatchers.IO) { shell(command, timeoutSeconds) }

    /** GETs only: a widget's post(...) sends nothing from here, as Soma desktop takes no widget side effects of that kind yet. */
    override suspend fun http(url: String, headers: Map<String, String>, timeoutSeconds: Int, body: ByteArray?) =
        if (body != null) ShellResult("", "post(...) isn't supported on Soma desktop", 1) else withContext(Dispatchers.IO) { get(url, headers, timeoutSeconds) }

    /** Never retried: a side effect runs once. */
    override fun run(command: String, done: (ShellResult?) -> Unit) {
        kotlin.concurrent.thread(name = "widget-run", isDaemon = true) { done(runCatching { shell(command, 30) }.getOrNull()) }
    }

    /**
     * A long-running command's lines, until it ends or the widget stops listening. Stopping ends the process at once, even
     * one that prints nothing (round-1 review: a quiet one outlived its widget, its reader blocked for good).
     */
    override suspend fun stream(command: String, onLine: (String) -> Unit) = coroutineScope {
        val p = process(command, mergeErrors = true)
        val ender = launch { try { awaitCancellation() } finally { end(p) } }
        try {
            withContext(Dispatchers.IO) { p.inputStream.bufferedReader().useLines { lines -> for (line in lines) { ensureActive(); onLine(line) } } }
        } finally {
            ender.cancel()
            end(p)
        }
    }

    /**
     * A command as a widget runs it: /bin/sh in the widgets' own folder, with a small environment of its own, never Soma's
     * (which can hold a canvas token or anything Soma was started with). A command that starts something in the background
     * of the background (a double fork) can outlive its timeout: only the process tree that can still be seen is ended.
     */
    private fun process(command: String, mergeErrors: Boolean = false): Process = ProcessBuilder("/bin/sh", "-c", command).directory(workDir.apply { mkdirs() }).apply {
        val home = System.getProperty("user.home")
        environment().clear()
        environment().putAll(mapOf("HOME" to home, "USER" to System.getProperty("user.name"), "LANG" to (System.getenv("LANG") ?: "en_US.UTF-8"),
            "PATH" to "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "TMPDIR" to (System.getenv("TMPDIR") ?: "/tmp"), "SHELL" to "/bin/sh"))
        redirectErrorStream(mergeErrors)
    }.start()

    private fun end(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    private fun shell(command: String, timeoutSeconds: Int): ShellResult {
        val p = process(command)
        val out = StringBuilder(); val err = StringBuilder()
        val readers = listOf(kotlin.concurrent.thread(isDaemon = true) { out.append(p.inputStream.bufferedReader().readText()) },
            kotlin.concurrent.thread(isDaemon = true) { err.append(p.errorStream.bufferedReader().readText()) })
        if (!p.waitFor(timeoutSeconds.toLong().coerceAtLeast(1), java.util.concurrent.TimeUnit.SECONDS)) {
            end(p)
            return ShellResult(out.toString(), err.toString(), 124)
        }
        readers.forEach { it.join(1000) }
        return ShellResult(out.toString(), err.toString(), p.exitValue())
    }

    /** One download per URL at a time: 48 thumbnails from one file ask for each image once. */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /** When a URL last failed, in nanoseconds, so a widget offline doesn't ask again on every update. */
    private val fileFailed = ConcurrentHashMap<String, Long>()

    /**
     * A web image, sound or font as the phone keeps one (WidgetServices.remoteFile): https only, a day before it's asked
     * for again, a minute before a failed one is tried again, and an old copy when a new one can't be had.
     */
    override suspend fun remoteFile(url: String): File? {
        if (!url.startsWith("https://")) return null
        return fileLocks.getOrPut(url) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                val file = File(fileCache, sha256(url))
                val kept = file.takeIf { it.isFile }
                if (kept != null && System.currentTimeMillis() - kept.lastModified() < FILE_FRESH_MS) return@withContext kept
                val failed = fileFailed[url]
                if (failed != null && System.nanoTime() - failed < FILE_RETRY_NS) return@withContext kept
                val bytes = runCatching { download(url) }.getOrNull()
                if (bytes == null) {
                    fileFailed[url] = System.nanoTime()
                    return@withContext kept
                }
                fileFailed.remove(url)
                runCatching {
                    fileCache.mkdirs()
                    val tmp = File.createTempFile("download", ".part", fileCache)
                    try {
                        tmp.writeBytes(bytes)
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally {
                        tmp.delete()
                    }
                    pruneFiles()
                    file
                }.getOrElse { kept }
            }
        }
    }

    /** Deletes the longest-kept files until the cache is under [FILE_CACHE_BYTES]. */
    private fun pruneFiles() {
        val files = fileCache.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= FILE_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** The phone's own data (battery, media, the device): there is none here. */
    override fun android(name: String): Value = Null

    /** A tap's `run(…)` runs in this machine's shell (it reaches here only once approved); nothing else of a phone's happens here. */
    override fun act(name: String, args: List<Value>, done: () -> Unit) {
        val command = args.firstOrNull()?.takeIf { it != Null }?.toString()
        if (name == "run" && command != null) run(command) { done() } else done()
    }

    /** No phone terminal and no Android or Linux app to start here: those taps are never asked about. */
    override fun canAct(name: String) = name != "terminal" && name != "app"

    /** No guest here: every guest path is the same file that never exists, so an Image from one shows nothing, whatever it says. */
    override fun guestFile(path: String) = NO_GUEST_FILE

    override fun fileUpdates(path: String): Flow<String> = flowOf("")

    override fun terminal(command: String?, fontSize: Double?): WidgetTerminal = error("A Terminal node doesn't draw on a computer.")

    companion object {
        private val NO_GUEST_FILE = File("/nonexistent/soma-widget-guest/none")

        /** As much of a response as a widget gets: the phone's cap, so one answer can't fill memory here either. */
        private const val MAX_HTTP_BYTES = 1 shl 20

        /** The most of one web file kept, as on the phone. */
        private const val MAX_FILE_BYTES = 8 shl 20
        private const val FILE_FRESH_MS = 24 * 3600_000L
        private const val FILE_RETRY_NS = 60_000_000_000L
        private const val FILE_CACHE_BYTES = 64L shl 20

        /** What [widget] runs with on this machine: HTTP, web files kept in [files], and this machine's shell behind [gate]. */
        fun gated(
            gate: dev.omalauncher.widgets.runtime.CommandGate,
            widget: String,
            workDir: File = File(SomaHome.dir, "widget-run"),
            get: (url: String, headers: Map<String, String>, timeoutSeconds: Int) -> ShellResult = ::httpGet,
            files: File = File(SomaHome.dir, "widget-files"),
            download: (url: String) -> ByteArray? = ::downloadFile,
        ): Services = dev.omalauncher.widgets.runtime.GatedServices(DesktopWidgetServices(get, workDir, download, files), gate, widget)

        /**
         * A web file's bytes as the phone downloads one: https only, redirects followed but never to plain http, 8 seconds
         * to connect and 20 for the answer, and null for anything but a 2xx or a body over [MAX_FILE_BYTES].
         */
        fun downloadFile(url: String): ByteArray? {
            val uri = URI(url)
            if (uri.scheme != "https") return null
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().setHeader("User-Agent", "omalauncher-widgets").build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            return response.body().use { body ->
                if (response.statusCode() !in 200..299 || response.uri().scheme != "https") return null
                // The timeout above ends at the headers: a body that stalls is closed at the same 20 seconds, as the phone's read times out.
                val stop = closer.schedule({ body.close() }, 20, TimeUnit.SECONDS)
                try {
                    body.readNBytes(MAX_FILE_BYTES + 1).takeIf { it.size <= MAX_FILE_BYTES }
                } finally {
                    stop.cancel(false)
                }
            }
        }

        private val closer = Executors.newSingleThreadScheduledExecutor { Thread(it, "widget-download-timeout").apply { isDaemon = true } }

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private val client: HttpClient by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(8)).build() }

        /**
         * An HTTP GET as the phone makes one (WidgetServices.http): the same User-Agent, at most [MAX_HTTP_BYTES] of body, and
         * a non-2xx status with its reason phrase as the error; -1 and why when it couldn't be made. Only http and https.
         */
        fun httpGet(url: String, headers: Map<String, String>, timeoutSeconds: Int): ShellResult = runCatching {
            val uri = URI(url)
            require(uri.scheme == "https" || uri.scheme == "http") { "only http and https" }
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds.toLong().coerceAtLeast(1))).GET()
                // The phone's User-Agent unless the widget sets its own, which then replaces it, as on the phone.
                .setHeader("User-Agent", "omalauncher-widgets")
                .apply { headers.forEach { (k, v) -> setHeader(k, v) } }.build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val body = response.body().use { String(it.readNBytes(MAX_HTTP_BYTES), Charsets.UTF_8) }.trimEnd()
            val code = response.statusCode()
            ShellResult(body, if (code in 200..299) "" else REASONS[code] ?: "HTTP $code", code)
        }.getOrElse { ShellResult("", it.message ?: it.javaClass.simpleName, -1) }

        /** HTTP/2 has no reason phrase; these are the ones a widget most often meets, as HTTP/1.1 says them. */
        private val REASONS = mapOf(400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 429 to "Too Many Requests", 500 to "Internal Server Error", 502 to "Bad Gateway", 503 to "Service Unavailable")
    }
}
