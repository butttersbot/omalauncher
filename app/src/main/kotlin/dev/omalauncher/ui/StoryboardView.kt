package dev.omalauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.art.ArtImage
import dev.omalauncher.canvas.MangaPrefs
import dev.omalauncher.canvas.Storyboard
import dev.omalauncher.canvas.TrayChip
import dev.omalauncher.canvas.TrayTab
import dev.omalauncher.theme.pack.PackPanels
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.Panel
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.PanelPoint
import dev.omalauncher.widgets.layout.artIdOf
import dev.omalauncher.widgets.panelAt
import kotlin.math.roundToInt

/*
 * The Storyboard (ネーム) as it is drawn (Manga look round 4; features/manga-look/manga-mockup.html, version 4): over the
 * overview, opened from its upper-right corner. Pages in a strip, the page being set up large with its panels, the panel
 * layouts, the tray of widgets and art, and the saved layouts. What each tap or drop does is canvas/Storyboard.kt's.
 */

/** A page as the mockup draws it: 60 across, 96 down. */
private const val SHEET = 60f / 96f

/** Wider than this, the Storyboard is two columns, as the unfolded Fold's spread. */
private val SPREAD = 600.dp

/** Something carried: its [key], what it is called, and where the finger is, in the root's space. */
@Stable
private class BoardCarried(val key: String, val title: String, start: Offset) {
    var at by mutableStateOf(start)
}

/** Where a carried thing would land: a panel of the page being set up, a page in the strip, or the tray. */
private sealed interface BoardLanding {
    data class OnPanel(val panel: Int) : BoardLanding

    data class OnPage(val page: String) : BoardLanding

    data object OnTray : BoardLanding
}

/** Where the drop targets are, in the Storyboard's own space, as they were last laid out. */
private class BoardTargets {
    /** The Storyboard's own layout: every place here is measured in it, whatever layers it is drawn through. */
    var space: LayoutCoordinates? = null
    var big = Rect.Zero
    var tray = Rect.Zero
    val pages = HashMap<String, Rect>()
}

/**
 * The Storyboard over the whole window. [panels] is the look's `[panels]` (ink, screentone). [onDone] leaves to home on the
 * page set up last; [onBack] goes back to the overview.
 */
@Composable
internal fun StoryboardView(
    board: Storyboard,
    panels: PackPanels,
    onDone: (String?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { if (board.trial != null) board.back() else onBack() }
    val haptics = LocalHapticFeedback.current
    // Read as it is drawn (home's own state), so a change made here or by an agent meanwhile shows at once.
    board.changes
    val n = board.notebook()
    val targets = remember { BoardTargets() }
    var carried by remember { mutableStateOf<BoardCarried?>(null) }
    val ink = Pack.foreground

    // Home as it is when asked, never as it was drawn: a drag's handler outlives the drawing it started from (critic: a chip
    // dragged after "Use this" landed by the old panels).
    fun landingAt(at: Offset): BoardLanding? {
        val now = board.notebook() ?: return null
        targets.pages.entries.firstOrNull { it.value.contains(at) }?.let { return BoardLanding.OnPage(it.key) }
        if (targets.tray.contains(at)) return BoardLanding.OnTray
        if (targets.big.contains(at) && board.trial == null) {
            val p = now.pages.firstOrNull { it.id == board.page } ?: return null
            val cut = board.panelsOf(now, p) ?: return null
            val i = panelAt(cut, now.columns, now.rows, ((at.x - targets.big.left) / targets.big.width).toDouble(), ((at.y - targets.big.top) / targets.big.height).toDouble())
            if (i >= 0) return BoardLanding.OnPanel(i)
        }
        return null
    }

    fun drop(c: BoardCarried) {
        val page = board.page
        when (val l = landingAt(c.at)) {
            is BoardLanding.OnPanel -> if (page != null) board.place(c.key, page, l.panel)
            is BoardLanding.OnPage -> board.send(c.key, l.page)
            BoardLanding.OnTray -> board.takeOff(c.key)
            null -> {}
        }
    }

    // Long-press lifts, as on home; the drop lands where the finger is.
    val lift: (String, String, Offset) -> Unit = { key, title, at ->
        if (board.canMove()) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            carried = BoardCarried(key, title, at)
        }
    }
    val carry = BoardCarry(
        lift = lift,
        move = { at -> carried?.let { it.at = at } },
        end = { carried?.let(::drop); carried = null },
        cancel = { carried = null },
    )

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { targets.space = it }
            .background(Pack.paper)
            // Nothing under it takes a tap: the overview's "tap nothing to leave" stays behind.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { contentDescription = "Storyboard" },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(board, onDone)
            if (n == null) {
                BasicText("Home is still loading.", Modifier.padding(16.dp), style = Pack.body(color = Pack.muted))
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val over = carried?.let { landingAt(it.at) }
                    val spread = maxWidth >= SPREAD
                    // One column (folded): the page a little smaller, so the tray below it is in reach of a drag.
                    val left = @Composable { PagesColumn(board, n, panels, targets, carried, over, carry, if (spread) 300.dp else 250.dp) }
                    val right = @Composable { ToolsColumn(board, n, targets, over is BoardLanding.OnTray, carried, carry) }
                    if (spread) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { left() }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { right() }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            left()
                            right()
                        }
                    }
                }
            }
            Said(board)
        }
        carried?.let { c ->
            // The ghost: what is carried, in ink, a little tilted, beside the finger.
            BasicText(
                Pack.caption(c.title),
                Modifier
                    .offset { IntOffset((c.at.x + 24).roundToInt(), (c.at.y - 48).roundToInt()) }
                    .graphicsLayer { rotationZ = -3f }
                    .background(ink)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                style = Pack.mono(11.sp, Pack.paper),
                maxLines = 1,
            )
        }
    }
}

