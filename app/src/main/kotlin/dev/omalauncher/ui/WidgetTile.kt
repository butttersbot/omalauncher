package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import dev.omalauncher.widgets.WidgetEngine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.omalauncher.theme.pack.scrapTilt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.lang.text
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.render.WidgetScreen
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import dev.omalauncher.widgets.runtime.WidgetInstance

@Composable
internal fun WidgetTile(
    spec: WidgetSpec,
    instance: WidgetInstance?,
    cells: Pair<Int, Int>?,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    needs: List<String>,
    install: WidgetEngine.Install?,
    onInstall: () -> Unit,
    onFix: (String) -> Unit,
    /** Where Linux stands, if the widget needs it and it isn't running: a placeholder keeps its cells. */
    waitsForLinux: LinuxState? = null,
    onInstallLinux: () -> Unit = {},
    /** Told where the tile is, for the widget's screen to grow out of and shrink back into. */
    tiles: TileBounds? = null,
) {
    val owner = remember { Any() }
    if (tiles != null) DisposableEffect(tiles, spec.file.name) { onDispose { tiles.forget(spec.file.name, owner) } }
    Box(
        modifier
            // Only listens: nothing is measured, placed or drawn differently for it.
            .then(if (tiles != null) Modifier.onGloballyPositioned { tiles.place(spec.file.name, owner, it.boundsInWindow()) } else Modifier)
            .scrap(focused, spec.file.name, dressed = true)
            .pointerInput(spec) { detectTapGestures(onTap = { onTap() }) }
            // After the pointer input: a tap on the pack's title bar is still a tap on the tile.
            .scrapContent(),
    ) {
        if (waitsForLinux != null) {
            // While an installed Linux starts up the tile stays empty; it only offers an install when there's none.
            if (waitsForLinux != LinuxState.Checking && waitsForLinux != LinuxState.Installed) {
                LinuxPlaceholder(spec.name, installing = waitsForLinux is LinuxState.Installing, onInstallLinux)
            }
        } else if (instance != null && needs.isNotEmpty()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(spec.name, style = Pack.body(color = Pack.scrapMuted))
                BasicText("needs ${needs.joinToString(", ")}", style = Pack.body(color = Pack.scrapInk))
                when (install) {
                    WidgetEngine.Install.Running -> BasicText("installing…", style = Pack.body(color = Pack.accent))
                    is WidgetEngine.Install.Failed -> {
                        BasicText(install.log.lines().lastOrNull { it.isNotBlank() }.orEmpty(), style = Pack.body(Pack.size.small, Pack.red), maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TileButton("retry", Pack.accent, onInstall)
                            TileButton("fix with agent", Pack.scrapMuted) { onFix(install.log) }
                        }
                    }
                    null -> TileButton("install", Pack.accent, onInstall)
                }
            }
        } else if (instance == null) {
            Column(Modifier.padding(12.dp)) {
                BasicText(spec.file.name, style = Pack.body(color = Pack.red))
                BasicText(spec.error.orEmpty(), style = Pack.body(Pack.size.small, Pack.scrapMuted))
            }
        } else {
            WidgetContent(instance, Modifier.fillMaxSize(), cells)
        }
    }
}

/** A widget that runs Linux commands, before Linux is installed: its name and an offer to install. */
@Composable
private fun LinuxPlaceholder(
    name: String,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(name, style = Pack.body(color = Pack.scrapMuted), maxLines = 1)
        Spacer(Modifier.weight(1f))
        BasicText("needs Linux", style = Pack.body(color = Pack.accent))
        BasicText(
            "runs commands in the optional Arch Linux",
            style = Pack.body(Pack.size.small, Pack.scrapMuted),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TileButton(if (installing) "installing…" else "install", Pack.accent, onInstall)
    }
}

@Composable
private fun TileButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier
            .framed(Pack.hair, color, radius = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = Pack.body(color = color),
    )
}

/**
 * Where each widget's tile is in the window right now, by the widget's file name, on its cell's upright
 * rectangle. A tile is here only while it is composed: one on a page that isn't showing, or that was
 * removed, has no rectangle, and its screen fades instead of growing. Plain values, read when a screen
 * opens or closes: nothing recomposes as tiles move.
 */
internal class TileBounds {
    private class Place(
        val owner: Any,
        var at: Rect,
    )

    private val places = HashMap<String, Place>()

    operator fun get(key: String): Rect? = places[key]?.at

    /** [owner], one composed tile of the widget [key], is at [at]. */
    fun place(
        key: String,
        owner: Any,
        at: Rect,
    ) {
        val place = places[key]
        if (place?.owner === owner) place.at = at else places[key] = Place(owner, at)
    }

    /** [owner]'s tile is gone. A newer tile of the same widget, composed before this one left, keeps its place. */
    fun forget(
        key: String,
        owner: Any,
    ) {
        if (places[key]?.owner === owner) places.remove(key)
    }
}

