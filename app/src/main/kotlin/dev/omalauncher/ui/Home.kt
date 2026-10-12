package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import android.app.WallpaperManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.WidgetItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import dev.omalauncher.Runtime
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
    BackHandler(enabled = terminals.active != null) { terminals.deactivate() }
    BackHandler(enabled = state.homeMenuOpen) { state.homeMenuOpen = false }
    BackHandler(enabled = state.editing != null && !state.homeMenuOpen) { state.editing = null }
    BackHandler(enabled = arrange.folder != null && !state.homeMenuOpen) { arrange.folder = null }
    LaunchedEffect(engine) {
        engine.launcherAction = { action ->
            when (action) {
                // "themes" from before the screen was style; widgets still send it.
                "style", "themes" -> state.styleOpen = true
                "agents" -> state.agentsOpen = true
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
    // The background slides with the pages, like stock launchers. Our live wallpaper gets it through Android.
    val view = LocalView.current
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
    // A terminal tile keeps the keyboard only while its page shows.
    LaunchedEffect(pager.currentPage) { terminals.deactivate() }
    // Its shell exited, or the app lost focus: the soft keyboard goes too.
    val typing = terminals.active != null
    LaunchedEffect(typing) { if (!typing) terminals.keyboard?.hide() }
    LaunchedEffect(state.requestedPage) {
        val at = state.requestedPage ?: return@LaunchedEffect
        state.requestedPage = null
        pages.getOrNull(at)?.let {
            state.currentPage = it
            pager.animateScrollToPage(at / per)
        }
    }

    fun openMenu(item: HomeItem?) {
        menuFor = item
        state.homeMenuOpen = true
    }

    fun remove(item: HomeItem) {
        state.editing = null
        when (item) {
            is WidgetItem -> engine.removeWidget(item.spec)
            is Shortcut, is Folder -> engine.shortcuts.remove(item.key)
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
            hidden = { state.homeCovered || engine.openScreen != null },
            // Gone as the pages shrink under a pinch: the overview names every page itself.
            faded = { maxOf(state.drawer.value, (1f - pinch.value) / (1f - OVERVIEW_AT)) },
            // The pages showing are caught as they are on the way in.
            onOpen = {
                runtime.pageStills.request()
                state.openOverview()
            },
            // 5. Not under a terminal tile's keyboard, as on the pages.
            modifier = Modifier.opensDrawer(state, windowHeight) { terminals.active == null },
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
            onSelect = { state.editing = it },
            actions = { item ->
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
                    is Folder ->
                        FolderTile(
                            folder = item,
                            icons = item.apps.take(4).map { runtime.shortcutIcon(it, Pack.monoIcons) },
                            focused = highlighted,
                            modifier = tile,
                            onTap = { arrange.folder = item.key },
                        )
                }
            },
            // The recent apps strip's room: kept while it's empty, so tiles don't reflow when it fills, and still
            // home's to swipe and long-press there until it does.
            room = if (Pack.recentApps && side == null) RecentStripHeight else 0.dp,
            per = per,
            ready = engine.notebook.opened,
            drawing = state.drawing,
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
                scope.launch { pinch.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
            },
            onLongPressEmpty = { openMenu(null) },
            onNoRoom = { item -> showPage = engine.moveToFreePage(item) },
            onMove = { item, page ->
                engine.moveToPage(item, page)
                showPage = page
            },
            onTouch = { state.currentPage = it },
            // Only while the pages are odd, which is when the empty half shows: a second tap on its heels adds nothing.
            onAddPage = { if (engine.pages.size % 2 == 1) showPage = engine.addPage() },
            onOfferPage = { engine.addPage()?.also { showPage = it } },
            onWithdrawPage = { engine.notebook.withdrawPage(it) },
            onGroup = { app, target -> engine.shortcuts.group(app, target) },
            onReorder = { app, before -> engine.shortcuts.moveBefore(app.key, before) },
        )
      }
    }

    // What the start had to say about the layout (a migration that moved tiles, or couldn't be saved), until tapped away.
    engine.notice?.let { text ->
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(start = PageGap, end = PageGap, top = PageNameHeight)
                .panel()
                .clickable { engine.notice = null }
                .padding(12.dp),
        ) {
            BasicText(text, style = Pack.body())
            BasicText("tap to hide", style = Pack.body(Pack.size.small, Pack.muted))
        }
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

/**
 * The name of the page showing, top-left over it; of two pages side by side, each over its own half.
 * [hidden] and [faded] (the drawer's position, 0 to 1) are read while drawing: under the app list or a
 * full-screen panel it isn't there. A tap opens the overview of all pages ([onOpen]).
 */
@Composable
private fun PageNames(
    names: List<String?>,
    hidden: () -> Boolean,
    faded: () -> Float,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(PageNameHeight)
            .graphicsLayer { alpha = if (hidden()) 0f else 1f - faded().coerceIn(0f, 1f) },
    ) {
        for (name in names) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (name != null) {
                    BasicText(
                        name,
                        Modifier
                            .padding(start = PageGap)
                            .clickable(onClick = onOpen)
                            .background(Pack.folioPlate)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = Pack.folio,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
