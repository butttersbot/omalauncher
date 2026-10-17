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
) : Services {
    override suspend fun cmd(command: String, timeoutSeconds: Int) = withContext(Dispatchers.IO) { shell(command, timeoutSeconds) }

    override suspend fun http(url: String, headers: Map<String, String>, timeoutSeconds: Int) = withContext(Dispatchers.IO) { get(url, headers, timeoutSeconds) }

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

    /** The phone's own data (battery, media, the device): there is none here. */
    override fun android(name: String): Value = Null

    override fun act(name: String, args: List<Value>, done: () -> Unit) = done()

    /** No guest here: every guest path is the same file that never exists, so an Image from one shows nothing, whatever it says. */
    override fun guestFile(path: String) = NO_GUEST_FILE

    override fun fileUpdates(path: String): Flow<String> = flowOf("")

    override fun terminal(command: String?, fontSize: Double?): WidgetTerminal = error("A Terminal node doesn't draw on a computer.")

    companion object {
        private val NO_GUEST_FILE = File("/nonexistent/soma-widget-guest/none")

        /** As much of a response as a widget gets: the phone's cap, so one answer can't fill memory here either. */
        private const val MAX_HTTP_BYTES = 1 shl 20

        /** What [widget] runs with on this machine: HTTP, and this machine's shell behind [gate]. */
        fun gated(
            gate: dev.omalauncher.widgets.runtime.CommandGate,
            widget: String,
            workDir: File = File(SomaHome.dir, "widget-run"),
            get: (url: String, headers: Map<String, String>, timeoutSeconds: Int) -> ShellResult = ::httpGet,
        ): Services = dev.omalauncher.widgets.runtime.GatedServices(DesktopWidgetServices(get, workDir), gate, widget)

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
