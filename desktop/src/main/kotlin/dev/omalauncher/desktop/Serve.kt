package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.canvas.ModuleView
import dev.omalauncher.canvas.moduleRef
import dev.omalauncher.canvas.moduleSize
import dev.omalauncher.ui.Pack
import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.OwlHost
import dev.omalauncher.widgets.lang.hostRefusals
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.render.OwlScreen
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.runtime.CommandGate
import dev.omalauncher.widgets.runtime.WidgetInstance
import dev.omalauncher.widgets.runtime.WidgetLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * `soma --serve` (Soma Canvas slice 8a, PRD §3): Soma desktop running headless on the Mac Pro, the canvas service. No
 * window, no tray, AWT headless, its own lock (soma-serve.lock) so a windowed Soma can run beside it. It keeps the screens
 * that have no Soma of their own (the car's first), draws their frames with the same shared renderer on a thread of its
 * own, keeps the shared widget library behind the checker and the run-time gate, and answers the agents' tailnet door.
 * It runs as a LaunchAgent in Boss's session (installing that is his to do), so it has his Keychain and draws with nobody
 * at the screen.
 */

/**
 * The service's port on the tailnet. Beside the runner's 8478; 8477 (share-gate), 8479 (herd) and 8480 (herd's pocket)
 * are taken on Boss's Macs (round-1 review found herd on 8479 here). The runner advertises the service's address in its
 * /health, so a device asks the runner rather than relying on this number.
 */
const val SERVE_PORT = 8481

/** The car's screen: its grid is its own (PRD §8: nothing is mirrored between screens). */
const val CAR_SCREEN = "car"
const val CAR_COLUMNS = 8
const val CAR_ROWS = 5

/** A frame's size, in pixels at density 1: the car's browser, landscape. */
const val FRAME_WIDTH = 1280
const val FRAME_HEIGHT = 800

/** The library's file names: a widget's name and ".owl", nothing that reaches outside it. */
private val WIDGET_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.owl")

class SomaService(
    private val home: File,
    private val devices: PairedDevices,
    val source: ModuleSource,
    val gate: CommandGate,
    private val machine: String,
) {
    val tokens = ThreadTokens(File(home, "thread-tokens.json"))

    /** The car's public door (slice 9a): opened by [run] on the loopback for Funnel; its pairing is asked for here. */
    val car = CarDoor(this, home)

    /** Whether the public door answers from outside (SOMA_PUBLIC_URL, the Funnel address), said in /health. */
    var liveness = PublicLiveness(null)
    val library = WidgetLibrary(File(home, "widgets").apply { mkdirs() }, gate)
    private val screens = HashMap<String, ScreenCanvas>()

    /**
     * Frames are drawn here, one at a time, never on a request's own thread; and the widgets run here too. A widget's
     * runtime is made for one thread, the one that draws it (the phone's main thread): its state is made and read there,
     * never across threads (a background pool once read state a frame had made but not yet applied).
     */
    private val frames = Executors.newSingleThreadExecutor { Thread(it, "soma-frames").apply { isDaemon = true } }
    private val widgetScope = CoroutineScope(SupervisorJob() + frames.asCoroutineDispatcher())
    private val widgets = HashMap<String, Pair<String, WidgetInstance>>()

    /** The screen [name], read from its log the first time it is asked for; the car's exists from the start. */
    @Synchronized fun screen(name: String): ScreenCanvas? {
        screens[name]?.let { return it }
        val file = File(home, "screens/$name.json")
        if (name != CAR_SCREEN && !file.exists()) return null
        return ScreenCanvas(file, name, CAR_COLUMNS, CAR_ROWS, machine, widgetKnown = { File(home, "widgets/$it").isFile }).also { it.load(); screens[name] = it }
    }

    /** The running widget for the library file [name], when he has approved that file here; started the first time. */
    @Synchronized fun widget(name: String): WidgetInstance? {
        val text = library.approvedText(name)
        widgets[name]?.takeIf { it.first == text }?.let { return it.second }
        // A file replaced (now in quarantine again) or gone: the old one stops, so nothing of it runs on (round-1 review).
        widgets.remove(name)?.second?.stop()
        if (text == null) return null
        val program = runCatching { Compiler.compile(text) }.getOrNull() ?: return null
        if (hostRefusals(program, OwlHost.Service).isNotEmpty()) return null
        val inst = WidgetInstance(program, DesktopWidgetServices.gated(gate, name, workDir = File(home, "widget-run")), onError = {})
        inst.start(widgetScope) { true }
        widgets[name] = text to inst
        return inst
    }

    /** Why the library widget [name] isn't drawn here: it waits for his OK, it can't be drawn on this screen, or it isn't there. */
    fun whyNot(name: String): String {
        val text = library.approvedText(name) ?: return if (java.io.File(home, "widgets/$name").isFile) "$name waits for your OK here" else "$name isn't in the library"
        val program = runCatching { Compiler.compile(text) }.getOrNull() ?: return "$name doesn't compile"
        return hostRefusals(program, OwlHost.Service).firstOrNull()?.let { "$name can't draw here: $it" } ?: "$name isn't running"
    }

    /** [screen]'s page [page] (its first when null) drawn as a PNG, [width] by [height]. */
    fun frame(screen: ScreenCanvas, page: String?, width: Int = FRAME_WIDTH, height: Int = FRAME_HEIGHT): ByteArray = frames.submit<ByteArray> {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        val scene = ImageComposeScene(width, height, Density(1f)) { ScreenView(this, screen, page) }
        try {
            scene.render(0)
            scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }.get(30, TimeUnit.SECONDS)

    /**
     * A widget file from an agent: checked as `owl check` does (it must compile; what each host can't draw is said), then
     * written into the library, where it waits in quarantine on every machine until he approves it there.
     */
    fun install(name: String, text: String): JSONObject {
        if (!WIDGET_NAME.matches(name)) return JSONObject().put("ok", false).put("error", "A widget's file name is lower-case letters, digits, '.', '_' or '-', ending .owl.")
        if (text.length > 256 * 1024) return JSONObject().put("ok", false).put("error", "A widget file is at most 256 KB.")
        val program = try { Compiler.compile(text) } catch (e: CompileError) {
            return JSONObject().put("ok", false).put("error", "It doesn't compile: ${e.message}")
        }
        val dir = File(home, "widgets").apply { mkdirs() }
        val fresh = File(dir, ".$name.new")
        fresh.writeText(text)
        if (!fresh.renameTo(File(dir, name))) { File(dir, name).delete(); fresh.renameTo(File(dir, name)) }
        library.approvedText(name)
        return JSONObject().put("ok", true).put("widget", name).put("name", program.name)
            .put("hosts", JSONObject().apply { OwlHost.entries.forEach { h -> put(h.id, JSONArray(hostRefusals(program, h))) } })
            .put("waits", "for his OK on each machine before it loads or runs anything there")
    }

    /** The door: started on [address]:[port]. */
    fun open(address: String, port: Int = SERVE_PORT): HttpServer = HttpServer.create(InetSocketAddress(address, port), 64).apply {
        // Enough hands that slow callers can't hold the door: each is cut off by the server's own limits within seconds, and
        // it takes more of them at once than the tailnet's few devices make to fill these.
        executor = Executors.newFixedThreadPool(64) { Thread(it, "soma-serve-http").apply { isDaemon = true } }
        createContext("/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { answer(ex, 500, JSONObject().put("ok", false).put("error", "the service failed")) } } }
        start()
    }

    private fun answer(ex: HttpExchange, code: Int, body: JSONObject) = bytes(ex, code, "application/json", body.toString().toByteArray())

    private fun bytes(ex: HttpExchange, code: Int, type: String, data: ByteArray) {
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun body(ex: HttpExchange): JSONObject? = runCatching {
        val length = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1
        if (length > MAX_BODY) return null
        JSONObject(String(ex.requestBody.readNBytes(MAX_BODY.toInt()), Charsets.UTF_8))
    }.getOrNull()

    /** Recent refusals by address: one that keeps guessing is answered at once with 429, never with a thread held asleep. */
    private val refusals = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    private fun refused(address: String): Boolean {
        val now = System.currentTimeMillis()
        val (count, since) = refusals.compute(address) { _, was -> if (was == null || now - was.second > 60_000) 1 to now else (was.first + 1) to was.second }!!
        return count > 20 && now - since <= 60_000
    }

    /** A body's "words", when they are all strings; null otherwise. */
    private fun words(b: JSONObject?): List<String>? = b?.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.opt(it) as? String ?: return null } }

    private fun handle(ex: HttpExchange) {
        val bearer = ex.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
        val caller = ex.remoteAddress.address.hostAddress
        val device = if (bearer.isNotEmpty() && !bearer.startsWith(ThreadTokens.PREFIX)) devices.caller(bearer, caller) else null
        val thread = if (bearer.startsWith(ThreadTokens.PREFIX)) tokens.grant(bearer) else null
        if (device == null && thread == null) {
            return if (refused(caller)) answer(ex, 429, JSONObject().put("ok", false).put("error", "too many tries; wait a minute"))
            else answer(ex, 401, JSONObject().put("ok", false).put("error", "no"))
        }
        val parts = ex.requestURI.path.split('/').filter { it.isNotEmpty() }
        val query = ex.requestURI.rawQuery.orEmpty().split('&').mapNotNull { q -> q.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        fun no(code: Int, why: String) = answer(ex, code, JSONObject().put("ok", false).put("error", why))
        when {
            // A thread's token reaches verbs on its screens, and nothing else (not even /health).
            thread != null -> when {
                ex.requestMethod == "POST" && parts.size == 3 && parts[0] == "screens" && parts[2] == "canvas" -> {
                    if (parts[1] !in thread.screens) return no(403, "this thread's token isn't for the screen \"${parts[1]}\"")
                    val screen = screen(parts[1]) ?: return no(404, "no screen \"${parts[1]}\"")
                    val words = words(body(ex)) ?: return no(400, "words, as a list of strings")
                    answer(ex, 200, CanvasCommands.run(words, screen, "thread-${thread.thread}", "agent:${thread.thread}"))
                }
                else -> no(403, "a thread's token is for arranging its screens, nothing else")
            }
            ex.requestMethod == "GET" && parts == listOf("health") -> answer(ex, 200, JSONObject().put("ok", true).put("service", "soma-serve").put("machine", machine).put("public", liveness.json()))
            // The car's pairing (slice 9a): a code to show on this device, the car forgotten, or how it stands.
            ex.requestMethod == "POST" && parts == listOf("car", "pairing") -> car.startPairing(device!!).let { (code, until) -> answer(ex, 200, JSONObject().put("ok", true).put("code", code).put("until", until)) }
            ex.requestMethod == "DELETE" && parts == listOf("car", "pairing") -> { car.revoke(); answer(ex, 200, JSONObject().put("ok", true)) }
            ex.requestMethod == "GET" && parts == listOf("car") -> answer(ex, 200, JSONObject().put("ok", true).put("paired", car.paired()).put("frame", car.frameId).put("public", liveness.json()))
            ex.requestMethod == "GET" && parts == listOf("screens") -> answer(ex, 200, JSONObject().put("ok", true).put("screens", JSONArray(listOf(CAR_SCREEN) + (File(home, "screens").list().orEmpty()
                .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.filter { it != CAR_SCREEN && SCREEN_NAME.matches(it) }.sorted()))))
            ex.requestMethod == "GET" && parts.size == 3 && parts[0] == "screens" && parts[2] == "frame.png" -> {
                val screen = screen(parts[1]) ?: return no(404, "no screen \"${parts[1]}\"")
                bytes(ex, 200, "image/png", frame(screen, query["page"]))
            }
            ex.requestMethod == "POST" && parts.size == 3 && parts[0] == "screens" && parts[2] == "canvas" -> {
                val screen = screen(parts[1]) ?: return no(404, "no screen \"${parts[1]}\"")
                val b = body(ex) ?: return no(400, "a JSON body")
                val words = words(b) ?: return no(400, "words, as a list of strings")
                answer(ex, 200, CanvasCommands.run(words, screen, b.optString("request").ifEmpty { "device-$device-${System.currentTimeMillis()}" }, "agent:$device"))
            }
            ex.requestMethod == "POST" && parts == listOf("widgets") -> {
                val b = body(ex) ?: return no(400, "a JSON body")
                answer(ex, 200, install(b.optString("name"), b.optString("text")))
            }
            // A runner registers the token it minted for a thread, and revokes it when the thread ends.
            ex.requestMethod == "POST" && parts == listOf("tokens") -> {
                val b = body(ex) ?: return no(400, "a JSON body")
                val screens = b.optJSONArray("screens")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }
                    ?.takeIf { it.isNotEmpty() } ?: return no(400, "the screens the token is for, as a list")
                runCatching { tokens.register(b.getString("digest"), ThreadGrant(b.getString("thread"), screens, b.optLong("expires", System.currentTimeMillis() + ThreadTokens.DAY), device!!)) }
                    .onSuccess { answer(ex, 200, JSONObject().put("ok", true)) }.onFailure { no(400, it.message ?: "a digest, a thread and its screens") }
            }
            // Only the device that registered a token revokes it.
            ex.requestMethod == "DELETE" && parts.size == 2 && parts[0] == "tokens" ->
                if (tokens.revoke(parts[1], device!!)) answer(ex, 200, JSONObject().put("ok", true)) else no(404, "no token of yours by that digest")
            // His OK for this screenless machine, tapped on a paired device. What makes it his is the door: the paired device's
            // own key, from that very device (whois), naming itself as the signer. The signature, keyed with that device's key
            // digest (the only form of the key kept here), binds the approval's fields together; it is no proof on its own:
            // anyone who can read the runner's table could make one, so nothing trusts it without the door (round-1 review).
            ex.requestMethod == "POST" && parts == listOf("approvals") -> {
                val b = body(ex) ?: return no(400, "a JSON body")
                if (b.optString("device") != device) return no(403, "an approval is sent by the device that signed it")
                val why = gate.applySigned(b.toString()) { d -> devices.digestOf(d)?.toByteArray() }
                answer(ex, if (why == null) 200 else 403, if (why == null) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("error", why))
            }
            // What waits for his OK here, for a paired device to show and sign.
            ex.requestMethod == "GET" && parts == listOf("approvals") -> answer(ex, 200, JSONObject().put("ok", true)
                .put("files", JSONArray(gate.fileAsks().map { JSONObject().put("widget", it.widget).put("file", it.fileHash) }))
                .put("commands", JSONArray(gate.commandAsks().map { JSONObject().put("widget", it.widget).put("command", it.command).put("hash", it.hash) })))
            else -> no(404, "nothing here")
        }
    }

    companion object {
        /** The service's lock, held as long as the process runs (a field, so nothing collects it). */
        @Volatile private var held: java.nio.channels.FileLock? = null

        /** A request's body, at most. */
        private const val MAX_BODY = 512L * 1024

        /** A screen's name, as kept in screens/. */
        private val SCREEN_NAME = Regex("[a-z0-9][a-z0-9-]{0,39}")

        init {
            // The JDK server's own limits: a request not read whole in 10 s, or answered in 60, is dropped, and idle
            // connections go after 10 s, so half-open sockets can't hold the door (round-1 review froze it with four).
            // A real device sends a request in milliseconds: one not read whole in 3 s is dropped.
            System.setProperty("sun.net.httpserver.maxReqTime", "3")
            System.setProperty("sun.net.httpserver.maxRspTime", "60")
            System.setProperty("sun.net.httpserver.idleInterval", "10")
            System.setProperty("sun.net.httpserver.maxIdleConnections", "32")
            // An answer never waits to read a body nobody will use (round-2 review: a refused POST with a Content-Length held
            // its thread while the server drained a body that never came).
            System.setProperty("sun.net.httpserver.drainAmount", "0")
        }

        /**
         * Runs the service until it is ended: headless, under its own lock, on this Mac's tailnet address (the loopback when
         * SOMA_SERVE_BIND is 127.0.0.1, for a test, where SOMA_SERVE_WHOIS_FILE says who calls).
         */
        fun run() {
            System.setProperty("java.awt.headless", "true")
            OwlScreen.host = OwlHost.Service
            val home = SomaHome.dir
            val lock = runCatching { java.io.RandomAccessFile(File(home, "soma-serve.lock"), "rw").channel.tryLock() }.getOrNull()?.also { held = it }
                ?: run { System.err.println("soma --serve: another service holds ${File(home, "soma-serve.lock")}"); kotlin.system.exitProcess(1) }
            val loopback = System.getenv("SOMA_SERVE_BIND") == "127.0.0.1"
            val whoisFile = System.getenv("SOMA_SERVE_WHOIS_FILE")?.takeIf { loopback }
            val whois: (String) -> String? = if (whoisFile != null) { a -> runCatching { JSONObject(File(whoisFile).readText()).optString(a).ifEmpty { null } }.getOrNull() } else TailscaleWhois()::name
            val keys = File(System.getenv("OMA_RUNNER_KEYS") ?: (System.getProperty("user.home") + "/agents/claude-code/oma-runner.keys.json"))
            val machine = SomaHome.machine
            Looks.use(SomaHome.read(SomaHome.settings).optString("pack").ifEmpty { "city-pop" })
            val notices = Notices()
            val platform = DesktopThreadsPlatform(notices)
            val engine = dev.omalauncher.assist.MacThreadsEngine(platform)
            val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices), notices)
            val gate = CommandGate(SomaHome.approved, machine)
            val source = dev.omalauncher.canvas.withWidgetAsks(dev.omalauncher.canvas.chatHostModules(host, dev.omalauncher.canvas.cockpitFeeds(), handoffs = engine::handoffs, registry = engine::projects), gate)
            val service = SomaService(home, PairedDevices(keys, whois), source, gate, machine)
            // A trial on the loopback can leave his runners alone.
            if (System.getenv("SOMA_SERVE_NO_RUNNERS") != "1") engine.open()
            kotlin.concurrent.thread(name = "soma-feeds", isDaemon = true) {
                while (true) {
                    runCatching { source.refresh(emptyList()) }
                    Thread.sleep(30_000)
                }
            }
            var address = if (loopback) "127.0.0.1" else null
            while (address == null) {
                address = TailscaleWhois.address()
                if (address == null) { System.err.println("soma --serve: no Tailscale address yet; waiting"); Thread.sleep(10_000) }
            }
            val port = System.getenv("SOMA_SERVE_PORT")?.toIntOrNull() ?: SERVE_PORT
            service.open(address, port)
            // The car's door, on the loopback only: Funnel publishes it (Boss turns Funnel on), nothing else reaches it.
            val carPort = System.getenv("SOMA_CAR_PORT")?.toIntOrNull() ?: CarDoor.CAR_PORT
            service.car.open("127.0.0.1", carPort)
            service.liveness = PublicLiveness(System.getenv("SOMA_PUBLIC_URL"))
            kotlin.concurrent.thread(name = "soma-public-liveness", isDaemon = true) {
                while (true) {
                    service.liveness.check()
                    if (service.liveness.up == false) System.err.println("soma --serve: the public door (${System.getenv("SOMA_PUBLIC_URL")}) isn't answering; is Funnel on?")
                    Thread.sleep(60_000)
                }
            }
            println("soma --serve: on $address:$port, the car's door on 127.0.0.1:$carPort, lock held: ${lock.isValid}")
            Thread.currentThread().join()
        }

        /** The LaunchAgent that runs the service in his session: printed for him to install, never installed from here. */
        fun launchAgent(command: String): String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>Label</key><string>dev.omalauncher.soma.serve</string>
              <key>ProgramArguments</key><array><string>$command</string><string>--serve</string></array>
              <key>RunAtLoad</key><true/>
              <key>KeepAlive</key><true/>
              <key>ProcessType</key><string>Interactive</string>
              <key>StandardOutPath</key><string>${System.getProperty("user.home")}/Library/Logs/soma-serve.log</string>
              <key>StandardErrorPath</key><string>${System.getProperty("user.home")}/Library/Logs/soma-serve.log</string>
            </dict></plist>
        """.trimIndent()
    }
}

/** A screen's page as the service draws it: its modules and widgets on the screen's own grid, City Pop, nothing else. */
@Composable
fun ScreenView(
    service: SomaService, screen: ScreenCanvas, pageId: String?, source: ModuleSource = service.source, now: Long = System.currentTimeMillis(),
    /** Whether a widget's own taps act (its commands): never on the car, whose taps reach approvals and nothing else. */
    widgetsAct: Boolean = true,
) {
    val page = screen.page.pages.firstOrNull { it.id == pageId } ?: screen.page.pages.firstOrNull()
    BoxWithConstraints(Modifier.fillMaxSize().background(Pack.background)) {
        val gap = 8.dp
        val cellW = (maxWidth - gap * (screen.columns + 1)) / screen.columns
        val cellH = (maxHeight - gap * (screen.rows + 1)) / screen.rows
        for (item in page?.items.orEmpty()) {
            val c = item.cell ?: continue
            val m = Modifier.offset(gap + (cellW + gap) * c.x, gap + (cellH + gap) * c.y).size(cellW * c.w + gap * (c.w - 1), cellH * c.h + gap * (c.h - 1))
            when {
                item.kind == ItemKind.Module -> moduleRef(item.key)?.let { ref ->
                    ModuleView(ref, moduleSize(c.w, c.h, screen.columns), source, now, m, onOpen = {}, onNew = {})
                }
                item.kind == ItemKind.Widget -> {
                    val inst = service.widget(item.key)
                    val why = if (inst == null) service.whyNot(item.key) else null
                    if (inst != null) Box(m) {
                        WidgetContent(inst, Modifier.fillMaxSize(), cells = c.w to c.h)
                        // Above it, something that takes every pointer and does nothing: the widget never sees the tap.
                        if (!widgetsAct) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
                    }
                    else Box(m, contentAlignment = Alignment.Center) { BasicText(why!!, style = Pack.mono(Pack.size.small, Pack.muted)) }
                }
            }
        }
    }
}
