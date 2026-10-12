package dev.omalauncher.widgets.layout

import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.GRID_COLUMNS
import org.json.JSONArray
import org.json.JSONObject

/** Rows of a notebook page when nothing saved reaches further down, and the most a page may have. */
const val NOTEBOOK_MIN_ROWS = 9
const val NOTEBOOK_MAX_ROWS = 12

/**
 * What the launcher saved before the notebook, as raw as it sits in SharedPreferences.
 * From `widget_grid`: [layout] (key `layout`), [legacyCells] (key `cells`, launchers before versioning) and
 * [portraitRows] (keys `portrait_rows_<size>`, by size). From `shortcuts`: [shortcuts] (key `items`) and
 * [folders] (key `folders`). [legacyPages] is the whole of the old `widget_pages` preferences, if still there.
 */
data class OldStores(
    val layout: String? = null,
    val legacyCells: String? = null,
    val portraitRows: Map<String, Int> = emptyMap(),
    val shortcuts: String? = null,
    val folders: String? = null,
    val legacyPages: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** From the preferences' own maps (`SharedPreferences.all`); values of another type than expected are left out. */
        fun of(
            widgetGrid: Map<String, *>,
            shortcuts: Map<String, *>,
            widgetPages: Map<String, *> = emptyMap<String, Any?>(),
        ) = OldStores(
            layout = widgetGrid["layout"] as? String,
            legacyCells = widgetGrid["cells"] as? String,
            portraitRows =
                widgetGrid.entries
                    .filter { it.key.startsWith(PORTRAIT_ROWS) && it.value is Int }
                    .associate { it.key.removePrefix(PORTRAIT_ROWS) to it.value as Int },
            shortcuts = shortcuts["items"] as? String,
            folders = shortcuts["folders"] as? String,
            legacyPages = widgetPages.entries.filter { it.value is Int }.associate { it.key to it.value as Int },
        )

        private const val PORTRAIT_ROWS = "portrait_rows_"
    }
}

/** A widget file that exists: its file name, the page its file asks for (a page chosen on the phone wins) and its declared size. */
data class OldWidget(
    val key: String,
    val page: Int,
    val size: Pair<Int, Int>? = null,
)

/** An item that isn't in the cell it was saved in: [from] is null if it had none; [newPage] if its page had no room. */
data class Moved(
    val key: String,
    val from: Cell?,
    val to: Cell,
    val page: String,
    val newPage: Boolean,
)

/** What a migration did, for telling Boss and for deciding whether to keep it. */
data class MigrationReport(
    /** Rows of every page, and how far down the tallest saved page reached. */
    val rows: Int,
    val tallest: Int,
    val items: Int,
    /** Items whose saved cell didn't fit and was given another. */
    val replaced: Int,
    /** Items that had no saved cell and were given one. */
    val placed: Int,
    /** Pages added after a page that had no room. */
    val pagesAdded: Int,
    val moved: List<Moved>,
    /** The new page's id for each old page number. */
    val pageIds: Map<Int, String>,
    /** Pairs of kept cells that lie over each other, as they did before. */
    val overlaps: Int,
    /** Saved cells and page choices that belong to nothing that exists; left behind. */
    val orphans: List<String>,
    /** Row counts the old grid had per window, by size; nothing is derived from them. */
    val portraitRows: Map<String, Int>,
    /** What couldn't be read. Not empty means the old data was only partly converted: show it, don't save it. */
    val problems: List<String>,
) {
    val clean get() = problems.isEmpty()

    fun summary(): String =
        buildString {
            append("$items items on ${pageIds.size + pagesAdded} pages of $GRID_COLUMNS x $rows (tallest saved page: $tallest rows). ")
            append("$replaced re-placed because their cell didn't fit, $placed placed for the first time, $pagesAdded pages added.")
            if (overlaps > 0) append(" $overlaps pairs of tiles overlap, as before.")
            if (orphans.isNotEmpty()) append(" ${orphans.size} saved cells belong to nothing and were left behind.")
            for (p in problems) append("\nproblem: $p")
        }
}

class Migration(
    val notebook: Notebook,
    val report: MigrationReport,
)

/**
 * The notebook that holds what [old] and the [widgets] that exist held: the same items on the same pages in
 * the same cells, wherever they fit. Page numbers close up into positions 1..n, named "Page 1".."Page n".
 * Every page gets as many rows as the tallest saved page, at least [NOTEBOOK_MIN_ROWS] and at most
 * [NOTEBOOK_MAX_ROWS]. An item without a cell gets the first free one on its page; so does one whose cell
 * doesn't fit, and when its page is full it goes to a new page right after it. Cells that overlap stay as they are.
 *
 * Changes nothing and never throws: what can't be read is left out and named in the report.
 */