/**
 * A widget's screen growing out of its tile and shrinking back into it (PRD AC-31). One host is composed, at
 * the size it has full screen, from the moment the screen opens until it has shrunk away; [progress] (0 at the
 * tile, 1 full screen) only moves the host's clip, corners and opacity, so the widget's screen is laid out
 * once and is never started again by the animation.
 */
private class Grow(
    open: Pair<WidgetSpec, WidgetInstance>?,
) {
    /** A launcher that comes back with a screen already open shows it open. */
    val progress = Animatable(if (open != null) 1f else 0f)

    /** The tile it grows out of or shrinks into, in the window; null fades instead. */
    var from by mutableStateOf<Rect?>(null)

    /** The screen last open, kept while it shrinks away. */
    var held by mutableStateOf(open)

    /** Closed, and still shrinking away: it takes no touches of its own any more. */
    var leaving by mutableStateOf(false)

    /**
     * The widget [progress] is for. A screen composed before its own grow has begun (another widget's opened
     * while this one was closing, or this one's again after it had gone) is not shown at all until it is.
     */
    var name by mutableStateOf(open?.first?.file?.name)
        private set

    /** [screen] opened: from [tile], or from where it had shrunk to if it was on its way out. */
    suspend fun enter(
        screen: String,
        tile: Rect?,
    ) {
        if (screen != name) {
            // Another widget's screen, or this one's again after it had gone: it starts at its tile, and shows from there.
            progress.snapTo(0f)
            from = tile
            name = screen
        }
        from = tile
        progress.moveTo(1f)
    }

    /** It closed: back into [tile], where the tile is now, and then it is gone. */
    suspend fun leave(tile: Rect?) {
        from = tile
        // The limit is on the clock, not on frames: a launcher that stopped drawing mid-way still lets go.
        withTimeoutOrNull(LEAVE_LIMIT_MS) { progress.moveTo(0f) }
        end()
    }

    /** Gone, now: it has shrunk away, or that was cut short by a touch, a fold or a turn. */
    fun end() {
        // Only what is leaving ends: a way out that finishes late, after another screen has opened, takes nothing from that one.
        if (!leaving) return
        held = null
        name = null
    }
}

/**
 * The widget whose screen { } is [open], full screen over home, growing out of its tile in [tiles] and
 * shrinking back into it when it closes: by Back, by its own close, or by anything else that closes it.
 * Without a tile showing to grow from or go back to, it fades. [running] says whether a widget whose screen
 * closed is still the one running, so the screen of one that was removed or reloaded goes at once.
 */
@Composable
internal fun WidgetScreenHost(
    open: Pair<WidgetSpec, WidgetInstance>?,
    tiles: TileBounds,
    running: (WidgetSpec, WidgetInstance) -> Boolean,
    onDismiss: () -> Unit,
) {
    val grow = remember { Grow(open) }
    // Outlives the host below, which is gone as soon as its screen has shrunk away.
    val scope = rememberCoroutineScope()
    SideEffect {
        if (open != null) grow.held = open
        grow.leaving = open == null
    }
    val name = open?.first?.file?.name
    LaunchedEffect(name) {
        if (name != null) {
            grow.enter(name, tiles[name])
        } else {
            val held = grow.held ?: return@LaunchedEffect
            grow.leave(tiles[held.first.file.name])
        }
    }
    // Folded, unfolded or turned mid-way: the tile is somewhere else now, so the screen is where it was going at once.
    val window = LocalWindowInfo.current.containerSize
    LaunchedEffect(window) {
        if (!grow.progress.isRunning) return@LaunchedEffect
        if (grow.leaving) grow.end()
        grow.progress.snapTo(if (grow.leaving) 0f else 1f)
    }
    val (spec, instance) = open ?: grow.held?.takeIf { running(it.first, it.second) } ?: return

    val progress = grow.progress
    val origin = remember { floatArrayOf(0f, 0f) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val scrap = Pack.look.pack.scrap
    val tileShape = Pack.shape()
    val tilt = scrapTilt(spec.file.name, scrap.tiltMin, scrap.tiltMax)

    /** The tile in the host's own space, if enough of it shows for the screen to grow from it. */
    fun tileIn(size: Size): Rect? = grow.from?.translate(-origin[0], -origin[1])?.takeIf { growsFromTile(it, size.toRect()) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val at = it.positionInWindow()
                origin[0] = at.x
                origin[1] = at.y
            }
            // Open, this is the plain full-screen panel: no layer property is set and nothing is clipped.
            .graphicsLayer {
                val t = progress.value
                val leaving = grow.leaving
                // Not this screen's grow yet, or the last half of its way out, which is the tile's paper alone: it is
                // neither drawn nor touched, so a press there goes to home (a tap on its tile opens it again).
                if (grow.name != spec.file.name || (leaving && t < GROW_LEAVES_BY)) {
                    alpha = 0f
                    clip = true
                    shape = NowhereShape
                    return@graphicsLayer
                }
                if (t >= 1f) return@graphicsLayer
                val tile = tileIn(size)
                if (tile == null) {
                    alpha = if (leaving) leaveAlpha(t) else t.coerceIn(0f, 1f)
                } else {
                    alpha = if (leaving) leaveAlpha(t) else growAlpha(t)
                    // Clipped to the growing rectangle, which is also all of it that can be touched.
                    clip = true
                    shape = RectShape(growRect(tile, size.toRect(), t), between(cornerRadius(tileShape, tile.size, direction, density), 0f, t))
                }
            }
            // Inside the clip. A screen on its way out does nothing more: while most of it is still there, a press
            // on it ends it there and goes no further.
            .pointerInput(grow) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!grow.leaving) continue
                        event.changes.forEach { it.consume() }
                        grow.end()
                        // Not left part-way: opened again, it starts at its tile.
                        scope.launch { progress.snapTo(0f) }
                    }
                }
            },
    ) {
        WidgetScreenPanel(spec, instance, onDismiss)
    }
    // The tile's own paper over what grows out of it, leaning as the tile leans and straightening as it goes.
    // Only drawn, and only mid-way: it takes no touch and no room.
    val fill = scrap.surface.fill.toColor()
    val line = scrap.surface.borderColor.toColor()
    val lineWidth = scrap.surface.border
    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value
        if (grow.name != spec.file.name) return@Canvas
        val paper = if (grow.leaving) leavePaper(t) else growPaper(t)
        if (paper <= 0f) return@Canvas
        val tile = tileIn(size) ?: return@Canvas
        val at = growRect(tile, size.toRect(), t)
        val radius = CornerRadius(between(cornerRadius(tileShape, tile.size, direction, density), 0f, t))
        rotate(growTilt(tilt, t), pivot = at.center) {
            drawRoundRect(fill, at.topLeft, at.size, radius, alpha = paper)
            if (lineWidth > 0f) drawRoundRect(line, at.topLeft, at.size, radius, alpha = paper, style = Stroke(lineWidth.dp.toPx()))
        }
    }
}

