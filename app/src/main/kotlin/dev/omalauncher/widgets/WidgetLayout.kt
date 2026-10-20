package dev.omalauncher.widgets

import dev.omalauncher.widgets.layout.Placement
import dev.omalauncher.widgets.layout.Tile
import dev.omalauncher.widgets.layout.estimatedGeometry
import dev.omalauncher.widgets.layout.firstFit
import dev.omalauncher.widgets.layout.shown

/**
 * Where a tile of [w] by [h] cells goes on from a page with no room for it (PRD UP-1): the first of [pages]
 * after the one at position [from] with a free spot at that size, [takenOn] giving the cells taken on a page.
 * Null when none has: then a new page after the last is what there is to offer.
 */
fun nextPageWithRoom(
    pages: List<String>,
    from: Int,
    w: Int,
    h: Int,
    cols: Int,
    rows: Int,
    takenOn: (String) -> List<Cell>,
): String? = pages.drop(from + 1).firstOrNull { firstFit(w, h, takenOn(it), cols, rows) != null }

/**
 * Where each widget and pinned app sits on its page's grid, keyed by file name (or shortcut key). The cells
 * are the [notebook]'s, kept on the launcher side so rearranging never rewrites the widget files agents own.
 */
class WidgetLayout(
    private val notebook: LiveNotebook,
) {
    fun cell(name: String) = notebook.cell(name)

    /** Live update while dragging; [save] persists. An unchanged cell isn't written, so pointer moves within it don't recompose the page. */
    fun set(
        name: String,
        cell: Cell,
    ) = notebook.setCell(name, cell)

    /** Finds the cells; the arithmetic lives in the layout core, shared with its tests. */
    private val placement = Placement<HomeItem>(notebook.cells, ::save) { Tile(it.key, it.size, it.resizable) }

    /**
     * Gives widgets that have no cell yet one at their declared size, in the
     * first free spot. Saved cells are never rewritten here.
     *
     * Returns the items that don't fit at their size; they get no cell, so
     * the caller can move them to a page with room instead of squeezing them
     * in or stacking them on top of other tiles. That includes apps pinned to
     * a full page, such as page 1 of a fresh install.
     */
    fun place(
        items: List<HomeItem>,
        cols: Int,
        rows: Int,
        /** The id of the page being laid out: the cells of widgets on it whose files aren't there now stay theirs. */
        page: String? = null,
    ): List<HomeItem> = placement.place(items, cols, rows, held(page, items))

    /** The places kept on [page], which shows [items], for what isn't shown there now (a widget whose file is missing). */
    fun heldOn(
        page: String,
        items: List<HomeItem>,
    ) = held(page, items)

    private fun held(
        page: String?,
        items: List<HomeItem>,
    ) = page?.let { notebook.cellsOn(it, except = items.map { item -> item.key }.toSet()) }.orEmpty()

    /**
     * Gives [item] a free spot at its size among [others]; false when there's none, or no page was laid out yet.
     * Those of [others] without a cell yet (their page hasn't been laid out, or is being laid out this very frame,
     * with two pages showing) get theirs first: the page's own tiles keep their spots, the newcomer takes what's left.
     */
    fun claim(
        item: HomeItem,
        others: List<HomeItem>,
        page: String? = null,
    ): Boolean = placement.claim(item, others, held(page, others + item))

    /**
     * The cells taken on [page], which shows [items], for a tile that isn't [mover]: the tiles as they show (an
     * app as 1x1) and the places kept for widgets whose files are missing.
     */
    private fun takenOn(
        page: String,
        items: List<HomeItem>,
        mover: String,
    ) = held(page, items) + items.filter { it.key != mover }.mapNotNull { notebook.cell(it.key)?.shown(it.resizable) }

    /**
     * Whether the tile [mover] can be put down in exactly [cell] on [page], which shows [items]: on no other
     * tile, and not on the place kept for a widget whose file is missing, which shows nothing but is still its.
     */
    fun freeAt(
        cell: Cell,
        mover: String,
        page: String,
        items: List<HomeItem>,
    ) = takenOn(page, items, mover).none { it.overlaps(cell) }

    /** Whether [page], showing [items], has a free spot for the tile [mover] at the size of [cell]. */
    fun roomFor(
        cell: Cell,
        mover: String,
        page: String,
        items: List<HomeItem>,
        cols: Int,
        rows: Int,
    ) = firstFit(cell.w, cell.h, takenOn(page, items, mover), cols, rows) != null

    /** The next page after [page] with room for the tile [mover] at the size of [cell]; null when none has. See [nextPageWithRoom]. */
    fun nextWithRoom(
        cell: Cell,
        mover: String,
        page: String,
        itemsOn: (String) -> List<HomeItem>,
        cols: Int,
        rows: Int,
    ): String? = nextPageWithRoom(notebook.pages.map { it.id }, notebook.position(page), cell.w, cell.h, cols, rows) { takenOn(it, itemsOn(it), mover) }

    /** Whether home has laid a page out yet; before that there is no grid to [claim] a spot on. */
    val laidOut get() = placement.grid != null

    /**
     * Gives [item], just put on a page, its cells without waiting for home to show that page: the first free
     * ones there, or on the next page with room at its size, or on a new page after the last. [itemsOn] lists
     * what is on a page by its id. Returns the id of the page it is on now; null, and nothing changed, when it
     * is on no page or home has no grid yet (it is placed when its page is first laid out, as always). One that
     * has cells already keeps them and its page, whoever calls: this places, it never rearranges.
     */
    fun land(
        item: HomeItem,
        itemsOn: (String) -> List<HomeItem>,
    ): String? {
        val from = notebook.position(notebook.pageOf(item.key))
        if (from < 0) return null
        val pages = notebook.pages.map { it.id }
        if (notebook.cell(item.key) != null) return pages[from]
        if (!laidOut) return null
        val page = pages.drop(from).firstOrNull { claim(item, itemsOn(it), it) } ?: notebook.addPage() ?: return null
        if (page != pages[from]) notebook.move(item.key, page)
        return page
    }

    /** Forgets everything about [name]: its cell and page. */
    fun remove(name: String) = notebook.forget(name)

    /** The home grid's cell width and height on screen and the gap between tiles, in pixels, as last laid out. */
    @Volatile var geometry: Triple<Float, Float, Int>? = null

    /** A tile's size in pixels at [cells]; before the home grid was laid out, an estimate from the screen width. */
    fun tilePx(
        cells: Pair<Int, Int>,
        metrics: android.util.DisplayMetrics,
    ): Pair<Int, Int> = dev.omalauncher.widgets.layout.tilePx(cells, geometry ?: estimatedGeometry(metrics.widthPixels, metrics.density))

    fun save() = notebook.save()
}
