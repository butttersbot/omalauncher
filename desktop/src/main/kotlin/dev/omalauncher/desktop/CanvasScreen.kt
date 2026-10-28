package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.canvas.LocalModuleFocus
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.ModuleSource
import dev.omalauncher.canvas.ModuleView
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.canvas.moduleRef
import dev.omalauncher.canvas.moduleSize
import dev.omalauncher.oma.ChatHost
import dev.omalauncher.oma.OMA_TAB
import dev.omalauncher.oma.OmaChatScreen
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.oma.newChatTab
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.ui.Menu
import dev.omalauncher.ui.MenuItem
import dev.omalauncher.ui.MenuTitle
import dev.omalauncher.ui.Pack
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.zIndex
import dev.omalauncher.ui.Chosen
import dev.omalauncher.ui.InkLayer
import dev.omalauncher.ui.InkToolKeys
import dev.omalauncher.ui.InkToolWords
import dev.omalauncher.ui.LiveInk
import dev.omalauncher.ui.LocalStickerShelf
import dev.omalauncher.ui.NoteEditor
import dev.omalauncher.ui.PageScraps
import dev.omalauncher.ui.StickerPicker
import dev.omalauncher.widgets.annotations.NoteRole
import dev.omalauncher.widgets.annotations.PageArea
import kotlin.math.roundToInt

/** Below this width the window is the chat alone: the canvas is kept as it is and comes back when there is room. */
val CANVAS_ROOM = 760.dp

/** The space between blocks and around the page. */
private val GAP = 8.dp

/** How far the pack's cards draw past their own edges, each side: what the grid leaves room for at the window's edges. */
internal data class Reach(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp)

/**
 * The pack's [Reach]: each cast shadow's offset plus its blur as far as it shows (a blur reaches about 1.75 times its radius,
 * plus a pixel or two of fade; round-1 review: x + blur alone left custard's tail at the edge), and the tape on a card's top,
 * half above the card and lifted at one end by its angle (round-1 review: a taped card in the top row had its tape sliced).
 */
internal fun shadowReach(): Reach {
    val scrap = Pack.look.pack.scrap
    val cast = listOf(scrap.surface.shadow, scrap.surface.shadow2).filter { it.visible && !it.inset }
    fun bleed(offset: (dev.omalauncher.theme.pack.PackShadow) -> Float) = (cast.maxOfOrNull { if (it.blur > 0f) offset(it) + it.blur * 1.75f + 2f else offset(it) } ?: 0f).coerceAtLeast(0f)
    val tape = scrap.tape.takeIf { it.style != dev.omalauncher.theme.pack.Dressing.None }
        ?.let { it.height / 2f + it.width / 2f * kotlin.math.abs(kotlin.math.sin(Math.toRadians(it.angle.toDouble()))).toFloat() } ?: 0f
    // A shadow's left and top bleed only past the gap that is already there; the tape gets the whole gap above it too, as the
    // shadows do at the right and bottom (round-2 review: netting the gap off put the tape's corner flush with the top).
    return Reach((bleed { -it.x } - GAP.value).coerceAtLeast(0f).dp, maxOf(bleed { -it.y } - GAP.value, tape).coerceAtLeast(0f).dp, bleed { it.x }.dp, bleed { it.y }.dp)
}

/** The arranging bar's strip, below the blocks. */
private val BAR = 36.dp

/** The grip on a block's corner that resizes it while arranging. */
private val GRIP = 18.dp

/**
 * The chat block of the dashboard window, where the chat has a window of its own (Boss, 2026-10-28: the chat window is the
 * unfolded phone's, the widgets a window of their own): a click brings the chat window.
 */
@Composable
private fun ChatElsewhere(open: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick = open).semantics { contentDescription = "Chat block" }, contentAlignment = Alignment.Center) {
        BasicText("[ chat ]", style = Pack.body(color = Pack.accent))
    }
}

/**
 * The window as a canvas (slice 5): [canvas]'s page, its chat block drawing the chat on [host] and every other block a
 * module from [source], at the size its cells give it. A tap on a module opens that thread's tab in the chat block; a
 * right-click offers to add a module, remove one or arrange; while arranging, a block is moved by dragging it and resized
 * by its corner, and every change is a verb in the canvas's log. [now] changes when the modules may show something new.
 *
 * With [scraps] (the dashboard window, desktop slice 5b) each page also has the phone home's notes, stickers and ink over
 * its blocks: the page's right-click menu adds a note or a sticker or draws; a click opens a note's text, a right-click on
 * a note or sticker selects it (its bar edits, turns or deletes it, its corner resizes it), and a drag moves it.
 */
