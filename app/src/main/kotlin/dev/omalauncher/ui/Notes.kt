package dev.omalauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.PageArea
import dev.omalauncher.widgets.annotations.Scrap
import dev.omalauncher.widgets.annotations.landings
import dev.omalauncher.widgets.annotations.scrap
import dev.omalauncher.widgets.annotations.scrapAt
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The phone's touches on its notes and stickers: lifted by a long press, carried by a finger to this page or
 * another, resized by the handle, as home's gesture arbiter decides whose a touch is. How they are drawn and
 * edited is shared with Soma desktop, in :chat's Scraps.kt (desktop slice 5b, 2026-10-28).
 */

/**
 * What a note or sticker in hand needs of home to change pages, as a tile does: [near], the pages showing
 * now with where each is on screen, the one a place on screen is over first, or the nearest to it where it
 * is over none; [rest], told where the finger is every moment it is down, so that resting at a side edge
 * turns the page, or past the last page offers a new one; and [end], told the page it is on when the finger
 * lets go of it, however that comes about.
 */
internal class Crossing(
    val near: (Offset) -> List<Pair<String, PageArea>>,
    val rest: (Offset) -> Unit,
    val end: (page: String) -> Unit,
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
 * Carries [scrap], lifted on [page] by the finger [down], by as far as the finger goes; true if it moved at
 * all. [at] is where the finger came down and [area] the page, both in the page's own coordinates. All of it
 * stays on its page until the finger is over another, which it then goes to ([crossing], [landings]): the
 * other page of a pair, or the one turned to at a side edge. A page that takes no more, or whose file
 * couldn't be read, leaves it where it is and is asked once only, so the owner is told once. A note the
 * owner moves is on top, and his.
 */
private suspend fun AwaitPointerEventScope.carry(
    down: PointerInputChange,
    at: Offset,
    area: PageArea,
    book: AnnotationBook,
    marks: Marks,
    page: String,
    scrap: Scrap,
    arbiter: GestureArbiter,
    crossing: Crossing,
): Boolean {
    var on = page
    var id = scrap.id
    // From the corner or centre it is placed by to the finger, which it keeps on whatever page.
    val hold =
        when (scrap) {
            is Scrap.Note -> at - Offset(area.x(scrap.note.x), area.y(scrap.note.y))
            is Scrap.Stuck -> at - Offset(area.x(scrap.sticker.x), area.y(scrap.sticker.y))
        }
    val refused = HashSet<String>()
    return try {
        follow(down.id, down.position, arbiter, resting = true) { by ->
            val finger = down.position + by
            crossing.rest(finger)
            val showing = crossing.near(finger)
            for (to in landings(on, showing.map { it.first })) {
                if (to in refused) continue
                // A page turned to is read as it comes into view: it is asked again in a moment.
                val status = book.status(to)
                if (status != AnnotationBook.Status.Ready && status != AnnotationBook.Status.Unreadable) break
                val there = showing.first { it.first == to }.second
                val landed = book.moveScrap(on, to, id, there.across(finger.x - hold.x), there.down(finger.y - hold.y))
                if (landed == null) {
                    refused += to
                    continue
                }
                marks.carriedOff(on, id)
                if (marks.selected == Chosen(on, id)) marks.selected = Chosen(to, landed)
                on = to
                id = landed
                break
            }
            // Not showing while the page turns under it: it waits where it is.
            showing.firstOrNull { it.first == on }?.second?.let { here ->
                val x = here.across(finger.x - hold.x)
                val y = here.down(finger.y - hold.y)
                when (scrap) {
                    is Scrap.Note -> book.changeNote(on, id, raise = true) { it.copy(x = x, y = y) }
                    is Scrap.Stuck -> book.changeSticker(on, id) { it.copy(x = x, y = y) }
                }
            }
        }
    } finally {
        crossing.end(on)
    }
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
            carry(down, at, area, book, marks, page, scrap, arbiter, crossing)
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
            Owner.Resize -> follow(down.id, down.position, arbiter, onMove = resizeScrap(book, chosen.page, scrap, area))
            Owner.Note -> carry(down, at, area, book, marks, chosen.page, scrap, arbiter, crossing)
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

/** The note being typed in on home: the shared editor, over home like its menu. */
@Composable
fun NoteEditor(state: LauncherState) = NoteEditor(state.marks)
