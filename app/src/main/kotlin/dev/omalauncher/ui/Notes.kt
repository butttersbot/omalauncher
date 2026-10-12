package dev.omalauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke as Outline
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.AnnotationLimits
import dev.omalauncher.widgets.annotations.Author
import dev.omalauncher.widgets.annotations.NoteRole
import dev.omalauncher.widgets.annotations.PageArea
import dev.omalauncher.widgets.annotations.Scrap
import dev.omalauncher.widgets.annotations.StickyNote
import dev.omalauncher.widgets.annotations.TURN_STEP
import dev.omalauncher.widgets.annotations.noteText
import dev.omalauncher.widgets.annotations.scrap
import dev.omalauncher.widgets.annotations.scrapAt
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * Sticky notes and stickers on a home page: floating over the tiles, in no cell, and never pushing a tile
 * (PRD AC-23). A tap on a note opens its text; a long press lifts it to drag, like a tile, to another page too, and a long press
 * that doesn't move selects it, with a handle to resize it by and a bar to edit or delete it. Whose a touch
 * is, is the gesture arbiter's to say, as for everything on home: a swipe that starts on a note is still a
 * swipe.
 */

/** A note's writing against its page: a twenty-fourth of the page's width, so it wraps the same on every display. */
private const val NOTE_TEXT_SHARE = 1f / 24f

/** The space between a note's edge and its writing, as a share of the page's width. */
private const val NOTE_PAD_SHARE = 0.018f

/** Where a note or a sticker is in [area], in the same pixels. A sticker is square, whatever the page's shape. */
internal fun Scrap.rect(area: PageArea): Rect =
    when (this) {
        is Scrap.Note -> Rect(area.x(note.x), area.y(note.y), area.x(note.x + note.w), area.y(note.y + note.h))
        is Scrap.Stuck -> area.length(sticker.size / 2f).let { half -> Rect(area.x(sticker.x) - half, area.y(sticker.y) - half, area.x(sticker.x) + half, area.y(sticker.y) + half) }
    }

private fun IntRect.area() = PageArea(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat())

/**
 * What a note or sticker in hand needs of home to change pages, as a tile does: [area], where a page is on
 * screen now, or null if it isn't showing; [under], the page a place on screen is over, with where it is; and
 * [rest], told where the finger is every moment it is down, so that resting at a side edge turns the page.
 */
internal class Crossing(
    val area: (page: String) -> PageArea?,
    val under: (Offset) -> Pair<String, PageArea>?,
    val rest: (Offset) -> Unit,
)

/** How often a finger that isn't moving is looked at again, for a rest at the edge to be noticed. */
private const val REST_MS = 50L

/**
 * Follows the finger [id] from [start] until it lifts or a pinch takes the touch, telling [onMove] how far it has gone;
 * true if it moved at all. With [resting], [onMove] is also told while the finger rests where it is.
 */
private suspend fun AwaitPointerEventScope.follow(
    id: PointerId,
    start: Offset,
    arbiter: GestureArbiter,
    resting: Boolean = false,
    onMove: (Offset) -> Unit,
): Boolean {
    var moved = false
    var by = Offset.Zero
    while (true) {
        val event = withTimeoutOrNull(if (resting && moved) REST_MS else Long.MAX_VALUE) { awaitPointerEvent(PointerEventPass.Initial) }
        if (event != null) {
            val c = event.changes.firstOrNull { it.id == id } ?: break
            c.consume()
            if (!c.pressed) break
            // A second finger made a pinch of it before it moved: it stays where it was.
            if (arbiter.owner == Owner.Pinch) return false
            by = c.position - start
            if (!moved && by.getDistance() > viewConfiguration.touchSlop) moved = true
        }
        if (moved) onMove(by)
    }
    return moved
}

/**
 * Carries [scrap], lifted on [page] by a finger that came down at [start] on screen, by as far as the finger
 * goes. All of it stays on its page until the finger is over another, which it then goes to ([crossing]): the
 * other page of a pair, or the one turned to at a side edge. A page that takes no more leaves it where it is.
 * A note the owner moves is on top, and his.
 */