@Composable
fun CanvasScreen(
    canvas: DesktopCanvas,
    host: ChatHost,
    source: ModuleSource,
    now: Long,
    wanted: String?,
    onTab: (String?) -> Unit,
    chatElsewhere: (() -> Unit)? = null,
    widgets: WindowWidgets? = null,
    /** The page's notes, stickers and ink (the dashboard window's, desktop slice 5b); without them it has none. */
    scraps: DesktopScraps? = null,
    /** Where a page kept as a template goes (the overview's "use as template…", desktop slice 5c); without it, not offered. */
    shelf: (() -> dev.omalauncher.canvas.CanvasShelf)? = null,
    /** Whether the overview of pages and looks is offered (the dashboard window, desktop slice 5c). */
    pagesOverview: Boolean = false,
) {
    // The stickers' pictures, for the page's stickers and the picker in its menu.
    CompositionLocalProvider(LocalStickerShelf provides scraps?.shelf) {
        Box(Modifier.fillMaxSize()) {
            CanvasPage(canvas, host, source, now, wanted, onTab, chatElsewhere, widgets, scraps, pagesOverview)
            if (pagesOverview && canvas.overview) PagesOverview(canvas, scraps, shelf)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CanvasPage(
    canvas: DesktopCanvas,
    host: ChatHost,
    source: ModuleSource,
    now: Long,
    wanted: String?,
    onTab: (String?) -> Unit,
    chatElsewhere: (() -> Unit)?,
    widgets: WindowWidgets?,
    scraps: DesktopScraps?,
    pagesOverview: Boolean,
) {
    var arranging by remember { mutableStateOf(false) }
    // The right-click menu, and the block it was asked over (null: the page).
    var menuOver by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // Where on the page the menu was asked for, and the stickers offered in it in place of its items.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var stickersOffered by remember { mutableStateOf(false) }
    // Where the page is, for the notes, stickers and ink on it; set as the page is laid out.
    val sheet = remember { Sheet() }
    BackHandler(enabled = arranging && !menuOpen) { arranging = false }
    if (scraps != null) {
        // Escape leaves drawing, then lets go of the note or sticker selected; an open note's own editor takes it first.
        BackHandler(enabled = scraps.drawing && !menuOpen) { scraps.stopDrawing() }
        BackHandler(enabled = scraps.marks.selected != null && !menuOpen) { scraps.marks.selected = null }
    }
    // Escape closes the menu first, as the chat's own menus do, before anything else takes it.
    BackHandler(enabled = menuOpen) { menuOpen = false }
    // One chat, moved between the canvas and the chat-alone window rather than made again: what he was typing, the open
    // tab strip and the scroll go with it when the window narrows or widens.
    val wantedNow by rememberUpdatedState(wanted)
    val onTabNow by rememberUpdatedState(onTab)
    val chat = remember(host) { movableContentOf { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wantedNow, onTab = { onTabNow(it.takeIf { tab -> tab != OMA_TAB }) }) } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Pack.background).then(if (scraps != null) Modifier.scrapsByMouse(scraps, sheet) else Modifier)) {
        if (maxWidth < CANVAS_ROOM && chatElsewhere == null) {
            chat()
            canvas.unkept?.let { why -> Unkept(why, Modifier.align(Alignment.TopEnd)) }
            return@BoxWithConstraints
        }
        // While arranging, and while there is more than one page, the bar has a strip of its own below the blocks: it never
        // lies over the chat's send or a grip.
        val pages = canvas.page.pages
        val strip = if (arranging || pages.size > 1) BAR else 0.dp
        // The pack's cards cast a shadow past their edge (City Pop: 7 down and right): the grid leaves room for it, so the
        // shadow of the last column and the last row is never cut by the window's edge.
        val reach = shadowReach()
        val cellW = (maxWidth - GAP * (DESKTOP_COLUMNS + 1) - reach.left - reach.right) / DESKTOP_COLUMNS
        val cellH = (maxHeight - strip - GAP * (DESKTOP_ROWS + 1) - reach.top - reach.bottom) / DESKTOP_ROWS
        fun x(c: Int) = GAP + reach.left + (cellW + GAP) * c
        fun y(r: Int) = GAP + reach.top + (cellH + GAP) * r
        fun w(n: Int) = cellW * n + GAP * (n - 1)
        fun h(n: Int) = cellH * n + GAP * (n - 1)
        val density = LocalDensity.current
        val pitch = with(density) { Offset((cellW + GAP).toPx(), (cellH + GAP).toPx()) }
        // A right-click on the page itself.
        Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e ->
            // Not one a note or sticker took (a right-click selects it).
            if (e.buttons.isSecondaryPressed && e.changes.none { it.isConsumed }) { menuOver = null; menuAt = e.changes.firstOrNull()?.position; stickersOffered = false; menuOpen = true }
        })
        for (item in canvas.items()) {
            val cell = item.cell ?: continue
            // A library widget (the dashboard window): drawn as the canvas service draws one for the car.
            if (item.kind == ItemKind.Widget && widgets != null) {
                key(item.key) {
                    Block(
                        item.key, cell, arranging, pitch, Modifier.offset(x(cell.x), y(cell.y)).size(w(cell.w), h(cell.h)),
                        onRightClick = { menuOver = item.key; stickersOffered = false; menuOpen = true },
                        onMove = { to -> canvas.apply(CanvasVerb.Move(item.key, canvas.shown, to)) },
                        onRemove = { canvas.apply(CanvasVerb.Remove(item.key)) },
                    ) {
                        // Asked again at each look ([now]): his OK in Needs you, or a file replaced, shows within one.
                        val inst = remember(item.key, now) { widgets.widget(item.key) }
                        if (inst != null) WidgetContent(inst, Modifier.fillMaxSize(), cells = cell.w to cell.h)
                        else Box(Modifier.fillMaxSize().border(1.dp, Pack.muted), contentAlignment = Alignment.Center) {
                            BasicText(widgets.whyNot(item.key), Modifier.padding(8.dp), style = Pack.mono(Pack.size.small, Pack.muted))
                        }
                    }
                }
                continue
            }
            val ref = moduleRef(item.key) ?: continue
            key(item.key) {
                Block(
                    item.key, cell, arranging, pitch,
                    Modifier.offset(x(cell.x), y(cell.y)).size(w(cell.w), h(cell.h)),
                    // The chat's own right-click is its (paste, copy): the canvas's menu is asked over a module or the page. The
                    // chat's block still takes a right-click, doing nothing, so one on its header or margins never falls through
                    // to the page beneath it (round-2 Critic).
                    onRightClick = if (ref.kind == ModuleKind.Chat) ({}) else ({ menuOver = item.key; stickersOffered = false; menuOpen = true }),
                    // On the page it is on: the chat's own, or the one shown.
                    onMove = { to -> canvas.apply(CanvasVerb.Move(item.key, if (ref.kind == ModuleKind.Chat) DESKTOP_PAGE else canvas.shown, to)) },
                    onRemove = { canvas.apply(CanvasVerb.Remove(item.key)) },
                ) {
                    if (ref.kind == ModuleKind.Chat) {
                        if (chatElsewhere != null) ChatElsewhere(chatElsewhere)
                        else Box(Modifier.fillMaxSize().semantics { contentDescription = "Chat block" }) { chat() }
                    } else {
                        CompositionLocalProvider(LocalModuleFocus provides arranging) {
                            // Measured on the columns beside the chat, as a size word is: a block as wide as that is full.
                            ModuleView(ref, moduleSize(cell.w, cell.h, canvas.rules.span(canvas.page)), source, now, Modifier.fillMaxSize(), onOpen = { onTab(it) }, onNew = { onTab(newChatTab(host)) })
                        }
                    }
                }
            }
        }
        if (scraps != null) {
            // The page's notes and stickers over its blocks, and its ink over them all, as on the phone's home (desktop slice
            // 5b): the page is the blocks' own rectangle, inside the room the pack's shadows are given at the window's edges.
            val page = canvas.showing
            val area = with(density) { IntRect(reach.left.roundToPx(), reach.top.roundToPx(), (maxWidth - reach.right).roundToPx(), (maxHeight - strip - reach.bottom).roundToPx()) }
            scraps.pageWidth = (maxWidth - reach.left - reach.right).value
            scraps.pageHeight = (maxHeight - strip - reach.top - reach.bottom).value
            scraps.gap = GAP.value
            sheet.page = page
            sheet.area = PageArea(area.left.toFloat(), area.top.toFloat(), area.width.toFloat(), area.height.toFloat())
            sheet.tall = scraps.pageHeight / scraps.pageWidth
            val ids = pages.map { it.id }.toSet()
            LaunchedEffect(scraps, ids) { scraps.pagesAre(ids) }
            LaunchedEffect(scraps, page) { scraps.book.open(page) }
            PageScraps(scraps.book, scraps.marks, page, area, constraints.maxWidth, area.bottom, scraps.arrivals, textScale = DESKTOP_TEXT_SCALE)
            InkLayer(scraps.book.on(page).strokes, area, Modifier.zIndex(1f))
            if (scraps.drawing) {
                LiveInk(scraps.marks, Modifier.zIndex(4f))
                // The drawing tools, above the bar: what a click on them does is theirs, not the pen's.
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = strip + GAP).zIndex(5f).onGloballyPositioned { sheet.tools = it.boundsInParent() }) {
                    fun write(role: NoteRole) {
                        if (!scraps.book.takes(page)) return
                        scraps.stopDrawing()
                        scraps.book.addNote(page, role = role)?.let {
                            scraps.arrivals.expect(it, null)
                            scraps.marks.editing = Chosen(page, it)
                        }
                    }
                    val sticker = { menuOver = null; menuAt = null; stickersOffered = true; menuOpen = true }
                    if (Pack.ownsPage) InkToolKeys(scraps.marks, page, 1f, ::write, sticker, scraps::stopDrawing, Modifier.semantics { contentDescription = "drawing tools" })
                    else Box(Modifier.widthIn(max = 640.dp)) { InkToolWords(scraps.marks, page, ::write, sticker, scraps::stopDrawing, Modifier.semantics { contentDescription = "drawing tools" }) }
                }
            } else {
                sheet.tools = null
            }
            scraps.book.notice?.let { said -> Unkept(said, Modifier.align(Alignment.TopStart).zIndex(5f).clickable { scraps.book.notice = null }) }
        }
        canvas.unkept?.let { why -> Unkept(why, Modifier.align(Alignment.TopEnd)) }
        if (strip > 0.dp) {
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(BAR).background(Pack.overlay).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // The pages, when there is more than one: the one shown in the accent, a click shows another. However
                // many there are, they scroll in their own part of the strip and never push the arranging controls off it.
                if (pages.size > 1) Row(Modifier.weight(1f, fill = !pagesOverview || arranging).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (p in pages) {
                        val on = p.id == canvas.shown || (pages.none { it.id == canvas.shown } && p.id == DESKTOP_PAGE)
                        BasicText(
                            p.name, Modifier.clickable { canvas.shown = p.id }.padding(end = 14.dp).semantics { contentDescription = "page ${p.name}" },
                            style = (if (on) Pack.body(Pack.size.small, Pack.accent) else Pack.body(Pack.size.small, Pack.muted)), maxLines = 1,
                        )
                    }
                    // The pages small, and the looks (desktop slice 5c), as the phone's pinch shows them.
                    if (pagesOverview) BasicText("[ pages ]", Modifier.clickable { canvas.overview = true }.semantics { contentDescription = "pages overview" }, style = Pack.body(Pack.size.small, Pack.accent), maxLines = 1)
                }
                if (pages.size <= 1 || (pagesOverview && !arranging)) Spacer(Modifier.weight(1f))
                if (pagesOverview && !arranging) BasicText(
                    if (scraps != null) "right-click: add · arrange · note · sticker · draw" else "right-click: add · arrange",
                    style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1,
                )
                if (arranging) {
                    BasicText(if (pages.size > 1) "· arranging  " else "arranging · drag a block, its corner resizes it  ", style = Pack.mono(Pack.size.small, Pack.foreground), maxLines = 1)
                    BasicText("[ add ]", Modifier.clickable { menuOver = null; menuOpen = true }, style = Pack.body(Pack.size.small, Pack.accent))
                    BasicText("  [ done ]", Modifier.clickable { arranging = false }, style = Pack.body(Pack.size.small, Pack.accent))
                }
            }
        }
    }
    if (menuOpen && stickersOffered && scraps != null) {
        // The stickers to pick from, as the phone's home menu turns into them: the one picked goes where the menu was asked.
        val page = canvas.showing
        val near = menuAt?.let { sheet.area.across(it.x) to sheet.area.down(it.y) }
        Menu(onDismiss = { menuOpen = false }) {
            MenuTitle("stick on this page")
            StickerPicker { choice, from ->
                menuOpen = false
                scraps.stopDrawing()
                scraps.book.stick(page, choice, near) { stuck ->
                    if (stuck != null) {
                        scraps.arrivals.expect(stuck, from)
                        scraps.marks.selected = Chosen(page, stuck)
                    }
                }
            }
        }
    } else if (menuOpen) {
        val over = menuOver?.let { moduleRef(it) }
        Menu(onDismiss = { menuOpen = false }) {
            if (menuOver != null && widgets != null && canvas.items().any { it.key == menuOver && it.kind == ItemKind.Widget }) {
                MenuItem("remove widget · ${menuOver!!.removeSuffix(".owl")}", Pack.red) {
                    canvas.apply(CanvasVerb.Remove(menuOver!!))
                    menuOpen = false
                }
            }
            if (over != null && over.kind != ModuleKind.Chat) {
                MenuItem("remove ${over.kind.title.lowercase()}${over.project?.let { " · $it" } ?: ""}", Pack.red) {
                    canvas.apply(CanvasVerb.Remove(menuOver!!))
                    menuOpen = false
                }
            }
            MenuItem(if (arranging) "done arranging" else "arrange") {
                arranging = !arranging
                menuOpen = false
            }
            // The pages small and the looks, from the page itself too: with one page there is no bar to find [ pages ] on.
            if (pagesOverview && menuOver == null) MenuItem("pages and looks") {
                menuOpen = false
                arranging = false
                canvas.overview = true
            }
            // On the page itself: a note, a sticker or ink, as the phone's home menu offers them, on the page shown.
            if (scraps != null && menuOver == null) {
                val page = canvas.showing
                val near = menuAt?.let { sheet.area.across(it.x) to sheet.area.down(it.y) }
                MenuItem("add a note") {
                    menuOpen = false
                    if (scraps.book.takes(page)) {
                        scraps.book.addNote(page, near)?.let {
                            scraps.arrivals.expect(it, null)
                            scraps.marks.editing = Chosen(page, it)
                        }
                    }
                }
                MenuItem("add a sticker") { if (scraps.book.takes(page)) stickersOffered = true else menuOpen = false }
                MenuItem(if (scraps.drawing) "done drawing" else "draw") {
                    menuOpen = false
                    if (scraps.drawing) scraps.stopDrawing() else {
                        scraps.marks.selected = null
                        scraps.drawing = true
                    }
                }
            }
            // The library's widgets not on the canvas yet; one he hasn't approved here waits for his OK in Needs you.
            val widgetsOffered = widgets?.names().orEmpty().filter { !canvas.has(it) }
            if (widgetsOffered.isNotEmpty()) MenuTitle("add a widget")
            for (name in widgetsOffered) {
                MenuItem(name.removeSuffix(".owl")) {
                    canvas.addWidget(name)
                    menuOpen = false
                }
            }
            val offered = addable(canvas, source.projects())
            if (offered.isNotEmpty()) MenuTitle("add a module")
            for ((key, name) in offered) {
                MenuItem(name) {
                    canvas.add(key)
                    menuOpen = false
                }
            }
        }
    }
    // A note's text, typed in over the window as on the phone.
    if (scraps != null) NoteEditor(scraps.marks)
}

