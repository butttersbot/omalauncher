package dev.omalauncher.widgets.annotations

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.GRID_COLUMNS
import dev.omalauncher.widgets.layout.GAP_SHARE
import dev.omalauncher.widgets.layout.NOTEBOOK_MIN_ROWS
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/*
 * Where a new note or sticker goes so that it lies on nothing: not on a tile, not on another note, not on a
 * sticker. A note used to start a fixed cascade from the page's corner, which knew nothing of the tiles and so
 * landed on them. Here everything on the page is a rectangle with its true size, the page is searched outward
 * from where the note is wanted, and the first place clear of all of it is taken. When the page has no such
 * place the answer says so instead of pretending: it is the place that covers least, marked as compromised.
 *
 * All of it is arithmetic on fractions of the page, with nothing of Android in it. Every way a note comes to be
 * (the agent's, the owner's from the menu) goes through [noteSpot], so none of them can forget the tiles.
 */

/** A rectangle on a page, as fractions of it: across of its width, down of its height. */
data class Box(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    val right get() = x + w
    val bottom get() = y + h

    /** How much of the page this and [other] both cover; 0 when they only touch or are apart. */
    fun overlap(other: Box): Float {
        val across = minOf(right, other.right) - maxOf(x, other.x)
        val down = minOf(bottom, other.bottom) - maxOf(y, other.y)
        return if (across > 0f && down > 0f) across * down else 0f
    }
}

/** A note's rectangle. */
fun StickyNote.box() = Box(x, y, w, h)

/** A sticker's rectangle; see [Sticker.box]. */
fun Sticker.taken(tall: Float = PAGE_TALL) = box(tall).let { Box(it[0], it[1], it[2] - it[0], it[3] - it[1]) }

/** The notes and stickers of a page as the rectangles they take up. */
fun Annotations.scraps(tall: Float = PAGE_TALL): List<Box> = notes.map { it.box() } + stickers.map { it.taken(tall) }

/**
 * The room kept between a new note and whatever is next to it, and between it and the page's edge, as a share
 * of the page's width: the gap tiles keep from each other and from the edge (6dp on a page 468dp wide), so a
 * note among tiles stands off them exactly as they stand off each other.
 */
const val PLACING_GAP = GAP_SHARE

/**
 * How far apart the places tried are, as a share of the page's width: a quarter of a cell. Fine enough to find
 * a hole only a little bigger than the note, and coarse enough that a whole page is a few hundred places.
 */
const val PLACING_STEP = 1f / (GRID_COLUMNS * 4)

/** A place comes out a step of [UNITS] off when it is saved, so two things this much closer than the gap still count as clear. */
private const val SLACK = 1f / UNITS

/** The fewest columns and rows of tile a note is made to fit before it is let overlap: narrower or shorter than that it can't be read. */
const val SMALLEST_NOTE_COLUMNS = 2
const val SMALLEST_NOTE_ROWS = 1

/** Whether [a] and [b] are closer than [across] sideways and [down] up and down: overlapping, or with less room between them than that. */
fun hits(
    a: Box,
    b: Box,
    across: Float = 0f,
    down: Float = 0f,
): Boolean = a.x - across < b.right && a.right + across > b.x && a.y - down < b.bottom && a.bottom + down > b.y

/**
 * A place found for something new: [box], and whether it is [compromised], which is when nothing clear was
 * left and it covers [overlap] of the page that something else already had.
 */
data class Spot(
    val box: Box,
    val compromised: Boolean = false,
    val overlap: Float = 0f,
)

/**
 * Where something [w] by [h] goes among [taken], as near as it can be to having its top-left corner at [near].
 *
 * The places tried are a lattice [step] apart that passes through [near] and stays [margin] inside the page,
 * with the places hard against that margin added, gone through nearest first (then leftmost, then topmost), and the first one at least [gap] away from every
 * rectangle of [taken] is the answer. The same page and the same question always give the same place, so
 * asking again after adding what was placed moves on to the next place instead of going round in circles.
 * [near] itself is the answer on a page with nothing on it.
 *
 * With no clear place anywhere the answer is the one that covers least of what is there (the nearest of
 * those), and it says so: [Spot.compromised]. It never fails and never leaves the page.
 *
 * [gap], [margin] and [step] are shares of the page's width; [tall] is what a share of its height is worth
 * against one of its width, so the same distance is kept sideways and up and down.
 */
fun clearSpot(
    taken: List<Box>,
    w: Float,
    h: Float,
    near: Pair<Float, Float>,
    gap: Float = PLACING_GAP,
    margin: Float = PLACING_GAP,
    step: Float = PLACING_STEP,
    tall: Float = PAGE_TALL,
): Spot {
    val left = margin.coerceAtMost((1f - w) / 2f).coerceAtLeast(0f)
    val top = (margin / tall).coerceAtMost((1f - h) / 2f).coerceAtLeast(0f)
    val x0 = near.first.coerceIn(left, maxOf(left, 1f - left - w))
    val y0 = near.second.coerceIn(top, maxOf(top, 1f - top - h))
    val places = lattice(x0, left, 1f - left - w, step).flatMap { x -> lattice(y0, top, 1f - top - h, step / tall).map { y -> Box(x, y, w, h) } }
    return nearestClear(places, taken, x0 to y0, gap, tall)
}

