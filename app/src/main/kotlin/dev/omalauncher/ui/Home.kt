package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import android.app.WallpaperManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.widgets.GRID_COLUMNS
import dev.omalauncher.widgets.ModuleItem
import dev.omalauncher.widgets.CockpitModules
import dev.omalauncher.oma.newChatTab
import dev.omalauncher.canvas.moduleSize
import dev.omalauncher.canvas.moduleRef
import dev.omalauncher.canvas.moduleProjects
import dev.omalauncher.canvas.ModuleView
import dev.omalauncher.canvas.LocalModuleFocus
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.herd.HerdSheep
import dev.omalauncher.assist.MacThreads
import dev.omalauncher.assist.ChatTitles
import dev.omalauncher.assist.agentThreadsNow
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.TabBook
import dev.omalauncher.oma.settled
import dev.omalauncher.widgets.ArtItem
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.ThreadItem
import dev.omalauncher.widgets.WidgetItem
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.scraps
import dev.omalauncher.widgets.tileThread
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.zIndex
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.annotations.HomeAnnotations
import androidx.compose.ui.platform.LocalContext
import dev.omalauncher.widgets.editWidgetCommand
import dev.omalauncher.widgets.fixDependenciesCommand
import dev.omalauncher.widgets.layout.foldView
import dev.omalauncher.widgets.layout.pagesAt
import dev.omalauncher.widgets.layout.posture
import dev.omalauncher.widgets.layout.slideUp
import dev.omalauncher.widgets.render.LocalTypingAt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import dev.omalauncher.widgets.layout.wallpaperOffset
import dev.omalauncher.widgets.layout.artIdOf
import dev.omalauncher.widgets.overflowIntoPanels
import dev.omalauncher.widgets.pickedPanels
import dev.omalauncher.art.ArtImage
import dev.omalauncher.art.ArtPickActivity
import dev.omalauncher.canvas.MangaPrefs
import dev.omalauncher.theme.pack.ArtPrint

/**
 * A one-finger drag upwards that starts here opens the app list under the finger (PRD AC-1): for the bands
 * around the pages, the page names above them and the dock below. Asked of a [GestureArbiter], as on the pages:
 * a drag more sideways than upwards, one downwards, a second finger or drawing mode leave it shut. What is
 * here keeps its own touches: a press that rests as long as a long press, or that something here has taken
 * (the agent button held to talk), is not a drag. [windowHeight] is what the list's position is a fraction of.
 */
internal fun Modifier.opensDrawer(
    state: LauncherState,
    windowHeight: Float,
    /** Asked as a touch comes down: false keeps the list shut, as while a terminal tile has the keyboard. */
    allowed: () -> Boolean = { true },
): Modifier =
    pointerInput(state, windowHeight) {
        awaitEachGesture {
            // After what is here has seen each move, so what it takes shows.
            val down = awaitFirstDown(requireUnconsumed = false)
            // Not from under the list, which closes its own way, nor from under the overview.
            if (state.drawer.value > 0.01f || state.overviewOpen || !allowed()) return@awaitEachGesture
            val arbiter = GestureArbiter()
            arbiter.down(TouchStart.Page, HomeModes(drawing = state.drawing))
            val speed = VelocityTracker()
            var pulling = false
            var lastY = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    val finger = event.changes.firstOrNull { it.id == down.id }
                    if (pressed == 0 || finger == null || !finger.pressed) {
                        if (pulling) event.changes.forEach { it.consume() }
                        break
                    }
                    arbiter.pointers(pressed)
                    if (!pulling) {
                        if (finger.isConsumed) break
                        if (finger.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) arbiter.longPress()
                        val by = finger.position - down.position
                        when (arbiter.moved(by.x, by.y, 0f, viewConfiguration.touchSlop)) {
                            Owner.Undecided -> continue
                            Owner.Drawer -> {
                                pulling = true
                                lastY = finger.position.y
                            }
                            else -> break
                        }
                    }
                    speed.addPosition(finger.uptimeMillis, finger.position)
                    state.dragBy(-(finger.position.y - lastY) / windowHeight)
                    lastY = finger.position.y
                    event.changes.forEach { it.consume() }
                }
            } finally {
                if (pulling) state.settle(speed.calculateVelocity().y)
            }
        }
    }

/** How often home looks at the threads for their tiles, and how often it lets the Mac's list be asked for again. */
private const val AGENT_TILES_EVERY_MS = 2_000L
private const val AGENT_TILES_GLANCE_MS = 60_000L

/** How long home rests on a page before it takes a still of it for the overview. */
private const val STILL_AFTER_MS = 700L

/** The band above the pages that names the page showing; the page's room starts below it. */
val PageNameHeight = 28.dp

