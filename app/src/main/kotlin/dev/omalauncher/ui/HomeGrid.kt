package dev.omalauncher.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.foundation.pager.PagerState
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.WidgetItem
import dev.omalauncher.widgets.WidgetLayout
import dev.omalauncher.widgets.layout.GridGeometry
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import kotlinx.coroutines.launch

/**
 * The home grid's pixel geometry on screen: [GridGeometry], which has the arithmetic, in Compose's
 * points and rects. The page is scaled as a whole into the room it has and centred there; [per] pages
 * stand side by side, each in a room [width] wide, a page's cells in its own room's coordinates, [local]
 * and [screen] convert.
 */
@Immutable
private class Grid(
    width: Int,
    height: Int,
    rows: Int,
    per: Int = 1,
) {
    private val geometry = GridGeometry(width, height, rows, per)
    val width get() = geometry.width
    val height get() = geometry.height
    val gap get() = geometry.gap
    val per get() = geometry.per

    /** The page itself, within its room. */
    val page get() = geometry.page.let { IntRect(it.left, it.top, it.right, it.bottom) }
    val cols get() = geometry.cols
    val rows get() = geometry.rows

    /** A screen cell's width and height. */
    val cellW get() = geometry.cellW
    val cellH get() = geometry.cellH

    fun rect(c: Cell) = geometry.rect(c).let { IntRect(it.left, it.top, it.right, it.bottom) }

    /** A saved cell as shown on this grid; only affects display, the saved cell stays as it is. */
    fun fit(
        c: Cell,
        item: HomeItem,
    ) = geometry.fit(c, item.resizable)

    /** Nearest column line to a point in the page's room. */
    fun colLine(p: Offset) = geometry.colLine(p.x)

    fun rowLine(p: Offset) = geometry.rowLine(p.y)

    /** Which of the pages on screen, left to right, a point is over. */
    fun side(p: Offset) = geometry.side(p.x)

    /** A point on screen in the coordinates of the page at [side]. */
    fun local(
        p: Offset,
        side: Int,
    ) = p - Offset(geometry.left(side).toFloat(), 0f)

    /** A rect on the page at [side] in screen coordinates. */
    fun screen(
        r: IntRect,
        side: Int,
    ) = r.translate(geometry.left(side), 0)
}

internal fun IntRect.has(p: Offset) = p.x >= left && p.x < right && p.y >= top && p.y < bottom

/** Which edges of the selected tile a press grabs; a corner grabs two. */
private data class Edges(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
) {
    val any get() = left || top || right || bottom
}

/**
 * Edges within [grab] of the frame, inside or just outside it (the handles sit on the border).
 * Side edges reach [sideGrab] inward: at the screen edge there's no outside to grab, and the
 * system back gesture claims the outermost strip.
 */
private fun edgesAt(
    rect: IntRect,
    p: Offset,
    grab: Int,
    sideGrab: Int,
): Edges {
    val inY = p.y > rect.top - grab && p.y < rect.bottom + grab
    val inX = p.x > rect.left - grab && p.x < rect.right + grab
    // Small tiles keep a movable middle: the grab zone reaches at most a third into the tile.
    val gx = minOf(sideGrab, rect.width / 3)
    val gy = minOf(grab, rect.height / 3)
    return Edges(
        left = inY && p.x > rect.left - grab && p.x < rect.left + gx,
        top = inX && p.y > rect.top - grab && p.y < rect.top + gy,
        right = inY && p.x < rect.right + grab && p.x > rect.right - gx,
        bottom = inX && p.y < rect.bottom + grab && p.y > rect.bottom - gy,
    )
}

/** A tile a finger is carrying, possibly across pages. [grab] is where the finger holds it, from its top-left corner. */
internal class Carry(
    val item: HomeItem,
    val from: String,
    val cell: Cell,
    val grab: Offset,
)

/** Arranging state shared by the pages, since a carried tile outlives the page it came from. */
@Stable
internal class Arrange {
    var carry by mutableStateOf<Carry?>(null)

    /** The finger, in page coordinates. */
    var finger by mutableStateOf(Offset.Zero)

    /** The id of the page the finger is over, where [preview] and [swapWith] apply; null over no page. */
    var page by mutableStateOf<String?>(null)

