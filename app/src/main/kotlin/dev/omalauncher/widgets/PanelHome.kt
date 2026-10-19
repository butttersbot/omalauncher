package dev.omalauncher.widgets

import dev.omalauncher.art.PanelHome
import dev.omalauncher.art.PanelPage
import dev.omalauncher.ui.ActivePack
import dev.omalauncher.theme.pack.PackPanels
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.OVERFLOW_PANELS
import dev.omalauncher.widgets.layout.Panel
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.PanelPoint
import dev.omalauncher.widgets.layout.PanelStyle
import dev.omalauncher.widgets.layout.artKey
import dev.omalauncher.widgets.layout.fitIntoPanels
import dev.omalauncher.widgets.layout.inPanels

/*
 * Home in panels (the Manga look, piece B; features/manga-look in ClaudeSync): the rules home arranges a panel page by,
 * apart from the drawing (ui/PanelPage.kt). A panel page keeps one thing per panel, each exactly in its panel's cell, and
 * nothing that is carried, placed or fitted ever moves a panel's lines.
 */

/** The `[panels]` of the look picked, or null when it isn't drawn in panels. Not a preview's: the pick itself. */
fun pickedPanels(): PackPanels? = ActivePack.pick.let { if (it.isConsole) null else it.load?.pack?.panels }

/** The look's margins and gutters as the panel layouts take them (the same page units). */
fun PackPanels.style(): PanelStyle =
    PanelStyle(
        marginLeft = margins.left.toDouble(),
        marginRight = margins.right.toDouble(),
        marginTop = margins.top.toDouble(),
        marginBottom = margins.bottom.toDouble(),
        gutterRows = gutterRows.toDouble(),
        gutterCols = gutterCols.toDouble(),
        pad = pad.toDouble(),
    )

/** The panels of [layout] on a grid of [cols] by [rows]; null for a free page, or a layout that can't be cut there. */
fun panelsOf(
    layout: String?,
    cols: Int,
    rows: Int,
    style: PanelStyle = PanelStyle(),
): List<Panel>? = layout?.let { PanelLayouts.panels(it, cols, rows, style) }?.takeIf { it.isNotEmpty() }