/** Home: the notebook's pages of widgets and pinned apps on a snap grid, bordered and gapped like Omarchy windows. */
@Composable
fun HomeScreen(
    runtime: Runtime,
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    val engine = runtime.widgets
    val linuxState by runtime.bootstrap.state.collectAsState()
    val arrange = remember { Arrange() }
    // The notebook's pages, by id and in its order; they stay when they're empty. How many show side by side is
    // the notebook's fold rule: one, or unfolded and wider than tall two fixed neighbours, where the pager counts
    // pairs and an odd last page has an empty right half that offers a new page.
    val pages = engine.pages
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val fold =
        with(density) {
            val width = window.width.toDp().value
            val height = window.height.toDp().value
            foldView(width, height, posture(width, height, state.hinge), pages.indexOf(state.currentPage), pages.size)
        }
    val per = fold.per
    SideEffect { state.pagesAcross = per }
    val pager = rememberPagerState(initialPage = fold.slot) { fold.slots }
    // A tile dropped on another page, or a page just added: show it once the page list has caught up.
    var showPage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showPage, pages, per) {
        val i = showPage?.let(pages::indexOf) ?: return@LaunchedEffect
        if (i >= 0) {
            state.currentPage = pages[i]
            pager.scrollToPage(i / per)
        }
        showPage = null
    }
    var menuFor by remember { mutableStateOf<HomeItem?>(null) }
    // How far a pinch has shrunk the pages: it follows the fingers, and springs back when they let go.
    val pinch = remember { Animatable(1f) }
    val pinching = remember { booleanArrayOf(false) }
    // What the app list's position is a fraction of, as where it is dragged shut.
    val windowHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()
    // A still of the pages showing, a moment after arriving on them, for the overview to show when they're left.
    LaunchedEffect(pager.settledPage, engine.notebook.opened) {
        delay(STILL_AFTER_MS)
        if (engine.notebook.opened && !state.homeCovered && engine.openScreen == null) runtime.pageStills.request()
    }
    // An open folder doesn't wait under the overview.
    LaunchedEffect(state.overviewOpen) { if (state.overviewOpen) arrange.folder = null }

    val terminals = runtime.terminals
    // What is free on the pages: their ink, notes and stickers. It takes changes once the notebook is read and kept.
    val marks = state.marks
    val context = LocalContext.current
    // The Manga look: its pages are manga pages of panels. Switched into, home is kept as it was and fitted into panels.
    val manga = pickedPanels()
    SideEffect { MangaPrefs.lookDefault = manga?.let { it.artPrint == ArtPrint.Ink } }
    LaunchedEffect(manga, engine.notebook.opened) {
        // Not before the look picked is the one drawn: at the start Console stands in until the pick is read.
        // Nor while the look picked can't be read, and Console stands in for it.
        val pick = ActivePack.pick
        if (pick.id != runtime.themes.pickedPack || pick.error != null) return@LaunchedEffect
        MangaHome.lookChanged(context, engine, manga)?.let { engine.notice = it }
    }

    /** A page just added: in the Manga look, cut into its default panels. */
    fun cut(page: String?): String? {
        if (page != null && manga != null) engine.notebook.setPanels(page, manga.defaultLayout)
        return page
    }

    LaunchedEffect(engine.notebook.opened, engine.notebook.kept) { marks.book = HomeAnnotations.of(context) }
    // Written a moment after each change; at once when home loses the window, or the pen is put down.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, state.drawing) { if (!focused || !state.drawing) marks.book?.flush() }
    // The thread tiles follow their threads while home has the window: a look now and then, which asks the Mac
    // nothing itself. Its list is asked for again only when it has grown old, and never with no Mac set up.
    LaunchedEffect(focused, engine.notebook.opened, engine.notebook.kept) {
        AgentTasks.restore(context)
        MacThreads.restore(context)
        var glanced = 0L
        while (focused) {
            val now = System.currentTimeMillis()
            if (now - glanced >= AGENT_TILES_GLANCE_MS) {
                glanced = now
                MacThreads.glance(context, now)
            }
            // Not while he is arranging: a tile appearing under one he carries would change where it can land.
            if (state.editing == null && arrange.carry == null) {
                // What he did to the chat's tabs reaches the tiles too: a pinned one never leaves, an archived one goes
                // until new work brings it back, a new chat is named here even while the chat is closed, and one that has
                // rested its hours is put away.
                val threads = agentThreadsNow(context)
                val tabs = TabBook.change(context) { it.settled(threads, ChatLog.of(context).tasks, now) }
                ChatTitles.check(context)
                engine.agentTiles.keep(threads, MacThreads.view.listed, now, tabs.pinned.toSet(), tabs.archived.keys) { page ->
                    // The page's notes and stickers, read if they never were: a tile is placed clear of them too.
                    marks.book?.let { book ->
                        book.open(page)
                        when (book.status(page)) {
                            AnnotationBook.Status.Ready -> book.on(page).scraps()
                            AnnotationBook.Status.Unreadable -> emptyList()
                            else -> null
                        }
                    }
                }
            }
            // The modules look again at every turn (what they read is in memory); the slower feeds only once rested, and only
            // with a module on home, off the main thread.
            CockpitModules.look(now)
            val modules = engine.notebook.modules().keys
            if (modules.isNotEmpty() && CockpitModules.due(now)) {
                scope.launch(Dispatchers.IO) {
                    if (CockpitModules.feed(context, runtime, moduleProjects(modules), now)) withContext(Dispatchers.Main) { CockpitModules.look(System.currentTimeMillis()) }
                }
            }
            delay(AGENT_TILES_EVERY_MS)
        }
    }
    // The pen has the page to itself: no folder open under it, and no terminal tile typing.
    LaunchedEffect(state.drawing) {
        if (state.drawing) {
            arrange.folder = null
            terminals.deactivate()
        }
    }
    BackHandler(enabled = terminals.active != null) { terminals.deactivate() }
    BackHandler(enabled = state.homeMenuOpen) { state.homeMenuOpen = false }
    BackHandler(enabled = state.editing != null && !state.homeMenuOpen) { state.editing = null }
    BackHandler(enabled = arrange.folder != null && !state.homeMenuOpen) { arrange.folder = null }
    BackHandler(enabled = marks.selected != null && !state.homeMenuOpen) { marks.selected = null }
    BackHandler(enabled = state.drawing && !state.homeMenuOpen) { state.stopDrawing() }
    LaunchedEffect(engine) {
        engine.launcherAction = { action ->
            when (action) {
                // "themes" from before the screen was style; widgets still send it.
                "style", "themes" -> state.styleOpen = true
                "agents" -> state.agentsOpen = true
                "oma" -> openOmaChat(context)
                "store" -> state.storeOpen = true
                "linux" -> state.linuxOpen = true
                "drawer" -> state.open()
            }
        }
    }
    // Folding shows the page last touched; unfolding shows the pair it's in.
    var shownPer by remember { mutableIntStateOf(per) }
    LaunchedEffect(per) {
        if (per == shownPer) return@LaunchedEffect
        shownPer = per
        pager.scrollToPage(fold.slot)
    }
    // Turning to other pages makes the left one of them the page last touched, until a finger lands on the right one.
    // The drawer pins apps to it.
    LaunchedEffect(pager.currentPage, pages, state.overviewOpen) {
        val shown = pagesAt(pager.currentPage, pages, per).filterNotNull()
        // Not while the overview moves pages about under the pager: the page he came from is still the one to go back to.
        if (state.overviewOpen) {
            engine.activePage = shown.firstOrNull()
            return@LaunchedEffect
        }
        if (state.currentPage !in shown) shown.firstOrNull()?.let { state.currentPage = it }
        engine.activePage = shown.firstOrNull()
    }
    LaunchedEffect(per) { engine.activeSpan = per }
    // Folded, unfolded or turned in the middle of a stroke: the page is somewhere else now, so the stroke ends
    // here and is kept as it was drawn. Drawing mode stays on.
    LaunchedEffect(per, window) { marks.finish() }
    // The background slides with the pages, like stock launchers. Our live wallpaper gets it through Android.
    val view = LocalView.current
    // A widget's haptic() taps through home's view, while home shows.
    androidx.compose.runtime.DisposableEffect(view) {
        engine.view = view
        onDispose { if (engine.view === view) engine.view = null }
    }
    LaunchedEffect(pager, view) {
        val wallpapers = WallpaperManager.getInstance(view.context)
        snapshotFlow {
            val offset = wallpaperOffset(pager.currentPage, pager.currentPageOffsetFraction, pager.pageCount)
            // Also resent when our live wallpaper is picked, which starts out centered.
            offset to runtime.themes.liveWallpaperActive
        }.collect { (offset, live) ->
            state.wallpaperOffset = offset
            if (live) {
                view.windowToken?.let {
                    wallpapers.setWallpaperOffsetSteps(if (pager.pageCount > 1) 1f / (pager.pageCount - 1) else 1f, 1f)
                    wallpapers.setWallpaperOffsets(it, offset, 0.5f)
                }
            }
        }
    }
    // A terminal tile keeps the keyboard only while its page shows, and a note or a sticker stays selected only while its does.
    LaunchedEffect(pager.currentPage) {
        terminals.deactivate()
        marks.selected = null
    }
    // Its shell exited, or the app lost focus: the soft keyboard goes too.
    val typing = terminals.active != null
    LaunchedEffect(typing) { if (!typing) terminals.keyboard?.hide() }
    LaunchedEffect(state.requestedPage) {
        val at = state.requestedPage ?: return@LaunchedEffect
        state.requestedPage = null
        pages.getOrNull(at)?.let {
            state.currentPage = it
            // Not in this effect: clearing the request restarts it, which would stop the turn as it began.
            scope.launch { pager.animateScrollToPage(at / per) }
        }
    }

    // The Manga look's panels, once the switch into it is handled: the fit gathers and places everything first.
    val panelLook = manga?.takeIf { MangaHome.ready }

    /** Whether [page] is a manga page with a hero: art in its top panel, as wide as the page, running up to the screen's top. */
    fun heroOn(page: String?): Boolean =
        page != null && panelLook != null &&
            hasHero(engine.notebook.pages.firstOrNull { it.id == page }?.panels, engine.notebook.rows, panelLook, engine.itemsOn(page), engine.layout::cell, arrange.carry?.item?.key)

    /** Whether the page at [side] of pager slot [slot] has a hero. */
    fun heroAt(
        slot: Int,
        side: Int,
    ) = heroOn(pagesAt(slot, pages, per).getOrNull(side))

    // What a hero runs up into: the page names' band and, above home, the status bar, to the top of the screen. Only while a
    // page with one is showing or a turn away, so every other page's pager is as it always was.
    val heroRoom = with(density) { PageNameHeight.roundToPx() + WindowInsets.systemBars.union(WindowInsets.displayCutout).getTop(density) }
    val heroNear = (pager.currentPage - 1..pager.currentPage + 1).any { slot -> (0 until per).any { heroAt(slot, it) } }
    // Where across the screen each page name starts, as a fraction of its width: a hero under it letters it light.
    val nameAt = with(density) { (PageGap + 12.dp).toPx() } / window.width.coerceAtLeast(1)
    val namesOverHero by remember(pages, per, panelLook, nameAt) {
        derivedStateOf {
            val position = pager.currentPage + pager.currentPageOffsetFraction
            (0 until if (Pack.ownsPage) 1 else per).map { i -> heroUnder(i.toFloat() / per + nameAt, position, per, ::heroAt) }
        }
    }
    // The status bar's icons are light while heroes run under all of it; once a turn is half way off them, as they always were,
    // and while home's menu or the overview covers the hero.
    val heroUnderBar by remember(pages, per, panelLook) {
        derivedStateOf { !state.overviewOpen && !state.homeMenuOpen && pinch.value > 0.99f && heroAcross(pager.currentPage, per, ::heroAt) }
    }
    SideEffect { state.heroUnderBar = heroUnderBar }
    androidx.compose.runtime.DisposableEffect(state) { onDispose { state.heroUnderBar = false } }

    fun openMenu(
        item: HomeItem?,
        from: Offset? = null,
        at: Pair<Float, Float>? = null,
    ) {
        menuFor = item
        state.homeMenuFrom = from
        state.homeMenuAt = at
        state.homeMenuOpen = true
    }

    fun remove(item: HomeItem) {
        state.editing = null
        when (item) {
            is WidgetItem -> engine.removeWidget(item.spec)
            is Shortcut, is Folder -> engine.shortcuts.remove(item.key)
            // Nothing of a thread's tile is kept but its place, which goes below; it stays off until its thread next starts work.
            is ThreadItem -> {}
            // A module is its place alone: it is back from the widget store whenever he wants it.
            is ModuleItem -> {}
            // Its picture stays in the art store, for an undo or a saved layout that has it.
            is ArtItem -> {}
        }
        engine.layout.remove(item.key)
    }

    /** Opens the default agent on the widget's file; with no agent installed, the agents screen instead. */
    fun edit(item: WidgetItem) {
        state.editing = null
        val agent = runtime.agents.default
        if (agent == null) state.agentsOpen = true else runtime.terminal(editWidgetCommand(agent.bin, item.spec), "Edit ${item.spec.name}", agent)
    }

    // Overlays below are siblings of the pager inside this Box, so they never change the page's size.
    val ime = WindowInsets.ime
    // Where the keyboard ends up, so a terminal tile resizes once rather than with every frame of its animation.
    @OptIn(ExperimentalLayoutApi::class)
    val imeTarget = WindowInsets.imeAnimationTarget
    val statusBar = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    // Back with the soft keyboard up only reaches the keyboard app, which closes itself. Closed like that
    // (not by the tile's ⌨ button), the tile lets go too, so the bar and recent apps come back. Only once it's gone:
    // hiding it again mid-way, while a back swipe still drives its animation, left home taking no touches.
    LaunchedEffect(terminals) {
        snapshotFlow { ime.getBottom(density) > 0 }.collect { shown ->
            if (!shown && terminals.keyboard?.wanted == true) terminals.deactivate()
        }
    }
    // The bar and recent apps don't wait for that: back as the keyboard starts closing, like the pages.
    LaunchedEffect(terminals) {
        snapshotFlow { imeTarget.getBottom(density) == 0 && terminals.keyboard?.wanted == true }.collect { terminals.keyboardLeaving = it }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // The widget input that has the keyboard, where it is with the page not slid up; and how far the page is slid for it.
    var typingAt by remember { mutableStateOf<Rect?>(null) }
    val slid = remember { floatArrayOf(0f) }
    val reportTyping = remember { { at: Rect? -> typingAt = at?.translate(0f, slid[0]) } }
    val extraKeys = terminals.active != null && extraKeysWanted()
    val side = homeSide()
    val sideRoom = sideWidth()
    val barRoom = with(density) { state.barHeight.takeIf { it > 0 }?.toDp() ?: (PromptBarHeight + 12.dp) }
    LaunchedEffect(extraKeys) { if (!extraKeys) terminals.extraKeys = null }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Sees every press first, without taking it: one outside the active terminal tile ends its turn.
            .pointerInput(terminals) {
                awaitEachGesture { terminals.pressAt(origin + awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position) }
            },
    ) {
        // Owns the soft keyboard for terminal tiles.
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { sink -> terminals.keyboard = sink } },
            onRelease = { if (terminals.keyboard === it) terminals.keyboard = null },
            modifier = Modifier.size(1.dp),
        )
    Column(
        Modifier
            .fillMaxSize()
            // Typing in a terminal tile: the keyboard never shrinks or re-flows the grid. The pages slide up so the tile
            // stays above it, but not under the status bar; a tile too tall for both gives up the rest at its bottom.
            .graphicsLayer {
                val keys = if (extraKeys) ExtraKeysHeight.toPx() else 0f
                val ceiling = statusBar.getTop(density) + 6.dp.toPx()
                val forTerminal =
                    terminals.liftFor(
                        imeHeight = ime.getBottom(density),
                        imeTarget = imeTarget.getBottom(density),
                        windowHeight = view.height,
                        margin = 8.dp.toPx() + keys,
                        top = ceiling,
                        minHeight = 64.dp.toPx(),
                    )
                // An input in a widget: the same slide, clear of the dock too, which the keyboard lifts with it.
                val forInput = typingAt?.let { slideUp(it.top, it.bottom, ime.getBottom(density), view.height, 8.dp.toPx() + barRoom.toPx(), ceiling) } ?: 0f
                slid[0] = forInput
                translationY = -(if (terminals.active != null) forTerminal else forInput)
            }
            // Nothing below where the typing tile ends, above the extra keys, so the gaps around them show the wallpaper, and
            // nothing lifted under the status bar.
            .drawWithContent {
                val keyboard = ime.getBottom(density)
                if (terminals.active == null || keyboard == 0) {
                    drawContent()
                } else {
                    val keys = if (extraKeys) ExtraKeysHeight.toPx() else 0f
                    val lift = terminals.lift - origin.y
                    clipRect(
                        top = statusBar.getTop(density) + lift,
                        bottom = view.height - keyboard - keys - 8.dp.toPx() + lift,
                    ) { this@drawWithContent.drawContent() }
                }
            }
            // Room for the bar, which DesktopScreen draws over the pages (so the keyboard lifts it without shrinking
            // the grid). The page's room ends above the strip, a gap, the bar and a gap: the bar as
            // measured, since a large font makes it taller. In a phone's landscape they stand beside it instead.
            .padding(
                start = if (side == HomeSide.Left) sideRoom else 0.dp,
                end = if (side == HomeSide.Right) sideRoom else 0.dp,
                bottom = if (side != null) 0.dp else barRoom,
            ),
    ) {
      CompositionLocalProvider(LocalTypingAt provides reportTyping) {
        PageNames(
            // Not before the notebook is read: the page shown until then is a stand-in.
            names = pagesAt(pager.currentPage, pages, per).map { page -> page?.takeIf { engine.notebook.opened }?.let(engine.notebook::name) },
            first = pager.currentPage * per,
            total = pages.size,
            hidden = { state.homeCovered || engine.openScreen != null },
            // Gone as the pages shrink under a pinch: the overview names every page itself.
            faded = { maxOf(state.drawer.value, (1f - pinch.value) / (1f - OVERVIEW_AT)) },
            // The pages showing are caught as they are on the way in.
            onOpen = {
                runtime.pageStills.request()
                state.openOverview()
            },
            overHero = namesOverHero,
            // 5. Not under a terminal tile's keyboard, as on the pages. Over the pages while a hero runs up under it from them.
            modifier = Modifier.zIndex(if (heroNear) 1f else 0f).opensDrawer(state, windowHeight) { terminals.active == null },
        )
        HomeGrid(
            pager = pager,
            pages = pages,
            rows = engine.notebook.rows,
            itemsOn = engine::itemsOn,
            layout = engine.layout,
            arrange = arrange,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            selected = state.editing,
            onSelect = {
                state.editing = it
                // One thing is edited at a time: a tile, or a note.
                if (it != null) marks.selected = null
            },
            actions = { item ->
                // A pinned Android app offers its shortcuts above the bar, as its long-press menu in the drawer does.
                val app = (item as? Shortcut)?.component?.let { c -> runtime.androidApps.apps.firstOrNull { it.component == c && it.profile == item.profile } }
                val (menu, offered) =
                    offeredShortcuts(item.key) {
                        app?.let { shortcutMenu(runtime, it, page = { engine.notebook.pageOf(item.key) ?: state.currentPage }, onOpen = { arrange.folder = null }) }
                    }
                // Nothing until they're loaded, so the bar doesn't jump down as they arrive.
                if (offered != null) {
                    Column {
                        if (menu != null && offered.isNotEmpty()) {
                            MenuItems(emptyList(), { state.editing = null }, minWidth = 160.dp, itemPadding = 12.dp, fontSize = Pack.size.item) {
                                ShortcutRows(offered, menu, { state.editing = null }, Pack.size.item, rule = false)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        EditBar {
                            if (item is WidgetItem) EditAction("edit") { edit(item) }
                            // As a QR code or link, or (your own) to the store. An agent's tile is a terminal, not a widget to pass on.
                            if (item is WidgetItem && !item.spec.file.name.startsWith("agent-")) {
                                EditAction("share") {
                                    state.editing = null
                                    state.sharing = item.spec
                                }
                            }
                            // Not in a session that isn't being saved: the widget's file would be gone and the store still list it.
                            if (engine.notebook.kept) EditAction("remove", Pack.red) { remove(item) } else EditAction("can't remove: not saved", Pack.muted) {}
                        }
                    }
                }
            },
            render = { item, tile, highlighted ->
                when (item) {
                    is WidgetItem -> {
                        // Whose terminal has the keyboard: the tile gives up what the keyboard covers once the lift is spent.
                        val typing = engine.instance(item.spec)?.owns(terminals.active) == true
                        WidgetTile(
                            spec = item.spec,
                            instance = engine.instance(item.spec),
                            cells = engine.layout.cell(item.key)?.let { it.w to it.h },
                            // Accent like Hyprland's active border: only while one of its terminals has the keyboard.
                            focused = highlighted || typing,
                            modifier =
                                if (typing) {
                                    tile
                                        .onGloballyPositioned { terminals.tile = it.boundsInWindow().translate(0f, terminals.lift) }
                                        .padding(bottom = with(density) { terminals.cut.toDp() })
                                } else {
                                    tile
                                },
                            onTap = { engine.tap(item.spec) },
                            needs = engine.missing[item.spec.file.name].orEmpty(),
                            install = engine.installs[item.spec.file.name],
                            onInstall = { engine.installDependencies(item.spec) },
                            waitsForLinux = if (engine.waitsForLinux(item.spec)) linuxState else null,
                            onInstallLinux = { state.linuxOpen = true },
                            tiles = state.tiles,
                            onFix = { log ->
                                val agent = runtime.agents.default
                                if (agent == null) {
                                    state.agentsOpen = true
                                } else {
                                    runtime.terminal(fixDependenciesCommand(agent.bin, item.spec, log), "Fix ${item.spec.name}", agent)
                                }
                            },
                        )
                    }
                    is Shortcut ->
                        ShortcutTile(
                            shortcut = item,
                            icon = runtime.shortcutIcon(item, Pack.monoIcons),
                            focused = highlighted,
                            modifier = tile,
                            onTap = {
                                // Opening an app from a folder closes the folder, like Android.
                                arrange.folder = null
                                runtime.open(item)
                            },
                            paused = runtime.androidApps.paused(item.profile),
                        )
                    // In a manga page's panel, a folder is the page's cast: its apps side by side, each a tap away.
                    is Folder ->
                        if (LocalInPanel.current) {
                            CastTile(
                                folder = item,
                                icons = item.apps.map { runtime.shortcutIcon(it, Pack.monoIcons) },
                                modifier = tile,
                                onOpen = { app -> runtime.open(app) },
                                onMore = { arrange.folder = item.key },
                            )
                        } else FolderTile(
                            folder = item,
                            icons = item.apps.take(4).map { runtime.shortcutIcon(it, Pack.monoIcons) },
                            focused = highlighted,
                            modifier = tile,
                            onTap = { arrange.folder = item.key },
                        )
                    is ModuleItem -> {
                        val cell = engine.layout.cell(item.key)
                        val ref = moduleRef(item.key)
                        // Read here so the tile draws again at each look; one this build doesn't know keeps its place, blank.
                        val now = CockpitModules.tick
                        if (ref == null || cell == null) {
                            androidx.compose.foundation.layout.Box(tile)
                        } else {
                            // A tap the module has no thread for (nothing waits, nothing runs) still opens the chat, on Soma's own tab.
                            androidx.compose.foundation.layout.Box(tile.pointerInput(item.key) { detectTapGestures(onTap = { openOmaChat(context) }) }) {
                                CompositionLocalProvider(LocalModuleFocus provides highlighted) {
                                    ModuleView(
                                        ref,
                                        moduleSize(cell.w, cell.h, GRID_COLUMNS),
                                        CockpitModules.source(context, runtime),
                                        now,
                                        Modifier.fillMaxSize(),
                                        onOpen = { key -> openOmaChat(context, thread = key) },
                                        onNew = { openOmaChat(context, thread = newChatTab(CockpitModules.host(context, runtime))) },
                                    )
                                }
                            }
                        }
                    }
                    // Printed in ink in the Manga look unless he picked color; in its own colors in every other look.
                    is ArtItem -> artIdOf(item.key)?.let { ArtImage(it, tile, ink = manga != null && MangaPrefs.artInk) } ?: Box(tile)
                    is ThreadItem ->
                        AgentTileView(
                            tile = engine.agentTiles.tiles[item.key],
                            key = item.key,
                            focused = highlighted,
                            modifier = tile,
                            onTap = { openOmaChat(context, thread = tileThread(item.key)) },
                        )
                }
            },
            // The recent apps strip's room: kept while it's empty, so tiles don't reflow when it fills, and still
            // home's to swipe and long-press there until it does.
            room = if (Pack.recentApps && side == null) RecentStripHeight else 0.dp,
            per = per,
            ready = engine.notebook.opened,
            drawing = state.drawing,
            marks = marks,
            captures = runtime.pageStills,
            // Not under a terminal tile's keyboard: the list comes up with no keyboard showing.
            onDrawerDrag = { dy -> if (terminals.active == null) state.dragBy(-dy / windowHeight) },
            onDrawerEnd = { velocity -> if (terminals.active == null) state.settle(velocity) },
            pinch = { pinch.value },
            onPinch = { scale ->
                // The pages are caught as they were when the pinch began.
                if (!pinching[0]) {
                    pinching[0] = true
                    runtime.pageStills.request()
                }
                scope.launch { pinch.snapTo(scale) }
            },
            onPinchEnd = { scale ->
                pinching[0] = false
                // Not over a stand-in page: there is no notebook to show until it is read.
                if (settlesIntoOverview(scale) && engine.notebook.opened) state.openOverview()
                scope.launch { pinch.moveTo(1f) }
            },
            onLongPressEmpty = { from, at -> openMenu(null, from, at) },
            arrivals = state.arrivals,
            rooms = state.pageRooms,
            // A thread's tile is placed with its cells and has no page to be moved on to: the launcher puts one on the
            // Agents page or nowhere. One that has lost its place is taken off, not sent to a page of his.
            onNoRoom = { item ->
                val panels = manga != null && engine.notebook.pages.firstOrNull { it.id == engine.notebook.pageOf(item.key) }?.panels != null
                when {
                    item is ThreadItem -> engine.layout.remove(item.key)
                    // Off a manga page with no empty panel: to the next page with one, or a strip page made for it.
                    panels ->
                        showPage = engine.notebook.overflowIntoPanels(item.key, manga!!.defaultLayout, { p -> engine.layout.claim(item, engine.itemsOn(p), p) })
                    else -> showPage = engine.moveToFreePage(item)
                }
            },
            onMove = { item, page ->
                engine.moveToPage(item, page)
                showPage = page
            },
            onTouch = { state.currentPage = it },
            // Only while the pages are odd, which is when the empty half shows: a second tap on its heels adds nothing.
            onAddPage = { if (engine.pages.size % 2 == 1) showPage = cut(engine.addPage()) },
            onOfferPage = { cut(engine.addPage())?.also { showPage = it } },
            onWithdrawPage = { engine.notebook.withdrawPage(it) },
            onGroup = { app, target -> engine.shortcuts.group(app, target) },
            onReorder = { app, before -> engine.shortcuts.moveBefore(app.key, before) },
            panelLook = panelLook,
            panelLayout = { page -> engine.notebook.pages.firstOrNull { it.id == page }?.panels },
            // An empty panel opens the photo picker for itself: what is picked hangs there.
            onPanelTap = { page, panel -> ArtPickActivity.open(context, page, panel) },
            above = if (heroNear) heroRoom else 0,
        )
      }
    }

    // What the start had to say about the layout (a migration that moved tiles, or couldn't be saved), and what
    // the pages have to say about their ink and notes (a file that can't be read, a page full of ink), until tapped away.
    Column(Modifier.align(Alignment.TopCenter).padding(start = PageGap, end = PageGap, top = PageNameHeight)) {
        engine.notice?.let { text -> Notice(text) { engine.notice = null } }
        marks.book?.let { book -> book.notice?.let { text -> Notice(text) { book.notice = null } } }
    }

    // The herd sheep: every coding agent on every machine of the tailnet, in home's corner above the bar. It steps
    // aside while home is covered or being changed, so it never sits on what he is working on.
    LaunchedEffect(Unit) { HerdSheep.restore(context) }
    // While it floats over everything (phone control on), that one is the sheep, home included.
    if (HerdSheep.shown && !HerdSheep.floating && !state.homeCovered && engine.openScreen == null && state.editing == null && !state.drawing && !state.overviewOpen) {
        HerdPet(Modifier.align(Alignment.BottomEnd).padding(bottom = barRoom))
    }

    if (extraKeys) {
        ExtraKeys(
            runtime.keyboard,
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .onGloballyPositioned { terminals.extraKeys = it.boundsInWindow() },
        )
    }

    }
}

