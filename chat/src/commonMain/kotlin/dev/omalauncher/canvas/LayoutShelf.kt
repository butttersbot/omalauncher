package dev.omalauncher.canvas

import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PAGE_START
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.applyVerb
import dev.omalauncher.widgets.layout.artIdOf
import dev.omalauncher.widgets.layout.firstFit
import org.json.JSONArray
import org.json.JSONObject

/*
 * Saved layouts (Manga look, piece D; Boss, 2026-10-19: "we definitely need to be able to save layouts"). A saved layout is
 * the whole home as the notebook store writes it (pages, cells, apps and folders, the dock, and with the Manga look each
 * page's panels and its art), the look it was saved in, and when. It is this screen's own, kept beside its notebook and
 * never mirrored (every screen keeps its own layout; a page goes to another screen only as a template). Loading one is
 * a canvas change like any other: exact verbs through the screen's pad, one restore point, so undo takes it back whole.
 */

/** A saved layout as the shelf lists it: its name, the look it was saved in, when, whether home saved it by itself, and its pages. */
data class SavedLayout(val name: String, val look: String?, val time: Long, val auto: Boolean, val pages: Int, val text: String) {
    /** The home it holds, as the notebook store reads it back; null when it can't be read. */
    fun notebook(): Notebook? = NotebookStore.read(text)
}

/** The shelf's schema, in the [Versioned] wrapper: `{"layouts": [{"name", "look"?, "time", "auto"?, "notebook": "<the store's text>"}]}`. */
const val LAYOUTS_VERSION = 1

/** How many layouts a shelf keeps, home's own included: each is a whole home. */
const val LAYOUTS_MAX = 24

/** [typed] as a layout's name, its spaces as one; refused as [templateNameOf] refuses a template's. */
fun layoutNameOf(typed: String): String {
    val name = typed.trim().replace(Regex("\\s+"), " ")
    if (!TEMPLATE_NAME.matches(name) || ".." in name) refuse("\"$typed\" can't be a layout's name: letters, digits, spaces, dots, dashes, at most 40. Nothing was changed.")
    return name
}

/**
 * A screen's saved layouts, kept as JSON text by [read] and [write] (the Fold: a preference file of its own beside the
 * notebook's). [look] is the look in use, saved with each layout. Text that can't be read is an empty shelf, never an
 * error; one from a newer build is read and never written over (as the notebook store does), so nothing it holds is lost.
 */
