package dev.omalauncher.desktop

import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.runtime.CommandGate
import dev.omalauncher.widgets.runtime.sha256
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
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The canvas service's door (Soma Canvas slice 8a): a paired device calling from itself gets in; a wrong key, a car key or
 * a key from the wrong device does not; a thread's token reaches verbs on its own screens only; frames are drawn headless;
 * a widget an agent installs is checked and waits in quarantine; his signed OK applies once.
 */
class ServeTest {
    private val home = Files.createTempDirectory("soma-serve").toFile().also { System.setProperty("soma.home", it.path) }
    private val foldKey = "fold-key-" + "x".repeat(40)
    private val macbookKey = "macbook-key-" + "y".repeat(40)
    private val carKey = "car-key-" + "z".repeat(40)
    private var caller = "fold"

    private val keys = File(home, "keys.json").apply {
        writeText(JSONObject().put("devices", JSONArray(listOf(
            JSONObject().put("name", "fold").put("digest", keyDigest(foldKey)),
            JSONObject().put("name", "macbook").put("digest", keyDigest(macbookKey)),
        ))).toString())
    }

    // Kept where the car's own pairing keeps it (slice 9a): never in the runner's device table.
    init { File(home, "car-keys.json").writeText(JSONObject().put("car", keyDigest(carKey)).toString()) }

