package dev.omalauncher.widgets.layout

import dev.omalauncher.canvas.CanvasPad
import dev.omalauncher.canvas.moduleTitle
import dev.omalauncher.canvas.pageNamed
import dev.omalauncher.canvas.refuse
import dev.omalauncher.widgets.Cell
import org.json.JSONObject

/*
 * Picking a page's panel layout in the Manga look (features/manga-look; the mockup's setSheet): the verb `page.panels`,
 * how it applies, undoes and is written, and the refit that goes with it. A panel page keeps one item per panel, each item
 * exactly in its panel's cell.
 */

/** The layout a page gets when it is made for what no longer fits elsewhere (the mockup's overflow page). */
const val OVERFLOW_PANELS = "yonkoma"

/** [v] applied to [n]: exactly, or not at all and why (see [applyVerb]). */
internal fun applyPagePanels(n: Notebook, v: CanvasVerb.PagePanels): Applied {
    fun no(why: String) = Applied(n, false, why)
    val page = n.pages.firstOrNull { it.id == v.id } ?: return no("the page is gone")
    if (v.panels != null && !PanelLayouts.fits(v.panels, n.columns, n.rows)) return no("that panel layout can't be cut on this grid")
    if (v.cells.keys.any { k -> page.items.none { it.key == k } }) return no("something it moves is not on that page")
    if (v.cells.values.any { c -> c.x < 0 || c.y < 0 || c.w < 1 || c.h < 1 || c.right > n.columns || c.bottom > n.rows }) return no("that spot is off the grid")
    val items = page.items.map { item -> v.cells[item.key]?.let { item.copy(cell = it) } ?: item }
    for (k in v.cells.keys) {
        val cell = v.cells.getValue(k)
        if (items.any { it.key != k && it.cell?.overlaps(cell) == true }) return no("that spot is taken")
    }
    return Applied(n.copy(pages = n.pages.map { if (it.id == v.id) it.copy(items = items, panels = v.panels) else it }), true)
}

/** What undoes [v], from the canvas [before] it: the page's layout as it was, and the cells its items had. */
fun inverseOfPagePanels(before: Notebook, v: CanvasVerb.PagePanels): List<CanvasVerb> {
    val page = before.pages.firstOrNull { it.id == v.id } ?: return emptyList()
    val cells = LinkedHashMap<String, Cell>()
    for (k in v.cells.keys) page.items.firstOrNull { it.key == k }?.cell?.let { cells[k] = it }
    return listOf(CanvasVerb.PagePanels(v.id, page.panels, cells))
}

internal fun pagePanelsJson(v: CanvasVerb.PagePanels): JSONObject =
    JSONObject().put("verb", "page.panels").put("id", v.id).putOpt("panels", v.panels)
        .put("cells", JSONObject().also { o -> v.cells.forEach { (k, c) -> o.put(k, VerbJson.cell(c)) } })

/** The verb [o] says; one naming a layout this build doesn't know is a newer build's, kept and never applied. */
internal fun pagePanelsOf(o: JSONObject): CanvasVerb {
    val panels = o.optString("panels").ifEmpty { null }
    if (panels != null && !PanelLayouts.known(panels)) return CanvasVerb.Unknown(o)
    val cells = LinkedHashMap<String, Cell>()
    o.optJSONObject("cells")?.let { c ->
        for (k in c.keys()) c.getJSONObject(k).let { cells[k] = Cell(it.getInt("x"), it.getInt("y"), it.getInt("w"), it.getInt("h")) }
    }
    return CanvasVerb.PagePanels(o.getString("id"), panels, cells)
}

/**
 * A page refit into a panel layout: the [verbs] that do it, in order, and the [notebook] after them; what [moved] to another
 * page (key to page id) because no panel was left for it, the art that was [removed] for the same reason (its picture is
 * kept by the art store), and the pages [made] for what found no empty panel anywhere.
 */
data class PanelFit(
    val verbs: List<CanvasVerb>,
    val notebook: Notebook,
    val moved: List<Pair<String, String>> = emptyList(),
    val removed: List<String> = emptyList(),
    val made: List<String> = emptyList(),
)

