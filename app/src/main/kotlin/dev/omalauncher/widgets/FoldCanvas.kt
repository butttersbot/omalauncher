package dev.omalauncher.widgets

import android.content.Context
import dev.omalauncher.canvas.CanvasPad
import dev.omalauncher.canvas.RestorePoint
import dev.omalauncher.canvas.RestorePoints
import dev.omalauncher.canvas.ScreenRules
import dev.omalauncher.canvas.restorePoint
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.PAGE_START
import dev.omalauncher.widgets.layout.applyVerb
import dev.omalauncher.widgets.layout.shown

/*
 * The Fold's home as the agents' canvas verbs reach it (Soma Canvas slice 6): `oma-phone canvas …`, from the quick lane or a
 * builder, and only on Boss's word. LiveNotebook stays home's only writer: each verb is done through it as the overview or a
 * drag does it, any page included. What undoes each request is kept beside the notebook ([points]), never in it.
 */
class FoldCanvasPad(
    private val notebook: LiveNotebook,
    /** A new page after the last, as the overview adds one; null when home can't take one now. */
    private val addPage: () -> String?,
    /** Takes away a page of modules with what is on it; false when it can't be. */
    private val removePage: (String) -> Boolean,
    private val points: RestorePoints,
    /**
     * Whether a page has his notes, stickers or ink on it, or they aren't read yet: an agent never takes such a page away,
     * since they go with it for good (removing one is his, in the overview, where he is asked).
     */
    private val marked: (String) -> Boolean = { false },
    /** An undo done here, told to sync with the verbs it applied, so the service's copy knows that request is undone (s12). */
    private val undoneHere: (String, List<CanvasVerb>) -> Unit = { _, _ -> },
) : CanvasPad {
    override val rules = ScreenRules(
        // Ids as the notebook gives them, so a resolved page's id is the one it then gets (each is mapped anyway).
        pageId = { n -> "p" + ((n.pages.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1) },
    )

    /** Home as placing sees it: an app or a folder takes one cell, whatever an old saved cell says. */
    override fun notebook(): Notebook {
        val n = notebook.snapshot()
        return n.copy(pages = n.pages.map { p ->
            p.copy(items = p.items.map { it.copy(cell = it.cell?.shown(it.kind != ItemKind.App && it.kind != ItemKind.Folder)) })
        })
    }

    override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
        val before = notebook()
        val done = ArrayList<CanvasVerb>()
        val why = run(verbs, done)
        if (done.isNotEmpty()) points.add(request, restorePoint(before, done))
        return why
    }

    override fun lastRestorePoint(): RestorePoint? = points.last()

    override fun restorePoints(): List<RestorePoint> = points.all().reversed()

    /** Kept until it all applies: an undo that stops partway (home not written now) can be asked again. */
    override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? {
        val done = ArrayList<CanvasVerb>()
        val why = run(verbs, done)
        if (done.isNotEmpty()) undoneHere(restored.request, done)
        if (why == null) points.drop(restored.request)
        return why
    }

    /**
     * [verbs] done one by one through the notebook, each checked first against home as it is then, exactly as a verb applies
     * anywhere. A page made here gets the notebook's own id, and later verbs naming the asked-for id follow it. What was done
     * goes into [done], with the ids it really has. Why the first that couldn't be done wasn't; null when all were.
     */
    private fun run(verbs: List<CanvasVerb>, done: MutableList<CanvasVerb>): String? {
        if (!notebook.opened) return "Home is still loading. Try again in a moment"
        val ids = HashMap<String, String>()
        fun real(id: String) = ids[id] ?: id
        // Pages made by these same verbs: nothing can be written or stuck on one yet, though its notes were never read, so
        // taking it away again (a saved layout's page for the moment) is never stopped by the marks rule (storyboard critic).
        val made = HashSet<String>()
        for (asked in verbs) {
            val v = when (asked) {
                is CanvasVerb.PageRename -> asked.copy(id = real(asked.id))
                is CanvasVerb.PageRemove -> asked.copy(id = real(asked.id))
                is CanvasVerb.PageMove -> asked.copy(id = real(asked.id), after = asked.after?.let { if (it == PAGE_START) it else real(it) })
                is CanvasVerb.Place -> asked.copy(page = real(asked.page))
                is CanvasVerb.Move -> asked.copy(page = real(asked.page))
                is CanvasVerb.PagePanels -> asked.copy(id = real(asked.id))
                is CanvasVerb.PageMake -> asked.copy(after = asked.after?.let { if (it == PAGE_START) it else real(it) })
                else -> asked
            }
            val check = applyVerb(notebook(), v)
            if (!check.applied) return check.why
            when (v) {
                is CanvasVerb.PageMake -> {
                    // By the id asked for, free on home (checked just above): a page an undo or a saved layout brings back is the
                    // page it was, and a later verb naming another's old id never meets one home numbered meanwhile (layouts review).
                    if (pageName(v.name) == null) return "that is not a name a page can have"
                    if (notebook.addPageWithId(v.id, v.name, v.after)) { made += v.id; done += v; continue }
                    val id = addPage() ?: return "home can't take a new page now"
                    ids[v.id] = id
                    if (!notebook.renamePage(id, v.name)) { notebook.withdrawPage(id); return "that is not a name a page can have" }
                    // Back in its place, for an undo that brings a page back: after the one it followed, or first.
                    val at = when (v.after) { null -> notebook.pages.size - 1; PAGE_START -> 0; else -> notebook.position(v.after) + 1 }
                    if (at in 0 until notebook.pages.size - 1) notebook.movePage(id, at - (notebook.pages.size - 1))
                    made += id
                    done += v.copy(id = id)
                    continue
                }
                is CanvasVerb.PageRename -> if (!notebook.renamePage(v.id, v.name)) return "that is not a name a page can have"
                is CanvasVerb.PageMove -> {
                    // Where applyVerb put it (checked just above), reached by moving it that many places.
                    val to = check.notebook.pages.indexOfFirst { it.id == v.id }
                    val by = to - notebook.position(v.id)
                    if (by != 0 && !notebook.movePage(v.id, by)) return "home can't move that page now"
                }
                is CanvasVerb.PageRemove -> {
                    if (v.id !in made && marked(v.id)) return "the page \"${notebook.name(v.id)}\" has notes, stickers or ink on it, so it stays; he removes it himself in the overview"
                    if (!removePage(v.id)) return "home can't take that page away now"
                }
                is CanvasVerb.Place -> { notebook.move(v.key, v.page); notebook.setCell(v.key, v.cell); notebook.save() }
                is CanvasVerb.Move -> { notebook.move(v.key, v.page); notebook.setCell(v.key, v.cell); notebook.save() }
                is CanvasVerb.Remove -> notebook.forget(v.key)
                is CanvasVerb.PagePanels -> if (!notebook.setPanels(v.id, v.panels, v.cells)) return "home can't change that page's panels now"
                is CanvasVerb.Unknown -> return "a newer build's change"
            }
            done += v
        }
        return null
    }
}