    /** A page added for the tile being carried, because it was held past the last one; taken back if nothing lands on it. */
    var offered: String? = null
    var preview by mutableStateOf<Cell?>(null)
    var swapWith by mutableStateOf<String?>(null)

    /** Dropping now puts the carried app into a folder with [swapWith], rather than swapping. */
    var grouping by mutableStateOf(false)

    /** The open folder's key; its panel covers the grid until a tap outside, Back, or an app lifted out of it. */
    var folder by mutableStateOf<String?>(null)

    /** Where the selected tile's action bar is, in page coordinates; taps there go to the bar, not the editor. */
    var bar by mutableStateOf<IntRect?>(null)

    /** A finger is holding a tile (lifted, dragging or resizing); the action bar waits for release. */
    var holding by mutableStateOf(false)

    fun drop() {
        carry = null
        preview = null
        swapWith = null
        grouping = false
    }
}

/**
 * The home pages, edited like a stock Android home screen: long-press a tile to
 * lift it and drag it right away, or release to select it. A selected widget
 * shows resize handles; drag them (or the frame) to resize, drag the tile to
 * move it, tap outside or press Back to finish. Hold a carried tile against a
 * side edge to turn the page; held past the last page, a new one is offered, which
 * stays only if the tile is dropped on it. Drop an app
 * on another app to put both in a folder, or on a folder to add it; tap a folder
 * to open it, and long-press an app in it to drag it to another place in it,
 * or back out.
 *
 * Gestures live here, around the pager, rather than on each page: a drag that
 * turns the page has to survive leaving the page it started on. [room] at the
 * bottom has no tiles but takes swipes and long-presses like empty space; what's
 * drawn over it (the recent apps strip) can take them first.
 *
 * [pages] are the notebook's page ids in order, each a grid of 6 by [rows] cells.
 * With [per] of 2 (an unfolded foldable) each pager slot shows two of them side
 * by side, and the pager counts slots; with an odd number of pages the last
 * slot's right half is empty and offers a new page ([onAddPage]).
 */