/** Whether the point ([x], [y]) is inside the polygon [pts] (all in fractions of the page). */
fun inside(
    pts: List<PanelPoint>,
    x: Double,
    y: Double,
): Boolean {
    var inside = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/**
 * The panel a finger at ([x], [y]), fractions of the page, is over: the one whose ink it is inside, else the one whose
 * cell is under it, so a gutter or a margin belongs to a panel too and a drop never falls between them. -1 off the page.
 */
fun panelAt(
    panels: List<Panel>,
    cols: Int,
    rows: Int,
    x: Double,
    y: Double,
): Int {
    if (x < 0 || y < 0 || x >= 1 || y >= 1) return -1
    panels.indexOfFirst { inside(it.outline, x, y) }.takeIf { it >= 0 }?.let { return it }
    val col = (x * cols).toInt()
    val row = (y * rows).toInt()
    return panels.indexOfFirst { p -> col >= p.cell.x && col < p.cell.right && row >= p.cell.y && row < p.cell.bottom }
}

/** The panel [cell] is in: the one it is exactly, else the one panel it lies inside; -1 for neither. */
fun panelOf(
    panels: List<Panel>,
    cell: Cell,
): Int {
    panels.indexOfFirst { it.cell == cell }.takeIf { it >= 0 }?.let { return it }
    val within = panels.indices.filter { i -> panels[i].cell.let { cell.x >= it.x && cell.y >= it.y && cell.right <= it.right && cell.bottom <= it.bottom } }
    return within.singleOrNull() ?: -1
}

/** Where a carried thing would land on a panel page: the [panel] (its [cell]), and the [occupant] it would swap with. */
data class PanelAim(
    val panel: Int,
    val cell: Cell,
    val occupant: String?,
)

/**
 * Where [carried] lands with the finger at ([x], [y]) on a panel page holding [items] (each key with its cell; the carried
 * one among them when it came from this page): the panel there, and what is in it, to swap with. Null off the page and
 * over its own panel, where a drop changes nothing; and, for an app lifted out of a folder ([inFolder]), which has no
 * panel to give in trade, over a panel that holds something.
 */
fun panelAim(
    panels: List<Panel>,
    items: List<Pair<String, Cell>>,
    carried: String,
    cols: Int,
    rows: Int,
    x: Double,
    y: Double,
    inFolder: Boolean = false,
): PanelAim? {
    val i = panelAt(panels, cols, rows, x, y)
    if (i < 0) return null
    val cell = panels[i].cell
    if (items.any { (key, c) -> key == carried && c.overlaps(cell) }) return null
    val occupant = items.firstOrNull { (key, c) -> key != carried && c.overlaps(cell) }?.first
    if (occupant != null && inFolder) return null
    return PanelAim(i, cell, occupant)
}

/**
 * A drop on a manga page, as home does it: on the panel holding [occupant], the carried [item] (from [cell] on page [from])
 * and the occupant trade cells, and pages when they were on different ones; on an empty panel, [item] takes its cell [to]
 * and the page. An app lifted out of a folder ([inFolder]) has no cell to trade and only moves. [set] gives a key a cell,
 * [move] puts it on a page. Whether anything changed. The panel lines are never touched: only what is in them.
 */
fun dropInPanel(
    item: String,
    from: String,
    cell: Cell,
    page: String,
    to: Cell,
    occupant: String?,
    cellOf: (String) -> Cell?,
    set: (String, Cell) -> Unit,
    move: (String, String) -> Unit,
    inFolder: Boolean = false,
): Boolean {
    if (occupant != null) {
        if (inFolder || occupant == item) return false
        val other = cellOf(occupant) ?: return false
        set(occupant, cell)
        set(item, other)
        if (page != from) {
            move(occupant, from)
            move(item, page)
        }
        return true
    }
    set(item, to)
    if (page != from || inFolder) move(item, page)
    return true
}

/** What [settleIntoPanels] comes to: the new [cells] of what had to move into a panel, and what found [noRoom] there. */
data class PanelSettle(
    val cells: Map<String, Cell>,
    val noRoom: List<String>,
)

/**
 * A panel page put back in order, as it is shown: what is exactly in a panel it alone holds stays; what lies inside one
 * empty panel (an app at one cell, say) fills it; everything else (new, without a cell, across a line, or a second thing in
 * a panel) takes the first empty panel in the layout's order, and what finds none has [PanelSettle.noRoom] there. [held]
 * are places kept on the page for what isn't shown now (a widget whose file is missing): their panels aren't free.
 */
fun settleIntoPanels(
    panels: List<Panel>,
    items: List<Pair<String, Cell?>>,
    held: List<Cell> = emptyList(),
): PanelSettle {
    val taken = BooleanArray(panels.size) { i -> held.any { it.overlaps(panels[i].cell) } }
    val placed = HashMap<String, Int>()
    for ((key, cell) in items) {
        val i = cell?.let { c -> panels.indexOfFirst { it.cell == c } } ?: -1
        if (i >= 0 && !taken[i]) {
            taken[i] = true
            placed[key] = i
        }
    }
    for ((key, cell) in items) {
        if (key in placed || cell == null) continue
        val i = panelOf(panels, cell)
        if (i >= 0 && !taken[i]) {
            taken[i] = true
            placed[key] = i
        }
    }
    val cells = LinkedHashMap<String, Cell>()
    val noRoom = ArrayList<String>()
    for ((key, cell) in items) {
        val i = placed[key] ?: taken.indexOfFirst { !it }.takeIf { it >= 0 }?.also { taken[it] = true }
        if (i == null) noRoom += key else if (panels[i].cell != cell) cells[key] = panels[i].cell
    }
    return PanelSettle(cells, noRoom)
}

/**
 * Whether a page of [page]'s items needs its loose apps gathered into one folder (the cast) before it is fitted into
 * [panels] panels: only when there are more things on it than panels, and at least two loose apps to gather. The apps, in
 * the page's reading order; empty when it needn't.
 */
fun castOf(
    page: NotebookPage,
    panels: Int,
): List<String> {
    val apps = page.items.filter { it.kind == ItemKind.App }
    if (page.items.size <= panels || apps.size < 2) return emptyList()
    return apps.sortedWith(compareBy({ it.cell?.y ?: Int.MAX_VALUE }, { it.cell?.x ?: Int.MAX_VALUE })).map { it.key }
}

/**
 * Home fitted into panels as the look turns to one drawn in them: each page that isn't in panels (a free page, or one
 * rearranged in another look) refit into its own layout, or [default] when it has none or its own can't be cut here, in
 * page order, so what one page has no panel for goes on to the next with an empty panel, or a page made for it
 * ([fitIntoPanels]). Pages made for overflow are in panels already. [notebook] has the cells as saved, so a page in panels
 * is known as one; [weight] says which goes into the biggest panel. [newPage] names a page made; the verbs, in order.
 */
fun fitHomeIntoPanels(
    notebook: Notebook,
    default: String,
    newPage: (Notebook) -> Pair<String, String>,
    weight: (NotebookItem) -> Int = ::shownWeight,
): List<CanvasVerb> {
    var n = notebook
    val verbs = ArrayList<CanvasVerb>()
    var at = 0
    while (at < n.pages.size) {
        val page = n.pages[at++]
        if (page.panels != null && inPanels(n, page)) continue
        val layout = page.panels?.takeIf { PanelLayouts.fits(it, n.columns, n.rows) } ?: default.takeIf { PanelLayouts.fits(it, n.columns, n.rows) } ?: continue
        val fit = fitIntoPanels(n, page.id, layout, newPage, weight)
        verbs += fit.verbs
        n = fit.notebook
    }
    return verbs
}

/** How heavy [item] is in a refit: the cells it shows in, an app or a folder one, whatever its saved cell says. */
fun shownWeight(item: NotebookItem): Int = if (item.kind == ItemKind.App || item.kind == ItemKind.Folder) 1 else (item.cell?.w ?: 0) * (item.cell?.h ?: 0)

/** A new page's id from [id] and the name "Page N" for its place after the last, as no page has it yet. */
fun panelPageNamed(
    n: Notebook,
    id: String,
): Pair<String, String> {
    var number = n.pages.size + 1
    while (n.pages.any { it.name == "Page $number" }) number++
    return id to "Page $number"
}

/** The cells taken on [page] now: what is shown there, and the places kept for what isn't (a widget whose file is missing). */
private fun LiveNotebook.cellsOnPage(page: String): List<Cell> =
    snapshot().pages.firstOrNull { it.id == page }?.items.orEmpty().mapNotNull { it.cell }

/** The panels of [page] that hold nothing, by their index in its layout; empty for a free page. */
fun LiveNotebook.emptyPanelsOn(
    page: String,
    columns: Int = GRID_COLUMNS,
): List<Int> {
    val panels = panelsOf(pages.firstOrNull { it.id == page }?.panels, columns, rows) ?: return emptyList()
    val taken = cellsOnPage(page)
    return panels.indices.filter { i -> taken.none { it.overlaps(panels[i].cell) } }
}

/**
 * Puts [key], which has no place on its panel page, where there is one: the first empty panel of a panel page after it,
 * else a free page after it with room ([claimFree]), else a new page cut as the overflow strip (or [layout] where that can't
 * be cut) in its first panel. The page it went to; null, and nothing changed, before home is open.
 */
fun LiveNotebook.overflowIntoPanels(
    key: String,
    layout: String,
    claimFree: (String) -> Boolean,
    columns: Int = GRID_COLUMNS,
): String? {
    if (!opened) return null
    val from = position(pageOf(key))
    for (page in pages.drop(from + 1).map { it.id }) {
        val cut = pages.first { it.id == page }.panels
        if (cut == null) {
            if (claimFree(page)) {
                move(key, page)
                return page
            }
            continue
        }
        val empty = emptyPanelsOn(page, columns).firstOrNull() ?: continue
        move(key, page)
        setCell(key, panelsOf(cut, columns, rows)!![empty].cell)
        save()
        return page
    }
    val strip = OVERFLOW_PANELS.takeIf { PanelLayouts.fits(it, columns, rows) } ?: layout
    val made = addPage() ?: return null
    setPanels(made, strip)
    move(key, made)
    setCell(key, panelsOf(strip, columns, rows)!!.first().cell)
    save()
    return made
}

/**
 * Home's panel pages for the art placer (piece C's [PanelHome]): while the look is drawn in panels ([on]), each panel page
 * with which of its panels are empty, and art hung by putting `art:<id>` into a panel's cell. [onHung] is told where, for
 * the "パッ" home shows there.
 */
class HomePanels(
    private val home: () -> LiveNotebook,
    private val on: () -> Boolean = { pickedPanels() != null },
    private val onHung: (String, Int) -> Unit = { _, _ -> },
    private val columns: Int = GRID_COLUMNS,
) : PanelHome {
    override fun panelPages(): List<PanelPage> {
        val notebook = home()
        if (!on() || !notebook.opened) return emptyList()
        return notebook.pages.filter { panelsOf(it.panels, columns, notebook.rows) != null }.map { page ->
            val panels = panelsOf(page.panels, columns, notebook.rows)!!
            val empty = notebook.emptyPanelsOn(page.id, columns).toSet()
            PanelPage(page.id, page.name, panels.indices.map { it in empty })
        }
    }

    override fun put(
        artId: String,
        page: String,
        panel: Int,
    ): Boolean {
        val notebook = home()
        if (!on() || !notebook.opened) return false
        val cut = panelsOf(notebook.pages.firstOrNull { it.id == page }?.panels, columns, notebook.rows) ?: return false
        if (panel !in cut.indices || panel !in notebook.emptyPanelsOn(page, columns)) return false
        val key = artKey(artId)
        notebook.move(key, page)
        notebook.setCell(key, cut[panel].cell)
        notebook.save()
        onHung(page, panel)
        return true
    }
}
