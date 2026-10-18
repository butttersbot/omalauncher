package dev.omalauncher.desktop

import com.sun.net.httpserver.HttpExchange
import dev.omalauncher.canvas.CanvasAsk
import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * The Soma connector for Grok (Soma Canvas slice 9c, PRD §9): on the public door beside the car's page, a stateless MCP
 * server and the OAuth 2.1 authorization it needs, so the car's Grok can ask "what needs me" and "put my watchlist on the
 * car screen". Grok is a steerable caller (it reads his mail and the web), so its reach is small: a summary out
 * (counts, never titles, paths or commands), and adding or moving modules in; anything that takes something away, or
 * touches the Fold's home, waits in Needs you as "Grok asks: …" for his tap. No approvals, no threads, no widget commands.
 *
 * Sign-in is pre-registration: Soma has one public client id, shown with a one-time consent code on one of his paired
 * devices; he pastes the id at grok.com, and types the code on Soma's sign-in page when Grok sends him there. Grok's
 * redirect URL isn't published, so the first one (https, on grok.com or x.ai, while a consent code is open) is pinned and
 * every later one must match it exactly. PKCE S256 is required; tokens are for this server alone (the resource, checked on
 * every call), last an hour, and refresh by rotation (a refresh used twice ends the whole sign-in).
 */
