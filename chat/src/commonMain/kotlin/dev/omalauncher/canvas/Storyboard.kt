package dev.omalauncher.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.OVERFLOW_PANELS
import dev.omalauncher.widgets.layout.Panel
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.artIdOf
import dev.omalauncher.widgets.layout.artKey
import dev.omalauncher.widgets.layout.emptyPanels
import dev.omalauncher.widgets.layout.firstFit
import dev.omalauncher.widgets.layout.fitIntoPanels
import dev.omalauncher.widgets.layout.inPanels
import dev.omalauncher.widgets.layout.setPagePanels

/*
 * The Storyboard (ネーム; Manga look round 4, Boss 2026-10-19): one place to set up every page of home for the Manga look, opened
 * from the overview's upper-right corner. Pick a page, try a panel layout on it before using it, move widgets and art between
 * panels and pages, take them off to the tray, and save or load whole layouts. Every change is home's own, made at once through
 * home's canvas pad with the same verbs agents use (page.panels, place, move, remove), each one restore point; trying a layout
 * changes nothing until "Use this". This is its state and what each tap or drop does; the drawing is the launcher's.
 */

/** Which things the tray shows. */
enum class TrayTab { Widgets, Art }

/** Something the tray offers: its [key] and [kind], what it is called, and the page it is on now (null: only in the tray). */
data class TrayChip(val key: String, val kind: ItemKind, val title: String, val page: String?)

/**
 * A panel layout tried on a page and not made: the page as it would be ([shown], what is in each panel), and what would have
 * to go because no panel is left for it, in words ([left]); empty when everything fits.
 */
data class LayoutTrial(val page: String, val layout: String, val shown: NotebookPage, val columns: Int, val rows: Int, val left: List<String>)

/** What the last tap came to: [text], and whether it went as asked ([ok]). */
data class StoryboardSaid(val text: String, val ok: Boolean = true)

/**
 * The Storyboard's state over home's canvas [pad] (null while home isn't open) and the saved layouts on [shelf] (null where there
 * are none). [artIds] are the pictures kept to hang (the art store); [titleOf] names a widget or folder as home shows it, where it
 * knows better than its key. [newPageLayout] is how a page added here is cut (the mockup's: a 4-koma strip).
 */
