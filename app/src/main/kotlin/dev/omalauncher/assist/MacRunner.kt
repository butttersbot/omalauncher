package dev.omalauncher.assist

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/*
 * The phone's side of oma-runner, the service on the Mac Pro that runs Oma's builders (docs/MAC-RUNNER-SPEC.md,
 * "As built on the Mac"). Where the runner is, every request it takes, how each answer is read and when the
 * phone does the work itself are plain functions, unit tested; MacRunnerClient at the end is the only part that
 * touches the network. What a thread says while it works is in MacRunnerEvents.kt.
 */

/** The file in the guest's ~/.config/omalauncher that says where the runner is: `{"url":"http://...:8478","key":"..."}`. */
const val MAC_RUNNER_CONFIG = "mac-runner.json"

/**
 * Where the runner is and the key it asks for. Not a data class, and [toString] leaves the key out, so that it
 * cannot reach a log, an error or the chat by being printed.
 */
class MacRunnerConfig(val url: String, val key: String) {
    override fun equals(other: Any?) = other is MacRunnerConfig && other.url == url && other.key == key

    override fun hashCode() = 31 * url.hashCode() + key.hashCode()

    override fun toString() = "MacRunnerConfig(url=$url, key=hidden)"
}

/** The runner's address without a path, or null for anything that is not a plain http(s) address of a host. */
private fun macRunnerBase(given: String): String? {
    val uri = runCatching { URI(given.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    if (!uri.path.isNullOrEmpty() && uri.path.trim('/').isNotEmpty()) return null
    return "$scheme://${uri.host}" + (if (uri.port > 0) ":${uri.port}" else "")
}

/**
 * The config from the file's text. Null means no Mac is set up, which is how a missing, empty or broken file is
 * read: not an error. A key with a space or a line break in it is refused, since it goes into a header.
 */
fun parseMacRunnerConfig(text: String?): MacRunnerConfig? {
    if (text.isNullOrBlank()) return null
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val url = macRunnerBase(json.macText("url")) ?: return null
    val key = json.macText("key").trim()
    if (key.isEmpty() || key.any { it <= ' ' || it.code >= 127 }) return null
    return MacRunnerConfig(url, key)
}

/** Reads the config from the guest's launcher folder (Proot.launcherConfig). The file is tiny; a large one is not it. */
fun readMacRunnerConfig(launcherConfig: File): MacRunnerConfig? {
    val file = File(launcherConfig, MAC_RUNNER_CONFIG)
    return parseMacRunnerConfig(runCatching { if (file.isFile && file.length() <= 16_384) file.readText() else null }.getOrNull())
}

/** One call to the runner: [path] starts with a slash and carries any query; [body] is JSON text. */
data class MacRequest(val method: String, val path: String, val body: String? = null)

/** Every call the runner takes. Ids come from the runner's own answers, but are still escaped into the path. */
object MacRequests {
    private fun part(id: String) = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun from(after: Long) = "?after=" + after.coerceAtLeast(0)

    fun health() = MacRequest("GET", "/health")

    fun threads() = MacRequest("GET", "/threads")

    fun thread(threadId: String) = MacRequest("GET", "/threads/${part(threadId)}")

    /** A new thread called [name] in [project], running the agent [kind] (`claude`, `codex` or `cursor`); none named is the runner's default, Claude. */
    fun makeThread(name: String, project: String, kind: String = "") =
        MacRequest("POST", "/threads", JSONObject().put("name", name).put("project", project).apply { if (kind.isNotEmpty()) put("kind", kind) }.toString())

    /** The owner's words exactly as he said them: nothing is trimmed or added here. */
    fun sendMessage(threadId: String, text: String) = MacRequest("POST", "/threads/${part(threadId)}/messages", JSONObject().put("text", text).toString())

    fun events(threadId: String, after: Long) = MacRequest("GET", "/threads/${part(threadId)}/events${from(after)}")

    fun stream(threadId: String, after: Long) = MacRequest("GET", "/threads/${part(threadId)}/stream${from(after)}")

    fun answerApproval(threadId: String, approval: String, allow: Boolean, nonce: String) = MacRequest(
        "POST",
        "/threads/${part(threadId)}/approvals/${part(approval)}",
        JSONObject().put("decision", if (allow) MacDecision.Allow.word else MacDecision.Deny.word).put("nonce", nonce).toString(),
    )

    /** The runner wants nothing in the body; an empty object is sent because a POST with none is not sent alike everywhere. */
    fun stop(threadId: String) = MacRequest("POST", "/threads/${part(threadId)}/stop", "{}")
}

/** What came back: the HTTP status and the body as text. */
data class MacReply(val code: Int, val body: String)

/** An answer from the runner, read. Nothing in here ever holds the key. */
sealed interface MacAnswer<out T> {
    data class Ok<T>(val value: T) : MacAnswer<T>

    /** 401: the key is missing or wrong. */
    data object Unauthorized : MacAnswer<Nothing>

    /** 403: a project outside the allowed [roots], or an approval answered with the wrong nonce (it goes on waiting). */
    data class Refused(val error: String, val roots: List<String>) : MacAnswer<Nothing>

    /** 404: no such thread, or an approval already answered or gone. */
    data object NotFound : MacAnswer<Nothing>

    /** Any other status, or a 200 that is not the runner's. */
    data class Failed(val code: Int, val error: String) : MacAnswer<Nothing>

    /** No answer at all. */
    data object Unreachable : MacAnswer<Nothing>
}

const val MAC_RUNNER_NAME = "oma-runner"

/** What `/health` says. [kinds] are the agents installed on the Mac, as the runner names them; empty from a runner that does not say. */
data class MacHealth(val threads: Int, val working: Int, val waiting: Int, val kinds: List<String> = emptyList())

/** Where a thread's builder runs, set when the thread is made. [word] is the runner's. */
enum class ThreadHome(val word: String) { Mac("mac"), Phone("phone") }

/**
 * A thread as the runner lists it. [summary] is the first line of the owner's last message, [tail] the last 300
 * characters the agent said, [lastWorked] Unix seconds or null before its first task, [waitingTurns] the messages
 * waiting their turn, [lastEvent] the id of its newest event, [pending] the approvals waiting now, and
 * [background] what the agent left running on the Mac after its answer, in its own words; empty when nothing is.
 */
data class MacThread(
    val id: String,
    val name: String,
    val project: String,
    val home: ThreadHome,
    val kind: String,
    val summary: String,
    val status: MacThreadStatus,
    val lastWorked: Long?,
    val tail: String,
    val waitingTurns: Int,
    val lastEvent: Long,
    val pending: List<MacApproval>,
    val background: List<String> = emptyList(),
)

/** What became of the owner's words: taken at once, or waiting behind [Queued.position] - 1 others. */
sealed interface MacTaken {
    data object Now : MacTaken

    data class Queued(val position: Int) : MacTaken
}

/** The answer to `events?after=N`: the thread's status and the events after N. */
data class MacEventsPage(val status: MacThreadStatus, val events: List<MacEvent>)

private fun JSONObject.macCount(key: String): Int = (macWhole(key) ?: 0L).toInt()

/** A thread from its JSON, with what is missing left empty; null without an id, since nothing could be asked of it. */
internal fun macThread(json: JSONObject): MacThread? {
    val id = json.macText("id")
    if (id.isBlank()) return null
    val home = json.macText("home")
    return MacThread(
        id = id,
        name = json.macText("name"),
        project = json.macText("project"),
        home = ThreadHome.entries.firstOrNull { it.word == home } ?: ThreadHome.Mac,
        kind = json.macText("kind"),
        summary = json.macText("summary"),
        status = MacThreadStatus.of(json.macText("status")),
        lastWorked = json.macWhole("last_worked"),
        tail = json.macText("tail"),
        waitingTurns = json.macCount("waiting_turns"),
        lastEvent = json.macWhole("last_event") ?: 0L,
        pending = json.optJSONArray("pending").macObjects().mapNotNull { macApproval(it) },
        background = json.optJSONArray("background").let { list -> (0 until (list?.length() ?: 0)).mapNotNull { (list?.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) } },
    )
}

/** The statuses every call shares; [read] takes what a 200 with `ok` carries and gives null when it is not that. */
private fun <T : Any> macAnswer(reply: MacReply?, read: (JSONObject) -> T?): MacAnswer<T> {
    if (reply == null) return MacAnswer.Unreachable
    val json = runCatching { JSONObject(reply.body) }.getOrNull()
    val error = json?.macText("error").orEmpty()
    return when (reply.code) {
        200 -> {
            val value = if (json != null && json.optBoolean("ok")) read(json) else null
            if (value == null) MacAnswer.Failed(200, "not an answer from $MAC_RUNNER_NAME") else MacAnswer.Ok(value)
        }
        401 -> MacAnswer.Unauthorized
        403 -> {
            val roots = json?.optJSONArray("roots")
            MacAnswer.Refused(error, if (roots == null) emptyList() else (0 until roots.length()).map { roots.optString(it) })
        }
        404 -> MacAnswer.NotFound
        else -> MacAnswer.Failed(reply.code, error)
    }
}

/** `GET /health`. Something else answering on the runner's port is not the runner. */
fun parseMacHealth(reply: MacReply?): MacAnswer<MacHealth> = macAnswer(reply) { json ->
    if (json.macText("runner") != MAC_RUNNER_NAME) null
    else {
        val kinds = json.optJSONArray("kinds")
        MacHealth(json.macCount("threads"), json.macCount("working"), json.macCount("waiting"), (0 until (kinds?.length() ?: 0)).mapNotNull { (kinds?.opt(it) as? String)?.takeIf(String::isNotBlank) })
    }
}

/** `GET /threads`. */
fun parseMacThreads(reply: MacReply?): MacAnswer<List<MacThread>> = macAnswer(reply) { json ->
    json.optJSONArray("threads")?.macObjects()?.mapNotNull { macThread(it) }
}

/** `GET /threads/ID` and `POST /threads`. */
fun parseMacThread(reply: MacReply?): MacAnswer<MacThread> = macAnswer(reply) { json ->
    json.optJSONObject("thread")?.let { macThread(it) }
}

/** `POST /threads/ID/messages`. */
fun parseMacTaken(reply: MacReply?): MacAnswer<MacTaken> = macAnswer(reply) { json ->
    when (json.macText("taken")) {
        "now" -> MacTaken.Now
        "queued" -> MacTaken.Queued(json.macCount("position"))
        else -> null
    }
}

/** `GET /threads/ID/events?after=N`. Entries that are not events are left out. */
fun parseMacEvents(reply: MacReply?): MacAnswer<MacEventsPage> = macAnswer(reply) { json ->
    json.optJSONArray("events")?.let { list -> MacEventsPage(MacThreadStatus.of(json.macText("status")), list.macObjects().mapNotNull { macEvent(it) }) }
}

/** `POST /threads/ID/approvals/APPROVAL`: the decision the runner took. */
fun parseMacDecision(reply: MacReply?): MacAnswer<MacDecision> = macAnswer(reply) { json ->
    MacDecision.of(json.macText("decision")).takeIf { it == MacDecision.Allow || it == MacDecision.Deny }
}

/** `POST /threads/ID/stop`: whether a task was running to be stopped. */
fun parseMacStopped(reply: MacReply?): MacAnswer<Boolean> = macAnswer(reply) { json ->
    if (json.has("stopped")) json.optBoolean("stopped") else null
}

/** What asking `/health` came to. */
sealed interface MacHealthResult {
    data class Up(val health: MacHealth) : MacHealthResult

    /** No answer inside the limit, or an answer that is not the runner's. */
    data object Unreachable : MacHealthResult

    /** The Mac answered and turned the key down; [code] is 401. */
    data class Refused(val code: Int) : MacHealthResult

    /** No Mac is set up on this phone. */
    data object NotConfigured : MacHealthResult
}

fun macHealthResult(reply: MacReply?): MacHealthResult = when (val answer = parseMacHealth(reply)) {
    is MacAnswer.Ok -> MacHealthResult.Up(answer.value)
    is MacAnswer.Unauthorized -> MacHealthResult.Refused(401)
    else -> MacHealthResult.Unreachable
}

/** Where the owner's words for a thread go. */
enum class MacRoute {
    /** The Mac has the thread. For a task already started there this means: stay with it, and send nothing again. */
    RunOnMac,

    RunOnPhone,

    /** Say the Mac cannot be reached, and keep his words to send when it is back if he says so. */
    TellOwnerUnreachable,

    /** The task is working on the Mac out of reach: show that, start nothing, pick the stream up when the link returns. */
    KeepWaitingForLink,
}

/**
 * The spec's "When the Mac is not there". A task that has started on the Mac is never started again anywhere,
 * whatever else is true. Otherwise a phone thread stays on the phone; a Mac thread goes to the Mac when it answers,
 * to the phone when it does not and the phone has a Git clone of the project, and to the owner when it has none.
 * A Mac that answers but turns the key down goes to the owner as well: working here would hide a setup fault.
 */
fun macRoute(home: ThreadHome, health: MacHealthResult, startedOnMac: Boolean, phoneHasClone: Boolean): MacRoute = when {
    startedOnMac -> if (health is MacHealthResult.Up) MacRoute.RunOnMac else MacRoute.KeepWaitingForLink
    home == ThreadHome.Phone -> MacRoute.RunOnPhone
    health is MacHealthResult.Up -> MacRoute.RunOnMac
    health is MacHealthResult.Refused -> MacRoute.TellOwnerUnreachable
    phoneHasClone -> MacRoute.RunOnPhone
    else -> MacRoute.TellOwnerUnreachable
}

/** The runner of [mac], or one that answers nothing when it is null (its file is gone). */
fun macClient(mac: MacMachine?) = MacRunnerClient(mac?.config, mac?.id ?: MAC_FIRST)

/**
 * The network under all of the above, and nothing more. Every call blocks, so none belongs on the main thread,
 * and none throws: a failure is [MacAnswer.Unreachable], with no message, because a failure's own words can
 * quote the request's headers and the key is in them. [machine] is which Mac [config] is (MacMachines.kt): the
 * thread ids given and returned are the phone's, and are made the runner's own here, on the way out.
 */
class MacRunnerClient(private val config: MacRunnerConfig?, private val machine: String = MAC_FIRST) {
    private fun own(threadId: String) = macRunnerId(threadId)

    private fun ours(answer: MacAnswer<MacThread>): MacAnswer<MacThread> = if (answer is MacAnswer.Ok) MacAnswer.Ok(macOnMachine(machine, answer.value)) else answer

    /**
     * Asked before every hand-over. The limit is on the whole call, not on each step of it, so the wait is on
     * another thread: that one gives up by its own timeouts soon after.
     */
    fun health(): MacHealthResult {
        if (config == null) return MacHealthResult.NotConfigured
        val asked = FutureTask(Callable<MacReply?> { send(MacRequests.health(), HEALTH_LIMIT_MS, HEALTH_LIMIT_MS) })
        Thread(asked, "mac-runner-health").apply { isDaemon = true }.start()
        return macHealthResult(runCatching { asked.get(HEALTH_LIMIT_MS.toLong(), TimeUnit.MILLISECONDS) }.getOrNull())
    }

    fun threads(): MacAnswer<List<MacThread>> = when (val listed = parseMacThreads(send(MacRequests.threads()))) {
        is MacAnswer.Ok -> MacAnswer.Ok(listed.value.map { macOnMachine(machine, it) })
        else -> listed
    }

    fun thread(threadId: String): MacAnswer<MacThread> = ours(parseMacThread(send(MacRequests.thread(own(threadId)))))

    fun makeThread(name: String, project: String, kind: String = ""): MacAnswer<MacThread> = ours(parseMacThread(send(MacRequests.makeThread(name, project, kind))))

    fun sendMessage(threadId: String, text: String): MacAnswer<MacTaken> = parseMacTaken(send(MacRequests.sendMessage(own(threadId), text)))

    fun events(threadId: String, after: Long): MacAnswer<MacEventsPage> = parseMacEvents(send(MacRequests.events(own(threadId), after)))

    fun answerApproval(threadId: String, approval: String, allow: Boolean, nonce: String): MacAnswer<MacDecision> =
        parseMacDecision(send(MacRequests.answerApproval(own(threadId), approval, allow, nonce)))

    fun stop(threadId: String): MacAnswer<Boolean> = parseMacStopped(send(MacRequests.stop(own(threadId))))

    /**
     * Follows a thread from event [after] until the link drops or [shouldStop] says so; see [followMacStream].
     * Three keep-alives missed in a row count as a dropped link.
     */
    fun stream(threadId: String, after: Long, shouldStop: () -> Boolean, onEvent: (MacEvent) -> Unit): MacStreamEnd {
        val dropped = MacStreamEnd(after, MacStreamWhy.Dropped)
        val connection = runCatching { prepare(MacRequests.stream(own(threadId), after), CONNECT_MS, STREAM_SILENCE_MS) }.getOrNull() ?: return dropped
        try {
            val code = runCatching { connection.responseCode }.getOrNull() ?: return dropped
            if (code != 200) return MacStreamEnd(after, MacStreamWhy.Refused, code)
            val lines = runCatching { connection.inputStream.bufferedReader() }.getOrNull() ?: return dropped
            return followMacStream(after, { lines.readLine() }, shouldStop, onEvent)
        } finally {
            connection.disconnect()
        }
    }

    /** Sets a request up without sending it. A redirect is not followed: it would carry the key to wherever it led. */
    private fun prepare(request: MacRequest, connectMs: Int, readMs: Int): HttpURLConnection? {
        val runner = config ?: return null
        val connection = URL(runner.url + request.path).openConnection() as HttpURLConnection
        connection.connectTimeout = connectMs
        connection.readTimeout = readMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.requestMethod = request.method
        connection.setRequestProperty("Authorization", "Bearer " + runner.key)
        if (request.body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
        }
        return connection
    }

    private fun send(request: MacRequest, connectMs: Int = CONNECT_MS, readMs: Int = READ_MS): MacReply? {
        val connection = runCatching { prepare(request, connectMs, readMs) }.getOrNull() ?: return null
        return try {
            val body = request.body
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val answer = if (code < 400) connection.inputStream else connection.errorStream
            MacReply(code, answer?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } catch (problem: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The spec's "about two seconds of silence" before the phone does the work itself. */
        const val HEALTH_LIMIT_MS = 2_000
        const val CONNECT_MS = 5_000
        const val READ_MS = 15_000

        /** The runner writes a keep-alive every fifteen seconds while nothing happens. */
        const val STREAM_SILENCE_MS = 45_000
    }
}
