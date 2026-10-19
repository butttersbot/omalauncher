package dev.omalauncher.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Pack
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.PanelLayouts
import org.json.JSONObject

/*
 * The Layouts module (Manga look, piece D): the only place saved layouts and the Manga look's preference show. A tap on a
 * layout asks inside the tile ("Load Work?") and loads it only on yes, as one change that undo takes back; "Save this"
 * keeps home as it is now under the next free "Layout N" (an agent renames it on his word); "Art: Ink / Color" is how art
 * in a panel is printed. No settings screen: this tile is the whole of it.
 */

/**
 * What the Layouts module reads and does where layouts are kept: the [shelf], and home's pad to load through (null while home
 * isn't open). [words] runs canvas words as every screen does.
 */
class LayoutsSource(
    val shelf: LayoutShelf,
    val pad: () -> CanvasPad?,
    private val now: () -> Long = System::currentTimeMillis,
    private val words: (List<String>, CanvasPad, String, String) -> JSONObject = { w, pad, request, actor -> CanvasCommands.run(w, pad, request, actor) },
) {
    /** What a tap in the tile came to: [text] to show, and whether it went well. */
    data class Said(val text: String, val ok: Boolean)

    /** His tap on yes: [name] loaded through home's pad as his own change, one restore point. */
    fun load(name: String): Said = act { pad -> shelf.load(name, pad, "tile-" + now().toString(36), "hand") }

    /** His tap on "Save this": home kept as the next free "Layout N". */
    fun save(): Said = act { pad -> "kept as \"" + shelf.save(nextLayoutName(shelf), pad.notebook()).name + "\"" }

    /** The page the tile [key] is on, on home's grid as it is now; null while home isn't open or the tile isn't on a page. */
    fun pageOf(key: String): Pair<Notebook, NotebookPage>? = pad()?.notebook()?.let { n -> n.pages.firstOrNull { p -> p.items.any { it.key == key } }?.let { n to it } }

    /** His tap on a panel layout's thumbnail: the page [page] cut into [layout] (`page.panels`), its things refit, one restore point. */
    fun panels(page: String, layout: String): Said = act { pad ->
        val r = words(listOf(PAGE_PANELS_WORD, page, layout), pad, "tile-" + now().toString(36), "hand")
        if (!r.optBoolean("ok")) refuse(r.optString("error"))
        r.optString("did")
    }

    private fun act(work: (CanvasPad) -> String): Said = try {
        val pad = pad() ?: refuse("Home is still loading. Try again in a moment.")
        Said(work(pad), true)
    } catch (e: AskRefused) {
        Said(e.message.orEmpty(), false)
    }
}

/** The screen's saved layouts for its Layouts module: set where layouts are kept (the Fold's home), null anywhere else. */
object Layouts {
    @Volatile var here: LayoutsSource? = null
}

/** The canvas word that cuts a page into panels (piece A's `page.panels PAGE LAYOUT`; a page may be named by its id). */
private const val PAGE_PANELS_WORD = "page.panels"

/** "Layout N" for the first N no layout is called. */
fun nextLayoutName(shelf: LayoutShelf): String = generateSequence(1) { it + 1 }.map { "Layout $it" }.first { shelf.get(it) == null }

private fun day(time: Long): String = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(time))

/**
 * Layouts: how many are saved (dot); "Save this" and the newest names (line); every layout that fits, newest first, with the
 * look and the day it was saved, then "Save this" and "Art: Ink / Color" (card and full). [now] is the screen's look at its
 * sources, so a layout an agent saves shows at the next one.
 */
