package dev.omalauncher.ui

import kotlinx.coroutines.delay
import dev.omalauncher.canvas.moduleProjects
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.omalauncher.widgets.CockpitModules
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.oma.ring.HomeRing
import dev.omalauncher.widgets.checkSecretsCommand
import dev.omalauncher.widgets.publishWidgetCommand
import dev.omalauncher.widgets.render.LocalHomeCovered
import dev.omalauncher.widgets.reviewSharedWidgetCommand
import kotlinx.coroutines.launch

@Composable
fun LauncherRoot(
    runtime: Runtime,
    state: LauncherState,
) {
    state.bindScope()
    val ready = runtime.launcherReady
    // With our live wallpaper active the window is transparent and Android draws the wallpaper behind it.
    val showOwnBackground = !ready || !runtime.themes.liveWallpaperActive
    Box(
        Modifier
            .fillMaxSize()
            .then(if (showOwnBackground) Modifier.background(Palette.background) else Modifier),
    ) {
        if (ready) DesktopScreen(runtime, state) else SetupScreen(runtime)
    }
}

@Composable
private fun DesktopScreen(
    runtime: Runtime,
    state: LauncherState,
) {
    val context = LocalContext.current
    // Something that needs Linux was asked for (a terminal, Super+Enter): offer to install it.
    LaunchedEffect(runtime.linuxWanted) {
        if (runtime.linuxWanted) {
            runtime.linuxWanted = false
            state.close()
            state.linuxOpen = true
        }
    }
    BackHandler {
        when {
            // Screens opened from settings return to it; settings itself is last.
            state.shared != null -> state.shared = null
            state.sharing != null -> state.sharing = null
            state.linuxOpen -> state.linuxOpen = false
            state.licensesOpen -> state.licensesOpen = false
            state.agentsOpen -> state.closeAgents()?.let { openOmaChat(context, it) }
            state.storeOpen -> state.storeOpen = false
            state.themeBrowserOpen -> state.themeBrowserOpen = false
            state.styleOpen -> state.styleOpen = false
            state.packagesOpen -> state.packagesOpen = false
            state.settingsOpen -> state.settingsOpen = false
            runtime.widgets.openScreen != null -> runtime.widgets.openScreen = null
            else -> state.close()
        }
    }

    if (Pack.ownsPage) {
        // A theme pack with its own page: its paper or wallpaper is behind home instead of the theme's, the live one included.
        PagePaper()
        PageGutters(state.pagesAcross)
    } else if (!runtime.themes.liveWallpaperActive) {
        runtime.themes.wallpaper?.let { bmp ->
            // Read while drawing, so swiping pages only redraws this.
            Canvas(Modifier.fillMaxSize()) {
                val src = wallpaperCrop(bmp.width, bmp.height, size.width.toInt(), size.height.toInt(), state.wallpaperOffset)
                drawImage(
                    bmp,
                    srcOffset = IntOffset(src.left, src.top),
                    srcSize = IntSize(src.width(), src.height()),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
    val covered = remember(state) { { state.homeCovered || runtime.widgets.openScreen != null } }
    CompositionLocalProvider(LocalHomeCovered provides covered) {
        Column(Modifier.fillMaxSize().safePadding()) {
            HomeScreen(runtime, state, Modifier.weight(1f))
        }
    }
    HomeMenu(runtime, state)

    // Only whether it shows is read here; the animation itself runs in the layer, without recomposing.
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    val search by remember(runtime, state) { derivedStateOf { searchApps(runtime, state.query) } }
    val highlighted = search.highlighted(state.picked)

    fun launch(hit: AppHit) {
        runtime.usage.record(hit.key)
        state.query = ""
        state.closeNow()
        when (hit) {
            is AppHit.Linux -> runtime.launch(hit.app)
            is AppHit.Android -> runtime.androidApps.open(hit.app)
        }
    }

    val dock = runtime.widgets.dock
    val handlers =
        remember(runtime, state, context, dock) {
            AppHandlers(
                launch = ::launch,
                // Pins to the page that's showing; the panel closes so the new tile is visible.
                pin = { hit ->
                    when (hit) {
                        is AppHit.Linux -> runtime.widgets.shortcuts.addLinux(hit.app, state.currentPage)
                        is AppHit.Android -> runtime.widgets.shortcuts.addAndroid(hit.app, state.currentPage)
                    }
                    state.close()
                },
                docked = { hit -> dock.isPinned(hit.dockKey()) },
                // The panel closes here too, so the dock shows what changed.
                dock = { hit ->
                    if (dock.isPinned(hit.dockKey())) dock.unpin(hit.dockKey()) else dock.pin(hit.dockEntry())
                    state.close()
                },
                info = { app ->
                    state.closeNow()
                    runtime.androidApps.openInfo(app)
                },
                uninstall = { app -> runtime.androidApps.uninstall(app) },
            )
        }

    /** The ask row: what's typed goes to the Oma chat's field, not sent; without Linux or an agent, to setting one up. */
    fun ask() {
        val query = state.query
        when (agentRoute(runtime.bootstrap.state.value == LinuxState.Installed, runtime.agents.default?.bin)) {
            AgentRoute.InstallLinux -> {
                state.closeNow()
                state.linuxOpen = true
            }
            AgentRoute.ChooseAgent -> state.agentsOpen = true
            AgentRoute.Background, AgentRoute.Terminal -> {
                state.closeNow()
                openOmaChat(context, query)
            }
        }
    }

    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    if (panelShown) {
        // Near-opaque like stock drawers: home widgets bleeding through made icons hard to read. A look with its
        // own page says what the list stands on: the prototype's drawer is the paper itself, with nothing showing through.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.drawer.value.coerceAtMost(1f) }
                .background(if (Pack.ownsPage) Pack.drawerFill else Palette.background.copy(alpha = 0.97f))
                .clickable { state.close() }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dy -> state.dragBy(-dy / screenHeight) },
                    onDragStopped = { velocity -> state.settle(velocity) },
                ),
        )
    }
    // The dock at the bottom of home, and the app list over all of it; the keyboard lifts both. Nothing here takes
    // touches while the list is closed, except the dock and the strip. A phone's landscape stands them in a rail
    // beside the grid, and opens the list over it, centered.
    val side = homeSide()
    if (side != null && !panelShown) {
        if (runtime.terminals.active == null) {
            SideRail(runtime, state, handlers, dock, side, Modifier.fillMaxSize().safePadding())
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .safePadding()
                .imePadding()
                .then(if (side != null) Modifier.wrapContentWidth().widthIn(max = ReadingWidth) else Modifier),
        ) {
            // Hidden while a terminal tile types: it would sit on the keyboard, over the tile.
            if (side == null && (runtime.terminals.active == null || runtime.terminals.keyboardLeaving)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    // Home only: the open app list has everything.
                    if (Pack.recentApps && !panelShown) RecentStrip(runtime, handlers)
                    // Stays laid out under the open list, so what it measures for home and what it's hearing carry on.
                    // The drawing tools take the dock's place, as in the prototype: in a look with its own page it isn't behind them.
                    Box(Modifier.fadesUnderDrawer(state).then(if (state.drawing && Pack.ownsPage) Modifier.alpha(0f) else Modifier)) {
                        // Insets itself by the grid gap, so it lines up with the tiles and the gap still takes taps.
                        // A drag upwards from the dock opens the app list, as from the pages above it.
                        DockBand(runtime, state, dock, Modifier.opensDrawer(state, screenHeight) { runtime.terminals.active == null })
                        if (panelShown) DockCover(state, Modifier.matchParentSize())
                    }
                }
            }
            if (panelShown) {
                AppPanel(
                    runtime = runtime,
                    state = state,
                    search = search,
                    highlighted = highlighted,
                    handlers = handlers,
                    onAsk = ::ask,
                    onSettings = {
                        state.closeNow()
                        state.settingsOpen = true
                    },
                    modifier =
                        Modifier.graphicsLayer {
                            val progress = state.drawer.value
                            alpha = progress.coerceAtMost(1f)
                            translationY = (1f - progress) * size.height * 0.6f
                        },
                )
            }
        }
    }

    // The drawing tools stand over the dock while one finger draws, and take its place. The app list coming up
    // (from a keyboard, or a widget asking for it) puts the pen down: the tools are never over it.
    LaunchedEffect(panelShown) { if (panelShown) state.stopDrawing() }
    if (state.drawing && !panelShown) {
        var stickers by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().safePadding(), contentAlignment = Alignment.BottomCenter) { DrawTools(state, onStickers = { stickers = true }) }
        // Outside the bars' padding, so the scrim reaches under the status and navigation bars as the home menu's does.
        if (stickers) DrawStickers(state) { stickers = false }
    }

    // A sticky note's text, typed over home and over the dock, which the keyboard would otherwise lift into it.
    NoteEditor(state)

    // Every page at once, over the whole window: over the dock too, and under the ring and the full-screen panels.
    Overview(runtime, state)

    // The agent's light along the screen's edge, over home and its dock and in nothing's way: it takes no touch and no room.
    HomeRing()

    // Full-screen panels draw over the dock and the ring.
    ThemesOverlay(runtime, state)
}

