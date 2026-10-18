package dev.omalauncher.desktop

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.widgets.runtime.CommandGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * The Soma connector for Grok (Soma Canvas slice 9c): metadata and the 401 challenge as the MCP spec has them, PKCE
 * enforced, the redirect pinned, the audience checked, refresh by rotation; a summary out and add-or-move in, anything
 * else waiting for his tap; and a Grok token opens nothing else.
 */
class GrokConnectorTest {
    private val home = Files.createTempDirectory("soma-grok").toFile().also { System.setProperty("soma.home", it.path) }
    private val foldKey = "fold-key-" + "x".repeat(40)
    private val keys = File(home, "keys.json").apply {
        writeText(JSONObject().put("devices", JSONArray(listOf(JSONObject().put("name", "fold").put("digest", keyDigest(foldKey))))).toString())
    }
    private val secretTitle = "rotate the prod database password"
    private val source = object : ModuleSource {
        override fun threads() = listOf(AgentThread("mac:t1", secretTitle, ThreadState.NeedsOk, "doing /Users/jackkim/secret", "needs your OK"))
        override fun asks() = listOf(ModuleAsk("a1", secretTitle, "rm -rf /tmp/x", "mac:t1", "Mac Pro", Any()))
        override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) = said(null)
        override fun projects() = listOf("omalauncher")
    }
    private val service = SomaService(home, PairedDevices(keys) { "fold" }, source, CommandGate(File(home, "approved.json"), "macpro"), "macpro")
        .also { it.grok.base = "https://macpro.example.ts.net" }
    private val tailnet = service.open("127.0.0.1", 0)
    private val door = service.car.open("127.0.0.1", 0)
    private val inside = "http://127.0.0.1:${tailnet.address.port}"
    private val outside = "http://127.0.0.1:${door.address.port}"
    private val grokRedirect = "https://grok.com/connectors/oauth/callback"
    private val resource = "https://macpro.example.ts.net/mcp"

    private class Answer(val code: Int, val text: String, private val raw: Map<String, List<String>>) {
        fun header(name: String) = raw.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        val json get() = JSONObject(text)
    }

    private fun call(url: String, method: String = "GET", body: String? = null, bearer: String? = null, form: Boolean = false, headers: Map<String, String> = emptyMap(), cookie: String? = null): Answer {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        cookie?.let { c.setRequestProperty("Cookie", "soma_car=$it") }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        return Answer(code, text, c.headerFields.filterKeys { it != null })
    }

    private fun enc(params: List<Pair<String, String>>) = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun params(location: String) = location.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private val verifier = "v".repeat(20) + "-verifier-" + "w".repeat(20)
    private val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private fun authorizeParams(clientId: String, redirect: String = grokRedirect, method: String = "S256", ch: String = challenge) =
        listOf("response_type" to "code", "client_id" to clientId, "redirect_uri" to redirect, "code_challenge" to ch, "code_challenge_method" to method, "state" to "st8", "resource" to resource, "scope" to "soma")

    /** His sign-in, as Grok and he would do it: the consent code from his device, typed on Soma's page. A code for Grok's token call. */
    private fun signIn(): Pair<String, String> {
        val consent = call("$inside/grok/consent", "POST", "{}", foldKey).json
        val clientId = consent.getString("client_id")
        val page = call("$outside/oauth/authorize?" + enc(authorizeParams(clientId)))
        assertEquals(200, page.code)
        assertTrue(page.text.contains("name=consent"))
        val back = call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId) + ("consent" to consent.getString("code"))), form = true)
        assertEquals(302, back.code)
        val p = params(back.header("Location")!!)
        assertTrue(back.header("Location")!!.startsWith(grokRedirect + "?"))
        assertEquals("st8", p["state"])
        assertEquals("https://macpro.example.ts.net", p["iss"])
        return clientId to p.getValue("code")
    }

    private fun tokens(clientId: String, code: String, v: String = verifier) = call("$outside/oauth/token", "POST",
        enc(listOf("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "redirect_uri" to grokRedirect, "code_verifier" to v, "resource" to resource)), form = true)

    private fun rpc(token: String?, method: String, params: JSONObject = JSONObject(), id: Int = 1) =
        call("$outside/mcp", "POST", JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString(), token,
            headers = mapOf("Accept" to "application/json, text/event-stream", "MCP-Protocol-Version" to "2026-07-28"))

    private fun tool(token: String, name: String, args: JSONObject = JSONObject()) =
        rpc(token, "tools/call", JSONObject().put("name", name).put("arguments", args)).json.getJSONObject("result")

    private fun signedIn(): JSONObject {
        val (clientId, code) = signIn()
        return tokens(clientId, code).json.put("client_id", clientId)
    }

    @Test fun metadataAndTheChallengeAreThereAndNothingAnswersBeforeThePublicAddressIsSet() {
        val prm = call("$outside/.well-known/oauth-protected-resource").json
        assertEquals(resource, prm.getString("resource"))
        assertEquals("https://macpro.example.ts.net", prm.getJSONArray("authorization_servers").getString(0))
        val asm = call("$outside/.well-known/oauth-authorization-server").json
        assertEquals("https://macpro.example.ts.net", asm.getString("issuer"))
        assertEquals(listOf("S256"), asm.getJSONArray("code_challenge_methods_supported").toList())
        assertEquals(listOf("none"), asm.getJSONArray("token_endpoint_auth_methods_supported").toList())
        val bare = rpc(null, "tools/list")
        assertEquals(401, bare.code)
        assertTrue(bare.header("WWW-Authenticate")!!, bare.header("WWW-Authenticate")!!.contains("resource_metadata=\"https://macpro.example.ts.net/.well-known/oauth-protected-resource\""))
        assertEquals(401, rpc("soma-grok-made-up", "tools/list").code)
        // Before Funnel's address is set, none of it answers.
        service.grok.base = null
        assertEquals(404, call("$outside/.well-known/oauth-authorization-server").code)
        assertEquals(404, rpc(null, "tools/list").code)
        stop()
    }

    @Test fun signInNeedsHisCodePkceAndGroksPinnedRedirect() {
        val consent = call("$inside/grok/consent", "POST", "{}", foldKey).json
        val clientId = consent.getString("client_id")
        // A redirect that isn't Grok's is never sent anything.
        for (bad in listOf("http://grok.com/cb", "https://evil.example/cb", "https://grok.com.evil.example/cb", "https://user@grok.com/cb", "https://grok.com:8443/cb"))
            assertEquals(bad, 400, call("$outside/oauth/authorize?" + enc(authorizeParams(clientId, redirect = bad))).code)
        assertEquals(400, call("$outside/oauth/authorize?" + enc(authorizeParams("someone-else"))).code)
        // PKCE: S256 only, and a challenge there.
        assertEquals("invalid_request", params(call("$outside/oauth/authorize?" + enc(authorizeParams(clientId, method = "plain"))).header("Location")!!)["error"])
        assertEquals("invalid_request", params(call("$outside/oauth/authorize?" + enc(authorizeParams(clientId, ch = "short"))).header("Location")!!)["error"])
        // Another resource: refused.
        val other = authorizeParams(clientId).map { if (it.first == "resource") it.first to "https://elsewhere.example/mcp" else it }
        assertEquals("invalid_target", params(call("$outside/oauth/authorize?" + enc(other)).header("Location")!!)["error"])
        // A wrong code: the page again, nothing sent.
        val wrong = call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId) + ("consent" to "WRONGONE")), form = true)
        assertEquals(200, wrong.code)
        assertTrue(wrong.text.contains("isn't right"))
        // His code: a code back to Grok, the redirect pinned from now on.
        val back = call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId) + ("consent" to consent.getString("code"))), form = true)
        val code = params(back.header("Location")!!).getValue("code")
        assertEquals(grokRedirect, JSONObject(File(home, "grok.json").readText()).getString("redirect"))
        assertEquals(400, call("$outside/oauth/authorize?" + enc(authorizeParams(clientId, redirect = "https://x.ai/other"))).code)
        // The consent code was once only.
        assertEquals(200, call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId) + ("consent" to consent.getString("code"))), form = true).code)
        // A wrong verifier gets nothing, and the code is spent.
        assertEquals("invalid_grant", tokens(clientId, code, v = "x".repeat(50)).json.getString("error"))
        assertEquals("invalid_grant", tokens(clientId, code).json.getString("error"))
        // A fresh code and the right verifier: an hour's token.
        val (_, again) = run {
            val c2 = call("$inside/grok/consent", "POST", "{}", foldKey).json.getString("code")
            val b2 = call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId) + ("consent" to c2)), form = true)
            clientId to params(b2.header("Location")!!).getValue("code")
        }
        val t = tokens(clientId, again)
        assertEquals(200, t.code)
        assertEquals(3600, t.json.getInt("expires_in"))
        assertTrue(t.json.getString("access_token").startsWith("soma-grok-"))
        // Kept as digests only.
        assertFalse(File(home, "grok.json").readText().contains(t.json.getString("access_token")))
        stop()
    }

    @Test fun codesAndTokensEndOnTimeAndTheTokenCallChecksEverything() {
        var t = 1_000_000L
        service.grok.now = { t }
        val (clientId, code) = signIn()
        // Another redirect, a verifier too short (even one that hashes right), a code a minute old: nothing.
        val other = call("$outside/oauth/token", "POST", enc(listOf("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "redirect_uri" to "https://grok.com/other", "code_verifier" to verifier)), form = true)
        assertEquals("invalid_grant", other.json.getString("error"))
        // A verifier shorter than PKCE allows is refused even when its hash is the challenge sent.
        val short = "abc"
        val shortChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(short.toByteArray()))
        val c2 = call("$inside/grok/consent", "POST", "{}", foldKey).json.getString("code")
        val b2 = call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId, ch = shortChallenge) + ("consent" to c2)), form = true)
        assertEquals("invalid_grant", tokens(clientId, params(b2.header("Location")!!).getValue("code"), v = short).json.getString("error"))
        val (_, code3) = signIn()
        t += 61_000
        assertEquals("invalid_grant", tokens(clientId, code3).json.getString("error"))
        val (_, code4) = signIn()
        val tok = tokens(clientId, code4).json
        assertEquals(200, rpc(tok.getString("access_token"), "ping").code)
        t += 59 * 60_000
        assertEquals(200, rpc(tok.getString("access_token"), "ping").code)
        t += 2 * 60_000
        assertEquals(401, rpc(tok.getString("access_token"), "ping").code)
        // His consent code lasts ten minutes.
        val late = call("$inside/grok/consent", "POST", "{}", foldKey).json.getString("code")
        t += 10 * 60_000 + 1_000
        assertTrue(call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId) + ("consent" to late)), form = true).text.contains("isn't right"))
        // A refresh asking for another resource is refused, and the token still works for the right one.
        val wrong = call("$outside/oauth/token", "POST", enc(listOf("grant_type" to "refresh_token", "refresh_token" to tok.getString("refresh_token"), "client_id" to clientId, "resource" to "https://elsewhere.example/mcp")), form = true)
        assertEquals("invalid_target", wrong.json.getString("error"))
        val right = call("$outside/oauth/token", "POST", enc(listOf("grant_type" to "refresh_token", "refresh_token" to tok.getString("refresh_token"), "client_id" to clientId, "resource" to resource)), form = true)
        assertEquals(200, right.code)
        // A refresh token lasts thirty days.
        t += 31L * 24 * 60 * 60_000
        assertEquals("invalid_grant", call("$outside/oauth/token", "POST", enc(listOf("grant_type" to "refresh_token", "refresh_token" to right.json.getString("refresh_token"), "client_id" to clientId)), form = true).json.getString("error"))
        stop()
    }

    @Test fun theCapIsTenMinutesThatSlideAndReadingIsNeverStopped() {
        var t = 5_000_000L
        service.grok.now = { t }
        val token = signedIn().getString("access_token")
        for (w in listOf("place needs card", "place pocket card", "place timeline card", "page-make Trip", "page-make Two")) {
            assertFalse(w, tool(token, "soma_arrange", JSONObject().put("text", w)).getBoolean("isError"))
            t += 60_000
        }
        assertTrue(tool(token, "soma_arrange", JSONObject().put("text", "place threads card")).getBoolean("isError"))
        assertFalse(tool(token, "soma_arrange", JSONObject().put("text", "board")).getBoolean("isError"))
        // Ten minutes after the first, it has slid out: one more.
        t = 5_000_000L + 10 * 60_000 + 1_000
        assertFalse(tool(token, "soma_arrange", JSONObject().put("text", "place threads card")).getBoolean("isError"))
        assertTrue(tool(token, "soma_arrange", JSONObject().put("text", "place computers card")).getBoolean("isError"))
        // A long fumble is logged at the length a call may be.
        tool(token, "soma_arrange", JSONObject().put("text", "z".repeat(5_000)))
        assertTrue(File(home, "grok-calls.log").readLines().all { it.length < 800 })
        stop()
    }

    @Test fun aRefreshTokenWorksOnceAndUsedAgainEndsTheSignIn() {
        val t = signedIn()
        val refresh = { r: String -> call("$outside/oauth/token", "POST", enc(listOf("grant_type" to "refresh_token", "refresh_token" to r, "client_id" to t.getString("client_id"))), form = true) }
        val next = refresh(t.getString("refresh_token"))
        assertEquals(200, next.code)
        assertNotEquals(t.getString("refresh_token"), next.json.getString("refresh_token"))
        assertEquals(200, rpc(next.json.getString("access_token"), "tools/list").code)
        // The old refresh token, used again: refused, and every token of that sign-in ends.
        assertEquals("invalid_grant", refresh(t.getString("refresh_token")).json.getString("error"))
        assertEquals(401, rpc(next.json.getString("access_token"), "tools/list").code)
        assertEquals("invalid_grant", refresh(next.json.getString("refresh_token")).json.getString("error"))
        stop()
    }

    @Test fun aTokenForAnotherAddressIsRefused() {
        val token = signedIn().getString("access_token")
        assertEquals(200, rpc(token, "tools/list").code)
        // Funnel's address changes: the token was for the old one.
        service.grok.base = "https://other.example.ts.net"
        assertEquals(401, rpc(token, "tools/list").code)
        stop()
    }

    @Test fun groksToolsSayLittleAddAndMoveAndLeaveTheRestToHim() {
        val token = signedIn().getString("access_token")
        val init = rpc(token, "initialize", JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "grok").put("version", "1")))
        assertEquals("2025-11-25", init.json.getJSONObject("result").getString("protocolVersion"))
        assertEquals(null, init.header("Mcp-Session-Id"))
        assertEquals(202, call("$outside/mcp", "POST", JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString(), token).code)
        assertEquals(listOf("soma_status", "soma_arrange"), rpc(token, "tools/list").json.getJSONObject("result").getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } })
        // No protocol header: taken as the current one. An unknown one: refused.
        assertEquals(200, call("$outside/mcp", "POST", JSONObject().put("jsonrpc", "2.0").put("id", 9).put("method", "ping").toString(), token).code)
        assertEquals(400, call("$outside/mcp", "POST", JSONObject().put("jsonrpc", "2.0").put("id", 9).put("method", "ping").toString(), token, headers = mapOf("MCP-Protocol-Version" to "1999-01-01")).code)
        // A page elsewhere can't drive it.
        // (Over a plain socket: the JDK's client won't send an Origin header.)
        fun withOrigin(origin: String): String = java.net.Socket("127.0.0.1", door.address.port).use { s ->
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 9).put("method", "ping").toString()
            s.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\nOrigin: $origin\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
            s.getInputStream().bufferedReader().readLine()
        }
        assertTrue(withOrigin("https://evil.example").contains(" 403"))
        assertTrue(withOrigin("https://grok.com").contains(" 200"))
        // The summary: counts and project names, never a title, a path or a command.
        val status = tool(token, "soma_status")
        val text = status.getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals(1, JSONObject(text).getInt("needs_you"))
        assertEquals(listOf("omalauncher"), JSONObject(text).getJSONArray("projects").toList())
        assertFalse(text, text.contains("rotate") || text.contains("/Users") || text.contains("rm -rf"))
        // Adding runs now, on the car's screen by default.
        assertFalse(tool(token, "soma_arrange", JSONObject().put("text", "place needs card top-left")).getBoolean("isError"))
        assertTrue(service.screen(CAR_SCREEN)!!.page.pages[0].items.any { it.key == "module:needs" })
        // Taking away waits for his tap, in Needs you on every screen the service draws.
        val waits = tool(token, "soma_arrange", JSONObject().put("text", "remove needs"))
        assertTrue(waits.toString(), waits.getJSONArray("content").getJSONObject(0).getString("text").contains("waits for Boss's tap"))
        assertTrue(service.screen(CAR_SCREEN)!!.page.pages[0].items.any { it.key == "module:needs" })
        val ask = service.source.asks().first { it.title.startsWith("Grok asks:") }
        assertEquals("Grok asks: remove needs", ask.title)
        // And from his devices.
        assertEquals("remove needs", call("$inside/approvals", bearer = foldKey).json.getJSONArray("grok").getJSONObject(0).getString("text"))
        // His approve runs it.
        var said: String? = "unset"
        service.source.answer(ask, true) { said = it }
        assertEquals(null, said)
        assertFalse(service.screen(CAR_SCREEN)!!.page.pages[0].items.any { it.key == "module:needs" })
        // Every call is logged with its raw text.
        val log = File(home, "grok-calls.log").readText()
        assertTrue(log.contains("\"text\":\"place needs card top-left\"") && log.contains("\"text\":\"remove needs\""))
        // Five in ten minutes, then no more.
        // Reading doesn't count, nor does what couldn't be done (Needs you is gone now); changing does.
        tool(token, "soma_arrange", JSONObject().put("text", "move needs top-right"))
        tool(token, "soma_arrange", JSONObject().put("text", "board"))
        for (words in listOf("place pocket card", "place timeline card", "page-make Trip"))
            assertFalse(words, tool(token, "soma_arrange", JSONObject().put("text", words)).getBoolean("isError"))
        val over = tool(token, "soma_arrange", JSONObject().put("text", "place threads card"))
        assertTrue(over.getBoolean("isError"))
        assertTrue(over.toString(), over.toString().contains("at most 5"))
        stop()
    }

    @Test fun anythingOnTheFoldsHomeWaitsAndNonsenseIsRefused() {
        val token = signedIn().getString("access_token")
        // The Fold's home copy on the service, made by its own sync.
        File(home, "screens").mkdirs()
        File(home, "screens/fold-q5.json").writeText(dev.omalauncher.widgets.layout.VerbJson.log(dev.omalauncher.widgets.layout.VerbLog("fold-q5")))
        // As its own first sync leaves it: the screen's grid and owner beside it (slice 8b).
        File(home, "screens/fold-q5.grid.json").writeText(JSONObject().put("columns", 6).put("rows", 8).put("owner", "fold").toString())
        // Reading the Fold's copy is reading: it runs, and waits for nothing.
        assertFalse(tool(token, "soma_arrange", JSONObject().put("text", "board").put("screen", "fold-q5")).getBoolean("isError"))
        assertTrue(service.grok.asks().isEmpty())
        val fold = tool(token, "soma_arrange", JSONObject().put("text", "place needs card").put("screen", "fold-q5"))
        assertTrue(fold.toString().contains("waits for Boss's tap"))
        assertTrue(service.screen("fold-q5")!!.page.pages.all { it.items.isEmpty() })
        assertTrue(tool(token, "soma_arrange", JSONObject().put("text", "launch the missiles")).getBoolean("isError"))
        assertTrue(tool(token, "soma_arrange", JSONObject().put("text", "place needs").put("screen", "../keys")).getBoolean("isError"))
        assertTrue(tool(token, "soma_arrange", JSONObject().put("text", "x".repeat(400))).getBoolean("isError"))
        stop()
    }

    @Test fun fumblesDontUseTheCapAndHisAnswerMustSayYesOrNo() {
        val token = signedIn().getString("access_token")
        repeat(6) { assertTrue(tool(token, "soma_arrange", JSONObject().put("text", "launch the missiles")).getBoolean("isError")) }
        assertFalse(tool(token, "soma_arrange", JSONObject().put("text", "place needs card top-left")).getBoolean("isError"))
        tool(token, "soma_arrange", JSONObject().put("text", "remove needs"))
        val id = service.grok.asks().single().id
        // His answer says yes or no; nothing else is taken for one.
        for (body in listOf(JSONObject(), JSONObject().put("allow", "true"))) assertEquals(400, call("$inside/grok/asks/$id", "POST", body.toString(), foldKey).code)
        assertEquals(1, service.grok.asks().size)
        // Disconnected, Grok asks nothing more.
        assertEquals(200, call("$inside/grok", "DELETE", bearer = foldKey).code)
        assertTrue(service.grok.asks().isEmpty())
        stop()
    }

    @Test fun messagesAreOneAtATimeAndSmall() {
        val token = signedIn().getString("access_token")
        val batch = call("$outside/mcp", "POST", "[" + JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping") + "]", token)
        assertEquals(400, batch.code)
        assertEquals(-32600, batch.json.getJSONObject("error").getInt("code"))
        assertEquals(413, call("$outside/mcp", "POST", "{\"x\":\"" + "y".repeat(20_000) + "\"}", token).code)
        assertEquals(-32602, rpc(token, "tools/call", JSONObject().put("name", "soma_delete_everything")).json.getJSONObject("error").getInt("code"))
        stop()
    }

    @Test fun grokMayKeepATemplateButLayingOneOutOrSayingWhatHePrefersWaitsForHim() {
        val token = signedIn().getString("access_token")
        fun said(r: JSONObject) = r.getJSONArray("content").getJSONObject(0).getString("text")
        assertFalse(tool(token, "soma_arrange", JSONObject().put("text", "place needs card top-left")).getBoolean("isError"))
        val kept = tool(token, "soma_arrange", JSONObject().put("text", "template-save Drive"))
        assertFalse(said(kept), kept.getBoolean("isError"))
        assertTrue(File(home, "templates/drive.json").isFile)
        for (words in listOf("template-apply Drive", "remember Needs you goes top left", "template-save Drive --replace")) assertTrue(said(tool(token, "soma_arrange", JSONObject().put("text", words))).contains("waits for Boss's tap"))
        // Keeping one by a name he already has is refused, never a silent replace.
        assertTrue(tool(token, "soma_arrange", JSONObject().put("text", "template-save Drive")).getBoolean("isError"))
        assertEquals("", service.shelf.layout())
        assertEquals(3, service.source.asks().count { it.title.startsWith("Grok asks:") })
        // What Grok kept is logged as Grok's; what he approved, as his approval of Grok's.
        val ask = service.source.asks().first { it.title.contains("remember") }
        service.source.answer(ask, true) {}
        val log = File(home, "shelf.log").readLines().map { JSONObject(it).getString("actor") to JSONObject(it).getJSONArray("words").getString(0) }
        assertEquals(listOf("grok" to "template-save", "grok, approved by him" to "remember"), log)
        stop()
    }

    @Test fun aGrokTokenOpensNothingElse() {
        val token = signedIn().getString("access_token")
        // Not the agents' API on the tailnet door.
        for ((m, p) in listOf("GET" to "/health", "GET" to "/screens", "GET" to "/approvals", "POST" to "/screens/car/canvas", "POST" to "/grok/consent"))
            assertEquals("$m $p", 401, call("$inside$p", m, if (m == "POST") JSONObject().put("words", JSONArray(listOf("board"))).toString() else null, token).code)
        // Not the car's page.
        assertEquals(401, call("$outside/car/frame", cookie = token).code)
        assertEquals(401, call("$outside/car/frame", bearer = token).code)
        assertFalse(call("$outside/", cookie = token).text.contains("EventSource"))
        stop()
    }

    @Test fun hisDesktopsTraySaysWhatToPasteAtGrokAndDisconnects() {
        val runner = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/health") { ex ->
                val body = JSONObject().put("ok", true).put("soma", inside).toString().toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val machines = listOf(dev.omalauncher.assist.MacMachine("m", "Mac Pro", dev.omalauncher.assist.MacRunnerConfig("http://127.0.0.1:${runner.address.port}", foldKey)))
        val (said, details) = GrokConnecting.start(machines)
        // The notice fits what a notice shows, code and all; the rest goes on his clipboard to paste.
        assertTrue(said, said.length <= 240 && Regex("^Type [A-Z0-9]{8} ").containsMatchIn(said))
        assertTrue(details!!, details.contains("Client ID: soma-") && details.contains("Authorization URL: https://macpro.example.ts.net/oauth/authorize") && details.contains("Server URL: $resource"))
        val code = Regex("Type ([A-Z0-9]{8})").find(said)!!.groupValues[1]
        val clientId = Regex("Client ID: (\\S+)").find(details)!!.groupValues[1]
        val back = call("$outside/oauth/authorize", "POST", enc(authorizeParams(clientId) + ("consent" to code)), form = true)
        val token = tokens(clientId, params(back.header("Location")!!).getValue("code")).json.getString("access_token")
        assertEquals(200, rpc(token, "tools/list").code)
        assertTrue(GrokConnecting.end(machines).startsWith("Grok is disconnected"))
        assertEquals(401, rpc(token, "tools/list").code)
        runner.stop(0)
        stop()
    }

    private fun JSONArray.toList() = (0 until length()).map { getString(it) }

    private fun stop() {
        tailnet.stop(0)
        door.stop(0)
    }
}
