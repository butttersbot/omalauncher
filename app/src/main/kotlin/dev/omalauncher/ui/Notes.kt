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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.roundToInt

/*
 * Sticky notes and stickers on a home page: floating over the tiles, in no cell, and never pushing a tile
 * (PRD AC-23). A tap on a note opens its text; a long press lifts it to drag, like a tile, and a long press
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

/** Follows the finger [id] from [start] until it lifts or a pinch takes the touch, telling [onMove] how far it has gone; true if it moved at all. */
private suspend fun AwaitPointerEventScope.follow(
    id: PointerId,
    start: Offset,
    arbiter: GestureArbiter,
    onMove: (Offset) -> Unit,
): Boolean {
    var moved = false
    while (true) {
        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: break
        c.consume()
        if (!c.pressed) break
        // A second finger made a pinch of it before it moved: it stays where it was.
        if (arbiter.owner == Owner.Pinch) return false
        val by = c.position - start
        if (!moved && by.getDistance() > viewConfiguration.touchSlop) moved = true
        if (moved) onMove(by)
    }
    return moved
}

/** Carries [scrap] by as far as the finger goes; all of it stays on the page. A note the owner moves is on top, and his. */
private fun carry(
    book: AnnotationBook,
    page: String,
    scrap: Scrap,
    area: PageArea,
): (Offset) -> Unit =
    when (scrap) {
        is Scrap.Note -> { by -> book.changeNote(page, scrap.id, raise = true) { it.copy(x = scrap.note.x + by.x / area.width, y = scrap.note.y + by.y / area.height) } }
        is Scrap.Stuck -> { by -> book.changeSticker(page, scrap.id) { it.copy(x = scrap.sticker.x + by.x / area.width, y = scrap.sticker.y + by.y / area.height) } }
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
            follow(down.id, down.position, arbiter, carry(book, page, scrap, area))
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
            Owner.Resize -> follow(down.id, down.position, arbiter, resize(book, chosen.page, scrap, area))
            Owner.Note -> follow(down.id, down.position, arbiter, carry(book, chosen.page, scrap, area))
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
) {
    val on = book.on(page)
    if (on.notes.isEmpty() && on.stickers.isEmpty()) return
    val within = area.area()
    for (note in on.notes.sortedBy { it.z }) {
        key(note.id) {
            // A tap opens its text, unless the page takes no change, which the book then says.
            NoteScrap(note, Scrap.Note(note).rect(within)) { if (book.takes(page)) marks.editing = Chosen(page, note.id) }
        }
    }
    // Stickers lie over the notes, the last one stuck on top. A tap selects one.
    for (sticker in on.stickers) {
        key(sticker.id) {
            StickerScrap(sticker, Scrap.Stuck(sticker).rect(within)) { if (book.takes(page)) marks.selected = Chosen(page, sticker.id) }
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
                    if (r.top > 56.dp.toPx()) r.top.roundToInt() - 48.dp.roundToPx() else r.bottom.roundToInt() + 12.dp.roundToPx(),
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
    onTap: () -> Unit,
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
            .notePaper(look)
            .clickable(onClick = onTap)
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
