package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.oma.OMA_TAB
import dev.omalauncher.canvas.chatHostModules
import dev.omalauncher.canvas.cockpitFeeds
import dev.omalauncher.canvas.moduleProjects
import dev.omalauncher.oma.somaLinkKey
import dev.omalauncher.oma.somaLinkTab
import dev.omalauncher.ui.DesktopBack
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Soma desktop: the phone's chat in a window, on the Macs and the Omarchy boxes (cockpit/DESIGN.md §6). One runs at a
 * time: starting it again, or `soma --toggle` (a Hyprland bind or a macOS shortcut), shows or hides the window that
 * runs. Closing the window hides it; the tray's Quit ends it. Escape is Back: it closes a menu first, then hides.
 */
fun main(args: Array<String>) {
    // `soma canvas …`: Soma's front agent arranging the running Soma's canvas (slice 6). Says its answer and ends; no window.
    if (args.firstOrNull() == "canvas") exitProcess(CanvasDoor.client(args.drop(1)))
    // The canvas service (slice 8a): headless, its own lock, no window. Its LaunchAgent is printed for him to install.
    if ("--serve" in args) return SomaService.run()
    if ("--print-launch-agent" in args) return println(SomaService.launchAgent(ProcessHandle.current().info().command().orElse("soma")))
    if ("--keychain-check" in args) return println(RunnerKeys.check())
    if ("--start-at-login" in args) return println(Login.install())
    if ("--no-start-at-login" in args) return println(Login.remove())
    if ("--register-links" in args) return println(Login.registerLinks())
    // A soma:// link on the command line (Linux's xdg-open passes it so, and so can a terminal) opens its tab.
    val link = args.firstOrNull { somaLinkKey(it) != null }
    // Another Soma holds the lock while it runs (the system lets it go however it ends): that one is told, and this one ends.
    val claimed = runCatching { Single.claim() }.getOrElse { fail("Soma can't write to ${SomaHome.dir}: ${it.message ?: it.javaClass.simpleName}") }
    if (!claimed) {
        if (!Single.handOff(link?.let { "open $it" } ?: if ("--toggle" in args) "toggle" else "show")) fail("Another Soma is running but not answering. Quit it from its tray icon (or end it), then open Soma again.", code = 0)
        exitProcess(0)
    }
    val shown = mutableStateOf(true)
    val wantedTab = mutableStateOf<String?>(null)
    // Set once the engine is up; a link heard before then waits in [early]. Both are only ever touched on the event
    // thread (the listener and the Apple-event handler post there, and so does the code that sets them), so a link that
    // arrives while the engine is being built can't fall between the two. Of links heard before then, the last one wins.
    var opener: ((String) -> Unit)? = null
    var early: String? = link
    fun open(url: String) = opener?.invoke(url) ?: run { early = url }
    // The front agent's door to the canvas: set once the canvas is read; a call before then is refused.
    var door: CanvasDoor? = null
    Single.listen(onCanvas = { line -> door?.handle(line) ?: """{"ok":false,"error":"Soma is still starting. Try again in a moment."}""" }) { said ->
        when {
            said == "toggle" -> shown.value = !shown.value
            said.startsWith("open ") -> open(said.removePrefix("open "))
            else -> shown.value = true
        }
    }
    // On the Mac a link reaches the running app as an Apple event, never on the command line.
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { event -> javax.swing.SwingUtilities.invokeLater { open(event.uri.toString()) } }
    }
    Looks.fonts()
    // City Pop unless he picked another look from the tray (Boss, 2026-10-16: "start with city pop for desktop too").
    Looks.use(SomaHome.read(SomaHome.settings).optString("pack").ifEmpty { "city-pop" })
    val notices = Notices()
    val platform = DesktopThreadsPlatform(notices)
    val engine = MacThreadsEngine(platform)
    // The window's canvas (slice 5), and its modules' one source: the chat's own host, and the slower feeds on a timer.
    val canvas = DesktopCanvas(SomaHome.canvas, runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("desktop")).also { it.load() }
    // Its verbs from the front agent (slice 6), done on the event thread, where the canvas's state lives.
    // Waited on for a while, never for ever: the port's one listener isn't held by a window that has stopped answering.
    door = CanvasDoor { work ->
        val answer = java.util.concurrent.CompletableFuture<org.json.JSONObject>()
        javax.swing.SwingUtilities.invokeLater { answer.complete(runCatching { work(canvas) }.getOrElse { org.json.JSONObject().put("ok", false).put("error", "Soma couldn't do that.") }) }
        runCatching { answer.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { org.json.JSONObject().put("ok", false).put("error", "Soma didn't answer in time; nothing may have changed, or it may yet.") }
    }
    val front = DesktopFrontAgent(engine, platform, notices, door = door, shim = CanvasDoor.shim(SomaHome.bin))
    val host = DesktopChatHost(platform, engine, front, notices)
    canvas.setAside?.let { notices.say("Soma", "The canvas's saved layout couldn't be read, so it was set aside as ${it.name} and the starting page was laid out.") }
    // What widgets wait for his OK on here (slice 7b) is in Needs you beside the threads' asks.
    val gate = dev.omalauncher.widgets.runtime.CommandGate(SomaHome.approved, SomaHome.machine)
    val source = dev.omalauncher.canvas.withWidgetAsks(chatHostModules(host, cockpitFeeds(), handoffs = engine::handoffs, registry = engine::projects), gate)
    val tick = mutableStateOf(0L)
    // Two loops: the look, which only reads what the app holds, never waits on the slower feeds, whose reads can take a while.
    thread(name = "soma-modules", isDaemon = true) {
        while (true) {
            javax.swing.SwingUtilities.invokeLater { tick.value = System.currentTimeMillis() }
            Thread.sleep(MODULES_LOOK_MS)
        }
    }
    thread(name = "soma-feeds", isDaemon = true) {
        while (true) {
            runCatching { source.refresh(moduleProjects(canvas.page.pages.flatMap { p -> p.items.map { it.key } })) }
            javax.swing.SwingUtilities.invokeLater { tick.value = System.currentTimeMillis() }
            Thread.sleep(MODULE_FEEDS_MS)
        }
    }
    // A link shows the window on its tab: a thread's own, or the chat that lives on it. One for no tab known opens nothing.
    javax.swing.SwingUtilities.invokeLater {
        opener = { url ->
            somaLinkTab(url, platform.book())?.let { tab ->
                wantedTab.value = tab.takeIf { it != OMA_TAB }
                shown.value = true
            }
        }
        early?.let { url -> opener?.invoke(url) }
        early = null
    }
    // Keys delivered by `oma-runner pair` go into the Keychain at once, and whenever another arrives. Each import is
    // said, and a file that can't be taken is said once, not at every look.
    thread(name = "soma-keys", isDaemon = true) {
        val told = HashSet<String>()
        while (true) {
            val before = RunnerKeys.machines().map { it.id }
            val taken = RunnerKeys.take()
            taken.added.forEach { notices.say("Soma", it) }
            taken.problems.filter { told.add(it.key) }.forEach { notices.say("Soma", it.text) }
            if (RunnerKeys.machines().map { it.id } != before) engine.refresh()
            Thread.sleep(10_000)
        }
    }
    engine.open()
    application {
        var shown by shown
        var wanted by wantedTab
        remember {
            notices.tray = Tray.make(onOpen = { shown = true }, onQuit = { Single.release(); exitApplication() }, onLook = { id -> Looks.use(id); SomaHome.write(SomaHome.settings, SomaHome.read(SomaHome.settings).put("pack", id)) })
        }
        Window(
            onCloseRequest = { shown = false },
            visible = shown,
            title = "Soma",
            state = rememberWindowState(size = DpSize(1240.dp, 820.dp)),
            onPreviewKeyEvent = { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    if (!DesktopBack.press()) shown = false
                    true
                } else {
                    false
                }
            },
        ) {
            CanvasScreen(canvas, host, source, tick.value, wanted, onTab = { wanted = it })
        }
    }
}