@Composable
private fun ThemesOverlay(
    runtime: Runtime,
    state: LauncherState,
) {
    // A widget's screen first: what it opens (themes, agents) draws on top of it.
    // It grows out of its tile, and stays until it has shrunk back into it.
    WidgetScreenHost(
        open = runtime.widgets.screenInstance(),
        tiles = state.tiles,
        running = { spec, inst -> runtime.widgets.instance(spec) === inst },
        onDismiss = { runtime.widgets.openScreen = null },
    )
    // Settings first so the screens it opens draw on top of it.
    if (state.settingsOpen) {
        SettingsScreen(
            runtime,
            onDismiss = { state.settingsOpen = false },
            onStyle = { state.styleOpen = true },
            onAgents = { state.agentsOpen = true },
            onPackages = { state.packagesOpen = true },
            onStore = { state.storeOpen = true },
            onLinux = { state.linuxOpen = true },
            onLicenses = { state.licensesOpen = true },
        )
    }
    if (state.packagesOpen) PackagesScreen(runtime, onDismiss = { state.packagesOpen = false })
    if (state.styleOpen) StyleScreen(runtime, onDismiss = { state.styleOpen = false }, onBrowse = { state.themeBrowserOpen = true })
    if (state.themeBrowserOpen) ThemeCatalogScreen(runtime.themes, onDismiss = { state.themeBrowserOpen = false })
    if (state.storeOpen) {
        // A project's modules are offered by its registry name, so the registry is read (off the main thread) as the store
        // opens; until it has been, no project's module is offered under a name that would change once it is.
        val context = LocalContext.current
        val projects by produceState<List<String>?>(null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    // A read already under way is waited for (as long as a read can take) rather than offering none.
                    if (!CockpitModules.feed(context, runtime, moduleProjects(runtime.widgets.notebook.modules().keys), System.currentTimeMillis(), force = true)) {
                        val until = System.currentTimeMillis() + 30_000L
                        while (CockpitModules.feedingNow && System.currentTimeMillis() < until) delay(100)
                    }
                    CockpitModules.source(context, runtime).projects()
                }.getOrDefault(emptyList())
            }
        }
        StoreScreen(runtime.widgets, runtime.agents.installed, state.currentPage, runtime.themes.picked, projects = projects, onDismiss = { state.storeOpen = false })
    }
    if (state.licensesOpen) LicensesScreen(onDismiss = { state.licensesOpen = false })
    if (state.linuxOpen) LinuxScreen(runtime, onDismiss = { state.linuxOpen = false })
    state.shared?.let { link ->
        SharedWidgetScreen(
            runtime.widgets,
            link,
            state.currentPage,
            // Stays open underneath, to add it once the agent has had its say.
            onReview = { path ->
                val agent = runtime.agents.default
                if (agent == null) state.agentsOpen = true else runtime.terminal(reviewSharedWidgetCommand(agent.bin, path), "Review widget", agent)
            },
            onDismiss = { state.shared = null },
        )
    }
    state.sharing?.let { spec ->
        ShareWidgetScreen(
            spec,
            // Only your own: a widget from the store or from someone else isn't yours to submit.
            onPublish =
                if (!runtime.widgets.isOwnWidget(spec)) {
                    null
                } else {
                    {
                        state.sharing = null
                        // The default agent opens a pull request (or an issue) on the store repo.
                        val agent = runtime.agents.default
                        if (agent == null) state.agentsOpen = true else runtime.terminal(publishWidgetCommand(agent.bin, spec), "Publish ${spec.name}", agent)
                    }
                },
            // Closes the screen: what the agent changes would be shared from a fresh one.
            onCheck = {
                state.sharing = null
                val agent = runtime.agents.default
                if (agent == null) state.agentsOpen = true else runtime.terminal(checkSecretsCommand(agent.bin, spec), "Check ${spec.name}", agent)
            },
            onDismiss = { state.sharing = null },
        )
    }
    if (state.agentsOpen) {
        val context = LocalContext.current
        AgentsScreen(
            runtime,
            // Words said before there was an agent go on to the Oma chat's field, to send from there.
            onDismiss = { state.closeAgents()?.let { openOmaChat(context, it) } },
            onOpen = { agent ->
                // Words held for an agent that wasn't there yet don't wait for some later visit to this screen.
                state.closeAgents()
                runtime.terminal(agent.bin, agent.name, agent)
            },
        )
    }
}

/** Clear of the system bars and of the camera cutout, which landscape puts at a side. */
@Composable
private fun Modifier.safePadding() = windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
