package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.omalauncher.canvas.AskRefused
import dev.omalauncher.canvas.CanvasShelf
import dev.omalauncher.canvas.templateNameOf
import dev.omalauncher.canvas.templateOf
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Menu
import dev.omalauncher.ui.MenuItem
import dev.omalauncher.ui.MenuTitle
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.framed
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.NotebookPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * The dashboard's pages, small (desktop slice 5c, Boss approved design/soma-dashboard-pages-mockup.html on 2026-10-28):
 * the phone's pinch to its Overview, reached by [ pages ], Cmd-0 or a pinch on a trackpad. Every page is a thumbnail of its
 * blocks, the one shown outlined: a click goes there, a drag reorders, a right-click renames one, keeps it as a template or
 * removes it (asking first when something is on it), and + page adds one. Under them, the phone's looks as a strip: a click
 * restyles both of Soma's windows. Escape goes back.
 */

/** A thumbnail's width, and the space between thumbnails. */
private val THUMB = 200.dp
private val THUMB_GAP = 14.dp

/** The longest name a page takes here. */
const val PAGE_NAME_CHARS = 40

/** [typed] as a page's name: its spaces as one, at most [PAGE_NAME_CHARS]; null when nothing is left. */
fun pageNameOf(typed: String): String? = typed.replace(Regex("\\s+"), " ").trim().take(PAGE_NAME_CHARS).trim().ifEmpty { null }

/** Where a page dragged [moved] thumbnails along from [from] lands among [count] pages. */
fun droppedAt(from: Int, moved: Float, count: Int): Int = (from + moved.roundToInt()).coerceIn(0, count - 1)

/**
 * What removing the page with [blocks] blocks and [marks] (its notes, stickers and ink) asks before it goes: null when the
 * page is empty and goes at once.
 */
fun removeQuestion(name: String, blocks: Int, marks: Boolean): String? {
    if (blocks == 0 && !marks) return null
    val what = listOfNotNull(
        blocks.takeIf { it > 0 }?.let { if (it == 1) "1 block" else "$it blocks" },
        "its notes, stickers or ink".takeIf { marks },
    ).joinToString(" and ")
    return "click again to remove \"$name\" with $what"
}

/** What the overview's right-click menu shows now: its rows, the rename field, or the template's name. */
private enum class PageMenu { Rows, Rename, Template }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PagesOverview(canvas: DesktopCanvas, scraps: DesktopScraps?, shelf: (() -> CanvasShelf)?) {
    val pages = canvas.page.pages
    var asked by remember { mutableStateOf<NotebookPage?>(null) }
    // What was last said here, and whether it went wrong (in red) or was done (muted).
    var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // Each page's notes, stickers and ink read, so removing one with some asks first even if it was never shown.
    LaunchedEffect(scraps, pages.map { it.id }) { scraps?.let { s -> pages.forEach { s.book.open(it.id) } } }
    // Escape goes back to the page, once the menu (if open) has taken its own.
    BackHandler(enabled = asked == null) { canvas.overview = false }
    // Over the page, and its menu over it.
    Box(Modifier.fillMaxSize().zIndex(10f)) {
        Column(
            Modifier.fillMaxSize().background(Pack.background)
                // In the way of the page under it: a click or a right-click here is never the page's.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(24.dp).semantics { contentDescription = "Pages overview" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(Pack.caption("pages"), Modifier.weight(1f), style = Pack.mono(Pack.size.small, Pack.muted))
                BracketAction("back", Pack.accent) { canvas.overview = false }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(THUMB_GAP),
                verticalAlignment = Alignment.Top,
            ) {
                pages.forEachIndexed { i, p ->
                    androidx.compose.runtime.key(p.id) {
                        Thumb(canvas, p, i, pages.size, on = p.id == canvas.showing, onMenu = { asked = p })
                    }
                }
                BasicText(
                    "+ page",
                    Modifier.width(THUMB).height(THUMB * DESKTOP_ROWS / DESKTOP_COLUMNS).framed(Pack.hair, Pack.border, Pack.background)
                        .clickable { if (canvas.addPage() == null) said = "a page couldn't be added" to true }
                        .padding(top = THUMB * DESKTOP_ROWS / DESKTOP_COLUMNS / 2 - 10.dp).semantics { contentDescription = "add a page" },
                    style = Pack.body(Pack.size.item, Pack.accent).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
            }
            said?.let { (words, wrong) -> BasicText(words, Modifier.padding(bottom = 10.dp).clickable { said = null }, style = Pack.mono(Pack.size.small, if (wrong) Pack.red else Pack.muted)) }
            BasicText(Pack.caption("looks"), Modifier.padding(top = 12.dp), style = Pack.mono(Pack.size.small, Pack.muted))
            LooksStrip()
            Spacer(Modifier.weight(1f))
            BasicText(
                "click a page to go there · drag one to reorder · right-click to rename, use as template or remove · Cmd-1…9 jump to a page · Escape goes back",
                style = Pack.mono(Pack.size.small, Pack.muted),
            )
        }
        asked?.let { page -> PageMenu(canvas, scraps, shelf, page, onSaid = { words, wrong -> said = words to wrong }, onDismiss = { asked = null }) }
    }
}