/** Tells [onRect] where this is in the Storyboard's space ([targets]) each time it is laid out. */
private fun Modifier.within(
    targets: BoardTargets,
    onRect: (Rect) -> Unit,
): Modifier =
    onGloballyPositioned { c ->
        val space = targets.space
        if (space != null && space.isAttached && c.isAttached) onRect(space.localBoundingBoxOf(c))
    }

/** The overview's way into the Storyboard, in its upper-right corner: a box of ink, as the Storyboard's own buttons. */
@Composable
internal fun StoryboardButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .background(Pack.paper)
            .border(1.dp, Pack.foreground)
            .clickable(onClickLabel = "set up pages", onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "Storyboard: set up every page" }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Storyboard", style = Pack.body(12.sp), maxLines = 1)
        BasicText("ネーム", Modifier.padding(start = 6.dp), style = Pack.mono(9.sp, Pack.muted), maxLines = 1)
    }
}

/** How a long-press carries something: lifted with its key, its name and the finger's place in the root; moved; let go. */
private class BoardCarry(
    val lift: (String, String, Offset) -> Unit,
    /** The finger is at this place now. */
    val move: (Offset) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
)

/**
 * Long-press on this lifts what [what] says is under the finger (key and name, or null for nothing), given where the finger is
 * here and this one's size, and carries it. The finger is followed through this one's own layout into the Storyboard's
 * space ([targets]), so a layer scaling the overview as it appears never puts the drop somewhere the finger isn't.
 */
private fun Modifier.carries(
    carry: BoardCarry,
    targets: BoardTargets,
    vararg keys: Any?,
    what: (Offset, androidx.compose.ui.unit.IntSize) -> Pair<String, String>?,
): Modifier {
    val here = arrayOfNulls<LayoutCoordinates>(1)
    fun place(local: Offset): Offset? {
        val space = targets.space ?: return null
        val mine = here[0] ?: return null
        return if (space.isAttached && mine.isAttached) space.localPositionOf(mine, local) else null
    }
    return onGloballyPositioned { here[0] = it }.pointerInput(*keys) {
        detectDragGesturesAfterLongPress(
            onDragStart = { local -> what(local, size)?.let { (key, title) -> place(local)?.let { carry.lift(key, title, it) } } },
            onDrag = { change, _ ->
                change.consume()
                place(change.position)?.let(carry.move)
            },
            onDragEnd = carry.end,
            onDragCancel = carry.cancel,
        )
    }
}

