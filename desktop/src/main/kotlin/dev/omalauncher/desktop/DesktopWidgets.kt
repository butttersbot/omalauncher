package dev.omalauncher.desktop

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WidgetTerminal
import kotlinx.coroutines.Dispatchers
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
 * What a widget reaches on a computer (Soma Canvas slice 7a): the web over HTTP, as on the phone, and nothing of
 * Android's. Its commands are another matter: on every machine they run only once he has approved them there, which is
 * slice 7b's gate; until then a command here doesn't run, and says so in the widget's place, as a failed command does.
 */
class DesktopWidgetServices(
    /** An HTTP GET; a test gives canned answers. */
    private val get: (url: String, headers: Map<String, String>, timeoutSeconds: Int) -> ShellResult = ::httpGet,
) : Services {
    override suspend fun cmd(command: String, timeoutSeconds: Int) = NOT_YET

    override suspend fun http(url: String, headers: Map<String, String>, timeoutSeconds: Int) = withContext(Dispatchers.IO) { get(url, headers, timeoutSeconds) }

    override fun run(command: String, done: (ShellResult?) -> Unit) = done(null)

    override suspend fun stream(command: String, onLine: (String) -> Unit) {}

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

        private val NOT_YET = ShellResult("", "commands run here once approved on this machine (slice 7b)", -1)

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
