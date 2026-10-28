package dev.omalauncher.widgets.layout

import dev.omalauncher.upgrade.mapObjects
import dev.omalauncher.widgets.Cell
import org.json.JSONArray
import org.json.JSONObject

/*
 * Every change to a canvas page, as a verb (Soma Canvas, cockpit/canvas/PRD.md §5 and §8). A screen's layout is its
 * notebook; a verb is one change to it, with where it came from and its place in that screen's order. The log exists for
 * order and undo only: nothing reads it to learn anything (Boss, 2026-10-16), and placement never looks at it.
 *
 * In this slice nothing writes verbs yet: the Fold keeps its own document writer. What is here is the vocabulary, its
 * JSON, the log's order, and how an exact verb changes a notebook, every one a plain function, unit tested. Verbs that
 * say what an agent means ("Needs you, card, top left") rather than exact cells are slice 6's, through the same log.
 */

/** The verbs' schema. A verb from a newer schema, or of a kind this build doesn't know, is kept and never applied. */
const val VERB_SCHEMA = 1

/** In a page.make's `after`: the new page goes first, before every other (an undone removal of the first page). */
const val PAGE_START = "^start"

/** Who a verb is from: the [device] it was made on and the [actor] there ("hand", "launcher", "agent:<thread>", "grok"). */
data class VerbOrigin(val device: String, val actor: String)

/** One change to a canvas page. */
sealed interface CanvasVerb {
    /** A new page called [name], with the id [id], after the page [after] (null: at the end). */
    data class PageMake(val id: String, val name: String, val after: String? = null) : CanvasVerb

    data class PageRename(val id: String, val name: String) : CanvasVerb

    /** The page [id] moved to just after the page [after] ([PAGE_START]: first; null: last), with everything on it (desktop slice 5c: a drag in the overview). */
    data class PageMove(val id: String, val after: String? = null) : CanvasVerb

    /** The page [id] goes, with everything on it. */
    data class PageRemove(val id: String) : CanvasVerb

    /** The item [key] of [kind] put on the page [page] at exactly [cell]. One already on the canvas is moved there instead. */
    data class Place(val key: String, val kind: ItemKind, val page: String, val cell: Cell) : CanvasVerb

    /** The item [key] moved to [cell] on the page [page] (its own page when it doesn't change). Size included: a move may resize. */
    data class Move(val key: String, val page: String, val cell: Cell) : CanvasVerb

    data class Remove(val key: String) : CanvasVerb

    /**
     * The page [id] given the panel layout [panels] (null: the free grid), and the items [cells] names, all on that page,
     * moved to those cells at once: a refit or a swap of panels never passes through cells two items hold (PanelVerbs.kt).
     */
    data class PagePanels(val id: String, val panels: String?, val cells: Map<String, Cell> = emptyMap()) : CanvasVerb

    /**
     * A verb this build can't read or apply: kept as it came ([raw], its JSON text), so a newer phone's or service's change
     * is never lost or misread, and two logs holding it compare equal.
     */
    data class Unknown(val raw: String) : CanvasVerb {
        constructor(json: JSONObject) : this(json.toString())
    }
}

/** A verb in a screen's log: its place [version] in that screen's order, the [request] it belongs to (undo reverts a request whole), and its [origin]. */
data class LoggedVerb(val version: Long, val request: String, val origin: VerbOrigin, val verb: CanvasVerb, val schema: Int = VERB_SCHEMA)

/** What applying a verb did: the notebook after it, and whether it applied. One that didn't leaves the notebook as it was. */
data class Applied(val notebook: Notebook, val applied: Boolean, val why: String = "")

/**
 * [verb] applied to [notebook], exactly. A verb whose page or item is gone, whose cell falls outside the grid or onto
 * another item, or that this build can't read, doesn't apply and says why; nothing is guessed. (Re-resolving a late verb
 * whose spot was taken in the meantime is slice 8's; picking a spot from intent is slice 6's.)
 */