@Composable
private fun Header(
    board: Storyboard,
    onDone: (String?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(Pack.foreground, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Storyboard", style = Pack.display(20.sp), maxLines = 1)
            BasicText("ネーム", Modifier.padding(start = 8.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1)
        }
        InkButton("Art in ink", on = MangaPrefs.artInk, toggle = true) { MangaPrefs.artInk = !MangaPrefs.artInk }
        InkButton("Done", solid = true) { onDone(board.page) }
    }
}

/** The page strip and the page being set up, large. */
@Composable
private fun PagesColumn(
    board: Storyboard,
    n: Notebook,
    look: PackPanels,
    targets: BoardTargets,
    carried: BoardCarried?,
    over: BoardLanding?,
    carry: BoardCarry,
    most: androidx.compose.ui.unit.Dp,
) {
    BoardLabel("Pages · drop a panel on one to send it there")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        n.pages.forEachIndexed { i, p ->
            val current = p.id == board.page
            Column(
                Modifier
                    .width(44.dp)
                    .within(targets) { targets.pages[p.id] = it }
                    .clickable(onClickLabel = "set up ${p.name}") { board.pick(p.id) }
                    .semantics { selected = current; contentDescription = "${p.name}, page ${i + 1}" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Thumb(board, n, p, current, dropping = carried != null && over == BoardLanding.OnPage(p.id))
                BasicText("${i + 1}", Modifier.padding(top = 4.dp), style = Pack.mono(9.sp, if (current) Pack.accent else Pack.muted), maxLines = 1)
            }
        }
        // A new page, cut as a 4-koma strip, and the one set up next.
        Box(
            Modifier
                .width(44.dp)
                .aspectRatio(SHEET)
                .dashed(Pack.foreground)
                .clickable(onClickLabel = "add a page") { board.addPage() }
                .semantics { contentDescription = "Add a page" },
            contentAlignment = Alignment.Center,
        ) { BasicText("+", style = Pack.display(18.sp)) }
    }
    // Pages gone since they were laid out are no target.
    targets.pages.keys.retainAll(n.pages.map { it.id }.toSet())
    val page = n.pages.firstOrNull { it.id == board.page } ?: return
    val trial = board.trial?.takeIf { it.page == page.id }
    BigPage(board, n, page, trial?.shown, look, targets, carried, (over as? BoardLanding.OnPanel)?.panel, carry, most)
}

/** A page in the strip: its panels, each filled as what holds it (art toned, a widget plain, an empty panel grey). */
@Composable
private fun Thumb(
    board: Storyboard,
    n: Notebook,
    p: NotebookPage,
    current: Boolean,
    dropping: Boolean,
) {
    val ink = Pack.foreground
    val paper = Pack.paper
    val accent = Pack.accent
    val cut = board.panelsOf(n, p)
    val held = if (cut == null) emptyList() else board.occupants(n, p)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(SHEET)
            .then(
                when {
                    dropping -> Modifier.dashed(accent, 2.dp, 2.dp)
                    current -> Modifier.border(2.dp, accent)
                    else -> Modifier
                },
            ).padding(if (current || dropping) 3.dp else 0.dp),
    ) {
        drawRect(paper)
        drawRect(ink.copy(alpha = 0.25f), style = Stroke(1f))
        if (cut == null) {
            // A free page: its things' cells, as the overview outlines a page it has no picture of.
            val w = size.width / n.columns
            val h = size.height / n.rows
            for (item in p.items) item.cell?.let { c -> drawRect(ink.copy(alpha = 0.5f), Offset(c.x * w + 1, c.y * h + 1), Size(c.w * w - 2, c.h * h - 2), style = Stroke(1f)) }
        } else {
            cut.forEachIndexed { i, panel ->
                val path = outline(panel.outline, size)
                drawPath(path, fillFor(held.getOrNull(i), ink, paper))
                drawPath(path, ink, style = Stroke(0.8.dp.toPx()))
            }
        }
    }
}

private fun fillFor(
    item: NotebookItem?,
    ink: Color,
    paper: Color,
): Color =
    when {
        item == null -> ink.copy(alpha = 0.1f)
        item.kind == ItemKind.Art -> ink.copy(alpha = 0.35f)
        else -> paper
    }