/** Says the canvas isn't being kept, top right, at any width. */
@Composable
private fun Unkept(why: String, modifier: Modifier) {
    BasicText(why, modifier.padding(14.dp).background(Pack.overlay).padding(horizontal = 12.dp, vertical = 6.dp), style = Pack.mono(Pack.size.small, Pack.red))
}

/**
 * The modules that can be added: each kind not on the page yet (not the Fold's home's own, whose saved layouts aren't kept
 * here), then a Project and a Handoff module for each of [projects].
 */
fun addable(canvas: DesktopCanvas, projects: List<String>): List<Pair<String, String>> =
    (ModuleKind.entries.filter { !it.perProject && !it.chatScreen && !it.foldHome }.mapNotNull { k -> moduleKey(k)?.let { it to k.title } } +
        projects.flatMap { p -> ModuleKind.entries.filter { it.perProject }.mapNotNull { k -> moduleKey(k, p)?.let { it to "${k.title.lowercase()} · $p" } } })
        .filter { (key, _) -> !canvas.has(key) }

/**
 * One block at its cells. While [arranging], a drag moves it by whole cells ([pitch] is one cell and its gap, in pixels)
 * and its corner's grip resizes it; it lets go where it was if the new cells aren't free, which [onMove] says by doing
 * nothing. Outside arranging, what it holds has the pointer, and only a right-click is taken here.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Block(
    key: String,
    cell: Cell,
    arranging: Boolean,
    pitch: Offset,
    modifier: Modifier,
    onRightClick: (() -> Unit)?,
    onMove: (Cell) -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var grow by remember { mutableStateOf(Offset.Zero) }
    // Where the pointer went down on the block and on its grip, while neither had moved.
    var downAt by remember { mutableStateOf(Offset.Zero) }
    var growAt by remember { mutableStateOf(Offset.Zero) }
    fun cells(px: Float, unit: Float) = (px / unit).roundToInt()
    Box(
        modifier
            .graphicsLayer { translationX = drag.x; translationY = drag.y }
            .then(if (onRightClick == null) Modifier else Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e -> if (e.buttons.isSecondaryPressed && e.changes.none { it.isConsumed }) onRightClick() }),
    ) {
        content()
        if (arranging) {
            val chat = moduleRef(key)?.kind == ModuleKind.Chat
            // Over what it holds, so a drag is the block's and not a tap on a row.
            Box(
                Modifier.fillMaxSize().border(2.dp, Pack.accent).semantics { contentDescription = "arrange $key" }
                    .pointerInput(key, cell) { awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false).position } }
                    .pointerInput(key, cell) {
                        detectDragGestures(
                            onDragEnd = {
                                val to = cell.copy(x = (cell.x + cells(drag.x, pitch.x)).coerceIn(0, DESKTOP_COLUMNS - cell.w), y = (cell.y + cells(drag.y, pitch.y)).coerceIn(0, DESKTOP_ROWS - cell.h))
                                drag = Offset.Zero
                                if (to != cell) onMove(to)
                            },
                            onDragCancel = { drag = Offset.Zero },
                        ) { change, _ ->
                            change.consume()
                            // Where the pointer is now, in the block's unmoved frame, less where it went down: the block's own
                            // translation is undone first, so it follows the pointer exactly (round-3 Critic: summing each
                            // event's local amount lost the block's own motion, and it trailed at half speed).
                            drag = change.position + drag - downAt
                        }
                    },
            )
            if (!chat) {
                // On a patch of its own at the corner, over the caption rather than beside it.
                BasicText("×", Modifier.align(Alignment.TopEnd).padding(4.dp).background(Pack.background).padding(horizontal = 6.dp).clickable(onClick = onRemove).semantics { contentDescription = "remove $key" }, style = Pack.body(Pack.size.large, Pack.red))
            }
            Box(
                Modifier.align(Alignment.BottomEnd).size(GRIP).graphicsLayer { translationX = grow.x; translationY = grow.y }.background(Pack.accent).semantics { contentDescription = "resize $key" }
                    .pointerInput(key, cell) { awaitEachGesture { growAt = awaitFirstDown(requireUnconsumed = false).position } }
                    .pointerInput(key, cell) {
                        detectDragGestures(
                            onDragEnd = {
                                val to = cell.copy(w = (cell.w + cells(grow.x, pitch.x)).coerceIn(1, DESKTOP_COLUMNS - cell.x), h = (cell.h + cells(grow.y, pitch.y)).coerceIn(1, DESKTOP_ROWS - cell.y))
                                grow = Offset.Zero
                                if (to != cell) onMove(to)
                            },
                            onDragCancel = { grow = Offset.Zero },
                        ) { change, _ ->
                            change.consume()
                            grow = change.position + grow - growAt
                        }
                    },
            )
        }
    }
}
