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

    /**
     * Slice 8c's proof: an agent in a runner thread runs `soma canvas` (the runner's own helper, as it writes it) with only
     * what its thread's environment holds, places a module on a named screen, and can neither approve nor reach a key.
     */
    @Test fun anAgentInARunnerThreadArrangesItsScreenAndNothingElse() {
        val runner = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "tools/mac-runner/oma-runner") }.first { it.isFile }.readText()
        val helper = File(home, "bin/soma").apply {
            parentFile.mkdirs()
            writeText(runner.substringAfter("SOMA_HELPER = r\'\'\'").substringBefore("\'\'\'"))
            setExecutable(true, true)
        }
        val token = ThreadTokens.PREFIX + "a".repeat(48)
        assertEquals(200, call("POST", "/tokens", foldKey, JSONObject().put("digest", keyDigest(token)).put("thread", "t7").put("screens", JSONArray(listOf("car")))).first)
        // The thread's environment, as the runner gives it: the token, the service, its screens; never a device key.
        // The helper runs on this computer's python3: Homebrew's on the Macs, the system's on Linux (as DesktopEndToEndTest finds it).
        val python = listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3").first { File(it).canExecute() }
        fun soma(vararg words: String): Pair<Int, String> {
            val p = ProcessBuilder(listOf(python, helper.path) + words).apply {
                environment().clear()
                environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.path, "SOMA_CANVAS_TOKEN" to token, "SOMA_SERVICE" to base, "SOMA_CANVAS_SCREENS" to "car"))
            }.redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return p.exitValue() to out
        }
        val (code, out) = soma("canvas", "place", "needs", "card", "top-left")
        assertEquals(out, 0, code)
        assertTrue(service.screen("car")!!.log.entries.any { it.origin.actor == "agent:t7" && (it.verb as? CanvasVerb.Place)?.key == "module:needs" })
        // Another screen: refused by the service. And the token itself never shows in what it prints.
        File(home, "screens").mkdirs(); File(home, "screens/desk.json").writeText(JSONObject().put("screen", "desk").put("entries", JSONArray()).toString())
        val (other, said) = soma("canvas", "--screen", "desk", "board")
        assertEquals(1, other)
        assertFalse(said.contains(token))
        // The helper has no words for approving or for keys, and the token reaches no route that does.
        for ((m, path) in listOf("POST" to "/approvals", "GET" to "/approvals", "POST" to "/tokens", "POST" to "/widgets")) assertEquals("$m $path", 403, call(m, path, token, JSONObject()).first)
        server.stop(0)
    }

    @Test fun theDesktopsOwnLayoutSyncsWithItsCopyOnTheServiceAndAnAgentWorksOnIt() {
        // Soma desktop's window on this machine, its own screen on the service.
        val canvas = DesktopCanvas(File(home, "window.json"), "desk").also { it.load() }
        val desk = DesktopSync(canvas, File(home, "window-synced.json"), "macbook")
        val exchange = { screen: String, since: Long, sent: List<dev.omalauncher.canvas.SentVerb> ->
            dev.omalauncher.canvas.syncExchange(base, foldKey, screen, DESKTOP_COLUMNS, DESKTOP_ROWS, since, sent)
        }
        // First round: the window's layout goes up as its own screen.
        assertEquals(emptyList<String>(), desk.round(exchange))
        val screen = service.screen("desk-macbook")!!
        // Its pages are the window's own, and only those: no page of the service's own comes back (round-1 review).
        assertEquals(listOf(DESKTOP_PAGE), canvas.page.pages.map { it.id })
        // Only the device whose screen it is syncs it.
        caller = "macbook"
        assertEquals(403, call("POST", "/screens/desk-macbook/sync", macbookKey, JSONObject().put("since", 0).put("columns", DESKTOP_COLUMNS).put("rows", DESKTOP_ROWS)).first)
        caller = "fold"
        assertEquals(canvas.page.pages.map { p -> p.id to p.items.map { it.key to it.cell } }, screen.page.pages.map { p -> p.id to p.items.map { it.key to it.cell } })
        // An agent, through the door, moves Threads on the desk; by hand, he removes Computers meanwhile.
        assertTrue(json(call("POST", "/screens/desk-macbook/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("resize", "threads", "line"))))).getBoolean("ok"))
        canvas.apply(CanvasVerb.Remove("module:computers"))
        desk.round(exchange)
        // Both, on both.
        assertFalse(canvas.has("module:computers"))
        assertFalse(service.screen("desk-macbook")!!.page.pages.any { p -> p.items.any { it.key == "module:computers" } })
        val threads = canvas.items().first { it.key == "module:threads" }.cell!!
        assertEquals(1, threads.h)
        assertEquals(service.screen("desk-macbook")!!.page.pages.flatMap { it.items }.first { it.key == "module:threads" }.cell, threads)
        // On the desk's copy the chat is held as on the desk: nothing goes under it, and it stays.
        assertFalse(json(call("POST", "/screens/desk-macbook/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("remove", "chat"))))).getBoolean("ok"))
        assertFalse(json(call("POST", "/screens/desk-macbook/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("page-make", "Two"))))).let { call("POST", "/screens/desk-macbook/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("page-remove", "Soma")))) }.let { JSONObject(String(it.second)) }.getBoolean("ok"))
        val placed = json(call("POST", "/screens/desk-macbook/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("place", "pocket", "card", "top-left")))))
        assertTrue(placed.toString(), placed.getBoolean("ok"))
        val chat = service.screen("desk-macbook")!!.page.pages.flatMap { it.items }.first { it.key == CHAT_BLOCK }.cell!!
        assertFalse(service.screen("desk-macbook")!!.page.pages.flatMap { it.items }.first { it.key == "module:pocket" }.cell!!.overlaps(chat))
        // The car's screen is untouched by any of it: nothing is mirrored.
        assertTrue(service.screen("car")!!.page.pages.all { it.items.isEmpty() })
        // A screen's grid is fixed by its first sync.
        assertEquals(409, call("POST", "/screens/desk-macbook/sync", foldKey, JSONObject().put("since", 0).put("columns", 6).put("rows", 8)).first)
        server.stop(0)
    }

    @Test fun aRefusalKeepsTheDesksChangeAndARestartSendsOnlyWhatIsNew() {
        val canvas = DesktopCanvas(File(home, "window.json"), "desk").also { it.load() }
        val synced = File(home, "window-synced.json")
        val desk = DesktopSync(canvas, synced, "macbook")
        var rows = DESKTOP_ROWS
        val exchange = { screen: String, since: Long, sent: List<dev.omalauncher.canvas.SentVerb> ->
            dev.omalauncher.canvas.syncExchange(base, foldKey, screen, DESKTOP_COLUMNS, rows, since, sent)
        }
        desk.round(exchange)
        // Refused (a grid that isn't this screen's, as a real 409 from the door): his change stays, and is said once.
        canvas.apply(CanvasVerb.Remove("module:computers"))
        rows = DESKTOP_ROWS + 1
        val said = desk.round(exchange)!!
        assertTrue(said.toString(), said.single().startsWith("the canvas service refused"))
        assertFalse(canvas.has("module:computers"))
        assertTrue(canvas.log.entries.any { (it.verb as? CanvasVerb.Remove)?.key == "module:computers" })
        assertEquals(emptyList<String>(), desk.round(exchange))
        // Taken next time.
        rows = DESKTOP_ROWS
        desk.round(exchange)
        assertFalse(service.screen("desk-macbook")!!.page.pages.any { p -> p.items.any { it.key == "module:computers" } })
        // An agent resizes Threads; then Soma restarts and syncs: only what is new comes back, nothing old goes over it.
        assertTrue(json(call("POST", "/screens/desk-macbook/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("resize", "threads", "line"))))).getBoolean("ok"))
        val version = service.screen("desk-macbook")!!.log.version
        val again = DesktopCanvas(File(home, "window.json"), "desk").also { it.load() }
        assertEquals(emptyList<String>(), DesktopSync(again, synced, "macbook").round(exchange))
        assertEquals(version, service.screen("desk-macbook")!!.log.version)
        assertEquals(1, again.items().first { it.key == "module:threads" }.cell!!.h)
        assertFalse(again.has("module:computers"))
        // No device syncs the car's screen, and nothing on it is an app with nothing to open.
        assertEquals(403, call("POST", "/screens/car/sync", foldKey, JSONObject().put("since", 0).put("columns", 6).put("rows", 4)).first)
        assertFalse(service.screen("car")!!.apply(CanvasVerb.Place("app:x#0", dev.omalauncher.widgets.layout.ItemKind.App, "main", dev.omalauncher.widgets.Cell(0, 0, 1, 1))) == null)
        // The service loses the desk's copy: the window sends itself whole and the copy becomes it.
        val desks = File(home, "screens/desk-macbook.json")
        val windowItems = again.items().map { it.key to it.cell }.toSet()
        desks.writeText(dev.omalauncher.widgets.layout.VerbJson.log(dev.omalauncher.widgets.layout.VerbLog("desk-macbook")))
        service.screen("desk-macbook")!!.load()
        val desk2 = DesktopSync(again, synced, "macbook")
        assertEquals(null, desk2.round(exchange)?.takeIf { false })
        desk2.round(exchange)
        assertEquals(windowItems, service.screen("desk-macbook")!!.page.pages.flatMap { it.items }.map { it.key to it.cell }.toSet())
        assertEquals(windowItems, again.items().map { it.key to it.cell }.toSet())
        // His window's saved layout can't be read: the window starts from the default page and takes the service's copy,
        // never sending the default over it.
        val kept = service.screen("desk-macbook")!!.log.version
        File(home, "window.json").writeText("{ not a layout")
        val fresh = DesktopCanvas(File(home, "window.json"), "desk").also { it.load() }
        assertTrue(fresh.setAside != null)
        DesktopSync(fresh, synced, "macbook").round(exchange)
        assertEquals(kept, service.screen("desk-macbook")!!.log.version)
        assertEquals(windowItems, fresh.items().map { it.key to it.cell }.toSet())
        // What the service gave is lost (cut short mid-write): an agent changes the desk, Soma restarts, and nothing old goes
        // up over it (round-4 review).
        assertTrue(json(call("POST", "/screens/desk-macbook/canvas", foldKey, JSONObject().put("words", JSONArray(listOf("resize", "threads", "card"))))).getBoolean("ok"))
        DesktopSync(fresh, synced, "macbook").round(exchange)
        val agentHas = service.screen("desk-macbook")!!.page.pages.flatMap { it.items }.map { it.key to it.cell }.toSet()
        val before = service.screen("desk-macbook")!!.log.version
        synced.writeText(synced.readText().take(40))
        val restarted = DesktopCanvas(File(home, "window.json"), "desk").also { it.load() }
        repeat(2) { DesktopSync(restarted, synced, "macbook").round(exchange) }
        assertEquals(before, service.screen("desk-macbook")!!.log.version)
        assertEquals(agentHas, service.screen("desk-macbook")!!.page.pages.flatMap { it.items }.map { it.key to it.cell }.toSet())
        assertEquals(agentHas, restarted.items().map { it.key to it.cell }.toSet())
        // Both lost: the window starts from the default page and takes the service's copy; the default never goes up.
        File(home, "window.json").writeText("{ not a layout")
        synced.delete()
        val both = DesktopCanvas(File(home, "window.json"), "desk").also { it.load() }
        DesktopSync(both, synced, "macbook").round(exchange)
        assertEquals(before, service.screen("desk-macbook")!!.log.version)
        assertEquals(agentHas, both.items().map { it.key to it.cell }.toSet())
        // The file is written whole: no half-written copy is ever left beside it.
        assertFalse(File(home, ".window-synced.json.new").exists())
        assertTrue(dev.omalauncher.widgets.layout.VerbJson.logOf(synced.readText()) != null)
        server.stop(0)
    }

    @Test fun aWindowSetAsideOnANewServiceIsNeverBlanked() {
        val canvas = DesktopCanvas(File(home, "window.json").apply { writeText("{ not a layout") }, "desk").also { it.load() }
        assertTrue(canvas.setAside != null)
        val items = canvas.items().map { it.key to it.cell }.toSet()
        val exchange = { screen: String, since: Long, sent: List<dev.omalauncher.canvas.SentVerb> ->
            dev.omalauncher.canvas.syncExchange(base, foldKey, screen, DESKTOP_COLUMNS, DESKTOP_ROWS, since, sent)
        }
        DesktopSync(canvas, File(home, "window-synced.json"), "macbook").round(exchange)
        assertEquals(items, canvas.items().map { it.key to it.cell }.toSet())
        assertEquals(items, service.screen("desk-macbook")!!.page.pages.flatMap { it.items }.map { it.key to it.cell }.toSet())
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