private fun carry(
    book: AnnotationBook,
    marks: Marks,
    page: String,
    scrap: Scrap,
    start: Offset,
    crossing: Crossing,
): (Offset) -> Unit {
    var on = page
    var id = scrap.id
    val (x, y) =
        when (scrap) {
            is Scrap.Note -> scrap.note.x to scrap.note.y
            is Scrap.Stuck -> scrap.sticker.x to scrap.sticker.y
        }
    // From the corner or centre it is placed by to the finger, which it keeps on whatever page.
    val hold = crossing.area(page)?.let { start - Offset(it.x(x), it.y(y)) } ?: Offset.Zero
    return { by ->
        val finger = start + by
        crossing.rest(finger)
        val over = crossing.under(finger)
        if (over != null && over.first != on) {
            val (to, area) = over
            book.moveScrap(on, to, id, area.across(finger.x - hold.x), area.down(finger.y - hold.y))?.let { there ->
                if (marks.selected == Chosen(on, id)) marks.selected = Chosen(to, there)
                on = to
                id = there
            }
        }
        // Not showing while the page turns under it: it waits where it is.
        crossing.area(on)?.let { area ->
            val ax = area.across(finger.x - hold.x)
            val ay = area.down(finger.y - hold.y)
            when (scrap) {
                is Scrap.Note -> book.changeNote(on, id, raise = true) { it.copy(x = ax, y = ay) }
                is Scrap.Stuck -> book.changeSticker(on, id) { it.copy(x = ax, y = ay) }
            }
        }
    }
}

/** Resizes [scrap] by its bottom-right handle: a note's corner follows the finger, a sticker grows about its centre. */
private fun resize(
    book: AnnotationBook,
    page: String,
    scrap: Scrap,
    area: PageArea,
): (Offset) -> Unit =
    when (scrap) {
        is Scrap.Note -> { by -> book.changeNote(page, scrap.id) { it.copy(w = scrap.note.w + by.x / area.width, h = scrap.note.h + by.y / area.height) } }
        is Scrap.Stuck -> { by -> book.changeSticker(page, scrap.id) { it.copy(size = scrap.sticker.size + (by.x + by.y) / area.width) } }
    }

/**
 * A touch that came [down] on [page] at [at] with nothing being edited: if a note or a sticker is under it,
 * the touch is its (true), and a long press lifts it to be carried; a tap is left to it, and a swipe to the
 * pager or the app list, as the [arbiter] decides. [area] is the page in the coordinates [at] is in.
 * [awaitLong] waits out the long press and [onLift] is told when one lifted it.
 */
internal suspend fun AwaitPointerEventScope.liftScrap(
    down: PointerInputChange,
    at: Offset,
    page: String,
    area: PageArea,
    marks: Marks,
    book: AnnotationBook,
    arbiter: GestureArbiter,
    crossing: Crossing,
    awaitLong: suspend AwaitPointerEventScope.() -> Boolean,
    onLift: () -> Unit,
): Boolean {
    val scrap = book.on(page).scrapAt(area.across(at.x), area.down(at.y)) ?: return false
    arbiter.down(TouchStart.Tile, HomeModes(), note = true)
    if (!awaitLong() || !book.takes(page)) return true
    onLift()
    marks.selected = Chosen(page, scrap.id)
    marks.holding = true
    val moved =
        try {
            follow(down.id, down.position, arbiter, resting = true, onMove = carry(book, marks, page, scrap, down.position, crossing))
        } finally {
            marks.holding = false
        }
    // Like a tile: a drag drops it and is done; a long press that goes nowhere leaves it selected.
    if (moved) marks.selected = null
    return true
}

/**
 * A touch that came [down] while the note or sticker [chosen] is selected: every such touch is the editor's.
 * On its bar the buttons get it; on its handle it is resized; on itself it is carried; anywhere else lets it
 * go and does nothing more. False if it is no longer there, and the touch is anyone's. [at] and [area] are in
 * its page's own coordinates, where its bar is; [grab] is how near the handle counts.
 */
