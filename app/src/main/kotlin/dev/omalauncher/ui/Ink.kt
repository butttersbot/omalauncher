package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as Outline
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.pack.InkBlend
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.AnnotationLimits
import dev.omalauncher.widgets.annotations.ERASER_REACH
import dev.omalauncher.widgets.annotations.Ink
import dev.omalauncher.widgets.annotations.InkRole
import dev.omalauncher.widgets.annotations.InkTool
import dev.omalauncher.widgets.annotations.PageArea
import dev.omalauncher.widgets.annotations.STROKE_TOLERANCE
import dev.omalauncher.widgets.annotations.Stroke
import dev.omalauncher.widgets.annotations.simplify
import dev.omalauncher.widgets.annotations.touches
import dev.omalauncher.widgets.annotations.unit
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.hypot

/** What one finger does to the page in drawing mode. */
enum class DrawTool {
    Pen,
    Highlighter,

    /** Takes away every stroke it touches, whole. */
    Eraser,
}

/** A note or a sticker, by the page it is on and its id there. */
data class Chosen(
    val page: String,
    val id: String,
)

/** How many times the pen's width the highlighter's is. */
const val HIGHLIGHTER_WIDE = 5f

/** A finger moves this many pixels before the line gets another point. */
private const val MIN_STEP = 1.5f

/**
 * What home knows of drawing and of the notes and stickers while it shows: the tool in hand, the stroke under
 * the finger, and which note or sticker is selected. What is on the pages themselves is the [book]'s.
 */
@Stable
class Marks {
    /** The pages' annotations; null until home has them. */
    var book by mutableStateOf<AnnotationBook?>(null)

    var tool by mutableStateOf(DrawTool.Pen)

    /** The pen writes in the pack's second ink. */
    var second by mutableStateOf(false)

    /** The stroke being drawn. */
    internal val live = LiveStroke()

    /** The note or sticker that is selected: its handle and its bar show. */
    var selected by mutableStateOf<Chosen?>(null)

    /** Where the selected one's bar is, in its page's coordinates; taps there are the bar's. */
    internal var bar by mutableStateOf<IntRect?>(null)

    /** A finger is holding the selected one; its bar waits for release. */
    internal var holding by mutableStateOf(false)

    /** The note whose text is being typed. */
    var editing by mutableStateOf<Chosen?>(null)

    /**
     * A finger came down at [at] to draw on [page], which is at [area] in the same coordinates; [pen] is the
     * pen's width there in pixels. False if nothing comes of this touch: the page takes no ink (and says why),
     * or the finger is off the page.
     */
    internal fun begin(
        page: String,
        area: PageArea,
        at: Offset,
        pen: Float,
    ): Boolean {
        val book = book ?: return false
        val fx = area.across(at.x)
        val fy = area.down(at.y)
        if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) return false
        if (if (tool == DrawTool.Eraser) !book.takes(page) else !book.roomToDraw(page)) return false
        live.start(page, area, tool, if (tool == DrawTool.Highlighter) InkRole.Highlighter else if (second) InkRole.Second else InkRole.Pen, pen * if (tool == DrawTool.Highlighter) HIGHLIGHTER_WIDE else 1f)
        if (tool == DrawTool.Eraser) live.from = book.ink(page)
        extend(at)
        return true
    }

    /** The finger is at [at] now. */
    internal fun extend(at: Offset) {
        val page = live.page ?: return
        if (live.tool != DrawTool.Eraser) {
            live.add(at)
            return
        }
        val from = live.from ?: return
        val area = live.area
        // Along the way from where it last was, so a quick pass doesn't step over a line.
        val before = live.eraser ?: at
        val steps = ceil(hypot(at.x - before.x, at.y - before.y) / (area.length(ERASER_REACH) / 2f).coerceAtLeast(1f)).toInt().coerceIn(1, 64)
        var more = false
        for (step in 1..steps) {
            val x = area.across(before.x + (at.x - before.x) * step / steps)
            val y = area.down(before.y + (at.y - before.y) * step / steps)
            for (i in from.strokes.indices) if (i !in live.hit && touches(from.strokes[i], x, y)) more = live.hit.add(i) || more
        }
        live.eraser = at
        live.tick++
        if (more) book?.erase(page, from, live.hit)
    }

    /** The finger lifted: the stroke is the page's now. An eraser's pass already is. */
    internal fun finish() {
        val page = live.page ?: return
        if (live.tool != DrawTool.Eraser && live.count > 0) {
            val area = live.area
            val points = FloatArray(live.count * 2) { i -> unit(if (i % 2 == 0) area.across(live.points[i]) else area.down(live.points[i])) }
            val tool = if (live.tool == DrawTool.Highlighter) InkTool.Highlighter else InkTool.Pen
            book?.draw(page, Stroke(tool, live.role, area.share(live.width), simplify(points, STROKE_TOLERANCE)))
        }
        live.clear()
    }

    /** The touch turned out not to be drawing (a second finger landed): nothing of it stays. */
    internal fun cancel() {
        val page = live.page ?: return
        // What this pass of the eraser took is back.
        live.from?.let { from -> if (live.hit.isNotEmpty()) book?.erase(page, from, emptyList()) }
        live.clear()
    }
}

