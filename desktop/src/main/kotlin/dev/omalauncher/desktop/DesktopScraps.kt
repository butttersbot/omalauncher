package dev.omalauncher.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Arrivals
import dev.omalauncher.ui.Chosen
import dev.omalauncher.ui.Marks
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.rect
import dev.omalauncher.ui.resizeScrap
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.AnnotationFiles
import dev.omalauncher.widgets.annotations.PageArea
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.PageTile
import dev.omalauncher.widgets.annotations.STICKER_SIZE
import dev.omalauncher.widgets.annotations.Scrap
import dev.omalauncher.widgets.annotations.StickerShelf
import dev.omalauncher.widgets.annotations.StickerSnapshots
import dev.omalauncher.widgets.annotations.measurePicture
import dev.omalauncher.widgets.annotations.scrap
import dev.omalauncher.widgets.annotations.scrapAt
import java.io.File
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/*
 * The dashboard window's notes, stickers and ink (desktop slice 5b, Boss 2026-10-28: the dashboard is to be like the
 * phone's home, notes, stickers and ink included). Drawn and edited by the phone's own code, from :chat; kept here, in
 * Soma's folder, as this window's alone: its layout is not the phone's, and neither is what is written on it.
 */

/** A new note on the dashboard: as many of its twelve columns and eight rows as a phone's note takes of its six and nine. */
const val DESKTOP_NOTE_WIDTH = 0.22f
const val DESKTOP_NOTE_HEIGHT = 0.18f

/** A new sticker on the dashboard: half the phone's share of the page, as its columns are half as wide. */
const val DESKTOP_STICKER_SIZE = STICKER_SIZE / 2f

/** How big a note's writing is on the dashboard against the phone's: its page is twice as many columns across. */
const val DESKTOP_TEXT_SCALE = 0.5f

/**
 * The dashboard's [AnnotationBook] and what is drawn from it. [dir] is Soma's folder: the pages' files are its
 * `annotations`, the copies of pictures stuck on them `annotations/stickers`, and the pictures to pick from its
 * `stickers`. Files are read and written on a thread of their own; what comes back is brought to the window's thread
 * by [foreground], and a page's save waits [AnnotationBook.SAVE_AFTER_MILLIS] on a Swing timer of its own. A new note
 * or sticker is placed clear of [canvas]'s blocks on its page.
 */
class DesktopScraps(
    private val canvas: DesktopCanvas,
    dir: File = SomaHome.dir,
    foreground: (() -> Unit) -> Unit = { task -> SwingUtilities.invokeLater(task) },
) {
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "soma-annotations").apply { isDaemon = true } }
    private val saves = HashMap<String, Timer>()

    /** The page's width and height in dp as the window last laid it out: what a share of the page is worth, for a new note's place. */
    var pageWidth = 1200f
    var pageHeight = 760f

    /** The gap between the dashboard's blocks and at its edges, in dp: CanvasScreen's own. */
    var gap = 8f

    val shelf =
        StickerShelf(
            File(dir, "stickers"),
            StickerSnapshots(File(dir, "annotations/stickers"), ::measurePicture),
            shownAs = File(dir, "stickers").path.replaceFirst(System.getProperty("user.home"), "~"),
        )

    val book =
        AnnotationBook(
            files = AnnotationFiles(File(dir, "annotations")),
            background = { task -> io.execute(task) },
            foreground = foreground,
            defer = { page, save ->
                saves.remove(page)?.stop()
                if (save != null) {
                    saves[page] =
                        Timer(AnnotationBook.SAVE_AFTER_MILLIS.toInt()) {
                            saves.remove(page)
                            save()
                        }.apply {
                            isRepeats = false
                            start()
                        }
                }
            },
            board = ::board,
            snapshots = shelf.snapshots,
        )

    /** The tool in hand, the stroke being drawn and the note or sticker selected, as the phone's home has them. */
    val marks = Marks().also { it.book = book }

    /** The note or sticker the menu just put on a page, which eases in. */
    val arrivals = Arrivals()

    /** Drawing on the page: the pointer is the pen, and the tools show. */
    var drawing by mutableStateOf(false)

    /** The pages there were when last looked at: one gone since takes what was written on it along. */
    private var pages = emptySet<String>()

    /**
     * The page [page] of the dashboard as a note or sticker placed on it sees it: twelve columns by eight rows, the
     * page's shape as laid out now, and the blocks on it.
     */
    fun board(page: String): PageBoard =
        PageBoard(
            rows = DESKTOP_ROWS,
            tiles = canvas.blocksOn(page).mapNotNull { item -> item.cell?.let { PageTile(item.key, it, item.kind.name.lowercase()) } },
            columns = DESKTOP_COLUMNS,
            tall = pageHeight / pageWidth,
            noteWidth = DESKTOP_NOTE_WIDTH,
            noteHeight = DESKTOP_NOTE_HEIGHT,
            stickerSize = DESKTOP_STICKER_SIZE,
            gap = gap / pageWidth,
        )

    /**
     * The canvas's pages now. At the first look, changes are taken from here on when the canvas is kept (as the phone
     * waits for its notebook to be kept); a page gone since the last look takes its annotations with it. Window thread.
     */
    fun pagesAre(ids: Set<String>) {
        if (!book.writable && canvas.unkept == null) book.keep(ids)
        for (gone in pages - ids) book.pageGone(gone)
        pages = ids
    }

    /** Draws no more: the stroke under the pointer, if any, is the page's. */
    fun stopDrawing() {
        drawing = false
        marks.finish()
    }
}