/** How often the modules look again at what the app holds, and how often the slower feeds (herd, pocket, handoffs) are read. */
private const val MODULES_LOOK_MS = 2_000L
private const val MODULE_FEEDS_MS = 30_000L

/**
 * Says why Soma can't start where he will see it (opened from Finder there is no terminal), and ends with [code].
 * Headless, as in a test, it is said on stderr alone.
 */
private fun fail(why: String, code: Int = 1): Nothing {
    System.err.println(why)
    if (!java.awt.GraphicsEnvironment.isHeadless()) runCatching { javax.swing.JOptionPane.showMessageDialog(null, why, "Soma", javax.swing.JOptionPane.WARNING_MESSAGE) }
    exitProcess(code)
}

/**
 * One Soma at a time. The one running holds a lock on [SomaHome.lock], which the system lets go however it ends, and
 * listens on the loopback on a port it writes to [SomaHome.socket]. A Soma started while the lock is held tells that
 * one what it was asked and ends; the port is only trusted when what answers on it says it is Soma.
 */
private object Single {
    private const val HELLO = "soma"
    private var held: java.nio.channels.FileLock? = null

    /** True when this is the only Soma: the lock is now this one's until it ends. Throws when the lock file can't be made. */
    fun claim(): Boolean {
        if (held != null) return true
        val channel = java.io.RandomAccessFile(SomaHome.lock, "rw").channel
        held = runCatching { channel.tryLock() }.getOrNull()
        if (held == null) channel.close()
        return held != null
    }

