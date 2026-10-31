package dev.omalauncher

import java.io.File
import android.app.ActivityManager
import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import dev.omalauncher.ui.PageCaptures
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.AgentStore
import dev.omalauncher.linux.AudioBridge
import dev.omalauncher.linux.Bootstrap
import dev.omalauncher.linux.EDIT_COMMAND
import dev.omalauncher.linux.FILES_COMMAND
import dev.omalauncher.linux.NEWS_COMMAND
import dev.omalauncher.linux.Gpu
import dev.omalauncher.linux.KEEP_COMMAND
import dev.omalauncher.linux.KEEP_PYTHON
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.linux.OMA_COMMAND
import dev.omalauncher.linux.OpenBridge
import dev.omalauncher.linux.Pacman
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.SystemUpdates
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.bundledIcons
import dev.omalauncher.linux.defaultApps
import dev.omalauncher.linux.keepGuestTools
import dev.omalauncher.linux.omaGuestTools
import dev.omalauncher.linux.loadLinuxApps
import dev.omalauncher.linux.matches
import dev.omalauncher.linux.resolveLinuxIcon
import dev.omalauncher.linux.run
import dev.omalauncher.linux.shellQuote
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.ui.KeyboardBridge
import dev.omalauncher.ui.TerminalTiles
import dev.omalauncher.ui.WindowManager
import dev.omalauncher.ui.bitmapMask
import dev.omalauncher.ui.decodeIcon
import dev.omalauncher.ui.decodeImageIcon
import dev.omalauncher.ui.decodeSvgIcon
import dev.omalauncher.ui.Pack
import dev.omalauncher.assist.MacThreads
import dev.omalauncher.assist.agentsGuestTools
import dev.omalauncher.assist.macGuestTools
import dev.omalauncher.upgrade.Provisioner
import dev.omalauncher.wayland.AndroidClipboard
import dev.omalauncher.wayland.OutputInfo
import dev.omalauncher.wayland.WaylandServer
import dev.omalauncher.wayland.XdgToplevel
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.WidgetSpec
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Process-wide state: the launcher (themes, widgets, Android apps) and the
 * optional Linux system (the rootfs, the Wayland server, the window manager).
 */