internal suspend fun AwaitPointerEventScope.editScrap(
    down: PointerInputChange,
    at: Offset,
    chosen: Chosen,
    area: PageArea,
    marks: Marks,
    book: AnnotationBook,
    arbiter: GestureArbiter,
    crossing: Crossing,
    grab: Float,
): Boolean {
    val scrap = book.on(chosen.page).scrap(chosen.id)
    if (scrap == null) {
        marks.selected = null
        return false
    }
    // Left unconsumed, so the bar's buttons get their taps.
    if (marks.bar?.has(at) == true) {
        arbiter.down(TouchStart.Bar, HomeModes(editing = true), note = true)
        return true
    }
    down.consume()
    val rect = scrap.rect(area)
    val owner =
        arbiter.down(
            when {
                (at - rect.bottomRight).getDistance() <= grab -> TouchStart.Handle
                rect.contains(at) -> TouchStart.Selected
                else -> TouchStart.Page
            },
            HomeModes(editing = true),
            note = true,
        )
    marks.holding = true
    try {
        when (owner) {
            Owner.Resize -> follow(down.id, down.position, arbiter, onMove = resize(book, chosen.page, scrap, area))
            Owner.Note -> follow(down.id, down.position, arbiter, resting = true, onMove = carry(book, marks, chosen.page, scrap, down.position, crossing))
            else -> {
                // A tap outside finishes; a drag outside does nothing.
                marks.selected = null
                follow(down.id, down.position, arbiter) {}
            }
        }
    } finally {
        marks.holding = false
    }
    return true
}

/** The notes and stickers on [page], over its tiles and under its ink, with the selected one's frame, handle and bar. */
@Composable
internal fun PageScraps(
    book: AnnotationBook,
    marks: Marks,
    page: String,
    area: IntRect,
    roomWidth: Int,
    roomHeight: Int,
    /** The one the menu just put here, which eases in from where it was picked. */
    arrivals: Arrivals? = null,
) {
    val on = book.on(page)
    // What was on the page when it was last composed: one that is gone since shrinks away instead of vanishing.
    val leaving = remember(book, page) { ScrapsLeaving() }
    leaving.see(on.notes.map { Scrap.Note(it) } + on.stickers.map { Scrap.Stuck(it) }, Motion.reduced)
    if (on.notes.isEmpty() && on.stickers.isEmpty() && leaving.none) return
    val within = area.area()
    for (note in on.notes.sortedBy { it.z }) {
        key(note.id) {
            // A tap opens its text, unless the page takes no change, which the book then says.
            NoteScrap(note, Scrap.Note(note).rect(within), arriving(note.id, arrivals)) { if (book.takes(page)) marks.editing = Chosen(page, note.id) }
        }
    }
    // Stickers lie over the notes, the last one stuck on top. A tap selects one.
    for (sticker in on.stickers) {
        key(sticker.id) {
            StickerScrap(sticker, Scrap.Stuck(sticker).rect(within), arriving(sticker.id, arrivals)) { if (book.takes(page)) marks.selected = Chosen(page, sticker.id) }
        }
    }
    // What was just taken off, shrinking away where it was. Only drawn: it takes no touch.
    for (scrap in leaving.left) {
        key("gone", scrap.id) {
            val left = rememberLeaving { leaving.gone(scrap.id) }
            val motion = Modifier.appears({ left.value }, SCRAP_LEAVE_SCALE)
            when (scrap) {
                is Scrap.Note -> NoteScrap(scrap.note, scrap.rect(within), motion, onTap = null)
                is Scrap.Stuck -> StickerScrap(scrap.sticker, scrap.rect(within), motion, onTap = null)
            }
        }
    }
    val chosen = marks.selected?.takeIf { it.page == page }?.let { on.scrap(it.id) } ?: return
    val density = LocalDensity.current
    val r = chosen.rect(within)
    val handle = with(density) { 14.dp.toPx() }
    Canvas(Modifier.fillMaxSize().zIndex(2f)) {
        drawRect(Pack.accent, topLeft = r.topLeft, size = r.size, style = Outline(Pack.mark.toPx()))
        drawCircle(Pack.background, handle / 2f + 2.dp.toPx(), r.bottomRight)
        drawCircle(Pack.accent, handle / 2f, r.bottomRight)
    }
    // Its bar above it, or below when it is at the top; not while a finger holds it.
    if (!marks.holding) {
        val at =
            with(density) {
                IntOffset(
                    r.left.roundToInt().coerceAtMost(roomWidth - 200.dp.roundToPx()).coerceAtLeast(0),
                    barTop(r.top.roundToInt(), r.bottom.roundToInt(), roomHeight, 48.dp.roundToPx(), 12.dp.roundToPx()),
                )
            }
        DisposableEffect(Unit) { onDispose { marks.bar = null } }
        Box(Modifier.zIndex(3f).offset { at }.onSizeChanged { marks.bar = IntRect(at, it) }) {
            EditBar {
                if (chosen is Scrap.Note) {
                    EditAction("edit") {
                        marks.selected = null
                        marks.editing = Chosen(page, chosen.id)
                    }
                }
                // A step at a time, all the way round.
                if (chosen is Scrap.Stuck) EditAction("turn") { book.changeSticker(page, chosen.id) { it.copy(turn = it.turn + TURN_STEP) } }
                EditAction("delete", Pack.red) {
                    marks.selected = null
                    book.remove(page, chosen.id)
                }
            }
        }
    }
}