/** One thing the owner has to be told, over the top of the page until he taps it away. */
@Composable
private fun Notice(
    text: String,
    onHide: () -> Unit,
) {
    Column(Modifier.padding(bottom = PageGap).panel().clickable(onClick = onHide).padding(12.dp)) {
        BasicText(text, style = Pack.body())
        BasicText("tap to hide", style = Pack.body(Pack.size.small, Pack.muted))
    }
}

/**
 * A page name over a hero's art: the look's paper for letters, on no plate, over a soft shadow of its ink, so it reads over
 * halftone and color alike. Only the Manga look has heroes: its paper is light and its ink dark.
 */
internal fun heroLettering(density: androidx.compose.ui.unit.Density) =
    with(density) { TextStyle(color = Pack.paper, shadow = Shadow(Pack.foreground.copy(alpha = 0.85f), Offset(0f, 1.dp.toPx()), 6.dp.toPx())) }

/**
 * The prototype's folio: the names of the pages showing joined with a plus, then the first one's number out of
 * all, as in "Today + Week  1/5". Null when no page showing has a name yet.
 */
internal fun folio(
    names: List<String?>,
    first: Int,
    total: Int,
): String? {
    val named = names.filterNotNull().filter { it.isNotBlank() }
    if (named.isEmpty()) return null
    val place = if (total > 1) "  ${first + 1}/$total" else ""
    return named.joinToString(" + ") + place
}