class Runtime(
    private val context: Context,
) {
    val proot = Proot(context)
    val phoneRunner = dev.omalauncher.runner.PhoneRunner(context, dev.omalauncher.runner.MacPhoneTailnet(proot.launcherConfig))
    val wm = WindowManager()
    val server = WaylandServer(proot.waylandSocket.path, wm).also { wm.server = it }
    val keyboard = KeyboardBridge(wm)
    val terminals = TerminalTiles(this)
    val terminalFont = TerminalFont(context, proot)
    val linuxApps = mutableStateListOf<LinuxApp>()

    /** Icons of [linuxApps] by desktop id; apps without a PNG icon have no entry. */
    val linuxIcons = mutableStateMapOf<String, ImageBitmap>()

    /** Silhouettes of [linuxIcons] for the monochrome icon style. */
    val linuxMasks = mutableStateMapOf<String, ImageBitmap>()

    /** The Icon= each of [linuxIcons] was loaded from, to notice an entry changing it. */
    private val linuxIconNames = HashMap<String, String>()
    val androidApps = AndroidAppCatalog(context)
    val usage = AppUsage(context)
    val themes = ThemeStore(context, proot, isLinux = { linux }, onConfigChanged = ::syncGuest)
    private val audio = AudioBridge(proot)
    private val openBridge = OpenBridge(context, proot, onOpenFiles = ::openFiles, onEdit = ::edit)
    val phoneBridge = dev.omalauncher.phone.PhoneBridge(context, proot)
    val agents = AgentStore(proot, onChanged = ::syncGuest)
    val pacman = Pacman(context, proot)
    val widgets = WidgetEngine(context, proot, this)

    /**
     * Stills of the home pages, for the overview's tiles. Only a convenience: dropped when home stops showing and
     * whenever Android asks for memory, after which a page shows as the outline of its cells until it is seen again.
     */
    val pageStills =
        PageCaptures().also { stills ->
            context.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = stills.clear()

                    override fun onTrimMemory(level: Int) = stills.clear()
                },
            )
        }
    val updates = SystemUpdates(proot, pacman, agents)
    val gpu = Gpu(context, proot, pacman)

    /** The launcher's own provided files (default widgets, their docs); see [Provisioner]. */
    private val launcherFiles = Provisioner(proot.filesDir, LauncherData.LAUNCHER_MANIFEST, listOf(widgets::managed))

    /** Every launcher-provided file in the guest; synced only while Linux is installed. */
    private val provisioner: Provisioner =
        Provisioner(
            proot.filesDir,
            LauncherData.GUEST_MANIFEST,
            listOf(
                { defaultApps(proot.applications) },
                { bundledIcons(context, proot.home) },
                openBridge::managed,
                phoneBridge::managed,
                pacman::managed,
                agents::managed,
                themes::managed,
                widgets::guestManaged,
                { macGuestTools(context, proot.rootfs) },
                { keepGuestTools(context, proot.rootfs) },
                { agentsGuestTools(context, proot.rootfs) },
                { omaGuestTools(context, proot.rootfs) },
                { dev.omalauncher.oma.PhoneArtifacts.guestTools(context, proot.rootfs) },
            ),
        )
    val bootstrap = Bootstrap(context, proot, agents, pacman, provisioner)

    /** Its jobs write files and start the guest; one that fails (storage full) mustn't take the home screen down. */
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "background job failed", e) })
    private var started = false
    private var prepared = false

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /**
     * The first-run screen is done: from then on the home screen shows, with
     * or without Linux. Installs from before Linux was optional count as done.
     */
    var launcherReady by mutableStateOf(prefs.getBoolean(SETUP_DONE, false) || bootstrap.wanted)
        private set

    /** Linux is installed and its services run: guest commands (like the update check) can go. */
    private var linux = false
    val connectionsLinuxRunning: Boolean get() = linux
    internal val connectionsKeeperStarted: Boolean get() = keeperOpen
    internal val connectionsBgWatcherStarted: Boolean get() = bgHealOpen
    /** Missing fixed services only: never kill a live keeper, its kept jobs, or the Linux runtime. */
    @Synchronized internal fun connectionsEnsureMissingService(keeper: Boolean): Boolean {
        if (!linux) return false
        return if (keeper) {
            if (keeperOpen) false else { openKeeper(); keeperOpen }
        } else {
            if (bgHealOpen) false else { openBgHeal(); bgHealOpen }
        }
    }
    /** The keeper is not answering though Linux is up: start it again (the one fixed service Soma owns; a keeper already alive makes the new one leave). */
    @Synchronized internal fun connectionsRestartKeeper(): Boolean {
        if (!linux) return false
        keeperOpen = false
        openKeeper()
        return keeperOpen
    }
    private val connectionsMonitor by lazy { dev.omalauncher.connections.ConnectionsMonitor(context.applicationContext, this) }
    /** The update pill's feed (cards soma-chat/Updates). */
    val phoneUpdates by lazy { dev.omalauncher.update.PhoneUpdater(context.applicationContext) }

    /** The Linux install and removal; one at a time. */
    private var linuxJob: Job? = null

    /** Set when something that needs Linux was asked for without it; the home screen opens the install sheet and clears it. */
    var linuxWanted by mutableStateOf(false)

    init {
        // Saved, so removing Linux later doesn't bring the first-run screen back.
        if (launcherReady) prefs.edit().putBoolean(SETUP_DONE, true).apply()
        themes.loadStyle()
        androidApps.start()
        // Omarchy's Super bindings inside Linux windows; the home screen handles its own (see MainActivity).
        keyboard.onShortcut = { code ->
            when (code) {
                KeyEvent.KEYCODE_ENTER -> true.also { terminal(null, "Terminal") }
                KeyEvent.KEYCODE_W -> wm.focused?.let { wm.close(it) } != null
                else -> false
            }
        }
        // A terminal shown again after the text size changed catches up, with foot's own zoom keys.
        wm.created = { window -> if (followsTerminalFont(window.appId)) terminalFont.track(window) }
        wm.focusGained = { window -> wm.zoom(window, terminalFont.catchUp(window)) }
        wm.openAppWindow = { handle ->
            // Recents uses the activity's manifest label, not the task description's; see the aliases in the manifest.
            val window = wm.windowByHandle(handle)
            val name = window?.let { displayName(it.appId, it.title) }
            val activity =
                when (name) {
                    "Terminal" -> "TerminalWindow"
                    "btop" -> "BtopWindow"
                    else -> agentOf(window?.appId.orEmpty(), name)?.windowAlias ?: "LinuxWindowActivity"
                }
            context.startActivity(
                Intent()
                    .setClassName(context, "dev.omalauncher.$activity")
                    .setData(Uri.parse("omarchy://window/$handle"))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                    ),
            )
        }
    }

    /** The launcher first (it never waits for Linux), then Linux if it's installed or an install was started. */
    fun start() {
        if (started) return
        started = true
        phoneRunner.start()
        connectionsMonitor.start()
        phoneUpdates.start()
        // Phone control switched off by a force-stop or an update, while he wanted it on, is put back: off this thread.
        dev.omalauncher.phone.PhoneControlHeal.atStart(context)
        // Wireless debugging, which Android turns off at every restart and Wi-Fi drop, is put back on.
        dev.omalauncher.phone.AdbWifiHeal.atStart(context)
        // Agent mail waiting in Taildrop is read by Soma itself, not only when Tailshare happens to run.
        dev.omalauncher.assist.TaildropMail.start(context)
        scope.launch {
            withContext(Dispatchers.IO) {
                launcherFiles.sync()
                agents.load()
                // A thread on the Mac may be working, or waiting for Boss to approve a step, since before this process
                // began: it is picked up now and not when he next opens the chat. With no Mac set up nothing is asked.
                // Its health check is also the first look of TailscaleHeal, since a reboot is when Tailscale drops.
                MacThreads.open(context)
            }
            themes.load()
            widgets.start()
            // On the first-run screen the download is the screen's job, with progress.
            if (launcherReady) scope.launch { themes.ensureRelease() }
            if (bootstrap.wanted) runLinux { bootstrap.run() } else bootstrap.run()
        }
    }

    /** The first-run screen is done; the home screen shows from now on, while the rest of the themes download. */
    fun finishWelcome() {
        prefs.edit().putBoolean(SETUP_DONE, true).apply()
        launcherReady = true
        scope.launch { themes.ensureRelease() }
    }

    /** Installs Linux in the background, with [agents]; progress shows in [Bootstrap.state] and the notification. */
    fun installLinux(agents: List<Agent>) = runLinux { bootstrap.install(agents) }

    /** Continues a failed install, or a failed update of an installed system. */
    fun retryLinux() = runLinux { bootstrap.run() }

    /** Runs [work] with the foreground service up, so it goes on outside the app; then starts Linux if it's installed. */
    private fun runLinux(work: suspend () -> Unit) {
        if (linuxJob?.isActive == true) return
        linuxJob =
            scope.launch {
                setService(true)
                withContext(Dispatchers.IO) {
                    if (!prepared) {
                        proot.prepare()
                        prepared = true
                    }
                }
                work()
                if (bootstrap.isInstalled) onLinuxReady() else setService(false)
            }
    }

    /**
     * Deletes Linux: its apps, agents, packages and home. Themes, widgets and
     * the home screen stay; widgets that need Linux turn back into placeholders.
     */
    fun removeLinux() {
        if (!bootstrap.isInstalled || linuxJob?.isActive == true) return
        linuxJob =
            scope.launch {
                linux = false
                widgets.linuxChanged(false)
                audio.stop()
                withContext(Dispatchers.IO) { proot.killAll() }
                bootstrap.remove()
                withContext(Dispatchers.IO) {
                    updates.forget()
                    agents.load()
                }
                gpu.turnOff()
                linuxApps.clear()
                linuxIcons.clear()
                linuxMasks.clear()
                linuxIconNames.clear()
                setService(false)
            }
    }

    /** The foreground service keeps the process, and with it Linux, alive; only while Linux is installed or installing. */
    private fun setService(on: Boolean) {
        val intent = Intent(context, LinuxService::class.java)
        // Android 12+ refuses a foreground service started from the background; the work then goes on without it.
        if (on) runCatching { context.startForegroundService(intent) }.onFailure { Log.w(TAG, "cannot start the service", it) } else context.stopService(intent)
    }

    /** Guest configs follow the theme and the installed agents; only while there is a guest. */
    private fun syncGuest() {
        if (linux) provisioner.sync()
    }

    /** Whether the bridges and the Wayland server are up; they run until the process ends, even if Linux is removed. */
    private var servicesStarted = false

    private suspend fun onLinuxReady() {
        if (linux) return
        if (!servicesStarted) {
            servicesStarted = true
            updateOutput()
            synchronized(server) { server.selection.system = AndroidClipboard(context, server::clipboardChanged) }
            context.registerComponentCallbacks(
                object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) = updateOutput()

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = Unit
                },
            )
            withContext(Dispatchers.IO) {
                server.start()
                proot.procShim.start()
                openBridge.start()
                phoneBridge.start()
            }
        } else {
            // Linux installed again: its new home has no foot-font.ini yet.
            terminalFont.write()
        }
        linux = true
        // The recent apps strip on home lists Linux apps before the app list is ever opened.
        scope.launch { refreshApps() }
        // Themes picked without Linux are written into the guest now.
        themes.renderGuest()
        withContext(Dispatchers.IO) {
            provisioner.sync()
            audio.start()
            synchronized(server) { server.input.setKeymap(proot.keymap.path) }
            agents.load()
        }
        widgets.linuxChanged(true)
        openDoor()
        openKeeper()
        openHerd()
        openBgHeal()
        refreshApps()
        withContext(Dispatchers.IO) { updates.load() }
        scope.launch {
            // Update steps that need the network; the home screen doesn't wait for them.
            bootstrap.runBacklog()
            updates.check()
        }
        // Apps render in software until the GPU passed its check on this system build.
        scope.launch { gpu.recheck() }
    }

    /** Turns GPU acceleration on (installing and checking the driver) or off, for Linux apps started from then on. */
    fun setGpu(on: Boolean) {
        if (!linux) return
        if (on) scope.launch { gpu.turnOn() } else gpu.turnOff()
    }

    /** Runs every pending update (packages and agents); the drawer then lists what changed. */
    fun updateSystem() {
        scope.launch {
            updates.updateAll()
            refreshApps()
        }
    }

    /**
     * Tells Wayland clients the screen's size and scale; they get it again on
     * rotation, a density change or a new Android font size, which also sizes
     * the terminal font.
     */
    private fun updateOutput() {
        val metrics = context.resources.displayMetrics
        val mmPerPx = 25.4f / metrics.xdpi
        val scale = outputScale()
        server.updateOutput(
            OutputInfo(
                metrics.widthPixels,
                metrics.heightPixels,
                scale,
                (metrics.widthPixels * mmPerPx).toInt(),
                (metrics.heightPixels * mmPerPx).toInt(),
            ),
        )
        terminalFont.update(metrics.density, context.resources.configuration.fontScale, scale, metrics.widthPixels)
    }

    /** wl_output scales are integers, so the density rounds down (2.625 -> 2); [TerminalFont] makes up the rest. */
    private fun outputScale() =
        context.resources.displayMetrics.density
            .toInt()
            .coerceIn(1, 4)

    /** Re-runs update steps that failed, e.g. offline; see [Bootstrap.updates]. */
    fun retryUpdates() {
        if (linux) scope.launch { bootstrap.runBacklog() }
    }

    /**
     * Launcher screens showing now: the home screen and Linux windows. Guest
     * helpers that only matter on screen (the /proc stand-ins) pause at zero.
     */
    private var screens = 0

    fun screenStarted() {
        if (screens++ == 0) proot.procShim.setActive(true)
        // A keeper that ended (python3 installed only now, or it failed) is started again when he next looks.
        if (linux) openKeeper()
        // The herd reporter too, so the Fold comes back to the pets' Agents tab without anyone asking.
        if (linux) {
            openHerd()
            openBgHeal()
        }
        // Only checks when the last check is hours old; see SystemUpdates.
        if (linux) scope.launch { updates.check() }
    }

    fun screenStopped() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens == 0) proot.procShim.setActive(false)
        if (screens == 0) pageStills.clear()
    }

    /** Rescans .desktop entries; the list only changes (and recomposes) when entries do. */
    suspend fun refreshApps() {
        if (!linux) return
        val apps = withContext(Dispatchers.IO) { loadLinuxApps(proot) }
        if (apps != linuxApps.toList()) {
            linuxApps.clear()
            linuxApps += apps
        }
        val ids = apps.mapTo(HashSet()) { it.id }
        // An entry can change its Icon= under the same id: on a fresh install, pacman's micro.desktop
        // shows up before the launcher's Editor entry replaces it.
        for (app in apps) {
            if (linuxIconNames[app.id].let { it != null && it != app.icon }) {
                linuxIcons -= app.id
                linuxMasks -= app.id
                linuxIconNames -= app.id
            }
        }
        linuxIcons.keys.retainAll(ids)
        linuxMasks.keys.retainAll(ids)
        linuxIconNames.keys.retainAll(ids)
        val missing = apps.filter { it.icon != null && it.id !in linuxIcons }
        if (missing.isEmpty()) return
        val size = (52 * context.resources.displayMetrics.density).toInt()
        val loaded =
            withContext(Dispatchers.IO) {
                missing.mapNotNull { app ->
                    val file = resolveLinuxIcon(proot.rootfs, app.icon!!) ?: return@mapNotNull null
                    decodeIcon(file, size)?.let { Triple(app, it.asImageBitmap(), bitmapMask(it).asImageBitmap()) }
                }
            }
        for ((app, icon, mask) in loaded) {
            linuxIcons[app.id] = icon
            linuxMasks[app.id] = mask
            linuxIconNames[app.id] = app.icon!!
        }
    }

    /** A pinned app's icon as the drawer shows it, or its silhouette; null until loaded, or if it has none. */
    fun shortcutIcon(
        shortcut: Shortcut,
        mono: Boolean,
    ): ImageBitmap? {
        (shortcut.component?.let { androidAppKey(it, shortcut.profile) } ?: shortcut.androidShortcut?.let { androidShortcutKey(it, shortcut.profile) })
            ?.let { return if (mono) androidApps.masks[it] else androidApps.icons[it] }
        shortcut.owl?.let { file -> return widgets.specs.firstOrNull { it.file.name == file }?.let { owlIcon(it, mono) } }
        val id = shortcut.linux?.id ?: return null
        return if (mono) linuxMasks[id] else linuxIcons[id]
    }

    /** Owl apps' icons and silhouettes, by their SVG (or image) and the accent they were drawn in: a theme change draws them anew. */
    private val owlIcons = HashMap<Pair<String, Int>, Pair<ImageBitmap, ImageBitmap>?>()

    /**
     * An owl app's icon, or its silhouette; null if it has none. An SVG is drawn with `currentColor` in the theme's
     * accent, an asset's image (a PNG) as it is.
     */
    fun owlIcon(
        spec: WidgetSpec,
        mono: Boolean,
    ): ImageBitmap? {
        val program = spec.program ?: return null
        val image = program.iconImage
        val svg = program.icon
        if (image == null && svg == null) return null
        val accent = Pack.accent.toArgb()
        val size = (52 * context.resources.displayMetrics.density).toInt()
        val drawn =
            owlIcons.getOrPut((svg ?: "image:" + image.contentHashCode()) to accent) {
                val bmp = if (image != null) decodeImageIcon(image, size) else decodeSvgIcon(svg!!, size, accent)
                bmp?.let { it.asImageBitmap() to bitmapMask(it).asImageBitmap() }
            }
        return if (mono) drawn?.second else drawn?.first
    }

    /** Opens the owl app in file [name] in its own task; nothing if it isn't running (removed, or broken). */
    fun openOwlApp(name: String) {
        if (widgets.isApp(name)) InstantActivity.startInstantly(context, OwlAppActivity.intent(context, name))
    }

    /** Wayland app_id -> name for windows we started, so their Android tasks get a proper label. */
    private val launchedNames = ConcurrentHashMap<String, String>()

    /** Wayland app_id -> the agent running in it, for sessions not named after their agent ("New app"). */
    private val launchedAgents = ConcurrentHashMap<String, Agent>()

    /** Whether a window's foot takes its size from foot.ini, as its own terminals, plain foot and most tiles do. */
    private fun followsTerminalFont(appId: String) =
        when {
            appId == "foot" -> true
            launchedNames.containsKey(appId) -> !terminalFont.hasOwnSize(appId)
            appId.startsWith(TerminalTiles.APP_ID_PREFIX) -> !terminals.hasOwnFont(appId)
            else -> false
        }

    /**
     * Opens [command] (or a shell) in a terminal named [name]. Each gets its own
     * foot app_id, so Recents shows "Grok" or "btop" rather than one "foot" per window.
     * An [agent] session gets the agent's logo and Recents entry whatever it's named.
     */
    fun terminal(
        command: String?,
        name: String,
        agent: Agent? = null,
    ) {
        if (!linux) {
            linuxWanted = true
            return
        }
        // Per agent, so a Claude and a Grok "New app" don't share an app_id.
        val slug = if (agent == null || agent.name == name) name else "${agent.bin} $name"
        val appId = "omarchy." + slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        launchedNames[appId] = name
        if (agent != null) launchedAgents[appId] = agent
        val args = command?.let { " -e bash -c " + shellQuote(it) }.orEmpty()
        launch("foot --app-id=$appId${terminalFont.optionFor(appId, command)}$args")
    }

    /** Opens a pinned app from the home screen. A Linux one runs its current desktop entry, which an update may have changed. */
    fun open(shortcut: Shortcut) {
        shortcut.linux?.let { pinned -> launch(linuxApps.firstOrNull { it.id == pinned.id } ?: pinned) }
        shortcut.component?.let { androidApps.open(it, shortcut.profile) }
        shortcut.androidShortcut?.let { androidApps.open(it, shortcut.profile) }
        shortcut.owl?.let(::openOwlApp)
    }

    fun launch(app: LinuxApp) {
        // Our own Files app: open it directly rather than round-tripping through the guest.
        if (app.exec == FILES_COMMAND) return openFiles()
        if (app.exec == NEWS_COMMAND) return dev.omalauncher.news.NewsActivity.open(context)
        if (app.exec == EDIT_COMMAND) return edit()
        if (app.terminal) return terminal(app.exec, app.name)
        // Like an Android icon: an app that already has a window comes back to it. Single-instance
        // apps would otherwise just exit on a second launch, showing nothing. Quickshell apps (App Finder)
        // all share the app_id org.quickshell, so their window title, which is their name, decides.
        wm.appWindows
            .firstOrNull { (it.appId.isNotEmpty() && app.matches(it.appId)) || it.title.equals(app.name, ignoreCase = true) }
            ?.let { if (bringToFront(it)) return }
        launch(app.exec)
    }

    /** Moves [window]'s Android task to the front; false if it has none (yet). */
    private fun bringToFront(window: XdgToplevel): Boolean {
        val handle = wm.handleOf(window) ?: return false
        val task =
            context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                runCatching { it.taskInfo?.baseIntent?.data?.lastPathSegment }.getOrNull() == handle
            } ?: return false
        // The task may have gone since it was listed.
        return runCatching { task.moveToFront() }.isSuccess
    }

    /** The name to show for a Linux window: launch name, then .desktop entry, then its title. */
    fun displayName(
        appId: String,
        title: String,
    ): String {
        launchedNames[appId]?.let { return it }
        linuxApps.firstOrNull { it.matches(appId) }?.let { return it.name }
        return title.ifEmpty { appId.ifEmpty { "Linux" } }
    }

    /** The agent running in a Linux window: one started for it, or one named after it. */
    fun agentOf(
        appId: String,
        name: String?,
    ): Agent? = launchedAgents[appId] ?: AGENTS.firstOrNull { it.name == name }

    /** The drawer icon of the app called [name] (an agent's logo, say); null until loaded, or if it has none. */
    fun iconNamed(name: String): ImageBitmap? = linuxApps.firstOrNull { it.name == name }?.let { linuxIcons[it.id] }

    /** Opens the Files app, at [path] (a guest path) if given. */
    /**
     * Opens [path] (a guest path) in the editor, or its start page without one.
     * [done] is the host path of a FIFO that gets a reply once the file is closed (see OpenBridge's shim).
     */
    fun edit(
        path: String? = null,
        done: String? = null,
    ) {
        InstantActivity.startInstantly(context, EditorActivity.intent(context, path, done))
    }

    fun openFiles(path: String? = null) {
        context.startActivity(
            Intent(context, FilesActivity::class.java)
                .putExtra(FilesActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Starts oma-door (tools/fold-development/oma-door) with Linux, once, if the owner set it up (Boss, 2026-10-12):
     * the private door on the phone's Tailscale address through which his Mac session reaches the agent here.
     * Both its token and the command have to be there, and only he or his own tooling puts them there; the
     * launcher ships neither. It ends with the launcher, which is why it is started again here.
     */
    private fun openDoor() {
        if (doorOpened) return
        if (!File(proot.home, DOOR_TOKEN).isFile || !File(proot.rootfs, DOOR_COMMAND).isFile) return
        doorOpened = true
        launch("oma-door") { doorOpened = false }
    }

    @Volatile private var doorOpened = false

    /**
     * Starts the Fold's herd reporter (tools/fold-development/oma-herd) with Linux, once, if it is installed (Boss,
     * 2026-10-17: the Fold in every pet's Agents tab, and staying there). Like the door it ends with the launcher's
     * Linux, which is why it is started again here; the launcher doesn't ship it.
     */
    private fun openHerd() {
        if (herdOpen) return
        if (!File(proot.rootfs, HERD_COMMAND).isFile) return
        herdOpen = true
        launch("oma-herd") { herdOpen = false }
    }

    @Volatile private var herdOpen = false

    /**
     * Starts the background display's watcher (tools/fold-development/oma-bg-heal) with Linux, once, if it is installed
     * (Boss, 2026-10-25: the helper "self heals whenever I'm connected to Wi-Fi if it's not on after a reboot"). It only
     * starts the helper again while Wireless debugging answers; it never turns that on.
     */
    @Synchronized private fun openBgHeal() {
        if (bgHealOpen) return
        if (!File(proot.rootfs, BG_HEAL_COMMAND).isFile) return
        bgHealOpen = true
        launch("oma-bg-heal") { bgHealOpen = false }
    }

    @Volatile private var bgHealOpen = false

    /** Starts `oma keep`'s keeper (see linux/Keeper.kt) in a proot of its own, which a builder turn ending leaves running. */
    @Synchronized private fun openKeeper() {
        if (keeperOpen) return
        if (listOf(OMA_COMMAND, KEEP_COMMAND, KEEP_PYTHON).any { !File(proot.rootfs, it).isFile }) return
        keeperOpen = true
        launch("oma keep serve") { keeperOpen = false }
    }

    @Volatile private var keeperOpen = false

    /** Starts a command inside the guest; its output goes to logcat. Returns at once; [onExit] runs on the launch thread. */
    fun launch(
        command: String,
        onExit: () -> Unit = {},
    ) {
        if (!linux) {
            linuxWanted = true
            onExit()
            return
        }
        Log.i(TAG, "launch: $command")
        thread(name = "guest-launch", isDaemon = true) {
            val code = runCatching { proot.run("exec $command") { Log.i(TAG, "[${command.take(24)}] $it") } }.getOrElse { -1 }
            Log.i(TAG, "exited $code: $command")
            onExit()
        }
    }

    companion object {
        private const val DOOR_TOKEN = ".config/oma-door/token"
        private const val DOOR_COMMAND = "usr/local/bin/oma-door"
        private const val HERD_COMMAND = "usr/local/bin/oma-herd"
        private const val BG_HEAL_COMMAND = "usr/local/bin/oma-bg-heal"
        private const val TAG = "runtime"
        private const val SETUP_DONE = "setup_done"
    }
}
