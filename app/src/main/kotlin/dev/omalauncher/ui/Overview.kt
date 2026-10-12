package dev.omalauncher.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.GRID_COLUMNS
import dev.omalauncher.widgets.PAGE_NAME_MAX
import dev.omalauncher.widgets.PageGone
import dev.omalauncher.widgets.PageLabel
import dev.omalauncher.widgets.clipped
import dev.omalauncher.widgets.pageName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Things kept by page id, up to [maxCount] of them and [maxBytes] in all as [bytes] counts them: the page
 * stills the overview shows. Past either limit the ones put longest ago go first, and one that is over the
 * limit by itself isn't kept at all. In memory only; a page without one is drawn as a placeholder.
 */
open class CaptureCache<T : Any>(
    private val maxCount: Int,
    private val maxBytes: Long,
    private val bytes: (T) -> Long,
) {
    private val kept = mutableStateMapOf<String, T>()

    /** Oldest first. */
    private val order = ArrayList<String>()

    /** What [bytes] makes of everything kept. */
    var held = 0L
        private set

    val pages: List<String> get() = order

    operator fun get(page: String): T? = kept[page]

    fun put(
        page: String,
        image: T,
    ) {
        drop(page)
        val size = bytes(image)
        if (maxCount < 1 || size > maxBytes) return
        kept[page] = image
        order += page
        held += size
        while (order.size > maxCount || held > maxBytes) drop(order.first())
    }

    fun drop(page: String) {
        val image = kept.remove(page) ?: return
        held -= bytes(image)
        order.remove(page)
    }

    /** Lets go of everything: memory is wanted elsewhere. */
    fun clear() {
        kept.clear()
        order.clear()
        held = 0
    }
}

/** A still's longer side, in pixels: a page in the overview is a small tile. */
private const val STILL_SIDE = 480

/** At most this many stills, and this much memory for them: about a megabyte each at [STILL_SIDE]. */
private const val STILLS = 24
private const val STILL_BYTES = 16L shl 20

/**
 * The stills of the pages: each as it was drawn when one was last [request]ed while it showed, which home does
 * a moment after arriving on a page and on the way into the overview. Never saved.
 */
class PageCaptures : CaptureCache<ImageBitmap>(STILLS, STILL_BYTES, { 4L * it.width * it.height }) {
    /** Counts the requests; the pages on screen record themselves when it changes. */
    var wanted by mutableIntStateOf(0)
        private set

    fun request() {
        wanted++
    }
}

/**
 * Records this page as it is drawn whenever a still is asked for, and keeps a small copy for the overview.
 * Between requests it draws as if this weren't here: nothing is recorded and no layer is in the way.
 */
@Composable
internal fun Modifier.captured(
    page: String,
    captures: PageCaptures?,
): Modifier {
    if (captures == null) return this
    val layer = rememberGraphicsLayer()
    // The request last recorded. One made before this page showed isn't its to answer.
    val first = remember { captures.wanted }
    val recorded = remember { intArrayOf(first) }
    // Followed from an effect, not read here: a request redraws the page, it doesn't recompose it.
    LaunchedEffect(captures, page) {
        snapshotFlow { captures.wanted }.collectLatest { wanted ->
            if (wanted == first) return@collectLatest
            // A frame for the page to draw itself into the layer, and one for that to be done with.
            withFrameNanos { }
            withFrameNanos { }
            if (recorded[0] == wanted) still(layer)?.let { captures.put(page, it) }
        }
    }
    return drawWithContent {
        val now = captures.wanted
        if (now != recorded[0]) {
            recorded[0] = now
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        } else {
            drawContent()
        }
    }
}