/** How small a note or a sticker starts as it comes out of the menu, and how small it gets as it shrinks away. */
private const val SCRAP_ARRIVE_SCALE = 0.3f
private const val SCRAP_LEAVE_SCALE = 0.5f

/** How long after the menu put a note or a sticker on a page it still eases in, in milliseconds: one that shows later is simply there. */
private const val ARRIVAL_MS = 2000L

/**
 * The note or sticker the menu just put on a page, and where in the window it was picked. The one scrap
 * with that id eases in from there the first time it shows, once; every other scrap is simply there.
 */
internal class Arrivals {
    private var id: String? = null
    private var from: Offset? = null
    private var at = 0L

    fun expect(
        id: String,
        from: Offset?,
        now: Long = SystemClock.uptimeMillis(),
    ) {
        this.id = id
        this.from = from
        at = now
    }

    /** Whether [id] is the one that just arrived, and where from; asked by the scrap as it first shows, and true once. */
    fun take(
        id: String,
        now: Long = SystemClock.uptimeMillis(),
    ): Arrival? {
        if (id != this.id) return null
        this.id = null
        return if (now - at in 0..ARRIVAL_MS) Arrival(from) else null
    }
}

/** Where in the window something that just arrived came from; null when that isn't known, and it eases in where it stands. */
internal class Arrival(
    val from: Offset?,
)

/** The layer a scrap eases in by if it is the one that just arrived; nothing at all for any other. */
@Composable
private fun arriving(
    id: String,
    arrivals: Arrivals?,
): Modifier {
    val arrival = remember { arrivals?.take(id) } ?: return Modifier
    val shown = rememberAppearing()
    val at = remember { floatArrayOf(0f, 0f) }
    return Modifier
        .onGloballyPositioned {
            val origin = it.positionInWindow()
            at[0] = origin.x
            at[1] = origin.y
        }.appears({ shown.value }, SCRAP_ARRIVE_SCALE) {
            arrival.from?.let { from -> pivotAt(from, Offset(at[0], at[1]), size) } ?: TransformOrigin.Center
        }
}

/**
 * The notes and stickers of one page that were taken off it and are still shrinking away. [see] is told
 * what is on the page each time it is composed, and keeps what is missing since the last time in [left]
 * until [gone] is called for it.
 */
internal class ScrapsLeaving {
    private val seen = LinkedHashMap<String, Scrap>()
    private val going = LinkedHashMap<String, Scrap>()
    private var changed by mutableIntStateOf(0)

    /** What is shrinking away, oldest first. */
    val left: List<Scrap>
        get() {
            changed
            return going.values.toList()
        }

    val none: Boolean
        get() {
            changed
            return going.isEmpty()
        }

    /** [now] is what is on the page. With [still] (the system's animations are off) nothing shrinks away: it is just gone. */
    fun see(
        now: List<Scrap>,
        still: Boolean,
    ) {
        val ids = now.mapTo(HashSet()) { it.id }
        if (!still) for ((id, scrap) in seen) if (id !in ids) going[id] = scrap
        // One that is back (taken off and put back) is on the page, not leaving it.
        going.keys.retainAll { it !in ids }
        seen.clear()
        for (scrap in now) seen[scrap.id] = scrap
    }

    /** [id] has shrunk away. */
    fun gone(id: String) {
        if (going.remove(id) != null) changed++
    }
}

/**
 * Where the selected one's bar goes, [bar] tall with [gap] to spare, in a room [room] tall: above it; below it
 * when there is no room above; and when there is none below either (a note stretched over the whole page),
 * inside its top edge, so the bar is never off the page where the pager would cut it away.
 */
internal fun barTop(
    top: Int,
    bottom: Int,
    room: Int,
    bar: Int,
    gap: Int,
): Int =
    when {
        top - bar - gap >= 0 -> top - bar
        bottom + gap + bar <= room -> bottom + gap
        else -> (top + gap).coerceIn(0, (room - bar).coerceAtLeast(0))
    }

