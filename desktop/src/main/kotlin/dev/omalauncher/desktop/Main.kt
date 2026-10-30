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
import dev.omalauncher.oma.ChatDrafts
import dev.omalauncher.oma.OMA_TAB
import dev.omalauncher.oma.OmaChatScreen
import dev.omalauncher.ui.Pack
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import dev.omalauncher.oma.LocalTopBarExtra
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.omalauncher.canvas.chatHostModules
import dev.omalauncher.canvas.cockpitFeeds
import dev.omalauncher.canvas.moduleProjects
import dev.omalauncher.oma.somaLinkKey
import dev.omalauncher.oma.somaLinkTab
import dev.omalauncher.ui.BackStack
import dev.omalauncher.ui.LocalBackStack
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Soma desktop: the phone's chat in a window, on the Macs and the Omarchy boxes (cockpit/DESIGN.md §6). One runs at a
 * time: starting it again, or `soma --toggle` (a Hyprland bind or a macOS shortcut), shows or hides the window that
 * runs. Closing the window hides it; the tray's Quit ends it. Escape closes the open menu or panel, never the window.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
fun main(args: Array<String>) {
    // `soma canvas …`: Soma's front agent arranging the running Soma's canvas (slice 6). Says its answer and ends; no window.
    if (args.firstOrNull() == "canvas") exitProcess(CanvasDoor.client(args.drop(1)))
    if (args.firstOrNull() == "front") {
        val front = DesktopFrontAgent.found()
        exitProcess(DesktopFrontAgent.choose(args.drop(1), SomaHome.dir, front.first, front.second, front.third, ::println))
    }
    // The canvas service (slice 8a): headless, its own lock, no window. Its LaunchAgent is printed for him to install.
    if ("--serve" in args) return SomaService.run()
    if ("--print-launch-agent" in args) return println(SomaService.launchAgent(ProcessHandle.current().info().command().orElse("soma"),
        listOfNotNull(System.getenv("SOMA_PUBLIC_URL")?.let { "SOMA_PUBLIC_URL" to it }).toMap()))
    if ("--mint-runner-key" in args) return println(SomaService.mintRunnerKey())
    if ("--keychain-check" in args) return println(RunnerKeys.check())
    // `soma check`: scratch chats on this Mac's runner, made, answered, approved and stopped with Soma's own key (Check.kt).
    // Two-way roads (Boss, 2026-10-28): every leg between this Soma, the other Somas and every runner, by name.
    if (args.firstOrNull() == "--mesh" || args.firstOrNull() == "mesh") exitProcess(when (args.getOrNull(1)) {
        "--prove" -> Mesh.prove(::println)
        "--read" -> Mesh.read(::println)
        "--list" -> Mesh.list(::println)
        else -> Mesh.run(::println)
    })
    if (args.firstOrNull() == "check") exitProcess(if (args.getOrNull(1) == "stop" && args.size == 3) Check.stop(args[2], ::println) else Check.run(::println))
    if ("--start-at-login" in args) return println(Login.install())
    if ("--no-start-at-login" in args) return println(Login.remove())
    if ("--register-links" in args) return println(Login.registerLinks())
    // A soma:// link on the command line (Linux's xdg-open passes it so, and so can a terminal) opens its tab.
    val link = args.firstOrNull { somaLinkKey(it) != null }
    // Another Soma holds the lock while it runs (the system lets it go however it ends): that one is told, and this one ends.
    val claimed = runCatching { Single.claim() }.getOrElse { fail("Soma can't write to ${SomaHome.dir}: ${it.message ?: it.javaClass.simpleName}") }
    if (!claimed) {
        if (!Single.handOff(link?.let { "open $it" } ?: if ("--toggle" in args) "toggle" else if ("--settings" in args) "settings" else "show")) fail("Another Soma is running but not answering. Quit it from its tray icon (or end it), then open Soma again.", code = 0)
        exitProcess(0)
    }
    val shown = mutableStateOf(true)
    // Its settings, in a window of their own (Boss, 2026-10-29: "there's actually no settings button on the desktop apps"):
    // the tray's Settings…, the Mac's Soma > Settings… (Cmd+,), Ctrl+, in a window, and `soma --settings`.
    val settingsShown = mutableStateOf("--settings" in args)
    val settingsBring = mutableStateOf(0)
    fun showSettings() {
        settingsShown.value = true
        settingsBring.value++
    }
    // Bumped by every explicit open (tray, Dock, `soma`, a link, the dashboard): the chat window is shown, un-minimized
    // and raised even when it is marked shown already (the MacBook's fix of 2026-10-21, 0b159117, brought back here).
    val bring = mutableStateOf(0)
    fun showWindow() {
        shown.value = true
        bring.value++
    }
    // The dashboard (the canvas of modules and widgets) in a window of its own, opened from the tray; it remembers whether it was open.
    val dashboard = mutableStateOf(SomaHome.read(SomaHome.settings).optBoolean("dashboard"))
    val wantedTab = mutableStateOf<String?>(null)
    // Set once the engine is up; a link heard before then waits in [early]. Both are only ever touched on the event
    // thread (the listener and the Apple-event handler post there, and so does the code that sets them), so a link that
    // arrives while the engine is being built can't fall between the two. Of links heard before then, the last one wins.
    var opener: ((String) -> Unit)? = null
    var early: String? = link
    fun open(url: String) = opener?.invoke(url) ?: run { early = url }
    // The front agent's door to the canvas: set once the canvas is read; a call before then is refused.
    var door: CanvasDoor? = null
    // The Linux tray's items (LinuxTray), done by the very actions the Mac tray's are; set once the window's tray is made.
    var trayDo: ((String) -> Unit)? = null
    Single.listen(onCanvas = { line -> door?.handle(line) ?: """{"ok":false,"error":"Soma is still starting. Try again in a moment."}""" }, onTray = { item -> trayDo?.invoke(item) }) { said ->
        when {
            said == "toggle" -> if (shown.value) shown.value = false else showWindow()
            said == "settings" -> showSettings()
            said.startsWith("open ") -> open(said.removePrefix("open "))
            else -> showWindow()
        }
    }
    // On the Mac a link reaches the running app as an Apple event, never on the command line.
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { event -> javax.swing.SwingUtilities.invokeLater { open(event.uri.toString()) } }
        // A click on Soma in the Dock (or `open Soma.app` while it runs) is a reopen event to this process, not a new
        // start: it shows the chat window (Boss, 2026-10-28: "soma window on mac pro disappeared again").
        desktop.addAppEventListener(somaReopenListener(::showWindow))
        // The Mac's own Soma > Settings… with Cmd+, in the app menu.
        if (desktop.isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler { javax.swing.SwingUtilities.invokeLater(::showSettings) }
    }
    Looks.fonts()
    // City Pop unless he picked another look from the tray (Boss, 2026-10-16: "start with city pop for desktop too").
    Looks.use(SomaHome.read(SomaHome.settings).optString("pack").ifEmpty { "city-pop" })
    val notices = Notices()
    val platform = DesktopThreadsPlatform(notices)
    // Pictures copied in and never sent (Soma quit or was ended first) go, as the phone sweeps its own at start.
    thread(name = "soma-drafts", isDaemon = true) { runCatching { ChatDrafts.of(SomaHome.dir).sweep(platform.chatLog().tasks) } }
    val engine = MacThreadsEngine(platform)
    // The window's canvas (slice 5), and its modules' one source: the chat's own host, and the slower feeds on a timer.
    val canvas = DesktopCanvas(SomaHome.canvas, runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("desktop"), chat = false).also { it.load() }
    // Its verbs from the front agent (slice 6), done on the event thread, where the canvas's state lives.
    // Waited on for a while, never for ever: the port's one listener isn't held by a window that has stopped answering.
    // Templates and LAYOUT.md: the service's shelf when a runner advertises one, else this Soma's own folder. Never on the window's thread.
    val shelf = { RemoteShelf.of(platform.machines()) ?: FileShelf(SomaHome.dir) }
    door = CanvasDoor(shelf = shelf) { work ->
        val answer = java.util.concurrent.CompletableFuture<org.json.JSONObject>()
        javax.swing.SwingUtilities.invokeLater { answer.complete(runCatching { work(canvas) }.getOrElse { org.json.JSONObject().put("ok", false).put("error", "Soma couldn't do that.") }) }
        runCatching { answer.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { org.json.JSONObject().put("ok", false).put("error", "Soma didn't answer in time; nothing may have changed, or it may yet.") }
    }
    val front = DesktopFrontAgent(engine, platform, notices, door = door, shim = CanvasDoor.shim(SomaHome.bin))
    val host = DesktopChatHost(platform, engine, front, notices)
    canvas.setAside?.let { notices.say("Soma", "The canvas's saved layout couldn't be read, so it was set aside as ${it.name} and the starting page was laid out.") }
    // What widgets wait for his OK on here (slice 7b) is in Needs you beside the threads' asks.
    val gate = dev.omalauncher.widgets.runtime.CommandGate(SomaHome.approved, SomaHome.machine)
    val windowWidgets = WindowWidgets(SomaHome.dir, gate)
    // The dashboard's notes, stickers and ink, kept in Soma's folder as this window's own (desktop slice 5b).
    val scraps = DesktopScraps(canvas).also { s -> canvas.scrapCells = s::cellsUnder }
    val source = dev.omalauncher.canvas.withWidgetAsks(chatHostModules(host, cockpitFeeds(), handoffs = engine::handoffs, registry = engine::projects), gate)
    val tick = mutableStateOf(0L)
    val desk = DesktopSync(canvas, File(SomaHome.dir, "canvas-synced.json"), SomaHome.machine)
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
            // Sync (slice 8b): this window's layout with the service's copy of it, when a runner says where the service is.
            // What the service had to drop or move (a late change to a page gone, cells taken by then) is said, once each.
            runCatching { DesktopSync.overTheTailnet(platform.machines())?.let { exchange -> desk.round(exchange) { work -> javax.swing.SwingUtilities.invokeAndWait(work) } }?.forEach { notices.say("Soma", "The canvas: $it") } }
            javax.swing.SwingUtilities.invokeLater { tick.value = System.currentTimeMillis() }
            Thread.sleep(MODULE_FEEDS_MS)
        }
    }
    // A link shows the window on its tab: a thread's own, or the chat that lives on it. One for no tab known opens nothing.
    javax.swing.SwingUtilities.invokeLater {
        opener = { url ->
            somaLinkTab(url, platform.book())?.let { tab ->
                wantedTab.value = tab.takeIf { it != OMA_TAB }
                showWindow()
            }
        }
        early?.let { url -> opener?.invoke(url) }
        early = null
    }
    // Keys delivered by `oma-runner pair` go into the Keychain at once, and whenever another arrives. Each import is
    // said, and a file that can't be taken is said once, not at every look.
    // This thread alone reads the Keychain (RunnerKeys.read), at the start and every ten seconds: the window and the
    // engine use what it last read, so a Keychain prompt waiting for his OK never holds the window back.
    thread(name = "soma-keys", isDaemon = true) {
        val told = HashSet<String>()
        while (true) {
            val before = RunnerKeys.known
            val taken = RunnerKeys.take()
            taken.added.forEach { notices.say("Soma", it) }
            taken.problems.filter { told.add(it.key) }.forEach { notices.say("Soma", it.text) }
            if (RunnerKeys.read().map { it.id to it.config.key } != before.map { it.id to it.config.key }) engine.refresh()
            Thread.sleep(10_000)
        }
    }
    engine.open()
    // The update pill (Updates.kt): what the last update came to, said once; then the Mac Pro's published build, asked
    // after the keys thread's first read and every half hour. Nothing installs until he taps it.
    var quitNow: (() -> Unit)? = null
    val updateDir = File(SomaHome.dir, "update")
    val updater = Updater(DesktopUpdateHands({ RunnerKeys.known }, updateDir, quitting = { javax.swing.SwingUtilities.invokeLater { quitNow?.invoke() } }), updateDir)
    thread(name = "soma-update", isDaemon = true) {
        runCatching { updater.markStarted() }
        // What the last update came to, and the published build, once the keys thread has read keys; then every half hour.
        while (RunnerKeys.known.isEmpty()) Thread.sleep(5_000)
        runCatching { updater.reportAtStart() }
        while (true) {
            runCatching { updater.check() }
            Thread.sleep(UPDATE_CHECK_MS)
        }
    }
    application {
        var shown by shown
        var wanted by wantedTab
        var dashboardShown by dashboard
        fun showDashboard(on: Boolean) { dashboardShown = on; SomaHome.write(SomaHome.settings, SomaHome.read(SomaHome.settings).put("dashboard", on)) }
        remember {
            val quit = { scraps.close(); LinuxTray.stop(); Single.release(); exitApplication() }
            quitNow = quit
            // The service is asked off the tray's thread; the code or what went wrong comes back as a notice.
            val car = { pair: Boolean -> kotlin.concurrent.thread(name = "soma-car-pairing", isDaemon = true) {
                notices.say("Soma", if (pair) CarPairing.start(platform.machines()) else CarPairing.end(platform.machines()))
            }; Unit }
            val grok = { connect: Boolean -> kotlin.concurrent.thread(name = "soma-grok-connect", isDaemon = true) {
                if (!connect) return@thread notices.say("Soma", GrokConnecting.end(platform.machines()))
                val (said, details) = GrokConnecting.start(platform.machines())
                val copied = details?.let { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(it), null) }.isSuccess }
                // Said as it is: when the clipboard couldn't take them, the details go in the notice, cut as it may be.
                notices.say("Soma", if (copied == false) said.substringBefore(" Grok's connector details") + " " + details else said)
            }; Unit }
            notices.tray = Tray.make(onOpen = ::showWindow, onDashboard = { showDashboard(true) }, onSettings = ::showSettings, onQuit = quit, onLook = Looks::pick, onCar = car, onGrok = grok)
            // The Mac tray's items, said by the Linux tray (two-way roads: the same menu on both).
            trayDo = { item ->
                when {
                    item == "show" -> showWindow()
                    item == "dashboard" -> showDashboard(true)
                    item == "settings" -> showSettings()
                    item == "quit" -> quit()
                    item.startsWith("look ") -> Looks.pick(item.removePrefix("look "))
                    item.startsWith("car ") -> car(item == "car pair")
                    item.startsWith("grok ") -> grok(item == "grok connect")
                }
            }
            // Java's tray doesn't show on Omarchy's Waybar: there, a StatusNotifierItem helper carries the same menu.
            if (notices.tray == null) LinuxTray.start(Single.port)
        }
        // Each window answers Escape from its own stack: an Escape in one never closes something in the other.
        val chatBack = remember { BackStack() }
        val dashboardBack = remember { BackStack() }
        val dashboardState = rememberWindowState(size = DpSize(1240.dp, 820.dp))
        // The dashboard first, so at a start with both open the chat window is in front of it.
        if (dashboardShown) Window(
            onCloseRequest = { scraps.flush(); showDashboard(false) },
            title = "Soma dashboard",
            state = dashboardState,
            onPreviewKeyEvent = { e -> settingsKey(e, ::showSettings) || pageKeys(e, canvas) || somaEscape(e, dashboardBack) },
        ) {
            // A pinch on a trackpad shows the pages small, and spreading them shows the page again, as on the phone (slice 5c).
            androidx.compose.runtime.DisposableEffect(window) { onDispose(Pinch.attach(window.rootPane) { inward -> canvas.overview = inward }) }
            CompositionLocalProvider(LocalBackStack provides dashboardBack) {
                // A module's thread opens in the chat window, brought forward.
                Box(Modifier.fillMaxSize()) {
                    CanvasScreen(canvas, host, source, tick.value, wanted, onTab = { wanted = it; showWindow() }, chatElsewhere = ::showWindow, widgets = windowWidgets, scraps = scraps, shelf = shelf, pagesOverview = true)
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)) { UpdatePill(updater) }
                }
            }
        }
        var settingsOpen by settingsShown
        val settingsBack = remember { BackStack() }
        if (settingsOpen) Window(
            onCloseRequest = { settingsOpen = false },
            title = "Soma settings",
            state = rememberWindowState(size = DpSize(640.dp, 720.dp)),
            onPreviewKeyEvent = { e -> somaEscape(e, settingsBack) },
        ) {
            // Brought forward each time it is asked for, as the chat window is.
            androidx.compose.runtime.LaunchedEffect(settingsBring.value) { window.toFront(); window.requestFocus() }
            CompositionLocalProvider(LocalBackStack provides settingsBack) { SomaSettings(onClose = { settingsOpen = false }) }
        }
        Window(
            onCloseRequest = { shown = false },
            visible = shown,
            title = "Soma",
            state = rememberWindowState(size = DpSize(1240.dp, 820.dp)),
            onPreviewKeyEvent = { e ->
                // A picture on the clipboard (a screenshot, or image files copied in Finder) is pasted into the chat as one
                // picked; anything else pastes as before.
                if (e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isMetaPressed || e.isCtrlPressed) && host.arrived(picturesOnClipboard())) true
                else settingsKey(e, ::showSettings) || somaEscape(e, chatBack)
            },
        ) {
            // Each time it is shown, it comes to the screen he is on (Boss, 2026-10-19: after a restart macOS put it on
            // the other monitor's Space, and show only made it visible there).
            androidx.compose.runtime.LaunchedEffect(shown, bring.value) { if (shown) toPointerScreen(window) }
            // The unfolded phone's chat (Boss, 2026-10-28): two panes once there is an artifact, a question or the files beside it.
            CompositionLocalProvider(LocalBackStack provides chatBack) {
                // Pictures dropped on the window go into the chat as picked ones.
                val drops = remember {
                    object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                        override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean = host.arrived(picturesIn(event.awtTransferable))
                    }
                }
                Box(Modifier.fillMaxSize().background(Pack.background).dragAndDropTarget(shouldStartDragAndDrop = { event ->
                    val t = event.awtTransferable
                    t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor) || t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)
                }, target = drops)) {
                    CompositionLocalProvider(LocalTopBarExtra provides { UpdatePill(updater) }) {
                        OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } })
                    }
                }
            }
        }
    }
}

