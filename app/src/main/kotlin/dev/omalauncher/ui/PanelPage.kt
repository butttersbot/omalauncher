package dev.omalauncher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.omalauncher.theme.pack.PackPanels
import dev.omalauncher.theme.pack.PackScreentone
import dev.omalauncher.theme.pack.PanelEmpty
import dev.omalauncher.widgets.ArtItem
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.layout.Panel
import dev.omalauncher.widgets.layout.PanelPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * A home page drawn as a manga page (the Manga look, piece B; the mockup's pageHTML): hairline ink around fixed panels, what
 * each holds inside its ink, screentone on the empty ones, art that bleeds off the page when its panel is as wide as the
 * page. The arranging is HomeGrid's; the rules are widgets/PanelHome.kt.
 */

/**
 * The marks a change leaves on panels: an ink wipe over what just arrived in one, and its sound effect, as the mockup's
 * "シュッ" on a swap and "パッ" on art put up. None with the system's animations off.
 */
object PanelFx {
    class Mark(
        val page: String,
        val panel: Int,
        val sfx: String?,
        val at: Long,
    )

    val marks = mutableStateListOf<Mark>()

    /** [panel] of [page] is inked again, with [sfx] lettered over it when given. */
    fun ink(
        page: String,
        panel: Int,
        sfx: String? = null,
    ) {
        if (Motion.reduced) return
        val now = SystemClock.uptimeMillis()
        // What a page that wasn't showing never played is let go, so nothing old plays when it comes into view.
        marks.removeAll { now - it.at > FX_MS * 2 }
        marks += Mark(page, panel, sfx, now)
    }

    /** The newest mark on [panel] of [page] still playing, or null. */
    fun on(
        page: String,
        panel: Int,
    ): Mark? = marks.lastOrNull { it.page == page && it.panel == panel && SystemClock.uptimeMillis() - it.at < FX_MS }
}

/** How long a mark plays: the mockup's 0.75 s lettering (its wipe is 0.42 s). */
private const val FX_MS = 750L
private const val WIPE_MS = 420

/** The mockup's wipe: quick to start, slow to finish. */
private val WIPE_EASING = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

/** The most an app's tile takes across or down in a panel. */
private val APP_MOST = 96.dp

/** How far the lettering leans, in degrees. */
private const val SFX_TILT = -12f

/** A polygon given in pixels from the top-left of what it clips. */
private class PolygonShape(
    private val pts: List<Offset>,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(polygon(pts))
}

private fun polygon(pts: List<Offset>) =
    Path().apply {
        pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }

/** Whether the side from [a] to [b] lies along the page's own edge: a bled picture's side, which has no ink. */
private fun alongEdge(
    a: PanelPoint,
    b: PanelPoint,
): Boolean {
    val e = 1e-6
    return (abs(a.x) < e && abs(b.x) < e) || (abs(a.x - 1) < e && abs(b.x - 1) < e) || (abs(a.y) < e && abs(b.y) < e) || (abs(a.y - 1) < e && abs(b.y - 1) < e)
}

/** Each panel's occupant: what is exactly in its cell, else what lies on it; one panel each. */
internal fun occupants(
    panels: List<Panel>,
    items: List<HomeItem>,
    cellOf: (String) -> Cell?,
): List<HomeItem?> {
    val used = HashSet<String>()
    val out = MutableList<HomeItem?>(panels.size) { null }
    for ((i, p) in panels.withIndex()) {
        items.firstOrNull { it.key !in used && cellOf(it.key) == p.cell }?.let {
            out[i] = it
            used += it.key
        }
    }
    for ((i, p) in panels.withIndex()) {
        if (out[i] != null) continue
        items.firstOrNull { it.key !in used && cellOf(it.key)?.overlaps(p.cell) == true }?.let {
            out[i] = it
            used += it.key
        }
    }
    return out
}

/**
 * The panels of [page]: [items] in them (each in its panel's cell, [cellOf]), on the page [area] of its room. While
 * something is carried ([carried], from this page or another) the panel it would land in is outlined in the spot color
 * ([aim], read as the finger moves, so only the ink is drawn again), and its own panel is left toned, dashed. A tap on an
 * empty panel is [onEmptyTap]'s (the art picker). [render] draws a tile; [selected] is the one being edited.
 */
@Composable
internal fun PanelPageView(
    page: String,
    items: List<HomeItem>,
    cellOf: (String) -> Cell?,
    panels: List<Panel>,
    look: PackPanels,
    area: IntRect,
    /** The page's whole room: a picture that bleeds runs off the page to its edges, as a printed bleed runs off the paper. */
    room: IntSize,
    carried: String?,
    aim: () -> Cell?,
    selected: String?,
    ready: Boolean,
    ink: Color,
    spot: Color,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
    onEmptyTap: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val held = occupants(panels, items, cellOf)

    fun px(p: PanelPoint) = Offset(area.left + p.x.toFloat() * area.width, area.top + p.y.toFloat() * area.height)

    // A bled picture's corners on the page's edge go on to the room's edge.
    fun bled(p: PanelPoint) =
        px(p).let { o ->
            val e = 1e-6
            Offset(
                when { abs(p.x) < e -> 0f; abs(p.x - 1) < e -> room.width.toFloat(); else -> o.x },
                when { abs(p.y) < e -> 0f; abs(p.y - 1) < e -> room.height.toFloat(); else -> o.y },
            )
        }

    // A tile's own outline would draw a second line inside the panel's ink: it is drawn this much larger and cut back.
    val frame = with(density) { focusWidth(Pack.look.pack.scrap.surface).dp.toPx() }
    for ((i, panel) in panels.withIndex()) androidx.compose.runtime.key(i) {
        val item = held[i]
        val lifted = item != null && item.key == carried
        val art = (item as? ArtItem)?.takeIf { !lifted }
        val shape = if (art != null) panel.bleed ?: panel.outline else panel.outline
        val pts = if (art != null && panel.bleed != null) shape.map(::bled) else shape.map(::px)
        val box = Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        val mark = PanelFx.on(page, i)
        val wipe = remember(mark) { Animatable(if (mark == null) 1f else 0f) }
        LaunchedEffect(mark) { if (mark != null) wipe.moveTo(1f, Motion.timed(WIPE_MS, WIPE_EASING)) }
        androidx.compose.runtime.key(item?.key) {
            Box(
                Modifier
                    .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                    .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                    .clip(PolygonShape(pts.map { it - box.topLeft }))
                    .wiped { wipe.value },
            ) {
                when {
                    art != null -> render(art, Modifier.fillMaxSize(), false)
                    item == null || lifted -> if (lifted || look.empty == PanelEmpty.Screentone) Screentone(look.screentone, ink, lifted, Modifier.fillMaxSize())
                    else -> {
                        val inner = panel.inner
                        val at = px(PanelPoint(inner.x, inner.y)) - box.topLeft - Offset(frame, frame)
                        // An app is an icon however big its panel: it sits in the middle at no more than an icon's size.
                        val most = if (item is dev.omalauncher.widgets.Shortcut) with(density) { APP_MOST.toPx() } else Float.MAX_VALUE
                        val w = (inner.w * area.width).toFloat().coerceAtMost(most) + 2 * frame
                        val h = (inner.h * area.height).toFloat().coerceAtMost(most) + 2 * frame
                        val roomW = (inner.w * area.width).toFloat() + 2 * frame
                        val roomH = (inner.h * area.height).toFloat() + 2 * frame
                        // The panel's inner room, with the tile in its middle cut back to its own size less its outline: the
                        // tile's own outline goes, and the panel's ink stands for it.
                        Box(
                            Modifier
                                .offset { IntOffset((at.x + frame).roundToInt(), (at.y + frame).roundToInt()) }
                                .size(with(density) { (roomW - 2 * frame).toDp() }, with(density) { (roomH - 2 * frame).toDp() }),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(with(density) { (w - 2 * frame).toDp() }, with(density) { (h - 2 * frame).toDp() }).graphicsLayer { clip = true },
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                // Larger than the room it is cut to, so laid out centred on it: the frame overhangs each side.
                                androidx.compose.runtime.CompositionLocalProvider(LocalInPanel provides true) {
                                    render(item, Modifier.requiredSize(with(density) { w.toDp() }, with(density) { h.toDp() }), selected == item.key)
                                }
                            }
                        }
                    }
                }
            }
        }
        // An empty panel takes its own taps: the art picker, for that panel. A long press is home's (its menu).
        if (item == null && carried == null && ready) {
            Box(
                Modifier
                    .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                    .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                    .pointerInput(page, i) { detectTapGestures(onLongPress = {}, onTap = { at -> if (dev.omalauncher.widgets.inside(shape, ((at.x + box.left - area.left) / area.width).toDouble(), ((at.y + box.top - area.top) / area.height).toDouble())) onEmptyTap(i) }) },
            )
        }
    }

    // The ink over everything in the panels: every side of each, but the sides a bled picture runs off the page along.
    val inkWidth = with(density) { look.inkWidth.dp.toPx() }
    val bold = with(density) { 3.dp.toPx() }
    val dash = with(density) { floatArrayOf(4.dp.toPx(), 3.dp.toPx()) }
    Canvas(Modifier.fillMaxSize().zIndex(1f)) {
        val target = aim()
        for ((i, panel) in panels.withIndex()) {
            val item = held[i]
            val lifted = item != null && item.key == carried
            val bleeds = item is ArtItem && !lifted && panel.bleed != null
            val shape = if (bleeds) panel.bleed!! else panel.outline
            val at: (PanelPoint) -> Offset = if (bleeds) ::bled else ::px
            val path = Path()
            for (j in shape.indices) {
                val a = shape[j]
                val b = shape[(j + 1) % shape.size]
                if (alongEdge(a, b)) continue
                at(a).let { path.moveTo(it.x, it.y) }
                at(b).let { path.lineTo(it.x, it.y) }
            }
            when {
                target == panel.cell && carried != null -> {
                    drawPath(polygon(shape.map(at)), spot.copy(alpha = 0.1f))
                    drawPath(path, spot, style = Stroke(bold))
                }
                lifted -> drawPath(path, ink, style = Stroke(inkWidth, pathEffect = PathEffect.dashPathEffect(dash)))
                item != null && item.key == selected -> drawPath(path, spot, style = Stroke(bold))
                else -> drawPath(path, ink, style = Stroke(inkWidth))
            }
        }
    }

    // The lettering of what just happened, over its panel.
    for ((i, panel) in panels.withIndex()) {
        val mark = PanelFx.on(page, i)?.takeIf { it.sfx != null } ?: continue
        val c = px(PanelPoint(panel.inner.x + panel.inner.w / 2, panel.inner.y + panel.inner.h / 2))
        androidx.compose.runtime.key(mark) { Sfx(mark.sfx!!, c, ink) }
    }
}