/**
 * The name of the page showing, top-left over it; of two pages side by side, each over its own half.
 * [hidden] and [faded] (the drawer's position, 0 to 1) are read while drawing: under the app list or a
 * full-screen panel it isn't there. A tap opens the overview of all pages ([onOpen]).
 */
@Composable
private fun PageNames(
    names: List<String?>,
    /** The first showing page's place among all [total], from 0. */
    first: Int,
    total: Int,
    hidden: () -> Boolean,
    faded: () -> Float,
    onOpen: () -> Unit,
    /** For each name shown, whether a hero's art is under it: then it is lettered light on no plate, over a soft shadow. */
    overHero: List<Boolean> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Row(
        modifier
            .fillMaxWidth()
            .height(PageNameHeight)
            .graphicsLayer { alpha = if (hidden()) 0f else 1f - faded().coerceIn(0f, 1f) },
    ) {
        // A look with its own page has the prototype's one folio: the names showing and where they are in the notebook.
        val shown = if (Pack.ownsPage) listOf(folio(names, first, total)) else names
        for ((i, name) in shown.withIndex()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (name != null) {
                    val hero = overHero.getOrElse(i) { false }
                    BasicText(
                        name,
                        Modifier
                            .padding(start = PageGap)
                            .clickable(onClick = onOpen)
                            .then(if (hero) Modifier else Modifier.background(Pack.folioPlate))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = if (hero) Pack.folio.merge(heroLettering(density)) else Pack.folio,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