/** Whether every item on [page] is in a panel's cell of its layout, one to a panel. */
fun inPanels(n: Notebook, page: NotebookPage): Boolean {
    val cells = PanelLayouts.panels(page.panels ?: return false, n.columns, n.rows).map { it.cell }
    return page.items.all { it.cell in cells } && page.items.map { it.cell }.distinct().size == page.items.size
}

/** The panels of [page] no item touches, in the layout's order. */
fun emptyPanels(n: Notebook, page: NotebookPage): List<Int> {
    val panels = PanelLayouts.panels(page.panels ?: return emptyList(), n.columns, n.rows)
    return panels.indices.filter { i -> page.items.none { it.cell?.overlaps(panels[i].cell) == true } }
}

/** A new page's id and name: the first "pN" no page has, and "Page N" for the place after the last page. */
private fun freshPage(n: Notebook): Pair<String, String> {
    val id = generateSequence(1) { it + 1 }.map { "p$it" }.first { id -> n.pages.none { it.id == id } }
    var number = n.pages.size + 1
    while (n.pages.any { it.name == "Page $number" }) number++
    return id to "Page $number"
}

/**
 * The page [page] of [notebook] refit into the panel layout [layout] (the mockup's setSheet): its items, heaviest first
 * ([weight]: the cells they take now), into its panels, biggest first. What is left over goes too: a widget, app or module
 * to the first empty panel of a panel page after this one (round to the start), else onto a new [OVERFLOW_PANELS] page at the
 * end ([newPage] names it); art leaves the page. [layout] null makes the page the free grid again, every item where it is.
 * The layout has to fit the grid ([PanelLayouts.fits]).
 */
fun fitIntoPanels(
    notebook: Notebook,
    page: String,
    layout: String?,
    newPage: (Notebook) -> Pair<String, String> = ::freshPage,
    weight: (NotebookItem) -> Int = { (it.cell?.w ?: 0) * (it.cell?.h ?: 0) },
): PanelFit {
    var n = notebook
    val verbs = ArrayList<CanvasVerb>()
    fun apply(v: CanvasVerb) {
        val r = applyVerb(n, v)
        check(r.applied) { "a refit's own verb didn't apply: ${r.why}" }
        verbs += v
        n = r.notebook
    }
    val at = n.pages.indexOfFirst { it.id == page }
    require(at >= 0) { "no page $page" }
    if (layout == null) {
        apply(CanvasVerb.PagePanels(page, null))
        return PanelFit(verbs, n)
    }
    val panels = PanelLayouts.panels(layout, n.columns, n.rows)
    require(panels.isNotEmpty()) { "$layout can't be cut on ${n.columns} by ${n.rows}" }
    // Heaviest first; among equals, as they read on the page (top to bottom, left to right).
    val items = n.pages[at].items
        .sortedWith(compareBy<NotebookItem>({ it.cell?.y ?: Int.MAX_VALUE }, { it.cell?.x ?: Int.MAX_VALUE }))
        .sortedByDescending(weight)
    val biggest = panels.indices.sortedByDescending { panels[it].area }
    val cells = LinkedHashMap<String, Cell>()
    biggest.forEachIndexed { k, i -> items.getOrNull(k)?.let { cells[it.key] = panels[i].cell } }
    val moved = ArrayList<Pair<String, String>>()
    val removed = ArrayList<String>()
    val made = ArrayList<String>()
    for (item in items.drop(biggest.size)) {
        if (item.kind == ItemKind.Art) {
            apply(CanvasVerb.Remove(item.key))
            removed += item.key
            continue
        }
        var dest: Pair<String, Cell>? = null
        for (k in 1 until n.pages.size) {
            val q = n.pages[(at + k) % n.pages.size]
            val empty = emptyPanels(n, q).firstOrNull() ?: continue
            dest = q.id to PanelLayouts.panels(q.panels!!, n.columns, n.rows)[empty].cell
            break
        }
        if (dest == null) {
            val (id, name) = newPage(n)
            val strip = if (PanelLayouts.fits(OVERFLOW_PANELS, n.columns, n.rows)) OVERFLOW_PANELS else layout
            apply(CanvasVerb.PageMake(id, name))
            apply(CanvasVerb.PagePanels(id, strip))
            made += id
            dest = id to PanelLayouts.panels(strip, n.columns, n.rows).first().cell
        }
        apply(CanvasVerb.Move(item.key, dest.first, dest.second))
        moved += item.key to dest.first
    }
    apply(CanvasVerb.PagePanels(page, layout, cells))
    return PanelFit(verbs, n, moved, removed, made)
}