class GrokConnector(
    private val service: SomaService,
    private val home: File,
) {
    /** The clock: tests move it to see codes and tokens end. */
    @Volatile internal var now: () -> Long = System::currentTimeMillis

    /** The public door's address (SOMA_PUBLIC_URL, Funnel's): nothing here answers until it is set. */
    @Volatile var base: String? = System.getenv("SOMA_PUBLIC_URL")?.trimEnd('/')?.ifEmpty { null }

    private val random = SecureRandom()
    private val file = File(home, "grok.json")
    private val calls = File(home, "grok-calls.log")

    private val resource get() = base?.let { "$it/mcp" }

    private fun state(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun keep(o: JSONObject) {
        home.mkdirs()
        val fresh = File(home, ".grok.json.new")
        fresh.writeText(o.toString())
        fresh.setReadable(false, false); fresh.setReadable(true, true); fresh.setWritable(false, false); fresh.setWritable(true, true)
        if (!fresh.renameTo(file)) { file.delete(); fresh.renameTo(file) }
    }

    private fun secret(prefix: String) = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun log(o: JSONObject) = runCatching { calls.appendText(o.put("at", now()).toString() + "\n") }

    // Consent: opened from one of his paired devices.

    private class Consent(val code: String, val until: Long, val by: String, var wrong: Int = 0)
    private var consent: Consent? = null

    /** The client id (made once) and a fresh one-time consent code, for [device] to show him. */
    @Synchronized fun openConsent(device: String): JSONObject {
        val s = state()
        val id = s.optString("client_id").ifEmpty { "soma-" + secret("").take(16).lowercase() }
        if (!s.has("client_id")) keep(s.put("client_id", id))
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val code = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val until = now() + 10 * 60_000
        consent = Consent(code, until, device)
        val b = base
        return JSONObject().put("ok", true).put("client_id", id).put("code", code).put("until", until).put("public", b != null)
            .put("authorization_endpoint", b?.let { "$it/oauth/authorize" }).put("token_endpoint", b?.let { "$it/oauth/token" })
            .put("mcp", resource).put("scope", SCOPE).put("redirect", s.optString("redirect").ifEmpty { null })
    }

    /** Signed out: every token goes, and the pinned redirect too (the next sign-in pins again, with a code open). */
    @Synchronized fun disconnect() {
        keep(state().apply { remove("tokens"); remove("redirect") })
        consent = null
        codes.clear()
        // What Grok asked and he hasn't answered goes too: a disconnected Grok asks nothing more.
        waiting.clear()
    }

    @Synchronized private fun takeConsent(typed: String): Boolean {
        val c = consent ?: return false
        if (now() > c.until) { consent = null; return false }
        if (!MessageDigest.isEqual(c.code.toByteArray(), typed.trim().uppercase().toByteArray())) {
            if (++c.wrong >= 5) consent = null
            return false
        }
        consent = null
        return true
    }

    // Grok's asks that wait for his tap.

    class GrokAsk(val id: String, val text: String, val screen: String, val words: List<String>, val at: Long)
    private val waiting = java.util.concurrent.CopyOnWriteArrayList<GrokAsk>()

    fun asks(): List<GrokAsk> = waiting.toList()

    /** [given] with Grok's waiting asks in its Needs you; his tap on one runs it (approve) or drops it (deny). */
    fun withAsks(given: ModuleSource): ModuleSource = object : ModuleSource by given {
        override fun asks() = waiting.map { ModuleAsk("grok:" + it.id, "Grok asks: " + it.text.take(200), it.words.joinToString(" ") + " · " + it.screen, "", "Grok", it, wholeDetail = true) } + given.asks()

        override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) {
            // Grok's asks are this wrapper's own, by their id: everything else is the feeds'.
            val id = ask.id.removePrefix("grok:").takeIf { ask.id.startsWith("grok:") && waiting.any { w -> w.id == it } } ?: return given.answer(ask, allow, said)
            said(answer(id, allow))
        }
    }

    /** His answer to Grok's ask [id]: what came of it, or null when there is nothing to say. */
    fun answer(id: String, allow: Boolean): String? {
        val ask = waiting.firstOrNull { it.id == id } ?: return "That ask from Grok is gone"
        if (!waiting.remove(ask)) return "That ask from Grok is gone"
        log(JSONObject().put("answered", ask.id).put("allow", allow))
        if (!allow) return null
        val screen = service.screen(ask.screen) ?: return "The screen \"${ask.screen}\" is gone"
        val done = CanvasCommands.run(ask.words, screen, "grok-ok-" + ask.id, "grok, approved by him", service.shelf)
        if (done.optBoolean("ok")) service.shelfLog("grok, approved by him", ask.screen, ask.words, done)
        return if (done.optBoolean("ok")) null else done.optString("error").ifEmpty { "It couldn't be done" }
    }

    // The public door's routes.

    fun handles(path: String) = path == "/mcp" || path.startsWith("/oauth/") || path.startsWith("/.well-known/oauth-")

    fun handle(ex: HttpExchange, who: String, limited: (String, Int) -> Boolean) {
        val b = base ?: return send(ex, 404, "text/plain", "")
        val path = ex.requestURI.path
        when {
            ex.requestMethod == "GET" && (path == "/.well-known/oauth-protected-resource" || path == "/.well-known/oauth-protected-resource/mcp") ->
                json(ex, 200, JSONObject().put("resource", resource).put("authorization_servers", JSONArray(listOf(b)))
                    .put("scopes_supported", JSONArray(listOf(SCOPE))).put("bearer_methods_supported", JSONArray(listOf("header"))).put("resource_name", "Soma"))
            ex.requestMethod == "GET" && path == "/.well-known/oauth-authorization-server" ->
                json(ex, 200, JSONObject().put("issuer", b).put("authorization_endpoint", "$b/oauth/authorize").put("token_endpoint", "$b/oauth/token")
                    .put("response_types_supported", JSONArray(listOf("code"))).put("grant_types_supported", JSONArray(listOf("authorization_code", "refresh_token")))
                    .put("code_challenge_methods_supported", JSONArray(listOf("S256"))).put("token_endpoint_auth_methods_supported", JSONArray(listOf("none")))
                    .put("scopes_supported", JSONArray(listOf(SCOPE))).put("authorization_response_iss_parameter_supported", true))
            path == "/oauth/authorize" && ex.requestMethod == "GET" -> authorize(ex, query(ex.requestURI.rawQuery), who, limited)
            path == "/oauth/authorize" && ex.requestMethod == "POST" -> authorize(ex, query(body(ex)), who, limited, posted = true)
            path == "/oauth/token" && ex.requestMethod == "POST" -> token(ex, query(body(ex)))
            path == "/mcp" && ex.requestMethod == "POST" -> mcp(ex, who)
            path == "/mcp" -> send(ex, 405, "text/plain", "", mapOf("Allow" to "POST"))
            else -> send(ex, 404, "text/plain", "")
        }
    }

    // Authorization: the code, after his consent code.

    private class Code(val clientId: String, val redirect: String, val challenge: String, val resource: String, val until: Long)
    private val codes = java.util.concurrent.ConcurrentHashMap<String, Code>()

    private fun authorize(ex: HttpExchange, q: Map<String, String>, who: String, limited: (String, Int) -> Boolean, posted: Boolean = false) {
        val s = state()
        val clientId = s.optString("client_id")
        // Until the client and its redirect check out, nothing is sent anywhere: a page here says what is wrong.
        if (clientId.isEmpty() || q["client_id"] != clientId) return page(ex, 400, "Soma doesn't know that client. Open \"Connect Grok\" on your desktop for Soma's client id.")
        val redirect = q["redirect_uri"].orEmpty()
        val pinned = s.optString("redirect").ifEmpty { null }
        if (pinned != null && redirect != pinned) return page(ex, 400, "That isn't the address Soma signs Grok in to.")
        if (pinned == null && !grokAddress(redirect)) return page(ex, 400, "Soma signs in only to Grok (an https address on grok.com or x.ai).")
        fun fail(error: String) = send(ex, 302, "text/plain", "", mapOf("Location" to withQuery(redirect, listOf("error" to error, "state" to q["state"], "iss" to base))))
        if (q["response_type"] != "code") return fail("unsupported_response_type")
        val challenge = q["code_challenge"].orEmpty()
        if (q["code_challenge_method"] != "S256" || !Regex("[A-Za-z0-9_-]{43}").matches(challenge)) return fail("invalid_request")
        val asked = q["resource"]?.trimEnd('/') ?: resource!!
        if (asked != resource) return fail("invalid_target")
        if (q["scope"] != null && q["scope"]!!.split(' ').any { it.isNotEmpty() && it != SCOPE }) return fail("invalid_scope")
        if (!posted) return consentPage(ex, q)
        if (limited("grok-consent:$who", 10) || limited("grok-consent", 30)) return page(ex, 429, "Too many tries; wait a minute.")
        if (!takeConsent(q["consent"].orEmpty())) return consentPage(ex, q, wrong = true)
        // His consent: the redirect is pinned now if it wasn't, and said.
        if (pinned == null) { keep(state().put("redirect", redirect)); log(JSONObject().put("pinned", redirect)) }
        val code = secret("c-")
        codes[digest(code)] = Code(clientId, redirect, challenge, asked, now() + 60_000)
        log(JSONObject().put("signed-in", clientId))
        send(ex, 302, "text/plain", "", mapOf("Location" to withQuery(redirect, listOf("code" to code, "state" to q["state"], "iss" to base))))
    }

    private fun grokAddress(url: String) = runCatching {
        val u = URI(url)
        val host = u.host?.lowercase() ?: return false
        u.scheme == "https" && u.userInfo == null && u.fragment == null && (u.port == -1 || u.port == 443) &&
            (host == "grok.com" || host.endsWith(".grok.com") || host == "x.ai" || host.endsWith(".x.ai"))
    }.getOrDefault(false)

    private fun token(ex: HttpExchange, q: Map<String, String>) {
        fun no(error: String, code: Int = 400) = json(ex, code, JSONObject().put("error", error))
        val clientId = state().optString("client_id")
        if (clientId.isEmpty() || q["client_id"] != clientId) return no("invalid_client", 401)
        when (q["grant_type"]) {
            "authorization_code" -> {
                val c = codes.remove(digest(q["code"].orEmpty())) ?: return no("invalid_grant")
                if (now() > c.until || c.clientId != clientId || q["redirect_uri"] != c.redirect) return no("invalid_grant")
                val verifier = q["code_verifier"].orEmpty()
                if (!Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier)) return no("invalid_grant")
                val made = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                if (!MessageDigest.isEqual(made.toByteArray(), c.challenge.toByteArray())) return no("invalid_grant")
                if (q["resource"] != null && q["resource"]!!.trimEnd('/') != c.resource) return no("invalid_target")
                json(ex, 200, issue(c.resource, secret("f-")))
            }
            "refresh_token" -> {
                val presented = digest(q["refresh_token"].orEmpty())
                val answer = synchronized(this) {
                    val s = state()
                    val tokens = s.optJSONArray("tokens") ?: JSONArray()
                    val found = (0 until tokens.length()).map { tokens.getJSONObject(it) }.firstOrNull { it.optString("digest") == presented && it.optString("kind") == "refresh" }
                        ?: return@synchronized null
                    // Used before: someone else has it too; the whole sign-in ends.
                    if (found.optBoolean("used") || now() > found.optLong("expires")) {
                        keep(s.put("tokens", JSONArray((0 until tokens.length()).map { tokens.getJSONObject(it) }.filter { it.optString("family") != found.optString("family") })))
                        log(JSONObject().put("refresh-reused", found.optString("family")))
                        return@synchronized null
                    }
                    // A refresh for another resource is refused before the token is spent (round-1 review: it ended the sign-in).
                    if (q["resource"] != null && q["resource"]!!.trimEnd('/') != found.optString("aud")) return@synchronized JSONObject().put("target", false)
                    found.put("used", true)
                    keep(s)
                    found
                } ?: return no("invalid_grant")
                if (answer.has("target")) return no("invalid_target")
                json(ex, 200, issue(answer.optString("aud"), answer.optString("family")))
            }
            else -> no("unsupported_grant_type")
        }
    }

    /** An hour's access token and a fresh refresh token for [aud], kept as digests. */
    @Synchronized private fun issue(aud: String, family: String): JSONObject {
        val access = secret(TOKEN_PREFIX)
        val refresh = secret(TOKEN_PREFIX + "r-")
        val s = state()
        val t = now()
        val kept = (s.optJSONArray("tokens") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.optLong("expires") > t }
        s.put("tokens", JSONArray(kept + listOf(
            JSONObject().put("digest", digest(access)).put("kind", "access").put("aud", aud).put("family", family).put("expires", t + ACCESS_MS),
            JSONObject().put("digest", digest(refresh)).put("kind", "refresh").put("aud", aud).put("family", family).put("expires", t + REFRESH_MS),
        )))
        keep(s)
        return JSONObject().put("access_token", access).put("token_type", "Bearer").put("expires_in", ACCESS_MS / 1000).put("refresh_token", refresh).put("scope", SCOPE)
    }

    /** Whether [bearer] is a live access token for this server, the audience checked. */
    private fun allowed(bearer: String?): Boolean {
        if (bearer.isNullOrEmpty() || !bearer.startsWith(TOKEN_PREFIX)) return false
        val d = digest(bearer)
        val tokens = state().optJSONArray("tokens") ?: return false
        return (0 until tokens.length()).map { tokens.getJSONObject(it) }.any {
            it.optString("kind") == "access" && MessageDigest.isEqual(it.optString("digest").toByteArray(), d.toByteArray()) && it.optString("aud") == resource && it.optLong("expires") > now()
        }
    }

    // MCP: stateless, JSON answers, no session id.

    private val arranges = java.util.ArrayDeque<Long>()

    private fun mcp(ex: HttpExchange, who: String) {
        val bearer = ex.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        if (!allowed(bearer)) {
            return send(ex, 401, "application/json", JSONObject().put("error", "invalid_token").toString(),
                mapOf("WWW-Authenticate" to "Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource\", scope=\"$SCOPE\"" + if (bearer != null) ", error=\"invalid_token\"" else ""))
        }
        // A browser page somewhere else never drives it (DNS rebinding): an Origin, when sent, must be Grok's.
        ex.requestHeaders.getFirst("Origin")?.let { if (!grokAddress(it)) return send(ex, 403, "text/plain", "") }
        // An older or newer protocol named is answered if known; none named is taken as the current one.
        ex.requestHeaders.getFirst("MCP-Protocol-Version")?.let { if (it !in VERSIONS) return json(ex, 400, rpcError(null, -32600, "unsupported protocol version $it")) }
        if ((ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L) > MAX_BODY) return send(ex, 413, "text/plain", "")
        val text = body(ex)
        if (text.trimStart().startsWith("[")) return json(ex, 400, rpcError(null, -32600, "one message per request"))
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return json(ex, 400, rpcError(null, -32700, "not a JSON-RPC message"))
        val id = msg.opt("id")
        val method = msg.optString("method")
        if (id == null || id == JSONObject.NULL) return send(ex, 202, "text/plain", "")
        val result: JSONObject = when (method) {
            "initialize" -> JSONObject().put("protocolVersion", msg.optJSONObject("params")?.optString("protocolVersion")?.takeIf { it in VERSIONS } ?: VERSIONS.first())
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "soma").put("title", "Soma").put("version", "1"))
                .put("instructions", "Soma is Boss's canvas of screens (the car, his desktops). Use soma_status for what waits for him; soma_arrange to add or move modules.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", tools())
            "tools/call" -> {
                val params = msg.optJSONObject("params") ?: JSONObject()
                if (params.optString("name") !in setOf("soma_status", "soma_arrange")) return json(ex, 200, rpcError(id, -32602, "no tool \"${params.optString("name")}\""))
                call(params, who)
            }
            else -> return json(ex, 200, rpcError(id, -32601, "no method $method"))
        }
        json(ex, 200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
    }

    private fun tools() = JSONArray(listOf(
        JSONObject().put("name", "soma_status").put("title", "Soma status")
            .put("description", "A summary of Boss's Soma: how many things wait for his OK, how many agent threads are working, waiting or idle, which computers are online, and the project names. No titles, paths or commands.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false))
            .put("annotations", JSONObject().put("readOnlyHint", true)),
        JSONObject().put("name", "soma_arrange").put("title", "Arrange a Soma screen")
            .put("description", "Arrange a screen of Soma's canvas with canvas words, e.g. \"place needs card top-left\", \"move threads bottom-right\", " +
                "\"resize pocket line\", \"page-make Trip\". Modules: needs, threads, computers, handoff, sessions, pocket, timeline, new-thread. " +
                "Sizes: dot, line, card, full. Also: \"layout\" (the preferences Boss stated), \"templates\", \"template-save NAME\". " +
                "On the car a template goes onto its own page (the one the car shows). Adding, moving and keeping a new template run now; removing anything, laying out a template, remembering a preference, " +
                "and anything on the Fold wait for Boss's tap. " +
                "The screen is the car's unless named. At most 5 calls in 10 minutes.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string").put("description", "The canvas words, e.g. \"place needs card top-left\"").put("maxLength", 300))
                .put("screen", JSONObject().put("type", "string").put("description", "Which screen: car (the default) or desk-<computer>")))
                .put("required", JSONArray(listOf("text"))).put("additionalProperties", false)),
    ))

    private fun call(params: JSONObject, who: String): JSONObject {
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return when (params.optString("name")) {
            "soma_status" -> { log(JSONObject().put("tool", "soma_status")); said(status().toString(), false) }
            "soma_arrange" -> arrange(args.optString("text"), args.optString("screen").ifEmpty { CAR_SCREEN })
            else -> said("No tool \"${params.optString("name")}\" here.", true)
        }
    }

    private fun said(text: String, error: Boolean) = JSONObject().put("content", JSONArray(listOf(JSONObject().put("type", "text").put("text", text)))).put("isError", error)

    /** Counts and names of projects only: assume what Grok is told is kept by xAI. */
    private fun status(): JSONObject {
        val s = service.source
        val threads = s.threads()
        return JSONObject().put("needs_you", s.asks().size)
            .put("threads", JSONObject().put("working", threads.count { it.state.name.startsWith("Working") })
                .put("waiting_for_him", threads.count { it.state == dev.omalauncher.assist.ThreadState.NeedsOk || it.state == dev.omalauncher.assist.ThreadState.Attention })
                .put("all", threads.size))
            .put("computers", JSONObject().put("online", s.computers().count { it.online }).put("all", s.computers().size))
            .put("projects", JSONArray(s.projects()))
    }

    @Synchronized private fun arrange(text: String, screenName: String): JSONObject {
        val t = now()
        while (arranges.isNotEmpty() && t - arranges.first > 10 * 60_000) arranges.removeFirst()
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(40)
        // Logged as said, up to the longest a call may be (round-2 review: a long fumble grew the log by its whole length).
        fun done(outcome: String, answer: JSONObject): JSONObject { log(JSONObject().put("tool", "soma_arrange").put("text", text.take(300)).put("screen", screenName).put("outcome", outcome)); return answer }
        // Reading never counts toward the cap, so it isn't stopped by it either.
        val readOnly = words.firstOrNull() in setOf("board", "modules", "templates", "layout")
        if (arranges.size >= 5 && !readOnly) return done("over the cap", said("Soma takes at most 5 arrangements in 10 minutes; ask again later.", true))
        if (text.length > 300 || words.isEmpty()) return done("refused", said("Say it in canvas words, at most 300 characters: ${CanvasCommands.USAGE}", true))
        if (!SCREEN.matches(screenName) || screenName.startsWith("..")) return done("refused", said("No screen \"$screenName\".", true))
        val screen = service.screen(screenName) ?: return done("refused", said("No screen \"$screenName\".", true))
        // Keeping a page as a template adds; laying one out, or saying what he prefers, is his to OK.
        // Replacing one he has is his to OK too (round-1 review: Grok replaced his template).
        val keeps = words[0] == "template-save" && "--replace" !in words
        val ask = if (readOnly || keeps || words[0] in setOf("undo", "template-apply", "remember", "template-save")) null else runCatching { CanvasCommands.ask(words) }.getOrElse { return done("refused", said(it.message ?: CanvasCommands.USAGE, true)) }
        val adds = readOnly || keeps || ask is CanvasAsk.Place || ask is CanvasAsk.Move || ask is CanvasAsk.Resize || ask is CanvasAsk.PageMake
        // Anything on the Fold's home but reading it waits for him.
        if (!adds || (screenName.startsWith("fold-") && !readOnly)) {
            val waits = GrokAsk(secret("").take(10), text, screenName, words, t)
            waiting += waits
            arranges.addLast(t)
            return done("waits for his tap", said("That waits for Boss's tap in Needs you (\"Grok asks: $text\"); nothing was changed yet.", false))
        }
        val answer = CanvasCommands.run(words, screen, "grok-" + t.toString(36), "grok", service.shelf)
        if (answer.optBoolean("ok")) service.shelfLog("grok", screenName, words, answer)
        // Only what changes something (or waits for him) counts toward the cap: a fumbled phrasing doesn't.
        if (answer.optBoolean("ok") && !readOnly) arranges.addLast(t)
        return done(if (answer.optBoolean("ok")) "done" else "refused", said(answer.toString(), !answer.optBoolean("ok")))
    }

    // Plumbing.

    private fun body(ex: HttpExchange): String {
        val length = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
        if (length > MAX_BODY) return ""
        return String(ex.requestBody.readNBytes(MAX_BODY), Charsets.UTF_8)
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { p ->
        p.split('=', limit = 2).takeIf { it.size == 2 }?.let { runCatching { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") }.getOrNull() }
    }.groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value.single() }

    private fun withQuery(url: String, params: List<Pair<String, String?>>): String =
        url + (if ('?' in url) "&" else "?") + params.filter { it.second != null }.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun rpcError(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message))

    private fun json(ex: HttpExchange, code: Int, o: JSONObject) = send(ex, code, "application/json", o.toString())

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(ex: HttpExchange, code: Int, said: String) = send(ex, code, "text/html; charset=utf-8", PAGE.replace("{{body}}", "<p>${esc(said)}</p>"))

    private fun consentPage(ex: HttpExchange, q: Map<String, String>, wrong: Boolean = false) {
        val hidden = q.filterKeys { it != "consent" }.entries.joinToString("") { (k, v) -> "<input type=hidden name=\"${esc(k)}\" value=\"${esc(v)}\">" }
        send(ex, 200, "text/html; charset=utf-8", PAGE.replace("{{body}}", """
            <h1>Connect Grok to Soma</h1>
            <p>Grok will see a summary of Soma (counts, never titles or commands), the names of your templates and the layout preferences you stated, and may add or move modules and keep new templates; anything else waits for your tap.</p>
            ${if (wrong) "<p class=wrong>That code isn't right, or it has expired. Open \"Connect Grok\" on your desktop for a new one.</p>" else ""}
            <form method=post action="/oauth/authorize">$hidden<input name=consent autocomplete=off autocapitalize=characters maxlength=8 placeholder="code from your desktop" required>
            <button>Connect</button></form>
        """.trimIndent()))
    }

    private fun send(ex: HttpExchange, code: Int, type: String, text: String, headers: Map<String, String> = emptyMap()) {
        val data = text.toByteArray()
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.responseHeaders.set("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.set("Referrer-Policy", "no-referrer")
        ex.responseHeaders.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        headers.forEach { (k, v) -> ex.responseHeaders.set(k, v) }
        ex.sendResponseHeaders(code, if (data.isEmpty()) -1 else data.size.toLong())
        if (data.isNotEmpty()) ex.responseBody.use { it.write(data) } else ex.close()
    }

    companion object {
        const val SCOPE = "soma"
        /** Every token Soma gives Grok starts so: the tailnet door, thread tokens and the car's key never look like it. */
        const val TOKEN_PREFIX = "soma-grok-"
        const val ACCESS_MS = 60 * 60_000L
        const val REFRESH_MS = 30 * 24 * 60 * 60_000L
        private const val MAX_BODY = 16 * 1024
        /** The current MCP protocol first; the older shape still answered. */
        val VERSIONS = listOf("2026-07-28", "2025-11-25", "2025-06-18")
        private val SCREEN = Regex("[a-z0-9][a-z0-9-]{0,40}")
        private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        private val PAGE = """
            <!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>Soma</title>
            <style>body{font:18px/1.4 -apple-system,Helvetica,Arial,sans-serif;max-width:32em;margin:3em auto;padding:0 16px;background:#fff;color:#12296b}
            input,button{font:inherit;padding:.5em;margin:.25em 0}.wrong{color:#c33d41}</style></head><body>{{body}}</body></html>
        """.trimIndent()
    }
}

/** Connecting Grok from Soma's tray: what to paste at grok.com and the code to type when Grok sends him to sign in. What to say. */
object GrokConnecting {
    private fun service(machines: List<dev.omalauncher.assist.MacMachine>) =
        machines.firstNotNullOfOrNull { m -> dev.omalauncher.canvas.advertisedService(m.config.url, m.config.key)?.let { it to m.config.key } }

    /**
     * What to say (short enough for a notice, which shows 240 characters) and, when there is one, what to paste at grok.com
     * (put on his clipboard: a notice can't be copied from; round-1 review: the code was cut off).
     */
    fun start(machines: List<dev.omalauncher.assist.MacMachine>): Pair<String, String?> {
        val (url, key) = service(machines) ?: return "No canvas service answered, so Grok can't be connected now." to null
        val (code, answer) = dev.omalauncher.canvas.somaPost(url.trimEnd('/') + "/grok/consent", key, "{}")
        val got = runCatching { JSONObject(answer) }.getOrNull()?.takeIf { code == 200 } ?: return "The canvas service didn't answer (HTTP $code)." to null
        if (!got.optBoolean("public")) return "The canvas service has no public address yet (Funnel and SOMA_PUBLIC_URL), so Grok can't reach it." to null
        val details = """
            Soma connector for Grok — at grok.com: Connectors → New Connector → Custom
            Server URL: ${got.getString("mcp")}
            Client ID: ${got.getString("client_id")}
            Authorization URL: ${got.getString("authorization_endpoint")}
            Token URL: ${got.getString("token_endpoint")}
            Scope: ${got.getString("scope")}
            Client authentication: none (PKCE only)
            When Grok sends you to Soma's sign-in page, type: ${got.getString("code")} (within 10 minutes, once)
        """.trimIndent()
        return "Type ${got.getString("code")} on Soma's page when Grok sends you there (10 minutes). Grok's connector details are on your clipboard." to details
    }

    fun end(machines: List<dev.omalauncher.assist.MacMachine>): String {
        val (url, key) = service(machines) ?: return "No canvas service answered, so Grok wasn't disconnected."
        val code = runCatching {
            val c = URI(url.trimEnd('/') + "/grok").toURL().openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 10000; c.requestMethod = "DELETE"
            c.setRequestProperty("Authorization", "Bearer $key")
            c.responseCode
        }.getOrDefault(-1)
        return if (code == 200) "Grok is disconnected: its tokens no longer work." else "Grok couldn't be disconnected (HTTP $code)."
    }
}
