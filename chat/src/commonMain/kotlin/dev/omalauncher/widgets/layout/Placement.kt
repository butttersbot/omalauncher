package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.GRID_COLUMNS

/** What placing needs to know of a home item: its [key], the [size] it asks for in cells, and whether it [resizable] (a widget) or always shows 1x1 (an app or folder). */
data class Tile(
    val key: String,
    val size: Pair<Int, Int>?,
    val resizable: Boolean = true,
)

/** A cell saved on a grid of [columns], moved onto one of [to]. */
fun rescale(
    c: Cell,
    columns: Int,
    to: Int = GRID_COLUMNS,
): Cell {
    if (columns == to || columns <= 0) return c
    val x = c.x * to / columns
    return c.copy(x = x, w = (c.w * to / columns).coerceIn(1, to - x))
}

/** A saved cell as its tile takes up room: shortcuts always show as 1x1, whatever an old saved cell says. */
fun Cell.shown(resizable: Boolean) = if (resizable) this else copy(w = 1, h = 1)

/** The size a tile is placed at on a grid of [cols] by [rows]: its own, or half the width by 3, cut to the grid. */
fun placedSize(
    size: Pair<Int, Int>?,
    cols: Int,
    rows: Int,
): Pair<Int, Int> {
    val (pw, ph) = size ?: (cols / 2 to 3)
    return pw.coerceIn(1, cols) to ph.coerceIn(1, rows)
}

/** First spot, row by row from the top left, where a tile of [w] by [h] overlaps none of [taken]; null when there's none. */
fun firstFit(
    w: Int,
    h: Int,
    taken: List<Cell>,
    cols: Int,
    rows: Int,
): Cell? {
    for (y in 0..rows - h) for (x in 0..cols - w) {
        val c = Cell(x, y, w, h)
        if (taken.none { it.overlaps(c) }) return c
    }
    return null
}

/**
 * Finds cells for the tiles of the home grid. [cells] is the saved arrangement by key, written in place;
 * [save] persists it. Items are whatever the caller lays out, seen through [tile].
 */
class Placement<T>(
    private val cells: MutableMap<String, Cell>,
    private val save: () -> Unit,
    private val tile: (T) -> Tile,
) {
    /** The grid last laid out on screen; every page shares it. */
    var grid: Pair<Int, Int>? = null
        private set

    /**
     * Gives items that have no cell yet one at their declared size, in the
     * first free spot. Saved cells are never rewritten here: a temporarily
     * small page (keyboard, rotation) must not destroy the user's layout.
     *
     * Returns the items that don't fit at their size; they get no cell, so
     * the caller can move them to a page with room instead of squeezing them
     * in or stacking them on top of other tiles. That includes apps pinned to
     * a full page, such as page 1 of a fresh install.
     */
    fun place(
        items: List<T>,
        cols: Int,
        rows: Int,
        /** Cells on the page that belong to something not among [items] (a widget whose file isn't there now): not free. */
        taken: List<Cell> = emptyList(),
    ): List<T> {
        grid = cols to rows
        var changed = false
        val homeless = ArrayList<T>()
        for (item in items) {
            val key = tile(item).key
            if (cells[key] != null) continue
            val spot = freeSpot(item, items, cols, rows, taken)
            if (spot == null) {
                homeless += item
                continue
            }
            cells[key] = spot
            changed = true
        }
        if (changed) save()
        return homeless
    }

    /**
     * Gives [item] a free spot at its size among [others]; false when there's none, or no page was laid out yet.
     * Those of [others] without a cell yet (their page hasn't been laid out, or is being laid out this very frame,
     * with two pages showing) get theirs first: the page's own tiles keep their spots, the newcomer takes what's left.
     */
    fun claim(
        item: T,
        others: List<T>,
        taken: List<Cell> = emptyList(),
    ): Boolean {
        val (cols, rows) = grid ?: return false
        val key = tile(item).key
        place(others.filter { tile(it).key != key }, cols, rows, taken)
        cells[key] = freeSpot(item, others, cols, rows, taken) ?: return false
        save()
        return true
    }

    /** First spot where [item] fits at its size beside the placed [items]; null when the page is full. */
    fun freeSpot(
        item: T,
        items: List<T>,
        cols: Int,
        rows: Int,
        taken: List<Cell> = emptyList(),
    ): Cell? {
        val me = tile(item)
        val others = taken + items.mapNotNull { other -> tile(other).let { o -> if (o.key == me.key) null else cells[o.key]?.shown(o.resizable) } }
        val (w, h) = placedSize(me.size, cols, rows)
        return firstFit(w, h, others, cols, rows)
    }
}

/**
 * Rows the tiles reach down to (apps show as 1x1): each tile's saved cell, or null without one, and
 * whether it resizes. The pages share one grid, which never has fewer rows than this.
 */
fun usedRows(tiles: List<Pair<Cell?, Boolean>>) = tiles.maxOfOrNull { (cell, resizable) -> cell?.let { if (resizable) it.bottom else it.y + 1 } ?: 0 } ?: 0

/**
 * The grid's cell width and height and its gap, guessed from a screen [widthPixels] wide before the grid was laid out.
 * [columns] is the screen's grid: the Fold's home is [GRID_COLUMNS]; a desktop's or the car's canvas has its own.
 */
fun estimatedGeometry(
    widthPixels: Int,
    density: Float,
    columns: Int = GRID_COLUMNS,
): Triple<Float, Float, Int> {
    val gap = Math.round(6 * density)
    val cell = (widthPixels - gap) / columns.toFloat()
    return Triple(cell, cell, gap)
}

/** A tile's size in pixels at [cells], on a grid of [geometry] (cell width, cell height, gap). */
fun tilePx(
    cells: Pair<Int, Int>,
    geometry: Triple<Float, Float, Int>,
): Pair<Int, Int> {
    val (cellW, cellH, gap) = geometry
    return (cells.first * cellW).toInt() - gap to (cells.second * cellH).toInt() - gap
}
