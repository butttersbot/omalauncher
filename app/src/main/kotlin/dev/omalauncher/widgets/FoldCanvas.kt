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

    /** Kept until it all applies: an undo that stops partway (home not written now) can be asked again. */
    override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? {
        val why = run(verbs, ArrayList())
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
        for (asked in verbs) {
            val v = when (asked) {
                is CanvasVerb.PageRename -> asked.copy(id = real(asked.id))
                is CanvasVerb.PageRemove -> asked.copy(id = real(asked.id))
                is CanvasVerb.Place -> asked.copy(page = real(asked.page))
                is CanvasVerb.Move -> asked.copy(page = real(asked.page))
                is CanvasVerb.PageMake -> asked.copy(after = asked.after?.let { if (it == PAGE_START) it else real(it) })
                else -> asked
            }
            val check = applyVerb(notebook(), v)
            if (!check.applied) return check.why
            when (v) {
                is CanvasVerb.PageMake -> {
                    val id = addPage() ?: return "home can't take a new page now"
                    ids[v.id] = id
                    if (!notebook.renamePage(id, v.name)) { notebook.withdrawPage(id); return "that is not a name a page can have" }
                    // Back in its place, for an undo that brings a page back: after the one it followed, or first.
                    val at = when (v.after) { null -> notebook.pages.size - 1; PAGE_START -> 0; else -> notebook.position(v.after) + 1 }
                    if (at in 0 until notebook.pages.size - 1) notebook.movePage(id, at - (notebook.pages.size - 1))
                    done += v.copy(id = id)
                    continue
                }
                is CanvasVerb.PageRename -> if (!notebook.renamePage(v.id, v.name)) return "that is not a name a page can have"
                is CanvasVerb.PageRemove -> if (!removePage(v.id)) return "home can't take that page away now"
                is CanvasVerb.Place -> { notebook.move(v.key, v.page); notebook.setCell(v.key, v.cell); notebook.save() }
                is CanvasVerb.Move -> { notebook.move(v.key, v.page); notebook.setCell(v.key, v.cell); notebook.save() }
                is CanvasVerb.Remove -> notebook.forget(v.key)
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