/**
 * The stroke under the finger, in the pixels it is drawn at. Plain fields, not state, so a new point
 * recomposes nothing; [tick] alone is read where the stroke is drawn, and redraws only that.
 */
internal class LiveStroke {
    var tick by mutableIntStateOf(0)
    var page: String? = null
        private set
    var area = PageArea(0f, 0f, 1f, 1f)
        private set
    var tool = DrawTool.Pen
        private set
    var role = InkRole.Pen
        private set

    /** In pixels. */
    var width = 0f
        private set
    var points = FloatArray(4 * AnnotationLimits.POINTS + 2)
        private set
    var count = 0
        private set

    /** Where the eraser is, and the ink as it was when it came down with the places of what it has touched since. */
    var eraser: Offset? = null
    var from: Ink? = null
    val hit = HashSet<Int>()

    fun start(
        page: String,
        area: PageArea,
        tool: DrawTool,
        role: InkRole,
        width: Float,
    ) {
        clear()
        this.page = page
        this.area = area
        this.tool = tool
        this.role = role
        this.width = width
    }

    fun add(at: Offset) {
        if (count > 0 && hypot(at.x - points[2 * count - 2], at.y - points[2 * count - 1]) < MIN_STEP) return
        if (2 * count + 2 > points.size) {
            // A very long line is thinned as it goes, so it never holds more than a stroke may keep twice over.
            val thin = simplify(points.copyOf(2 * count), area.length(STROKE_TOLERANCE), AnnotationLimits.POINTS, 1f)
            thin.copyInto(points)
            count = thin.size / 2
        }
        points[2 * count] = at.x
        points[2 * count + 1] = at.y
        count++
        tick++
    }

    fun clear() {
        page = null
        count = 0
        eraser = null
        from = null
        hit.clear()
        tick++
    }
}

/**
 * One finger draws: follows the finger that came [down] on [page] until it lifts, and takes every move from
 * what is under it, so no tile is tapped and no widget dragged through the pen. A second finger ends the
 * stroke with nothing drawn: two fingers are the pinch's, which the [arbiter] has said by then or will.
 */
internal suspend fun AwaitPointerEventScope.draw(
    down: PointerInputChange,
    marks: Marks,
    page: String,
    area: PageArea,
    pen: Float,
    arbiter: GestureArbiter,
) {
    down.consume()
    var drawing = marks.begin(page, area, down.position, pen)
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val finger = event.changes.firstOrNull { it.id == down.id }
            val pinch = arbiter.owner == Owner.Pinch
            if (drawing && (pinch || event.changes.count { it.pressed } > 1)) {
                marks.cancel()
                drawing = false
            }
            // The pinch has taken its fingers' moves already; anything else stays the pen's, drawn or not.
            if (!pinch) event.changes.forEach { it.consume() }
            if (finger == null || !finger.pressed) break
            if (drawing) {
                finger.historical.forEach { marks.extend(it.position) }
                marks.extend(finger.position)
            }
        }
        if (drawing) marks.finish()
        drawing = false
    } finally {
        if (drawing) marks.cancel()
    }
}

/** The pack's ink for [role]. Read while drawing, so a change of look redraws the ink in its new color. */
private fun inkColor(role: InkRole) =
    Pack.look.pack.ink.run {
        when (role) {
            InkRole.Pen -> pen
            InkRole.Second -> second
            InkRole.Highlighter -> highlighter
        }
    }.toColor()

/** How ink meets what it crosses: over it, or darkening it like one print run over another, as the pack says. */
private fun inkBlend() = if (Pack.look.pack.ink.blend == InkBlend.Multiply) BlendMode.Multiply else BlendMode.SrcOver

/** The line through [count] points, rounded where it turns. */
private inline fun inkPath(
    count: Int,
    x: (Int) -> Float,
    y: (Int) -> Float,
): Path {
    val path = Path()
    if (count == 0) return path
    path.moveTo(x(0), y(0))
    for (i in 1 until count - 1) path.quadraticTo(x(i), y(i), (x(i) + x(i + 1)) / 2f, (y(i) + y(i + 1)) / 2f)
    if (count > 1) path.lineTo(x(count - 1), y(count - 1))
    return path
}