/** The mockup's lettering: paper letters outlined in ink, tilted, popping up and drifting off as they fade. */
@Composable
private fun Sfx(
    text: String,
    center: Offset,
    ink: Color,
) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.moveTo(1f, Motion.timed(FX_MS.toInt(), LinearEasing)) }
    val style = Pack.display(28.sp, Pack.paper)
    val density = LocalDensity.current
    Box(
        Modifier
            .zIndex(3f)
            .wrapContentSize(unbounded = true)
            .offset { IntOffset(center.x.roundToInt(), center.y.roundToInt()) }
            .graphicsLayer {
                val p = t.value
                // Up to 22% it swells in; then it settles and drifts up as it goes.
                val rise = if (p < 0.22f) p / 0.22f else 1f
                val scale = if (p < 0.22f) 0.5f + 0.6f * rise else 1.1f - 0.1f * ((p - 0.22f) / 0.78f)
                alpha = if (p < 0.22f) rise else 1f - (p - 0.22f) / 0.78f
                scaleX = scale
                scaleY = scale
                translationX = -size.width / 2
                translationY = -size.height / 2 - (if (p < 0.22f) 0f else 0.14f * size.height * (p - 0.22f) / 0.78f)
            }
            // Slanted as lettering is, turned on the canvas.
            .drawWithContent { rotate(SFX_TILT) { this@drawWithContent.drawContent() } },
    ) {
        val stroke = with(density) { 2.4.dp.toPx() }
        val shadow = with(density) { 2.dp.toPx() }
        BasicText(text, Modifier.graphicsLayer { translationX = shadow; translationY = shadow }, style = style.merge(TextStyle(color = ink, drawStyle = Stroke(stroke))))
        BasicText(text, style = style.merge(TextStyle(color = ink, drawStyle = Stroke(stroke))))
        BasicText(text, style = style)
    }
}