/** One page, small: its blocks where they are, its name under it. A click shows it; a drag along the row moves it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Thumb(canvas: DesktopCanvas, page: NotebookPage, index: Int, count: Int, on: Boolean, onMenu: () -> Unit) {
    var drag by remember { mutableStateOf(0f) }
    // Where the pointer went down on it, before it moved.
    var downAt by remember { mutableStateOf(0f) }
    val pitch = with(LocalDensity.current) { (THUMB + THUMB_GAP).toPx() }
    Column(
        Modifier.width(THUMB).zIndex(if (drag != 0f) 1f else 0f).graphicsLayer { translationX = drag }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e -> if (e.buttons.isSecondaryPressed) onMenu() }
            .pointerInput(page.id) { awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false).position.x } }
            .pointerInput(page.id, index, count) {
                detectDragGestures(
                    onDragEnd = {
                        val to = droppedAt(index, drag / pitch, count)
                        drag = 0f
                        if (to != index) canvas.movePage(page.id, to)
                    },
                    onDragCancel = { drag = 0f },
                ) { change, _ ->
                    change.consume()
                    // Where the pointer is now in the thumbnail's unmoved frame, less where it went down: it follows the
                    // pointer exactly, as a block being arranged does (its own translation undone first).
                    drag = change.position.x + drag - downAt
                }
            }
            .clickable { canvas.shown = page.id; canvas.overview = false }
            .semantics { contentDescription = "thumbnail ${page.name}" },
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(THUMB * DESKTOP_ROWS / DESKTOP_COLUMNS)
                .border(if (on) 2.dp else 1.dp, if (on) Pack.accent else Pack.border).background(Pack.surface),
        ) {
            val cw = maxWidth / DESKTOP_COLUMNS
            val ch = maxHeight / DESKTOP_ROWS
            for (item in canvas.blocksOn(page.id)) {
                val c = item.cell ?: continue
                Box(Modifier.offset(cw * c.x + 2.dp, ch * c.y + 2.dp).size(cw * c.w - 4.dp, ch * c.h - 4.dp).background(Pack.background).border(1.dp, Pack.border))
            }
        }
        BasicText(
            page.name, Modifier.padding(top = 6.dp),
            style = Pack.body(Pack.size.small, if (on) Pack.accent else Pack.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The phone's looks as a strip: each its four colors and its name, the one in use outlined; a click uses it. */
@Composable
private fun LooksStrip() {
    val now = dev.omalauncher.ui.ActivePack.pick.id
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (look in Looks.swatches) {
            val on = look.id == now
            Column(
                Modifier.border(if (on) 2.dp else 1.dp, if (on) Pack.accent else Pack.border).clickable { Looks.pick(look.id) }.padding(8.dp)
                    .semantics { contentDescription = "look ${look.name}" },
            ) {
                Row { for (c in look.colors) Box(Modifier.size(18.dp).background(c).border(1.dp, Pack.border)) }
                BasicText(look.name, Modifier.padding(top = 4.dp), style = Pack.body(Pack.size.small, if (on) Pack.accent else Pack.foreground), maxLines = 1)
            }
        }
    }
}