/**
 * The dashboard's page keys (desktop slice 5c): Cmd-0 shows the pages small or the page again, Cmd-1…9 the page by its
 * place. Ctrl as well, for the Linux jar.
 */
internal fun pageKeys(event: androidx.compose.ui.input.key.KeyEvent, canvas: DesktopCanvas): Boolean {
    if (event.type != KeyEventType.KeyDown || !(event.isMetaPressed || event.isCtrlPressed) || event.isShiftPressed || event.isAltPressed) return false
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    return when (val n = digits.indexOf(event.key)) {
        -1 -> false
        0 -> { canvas.overview = !canvas.overview; true }
        else -> canvas.jump(n)
    }
}

/** Whether [word] is one a second `soma` may hand the running one: show, toggle, settings, or open a soma:// link. */
internal fun handOffWord(word: String): Boolean =
    word == "show" || word == "toggle" || word == "settings" || (word.startsWith("open ") && somaLinkKey(word.removePrefix("open ")) != null)

/** Cmd+, on the Mac, Ctrl+, on Linux: Soma's settings ([open]), from any of its windows. */
internal fun settingsKey(event: androidx.compose.ui.input.key.KeyEvent, open: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Comma || !(event.isMetaPressed || event.isCtrlPressed) || event.isShiftPressed || event.isAltPressed) return false
    open()
    return true
}

