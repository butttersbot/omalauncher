package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.GRID_COLUMNS
import kotlin.math.roundToInt

/** A rectangle in whole pixels; [right] and [bottom] are just outside it. */
data class PxRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width get() = right - left
    val height get() = bottom - top
}

/** A rectangle before it is rounded to pixels. */
data class Bounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * A page's shape, its width over its height, the same on every display: five wide for six tall. The room a
 * page gets differs a little from one display to the next (a foldable's cover and half of its inner screen);
 * the page is the largest rectangle of this shape that fits, and what is left over is margin.
 */
const val PAGE_ASPECT = 5f / 6f

/** The gap around tiles as a share of the page's width: 6dp on a page 468dp wide. It scales with the page, like everything on it. */
const val GAP_SHARE = 1f / 78f

/**
 * Pixel geometry of a canvas page: a grid of [columns] (the Fold's home: [GRID_COLUMNS]) by [rows] cells, scaled uniformly into the room a
 * page has ([width] by [height]) and centred there. The page keeps one shape ([PAGE_ASPECT]), so for a
 * given [rows] every cell has one shape too, and a tile takes the same share of the page wherever the page is
 * shown. Nothing is ever laid on its side, and the grid never re-flows: a smaller room is a smaller page.
 *
 * [per] pages stand side by side, each in a room [width] wide (two on a foldable's inner screen); a page's
 * rectangles are in its own room's coordinates.
 */
class GridGeometry(
    val width: Int,
    val height: Int,
    rows: Int,
    val per: Int = 1,
    /** The screen's columns: the Fold's home is [GRID_COLUMNS]; a desktop's or the car's canvas passes its own. */
    columns: Int = GRID_COLUMNS,
    /** The page's width over its height: the Fold's home pages are [PAGE_ASPECT]; a landscape canvas passes its own. */
    val aspect: Float = PAGE_ASPECT,
) {
    val cols = columns.coerceAtLeast(1)
    val rows = rows.coerceAtLeast(1)

    /** The page's size, and where it starts in its room. */
    private val wide = width > height * aspect
    val pageWidth = (if (wide) height * aspect else width.toFloat()).coerceAtLeast(1f)
    val pageHeight = if (wide) height.toFloat().coerceAtLeast(1f / aspect) else pageWidth / aspect
    val pageLeft = (width - pageWidth) / 2f
    val pageTop = (height - pageHeight) / 2f

    private val gapSize = pageWidth * GAP_SHARE

    /** The gap between tiles, in whole pixels, for what is drawn to match the grid. */
    val gap = gapSize.roundToInt()

    /** A cell's width and height. */
    val cellW = (pageWidth - gapSize) / cols
    val cellH = (pageHeight - gapSize) / this.rows

    /** The page in its room. */
    val page get() = PxRect(pageLeft.roundToInt(), pageTop.roundToInt(), (pageLeft + pageWidth).roundToInt(), (pageTop + pageHeight).roundToInt())

    /** A tile's rectangle: a gap before it, and the next cell's gap after it. */
    fun bounds(c: Cell) = Bounds(pageLeft + gapSize + c.x * cellW, pageTop + gapSize + c.y * cellH, pageLeft + c.right * cellW, pageTop + c.bottom * cellH)

    // Rounded, not truncated: rows * cellH can come out a hair under the page's height, which would leave the
    // bottom row a pixel short.
    fun rect(c: Cell) = bounds(c).let { PxRect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt()) }

    /** A saved cell as shown on this grid; only affects display, the saved cell stays as it is. */
    fun fit(
        c: Cell,
        resizable: Boolean,
    ): Cell {
        val w = if (resizable) c.w.coerceIn(1, cols) else 1
        val h = if (resizable) c.h.coerceIn(1, rows) else 1
        return Cell(c.x.coerceIn(0, cols - w), c.y.coerceIn(0, rows - h), w, h)
    }

    /** Nearest column line to a point [x] across the page's room. */
    fun colLine(x: Float) = line(x - pageLeft, cellW).coerceIn(0, cols)

    /** Nearest row line to a point [y] down the page's room. */
    fun rowLine(y: Float) = line(y - pageTop, cellH).coerceIn(0, rows)

    private fun line(
        px: Float,
        cell: Float,
    ) = ((px - gapSize / 2f) / cell).roundToInt()

    /** Which of the pages on screen, left to right, a point [x] across is over. */
    fun side(x: Float) = (x / width).toInt().coerceIn(0, per - 1)

    /** Where the room of the page at [side] starts across the screen. */
    fun left(side: Int) = side * width
}

/** The pages a pager slot shows, left to right; null where there is no page: the right half of the last pair when the pages are odd. */
fun <T : Any> pagesAt(
    slot: Int,
    pages: List<T>,
    per: Int,
): List<T?> = (0 until per).map { pages.getOrNull(slot * per + it) }

/** How far along the wallpaper is, 0 to 1, with the pager at [page] plus [fraction] of [pageCount] slots; centered when there's one. */
fun wallpaperOffset(
    page: Int,
    fraction: Float,
    pageCount: Int,
): Float {
    val last = pageCount - 1
    return if (last <= 0) 0.5f else ((page + fraction) / last).coerceIn(0f, 1f)
}