/**
 * The points [step] apart through [through] that lie from [first] to [last], and those two themselves: on a
 * page with edges the room left over is most often along one, and a place hard against it must not be stepped
 * past. [through] alone when there is no room to step.
 */
private fun lattice(
    through: Float,
    first: Float,
    last: Float,
    step: Float,
): List<Float> {
    if (step <= 0f || last <= first) return listOf(through)
    val before = floor((through - first) / step).toInt()
    val after = floor((last - through) / step).toInt()
    return ((-before..after).map { through + it * step } + first + last).distinct()
}

/**
 * Of [places], the nearest to [near] that keeps [gap] from all of [taken]; with none, the one covering least
 * of [taken], marked compromised. Nearest first, then leftmost, then topmost, so the answer is always the same.
 */
private fun nearestClear(
    places: List<Box>,
    taken: List<Box>,
    near: Pair<Float, Float>,
    gap: Float,
    tall: Float,
): Spot {
    val across = (gap - SLACK).coerceAtLeast(0f)
    val down = (gap / tall - SLACK).coerceAtLeast(0f)

    fun far(place: Box) = hypot(place.x - near.first, (place.y - near.second) * tall)
    val ordered = places.sortedWith(compareBy<Box>({ far(it) }, { it.x }, { it.y }))
    ordered.firstOrNull { place -> taken.none { hits(place, it, across, down) } }?.let { return Spot(it) }
    // The order is nearest first and the least is the first of its kind, so of equals the nearest is kept.
    val least = ordered.minBy { place -> taken.sumOf { place.overlap(it).toDouble() } }
    return Spot(least, compromised = true, overlap = taken.sumOf { least.overlap(it).toDouble() }.toFloat())
}

/** A tile on a page: the widget file or app it is, and its cells as it shows. */
data class PageTile(
    val name: String,
    val cell: Cell,
    /** What kind of thing it is, in a word: "widget", "app" or "folder". */
    val kind: String = "widget",
)

/**
 * A page as what is placed on it sees it: its grid of [columns] by [rows] cells and the [tiles] on them.
 *
 * Notes are drawn in the very rectangle the grid is laid out in (the page, [dev.omalauncher.widgets.layout.GridGeometry.page]),
 * so a tile's place in fractions of the page follows from its cells alone, the same on every display: the
 * gap is [GAP_SHARE] of the page's width, a cell is a sixth of what is left of the width beside one gap, and a
 * tile starts a gap after its first cell's line and ends on its last cell's (see GridGeometry.bounds). Down,
 * the same gap is a smaller share, as the page is taller than wide.
 */