/** A polygon given in fractions of [size]. */
private fun outline(
    pts: List<PanelPoint>,
    size: Size,
): Path =
    Path().apply {
        pts.forEachIndexed { i, q ->
            val x = (q.x * size.width).toFloat()
            val y = (q.y * size.height).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

/** A panel's ink, in fractions of the panel's own box (from [box], in fractions of the page), for clipping what it holds. */
private class PanelShape(
    private val pts: List<PanelPoint>,
    private val box: androidx.compose.ui.geometry.Rect,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Generic(
            outline(pts.map { PanelPoint((it.x - box.left) / box.width, (it.y - box.top) / box.height) }, size),
        )
}

/** The bounding box of [pts], in the fractions they are given in. */
private fun boxOf(pts: List<PanelPoint>): androidx.compose.ui.geometry.Rect =
    androidx.compose.ui.geometry.Rect(pts.minOf { it.x }.toFloat(), pts.minOf { it.y }.toFloat(), pts.maxOf { it.x }.toFloat(), pts.maxOf { it.y }.toFloat())

/**
 * The page being set up, large: its panels with what each holds (art printed as home prints it, a widget by its name, an empty
 * panel in screentone with a "+"). With a layout being tried ([tried]) it shows that page as it would be, and takes no taps.
 * A tap on a panel is the Storyboard's; a long-press lifts what is in it.
 */
@Composable
private fun BigPage(
    board: Storyboard,
    n: Notebook,
    page: NotebookPage,
    tried: NotebookPage?,
    look: PackPanels,
    targets: BoardTargets,
    carried: BoardCarried?,
    aimed: Int?,
    carry: BoardCarry,
    most: androidx.compose.ui.unit.Dp,
) {
    val shown = tried ?: page
    val cut = board.panelsOf(n, shown)
    val held = if (cut == null) emptyList() else board.occupants(n, shown)
    val ink = Pack.foreground
    val accent = Pack.accent
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val width = minOf(maxWidth, most)
        Box(
            Modifier
                .width(width)
                .aspectRatio(SHEET)
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.08f), Offset(5.dp.toPx(), 5.dp.toPx()), size) }
                .background(Pack.paper)
                .border(1.dp, ink)
                .within(targets) { targets.big = it }
                .semantics { contentDescription = "${page.name}, " + (shown.panels?.let { PanelLayouts.name(it) } ?: "the free grid") }
                .pointerInput(page.id, cut, tried != null) {
                    detectTapGestures { at ->
                        if (cut == null) return@detectTapGestures
                        val i = panelAt(cut, n.columns, n.rows, (at.x / size.width).toDouble(), (at.y / size.height).toDouble())
                        if (i >= 0) board.tapPanel(i)
                    }
                }.carries(carry, targets, page.id, cut, held, tried != null) { at, size ->
                    if (cut == null) return@carries null
                    val i = panelAt(cut, n.columns, n.rows, (at.x / size.width).toDouble(), (at.y / size.height).toDouble())
                    held.getOrNull(i)?.let { it.key to board.title(it) }
                },
        ) {
            if (cut == null) {
                BasicText(
                    "The free grid: pick a panel layout for this page.",
                    Modifier.align(Alignment.Center).padding(20.dp),
                    style = Pack.body(Pack.size.small, Pack.muted),
                )
            } else {
                val sizePx = with(density) { Size(width.toPx(), (width / SHEET).toPx()) }
                cut.forEachIndexed { i, panel ->
                    val item = held.getOrNull(i)
                    val box = boxOf(panel.outline)
                    val lifted = carried != null && item?.key == carried.key
                    Box(
                        Modifier
                            .offset { IntOffset((box.left * sizePx.width).roundToInt(), (box.top * sizePx.height).roundToInt()) }
                            .size(with(density) { (box.width * sizePx.width).toDp() }, with(density) { (box.height * sizePx.height).toDp() })
                            .clip(PanelShape(panel.outline, box))
                            .alpha(if (lifted) 0.25f else 1f)
                            .semantics { role = Role.Button; contentDescription = (item?.let(board::title) ?: "Empty panel") + ", panel ${i + 1}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        PanelInside(board, item, look)
                        when {
                            aimed == i -> Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.16f)))
                            board.armed != null && tried == null -> Box(Modifier.fillMaxSize().background(ink.copy(alpha = 0.08f)))
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    for (panel in cut) drawPath(outline(panel.outline, size), ink, style = Stroke(look.inkWidth.dp.toPx()))
                    aimed?.let { i -> cut.getOrNull(i)?.let { drawPath(outline(it.outline, size), accent, style = Stroke(2.dp.toPx())) } }
                }
            }
        }
    }
}