/** What [layer] last recorded as a bitmap no longer than [STILL_SIDE]; null if it recorded nothing or it can't be read. */
private suspend fun still(layer: GraphicsLayer): ImageBitmap? =
    try {
        if (layer.size.width <= 0 || layer.size.height <= 0) {
            null
        } else {
            val full = layer.toImageBitmap().asAndroidBitmap()
            withContext(Dispatchers.Default) {
                val soft = if (full.config == Bitmap.Config.HARDWARE) full.copy(Bitmap.Config.ARGB_8888, false) else full
                val scale = minOf(1f, STILL_SIDE / maxOf(soft.width, soft.height).toFloat())
                val small = Bitmap.createScaledBitmap(soft, (soft.width * scale).roundToInt().coerceAtLeast(1), (soft.height * scale).roundToInt().coerceAtLeast(1), true)
                if (small !== soft) soft.recycle()
                if (soft !== full) full.recycle()
                small.asImageBitmap()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Out of memory, or a layer that can't be read back: the page shows as a placeholder.
        null
    }

/** A page's width over its height as the overview draws it: between the cover's 0.633 and an inner half's 0.662. */
private const val PAGE_SHAPE = 0.65f

/** What the overview is asking about a page. */
private sealed interface PageAsk {
    val page: PageLabel

    data class Menu(override val page: PageLabel) : PageAsk

    data class Rename(override val page: PageLabel) : PageAsk

    data class Delete(override val page: PageLabel) : PageAsk
}

/**
 * Every page of the notebook as a still tile, in order, over the whole window: tap one to open it (its pair,
 * unfolded), long-press for rename, move and delete, "+" for a new page at the end. A pinch outwards on a
 * page opens it too, and Back closes. The tiles are images from when each page last showed, or the outline
 * of its cells for one that never did; no widget runs for this.
 */
@Composable
fun Overview(
    runtime: Runtime,
    state: LauncherState,
) {
    if (!state.overviewOpen) return
    val engine = runtime.widgets
    val notebook = engine.notebook
    val pages = notebook.pages
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<PageAsk?>(null) }
    // What a delete did with a page's things, when that isn't what he would expect.
    var told by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    // Where each tile is, for a pinch to find the page under it.
    val bounds = remember { HashMap<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    fun open(page: String) {
        val at = notebook.position(page)
        if (at >= 0) state.requestedPage = at
        state.closeOverview()
    }

    // Back goes to the page he came from, wherever the pages were moved meanwhile.
    BackHandler { open(state.currentPage) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer {
                alpha = shown.value
                val s = 1.06f - 0.06f * shown.value
                scaleX = s
                scaleY = s
            }.background(Pack.background.copy(alpha = 0.94f))
            // Two fingers spreading on a page open it. Seen before the tiles, and taken from them once it is a pinch.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var span0 = 0f
                    var ratio = 1f
                    var over: String? = null
                    var pinching = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (pinching) event.changes.forEach { it.consume() }
                            break
                        }
                        if (pressed.size >= 2) {
                            val span = (pressed[0].position - pressed[1].position).getDistance()
                            if (span0 == 0f) {
                                span0 = span
                                val middle = origin + (pressed[0].position + pressed[1].position) / 2f
                                // Only the tiles showing: one scrolled away may have stood where another is now.
                                val showing = grid.layoutInfo.visibleItemsInfo.map { it.key }
                                over = bounds.entries.firstOrNull { it.key in showing && it.value.contains(middle) }?.key
                            }
                            ratio = spanRatio(span0, span)
                            if (kotlin.math.abs(span - span0) > viewConfiguration.touchSlop) pinching = true
                        }
                        if (pinching) event.changes.forEach { it.consume() }
                    }
                    val page = over
                    if (pinching && page != null && opensFromOverview(ratio)) open(page)
                }
            }.safeDrawingPadding()
            .padding(PageGap),
    ) {
        Column(Modifier.fillMaxSize()) {
            BasicText(
                told ?: "tap a page to open it · hold one to rename, move or delete it",
                Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                style = Pack.body(Pack.size.small, if (told != null) Pack.accent else Pack.muted),
            )
            LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(pages, key = { it.id }) { page ->
                    PageTile(
                        name = page.name,
                        current = page.id == state.currentPage,
                        image = runtime.pageStills[page.id],
                        cells = { notebook.cellsOn(page.id, emptySet()) },
                        rows = notebook.rows,
                        modifier = Modifier.onGloballyPositioned { bounds[page.id] = it.boundsInRoot() },
                        onTap = { open(page.id) },
                        onHold = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            ask = PageAsk.Menu(page)
                        },
                    )
                }
                item(key = "+") {
                    PageTile(
                        name = "new page",
                        current = false,
                        image = null,
                        cells = { emptyList() },
                        rows = notebook.rows,
                        plus = true,
                        // The new page is last, where this tile was: the list follows it there.
                        onTap = {
                            if (engine.addPage() != null) {
                                told = null
                                scope.launch { grid.animateScrollToItem(notebook.pages.lastIndex) }
                            }
                        },
                        onHold = {},
                    )
                }
            }
        }
    }

    /** Says what a delete did with the page's things, where some found no room. */
    fun tell(
        gone: PageGone,
        name: String,
    ) {
        told =
            gone.spill?.let { spill ->
                val count = gone.spilled.size
                "${if (count == 1) "1 thing" else "$count things"} from $name had no room on ${notebook.name(gone.to.orEmpty())}: ${if (count == 1) "it is" else "they are"} on a new page, ${notebook.name(spill)}"
            }
    }

    fun delete(
        page: PageLabel,
        move: Boolean,
    ) {
        ask = null
        val gone = engine.deletePage(page.id, move) ?: return
        runtime.pageStills.drop(page.id)
        bounds.remove(page.id)
        // The page things moved to no longer looks like its still.
        gone.to?.takeIf { gone.moved.isNotEmpty() }?.let(runtime.pageStills::drop)
        tell(gone, page.name)
    }

    when (val asked = ask) {
        null -> {}
        is PageAsk.Menu -> {
            val at = notebook.position(asked.page.id)
            Menu(onDismiss = { ask = null }) {
                MenuTitle(asked.page.name)
                MenuItem("rename") { ask = PageAsk.Rename(asked.page) }
                if (at > 0) {
                    MenuItem("move earlier") {
                        notebook.movePage(asked.page.id, -1)
                        ask = null
                    }
                }
                if (at in 0 until pages.lastIndex) {
                    MenuItem("move later") {
                        notebook.movePage(asked.page.id, 1)
                        ask = null
                    }
                }
                // The only page stays.
                if (notebook.canDelete(asked.page.id)) {
                    MenuItem("delete", Pack.red) {
                        // An empty page has nothing to ask about.
                        if (engine.itemsOn(asked.page.id).isEmpty()) delete(asked.page, move = true) else ask = PageAsk.Delete(asked.page)
                    }
                }
            }
        }
        is PageAsk.Rename ->
            RenamePage(
                page = asked.page,
                taken = { notebook.nameTaken(it, except = asked.page.id) },
                onSave = {
                    if (notebook.renamePage(asked.page.id, it)) ask = null
                },
                onDismiss = { ask = null },
            )
        is PageAsk.Delete -> {
            val at = notebook.position(asked.page.id)
            val count = engine.itemsOn(asked.page.id).size
            val things = if (count == 1) "1 thing" else "$count things"
            val neighbour = pages.getOrNull(if (at == 0) 1 else at - 1)?.name.orEmpty()
            // Removing takes a second tap: it is the one choice here that can't be taken back from home.
            var sure by remember(asked) { mutableStateOf(false) }
            // What is drawn or stuck on the page is the page's: it is read here in case the page never showed.
            val book = state.marks.book
            LaunchedEffect(asked, book) { book?.open(asked.page.id) }
            Menu(onDismiss = { ask = null }) {
                MenuTitle("${asked.page.name} has $things on it")
                if (book?.on(asked.page.id)?.isEmpty == false) MenuTitle("what is written or stuck on the page goes with it")
                MenuItem("move ${if (count == 1) "it" else "them"} to $neighbour") { delete(asked.page, move = true) }
                if (notebook.kept) {
                    MenuItem(if (sure) "tap again to remove $things from home" else "remove ${if (count == 1) "it" else "them"} from home", Pack.red) {
                        if (sure) delete(asked.page, move = false) else sure = true
                    }
                } else {
                    MenuTitle("this session isn't being saved, so nothing can be removed in it")
                }
                MenuItem("keep the page") { ask = null }
            }
        }
    }
}