/** Draws only what an ink wipe at [progress] (0 to 1) has reached: a slanted edge sweeping from the left. */
private fun Modifier.wiped(progress: () -> Float) =
    drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
        } else {
            val w = size.width
            val h = size.height
            val edge =
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(1.4f * w * p, 0f)
                    lineTo(-0.3f * w + 1.4f * w * p, h)
                    lineTo(-0.3f * w, h)
                    close()
                }
            clipPath(edge) { this@drawWithContent.drawContent() }
        }
    }

/**
 * An empty panel's tone: dots of [tone] in [ink], darkest at the top left and faded toward the bottom right along the
 * mockup's 165° gradient. A [lifted] panel (what was in it is carried) is toned evenly, lighter.
 */
@Composable
internal fun Screentone(
    tone: PackScreentone,
    ink: Color,
    lifted: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val step = with(density) { tone.spacing.dp.toPx() }.coerceAtLeast(2f)
    val radius = with(density) { tone.dot.dp.toPx() }.coerceAtMost(step / 2)
    val brush =
        remember(step, radius, ink) {
            val side = step.roundToInt().coerceAtLeast(2)
            val tile = ImageBitmap(side, side)
            androidx.compose.ui.graphics.Canvas(tile).drawCircle(Offset(side / 2f, side / 2f), radius, Paint().apply { color = ink; isAntiAlias = true })
            ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawRect(brush)
        if (lifted) {
            drawRect(Color.Black.copy(alpha = 0.35f), blendMode = BlendMode.DstIn)
        } else {
            val theta = Math.toRadians(165.0)
            val dx = sin(theta).toFloat()
            val dy = -cos(theta).toFloat()
            val length = abs(size.width * dx) + abs(size.height * dy)
            val c = Offset(size.width / 2, size.height / 2)
            val from = c - Offset(dx, dy) * (length / 2)
            val to = c + Offset(dx, dy) * (length / 2)
            drawRect(
                Brush.linearGradient(
                    0f to Color.Black.copy(alpha = tone.opacity),
                    0.7f to Color.Black.copy(alpha = tone.fade),
                    1f to Color.Black.copy(alpha = tone.fade),
                    start = from,
                    end = to,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** Inside a manga page's panel: a folder there is drawn as its cast of apps ([CastTile]) rather than one folder badge. */
internal val LocalInPanel = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A folder in a panel, as the mockup's "cast": its apps' icons and names in rows across the panel, each opened by a tap
 * ([onOpen]); what doesn't fit is a "+N" that opens the folder ([onMore]), as a tap anywhere else in the panel does.
 */
@Composable
internal fun CastTile(
    folder: dev.omalauncher.widgets.Folder,
    icons: List<ImageBitmap?>,
    modifier: Modifier,
    onOpen: (dev.omalauncher.widgets.Shortcut) -> Unit,
    onMore: () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.pointerInput(folder.key) { detectTapGestures(onTap = { onMore() }) }.padding(10.dp)) {
        val cell = 66.dp
        val tall = 74.dp
        val columns = (maxWidth / cell).toInt().coerceAtLeast(1)
        val rows = ((maxHeight - 22.dp) / tall).toInt().coerceAtLeast(1)
        val room = columns * rows
        val shown = if (folder.apps.size > room) folder.apps.take(room - 1) else folder.apps
        androidx.compose.foundation.layout.Column {
            BasicText("CAST", Modifier.padding(bottom = 6.dp), style = Pack.mono(Pack.size.small, Pack.muted))
            for (r in 0 until rows) {
                androidx.compose.foundation.layout.Row {
                    for (c in 0 until columns) {
                        val i = r * columns + c
                        androidx.compose.foundation.layout.Box(Modifier.size(cell, tall), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                            val app = shown.getOrNull(i)
                            when {
                                app != null ->
                                    androidx.compose.foundation.layout.Column(
                                        Modifier.pointerInput(app.key) { detectTapGestures(onTap = { onOpen(app) }) },
                                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    ) {
                                        val icon = icons.getOrNull(folder.apps.indexOf(app))
                                        if (icon != null) {
                                            AppIcon(icon, app.label, Modifier.size(40.dp), Pack.monoIcons, framed = true)
                                        } else {
                                            androidx.compose.foundation.layout.Box(Modifier.size(40.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                                BasicText(Pack.placeholderText(app.label), style = Pack.placeholder(18.sp))
                                            }
                                        }
                                        BasicText(app.label, Modifier.padding(top = 3.dp), style = Pack.body(Pack.size.small), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                i == shown.size && folder.apps.size > shown.size ->
                                    BasicText("+${folder.apps.size - shown.size}", Modifier.padding(top = 10.dp), style = Pack.display(18.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}