/** The verb's own name, as agents say it: `page.panels PAGE LAYOUT|none`. */
const val PAGE_PANELS = "page.panels"

private fun layoutsSaid() = PanelLayouts.all.joinToString(", ") { "${it.id} (${it.name})" } + ", or none"

/**
 * `page.panels PAGE LAYOUT|none` against [pad], as part of [request] from [actor]: the page [PAGE] (its name or id) gets
 * that panel layout, and what is on it is refit (one restore point with the rest of the request). Refused, with nothing
 * changed, for a screen that pins something to every page or a page holding what agents never move.
 */
fun panelWords(words: List<String>, pad: CanvasPad, request: String, actor: String): JSONObject {
    val rest = words.drop(1)
    if (rest.size < 2) refuse("Usage: $PAGE_PANELS PAGE LAYOUT. Layouts: ${layoutsSaid()}. Nothing was changed.")
    val n = pad.notebook()
    val page = pageNamed(n, rest.dropLast(1).joinToString(" "))
    val said = rest.last().lowercase()
    val layout = if (said == "none") null else said.takeIf(PanelLayouts::known) ?: refuse("\"${rest.last()}\" is not a panel layout. Layouts: ${layoutsSaid()}. Nothing was changed.")
    return setPagePanels(pad, page.id, layout, request, actor)
}

/** What an item is called in an answer: an app by its label, a module by its title, a widget by its file. */
private fun titleOf(item: NotebookItem): String = item.app?.label ?: if (item.kind == ItemKind.Folder) "a folder" else moduleTitle(item.key)

/** [panelWords] for a page known by its [id] (the Layouts tile, home): the same refit, check and answer. */
fun setPagePanels(pad: CanvasPad, id: String, layout: String?, request: String, actor: String): JSONObject = try {
    val n = pad.notebook()
    val page = n.pages.firstOrNull { it.id == id } ?: refuse("That page is gone. Nothing was changed.")
    if (pad.rules.pinned(n).isNotEmpty()) refuse("This screen keeps something on every page, so its pages aren't cut into panels. Nothing was changed.")
    page.items.firstOrNull { it.key in pad.rules.kept }?.let { refuse("${titleOf(it)} stays where it is, so \"${page.name}\" isn't cut into panels. Nothing was changed.") }
    if (layout != null && !PanelLayouts.fits(layout, n.columns, n.rows)) refuse("${PanelLayouts.name(layout)} needs more rows or columns than this screen has. Nothing was changed.")
    if (page.panels == layout && (layout == null || inPanels(n, page))) {
        JSONObject().put("ok", true).put("did", "\"${page.name}\" is " + (layout?.let { "already ${PanelLayouts.name(it)}" } ?: "already the free grid"))
    } else {
        val fit = fitIntoPanels(n, id, layout, newPage = { now -> pad.rules.pageId(now) to freshPage(now).second })
        pad.apply(fit.verbs, request, actor)?.let { refuse(if (fit.verbs.size == 1) "$it. Nothing was changed." else "It stopped partway: $it. What came before that stays, and undo takes it back.") }
        val went = fit.moved.groupBy({ it.second }, { it.first }).map { (to, keys) ->
            keys.joinToString(" and ") { key -> titleOf(page.items.first { it.key == key }) } + " went to \"" + (fit.notebook.pages.firstOrNull { it.id == to }?.name ?: to) + "\""
        } + if (fit.removed.isEmpty()) emptyList() else listOf(fit.removed.size.let { if (it == 1) "a picture" else "$it pictures" } + " left the page (kept, to hang again)")
        JSONObject().put("ok", true).put("did", "\"${page.name}\" is now " + (layout?.let { PanelLayouts.name(it) } ?: "the free grid") +
            if (went.isEmpty()) "" else ". No panel left there, so " + went.joinToString("; "))
    }
} catch (e: dev.omalauncher.canvas.AskRefused) {
    JSONObject().put("ok", false).put("error", e.message)
}