/** What a note of [role] is cut from: the pack's sticky note, or its emphasised scrap for one an agent left. */
private class NoteLook(
    role: NoteRole,
) {
    private val scrap = Pack.look.pack.scrap
    val fill = (if (role == NoteRole.Alt) scrap.alt.fill else scrap.sticky.fill).toColor()
    val ink = (if (role == NoteRole.Alt) scrap.alt.ink else scrap.sticky.ink).toColor()
    val line = if (role == NoteRole.Alt) scrap.alt.border else scrap.sticky.border
    val edge = (if (role == NoteRole.Alt) scrap.alt.borderColor else scrap.sticky.borderColor).toColor()
    val dashed = role == NoteRole.Sticky && scrap.sticky.dashed
}

/** A note's paper: filled, and outlined as the pack outlines it. */
private fun Modifier.notePaper(look: NoteLook) =
    drawBehind {
        drawRect(look.fill)
        if (look.line > 0f) {
            val width = look.line.dp.toPx()
            val dashes = if (look.dashed) PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f)) else null
            drawRect(look.edge, topLeft = Offset(width / 2f, width / 2f), size = size.copy(size.width - width, size.height - width), style = Outline(width, pathEffect = dashes))
        }
    }

/** One sticky note at [rect]: plain text in the pack's hand, on the pack's sticky paper. */
@Composable
private fun NoteScrap(
    note: StickyNote,
    rect: Rect,
    /** How it eases in or out, if it does: its own layer, after its place and size. */
    motion: Modifier = Modifier,
    /** Null for one that is on its way off the page: it is only drawn. */
    onTap: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val look = NoteLook(note.role)
    // Against the page, so it reads the same on the cover and on half the inner screen: the page is a little wider than tall, 5 to 6.
    val pageWidth = rect.width / note.w.coerceAtLeast(0.001f)
    val size = with(density) { (pageWidth * NOTE_TEXT_SHARE).toSp() }
    Column(
        Modifier
            .zIndex(1f)
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .then(motion)
            .notePaper(look)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(with(density) { (pageWidth * NOTE_PAD_SHARE).toDp() }),
    ) {
        // Who left it, when it wasn't him.
        if (note.author == Author.Agent) BasicText("oma", style = Pack.mono(size * 0.7f, look.ink.copy(alpha = 0.6f)), maxLines = 1)
        BasicText(
            note.text.ifEmpty { "tap to write" },
            style = Pack.hand(size, if (note.text.isEmpty()) look.ink.copy(alpha = 0.5f) else look.ink),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A note's text in a field, over home like its menu: what is typed is kept when it closes, by "done", a tap
 * outside or Back, as plain text no longer than a note takes. A note left with nothing on it is taken off.
 */
@Composable
fun NoteEditor(state: LauncherState) {
    val marks = state.marks
    val chosen = marks.editing ?: return
    val book = marks.book
    val note = book?.on(chosen.page)?.notes?.firstOrNull { it.id == chosen.id }
    if (book == null || note == null) {
        LaunchedEffect(chosen) { marks.editing = null }
        return
    }
    var field by remember(chosen) { mutableStateOf(TextFieldValue(note.text, TextRange(note.text.length))) }
    val look = NoteLook(note.role)
    val focus = remember { FocusRequester() }
    LaunchedEffect(chosen) { focus.requestFocus() }

    fun close() {
        marks.editing = null
        if (field.text.isBlank()) book.remove(chosen.page, chosen.id) else book.changeNote(chosen.page, chosen.id) { it.copy(text = field.text.trimEnd()) }
    }
    BackHandler { close() }
    Menu(onDismiss = ::close) {
        MenuTitle("note")
        BasicTextField(
            value = field,
            // Never more than a note takes: what is typed or pasted past that is cut off.
            onValueChange = { typed ->
                val text = noteText(typed.text)
                field = if (text == typed.text) typed else TextFieldValue(text, TextRange(typed.selection.end.coerceAtMost(text.length)))
            },
            textStyle = Pack.hand(Pack.size.item, look.ink),
            cursorBrush = SolidColor(look.ink),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .heightIn(min = 120.dp, max = 260.dp)
                    .focusRequester(focus)
                    .notePaper(look)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (field.text.length >= AnnotationLimits.NOTE_TEXT) {
            BasicText("a note takes ${AnnotationLimits.NOTE_TEXT} characters", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = Pack.body(Pack.size.small, Pack.accent))
        }
        MenuItem("done", onClick = ::close)
        MenuItem("delete", Pack.red) {
            marks.editing = null
            book.remove(chosen.page, chosen.id)
        }
    }
}