fun applyVerb(
    notebook: Notebook,
    verb: CanvasVerb,
    /**
     * A copy of a screen kept elsewhere (the service's copy of the Fold's home, slice 8b): an app or a folder there is only
     * the cells it takes, so it may be placed with no entry. Never on the screen itself, where it would be an app with
     * nothing to open.
     */
    bare: Boolean = false,
): Applied {
    fun no(why: String) = Applied(notebook, false, why)
    fun page(id: String) = notebook.pages.firstOrNull { it.id == id }
    fun fits(cell: Cell, page: NotebookPage, except: String?): Boolean =
        cell.x >= 0 && cell.y >= 0 && cell.w >= 1 && cell.h >= 1 && cell.right <= notebook.columns && cell.bottom <= notebook.rows &&
            page.items.none { it.key != except && it.cell?.overlaps(cell) == true }
    return when (verb) {
        is CanvasVerb.PageMake -> {
            if (page(verb.id) != null) return no("a page with that id is already there")
            val at = if (verb.after == PAGE_START) 0 else verb.after?.let { after -> notebook.pages.indexOfFirst { it.id == after }.takeIf { it >= 0 }?.plus(1) ?: return no("the page it goes after is gone") } ?: notebook.pages.size
            Applied(notebook.copy(pages = notebook.pages.toMutableList().apply { add(at, NotebookPage(verb.id, verb.name)) }), true)
        }
        is CanvasVerb.PageRename -> page(verb.id)?.let { Applied(notebook.copy(pages = notebook.pages.map { p -> if (p.id == verb.id) p.copy(name = verb.name) else p }), true) } ?: no("the page is gone")
        is CanvasVerb.PageRemove -> if (page(verb.id) == null) no("the page is gone") else Applied(notebook.copy(pages = notebook.pages.filter { it.id != verb.id }), true)
        is CanvasVerb.PageMove -> {
            val moved = page(verb.id) ?: return no("the page is gone")
            if (verb.after == verb.id) return no("a page can't go after itself")
            val rest = notebook.pages.filter { it.id != verb.id }
            val at = if (verb.after == PAGE_START) 0 else verb.after?.let { after -> rest.indexOfFirst { it.id == after }.takeIf { it >= 0 }?.plus(1) ?: return no("the page it goes after is gone") } ?: rest.size
            Applied(notebook.copy(pages = rest.toMutableList().apply { add(at, moved) }), true)
        }
        is CanvasVerb.Place -> {
            val to = page(verb.page) ?: return no("the page is gone")
            if (!fits(verb.cell, to, verb.key)) return no("that spot is taken or off the grid")
            val was = notebook.pages.flatMap { it.items }.firstOrNull { it.key == verb.key }
            // An app or a folder carries its entries, which a verb doesn't: only one already on the canvas can be placed.
            if (!bare && was == null && (verb.kind == ItemKind.App || verb.kind == ItemKind.Folder)) return no("an app or folder needs its entry; only one already here can be placed")
            if (was != null && was.kind != verb.kind) return no("that key is already a ${was.kind.name.lowercase()} here")
            val item = was?.copy(cell = verb.cell) ?: NotebookItem(verb.key, verb.kind, verb.cell)
            Applied(notebook.copy(pages = notebook.pages.map { p -> if (p.id == verb.page) p.copy(items = p.items.filter { it.key != verb.key } + item) else p.copy(items = p.items.filter { it.key != verb.key }) }), true)
        }
        is CanvasVerb.Move -> {
            val from = notebook.pages.firstOrNull { p -> p.items.any { it.key == verb.key } } ?: return no("the item is gone")
            val to = page(verb.page) ?: return no("the page is gone")
            if (!fits(verb.cell, to, verb.key)) return no("that spot is taken or off the grid")
            val item = from.items.first { it.key == verb.key }.copy(cell = verb.cell)
            Applied(notebook.copy(pages = notebook.pages.map { p ->
                when (p.id) {
                    to.id -> p.copy(items = p.items.filter { it.key != verb.key } + item)
                    from.id -> p.copy(items = p.items.filter { it.key != verb.key })
                    else -> p
                }
            }), true)
        }
        is CanvasVerb.Remove -> if (notebook.pages.none { p -> p.items.any { it.key == verb.key } }) no("the item is gone") else Applied(notebook.copy(pages = notebook.pages.map { p -> p.copy(items = p.items.filter { it.key != verb.key }) }), true)
        is CanvasVerb.PagePanels -> applyPagePanels(notebook, verb)
        is CanvasVerb.Unknown -> no("a newer build's verb: kept, not applied")
    }
}

/** A screen's log, oldest first: [screen] names whose layout it is, and the versions only ever go up. */
data class VerbLog(val screen: String, val entries: List<LoggedVerb> = emptyList()) {
    /** The newest version: versions only go up, so this is also the one the next verb follows. */
    val version: Long get() = entries.maxOfOrNull { it.version } ?: 0L

    /** [verb] appended after the newest, as part of [request] from [origin]. */
    fun append(request: String, origin: VerbOrigin, verb: CanvasVerb): VerbLog = copy(entries = entries + LoggedVerb(version + 1, request, origin, verb))