/** A page's right-click menu in the overview: rename, use as template…, remove page (asking first when it isn't empty). */
@Composable
private fun PageMenu(canvas: DesktopCanvas, scraps: DesktopScraps?, shelf: (() -> CanvasShelf)?, page: NotebookPage, onSaid: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(PageMenu.Rows) }
    var sure by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(page.name) }
    var why by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler { if (shown == PageMenu.Rows) onDismiss() else { shown = PageMenu.Rows; why = null } }
    val caption = Pack.mono(Pack.size.small, Pack.muted)
    fun rename() {
        val clean = pageNameOf(name) ?: return
        if (clean != page.name) canvas.apply(CanvasVerb.PageRename(page.id, clean))
        onDismiss()
    }
    fun keep() {
        val s = shelf ?: return
        val typed = name
        // Laid out as it is now, on this thread; kept on the shelf (perhaps the Mac Pro's, over the tailnet) off it.
        val template = runCatching {
            val named = templateNameOf(typed)
            named to templateOf(canvas.page.copy(pages = listOf(page)), null, named, canvas.rules)
        }.getOrElse { why = (it as? AskRefused)?.message ?: "that couldn't be kept"; return }
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    val kept = s()
                    if (!replace && kept.template(template.first) != null) null
                    else { kept.keepTemplate(template.first, template.second.toString()); "kept \"${page.name}\" as the template \"${template.first}\": ask any screen's agent to lay it out there" }
                }
            }
            done.onSuccess { did ->
                if (did == null) { replace = true; why = "a template is already called \"${template.first}\": keep it again to replace it" }
                else { onSaid(did, false); onDismiss() }
            }.onFailure { why = "the template couldn't be kept: ${it.message ?: it.javaClass.simpleName}" }
        }
    }
    Menu(onDismiss) {
        when (shown) {
            PageMenu.Rows -> {
                BasicText("page \"${page.name}\"", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MenuItem("rename") { name = page.name; shown = PageMenu.Rename }
                if (shelf != null) MenuItem("use as template…") { name = page.name; replace = false; why = null; shown = PageMenu.Template }
                if (page.id != DESKTOP_PAGE) {
                    val question = removeQuestion(page.name, page.items.size, scraps?.book?.on(page.id)?.isEmpty == false)
                    MenuItem(if (sure && question != null) question else "remove page", Pack.red) {
                        if (question != null && !sure) { sure = true; return@MenuItem }
                        val done = canvas.apply(CanvasVerb.PageRemove(page.id))
                        if (!done.applied) onSaid("\"${page.name}\" stays: ${done.why}", true)
                        onDismiss()
                    }
                }
            }
            PageMenu.Rename, PageMenu.Template -> {
                val template = shown == PageMenu.Template
                MenuTitle(if (template) "keep \"${page.name}\" as the template called" else "rename \"${page.name}\"")
                val focus = remember { FocusRequester() }
                LaunchedEffect(shown) { focus.requestFocus() }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ").take(PAGE_NAME_CHARS); replace = false },
                    singleLine = true,
                    textStyle = Pack.body(Pack.size.item, Pack.foreground),
                    cursorBrush = SolidColor(Pack.accent),
                    modifier = Modifier.width(320.dp).padding(horizontal = 4.dp).framed(Pack.mark, Pack.accent, Pack.surface).padding(10.dp).focusRequester(focus)
                        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { if (template) keep() else rename(); true } else false }
                        .semantics { contentDescription = if (template) "template name" else "page name" },
                )
                why?.let { BasicText(it, Modifier.width(320.dp).padding(horizontal = 12.dp, vertical = 6.dp), style = Pack.mono(Pack.size.small, Pack.red)) }
                Row(Modifier.padding(top = 4.dp)) {
                    BracketAction(if (template) (if (replace) "replace" else "keep") else "done", Pack.accent) { if (template) keep() else rename() }
                    BracketAction("cancel", Pack.muted, onDismiss)
                }
            }
        }
    }
}