class LayoutShelf(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val look: () -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The shelf's text when [cache] was read from it: the same text is never parsed twice. */
    private var cachedText: String? = null
    private var cache: List<SavedLayout> = emptyList()

    /** Every saved layout, newest first. */
    fun list(): List<SavedLayout> {
        val text = read()
        if (text != cachedText) {
            cache = parse(text).sortedByDescending { it.time }
            cachedText = text
        }
        return cache
    }

    /** The layout called [name], whatever its case. */
    fun get(name: String): SavedLayout? = list().firstOrNull { it.name.equals(name.trim().replace(Regex("\\s+"), " "), ignoreCase = true) }

    /**
     * [home] kept as [typed]. One already called that is replaced only when [replace] says so, and a full shelf takes a new one
     * only in place of the oldest that home saved by itself.
     */
    fun save(typed: String, home: Notebook, replace: Boolean = false): SavedLayout {
        val name = layoutNameOf(typed)
        val was = get(name)
        if (was != null && !replace) refuse("A layout is already called \"${was.name}\": say --replace to replace it. Nothing was changed.")
        return keep(name, home, auto = false)
    }

    /**
     * [home] kept as [typed] by home itself ("Before Manga" when the look changes to Manga): the one it kept by that name before
     * is replaced, never one he saved under that name himself.
     */
    fun autoSave(typed: String, home: Notebook): SavedLayout {
        val name = layoutNameOf(typed)
        get(name)?.takeIf { !it.auto }?.let { refuse("\"${it.name}\" is a layout he saved himself; home doesn't replace it. Nothing was changed.") }
        return keep(name, home, auto = true)
    }

    private fun keep(name: String, home: Notebook, auto: Boolean): SavedLayout {
        writable()
        val others = list().filter { !it.name.equals(name, ignoreCase = true) }
        // Full: the oldest that home saved by itself goes, never one he named (round-trip test: "full shelf").
        val room = if (others.size < LAYOUTS_MAX) others else {
            val oldestAuto = others.filter { it.auto }.minByOrNull { it.time }
                ?: refuse("The shelf holds $LAYOUTS_MAX layouts already: delete one first. Nothing was changed.")
            others - oldestAuto
        }
        val made = SavedLayout(name, look(), now(), auto, home.pages.size, NotebookStore.write(home))
        store(room + made)
        return made
    }

    /** The layout [from] called [to] (its case may change); refused when [to] is another's name. */
    fun rename(from: String, to: String): SavedLayout {
        val was = get(from) ?: refuse(missing(from))
        val name = layoutNameOf(to)
        val other = get(name)
        if (other != null && other.name != was.name) refuse("A layout is already called \"${other.name}\". Nothing was changed.")
        writable()
        val renamed = was.copy(name = name)
        store(list().map { if (it.name == was.name) renamed else it })
        return renamed
    }

    /** Whether the layout [name] was there to delete. */
    fun delete(name: String): Boolean {
        val was = get(name) ?: return false
        writable()
        store(list().filter { it.name != was.name })
        return true
    }

    /** The art ids any saved layout puts in a panel: the art store keeps their files (piece C's `sweep`). */
    fun referencedArt(): Set<String> = list().flatMap { artIn(it.text) }.toSet()

    /**
     * Home made the layout [name] again through [pad], as part of [request] from [actor]: one restore point, which undo takes
     * back whole. Says what it did and what it left (an app no longer on home, a page with his ink on it).
     */
    fun load(name: String, pad: CanvasPad, request: String, actor: String): String {
        val saved = get(name) ?: refuse(missing(name))
        val want = saved.notebook() ?: refuse("The layout \"${saved.name}\" can't be read. Nothing was changed.")
        val plan = loadPlan(pad.notebook(), want)
        if (plan.verbs.isEmpty() && plan.removals.isEmpty()) {
            return if (plan.left.isEmpty()) "home already is the layout \"${saved.name}\""
            else "nothing changed: home already has what of the layout \"${saved.name}\" can be laid here; left: " + plan.left.joinToString("; ")
        }
        if (plan.verbs.isNotEmpty()) pad.apply(plan.verbs, request, actor)?.let { refuse("It stopped partway: $it. What came before that stays, and undo takes it back.") }
        val left = plan.left.toMutableList()
        // One by one, so a page that stays (his ink is on it) never stops the rest; all in the same restore point.
        for (r in plan.removals) pad.apply(listOf(r), request, actor)?.let { left += "the page \"${pageName(plan, r.id)}\" stays: $it" }
        val look = saved.look?.let { " (saved in the look \"$it\")" }.orEmpty()
        return "laid out the layout \"${saved.name}\"$look" + if (left.isEmpty()) "" else "; left: " + left.joinToString("; ")
    }

    private fun pageName(plan: LoadPlan, id: String) = plan.names[id] ?: id

    private fun missing(name: String) =
        "No layout is called \"${name.trim()}\". Layouts: ${list().joinToString(", ") { it.name }.ifEmpty { "none yet" }}. Nothing was changed."

    /** A shelf written by a newer build holds what this one may not know: it is read, never written over. */
    private fun writable() {
        if (newer(read())) refuse("The saved layouts were written by a newer Soma; this one only reads them. Nothing was changed.")
    }

    private fun store(layouts: List<SavedLayout>) {
        val text = Versioned.write(LAYOUTS_VERSION, JSONObject().put("layouts", JSONArray(layouts.sortedBy { it.time }.map { l ->
            JSONObject().put("name", l.name).putOpt("look", l.look).put("time", l.time).put("pages", l.pages).put("notebook", l.text).apply { if (l.auto) put("auto", true) }
        })))
        write(text)
        cachedText = null
    }

    companion object {
        /** The layouts [text] holds; a layout that doesn't parse, or names one before it, is left out. */
        fun parse(text: String?): List<SavedLayout> = runCatching {
            val data = Versioned.read(text, LAYOUTS_VERSION) as? JSONObject ?: return emptyList()
            val seen = HashSet<String>()
            data.optJSONArray("layouts")?.mapObjects { o ->
                val name = o.getString("name")
                if (!TEMPLATE_NAME.matches(name) || !seen.add(name.lowercase())) null
                else SavedLayout(name, o.optString("look").ifEmpty { null }, o.getLong("time"), o.optBoolean("auto"), o.optInt("pages"), o.getString("notebook"))
            }.orEmpty()
        }.getOrDefault(emptyList())

        fun newer(text: String?): Boolean = runCatching {
            JSONObject(text ?: return false).let { it.has("v") && it.has("data") && it.getInt("v") > LAYOUTS_VERSION }
        }.getOrDefault(false)

        /** The art ids the notebook text [text] puts on its pages, read from its keys alone, whatever kinds this build reads. */
        fun artIn(text: String): Set<String> = runCatching {
            val data = Versioned.read(text, NotebookStore.VERSION) as? JSONObject ?: return emptySet()
            val pages = data.optJSONArray("pages") ?: return emptySet()
            (0 until pages.length()).flatMap { p ->
                val items = pages.optJSONObject(p)?.optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { i -> artIdOf(items.optJSONObject(i)?.optString("key").orEmpty()) }
            }.toSet()
        }.getOrDefault(emptySet())
    }
}