    /** The verbs of [request], in order: what undo reverts. */
    fun request(request: String): List<LoggedVerb> = entries.filter { it.request == request }
}

object VerbJson {
    fun cell(c: Cell): JSONObject = JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h)

    private fun cellOf(o: JSONObject?): Cell? = o?.let { Cell(it.getInt("x"), it.getInt("y"), it.getInt("w"), it.getInt("h")) }

    fun verb(v: CanvasVerb): JSONObject = when (v) {
        is CanvasVerb.PageMake -> JSONObject().put("verb", "page.make").put("id", v.id).put("name", v.name).apply { v.after?.let { put("after", it) } }
        is CanvasVerb.PageRename -> JSONObject().put("verb", "page.rename").put("id", v.id).put("name", v.name)
        is CanvasVerb.PageRemove -> JSONObject().put("verb", "page.remove").put("id", v.id)
        is CanvasVerb.PageMove -> JSONObject().put("verb", "page.move").put("id", v.id).apply { v.after?.let { put("after", it) } }
        is CanvasVerb.Place -> JSONObject().put("verb", "module.place").put("key", v.key).put("kind", v.kind.name).put("page", v.page).put("cell", cell(v.cell))
        is CanvasVerb.Move -> JSONObject().put("verb", "module.move").put("key", v.key).put("page", v.page).put("cell", cell(v.cell))
        is CanvasVerb.Remove -> JSONObject().put("verb", "module.remove").put("key", v.key)
        is CanvasVerb.PagePanels -> pagePanelsJson(v)
        is CanvasVerb.Unknown -> runCatching { JSONObject(v.raw) }.getOrElse { JSONObject() }
    }

    /** The verb [o] says, or [CanvasVerb.Unknown] when it is from a newer [schema] or a kind this build doesn't know. Never throws. */
    fun verbOf(o: JSONObject, schema: Int = VERB_SCHEMA): CanvasVerb = runCatching {
        if (schema > VERB_SCHEMA) return CanvasVerb.Unknown(o)
        when (o.getString("verb")) {
            "page.make" -> CanvasVerb.PageMake(o.getString("id"), o.getString("name"), o.optString("after").ifEmpty { null })
            "page.rename" -> CanvasVerb.PageRename(o.getString("id"), o.getString("name"))
            "page.remove" -> CanvasVerb.PageRemove(o.getString("id"))
            "page.move" -> CanvasVerb.PageMove(o.getString("id"), o.optString("after").ifEmpty { null })
            "module.place" -> CanvasVerb.Place(o.getString("key"), ItemKind.valueOf(o.getString("kind")), o.getString("page"), cellOf(o.getJSONObject("cell"))!!)
            "module.move" -> CanvasVerb.Move(o.getString("key"), o.getString("page"), cellOf(o.getJSONObject("cell"))!!)
            "module.remove" -> CanvasVerb.Remove(o.getString("key"))
            "page.panels" -> pagePanelsOf(o)
            else -> CanvasVerb.Unknown(o)
        }
    }.getOrElse { CanvasVerb.Unknown(o) }

    fun log(l: VerbLog): String = JSONObject().put("screen", l.screen).put("entries", JSONArray(l.entries.map { e ->
        JSONObject().put("version", e.version).put("request", e.request).put("schema", e.schema)
            .put("origin", JSONObject().put("device", e.origin.device).put("actor", e.origin.actor)).put("verb", verb(e.verb))
    })).toString()

    /**
     * The log [text] holds, in version order, each version once (the first kept); an entry with no readable version,
     * request or origin is skipped, one whose verb is missing or unreadable is kept as [CanvasVerb.Unknown], and an
     * unreadable whole is null.
     */
    fun logOf(text: String?): VerbLog? = runCatching {
        val o = JSONObject(text.orEmpty())
        val entries = o.optJSONArray("entries")?.mapObjects { e ->
            val origin = e.getJSONObject("origin")
            val schema = e.optInt("schema", VERB_SCHEMA)
            val verb = e.optJSONObject("verb")?.let { verbOf(it, schema) } ?: CanvasVerb.Unknown(JSONObject())
            LoggedVerb(e.getLong("version"), e.getString("request"), VerbOrigin(origin.getString("device"), origin.getString("actor")), verb, schema)
        }.orEmpty()
        VerbLog(o.getString("screen"), entries.distinctBy { it.version }.sortedBy { it.version })
    }.getOrNull()
}