    private val source = object : ModuleSource {
        override fun threads() = emptyList<dev.omalauncher.assist.AgentThread>()
        override fun asks() = emptyList<ModuleAsk>()
        override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) = said(null)
    }
    private val gate = CommandGate(File(home, "approved.json"), "macpro")
    private val service = SomaService(home, PairedDevices(keys) { caller }, source, gate, "macpro")
    private val server = service.open("127.0.0.1", 0)
    private val base = "http://127.0.0.1:${server.address.port}"

    private fun call(method: String, path: String, key: String?, body: JSONObject? = null): Pair<Int, ByteArray> {
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        key?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val data = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to data
    }

    private fun json(r: Pair<Int, ByteArray>) = JSONObject(String(r.second))

    @Test fun onlyAPairedDeviceCallingFromItselfGetsIn() {
        assertEquals(200, call("GET", "/health", foldKey).first)
        assertEquals("soma-serve", json(call("GET", "/health", foldKey)).getString("service"))
        for (key in listOf(null, "", "nope", carKey, foldKey + "x", keyDigest(foldKey))) assertEquals(key.toString(), 401, call("GET", "/health", key).first)
        // The fold's key, but whois says the call comes from the MacBook: refused, as the runner refuses it.
        caller = "macbook"
        assertEquals(401, call("GET", "/screens", foldKey).first)
        assertEquals(200, call("GET", "/screens", macbookKey).first)
        caller = "fold"
        server.stop(0)
    }

    @Test fun theCarsScreenIsDrawnHeadlessAndChangesWhenArranged() {
        val (code, empty) = call("GET", "/screens/car/frame.png", foldKey)
        assertEquals(200, code)
        val first = ImageIO.read(empty.inputStream())
        assertEquals(FRAME_WIDTH to FRAME_HEIGHT, first.width to first.height)
        val placed = json(call("POST", "/screens/car/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("place", "needs", "card", "top-left")))))
        assertTrue(placed.toString(), placed.getBoolean("ok"))
        val after = call("GET", "/screens/car/frame.png", foldKey).second
        assertFalse(empty.contentEquals(after))
        // Kept as the car's own log: a new service reads it back.
        assertTrue(File(home, "screens/car.json").readText().contains("module:needs"))
        assertEquals(404, call("GET", "/screens/nowhere/frame.png", foldKey).first)
        server.stop(0)
    }

    @Test fun aThreadsTokenArrangesItsScreensAndNothingElse() {
        val token = ThreadTokens.PREFIX + "t".repeat(48)
        assertEquals(401, call("POST", "/screens/car/canvas", token, JSONObject().put("words", JSONArray(listOf("board")))).first)
        // Registered by the runner's own device.
        assertEquals(200, call("POST", "/tokens", foldKey, JSONObject().put("digest", keyDigest(token)).put("thread", "t1").put("screens", JSONArray(listOf("car")))).first)
        val r = json(call("POST", "/screens/car/canvas", token, JSONObject().put("words", JSONArray(listOf("place", "threads", "card")))))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertTrue(service.screen("car")!!.log.entries.any { it.origin.actor == "agent:t1" && it.request == "thread-t1" })
        // Not another screen, not approvals, not frames, not widgets, not tokens.
        File(home, "screens").mkdirs(); File(home, "screens/desk.json").writeText(JSONObject().put("screen", "desk").put("entries", JSONArray()).toString())
        assertEquals(403, call("POST", "/screens/desk/canvas", token, JSONObject().put("words", JSONArray(listOf("board")))).first)
        for ((m, p) in listOf("GET" to "/approvals", "GET" to "/screens/car/frame.png", "POST" to "/widgets", "POST" to "/tokens", "POST" to "/approvals")) assertEquals("$m $p", 403, call(m, p, token, JSONObject()).first)
        // Never kept in the clear.
        assertFalse(File(home, "thread-tokens.json").readText().contains(token))
        assertEquals(200, call("DELETE", "/tokens/${keyDigest(token)}", foldKey).first)
        assertEquals(401, call("POST", "/screens/car/canvas", token, JSONObject().put("words", JSONArray(listOf("board")))).first)
        server.stop(0)
    }

    @Test fun aWidgetAnAgentInstallsIsCheckedAndWaitsForHisOkBeforeItDraws() {
        val bad = json(call("POST", "/widgets", foldKey, JSONObject().put("name", "x.owl").put("text", "widget {")))
        assertFalse(bad.getBoolean("ok"))
        assertFalse(json(call("POST", "/widgets", foldKey, JSONObject().put("name", "../x.owl").put("text", "widget \"x\" { }"))).getBoolean("ok"))
        val web = json(call("POST", "/widgets", foldKey, JSONObject().put("name", "chart.owl").put("text", "widget \"chart\" {\n  size: 3x2\n  meta { owl: 6 }\n  Web { src: \"https://s.tradingview.com/widgetembed/\" }\n}")))
        assertTrue(web.getBoolean("ok"))
        assertEquals(1, web.getJSONObject("hosts").getJSONArray("service").length())
        val text = "widget \"hello\" {\n  size: 3x1\n  Text { text: \"hello from the library\" }\n}\n"
        val ok = json(call("POST", "/widgets", foldKey, JSONObject().put("name", "hello.owl").put("text", text)))
        assertTrue(ok.toString(), ok.getBoolean("ok"))
        assertEquals(text, File(home, "widgets/hello.owl").readText())
        // In quarantine: asked about, and drawn as waiting.
        assertTrue(gate.fileAsks().any { it.widget == "hello.owl" })
        val screen = service.screen("car")!!
        assertEquals(null, screen.apply(CanvasVerb.Place("hello.owl", ItemKind.Widget, screen.page.pages.first().id, dev.omalauncher.widgets.Cell(0, 0, 4, 1))))
        val waiting = call("GET", "/screens/car/frame.png", foldKey).second
        assertTrue(service.widget("hello.owl") == null)
        gate.approveFile("hello.owl", sha256(text))
        assertTrue(service.widget("hello.owl") != null)
        assertFalse(waiting.contentEquals(call("GET", "/screens/car/frame.png", foldKey).second))
        server.stop(0)
    }

    @Test fun hisSignedOkFromAPairedDeviceAppliesOnce() {
        val file = sha256("widget text")
        val commands = listOf(sha256("date"))
        val nonce = "nonce-" + System.nanoTime()
        val time = System.currentTimeMillis()
        val sig = CommandGate.sign(keyDigest(foldKey).toByteArray(), CommandGate.signedText("fold", "macpro", "w.owl", file, commands, nonce, time))
        val message = JSONObject().put("device", "fold").put("machine", "macpro").put("widget", "w.owl").put("file", file).put("commands", JSONArray(commands)).put("nonce", nonce).put("time", time).put("sig", sig)
        // Sent by another device than the one that signed it: refused.
        caller = "macbook"
        assertEquals(403, call("POST", "/approvals", macbookKey, message).first)
        caller = "fold"
        assertEquals(200, call("POST", "/approvals", foldKey, message).first)
        assertTrue(gate.allowed("w.owl", "date"))
        assertEquals(403, call("POST", "/approvals", foldKey, message).first)
        server.stop(0)
    }

    @Test fun aWidgetAFoldAgentWritesLandsInTheLibraryThroughThePhonesOwnKey() {
        // What PhoneService does for `oma-phone widget-install`: each Mac the phone is paired with, the service its runner
        // advertises, the phone's own key. The MacBook advertises none (no service there): it is never sent the file.
        val machines = listOf(
            dev.omalauncher.assist.MacMachine("macbook", "MacBook", dev.omalauncher.assist.MacRunnerConfig("http://127.0.0.2:8478", macbookKey)),
            dev.omalauncher.assist.MacMachine("macpro", "Mac Pro", dev.omalauncher.assist.MacRunnerConfig("http://127.0.0.1:8478", foldKey)),
        )
        val posted = ArrayList<String>()
        val text = "widget \"fromfold\" {\n  size: 3x1\n  Text { text: \"made on the Fold\" }\n}\n"
        val answer = dev.omalauncher.canvas.installOnSomaService(machines, "fromfold.owl", text,
            post = { url: String, key: String, body: String -> posted += url; dev.omalauncher.canvas.somaPost(url, key, body) },
            advertised = { runner: String, _: String -> if ("127.0.0.2" in runner) null else base })
        assertTrue(answer.toString(), answer.getBoolean("ok"))
        assertEquals("Mac Pro", answer.getString("on"))
        assertEquals(listOf("$base/widgets"), posted)
        assertEquals(text, File(home, "widgets/fromfold.owl").readText())
        assertTrue(gate.fileAsks().any { it.widget == "fromfold.owl" })
        server.stop(0)
    }

    @Test fun tokensAreRegisteredByDigestExpireAndAreRevokedOnlyByWhoRegisteredThem() {
        val token = ThreadTokens.PREFIX + "u".repeat(48)
        // A raw token instead of its digest: refused.
        assertEquals(400, call("POST", "/tokens", foldKey, JSONObject().put("digest", token).put("thread", "t2").put("screens", JSONArray(listOf("car")))).first)
        // Expired: refused at use.
        assertEquals(200, call("POST", "/tokens", foldKey, JSONObject().put("digest", keyDigest(token)).put("thread", "t2").put("screens", JSONArray(listOf("car"))).put("expires", 1000)).first)
        assertEquals(401, call("POST", "/screens/car/canvas", token, JSONObject().put("words", JSONArray(listOf("board")))).first)
        assertEquals(200, call("POST", "/tokens", foldKey, JSONObject().put("digest", keyDigest(token)).put("thread", "t2").put("screens", JSONArray(listOf("car")))).first)
        // Another paired device can't end it.
        caller = "macbook"
        assertEquals(404, call("DELETE", "/tokens/${keyDigest(token)}", macbookKey).first)
        caller = "fold"
        assertEquals(200, call("POST", "/screens/car/canvas", token, JSONObject().put("words", JSONArray(listOf("board")))).first)
        // Nor does a token get /health.
        assertEquals(403, call("GET", "/health", token).first)
        server.stop(0)
    }

    @Test fun anAgentPutsALibraryWidgetOnTheCarAndAReplacedOneStopsRunning() {
        val marker = File(home, "ran.txt")
        val text = "widget \"stamp\" {\n  size: 3x1\n  source s = cmd(\"echo x >> '${marker.path}'\") every 1s\n  Text { text: s }\n}\n"
        assertTrue(json(call("POST", "/widgets", foldKey, JSONObject().put("name", "stamp.owl").put("text", text))).getBoolean("ok"))
        val placed = json(call("POST", "/screens/car/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("place", "stamp.owl", "line", "top-left")))))
        assertTrue(placed.toString(), placed.getBoolean("ok"))
        gate.approveFile("stamp.owl", sha256(text))
        // Drawn, it starts, and its command asks: approved once it has asked.
        val asked = System.currentTimeMillis() + 8_000
        while (gate.commandAsks().isEmpty() && System.currentTimeMillis() < asked) { call("GET", "/screens/car/frame.png", foldKey); Thread.sleep(100) }
        gate.commandAsks().forEach { gate.answer(it, true) }
        val until = System.currentTimeMillis() + 8_000
        while (!marker.exists() && System.currentTimeMillis() < until) { call("GET", "/screens/car/frame.png", foldKey); Thread.sleep(200) }
        assertTrue("the approved widget never ran", marker.exists())
        // Replaced: it waits in quarantine again, and the old one stops running.
        assertTrue(json(call("POST", "/widgets", foldKey, JSONObject().put("name", "stamp.owl").put("text", text + "\n"))).getBoolean("ok"))
        call("GET", "/screens/car/frame.png", foldKey)
        Thread.sleep(1500)
        val lines = marker.readLines().size
        Thread.sleep(2500)
        assertEquals("the replaced widget kept running", lines, marker.readLines().size)
        server.stop(0)
    }

    @Test fun manyRefusedRequestsLeftHangingAndHalfSentHeadersDontHoldTheDoor() {
        // Round 2: as many as the old pool and more, of both kinds, with no key at all.
        val bodiless = (1..32).map { java.net.Socket("127.0.0.1", server.address.port).apply { getOutputStream().write("POST /widgets HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\n".toByteArray()); getOutputStream().flush() } }
        val halfHeaders = (1..32).map { java.net.Socket("127.0.0.1", server.address.port).apply { getOutputStream().write("GET /health HTTP/1.1\r\nHost: x\r\n".toByteArray()); getOutputStream().flush() } }
        Thread.sleep(300)
        val started = System.currentTimeMillis()
        assertEquals(200, call("GET", "/health", foldKey).first)
        assertEquals(200, call("GET", "/screens/car/frame.png", foldKey).first)
        assertTrue("keyed calls waited ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)
        (bodiless + halfHeaders).forEach { it.close() }
        server.stop(0)
    }

    @Test fun badShapesAreRefusedPlainly() {
        assertEquals(400, call("POST", "/screens/car/canvas", foldKey, JSONObject().put("words", JSONArray(listOf(1, 2)))).first)
        assertEquals(400, call("POST", "/tokens", foldKey, JSONObject().put("digest", keyDigest("soma-thread-x")).put("thread", "t").put("screens", "car")).first)
        val nothere = json(call("POST", "/screens/car/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("place", "nothere.owl", "card")))))
        assertFalse(nothere.getBoolean("ok"))
        assertTrue(nothere.getString("error").contains("isn't in the widget library"))
        server.stop(0)
    }

    @Test fun aTokenStopsWorkingWhenItsTimeIsUp() {
        var now = 1_000_000L
        val tokens = ThreadTokens(File(home, "clocked-tokens.json")) { now }
        tokens.register(keyDigest("soma-thread-live"), ThreadGrant("t", setOf("car"), now + 60_000, "fold"))
        assertTrue(tokens.grant("soma-thread-live") != null)
        now += 61_000
        assertEquals(null, tokens.grant("soma-thread-live"))
        server.stop(0)
    }

    @Test fun aRunnerThatAdvertisesAnotherHostIsNeverSentTheKey() {
        val runner = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 4).apply {
            createContext("/health") { ex -> val b = """{"ok":true,"soma":"http://203.0.113.9:8481"}""".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            start()
        }
        assertEquals(null, dev.omalauncher.canvas.advertisedService("http://127.0.0.1:${runner.address.port}", foldKey))
        runner.stop(0)
        server.stop(0)
    }

    @Test fun halfOpenConnectionsAndGuessersDontHoldTheDoor() {
        // Eight sockets that start a request and never finish it.
        val hung = (1..8).map { java.net.Socket("127.0.0.1", server.address.port).apply { getOutputStream().write("POST /widgets HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\n".toByteArray()); getOutputStream().flush() } }
        val started = System.currentTimeMillis()
        assertEquals(200, call("GET", "/health", foldKey).first)
        assertTrue("a keyed call waited ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 2_000)
        hung.forEach { it.close() }
        // A guesser is answered at once, then told to wait.
        val codes = (1..25).map { call("GET", "/health", "guess-$it").first }
        // (The hung sockets, closed, were refusals too: the count includes them.) Refused at once, then 429 from then on.
        val first = codes.indexOf(429)
        assertTrue(codes.toString(), first > 0 && codes.take(first).all { it == 401 } && codes.drop(first).all { it == 429 })
        server.stop(0)
    }

    @Test fun theLaunchAgentIsPrintedForHimNeverInstalled() {
        val plist = SomaService.launchAgent("/Applications/Soma.app/Contents/MacOS/Soma")
        assertTrue(plist.contains("<string>--serve</string>"))
        assertTrue(plist.contains("dev.omalauncher.soma.serve"))
        assertFalse(File(System.getProperty("user.home"), "Library/LaunchAgents/dev.omalauncher.soma.serve.plist").exists() && File(System.getProperty("user.home"), "Library/LaunchAgents/dev.omalauncher.soma.serve.plist").lastModified() > System.currentTimeMillis() - 60_000)
        assertNotEquals("", plist)
        server.stop(0)
    }
}