fun migrate(
    old: OldStores,
    widgets: List<OldWidget>,
): Migration =
    try {
        Migrator(old, widgets).run()
    } catch (e: Exception) {
        val report =
            MigrationReport(NOTEBOOK_MIN_ROWS, 0, 0, 0, 0, 0, emptyList(), mapOf(1 to "p1"), 0, emptyList(), old.portraitRows, listOf("migration failed: $e"))
        Migration(Notebook(NOTEBOOK_MIN_ROWS, listOf(NotebookPage("p1", "Page 1"))), report)
    }

private class OldItem(
    val key: String,
    val page: Int,
    val kind: ItemKind,
    val size: Pair<Int, Int>?,
    val app: PinnedApp? = null,
    val apps: List<PinnedApp> = emptyList(),
) {
    val resizable get() = kind == ItemKind.Widget
}

private class Migrator(
    private val old: OldStores,
    private val widgets: List<OldWidget>,
) {
    private val problems = ArrayList<String>()
    private val cells = LinkedHashMap<String, Cell>()
    private val pageChoices = LinkedHashMap<String, Int>()

    fun run(): Migration {
        readLayout()
        val items = items()
        val cols = GRID_COLUMNS
        val tallest = usedRows(items.map { cells[it.key] to it.resizable })
        val rows = maxOf(NOTEBOOK_MIN_ROWS, tallest).coerceAtMost(NOTEBOOK_MAX_ROWS)

        val moved = ArrayList<Moved>()
        var overlaps = 0
        var added = 0
        // Each old page, then the pages added after it for what it had no room for.
        val built = ArrayList<Pair<Int?, ArrayList<NotebookItem>>>()
        for (number in items.map { it.page }.distinct().sorted().ifEmpty { listOf(1) }) {
            val here = items.filter { it.page == number }
            val kept = here.mapNotNull { item -> cells[item.key]?.let { onScreen(it, item.resizable) }?.takeIf { it.y < rows && it.h <= rows - it.y }?.let { item.key to it } }.toMap()
            for ((i, a) in kept.values.withIndex()) overlaps += kept.values.drop(i + 1).count { it.overlaps(a) }
            val page = ArrayList<NotebookItem>()
            val overflow = ArrayList<ArrayList<NotebookItem>>()
            // In two passes, so a first-fit never takes a spot a kept cell further down the list holds.
            val taken = ArrayList(kept.values)
            val spots = HashMap<String, Cell>()
            for (item in here) {
                if (item.key in kept) continue
                val (w, h) = placedSize(cells[item.key]?.let { onScreen(it, item.resizable) }?.let { it.w to it.h } ?: item.size, cols, rows)
                firstFit(w, h, taken, cols, rows)?.let {
                    taken += it
                    spots[item.key] = it
                }
            }
            for (item in here) {
                val saved = cells[item.key]
                val cell = kept[item.key] ?: spots[item.key]
                if (cell != null) {
                    page += item.at(cell)
                    // The 1x1 an app always shows as is no move.
                    if (cell != saved?.shown(item.resizable)) moved += Moved(item.key, saved, cell, "", false)
                    continue
                }
                val (w, h) = placedSize(saved?.let { onScreen(it, item.resizable) }?.let { it.w to it.h } ?: item.size, cols, rows)
                // A page added for this one's neighbours may still have room; an empty page always has.
                val spot =
                    overflow.firstNotNullOfOrNull { extra -> firstFit(w, h, extra.mapNotNull { it.cell }, cols, rows)?.let { extra to it } }
                        ?: (ArrayList<NotebookItem>().also { overflow += it } to firstFit(w, h, emptyList(), cols, rows)!!)
                spot.first += item.at(spot.second)
                moved += Moved(item.key, saved, spot.second, "", true)
            }
            built += number to page
            overflow.forEach { built += null to it }
            added += overflow.size
        }

        val pages = built.mapIndexed { i, (_, list) -> NotebookPage("p${i + 1}", "Page ${i + 1}", list) }
        val pageOf = pages.flatMap { p -> p.items.map { it.key to p.id } }.toMap()
        val known = items.map { it.key }.toSet()
        val report =
            MigrationReport(
                rows = rows,
                tallest = tallest,
                items = items.size,
                replaced = moved.count { it.from != null },
                placed = moved.count { it.from == null },
                pagesAdded = added,
                moved = moved.map { it.copy(page = pageOf.getValue(it.key)) },
                pageIds = built.mapIndexedNotNull { i, (number, _) -> number?.let { it to pages[i].id } }.toMap(),
                overlaps = overlaps,
                orphans = (cells.keys + pageChoices.keys).filter { it !in known }.distinct(),
                portraitRows = old.portraitRows,
                problems = problems,
            )
        return Migration(Notebook(rows, pages), report)
    }

    private fun OldItem.at(cell: Cell) = NotebookItem(key, kind, cell, app, apps)

    /**
     * A saved cell where home shows its tile today: an app as 1x1, and a cell that hangs over a side pulled in,
     * as the grid does when it draws it. It fits the notebook if it doesn't reach below its last row.
     */
    private fun onScreen(
        c: Cell,
        resizable: Boolean,
    ): Cell {
        val s = c.shown(resizable)
        val w = s.w.coerceIn(1, GRID_COLUMNS)
        return Cell(s.x.coerceIn(0, GRID_COLUMNS - w), s.y.coerceAtLeast(0), w, s.h.coerceAtLeast(1))
    }

    /** Cells and page choices as [dev.omalauncher.widgets.WidgetLayout] loads them: the versioned layout, then what a launcher before versioning left, which wins. */
    private fun readLayout() {
        val data = Versioned.read(old.layout, 1) as? JSONObject
        if (data == null && !old.layout.isNullOrBlank()) problems += "the saved layout (widget_grid: layout) can't be read; items were placed afresh"
        data?.let { load(it, "widget_grid: layout") }
        if (old.legacyCells != null) {
            runCatching { JSONObject(old.legacyCells.orEmpty()) }
                .onSuccess { load(JSONObject().put("columns", 6).put("cells", it), "widget_grid: cells") }
                .onFailure { problems += "the older saved cells (widget_grid: cells) can't be read" }
        }
        pageChoices.putAll(old.legacyPages)
    }

    private fun load(
        data: JSONObject,
        from: String,
    ) {
        val columns = data.optInt("columns", GRID_COLUMNS)
        val saved = data.optJSONObject("cells")
        if (saved == null && data.has("cells")) problems += "the cells of $from aren't a table"
        var bad = 0
        for (key in saved?.keys()?.asSequence()?.toList().orEmpty()) {
            // Rescaling is part of reading: a cell that lay beyond its own grid can't be moved onto this one.
            val cell = runCatching { saved!!.optJSONObject(key)?.let { c -> rescale(Cell(c.getInt("x"), c.getInt("y"), c.getInt("w"), c.getInt("h")), columns) } }.getOrNull()
            if (cell == null) bad++ else cells[key] = cell
        }
        if (bad > 0) problems += "$bad cells of $from can't be read"
        val pages = data.optJSONObject("pages")
        if (pages == null && data.has("pages")) problems += "the pages of $from aren't a table"
        pages?.let { p -> for (k in p.keys()) pageChoices[k] = p.optInt(k, 1) }
    }

    /** Everything on the grid, in the order home lists it: widgets, apps, folders. A key seen twice counts once. */
    private fun items(): List<OldItem> {
        val (apps, folders) = readShortcuts()
        val all =
            widgets.map { OldItem(it.key, pageChoices[it.key] ?: it.page, ItemKind.Widget, it.size) } +
                apps.filter { it.second == null }.map { (app, _, page) -> OldItem(app.key, page, ItemKind.App, 1 to 1, app = app) } +
                folders.map { (key, page) -> OldItem(key, page, ItemKind.Folder, 1 to 1, apps = apps.filter { it.second == key }.map { it.first }) }
        val seen = HashSet<String>()
        val unique = all.filter { seen.add(it.key) }
        if (unique.size < all.size) problems += "${all.size - unique.size} items share a name with another; the first of each was kept"
        return unique
    }

    /** Pinned apps (each with its folder, if that exists, and its page) and folders (key and page), as [dev.omalauncher.widgets.Shortcuts] loads them. */
    private fun readShortcuts(): Pair<List<Triple<PinnedApp, String?, Int>>, List<Pair<String, Int>>> {
        // Version 0 (before versioning) is the same array, unwrapped.
        val array = Versioned.read(old.shortcuts, 1) { _, data -> data } as? JSONArray
        if (array == null && !old.shortcuts.isNullOrBlank()) problems += "the pinned apps (shortcuts: items) can't be read"
        val apps =
            array.objects("pinned apps (shortcuts: items)") { o ->
                Triple(NotebookStore.app(o), o.optString("folder").ifEmpty { null }, o.optInt("page", 1))
            }
        val folderArray = Versioned.read(old.folders, 1) as? JSONArray
        if (folderArray == null && !old.folders.isNullOrBlank()) problems += "the folders (shortcuts: folders) can't be read"
        val folders = folderArray.objects("folders (shortcuts: folders)") { it.getString("key") to it.optInt("page", 1) }
        // An app whose folder is gone goes back on the grid.
        val known = folders.map { it.first }.toSet()
        return apps.map { if (it.second?.let(known::contains) == true) it else it.copy(second = null) } to folders
    }

    private fun <T : Any> JSONArray?.objects(
        what: String,
        parse: (JSONObject) -> T,
    ): List<T> {
        this ?: return emptyList()
        val parsed = (0 until length()).mapNotNull { i -> runCatching { optJSONObject(i)?.let(parse) }.getOrNull() }
        if (parsed.size < length()) problems += "${length() - parsed.size} $what can't be read"
        return parsed
    }
}
