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
import dev.omalauncher.oma.newChatTab
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.ui.Menu
import dev.omalauncher.ui.MenuItem
import dev.omalauncher.ui.MenuTitle
import dev.omalauncher.ui.Pack
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
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
 * The window as a canvas (slice 5): [canvas]'s page, its chat block drawing the chat on [host] and every other block a
 * module from [source], at the size its cells give it. A tap on a module opens that thread's tab in the chat block; a
 * right-click offers to add a module, remove one or arrange; while arranging, a block is moved by dragging it and resized
 * by its corner, and every change is a verb in the canvas's log. [now] changes when the modules may show something new.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CanvasScreen(
    canvas: DesktopCanvas,
    host: ChatHost,
    source: ModuleSource,
    now: Long,
    wanted: String?,
    onTab: (String?) -> Unit,
) {
    var arranging by remember { mutableStateOf(false) }
    // The right-click menu, and the block it was asked over (null: the page).
    var menuOver by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = arranging && !menuOpen) { arranging = false }
    // Escape closes the menu first, as the chat's own menus do, before anything else takes it.
    BackHandler(enabled = menuOpen) { menuOpen = false }
    // One chat, moved between the canvas and the chat-alone window rather than made again: what he was typing, the open
    // tab strip and the scroll go with it when the window narrows or widens.
    val wantedNow by rememberUpdatedState(wanted)
    val onTabNow by rememberUpdatedState(onTab)
    val chat = remember(host) { movableContentOf { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wantedNow, onTab = { onTabNow(it.takeIf { tab -> tab != OMA_TAB }) }) } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Pack.background)) {
        if (maxWidth < CANVAS_ROOM) {
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
            if (e.buttons.isSecondaryPressed) { menuOver = null; menuOpen = true }
        })
        for (item in canvas.items()) {
            val cell = item.cell ?: continue
            val ref = moduleRef(item.key) ?: continue
            key(item.key) {
                Block(
                    item.key, cell, arranging, pitch,
                    Modifier.offset(x(cell.x), y(cell.y)).size(w(cell.w), h(cell.h)),
                    // The chat's own right-click is its (paste, copy): the canvas's menu is asked over a module or the page. The
                    // chat's block still takes a right-click, doing nothing, so one on its header or margins never falls through
                    // to the page beneath it (round-2 Critic).
                    onRightClick = if (ref.kind == ModuleKind.Chat) ({}) else ({ menuOver = item.key; menuOpen = true }),
                    // On the page it is on: the chat's own, or the one shown.
                    onMove = { to -> canvas.apply(CanvasVerb.Move(item.key, if (ref.kind == ModuleKind.Chat) DESKTOP_PAGE else canvas.shown, to)) },
                    onRemove = { canvas.apply(CanvasVerb.Remove(item.key)) },
                ) {
                    if (ref.kind == ModuleKind.Chat) {
                        Box(Modifier.fillMaxSize().semantics { contentDescription = "Chat block" }) { chat() }
                    } else {
                        CompositionLocalProvider(LocalModuleFocus provides arranging) {
                            // Measured on the columns beside the chat, as a size word is: a block as wide as that is full.
                            ModuleView(ref, moduleSize(cell.w, cell.h, canvas.rules.span(canvas.page)), source, now, Modifier.fillMaxSize(), onOpen = { onTab(it) }, onNew = { onTab(newChatTab(host)) })
                        }
                    }
                }
            }
        }
        canvas.unkept?.let { why -> Unkept(why, Modifier.align(Alignment.TopEnd)) }
        if (strip > 0.dp) {
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(BAR).background(Pack.overlay).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // The pages, when there is more than one: the one shown in the accent, a click shows another. However
                // many there are, they scroll in their own part of the strip and never push the arranging controls off it.
                if (pages.size > 1) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { for (p in pages) {
                    val on = p.id == canvas.shown || (pages.none { it.id == canvas.shown } && p.id == DESKTOP_PAGE)
                    BasicText(
                        p.name, Modifier.clickable { canvas.shown = p.id }.padding(end = 14.dp).semantics { contentDescription = "page ${p.name}" },
                        style = (if (on) Pack.body(Pack.size.small, Pack.accent) else Pack.body(Pack.size.small, Pack.muted)), maxLines = 1,
                    )
                } } else Spacer(Modifier.weight(1f))
                if (arranging) {
                    BasicText(if (pages.size > 1) "· arranging  " else "arranging · drag a block, its corner resizes it  ", style = Pack.mono(Pack.size.small, Pack.foreground), maxLines = 1)
                    BasicText("[ add ]", Modifier.clickable { menuOver = null; menuOpen = true }, style = Pack.body(Pack.size.small, Pack.accent))
                    BasicText("  [ done ]", Modifier.clickable { arranging = false }, style = Pack.body(Pack.size.small, Pack.accent))
                }
            }
        }
    }
    if (menuOpen) {
        val over = menuOver?.let { moduleRef(it) }
        Menu(onDismiss = { menuOpen = false }) {
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
            .then(if (onRightClick == null) Modifier else Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e -> if (e.buttons.isSecondaryPressed) onRightClick() }),
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