@Composable
internal fun HomeGrid(
    pager: PagerState,
    pages: List<String>,
    rows: Int,
    itemsOn: (String) -> List<HomeItem>,
    layout: WidgetLayout,
    arrange: Arrange,
    selected: String?,
    onSelect: (String?) -> Unit,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
    actions: @Composable (HomeItem) -> Unit,
    onLongPressEmpty: () -> Unit,
    onNoRoom: (HomeItem) -> Unit,
    onMove: (HomeItem, String) -> Unit,
    /** A press landed on this page: of two side by side, it is the one folding shows. */
    onTouch: (String) -> Unit,
    onAddPage: () -> Unit,
    /** Adds a page for a tile held past the last one and shows it; its id, or null if none was added. */
    onOfferPage: () -> String?,
    /** Takes back an offered page nothing was dropped on. */
    onWithdrawPage: (String) -> Unit,
    onGroup: (Shortcut, HomeItem) -> Unit,
    /** Moves an app within its folder to just before another of its apps, or with null to the end. */
    onReorder: (Shortcut, String?) -> Unit,
    modifier: Modifier = Modifier,
    room: Dp = 0.dp,
    per: Int = 1,
    /** False until the notebook is read: the one page shown then is a stand-in, not an empty page of his. */
    ready: Boolean = true,
    /** One finger draws on the page: it turns no page and lifts no tile. Nothing sets it yet. */
    drawing: Boolean = false,
    /** How far a pinch has shrunk the pages, 1 at rest; read while drawing. */
    pinch: () -> Float = { 1f },
    /** A pinch is under way, at this scale. */
    onPinch: (Float) -> Unit = {},
    /** The pinch let go, at this scale. */
    onPinchEnd: (Float) -> Unit = {},
    /** Where each page keeps a still of itself for the overview; none are kept without it. */
    captures: PageCaptures? = null,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Who each touch belongs to: every handler below asks it rather than deciding alone.
    val arbiter = remember { GestureArbiter() }
    val draws by rememberUpdatedState(drawing)
    val pinched by rememberUpdatedState(onPinch to onPinchEnd)

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // The grid stops [room] short of the bottom; the pages and their gestures go on below it.
        val height = constraints.maxHeight - with(density) { room.roundToPx() }
        val width = constraints.maxWidth / per
        // Every page is the same 6 by [rows] cells, whatever the window: only its scale follows the room it has.
        val grid = remember(width, height, rows, per) { Grid(width, height, rows, per) }
        val gap = grid.gap
        // Previews draw tiles at the size they have here.
        SideEffect { layout.geometry = Triple(grid.cellW, grid.cellH, gap) }
        val grab = with(density) { 20.dp.roundToPx() }
        val sideGrab = with(density) { 36.dp.roundToPx() }
        val edge = with(density) { 28.dp.roundToPx() }
        val current by rememberUpdatedState(Triple(pages, grid, selected))

        fun placedOn(
            page: String,
            g: Grid,
        ) = itemsOn(page).mapNotNull { item -> layout.cell(item.key)?.let { item to g.fit(it, item) } }

        /** The pages a pager slot shows, left to right; null where the last slot has no second page. */
        fun pagesAt(
            slot: Int,
            pages: List<String>,
            g: Grid,
        ) = dev.omalauncher.widgets.layout.pagesAt(slot, pages, g.per)

        /** Which of [shown] holds [key], or -1. */
        fun sideOf(
            key: String?,
            shown: List<String?>,
        ) = if (key == null) -1 else shown.indexOfFirst { page -> page != null && itemsOn(page).any { it.key == key } }

        Box(
            Modifier
                .fillMaxSize()
                // Watches every finger without taking any, and tells the arbiter how many are down and how they move.
                // Only a pinch is taken, and then from everything below: the pager, the tiles and a widget's own drag.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val slop = viewConfiguration.touchSlop
                        var pair: Pair<PointerId, PointerId>? = null
                        var span0 = 0f
                        var scale = 1f
                        var pinching = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    // The fingers' lifting is the pinch's too, never a tap on what is under them.
                                    if (pinching) event.changes.forEach { it.consume() }
                                    break
                                }
                                arbiter.pointers(pressed.size)
                                if (pressed.size >= 2) {
                                    val span = (pressed[0].position - pressed[1].position).getDistance()
                                    val ids = pressed[0].id to pressed[1].id
                                    // Another pair of fingers carries on from the scale the last pair left.
                                    if (ids != pair) {
                                        pair = ids
                                        span0 = span / scale
                                    }
                                    if (arbiter.moved(0f, 0f, span - span0, slop) == Owner.Pinch) {
                                        pinching = true
                                        scale = pinchScale(span0, span)
                                        pinched.first(scale)
                                    }
                                } else {
                                    pair = null
                                    val first = event.changes.firstOrNull { it.id == down.id }
                                    if (first != null && first.pressed) (first.position - down.position).let { arbiter.moved(it.x, it.y, 0f, slop) }
                                }
                                if (arbiter.owner == Owner.Pinch) event.changes.forEach { it.consume() }
                            }
                        } finally {
                            arbiter.up()
                            if (pinching) pinched.second(scale)
                        }
                    }
                }
                // Below the watcher, which measures the fingers on the screen, not on the page as it shrinks.
                .graphicsLayer {
                    val s = pinch()
                    scaleX = s
                    scaleY = s
                }
                .pointerInput(Unit) {
                    /**
                     * Follows the finger, carrying [item] from [cell] on page [from]; returns whether it moved.
                     * [origin] is where the tile is on screen: its cell, or for an app lifted out of a folder,
                     * its place in the open folder. Such an app sorts the folder (laid out by [folder]) while the
                     * finger stays over its panel; resting outside it closes the folder and carries the app on.
                     */
                    suspend fun AwaitPointerEventScope.moveDrag(
                        downId: PointerId,
                        start: Offset,
                        item: HomeItem,
                        cell: Cell,
                        from: String,
                        origin: IntRect,
                        folder: FolderGeometry? = null,
                    ): Boolean {
                        val inFolder = (item as? Shortcut)?.folder != null
                        val hold = start - Offset(origin.left.toFloat(), origin.top.toFloat())
                        var moved = false
                        var position = start
                        // When the finger reached a side edge; the page turns after it rests there a moment.
                        var atEdgeSince = 0L
                        // Sorting the open folder: the place the finger is over and since when, and since when it's off the panel.
                        var sorting = folder
                        var aim = -1
                        var aimSince = 0L
                        var outSince = 0L
                        while (true) {
                            val waiting = atEdgeSince > 0 || aimSince > 0 || outSince > 0
                            val event = withTimeoutOrNull(if (waiting) 50L else Long.MAX_VALUE) { awaitPointerEvent(PointerEventPass.Initial) }
                            if (event != null) {
                                val c = event.changes.firstOrNull { it.id == downId } ?: break
                                c.consume()
                                if (!c.pressed) break
                                // A second finger made a pinch of it before the tile moved: it stays where it was.
                                if (arbiter.owner == Owner.Pinch) {
                                    arrange.drop()
                                    return false
                                }
                                position = c.position
                            }
                            if (!moved && (position - start).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                arrange.carry = Carry(item, from, cell, hold)
                            }
                            if (!moved) continue
                            arrange.finger = position
                            val now = SystemClock.uptimeMillis()
                            val fg = sorting
                            if (fg != null) {
                                if (fg.panel.has(position)) {
                                    outSince = 0L
                                    val apps = (itemsOn(from).firstOrNull { it.key == (item as Shortcut).folder } as? Folder)?.apps.orEmpty()
                                    val to = apps.indices.minByOrNull { (fg.app(it).center.toOffset() - position).getDistanceSquared() } ?: continue
                                    when {
                                        to == apps.indexOfFirst { it.key == item.key } -> aimSince = 0L
                                        to != aim || aimSince == 0L -> aimSince = now
                                        // Like Android, the other apps make way once the finger rests over a place a moment.
                                        now - aimSince > REORDER_MS -> {
                                            onReorder(item as Shortcut, apps.filter { it.key != item.key }.getOrNull(to)?.key)
                                            aimSince = 0L
                                        }
                                    }
                                    aim = to
                                    continue
                                }
                                aimSince = 0L
                                if (outSince == 0L) outSince = now
                                if (now - outSince <= FOLDER_EXIT_MS) continue
                                // Like Android, the folder closes once the app rests outside it, and the app goes on to the grid.
                                arrange.folder = null
                                sorting = null
                                outSince = 0L
                            }
                            val edgeSide = if (position.x < edge) -1 else if (position.x > size.width - edge) 1 else 0
                            when {
                                edgeSide == 0 -> atEdgeSince = 0L
                                atEdgeSince == 0L -> atEdgeSince = now
                                now - atEdgeSince > PAGE_TURN_MS && !pager.isScrollInProgress -> {
                                    val next = pager.currentPage + edgeSide
                                    if (next in 0 until pager.pageCount) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        scope.launch { pager.animateScrollToPage(next) }
                                    } else if (edgeSide > 0 && arrange.offered == null) {
                                        // Past the last page: a new one to drop on, like the empty page that used to wait there.
                                        arrange.offered = onOfferPage()
                                        if (arrange.offered != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    // Turning again takes a longer rest, so the page just reached can be looked at.
                                    atEdgeSince = now + PAGE_TURN_MS
                                }
                            }
                            val (pages, g) = current
                            // Side by side, the finger picks the page; the drop preview works in its coordinates.
                            val side = g.side(position)
                            val at = g.local(position, side)
                            val page = pages.getOrNull(pager.targetPage * g.per + side)
                            arrange.page = page
                            if (page == null) {
                                // Over the empty half of the last pair: nowhere to land.
                                arrange.grouping = false
                                arrange.swapWith = null
                                arrange.preview = null
                                continue
                            }
                            val others = placedOn(page, g).filter { it.first.key != item.key }
                            val over = others.lastOrNull { (_, r) -> g.rect(r).has(at) }
                            // Apps group with apps and folders; anything else swaps, except an app from a folder, which has no cell to trade.
                            val groups = item is Shortcut && (over?.first is Shortcut || over?.first is Folder)
                            val target = over?.takeIf { groups || !inFolder }
                            arrange.grouping = groups
                            arrange.swapWith = target?.first?.key
                            arrange.preview =
                                if (over != null) {
                                    target?.second
                                } else {
                                    val x = g.colLine(at - hold).coerceIn(0, (g.cols - cell.w).coerceAtLeast(0))
                                    val y = g.rowLine(at - hold).coerceIn(0, (g.rows - cell.h).coerceAtLeast(0))
                                    Cell(x, y, cell.w, cell.h).takeIf { n -> others.none { it.second.overlaps(n) } }
                                }
                        }
                        val page = arrange.page
                        val target = if (page == null) null else arrange.swapWith?.let { key -> itemsOn(page).firstOrNull { it.key == key } }
                        val drop = arrange.preview
                        val grouping = arrange.grouping
                        arrange.drop()
                        when {
                            page == null -> {}
                            target != null && grouping && moved -> onGroup(item as Shortcut, target)
                            // Swapping trades places and sizes, like swapping windows in Hyprland; across pages it trades pages too.
                            target != null ->
                                layout.cell(target.key)?.let { other ->
                                    layout.set(target.key, cell)
                                    layout.set(item.key, other)
                                    if (page != from) {
                                        onMove(target, from)
                                        onMove(item, page)
                                    }
                                }
                            drop != null && moved -> {
                                layout.set(item.key, drop)
                                if (page != from || inFolder) onMove(item, page)
                            }
                        }
                        layout.save()
                        arrange.offered?.let { offered ->
                            arrange.offered = null
                            if (itemsOn(offered).isEmpty()) onWithdrawPage(offered)
                        }
                        return moved
                    }

                    /**
                     * Waits out a long press without taking the touch, so a tap still reaches the tile; false if it ended or
                     * moved first, or the arbiter gave the touch to something else: a second finger came down.
                     */
                    suspend fun AwaitPointerEventScope.awaitLongPress(
                        downId: PointerId,
                        start: Offset,
                    ) = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == downId } ?: return@withTimeoutOrNull
                            if (!c.pressed || (c.position - start).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull
                            if (arbiter.owner != Owner.Undecided) return@withTimeoutOrNull
                        }
                    } == null && arbiter.longPress().let { it == Owner.Arrange || it == Owner.Menu }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val start = down.position
                        val (pages, g, sel) = current
                        val shown = pagesAt(pager.currentPage, pages, g)
                        val side = g.side(start)
                        val page = shown[side]
                        if (page != null) onTouch(page)
                        val at = g.local(start, side)
                        val placed = page?.let { placedOn(it, g) }.orEmpty()
                        // Drawing: the finger is the pen's. Nothing here lifts a tile, opens the menu or ends an edit.
                        if (draws) {
                            arbiter.down(TouchStart.Page, HomeModes(drawing = true))
                            return@awaitEachGesture
                        }

                        // An open folder takes every touch: a tap on an app opens it (the tile gets the tap),
                        // a long-press lifts it to sort it in or drag it out, a tap outside closes the folder. Its panel is centered on
                        // the whole screen, even with two pages showing.
                        val folderPage = shown.getOrNull(sideOf(arrange.folder, shown))
                        val open = folderPage?.let { p -> itemsOn(p).firstOrNull { it.key == arrange.folder } as? Folder }
                        if (open == null) arrange.folder = null
                        if (open != null && folderPage != null) {
                            val fg = FolderGeometry(size.width, g.height, g.cellW, g.cellH, g.gap, open.apps.size, this)
                            val hit = open.apps.indices.firstOrNull { fg.app(it).has(start) }
                            val owner = arbiter.down(if (hit == null) TouchStart.Page else TouchStart.Tile, HomeModes(folderOpen = true))
                            if (hit == null || owner == Owner.Editor) {
                                down.consume()
                                if (!fg.panel.has(start)) arrange.folder = null
                                while (true) {
                                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                    c.consume()
                                    if (!c.pressed) break
                                }
                                return@awaitEachGesture
                            }
                            if (!awaitLongPress(down.id, start)) return@awaitEachGesture
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            arrange.holding = true
                            try {
                                moveDrag(down.id, start, open.apps[hit], Cell(0, 0, 1, 1), folderPage, fg.app(hit), fg)
                            } finally {
                                arrange.holding = false
                            }
                            return@awaitEachGesture
                        }

                        // Edit mode: every touch belongs to the editor, nothing reaches the tiles. The selected
                        // tile may be on the other page of a pair; the editor works in its page's coordinates.
                        val selSide = sideOf(sel, shown)
                        val selectedItem = shown.getOrNull(selSide)?.let { p -> placedOn(p, g).firstOrNull { it.first.key == sel } }
                        val selAt = g.local(start, selSide.coerceAtLeast(0))
                        // Except the action bar: leave its taps unconsumed so its buttons get them.
                        if (selectedItem != null && arrange.bar?.has(selAt) == true) {
                            arbiter.down(TouchStart.Bar, HomeModes(editing = true))
                            return@awaitEachGesture
                        }
                        if (selectedItem != null) {
                            down.consume()
                            val (item, cell) = selectedItem
                            val rect = g.rect(cell)
                            val edges = if (item.resizable) edgesAt(rect, selAt, grab, sideGrab) else Edges(false, false, false, false)
                            val owner =
                                arbiter.down(
                                    when {
                                        edges.any -> TouchStart.Handle
                                        rect.has(selAt) -> TouchStart.Selected
                                        else -> TouchStart.Page
                                    },
                                    HomeModes(editing = true),
                                )
                            arrange.holding = true
                            try {
                                when (owner) {
                                    Owner.Resize -> {
                                        val others = placed.filter { it.first.key != item.key }.map { it.second }
                                        while (true) {
                                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                            c.consume()
                                            if (!c.pressed) break
                                            if (arbiter.owner == Owner.Pinch) break
                                            val (minW, minH) = item.minSize
                                            val p = g.local(c.position, selSide)
                                            val x0 = if (edges.left) g.colLine(p).coerceAtMost(cell.right - minW) else cell.x
                                            val x1 = if (edges.right) g.colLine(p).coerceAtLeast(cell.x + minW) else cell.right
                                            val y0 = if (edges.top) g.rowLine(p).coerceAtMost(cell.bottom - minH) else cell.y
                                            val y1 = if (edges.bottom) g.rowLine(p).coerceAtLeast(cell.y + minH) else cell.bottom
                                            val next = Cell(x0, y0, x1 - x0, y1 - y0)
                                            // Growing into another tile is refused; the frame just stops there.
                                            if (others.none { it.overlaps(next) }) layout.set(item.key, next)
                                        }
                                        layout.save()
                                    }
                                    Owner.Arrange -> shown[selSide]?.let { moveDrag(down.id, start, item, cell, it, g.screen(rect, selSide)) }
                                    else -> {
                                        // Tap outside finishes editing; a drag outside does nothing.
                                        onSelect(null)
                                        while (true) {
                                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                            c.consume()
                                            if (!c.pressed) break
                                        }
                                    }
                                }
                            } finally {
                                arrange.holding = false
                            }
                            return@awaitEachGesture
                        }

                        // The empty half of the last pair takes its own taps, to add a page.
                        if (page == null) {
                            arbiter.down(TouchStart.Page, HomeModes())
                            return@awaitEachGesture
                        }
                        val hit = placed.lastOrNull { (_, c) -> g.rect(c).has(at) }
                        arbiter.down(
                            when (hit?.first) {
                                null -> TouchStart.Page
                                is WidgetItem -> TouchStart.Widget
                                else -> TouchStart.Tile
                            },
                            HomeModes(),
                        )
                        if (!awaitLongPress(down.id, start)) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (hit == null) {
                            onLongPressEmpty()
                            return@awaitEachGesture
                        }
                        // Lifted: select it, and let the same finger drag it without letting go.
                        onSelect(hit.first.key)
                        arrange.holding = true
                        val moved =
                            try {
                                moveDrag(down.id, start, hit.first, hit.second, page, g.screen(g.rect(hit.second), side))
                            } finally {
                                arrange.holding = false
                            }
                        // Like Android, a drag drops and finishes; a plain long-press stays in edit mode.
                        if (moved) onSelect(null)
                    }
                },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !drawing, key ={ pages.getOrNull(it * grid.per) ?: "slot $it" }) { slot ->
                Row(Modifier.fillMaxSize()) {
                    for (page in pagesAt(slot, pages, grid)) {
                        Box(Modifier.width(with(density) { grid.width.toDp() }).fillMaxHeight()) {
                            if (page == null) PlusPage(grid, onAddPage) else TiledPage(page, itemsOn(page), layout, grid, arrange, selected, render, actions, onNoRoom, ready, captures)
                        }
                    }
                }
            }

            // The open folder, over the page it's on.
            val openFolder = arrange.folder?.let { key -> pagesAt(pager.currentPage, pages, grid).firstNotNullOfOrNull { p -> p?.let { itemsOn(it).firstOrNull { item -> item.key == key } as? Folder } } }
            if (openFolder != null) {
                val fg = FolderGeometry(width * per, grid.height, grid.cellW, grid.cellH, gap, openFolder.apps.size, density)
                FolderPanel(openFolder, fg, render, arrange.carry?.item?.key)
            }

            // The carried tile floats above the pager, so it stays put while pages turn, and above an open folder it's sorting.
            arrange.carry?.let { carry ->
                val r = grid.rect(carry.cell)
                androidx.compose.runtime.key(carry.item.key) {
                    render(
                        carry.item,
                        Modifier
                            // The finger is read while placing, so following it doesn't recompose the pages.
                            .offset {
                                val at = arrange.finger - carry.grab
                                IntOffset(at.x.roundToInt(), at.y.roundToInt())
                            }
                            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                            .zIndex(5f)
                            .graphicsLayer {
                                scaleX = 0.96f
                                scaleY = 0.96f
                                alpha = 0.85f
                            },
                        true,
                    )
                }
            }
        }
    }
}