private fun DrawScope.drawInk(
    path: Path,
    dot: Offset?,
    role: InkRole,
    width: Float,
) {
    // A line that goes nowhere is a dot, which a path of no length doesn't draw everywhere.
    if (dot != null) {
        drawCircle(inkColor(role), width / 2f, dot, blendMode = inkBlend())
    } else {
        drawPath(path, inkColor(role), style = Outline(width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = inkBlend())
    }
}

/** The paths of a page's strokes at one size, each made once: a new stroke doesn't rebuild the two thousand before it. */
private class InkPaths {
    private var size = Size.Zero
    private val paths = IdentityHashMap<Stroke, Path>()

    fun path(
        stroke: Stroke,
        size: Size,
        of: Int,
    ): Path {
        if (size != this.size || paths.size > of + 256) {
            paths.clear()
            this.size = size
        }
        return paths.getOrPut(stroke) { inkPath(stroke.count, { stroke.points[2 * it] * size.width }, { stroke.points[2 * it + 1] * size.height }) }
    }
}

/**
 * A page's ink, over the page at [area] in its room. It lies over the tiles, notes and stickers, as in the
 * design prototype: a highlighter runs across whatever is there.
 */
@Composable
internal fun InkLayer(
    strokes: List<Stroke>,
    area: IntRect,
    modifier: Modifier = Modifier,
) {
    if (strokes.isEmpty()) return
    val density = LocalDensity.current
    val paths = remember { InkPaths() }
    Canvas(
        modifier
            .offset { IntOffset(area.left, area.top) }
            .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
            // Its own display list: a widget ticking under it doesn't draw two thousand strokes again.
            .graphicsLayer(),
    ) {
        for (stroke in strokes) {
            val b = stroke.bounds
            val dot = if (b[2] - b[0] < 1e-4f && b[3] - b[1] < 1e-4f) Offset(stroke.points[0] * size.width, stroke.points[1] * size.height) else null
            drawInk(paths.path(stroke, size, strokes.size), dot, stroke.role, stroke.width * size.width)
        }
    }
}

/**
 * The stroke under the finger, or the eraser's reach, over the pages. Drawn from [Marks.live] as it is: a new
 * point redraws this and recomposes nothing, the pages least of all.
 */
@Composable
internal fun LiveInk(
    marks: Marks,
    modifier: Modifier = Modifier,
) {
    // The back gesture is kept off the side edges where Android allows (200dp of each), so a line begun there isn't Back.
    Canvas(modifier.fillMaxSize().systemGestureExclusion()) {
        val live = marks.live
        // Read so each point drawn shows.
        live.tick
        if (live.page == null) return@Canvas
        val area = live.area
        clipRect(area.left, area.top, area.left + area.width, area.top + area.height) {
            if (live.tool == DrawTool.Eraser) {
                live.eraser?.let { drawCircle(Pack.pageLine, area.length(ERASER_REACH), it, style = Outline(1.dp.toPx())) }
            } else if (live.count > 0) {
                val points = live.points
                val dot = if (live.count == 1) Offset(points[0], points[1]) else null
                drawInk(inkPath(live.count, { points[2 * it] }, { points[2 * it + 1] }), dot, live.role, live.width)
            }
        }
    }
}

/**
 * The drawing tools, standing where the dock does while one finger draws: pen (again for the pack's second
 * ink), highlighter, eraser, undo and redo for the page last touched, and done. Its band takes every touch
 * that lands on it, so nothing under it is opened through it.
 */
@Composable
fun DrawTools(
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    if (!state.drawing) return
    val marks = state.marks
    val book = marks.book ?: return
    val page = state.currentPage
    val ink = book.ink(page)
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = with(density) { state.barHeight.toDp() }.coerceAtLeast(60.dp))
            .pointerInput(Unit) {}
            .padding(PageGap),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().panel().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tool("pen", marks.tool == DrawTool.Pen, swatch = if (marks.second) InkRole.Second else InkRole.Pen) {
                if (marks.tool == DrawTool.Pen) marks.second = !marks.second else marks.tool = DrawTool.Pen
            }
            Tool("marker", marks.tool == DrawTool.Highlighter, swatch = InkRole.Highlighter) { marks.tool = DrawTool.Highlighter }
            Tool("erase", marks.tool == DrawTool.Eraser) { marks.tool = DrawTool.Eraser }
            Tool("undo", enabled = ink.canUndo) { book.undo(page) }
            Tool("redo", enabled = ink.canRedo) { book.redo(page) }
            Tool("done") { state.stopDrawing() }
        }
    }
}

@Composable
private fun Tool(
    label: String,
    picked: Boolean = false,
    enabled: Boolean = true,
    swatch: InkRole? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) Box(Modifier.padding(end = 4.dp).size(10.dp).background(inkColor(swatch), Pack.round(5.dp)))
        BasicText(label, style = Pack.body(Pack.size.body, if (!enabled) Pack.muted else if (picked) Pack.accent else Pack.foreground), maxLines = 1)
    }
}
