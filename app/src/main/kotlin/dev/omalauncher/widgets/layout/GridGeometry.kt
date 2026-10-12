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
)

/** How many rows of square-ish cells fit [long] beside [GRID_COLUMNS] of them across [short]. */
fun squareRows(
    short: Int,
    long: Int,
    gap: Int,
) = ((long - gap) / ((short - gap) / GRID_COLUMNS.toFloat())).toInt().coerceAtLeast(1)

/**
 * Pixel geometry of the home grid: cells separated by Omarchy-style gaps, as many rows of
 * square-ish cells as fit, but never fewer than the [used] rows tiles were placed on. A layout
 * made on a tall screen keeps its arrangement on a squarer one (a foldable's inner screen) with
 * shorter cells, rather than tiles stacking. [turned] (landscape) lays portrait's grid on its
 * side: the same cells, [rows] of them across and [cols] down, so tiles keep their place and
 * shape. Everything else here works in those portrait cells.
 *
 * [per] pages stand side by side, each [width] wide (two on a foldable's inner screen); a page's
 * cells are in its own coordinates.
 */
class GridGeometry(
    val width: Int,
    val height: Int,
    val gap: Int,
    val turned: Boolean = false,
    portraitRows: Int? = null,
    used: Int = 0,
    val per: Int = 1,
) {
    val cols = GRID_COLUMNS
    val rows = maxOf(if (turned) portraitRows ?: squareRows(height, width, gap) else squareRows(width, height, gap), used)

    /** A screen cell's width and height. */
    val cellW = (width - gap) / (if (turned) rows else cols).toFloat()
    val cellH = (height - gap) / (if (turned) cols else rows).toFloat()

    // Rounded, not truncated: rows * cellH can come out a hair under the grid's height, which would leave the
    // bottom row a pixel short of the strip below.
    fun rect(c: Cell) =
        if (turned) {
            PxRect((gap + c.y * cellW).roundToInt(), (gap + c.x * cellH).roundToInt(), (c.bottom * cellW).roundToInt(), (c.right * cellH).roundToInt())
        } else {
            PxRect((gap + c.x * cellW).roundToInt(), (gap + c.y * cellH).roundToInt(), (c.right * cellW).roundToInt(), (c.bottom * cellH).roundToInt())
        }

    /** A saved cell as shown on this grid; only affects display, the saved cell stays as it is. */
    fun fit(
        c: Cell,
        resizable: Boolean,
    ): Cell {
        val w = if (resizable) c.w.coerceIn(1, cols) else 1
        val h = if (resizable) c.h.coerceIn(1, rows) else 1
        return Cell(c.x.coerceIn(0, cols - w), c.y.coerceIn(0, rows - h), w, h)
    }

    /** Nearest column line to a point on screen. */
    fun colLine(
        x: Float,
        y: Float,
    ) = (if (turned) line(y, cellH) else line(x, cellW)).coerceIn(0, cols)

    fun rowLine(
        x: Float,
        y: Float,
    ) = (if (turned) line(x, cellW) else line(y, cellH)).coerceIn(0, rows)

    private fun line(
        px: Float,
        cell: Float,
    ) = ((px - gap / 2f) / cell).roundToInt()

    /** Which of the pages on screen, left to right, a point [x] across is over. */
    fun side(x: Float) = (x / width).toInt().coerceIn(0, per - 1)

    /** Where the page at [side] starts across the screen. */
    fun left(side: Int) = side * width
}

/** A window's size as the key its portrait row count is saved under: short side first, so both orientations share it. */
fun windowKey(
    width: Int,
    height: Int,
) = "${minOf(width, height)}x${maxOf(width, height)}"

/**
 * Whether home shows two pages side by side: when each half of the window is phone-shaped, as on a
 * foldable's inner screen in either orientation. Phones, tablets and phone landscape show one.
 * The rule of `showsTwoPages` in ui/SideRail.kt, kept here beside its test until that one goes.
 */
fun showsTwoPages(
    width: Int,
    height: Int,
) = width / 2f / height in 0.4f..0.65f

/** Whether home turns portrait's grid on its side: landscape, unless the window shows two pages. As `homeTurned` in ui/SideRail.kt. */
fun homeTurned(
    width: Int,
    height: Int,
) = width > height && !showsTwoPages(width, height)

/**
 * The pages home shows, from those that hold something. One empty page always waits after the last one
 * (unless the last is empty already, by [isEmpty]); with [per] of 2 pages show in pairs, and an odd last
 * page gets an empty partner.
 */
fun shownPages(
    pages: List<Int>,
    per: Int,
    isEmpty: (Int) -> Boolean,
): List<Int> =
    pages
        .let { if (isEmpty(it.last())) it else it + (it.last() + 1) }
        .let { if (it.size % per == 0) it else it + (it.last() + 1) }

/** The pages a pager slot shows, left to right; a slot past the end shows page 1. */
fun pagesAt(
    slot: Int,
    pages: List<Int>,
    per: Int,
) = (0 until per).map { pages.getOrElse(slot * per + it) { 1 } }

/** How far along the wallpaper is, 0 to 1, with the pager at [page] plus [fraction] of [pageCount] slots; centered when there's one. */
fun wallpaperOffset(
    page: Int,
    fraction: Float,
    pageCount: Int,
): Float {
    val last = pageCount - 1
    return if (last <= 0) 0.5f else ((page + fraction) / last).coerceIn(0f, 1f)
}