@Composable
fun LayoutsModule(source: LayoutsSource?, size: ModuleSize, now: Long, modifier: Modifier = Modifier, key: String = "module:layouts") {
    var version by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<LayoutsSource.Said?>(null) }
    // Read at each look and after each tap here; the shelf parses only text it hasn't seen.
    val layouts = remember(source, version, now) { source?.shelf?.list().orEmpty() }
    // The page this tile is on, for its panel layouts: only a card or bigger shows them, and only on a page cut into panels.
    val page = remember(source, version, now, key, size) { if (size == ModuleSize.Card || size == ModuleSize.Full) source?.pageOf(key) else null }
    val tap = { what: () -> LayoutsSource.Said -> armed = null; said = what(); version++ }
    val ask = { name: String -> said = null; armed = name }
    ModuleFrame("Layouts", if (layouts.isEmpty()) "none saved" else "${layouts.size} saved", size, "module:layouts", modifier) {
        when {
            source == null -> BasicText("Saved layouts are kept on the Fold.", style = Pack.body(color = Pack.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            size == ModuleSize.Dot -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("${layouts.size}", style = Pack.display(Pack.size.title), maxLines = 1, softWrap = false)
                BasicText(Pack.caption("layouts"), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
            }
            armed != null -> Confirm(armed!!, inline = size == ModuleSize.Line, onYes = { name -> tap { source.load(name) } }, onNo = { armed = null })
            size == ModuleSize.Line -> Row(verticalAlignment = Alignment.CenterVertically) {
                ModuleButton("save this", Pack.accent) { tap { source.save() } }
                said?.let { Note(it, Modifier.weight(1f)) } ?: Box(Modifier.weight(1f)) {
                    FitAcross(layouts.size, gap = 4.dp) { i -> ModuleButton(layouts[i].name, Pack.foreground) { ask(layouts[i].name) } }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                said?.let { Note(it) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (layouts.isEmpty()) BasicText("Nothing saved yet: save this home to come back to it.", style = Pack.body(color = Pack.muted))
                    else FitRows(layouts.size, keyOf = { layouts[it].name }) { i -> LayoutRow(layouts[i]) { ask(layouts[i].name) } }
                }
                page?.let { (n, p) -> if (p.panels != null) PanelRow(p, n.columns, n.rows) { id -> tap { source.panels(p.id, id) } } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ModuleButton("save this", Pack.accent) { tap { source.save() } }
                    Spacer(Modifier.weight(1f))
                    BasicText(Pack.caption("art"), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
                    ModuleButton("ink", if (MangaPrefs.artInk) Pack.accent else Pack.muted) { MangaPrefs.artInk = true }
                    ModuleButton("color", if (MangaPrefs.artInk) Pack.muted else Pack.accent) { MangaPrefs.artInk = false }
                }
            }
        }
    }
}

/**
 * The page's panel layouts, when the page the tile is on is cut into panels: a thumbnail of each that can be cut on this grid,
 * the page's own marked; a tap cuts the page into that one (`page.panels`), refitting what is on it as one change undo takes back.
 */
@Composable
private fun PanelRow(page: NotebookPage, columns: Int, rows: Int, onPick: (String) -> Unit) {
    val ids = remember(columns, rows) { PanelLayouts.ids.filter { PanelLayouts.fits(it, columns, rows) } }
    FitAcross(ids.size, gap = 6.dp) { i ->
        val id = ids[i]
        val on = id == page.panels
        val ink = if (on) Pack.accent else Pack.muted
        val panels = remember(id, columns, rows) { PanelLayouts.panels(id, columns, rows) }
        Canvas(Modifier.size(22.dp, 34.dp).clickable(enabled = !on) { onPick(id) }.semantics { contentDescription = "panels ${PanelLayouts.name(id)}" + if (on) ", this page" else "" }) {
            for (p in panels) {
                val path = Path()
                p.outline.forEachIndexed { n, pt ->
                    val x = (pt.x * size.width).toFloat()
                    val y = (pt.y * size.height).toFloat()
                    if (n == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, ink, style = Stroke(width = if (on) 1.6f else 1f))
            }
        }
    }
}

/** One saved layout: its name, then the look and the day it was saved where there is room. Tapping it asks to load it. */
@Composable
private fun LayoutRow(l: SavedLayout, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(l.name, Modifier.weight(1f), style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        BasicText(Pack.caption(listOfNotNull(l.look, day(l.time)).joinToString(" · ")), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
    }
}

/** The ask inside the tile before anything is loaded: yes loads, no goes back to the list. On one row ([inline]) it is the short ask. */
@Composable
private fun Confirm(name: String, inline: Boolean, onYes: (String) -> Unit, onNo: () -> Unit) {
    val answers = @Composable {
        ModuleButton("yes", Pack.green) { onYes(name) }
        ModuleButton("no", Pack.red, onNo)
    }
    if (inline) Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("Load \"$name\"?", Modifier.weight(1f), style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        answers()
    } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("Load \"$name\"? Home becomes that layout; undo takes it back.", style = Pack.body(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) { answers() }
    }
}

@Composable
private fun Note(said: LayoutsSource.Said, modifier: Modifier = Modifier) {
    BasicText(said.text, modifier, style = Pack.mono(Pack.size.small, if (said.ok) Pack.muted else Pack.red), maxLines = 2, overflow = TextOverflow.Ellipsis)
}