/** Where what undoes home's arrangements is kept: a file of its own beside the notebook, never in it. */
fun foldRestorePoints(context: Context): RestorePoints {
    val prefs = context.getSharedPreferences("canvas", Context.MODE_PRIVATE)
    return RestorePoints({ prefs.getString("undo", null) }, { prefs.edit().putString("undo", it).apply() })
}

/** The undos done on home that sync hasn't sent yet, kept beside the restore points. */
fun foldUndonePending(context: Context): UndonePending {
    val prefs = context.getSharedPreferences("canvas", Context.MODE_PRIVATE)
    return UndonePending({ prefs.getString("undo-out", null) }, { prefs.edit().putString("undo-out", it).apply() })
}

/**
 * Undos done on home, each with what it touched (item keys and page ids), oldest first, until sync has sent them (s12, Boss:
 * undo takes back Grok's changes too). Sync tags the verbs it sends for those things as that undo's (`undo:<request>`), so the
 * service's copy of home knows the request is undone and no later undo there takes it back a second time.
 */
class UndonePending(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Undo(val request: String, val touched: Set<String>)

    fun all(): List<Undo> = runCatching {
        org.json.JSONArray(read() ?: "[]").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val t = o.getJSONArray("touched")
                Undo(o.getString("request"), (0 until t.length()).map { t.getString(it) }.toSet())
            }
        }
    }.getOrDefault(emptyList())

    private fun save(undos: List<Undo>) = write(org.json.JSONArray(undos.map { u ->
        org.json.JSONObject().put("request", u.request).put("touched", org.json.JSONArray(u.touched.toList()))
    }).toString())

    fun add(request: String, verbs: List<CanvasVerb>) {
        val was = all()
        val touched = verbs.mapNotNull(::touchedBy).toSet() + was.firstOrNull { it.request == request }?.touched.orEmpty()
        save(was.filter { it.request != request } + Undo(request, touched))
    }

    /** The undos sent in a round: gone from the list (one done since stays for the next round). */
    fun sent(requests: Set<String>) { if (requests.isNotEmpty()) save(all().filter { it.request !in requests }) }
}

/** The item or page a verb is about: an item's key, or a page's id. */
fun touchedBy(v: CanvasVerb): String? = when (v) {
    is CanvasVerb.Place -> v.key
    is CanvasVerb.Move -> v.key
    is CanvasVerb.Remove -> v.key
    is CanvasVerb.PageMake -> "page:" + v.id
    is CanvasVerb.PageRename -> "page:" + v.id
    is CanvasVerb.PageRemove -> "page:" + v.id
    is CanvasVerb.PageMove -> "page:" + v.id
    is CanvasVerb.PagePanels -> "page:" + v.id
    is CanvasVerb.Unknown -> null
}