/**
 * Escape belongs to the window's innermost open menu or panel ([back]); with none open it is not taken, and it never hides
 * a window (the MacBook's fix of 2026-10-21, 0b159117: Escape hid Soma, and he thought it had gone). Closing hides it.
 */
internal fun somaEscape(event: androidx.compose.ui.input.key.KeyEvent, back: BackStack): Boolean =
    event.type == KeyEventType.KeyDown && event.key == Key.Escape && back.press()

/** macOS's reopen event (a Dock click while Soma runs), handed to the window's thread. */
internal fun somaReopenListener(show: () -> Unit) = java.awt.desktop.AppReopenedListener { javax.swing.SwingUtilities.invokeLater(show) }

/**
 * Brings [window] to the screen under the pointer, centered there, unless most of it is on that screen already, and to the
 * front. A window moved onto a monitor goes to that monitor's Space in front.
 */
internal fun toPointerScreen(window: java.awt.Window) {
    // Minimized, it is restored first; then it comes to the pointer's screen and the front.
    if (window is java.awt.Frame) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
    window.isVisible = true
    val pointer = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
    val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
    val screen = screens.firstOrNull { pointer != null && it.contains(pointer) } ?: screens.firstOrNull()
    if (screen != null) {
        val at = placeOn(window.bounds, screen)
        if (at != window.location) window.location = at
    }
    window.toFront()
    window.requestFocus()
}

