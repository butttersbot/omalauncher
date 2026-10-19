package dev.omalauncher.canvas

import org.json.JSONArray
import org.json.JSONObject

/*
 * The saved-layout words (Manga look, piece D), beside the canvas words every screen takes: `layout.save NAME [--replace]`,
 * `layout.load NAME`, `layout.list`, `layout.delete NAME`, `layout.rename NAME --to NEW`. They reach a screen that keeps
 * saved layouts (a [LayoutPad]: the Fold's home); anywhere else they say where layouts are kept and change nothing.
 */

/** A screen's canvas with the layouts saved of it. */
interface LayoutPad : CanvasPad {
    val layouts: LayoutShelf
}

/** [this] canvas with [shelf] as its saved layouts. */
fun CanvasPad.withLayouts(shelf: LayoutShelf): LayoutPad = object : LayoutPad, CanvasPad by this {
    override val layouts = shelf
}

/** The words, as said or with a dash (`layout-save`), as the other canvas words are. */
private val LAYOUT_VERBS = setOf("save", "load", "list", "delete", "rename")

const val LAYOUT_USAGE = "layout.save NAME [--replace] | layout.load NAME | layout.list | layout.delete NAME | layout.rename NAME --to NEW"

/** The saved-layout words, run against [pad]; null when [words] aren't one of them. */
fun layoutWords(words: List<String>, pad: CanvasPad, request: String, actor: String): JSONObject? {
    val verb = words.firstOrNull()?.let { w -> LAYOUT_VERBS.firstOrNull { w == "layout.$it" || w == "layout-$it" } } ?: return null
    val shelf = (pad as? LayoutPad)?.layouts
        ?: refuse("Saved layouts are kept on the Fold, beside its home; this screen has none. Nothing was changed.")
    val rest = words.drop(1).toMutableList()
    fun usage(): Nothing = refuse("Usage: $LAYOUT_USAGE. Nothing was changed.")
    fun option(name: String): String? {
        val at = rest.indexOf(name)
        if (at < 0) return null
        val value = rest.drop(at + 1).joinToString(" ").ifBlank { usage() }
        while (rest.size > at) rest.removeAt(at)
        return value
    }
    fun named() = rest.joinToString(" ").ifBlank { usage() }
    return when (verb) {
        "list" -> {
            if (rest.isNotEmpty()) usage()
            JSONObject().put("ok", true).put("layouts", JSONArray(shelf.list().map { l ->
                JSONObject().put("name", l.name).putOpt("look", l.look).put("time", l.time).put("pages", l.pages).put("auto", l.auto)
            }))
        }
        "save" -> {
            val replace = rest.remove("--replace")
            val saved = shelf.save(named(), pad.notebook(), replace)
            JSONObject().put("ok", true).put("did", "kept home as the layout \"${saved.name}\" (${saved.pages} pages)")
        }
        "load" -> JSONObject().put("ok", true).put("did", shelf.load(named(), pad, request, actor))
        "delete" -> {
            val name = named()
            val was = shelf.get(name)?.name ?: refuse("No layout is called \"$name\". Nothing was changed.")
            shelf.delete(was)
            JSONObject().put("ok", true).put("did", "deleted the layout \"$was\"")
        }
        else -> {
            val to = option("--to") ?: usage()
            val from = shelf.get(named())?.name
            val renamed = shelf.rename(named(), to)
            JSONObject().put("ok", true).put("did", "the layout \"${from ?: renamed.name}\" is now called \"${renamed.name}\"")
        }
    }
}