/** What a panel holds, as the Storyboard shows it. */
@Composable
private fun PanelInside(
    board: Storyboard,
    item: NotebookItem?,
    look: PackPanels,
) {
    val art = item?.let { artIdOf(it.key) }
    when {
        art != null -> ArtImage(art, Modifier.fillMaxSize(), ink = MangaPrefs.artInk)
        item != null -> Tag(board.title(item))
        else -> {
            Screentone(look.screentone, Pack.foreground, lifted = false, modifier = Modifier.fillMaxSize())
            BasicText("+", style = Pack.display(18.sp, Pack.muted))
        }
    }
}

/** A widget's name in a panel: small capitals in a box of ink, as the mockup letters it. */
@Composable
private fun Tag(title: String) {
    BasicText(
        Pack.caption(title),
        Modifier
            .widthIn(max = 160.dp)
            .background(Pack.paper)
            .border(1.dp, Pack.foreground)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        style = Pack.mono(9.5.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The panel layouts, the tray, and the saved layouts. */
@Composable
private fun ToolsColumn(
    board: Storyboard,
    n: Notebook,
    targets: BoardTargets,
    trayAimed: Boolean,
    carried: BoardCarried?,
    carry: BoardCarry,
) {
    val page = n.pages.firstOrNull { it.id == board.page }
    val trial = board.trial
    BoardLabel("Panel layout · ${page?.name.orEmpty()}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (layout in PanelLayouts.all) {
            val fits = PanelLayouts.fits(layout.id, n.columns, n.rows)
            val trying = trial?.layout == layout.id
            val own = page?.panels == layout.id
            val cut = remember(layout.id, n.columns, n.rows) { PanelLayouts.panels(layout.id, n.columns, n.rows) }
            val ink = Pack.foreground
            val paper = Pack.paper
            Box(
                Modifier
                    .weight(1f)
                    .border(if (trying) 1.5.dp else 1.dp, if (trying) Pack.accent else if (own) ink else ink.copy(alpha = 0.25f))
                    .alpha(if (fits) 1f else 0.3f)
                    .clickable(enabled = fits && page != null, onClickLabel = "try ${layout.name}") { board.tryLayout(layout.id) }
                    .semantics { contentDescription = layout.name + if (own) ", this page's layout" else "" }
                    .padding(3.dp),
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(SHEET)) {
                    drawRect(ink.copy(alpha = 0.06f))
                    for (p in cut) {
                        val path = outline(p.outline, size)
                        drawPath(path, paper)
                        drawPath(path, ink, style = Stroke(1.dp.toPx()))
                    }
                }
            }
        }
    }
    trial?.let { t ->
        Column(Modifier.fillMaxWidth().border(1.dp, Pack.accent).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val where = n.pages.firstOrNull { it.id == t.page }?.name.orEmpty()
            val left = if (t.left.isEmpty()) " Everything fits." else " No panel left here, so " + t.left.joinToString("; ") + "."
            BasicText("Trying ${PanelLayouts.name(t.layout)} on \"$where\".$left", style = Pack.body(Pack.size.small))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Use this", solid = true) { board.useTrial() }
                InkButton("Back") { board.back() }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InkButton("Widgets", on = board.tab == TrayTab.Widgets) { board.tab = TrayTab.Widgets }
        InkButton("Art", on = board.tab == TrayTab.Art) { board.tab = TrayTab.Art }
    }
    val chips = board.chips(board.tab)
    val numbers = n.pages.mapIndexed { i, p -> p.id to i + 1 }.toMap()
    FlowRow(
        Modifier
            .fillMaxWidth()
            .within(targets) { targets.tray = it }
            .then(if (trayAimed) Modifier.background(Pack.accent.copy(alpha = 0.1f)).dashed(Pack.accent) else Modifier)
            .padding(4.dp)
            .semantics { contentDescription = "The tray: drag one onto a panel, or a panel here to take it off" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (chips.isEmpty()) {
            BasicText(if (board.tab == TrayTab.Art) "No pictures yet: share one to Soma (\"Add to a panel\"), or tap an empty panel on home." else "Nothing to add.", style = Pack.body(Pack.size.small, Pack.muted))
        }
        for (chip in chips) Chip(board, chip, chip.page?.let { numbers[it] }, lifted = carried?.key == chip.key, carry = carry, targets = targets)
    }
    BoardLabel("Saved layouts")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in board.saved()) InkButton(l.name, on = board.confirm == l.name) { board.tapSaved(l.name) }
        InkButton("Save this", solid = true) { board.save() }
    }
}

/** A thing in the tray: a picture's print or a widget's name, and the page it is on, dashed when it is on one. */
@Composable
private fun Chip(
    board: Storyboard,
    chip: TrayChip,
    number: Int?,
    lifted: Boolean,
    carry: BoardCarry,
    targets: BoardTargets,
) {
    val armed = board.armed == chip.key
    val ink = Pack.foreground
    val paper = Pack.paper
    val art = artIdOf(chip.key)
    Row(
        Modifier
            .alpha(if (lifted) 0.3f else 1f)
            .background(if (armed) ink else paper)
            .then(if (chip.page != null) Modifier.dashed(ink) else Modifier.border(1.dp, ink))
            .clickable(onClickLabel = "pick ${chip.title}") { board.arm(chip.key) }
            .carries(carry, targets, chip.key) { _, _ -> chip.key to (if (art != null) "picture" else chip.title) }
            .semantics { selected = armed; contentDescription = chip.title + (number?.let { ", on page $it" } ?: ", in the tray") }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (armed) paper else ink
        if (art != null) {
            ArtImage(art, Modifier.size(40.dp, 27.dp), ink = MangaPrefs.artInk)
        } else {
            BasicText(Pack.caption(chip.title), Modifier.widthIn(max = 150.dp), style = Pack.mono(10.5.sp, color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        number?.let { BasicText("p$it", style = Pack.mono(9.sp, color.copy(alpha = 0.6f)), maxLines = 1) }
    }
}

/** What the last tap came to, with Undo after a load until home changes here again; a tap puts the words away. */
@Composable
private fun Said(board: Storyboard) {
    val said = board.said
    val undo = board.undoable
    if (said == null && undo == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(Pack.foreground, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .clickable(onClickLabel = "dismiss") { board.dismiss() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(said?.text ?: "Undo puts back home from before the layout was loaded.", Modifier.weight(1f), style = Pack.body(Pack.size.small, if (said?.ok != false) Pack.foreground else Pack.accent), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (undo != null) {
            Spacer(Modifier.width(8.dp))
            InkButton("Undo") { board.undo() }
        }
    }
}

@Composable
private fun BoardLabel(text: String) {
    BasicText(Pack.caption(text), style = Pack.mono(9.5.sp, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The Storyboard's button: a box of ink; [solid] (or [on]) filled with it. */
@Composable
private fun InkButton(
    label: String,
    solid: Boolean = false,
    on: Boolean = false,
    toggle: Boolean = false,
    onClick: () -> Unit,
) {
    val filled = solid || on
    BasicText(
        label,
        Modifier
            .background(if (filled) Pack.foreground else Pack.paper)
            .border(1.dp, Pack.foreground)
            .clickable(onClick = onClick)
            .semantics {
                role = if (toggle) Role.Switch else Role.Button
                if (toggle) selected = on
            }.padding(horizontal = 10.dp, vertical = 7.dp),
        style = Pack.body(12.sp, if (filled) Pack.paper else Pack.foreground),
        maxLines = 1,
    )
}

/** A dashed line of [color] around this. */
private fun Modifier.dashed(
    color: Color,
    width: androidx.compose.ui.unit.Dp = 1.dp,
    inset: androidx.compose.ui.unit.Dp = 0.dp,
): Modifier =
    drawBehind { dashedRect(color, width.toPx(), -inset.toPx()) }

private fun DrawScope.dashedRect(
    color: Color,
    w: Float,
    inset: Float,
) {
    drawRect(
        color,
        Offset(inset + w / 2, inset + w / 2),
        Size(size.width - 2 * inset - w, size.height - 2 * inset - w),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * w, 3 * w))),
    )
}