/** Where a window at [bounds] goes on [screen]: where it is when its center is on that screen already, else centered on it. */
internal fun placeOn(bounds: java.awt.Rectangle, screen: java.awt.Rectangle): java.awt.Point =
    if (screen.contains(bounds.centerX, bounds.centerY)) bounds.location
    else java.awt.Point(screen.x + maxOf(0, (screen.width - bounds.width) / 2), screen.y + maxOf(0, (screen.height - bounds.height) / 2))

/** How often the modules look again at what the app holds, and how often the slower feeds (herd, pocket, handoffs) are read. */
private const val MODULES_LOOK_MS = 2_000L

/** How often the published desktop build is asked for (the update pill). */
private const val UPDATE_CHECK_MS = 30 * 60_000L
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

    /** Tells the Soma that holds the lock to [word] ("show", "toggle", "settings" or "open <link>"); true when it answered. One still starting is waited on briefly. */
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

    /** The port this Soma listens on, once [listen] has started. */
    var port = 0
        private set

    fun listen(onCanvas: (String) -> String, onTray: (String) -> Unit = {}, onWord: (String) -> Unit) {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        SomaHome.socket.writeText(server.localPort.toString())
        port = server.localPort
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
                }.getOrNull()?.trim() ?: continue
                // The Linux tray's items (LinuxTray), with this run's token; anything else is a plain hand-off word.
                trayWord(word, LinuxTray.token)?.let { item -> javax.swing.SwingUtilities.invokeLater { onTray(item) }; continue }
                if (!handOffWord(word)) continue
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