/**
 * Where a carried tile would land on [page]. Its own scope: the preview follows the finger from cell to
 * cell, and reading it in the page would recompose every tile on it each time.
 */
@Composable
private fun DropPreview(
    page: String,
    grid: Grid,
    arrange: Arrange,
) {
    if (arrange.page != page) return
    val cell = arrange.preview ?: return
    val density = LocalDensity.current
    val r = grid.rect(cell)
    Box(
        Modifier
            .offset { IntOffset(r.left, r.top) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            // Grouping fills more, so it reads as "into" rather than "in place of".
            .framed(Pack.mark, Pack.accent, Pack.accent.copy(alpha = if (arrange.grouping) 0.35f else 0.12f)),
    )
}

/** The empty right half of the last pair, when the pages are odd: the page's outline, and a tap adds the page. */
@Composable
private fun PlusPage(
    grid: Grid,
    onAdd: () -> Unit,
) {
    val density = LocalDensity.current
    val r = grid.page
    Box(
        Modifier
            .offset { IntOffset(r.left, r.top) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            .padding(with(density) { grid.gap.toDp() })
            .framed(Pack.hair, Pack.pageLine.copy(alpha = 0.5f))
            .clickable(onClick = onAdd),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("+ new page", style = Pack.body(color = Pack.pageMuted))
    }
}

/** How long a carried tile rests against a side edge before the page turns. */
private const val PAGE_TURN_MS = 600L

/** How long an app dragged in its open folder rests over another place before the apps make way; Launcher3's REORDER_DELAY. */
private const val REORDER_MS = 250L

/** How long it rests outside the folder's panel before the folder closes; Launcher3's ON_EXIT_CLOSE_DELAY. */
private const val FOLDER_EXIT_MS = 400L

/** One home page: its tiles, and while arranging the grid guides, drop preview and selection frame. */
@Composable
private fun TiledPage(
    page: String,
    items: List<HomeItem>,
    layout: WidgetLayout,
    grid: Grid,
    arrange: Arrange,
    selected: String?,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
    actions: @Composable (HomeItem) -> Unit,
    onNoRoom: (HomeItem) -> Unit,
    ready: Boolean,
    captures: PageCaptures?,
) {
    val density = LocalDensity.current
    val carried = arrange.carry?.item?.key
    LaunchedEffect(items.map { it.key }, grid.cols, grid.rows) {
        layout.place(items, grid.cols, grid.rows, page).forEach(onNoRoom)
    }
    BoxWithConstraints(Modifier.fillMaxSize().captured(page, captures)) {
        val pageWidth = constraints.maxWidth
        if (selected != null || (carried != null && arrange.folder == null)) {
            // Grid guides while arranging; not while sorting an open folder, which covers the grid.
            Canvas(Modifier.fillMaxSize()) {
                for (x in 0 until grid.cols) {
                    for (y in 0 until grid.rows) {
                        val r = grid.rect(Cell(x, y, 1, 1))
                        drawRect(
                            Pack.pageLine.copy(alpha = 0.35f),
                            topLeft = Offset(r.left.toFloat(), r.top.toFloat()),
                            size = androidx.compose.ui.geometry.Size(r.width.toFloat(), r.height.toFloat()),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                }
            }
        }
        if (ready && items.none { it.key != carried }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    if (carried != null) "drop here" else "no widgets on this page · long-press for a new one",
                    Modifier.background(Pack.background.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = Pack.body(),
                )
            }
        }
        if (carried != null) DropPreview(page, grid, arrange)
        for (item in items) {
            // The carried tile is drawn above the pager instead.
            if (item.key == carried) continue
            val cell = layout.cell(item.key)?.let { grid.fit(it, item) } ?: continue
            val rect = grid.rect(cell)
            androidx.compose.runtime.key(item.key) {
                render(
                    item,
                    Modifier
                        .offset { IntOffset(rect.left, rect.top) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
                    selected == item.key || arrange.swapWith == item.key,
                )
            }
        }
        // Selection frame: handles on each edge for widgets, plus the action bar.
        val sel = items.firstOrNull { it.key == selected }
        val selCell = sel?.let { layout.cell(it.key)?.let { c -> grid.fit(c, it) } }
        if (sel != null && selCell != null && carried == null) {
            val r = grid.rect(selCell)
            val handle = with(density) { 14.dp.roundToPx() }
            if (sel.resizable) {
                Canvas(Modifier.fillMaxSize().zIndex(2f)) {
                    drawRect(
                        Pack.accent,
                        topLeft = Offset(r.left.toFloat(), r.top.toFloat()),
                        size = androidx.compose.ui.geometry.Size(r.width.toFloat(), r.height.toFloat()),
                        style = Stroke(3.dp.toPx()),
                    )
                    for (p in listOf(
                        Offset(r.center.x.toFloat(), r.top.toFloat()),
                        Offset(r.center.x.toFloat(), r.bottom.toFloat()),
                        Offset(r.left.toFloat(), r.center.y.toFloat()),
                        Offset(r.right.toFloat(), r.center.y.toFloat()),
                    )) {
                        drawCircle(Pack.background, handle / 2f + 2.dp.toPx(), p)
                        drawCircle(Pack.accent, handle / 2f, p)
                    }
                }
                // Keep the back gesture off the side handles, so a tile against the screen edge can be resized.
                // Android honours at most 200dp of exclusion per edge.
                val zoneW = with(density) { 48.dp.roundToPx() }
                val zoneH = minOf(r.height, with(density) { 160.dp.roundToPx() })
                for (x in listOf(r.left, r.right)) {
                    Box(
                        Modifier
                            .offset { IntOffset(x - zoneW / 2, r.center.y - zoneH / 2) }
                            .size(with(density) { zoneW.toDp() }, with(density) { zoneH.toDp() })
                            .systemGestureExclusion(),
                    )
                }
            }
            // Action bar above the tile, or below it when the tile is at the top.
            val barAbove = r.top > with(density) { 56.dp.roundToPx() }
            if (!arrange.holding) {
                val at =
                    with(density) {
                        IntOffset(
                            r.left.coerceAtMost(pageWidth - 220.dp.roundToPx()).coerceAtLeast(0),
                            if (barAbove) r.top - 48.dp.roundToPx() else r.bottom + 8.dp.roundToPx(),
                        )
                    }
                DisposableEffect(Unit) { onDispose { arrange.bar = null } }
                Box(
                    Modifier
                        .zIndex(3f)
                        .offset { at }
                        .onSizeChanged { arrange.bar = IntRect(at, it) },
                ) { actions(sel) }
            }
        }
    }
}