/** The corner radius [shape] gives something of [size], in px; 0 for square corners. */
private fun cornerRadius(
    shape: Shape,
    size: Size,
    direction: LayoutDirection,
    density: Density,
): Float = (shape.createOutline(size, direction, density) as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f

/** A widget's screen { }, full screen over home in an Omarchy window titled by its `title` or the widget's name. */
@Composable
internal fun WidgetScreenPanel(
    spec: WidgetSpec,
    instance: WidgetInstance,
    onDismiss: () -> Unit,
) {
    val title = instance.program.screenTitle?.let { instance.eval(it, instance.root, "title").text() } ?: spec.name
    OverlayPanel(title, onDismiss) {
        WidgetScreen(instance, Modifier.weight(1f).fillMaxWidth())
    }
}

/** An owl app's window: its title, with a back chevron while its `back` holds, over its view, on the look's background. */
@Composable
internal fun OwlAppWindow(
    spec: WidgetSpec,
    instance: WidgetInstance,
) {
    val title = instance.program.screenTitle?.let { instance.eval(it, instance.root, "title").text() } ?: spec.name
    val up: (() -> Unit)? = if (instance.canGoUp()) { { instance.goUp() } } else null
    // Full screen like any Android app: no window border or side gap, and no ✕ (Back leaves the app and
    // Recents closes it; close() still does). With `edge` the view runs on under the navigation bar too:
    // only the keyboard pushes its bottom up.
    val insets =
        if (instance.program.edge) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).union(WindowInsets.ime)
        } else {
            WindowInsets.safeDrawing
        }
    Column(Modifier.fillMaxSize().background(Pack.background).windowInsetsPadding(insets)) {
        Row(
            Modifier
                .padding(horizontal = PanelPadding, vertical = 8.dp)
                .then(if (up != null) Modifier.clickable(onClick = up) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The font has no left chevron; the right one turned around is the same shape.
            if (up != null) Symbol(Glyph.ChevronRight, Pack.accent, Modifier.padding(end = 6.dp).rotate(180f), Pack.size.title)
            BasicText(Pack.heading(title), style = Pack.display(Pack.size.title, Pack.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WidgetScreen(instance, Modifier.weight(1f).fillMaxWidth())
    }
    if (instance.program.edge) {
        // How much of an `edge` app's bottom the navigation bar covers, for its `screenInset`; none while the keyboard is up.
        val density = LocalDensity.current
        val covered = (WindowInsets.safeDrawing.getBottom(density) - WindowInsets.ime.getBottom(density)).coerceAtLeast(0)
        val dp = with(density) { covered.toDp().value.toDouble() }
        SideEffect { instance.setScreenInset(dp) }
    }
}