/** The tray icon: Open, the dashboard, the looks, the car's pairing, Grok's connector, Quit. Its notifications are Soma's ([Notices]). */
private object Tray {
    fun make(onOpen: () -> Unit, onDashboard: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit, onLook: (String) -> Unit, onCar: (pair: Boolean) -> Unit = {}, onGrok: (connect: Boolean) -> Unit = {}): TrayIcon? {
        if (!SystemTray.isSupported()) return null
        val menu = PopupMenu()
        menu.add(MenuItem("Open Soma").apply { addActionListener { onOpen() } })
        menu.add(MenuItem("Dashboard").apply { addActionListener { onDashboard() } })
        menu.add(MenuItem("Settings…").apply { addActionListener { javax.swing.SwingUtilities.invokeLater(onSettings) } })
        val looks = java.awt.Menu("Look")
        for (id in Looks.ids) looks.add(MenuItem(id).apply { addActionListener { onLook(id) } })
        menu.add(looks)
        val car = java.awt.Menu("Car")
        car.add(MenuItem("Pair the car").apply { addActionListener { onCar(true) } })
        car.add(MenuItem("Unpair the car").apply { addActionListener { onCar(false) } })
        menu.add(car)
        val grok = java.awt.Menu("Grok")
        grok.add(MenuItem("Connect Grok").apply { addActionListener { onGrok(true) } })
        grok.add(MenuItem("Disconnect Grok").apply { addActionListener { onGrok(false) } })
        menu.add(grok)
        menu.addSeparator()
        menu.add(MenuItem("Quit Soma").apply { addActionListener { onQuit() } })
        val image = (Tray::class.java.classLoader.getResource("soma-tray.png")?.let { Toolkit.getDefaultToolkit().getImage(it) }
            ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply { createGraphics().apply { color = java.awt.Color(0x7aa2f7); fillOval(3, 3, 10, 10); dispose() } })
        val icon = TrayIcon(image, "Soma", menu).apply { isImageAutoSize = true; addActionListener { onOpen() } }
        return runCatching { SystemTray.getSystemTray().add(icon); icon }.getOrNull()
    }
}
