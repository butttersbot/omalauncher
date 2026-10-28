package dev.omalauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.prototypeUnit
import dev.omalauncher.widgets.annotations.NoteRole
import dev.omalauncher.widgets.annotations.PageArea
import dev.omalauncher.widgets.pickedPanels

/*
 * The phone's drawing: a finger's stroke as home's gesture arbiter allows it, and the band of tools where the
 * dock stands. The ink, the marks and the tools' keys themselves are shared with Soma desktop, in :chat's
 * PageInk.kt (desktop slice 5b, 2026-10-28).
 */

/**
 * One finger draws: follows the finger that came [down] on [page] until it lifts, and takes every move from
 * what is under it, so no tile is tapped and no widget dragged through the pen. A second finger ends the
 * stroke with nothing drawn: two fingers are the pinch's, which the [arbiter] has said by then or will.
 */
internal suspend fun AwaitPointerEventScope.draw(
    down: PointerInputChange,
    marks: Marks,
    page: String,
    area: PageArea,
    pen: Float,
    arbiter: GestureArbiter,
) {
    down.consume()
    var drawing = marks.begin(page, area, down.position, pen)
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val finger = event.changes.firstOrNull { it.id == down.id }
            val pinch = arbiter.owner == Owner.Pinch
            if (drawing && (pinch || event.changes.count { it.pressed } > 1)) {
                marks.cancel()
                drawing = false
            }
            // The pinch has taken its fingers' moves already; anything else stays the pen's, drawn or not.
            if (!pinch) event.changes.forEach { it.consume() }
            if (finger == null || !finger.pressed) break
            if (drawing) {
                finger.historical.forEach { marks.extend(it.position) }
                marks.extend(finger.position)
            }
        }
        if (drawing) marks.finish()
        drawing = false
    } finally {
        if (drawing) marks.cancel()
    }
}

/**
 * The stickers to pick from while drawing, asked for with the tools' "sticker". A menu over the whole window:
 * its host lays it outside the bars' padding. Picking one puts the pen down, as a note or text does.
 */
@Composable
fun DrawStickers(
    state: LauncherState,
    onClose: () -> Unit,
) {
    val marks = state.marks
    val book = marks.book ?: return
    val page = state.currentPage
    Menu(onDismiss = onClose) {
        MenuTitle("stick on this page")
        StickerPicker { choice, from ->
            onClose()
            state.stopDrawing()
            book.stick(page, choice) { stuck ->
                if (stuck != null) {
                    state.arrivals.expect(stuck, from)
                    marks.selected = Chosen(page, stuck)
                }
            }
        }
    }
}

/**
 * The drawing tools, standing where the dock does while one finger draws: pen (again for the pack's second
 * ink), highlighter, eraser, a sticky note, a sticker and plain text to put on the page last touched, undo
 * and redo for it, and done. Its band takes every touch that lands on it, so nothing under it is opened
 * through it.
 */
@Composable
fun DrawTools(
    state: LauncherState,
    onStickers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.drawing) return
    val marks = state.marks
    val book = marks.book ?: return
    val page = state.currentPage
    val density = LocalDensity.current

    // A note, a sticker or text put on the page ends the drawing: while a finger draws, nothing on the page can be
    // moved or typed in, and what was just added is to be placed or written at once.
    fun write(role: NoteRole) {
        if (!book.takes(page)) return
        state.stopDrawing()
        book.addNote(page, role = role)?.let {
            state.arrivals.expect(it, null)
            marks.editing = Chosen(page, it)
        }
    }
    // The band takes every touch that lands on it, where it stands over the dock. In a look drawn in panels there is no dock
    // and the pages run under it: only its keys take touches, so the page around them can still be drawn on.
    val panels = pickedPanels() != null
    val takes = Modifier.pointerInput(Unit) {}
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = with(density) { state.barHeight.toDp() }.coerceAtLeast(60.dp))
            .then(if (panels) Modifier else takes)
            .padding(PageGap),
        contentAlignment = Alignment.Center,
    ) {
        if (Pack.ownsPage) {
            // The prototype's `.tools`: square buttons like the dock's, from the left, the tool in hand in the emphasised colors.
            val wide = LocalConfiguration.current.run { prototypeUnit(screenWidthDp.toFloat(), screenHeightDp.toFloat()) }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = (14 * wide).dp - PageGap), contentAlignment = Alignment.Center) {
                // Nine keys are more than the prototype's seven: on a narrow screen they shrink together to fit the row.
                val u = wide.coerceAtMost(maxWidth.value / toolsWidth(TOOL_KEYS))
                InkToolKeys(marks, page, u, ::write, onStickers, { state.stopDrawing() }, if (panels) takes else Modifier)
            }
        } else {
            InkToolWords(marks, page, ::write, onStickers, { state.stopDrawing() }, if (panels) takes else Modifier)
        }
    }
}

