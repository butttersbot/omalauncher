package dev.omalauncher.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * A stand-in for the Fold's own runner (docs/PHONE-RUNNER-SPEC.md), on the loopback: its sessions Phone A/B/C (builders)
 * and Phone D, the runner's /threads protocol as the spec's table says, and every request written down. Phone B waits on
 * a command approval, Phone C on a question form; "work" keeps Phone A working until stopped. POST /threads and the
 * routes the spec leaves out answer 404; Phone D refuses words (mail only).
 */
class FakePhoneRunner(private val key: String) {
    data class Call(val method: String, val path: String, val body: JSONObject?)

    val calls = java.util.Collections.synchronizedList(ArrayList<Call>())
    private val lock = Any()
    private val threads = linkedMapOf<String, JSONObject>()
    private val events = HashMap<String, MutableList<JSONObject>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = java.util.concurrent.Executors.newCachedThreadPool() }
    val url get() = "http://127.0.0.1:${server.address.port}"

    init {
        val now = System.currentTimeMillis() / 1000
        fun session(id: String, name: String, kind: String, status: String = "idle") =
            JSONObject().put("id", id).put("name", name).put("project", "/data/phone").put("home", "phone").put("kind", kind).put("summary", "")
                .put("status", status).put("last_worked", now).put("tail", "").put("waiting_turns", 0).put("pending", JSONArray()).put("last_event", 0)
        threads["builder-a"] = session("builder-a", "Phone A", "claude")
        threads["builder-b"] = session("builder-b", "Phone B", "codex", "waiting")
        threads["builder-c"] = session("builder-c", "Phone C", "claude", "waiting")
        threads["phone-d"] = session("phone-d", "Phone D", "codex")
        threads.keys.forEach { events[it] = ArrayList() }
        emit("builder-a", JSONObject().put("type", "message").put("text", "set up the build"))
        emit("builder-a", JSONObject().put("type", "result").put("text", "Phone A is ready.").put("kind", "claude"))
        val command = JSONObject().put("approval", "tk-b1").put("nonce", "tk-b1").put("tool", "Bash").put("input", JSONObject().put("command", "./gradlew assemble")).put("explanation", "Run ./gradlew assemble")
        emit("builder-b", JSONObject().put("type", "message").put("text", "build the app"))
        pending("builder-b", command)
        val form = JSONObject().put("questions", JSONArray().put(JSONObject().put("question", "Which look?").put("header", "Look")
            .put("options", JSONArray().put(JSONObject().put("label", "City Pop")).put(JSONObject().put("label", "Console")))))
        emit("builder-c", JSONObject().put("type", "message").put("text", "restyle the home screen"))
        pending("builder-c", JSONObject().put("approval", "tk-c1").put("nonce", "tk-c1").put("tool", "AskUserQuestion").put("input", form).put("explanation", "Questions"))
        server.createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { answer(exchange, 500, JSONObject().put("ok", false)) } }
        server.start()
    }

    fun close() = server.stop(0)

    private fun emit(id: String, event: JSONObject) = synchronized(lock) {
        val list = events.getValue(id)
        event.put("id", list.size + 1L).put("time", System.currentTimeMillis() / 1000)
        list += event
        threads.getValue(id).put("last_event", list.size.toLong()).put("last_worked", System.currentTimeMillis() / 1000)
    }

    private fun pending(id: String, approval: JSONObject) = synchronized(lock) {
        threads.getValue(id).getJSONArray("pending").put(approval)
        emit(id, JSONObject(approval.toString()).put("type", "approval"))
    }

    private fun status(id: String, status: String) = synchronized(lock) {
        threads.getValue(id).put("status", status)
        emit(id, JSONObject().put("type", "status").put("status", status))
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path.trim('/').split('/').filter { it.isNotEmpty() }
        val body = if (ex.requestMethod == "POST") ex.requestBody.readBytes().decodeToString().let { runCatching { JSONObject(it) }.getOrNull() } else null
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer $key") return answer(ex, 401, JSONObject().put("ok", false).put("error", "no"))
        calls += Call(ex.requestMethod, ex.requestURI.toString(), body)
        val get = ex.requestMethod == "GET"
        val thread = path.getOrNull(1)?.let { id -> synchronized(lock) { threads[id] } }
        when {
            get && path == listOf("health") -> synchronized(lock) {
                answer(ex, 200, JSONObject().put("ok", true).put("runner", "oma-phone").put("threads", threads.size)
                    .put("working", threads.values.count { it.getString("status") == "working" }).put("waiting", threads.values.count { it.getString("status") == "waiting" })
                    .put("kinds", JSONArray(listOf("claude", "codex"))))
            }
            get && path == listOf("threads") -> synchronized(lock) { answer(ex, 200, JSONObject().put("ok", true).put("threads", JSONArray(threads.values.map { JSONObject(it.toString()) }))) }
            thread == null || path.firstOrNull() != "threads" -> answer(ex, 404, JSONObject().put("ok", false).put("error", "not here"))
            get && path.size == 2 -> synchronized(lock) { answer(ex, 200, JSONObject().put("ok", true).put("thread", JSONObject(thread.toString()))) }
            get && path.size == 3 && path[2] == "events" -> {
                val after = Regex("after=(\\d+)").find(ex.requestURI.query.orEmpty())?.groupValues?.get(1)?.toLong() ?: 0L
                synchronized(lock) {
                    answer(ex, 200, JSONObject().put("ok", true).put("status", thread.getString("status"))
                        .put("events", JSONArray(events.getValue(path[1]).filter { it.getLong("id") > after }.map { JSONObject(it.toString()) })))
                }
            }
            get && path.size == 3 && path[2] == "stream" -> stream(ex, path[1], Regex("after=(\\d+)").find(ex.requestURI.query.orEmpty())?.groupValues?.get(1)?.toLong() ?: 0L)
            ex.requestMethod == "POST" && path.size == 3 && path[2] == "messages" -> {
                val id = path[1]
                if (id == "phone-d") return answer(ex, 403, JSONObject().put("ok", false).put("error", "Phone D takes agent mail only"))
                val text = body?.optString("text").orEmpty()
                emit(id, JSONObject().put("type", "message").put("text", text))
                if ("work" in text) status(id, "working")
                else {
                    emit(id, JSONObject().put("type", "result").put("text", "heard: $text").put("kind", thread.getString("kind")))
                    status(id, "idle")
                }
                answer(ex, 200, JSONObject().put("ok", true).put("taken", "now"))
            }
            ex.requestMethod == "POST" && path.size == 4 && path[2] == "approvals" -> {
                val id = path[1]
                val waiting = synchronized(lock) { thread.getJSONArray("pending").let { list -> (0 until list.length()).map { list.getJSONObject(it) } } }
                val asked = waiting.firstOrNull { it.getString("approval") == path[3] } ?: return answer(ex, 404, JSONObject().put("ok", false).put("error", "already answered"))
                if (body?.optString("nonce") != asked.getString("nonce")) return answer(ex, 403, JSONObject().put("ok", false).put("error", "wrong nonce"))
                val decision = body.optString("decision")
                synchronized(lock) { thread.put("pending", JSONArray(waiting.filter { it !== asked })) }
                emit(id, JSONObject().put("type", "approval_result").put("approval", path[3]).put("decision", decision).apply { body.optJSONObject("answers")?.let { put("answers", it) } })
                status(id, "working")
                answer(ex, 200, JSONObject().put("ok", true).put("decision", decision))
            }
            ex.requestMethod == "POST" && path.size == 3 && path[2] == "stop" -> {
                emit(path[1], JSONObject().put("type", "stopped").put("dropped", 0))
                status(path[1], "idle")
                answer(ex, 200, JSONObject().put("ok", true).put("stopped", true))
            }
            else -> answer(ex, 404, JSONObject().put("ok", false).put("error", "not here"))
        }
    }

    /** Server-sent events from [after] on, as the runner streams them, with its keep-alive; held while the test runs. */
    private fun stream(ex: HttpExchange, id: String, after: Long) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        var sent = after
        runCatching {
            ex.responseBody.use { out ->
                repeat(600) {
                    val fresh = synchronized(lock) { events.getValue(id).filter { it.getLong("id") > sent }.map { JSONObject(it.toString()) } }
                    for (event in fresh) {
                        out.write("id: ${event.getLong("id")}\nevent: ${event.getString("type")}\ndata: $event\n\n".toByteArray())
                        sent = event.getLong("id")
                    }
                    if (fresh.isEmpty() && it % 20 == 0) out.write(": keep-alive\n\n".toByteArray())
                    out.flush()
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun answer(ex: HttpExchange, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