class PageBoard(
    rows: Int = NOTEBOOK_MIN_ROWS,
    tiles: List<PageTile> = emptyList(),
    val columns: Int = GRID_COLUMNS,
    private val tall: Float = PAGE_TALL,
) {
    val rows = rows.coerceAtLeast(1)

    /** The tiles as the grid shows them: a saved cell that reaches past the grid is brought inside it. */
    val tiles =
        tiles.map { tile ->
            val w = tile.cell.w.coerceIn(1, columns)
            val h = tile.cell.h.coerceIn(1, this.rows)
            tile.copy(cell = Cell(tile.cell.x.coerceIn(0, columns - w), tile.cell.y.coerceIn(0, this.rows - h), w, h))
        }

    private val gapAcross = GAP_SHARE
    private val gapDown = GAP_SHARE / tall
    private val cellWidth = (1f - gapAcross) / columns
    private val cellHeight = (1f - gapDown) / this.rows

    /** The rectangle a tile in [cell] is drawn in, as fractions of the page. */
    fun box(cell: Cell) = Box(gapAcross + cell.x * cellWidth, gapDown + cell.y * cellHeight, cell.w * cellWidth - gapAcross, cell.h * cellHeight - gapDown)

    /** The cells [box] lies on any part of: those whose own tile it would cover some of. One cell at least, the nearest, for a box in a gap. */
    fun cells(box: Box): Cell {
        val left = floor(box.x / cellWidth + SLACK).toInt().coerceIn(0, columns - 1)
        val top = floor(box.y / cellHeight + SLACK).toInt().coerceIn(0, rows - 1)
        val right = ceil((box.right - gapAcross) / cellWidth - SLACK).toInt().coerceIn(left + 1, columns)
        val bottom = ceil((box.bottom - gapDown) / cellHeight - SLACK).toInt().coerceIn(top + 1, rows)
        return Cell(left, top, right - left, bottom - top)
    }

    /** Which cells have something on them: a tile, or any part of one of [scraps]. By row, then column. */
    private fun covered(scraps: List<Box>): Array<BooleanArray> {
        val covered = Array(rows) { BooleanArray(columns) }
        for (cell in tiles.map { it.cell } + scraps.map(::cells)) for (y in cell.y until cell.bottom) for (x in cell.x until cell.right) covered[y][x] = true
        return covered
    }

    /**
     * The biggest rectangles of cells with nothing on them, neither a tile nor any part of [scraps]: each as
     * big as it can be made (no other free rectangle contains it), the largest first and at most [most] of them.
     */
    fun free(
        scraps: List<Box> = emptyList(),
        most: Int = FREE_RECTANGLES,
    ): List<Cell> {
        val covered = covered(scraps)
        val all = ArrayList<Cell>()
        for (y in 0 until rows) for (x in 0 until columns) for (h in 1..rows - y) for (w in 1..columns - x) {
            if ((y until y + h).all { row -> (x until x + w).none { covered[row][it] } }) all += Cell(x, y, w, h)
        }
        val widest = all.filter { one -> all.none { it != one && it.x <= one.x && it.y <= one.y && it.right >= one.right && it.bottom >= one.bottom } }
        return widest.sortedWith(compareBy<Cell>({ -it.w * it.h }, { it.y }, { it.x }, { -it.w })).take(most)
    }

    /**
     * Where a new note goes on this page among [scraps], the notes and stickers already there, as near as it
     * can be to having its top-left corner at [near]; the page's top-left corner when no place is asked for.
     *
     * A note is [NOTE_WIDTH] by [NOTE_HEIGHT] where that fits, and is made smaller, down to the tile of
     * [SMALLEST_NOTE_COLUMNS] by [SMALLEST_NOTE_ROWS] cells, only when it doesn't fit anywhere: every size is
     * tried over the whole page before the next smaller one. At each size the first choice is whole cells no
     * tile has, with the note in their corner as a tile would be, so it looks placed and not wedged in; then
     * any clear place at all ([clearSpot]), which is what is left when a note or sticker lies across the
     * cells. Only with no clear place at the smallest size does the note overlap, where it covers least, and
     * the answer says so.
     */
    fun noteSpot(
        scraps: List<Box>,
        near: Pair<Float, Float>? = null,
    ): Spot {
        val taken = tiles.map { box(it.cell) } + scraps
        val corner = near ?: (gapAcross to gapDown)
        val blocks = noteBlocks()
        for ((across, down) in blocks) {
            val size = noteSize(across, down)
            val aligned = freeBlocks(across, down).map { box(it).copy(w = size.first, h = size.second) }
            if (aligned.isNotEmpty()) nearestClear(aligned, taken, corner, PLACING_GAP, tall).takeIf { !it.compromised }?.let { return it }
            clearSpot(taken, size.first, size.second, corner, tall = tall).takeIf { !it.compromised }?.let { return it }
        }
        val (w, h) = blocks.last().let { noteSize(it.first, it.second) }
        return clearSpot(taken, w, h, corner, tall = tall)
    }

    /** Where a new note goes when it is wanted at the cell in [column] and [row]; see [noteSpot]. */
    fun noteSpotAt(
        scraps: List<Box>,
        column: Int,
        row: Int,
    ): Spot = noteSpot(scraps, box(Cell(column, row, 1, 1)).let { it.x to it.y })

    /** The size a note has in a tile's place of [across] by [down] cells: its own, or the tile's where that is smaller. */
    private fun noteSize(
        across: Int,
        down: Int,
    ) = box(Cell(0, 0, across, down)).let { minOf(NOTE_WIDTH, it.w) to minOf(NOTE_HEIGHT, it.h) }

    /**
     * The sizes of cell block a note is tried in, the one that gives the biggest note first: from the fewest
     * cells that hold a whole note down to the smallest a note is made to fit. Of two that give the same note
     * the one taking fewer cells comes first.
     */
    private fun noteBlocks(): List<Pair<Int, Int>> {
        val across = (SMALLEST_NOTE_COLUMNS..columns).firstOrNull { box(Cell(0, 0, it, 1)).w >= NOTE_WIDTH } ?: columns
        val down = (SMALLEST_NOTE_ROWS..rows).firstOrNull { box(Cell(0, 0, 1, it)).h >= NOTE_HEIGHT } ?: rows
        val blocks = (minOf(SMALLEST_NOTE_COLUMNS, across)..across).flatMap { w -> (minOf(SMALLEST_NOTE_ROWS, down)..down).map { h -> w to h } }
        return blocks.sortedWith(compareBy({ (w, h) -> noteSize(w, h).let { -it.first * it.second } }, { (w, h) -> w * h }, { (w, _) -> -w }))
    }

    /** Every block of [across] by [down] cells that no tile has any of. */
    private fun freeBlocks(
        across: Int,
        down: Int,
    ): List<Cell> {
        val held = tiles.map { it.cell }
        return (0..rows - down).flatMap { y -> (0..columns - across).map { x -> Cell(x, y, across, down) } }.filter { block -> held.none { it.overlaps(block) } }
    }

    companion object {
        /** How many free rectangles are told of: enough to choose from, few enough to read. */
        const val FREE_RECTANGLES = 6
    }
}

/** What an agent is told when its note had to go on top of something. */
const val NOTE_OVERLAPS = "The page is full: no clear space was left, so the note overlaps what is there. Tell the user."