    /** Tells the Soma that holds the lock to [word] ("show", "toggle" or "open <link>"); true when it answered. One still starting is waited on briefly. */
    fun handOff(word: String): Boolean {
        repeat(20) {
            if (tell(word)) return true
            Thread.sleep(150)
        }
        return false
    }

    private fun tell(word: String): Boolean {
        val port = runCatching { SomaHome.socket.readText().trim().toInt() }.getOrNull() ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                socket.soTimeout = 1000
                socket.getOutputStream().write((word + "\n").toByteArray())
                socket.getInputStream().bufferedReader().readLine() == HELLO
            }
        }.getOrDefault(false)
    }

    fun listen(onCanvas: (String) -> String, onWord: (String) -> Unit) {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        SomaHome.socket.writeText(server.localPort.toString())
        thread(name = "soma-single", isDaemon = true) {
            while (true) {
                val word = runCatching {
                    server.accept().use { client ->
                        // One that connects and says nothing can't hold the door: it is let go after a second.
                        client.soTimeout = 1000
                        val said = client.getInputStream().bufferedReader().readLine()
                        // A canvas call is answered with what it did, and goes no further.
                        if (said?.startsWith(CanvasDoor.WORD) == true) {
                            client.getOutputStream().write((runCatching { onCanvas(said) }.getOrElse { """{"ok":false,"error":"Soma couldn't do that."}""" } + "\n").toByteArray())
                            return@use null
                        }
                        client.getOutputStream().write((HELLO + "\n").toByteArray())
                        said
                    }
                }.getOrNull()?.trim()?.takeIf { it == "show" || it == "toggle" || (it.startsWith("open ") && somaLinkKey(it.removePrefix("open ")) != null) } ?: continue
                javax.swing.SwingUtilities.invokeLater { onWord(word) }
            }
        }
    }

    /** At Quit: the port file goes, so no later program on that port is taken for Soma. */
    fun release() {
        SomaHome.socket.delete()
        runCatching { held?.release() }
    }
}

/** The tray icon: Open, the looks, Quit. Its notifications are Soma's ([Notices]). */
private object Tray {
    fun make(onOpen: () -> Unit, onQuit: () -> Unit, onLook: (String) -> Unit): TrayIcon? {
        if (!SystemTray.isSupported()) return null
        val menu = PopupMenu()
        menu.add(MenuItem("Open Soma").apply { addActionListener { onOpen() } })
        val looks = java.awt.Menu("Look")
        for (id in Looks.ids) looks.add(MenuItem(id).apply { addActionListener { onLook(id) } })
        menu.add(looks)
        menu.addSeparator()
        menu.add(MenuItem("Quit Soma").apply { addActionListener { onQuit() } })
        val image = (Tray::class.java.classLoader.getResource("soma-tray.png")?.let { Toolkit.getDefaultToolkit().getImage(it) }
            ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply { createGraphics().apply { color = java.awt.Color(0x7aa2f7); fillOval(3, 3, 10, 10); dispose() } })
        val icon = TrayIcon(image, "Soma", menu).apply { isImageAutoSize = true; addActionListener { onOpen() } }
        return runCatching { SystemTray.getSystemTray().add(icon); icon }.getOrNull()
    }
}