/** One page in the overview: its still, or its cells in outline, under the pack's scrim, and its name below. */
@Composable
private fun PageTile(
    name: String,
    current: Boolean,
    image: ImageBitmap?,
    cells: () -> List<Cell>,
    rows: Int,
    onTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    plus: Boolean = false,
) {
    val scrim = Pack.look.pack.page.overviewScrim.toColor()
    Column(modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_SHAPE)
                .framed(if (current) Pack.mark else Pack.hair, if (current) Pack.accent else Pack.border, Pack.surface)
                .combinedClickable(onClick = onTap, onLongClick = onHold),
            contentAlignment = Alignment.Center,
        ) {
            when {
                plus -> BasicText("+", style = Pack.display(Pack.size.title, Pack.muted))
                image != null -> Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else -> {
                    val line = Pack.muted
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width / GRID_COLUMNS
                        val h = size.height / rows.coerceAtLeast(1)
                        val inset = 2.dp.toPx()
                        for (c in cells()) {
                            drawRect(
                                line,
                                topLeft = Offset(c.x * w + inset, c.y * h + inset),
                                size = Size((c.w * w - 2 * inset).coerceAtLeast(1f), (c.h * h - 2 * inset).coerceAtLeast(1f)),
                                style = Stroke(1.dp.toPx()),
                            )
                        }
                    }
                }
            }
            if (!plus) Box(Modifier.fillMaxSize().background(scrim))
        }
        BasicText(
            name,
            Modifier.padding(top = 4.dp),
            style = Pack.body(Pack.size.small, if (current) Pack.accent else Pack.foreground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A page's name in a field. A name with nothing in it can't be saved; one another page has can, with a word
 * about why that is awkward. No longer than [PAGE_NAME_MAX].
 */
@Composable
private fun RenamePage(
    page: PageLabel,
    taken: (String) -> Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember(page.id) { mutableStateOf(TextFieldValue(page.name, TextRange(0, page.name.length))) }
    val name = pageName(field.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Menu(onDismiss = onDismiss) {
        MenuTitle("rename ${page.name}")
        BasicTextField(
            value = field,
            // Never longer than a name can be: what is typed or pasted past that is cut off.
            onValueChange = { typed ->
                val text = clipped(typed.text)
                field = if (text == typed.text) typed else TextFieldValue(text, TextRange(typed.selection.end.coerceAtMost(text.length)))
            },
            singleLine = true,
            textStyle = Pack.body(Pack.size.item),
            cursorBrush = SolidColor(Pack.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name != null) onSave(field.text) }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .focusRequester(focus)
                    .framed(Pack.hair, Pack.border, Pack.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        val note =
            when {
                name == null -> "a page needs a name"
                taken(name) -> "another page is called this: asked for by name, Oma can't tell them apart"
                else -> null
            }
        if (note != null) {
            BasicText(note, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = Pack.body(Pack.size.small, if (name == null) Pack.red else Pack.accent))
        }
        if (name != null) MenuItem("save") { onSave(field.text) }
        MenuItem("cancel", onClick = onDismiss)
    }
}