/**
 * Where the dashboard's page is as last laid out, in the window's own pixels: its id, its rectangle ([area]), its
 * height over its width ([tall]) and the drawing tools' rectangle while they show, which the pen leaves alone.
 */
class Sheet {
    var page: String = DESKTOP_PAGE
    var area = PageArea(0f, 0f, 1f, 1f)
    var tall = 1f
    var tools: Rect? = null
}

/** How near the selected one's corner a press takes hold of it to resize, in dp: the phone's handle and a little more. */
private const val GRAB_DP = 14f

/**
 * The mouse on the page's notes, stickers and ink, before anything under them has it (the phone's home arbiter, for a
 * mouse). While drawing, a press on the page draws until it lets go, whatever it is over. Otherwise a right-click on a
 * note or sticker selects it, as the phone's long press does; a press on the selected one's corner resizes it; a drag
 * that starts on one moves it, and a click on one is left to it (a note opens, a sticker is selected); a press anywhere
 * else lets the selected one go and goes on to what is there.
 */
fun Modifier.scrapsByMouse(
    scraps: DesktopScraps,
    sheet: Sheet,
): Modifier =
    pointerInput(scraps, sheet) {
        awaitEachGesture {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) return@awaitEachGesture
            val down = event.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
            val at = down.position
            val book = scraps.book
            val marks = scraps.marks
            val page = sheet.page
            val area = sheet.area
            if (sheet.tools?.contains(at) == true) return@awaitEachGesture
            if (marks.bar?.contains(IntOffset(at.x.roundToInt(), at.y.roundToInt())) == true) return@awaitEachGesture
            if (marks.editing != null) return@awaitEachGesture
            if (scraps.drawing) {
                if (event.buttons.isPrimaryPressed) drawByMouse(down, scraps, page, area)
                return@awaitEachGesture
            }
            val under = book.on(page).scrapAt(area.across(at.x), area.down(at.y), sheet.tall)
            if (event.buttons.isSecondaryPressed) {
                if (under == null) return@awaitEachGesture
                down.consume()
                if (book.takes(page)) marks.selected = Chosen(page, under.id)
                return@awaitEachGesture
            }
            if (!event.buttons.isPrimaryPressed) return@awaitEachGesture
            val chosen = marks.selected?.takeIf { it.page == page }?.let { book.on(page).scrap(it.id) }
            if (chosen != null && (at - chosen.rect(area).bottomRight).getDistance() <= GRAB_DP * density) {
                down.consume()
                val resize = resizeScrap(book, page, chosen, area)
                marks.holding = true
                try {
                    follow(down) { by -> resize(by) }
                } finally {
                    marks.holding = false
                }
                return@awaitEachGesture
            }
            if (under == null) {
                if (marks.selected != null) marks.selected = null
                return@awaitEachGesture
            }
            // From the corner or centre it is placed by to the pointer, which it keeps as it goes.
            val hold =
                when (under) {
                    is Scrap.Note -> at - Offset(area.x(under.note.x), area.y(under.note.y))
                    is Scrap.Stuck -> at - Offset(area.x(under.sticker.x), area.y(under.sticker.y))
                }
            marks.holding = true
            val moved =
                try {
                    follow(down) { by ->
                        if (!book.takes(page)) return@follow
                        val x = area.across(at.x + by.x - hold.x)
                        val y = area.down(at.y + by.y - hold.y)
                        when (under) {
                            is Scrap.Note -> book.changeNote(page, under.id, raise = true) { it.copy(x = x, y = y) }
                            is Scrap.Stuck -> book.changeSticker(page, under.id) { it.copy(x = x, y = y) }
                        }
                    }
                } finally {
                    marks.holding = false
                }
            // Like a tile: a drag drops it and is done.
            if (moved) marks.selected = null
        }
    }

/**
 * Follows the pointer that came [down] until it lets go, telling [onMove] how far it has gone once it has gone further
 * than a click wanders; from then on its moves are taken from what is under it. True if it went that far.
 */
private suspend fun AwaitPointerEventScope.follow(
    down: PointerInputChange,
    onMove: (Offset) -> Unit,
): Boolean {
    var moved = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val c = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!c.pressed) {
            if (moved) c.consume()
            break
        }
        val by = c.position - down.position
        if (!moved && by.getDistance() > viewConfiguration.touchSlop) moved = true
        if (moved) {
            c.consume()
            onMove(by)
        }
    }
    return moved
}

/** The pen, the highlighter or the eraser on [page], from where the pointer came [down] until it lets go. */
private suspend fun AwaitPointerEventScope.drawByMouse(
    down: PointerInputChange,
    scraps: DesktopScraps,
    page: String,
    area: PageArea,
) {
    val marks = scraps.marks
    down.consume()
    var drawing = marks.begin(page, area, down.position, Pack.look.pack.ink.width.dp.toPx())
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            val c = event.changes.firstOrNull { it.id == down.id }
            if (c == null || !c.pressed) break
            if (drawing) {
                c.historical.forEach { marks.extend(it.position) }
                marks.extend(c.position)
            }
        }
        if (drawing) marks.finish()
        drawing = false
    } finally {
        if (drawing) marks.cancel()
    }
}