/**
 * What loading a layout comes to: the [verbs] to apply in order, the pages to take away after them ([removals], each on its
 * own so one that stays stops nothing), what can't be laid back in words ([left]), and page names by id for saying so.
 */
data class LoadPlan(val verbs: List<CanvasVerb>, val removals: List<CanvasVerb.PageRemove>, val left: List<String>, val names: Map<String, String> = emptyMap())

/** The id asked for a page that holds what is moved out of the way while the rest is laid (taken away again at the end). */
const val PARK_PAGE = "layout-moving"

/** The id asked for a page made for apps and folders the layout doesn't have and home has no room for. */
const val KEPT_PAGE = "layout-kept"

/**
 * The exact verbs that make [now] the layout [want]: its pages made or renamed, each thing put back in its cell on its page,
 * what it doesn't have taken off, and the pages it doesn't have taken away. Every verb is checked as it would apply, in
 * order: a thing whose cell is taken by another still to move waits, and two that would trade places go by way of a page
 * made for the moment and taken away again. Apps and folders are never taken off (a verb can't pin one back): those it
 * doesn't have stay where they are, or move to a free cell, and those it has that are no longer on home are said and left.
 */
fun loadPlan(now: Notebook, want: Notebook): LoadPlan {
    val verbs = ArrayList<CanvasVerb>()
    val left = ArrayList<String>()
    var n = now
    val names = HashMap<String, String>().apply { now.pages.forEach { put(it.id, it.name) } }
    fun take(v: CanvasVerb): Boolean {
        val r = applyVerb(n, v)
        if (r.applied) { n = r.notebook; verbs += v }
        return r.applied
    }
    fun items(of: Notebook) = of.pages.flatMap { p -> p.items.map { it.key to (p.id to it) } }.toMap()
    val had = items(now)

    // The pages, in the layout's order: one it has that home doesn't is made after the one before it.
    want.pages.forEachIndexed { i, p ->
        val was = n.pages.firstOrNull { it.id == p.id }
        if (was == null) take(CanvasVerb.PageMake(p.id, p.name, want.pages.getOrNull(i - 1)?.id ?: PAGE_START))
        else if (was.name != p.name) take(CanvasVerb.PageRename(p.id, p.name))
    }

    // Where each thing goes: its saved cell, when that is on this grid and clear of what came before it on its page.
    val target = LinkedHashMap<String, Pair<String, Cell>>()
    val taken = HashMap<String, MutableList<Cell>>()
    for (p in want.pages) for (item in p.items) {
        val cell = item.cell ?: continue
        val here = had[item.key]?.second
        when {
            here != null && here.kind != item.kind -> left += "${label(item)} (it is a ${here.kind.name.lowercase()} here now)"
            here == null && (item.kind == ItemKind.App || item.kind == ItemKind.Folder) -> left += "${label(item)} (no longer on home)"
            cell.x < 0 || cell.y < 0 || cell.right > n.columns || cell.bottom > n.rows -> left += "${label(item)} (its cells are off this grid)"
            taken[p.id].orEmpty().any { it.overlaps(cell) } -> left += "${label(item)} (its cells overlap another's)"
            else -> { target[item.key] = p.id to cell; taken.getOrPut(p.id) { ArrayList() } += cell }
        }
    }

    // What the layout doesn't have: taken off, but an app or a folder stays, in a free cell where its own is wanted. So
    // does a thing it has but can't lay back (said above): it stays on home, out of the way.
    val wantPages = want.pages.map { it.id }.toSet()
    val inLayout = want.pages.flatMap { p -> p.items.map { it.key } }.toSet()
    val kept = ArrayList<String>()
    for ((key, at) in had) {
        if (key in target) continue
        val (page, item) = at
        val cell = item.cell ?: continue
        if (key !in inLayout && item.kind != ItemKind.App && item.kind != ItemKind.Folder) { take(CanvasVerb.Remove(key)); continue }
        if (page in wantPages && taken[page].orEmpty().none { it.overlaps(cell) }) { taken.getOrPut(page) { ArrayList() } += cell; continue }
        val spot = (listOf(page).filter { it in wantPages } + want.pages.map { it.id }).distinct().firstNotNullOfOrNull { p ->
            firstFit(cell.w, cell.h, taken[p].orEmpty(), n.columns, n.rows)?.let { p to it }
        } ?: run {
            if (n.pages.none { it.id == KEPT_PAGE }) { take(CanvasVerb.PageMake(KEPT_PAGE, "Kept", want.pages.lastOrNull()?.id)); names[KEPT_PAGE] = "Kept" }
            firstFit(cell.w, cell.h, taken[KEPT_PAGE].orEmpty(), n.columns, n.rows)?.let { KEPT_PAGE to it }
        }
        if (spot == null) { left += "${label(item)} (no room for it)"; continue }
        target[key] = spot
        taken.getOrPut(spot.first) { ArrayList() } += spot.second
        if (key !in inLayout) kept += label(item)
    }
    if (kept.isNotEmpty()) left += "kept what the layout doesn't have: " + kept.joinToString(", ")

    // Each thing to its cell, as soon as the cell is free; when none can go, one in the way waits on a page of its own.
    val pending = target.filter { (key, t) -> items(n)[key]?.let { (p, i) -> p != t.first || i.cell != t.second } ?: true }.keys.toMutableList()
    fun verbFor(key: String): CanvasVerb {
        val (page, cell) = target.getValue(key)
        val here = items(n)[key]?.second
        return if (here == null) CanvasVerb.Place(key, want.pages.first { p -> p.items.any { it.key == key } }.items.first { it.key == key }.kind, page, cell)
        else CanvasVerb.Move(key, page, cell)
    }
    while (pending.isNotEmpty()) {
        val went = pending.filter { take(verbFor(it)) }
        pending -= went.toSet()
        if (went.isNotEmpty() || pending.isEmpty()) continue
        // Stuck: something still to move sits where another goes. It waits on the page made for the moment.
        val now2 = items(n)
        val blocker = pending.firstNotNullOfOrNull { key ->
            val (page, cell) = target.getValue(key)
            pending.firstOrNull { other -> other != key && now2[other]?.let { (p, i) -> p == page && i.cell?.overlaps(cell) == true } == true }
        }
        val moved = blocker?.let { b ->
            if (n.pages.none { it.id == PARK_PAGE }) take(CanvasVerb.PageMake(PARK_PAGE, "Moving", null))
            val c = now2.getValue(b).second.cell!!
            val spot = firstFit(c.w, c.h, n.pages.first { it.id == PARK_PAGE }.items.mapNotNull { it.cell }, n.columns, n.rows)
            spot != null && take(CanvasVerb.Move(b, PARK_PAGE, spot))
        } ?: false
        if (!moved) {
            pending.forEach { key -> left += "${label(had[key]?.second ?: NotebookItem(key, ItemKind.Widget, null))} (its cells stay taken)" }
            break
        }
    }
    if (n.pages.any { it.id == PARK_PAGE }) {
        if (n.pages.first { it.id == PARK_PAGE }.items.isEmpty()) take(CanvasVerb.PageRemove(PARK_PAGE)) else names[PARK_PAGE] = "Moving"
    }

    // The pages the layout doesn't have, emptied above; one that isn't empty stays, and is said.
    val removals = n.pages.filter { it.id !in wantPages && it.id != PARK_PAGE && it.id != KEPT_PAGE }.mapNotNull { p ->
        if (p.items.isEmpty()) CanvasVerb.PageRemove(p.id) else { left += "the page \"${p.name}\" stays: things are still on it"; null }
    }
    // A verb can't change the order of pages already there: said, never forced (a page's ink goes by its id).
    val order = n.pages.map { it.id }.filter { it in wantPages }
    if (order != want.pages.map { it.id }.filter { it in order }) left += "the pages stay in the order they are in now"
    // A page's panel layout needs piece A's page.panels verb to be laid back: until then it is said, never forced.
    for (p in want.pages) {
        val here = n.pages.firstOrNull { it.id == p.id } ?: continue
        if (here.panels != p.panels) left += "the page \"${p.name}\" stays cut as it is now (saved as ${p.panels?.let { PanelLayouts.name(it) } ?: "the free grid"})"
    }
    // Nor the dock, which is no page: it is his to change.
    if (now.dock.map { it.key } != want.dock.map { it.key }) left += "the dock stays as it is now"
    return LoadPlan(verbs, removals, left, names)
}

/** A thing as a layout says it: an app by its label, a folder by its first apps, a module by its title, anything else by its key. */
private fun label(item: NotebookItem): String = when (item.kind) {
    ItemKind.App -> item.app?.label ?: item.key
    ItemKind.Folder -> "the folder of " + item.apps.take(2).joinToString(" and ") { it.label }.ifEmpty { "apps" }
    ItemKind.Module -> moduleTitle(item.key)
    else -> item.key
}