class Storyboard(
    private val pad: () -> CanvasPad?,
    private val shelf: LayoutShelf?,
    private val artIds: () -> Collection<String> = { emptyList() },
    private val titleOf: (NotebookItem) -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val newPageLayout: String = OVERFLOW_PANELS,
) {
    /** The page being set up (its id). */
    var page by mutableStateOf<String?>(null)
        private set

    /** The layout tried on [page], not made yet. */
    var trial by mutableStateOf<LayoutTrial?>(null)
        private set

    /** The tray's chip picked to go into the next panel tapped. */
    var armed by mutableStateOf<String?>(null)
        private set

    var tab by mutableStateOf(TrayTab.Widgets)

    /** The saved layout tapped once: the next tap on it loads it. */
    var confirm by mutableStateOf<String?>(null)
        private set

    var said by mutableStateOf<StoryboardSaid?>(null)
        private set

    /**
     * The load an Undo takes back (its request), offered until home is changed here again: a hint or a refusal meanwhile
     * keeps it (the mockup's undo outlives its toast).
     */
    var undoable by mutableStateOf<String?>(null)
        private set

    /** Counts the changes made here, so what is drawn from home is read again. */
    var changes by mutableIntStateOf(0)
        private set

    /** The pictures in the store when the tray last read them, by the count of changes then. */
    private var artSeen: Pair<Int, List<String>>? = null

    /** Home as the Storyboard sees it; null while home isn't open. */
    fun notebook(): Notebook? = pad()?.notebook()

    /** Opened on [current], the page home was showing (else the first). */
    fun open(current: String?) {
        val n = notebook()
        page = n?.pages?.firstOrNull { it.id == current }?.id ?: n?.pages?.firstOrNull()?.id
        trial = null
        armed = null
        confirm = null
        said = StoryboardSaid("Pick a page, try layouts on it, and drag widgets or art into its panels.")
        changes++
    }

    /** Whether something may be lifted to move now: not while a layout is tried (said so). */
    fun canMove(): Boolean {
        if (trial == null) return true
        say(USE_OR_BACK, false)
        return false
    }

    /** The page [id] to set up. A layout being tried on another page is put away. */
    fun pick(id: String) {
        if (notebook()?.pages?.any { it.id == id } != true) return
        page = id
        trial = null
        confirm = null
    }

    fun dismiss() {
        said = null
    }

    /** A new page after the last, cut as [newPageLayout], to set up next. */
    fun addPage() = act {
        val pad = it
        val n = pad.notebook()
        val id = pad.rules.pageId(n)
        val name = freePageName(n)
        val layout = newPageLayout.takeIf { l -> PanelLayouts.fits(l, n.columns, n.rows) }
        val verbs = listOfNotNull(CanvasVerb.PageMake(id, name), layout?.let { l -> CanvasVerb.PagePanels(id, l) })
        pad.apply(verbs, request(), ACTOR)?.let { why -> refuse("No new page: $why.") }
        val made = pad.notebook().pages.let { pages -> pages.firstOrNull { p -> p.id == id } ?: pages.last() }
        page = made.id
        trial = null
        "Added \"${made.name}\"."
    }

    /**
     * [layout] tried on the page: what it would come to is shown and nothing changes. Tapping the page's own layout, when the
     * page is in it, puts the trial away.
     */
    fun tryLayout(layout: String) {
        armed = null
        confirm = null
        val n = notebook() ?: return say("Home is still loading. Try again in a moment.", false)
        val p = n.pages.firstOrNull { it.id == page } ?: return
        if (layout == p.panels && inPanels(n, p)) {
            trial = null
            return
        }
        if (!PanelLayouts.fits(layout, n.columns, n.rows)) return say("${PanelLayouts.name(layout)} needs more rows or columns than this screen has.", false)
        val pad = pad() ?: return
        val fit = fitIntoPanels(n, p.id, layout, newPage = { now -> pad.rules.pageId(now) to freePageName(now) })
        val shown = fit.notebook.pages.first { it.id == p.id }
        val went = fit.moved.groupBy({ it.second }, { it.first }).map { (to, keys) ->
            keys.joinToString(" and ") { k -> title(p.items.first { it.key == k }) } + " would go to " +
                if (to in fit.made) "a new page" else "\"" + (fit.notebook.pages.firstOrNull { it.id == to }?.name ?: to) + "\""
        } + fit.removed.map { "${title(p.items.first { i -> i.key == it })} would go back to the tray" }
        trial = LayoutTrial(p.id, layout, shown, n.columns, n.rows, went)
        said = null
    }

    /** The layout tried, made: the page.panels verb, its things refit, one restore point. */
    fun useTrial() {
        val t = trial ?: return
        trial = null
        act { pad ->
            val before = pad.notebook()
            val r = setPagePanels(pad, t.page, t.layout, request(), ACTOR)
            if (!r.optBoolean("ok")) refuse(r.optString("error"))
            // Said by what the trial named (a widget by its name, not its file), when home was as it was tried.
            val name = pad.notebook().pages.firstOrNull { it.id == t.page }?.name ?: t.page
            val went = t.left.map { it.replace(" would go ", " went ") }
            val asTried = runCatching { fitIntoPanels(before, t.page, t.layout, newPage = { now -> pad.rules.pageId(now) to freePageName(now) }).notebook.pages.first { it.id == t.page } == t.shown }.getOrDefault(false)
            if (!asTried) {
                r.optString("did").replaceFirstChar { it.uppercase() } + "."
            } else {
                "\"$name\" is now ${PanelLayouts.name(t.layout)}." + if (went.isEmpty()) "" else " No panel left there, so " + went.joinToString("; ") + "."
            }
        }
    }

    /** The trial put away: the page stays as it is. */
    fun back() {
        if (trial == null) return
        trial = null
        say("Kept the page as it was.")
    }

    /** [key] picked from the tray to go into the next panel tapped (picked again: put down). */
    fun arm(key: String) {
        if (trial != null) return say(USE_OR_BACK, false)
        armed = if (armed == key) null else key
        confirm = null
        val n = notebook() ?: return
        if (armed != null) say("Tap a panel on \"${n.pages.firstOrNull { it.id == page }?.name}\" to put ${chipTitle(n, key)} there.")
    }

    /** A tap on the [panel]th panel of the page: the picked chip goes there, or an empty panel says how to fill it. */
    fun tapPanel(panel: Int) {
        if (trial != null) return say(USE_OR_BACK, false)
        val key = armed
        val p = page ?: return
        if (key != null) {
            armed = null
            place(key, p, panel)
            return
        }
        val n = notebook() ?: return
        if (occupant(n, p, panel) == null) say("Drag a widget or a picture here, or tap one in the tray and then this panel.")
    }

    /**
     * [key] put into the [panel]th panel of [pageId]. What is there trades places with it when [key] was on a page (across pages
     * too); when [key] came from the tray, what was there goes back to the tray, or, for what can't leave home (an app, a folder,
     * a widget's file), to the first empty panel. One restore point. Whether it went.
     */
    fun place(key: String, pageId: String, panel: Int): Boolean {
        if (trial != null) { say(USE_OR_BACK, false); return false }
        return act { pad -> placeOn(pad, key, pageId, panel) } != null
    }

    /** [key] sent to the first empty panel of [pageId]: a panel dropped on a page in the strip. */
    fun send(key: String, pageId: String): Boolean {
        if (trial != null) { say(USE_OR_BACK, false); return false }
        val n = notebook() ?: return false
        val target = n.pages.firstOrNull { it.id == pageId } ?: return false
        if (panelsOf(n, target) == null) { say("\"${target.name}\" isn't cut into panels: pick a layout for it first.", false); return false }
        val empty = emptyPanels(n, target).firstOrNull()
            ?: run { say("\"${target.name}\" has no empty panel. Try another layout on it, or drop on a panel instead.", false); return false }
        return place(key, pageId, empty)
    }

    /** [key] taken off its page, back to the tray: a widget module or a picture. Apps, folders and widget files stay on home. */
    fun takeOff(key: String): Boolean {
        if (trial != null) { say(USE_OR_BACK, false); return false }
        return act { pad ->
            val n = pad.notebook()
            val item = n.pages.firstNotNullOfOrNull { p -> p.items.firstOrNull { it.key == key } } ?: refuse("${chipTitle(n, key).replaceFirstChar { it.uppercase() }} is in the tray already.")
            if (!trayable(item)) refuse(staysWhy(item))
            pad.apply(listOf(CanvasVerb.Remove(key)), request(), ACTOR)?.let { refuse("$it.") }
            "${title(item).replaceFirstChar { it.uppercase() }} is back in the tray. The empty panel keeps its lines."
        } != null
    }

    /** Home kept on the shelf as the next free "Layout N". */
    fun save() = act(altersHome = false) { pad ->
        val shelf = shelf ?: refuse("Saved layouts are kept on the Fold.")
        "Saved as \"" + shelf.save(nextLayoutName(shelf), pad.notebook()).name + "\"."
    }

    /** A tap on the saved layout [name]: the first asks, the second loads it (one restore point), with Undo offered. */
    fun tapSaved(name: String) {
        if (confirm != name) {
            confirm = name
            return
        }
        confirm = null
        trial = null
        armed = null
        val request = request()
        act(undo = request) { pad ->
            val shelf = shelf ?: refuse("Saved layouts are kept on the Fold.")
            // Said to him, not to an agent.
            val did = shelf.load(name, pad, request, ACTOR).replace("he removes it himself in the overview", "you can delete it in the overview")
            val n = pad.notebook()
            if (n.pages.none { it.id == page }) page = n.pages.firstOrNull()?.id
            did.replaceFirstChar { it.uppercase() } + "."
        }
    }

    /** The load last said undone: what it changed put back, as far as it still applies. */
    fun undo() {
        val request = undoable ?: return
        act { pad ->
            val point = pad.restorePoints().firstOrNull { it.request == request } ?: refuse("That load has been undone already.")
            val done = undoOn(pad.notebook(), point.inverses)
            if (done.applied.isEmpty()) refuse("Nothing of it can be undone now.")
            pad.undone(point, done.applied, ACTOR)?.let { refuse("The undo stopped partway: $it.") }
            val n = pad.notebook()
            if (n.pages.none { it.id == page }) page = n.pages.firstOrNull()?.id
            "Put back the layout from before." + if (done.left.isEmpty()) "" else " Left: " + done.left.joinToString("; ") + "."
        }
    }

    /** The saved layouts, oldest first: one saved here joins the end, so nothing moves under his finger. */
    fun saved(): List<SavedLayout> = shelf?.list().orEmpty().sortedBy { it.time }

    /** The tray's chips on [tab]: every module, widget, folder and app on home and every module to add; or every picture. */
    fun chips(tab: TrayTab): List<TrayChip> {
        val n = notebook() ?: return emptyList()
        val on = n.pages.flatMap { p -> p.items.map { it to p.id } }
        return when (tab) {
            TrayTab.Art -> {
                val hung = on.filter { it.first.kind == ItemKind.Art }.associate { it.first.key to it.second }
                // The store is read again only after a change here: the tray is drawn again on every move of a drag.
                val kept = artSeen?.takeIf { it.first == changes }?.second ?: artIds().toList().also { artSeen = changes to it }
                (kept.map(::artKey) + hung.keys).distinct().sorted().map { TrayChip(it, ItemKind.Art, "a picture", hung[it]) }
            }
            TrayTab.Widgets -> {
                val placed = on.filter { it.first.kind != ItemKind.Art }
                val keys = placed.map { it.first.key }.toSet()
                // The modules in the store's order, on home or not; then the rest of what is on home by name. The Layouts module
                // is only listed where someone placed it: this is where layouts are now.
                val modules = ModuleKind.entries.filter { !it.perProject && !it.chatScreen }.mapNotNull { moduleKey(it) }
                    .filter { it in keys || moduleRef(it)?.kind != ModuleKind.Layouts }
                val listed = modules.map { k -> placed.firstOrNull { it.first.key == k }?.let { (item, p) -> TrayChip(k, item.kind, title(item), p) } ?: TrayChip(k, ItemKind.Module, moduleTitle(k), null) }
                listed + placed.filter { it.first.key !in modules }.map { (item, p) -> TrayChip(item.key, item.kind, title(item), p) }.sortedBy { it.title.lowercase() }
            }
        }
    }

    /** The panels of the page [p], or null when it is the free grid (or cut in a layout this grid can't take). */
    fun panelsOf(n: Notebook, p: NotebookPage): List<Panel>? = p.panels?.let { PanelLayouts.panels(it, n.columns, n.rows) }?.takeIf { it.isNotEmpty() }

    /** What is in the [panel]th panel of the page [pageId]: what is exactly in its cell, else what lies on it. */
    fun occupant(n: Notebook, pageId: String, panel: Int): NotebookItem? {
        val p = n.pages.firstOrNull { it.id == pageId } ?: return null
        return occupants(n, p).getOrNull(panel)
    }

    /** Each panel's occupant on [p], in the layout's order: one thing to a panel. */
    fun occupants(n: Notebook, p: NotebookPage): List<NotebookItem?> {
        val panels = panelsOf(n, p) ?: return emptyList()
        val used = HashSet<String>()
        val out = MutableList<NotebookItem?>(panels.size) { null }
        for ((i, panel) in panels.withIndex()) p.items.firstOrNull { it.key !in used && it.cell == panel.cell }?.let { out[i] = it; used += it.key }
        for ((i, panel) in panels.withIndex()) if (out[i] == null) p.items.firstOrNull { it.key !in used && it.cell?.overlaps(panel.cell) == true }?.let { out[i] = it; used += it.key }
        return out
    }

    /** What [item] is called here: an app by its label, a module by its title, a picture as one. */
    fun title(item: NotebookItem): String = titleOf(item) ?: when (item.kind) {
        ItemKind.App -> item.app?.label ?: "an app"
        ItemKind.Folder -> "the cast of " + item.apps.take(2).joinToString(" and ") { it.label }.ifEmpty { "apps" }
        ItemKind.Module -> moduleTitle(item.key)
        ItemKind.Art -> "a picture"
        ItemKind.Thread -> "a thread's tile"
        else -> item.key.removeSuffix(".owl")
    }

    private fun chipTitle(n: Notebook, key: String): String =
        n.pages.firstNotNullOfOrNull { p -> p.items.firstOrNull { it.key == key } }?.let(::title)
            ?: if (artIdOf(key) != null) "the picture" else moduleTitle(key)

    private fun placeOn(pad: CanvasPad, key: String, pageId: String, panel: Int): String {
        val request = request()
        val n = pad.notebook()
        val target = n.pages.firstOrNull { it.id == pageId } ?: refuse("That page is gone.")
        val panels = panelsOf(n, target) ?: refuse("\"${target.name}\" isn't cut into panels: pick a layout for it first.")
        if (panel !in panels.indices) refuse("That panel is gone.")
        val cell = panels[panel].cell
        val occ = occupants(n, target)[panel]
        val from = n.pages.firstNotNullOfOrNull { p -> p.items.firstOrNull { it.key == key }?.let { p to it } }
        if (occ?.key == key) return "${title(occ).replaceFirstChar { it.uppercase() }} is there already."
        val verbs = ArrayList<CanvasVerb>()
        val said: String
        if (from != null) {
            val (source, item) = from
            // Where it was: its whole panel on a panel page (an app shows as one cell of it), else its own cells.
            val was = panelsOf(n, source)?.let { sp -> occupants(n, source).indexOfFirst { it?.key == key }.takeIf { it >= 0 }?.let { sp[it].cell } } ?: item.cell
                ?: refuse("${title(item).replaceFirstChar { it.uppercase() }} waits for its place on home.")
            when {
                occ == null && source.id == pageId -> verbs += CanvasVerb.PagePanels(pageId, target.panels, mapOf(key to cell))
                occ == null -> verbs += CanvasVerb.Move(key, pageId, cell)
                // A swap on one page: both at once, so it never passes through a cell two hold.
                source.id == pageId -> verbs += CanvasVerb.PagePanels(pageId, target.panels, mapOf(key to cell, occ.key to was))
                // Across pages: one waits somewhere while the other takes its place.
                else -> verbs += tradeAcross(n, key, source, was, occ.key, target, cell)
            }
            said = when {
                occ == null && source.id == pageId -> "Moved ${title(item)}."
                occ == null -> "${title(item).replaceFirstChar { it.uppercase() }} is on \"${target.name}\" now."
                else -> "Swapped ${title(item)} and ${title(occ)}."
            }
        } else {
            val kind = when {
                artIdOf(key) != null -> ItemKind.Art
                moduleRef(key) != null -> ItemKind.Module
                else -> refuse("That can't be put in a panel from here.")
            }
            val name = if (kind == ItemKind.Art) "the picture" else moduleTitle(key)
            var out = ""
            if (occ != null) {
                if (trayable(occ)) {
                    verbs += CanvasVerb.Remove(occ.key)
                    out = "; ${title(occ)} went back to the tray"
                } else {
                    // What can't leave home moves to the first empty panel: this page's, then the pages after it, round to the start.
                    val at = n.pages.indexOf(target)
                    val dest = (n.pages.drop(at) + n.pages.take(at)).firstNotNullOfOrNull { p ->
                        val ps = panelsOf(n, p) ?: return@firstNotNullOfOrNull null
                        emptyPanels(n, p).firstOrNull { !(p.id == pageId && it == panel) }?.let { p to ps[it].cell }
                    } ?: refuse("Every panel is full and ${title(occ)} can't go back to the tray: ${staysWhy(occ).replaceFirstChar { it.lowercase() }}")
                    verbs += CanvasVerb.Move(occ.key, dest.first.id, dest.second)
                    out = "; ${title(occ)} moved to " + if (dest.first.id == pageId) "an empty panel" else "\"${dest.first.name}\""
                }
            }
            verbs += CanvasVerb.Place(key, kind, pageId, cell)
            said = "${name.replaceFirstChar { it.uppercase() }} is on \"${target.name}\"$out."
        }
        val why = pad.apply(verbs, request, ACTOR)
        if (why != null) refuse(if (verbs.size == 1) "$why." else "It stopped partway: $why.")
        return said
    }

    /**
     * The verbs that trade [key] (at [was] on [from]) and [occ] (at [cell] on [to]) across pages, as one change and never by
     * making a page (the Fold won't take a page away until its notes are read: a page made to wait on would stay). [occ] waits
     * in a free cell anywhere, clear of the two cells traded (an app shows as one cell of its panel, so the rest looks free);
     * with every cell taken, [key] steps into one cell of its own place and [occ] waits beside it; else the other way round.
     */
    private fun tradeAcross(n: Notebook, key: String, from: NotebookPage, was: Cell, occ: String, to: NotebookPage, cell: Cell): List<CanvasVerb> {
        val kept = mapOf(to.id to cell, from.id to was)
        for (p in n.pages) firstFit(1, 1, p.items.mapNotNull { it.cell } + listOfNotNull(kept[p.id]), n.columns, n.rows)?.let { park ->
            return listOf(CanvasVerb.Move(occ, p.id, park), CanvasVerb.Move(key, to.id, cell), CanvasVerb.Move(occ, from.id, was))
        }
        fun corner(c: Cell) = Cell(c.x, c.y, 1, 1)
        fun beside(c: Cell) = if (c.w >= 2) Cell(c.x + 1, c.y, 1, 1) else Cell(c.x, c.y + 1, 1, 1)
        if (was.w * was.h >= 2) {
            return listOf(
                CanvasVerb.PagePanels(from.id, from.panels, mapOf(key to corner(was))),
                CanvasVerb.Move(occ, from.id, beside(was)),
                CanvasVerb.Move(key, to.id, cell),
                CanvasVerb.Move(occ, from.id, was),
            )
        }
        if (cell.w * cell.h >= 2) {
            return listOf(
                CanvasVerb.PagePanels(to.id, to.panels, mapOf(occ to corner(cell))),
                CanvasVerb.Move(key, to.id, beside(cell)),
                CanvasVerb.Move(occ, from.id, was),
                CanvasVerb.Move(key, to.id, cell),
            )
        }
        refuse("There's no room to trade those two: drop one on an empty panel first.")
    }

    /** Whether [item] can be taken off home to the tray and put back from it: a module, or a picture. */
    private fun trayable(item: NotebookItem) = item.kind == ItemKind.Module || item.kind == ItemKind.Art

    private fun staysWhy(item: NotebookItem): String = "${title(item).replaceFirstChar { it.uppercase() }} stays on home: " + when (item.kind) {
        ItemKind.App, ItemKind.Folder -> "drop it on another panel, or unpin it from home."
        ItemKind.Widget -> "it is a widget's file; drop it on another panel, or remove it from home's menu."
        else -> "drop it on another panel instead."
    }

    /** "Page N" for the first N no page is called, from the count of pages on. */
    private fun freePageName(n: Notebook): String {
        var number = n.pages.size + 1
        while (n.pages.any { it.name == "Page $number" }) number++
        return "Page $number"
    }

    private fun request() = "storyboard-" + now().toString(36)

    private fun say(text: String, ok: Boolean = true) {
        said = StoryboardSaid(text, ok)
    }

    /**
     * [work] done on home's pad, what it says said; what refuses it said as a refusal. What it said, or null when it didn't go.
     * Once it went, Undo is offered for [undo] (a load), or no longer offered when it alters home otherwise ([altersHome]).
     */
    private fun act(undo: String? = null, altersHome: Boolean = true, work: (CanvasPad) -> String): String? {
        val pad = pad() ?: run { say("Home is still loading. Try again in a moment.", false); return null }
        return try {
            work(pad).also {
                said = StoryboardSaid(it, true)
                if (undo != null) undoable = undo else if (altersHome) undoable = null
            }
        } catch (e: AskRefused) {
            say(e.message.orEmpty(), false)
            null
        } finally {
            changes++
        }
    }

    companion object {
        /** Who the Storyboard's changes are by: his own hand, as the Layouts module's. */
        const val ACTOR = "hand"

        const val USE_OR_BACK = "Use this layout or go back before moving things."
    }
}
