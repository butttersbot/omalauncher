package dev.omalauncher.widgets.annotations

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.LiveNotebook
import dev.omalauncher.widgets.layout.GridGeometry
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.PinnedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs

/** Where a new note or sticker goes: clear of the tiles and of what is stuck on the page, and honest when it can't be. */
class NotePlacementTest {
    /** The gap as [clearSpot] keeps it, less the step a saved place may be off by. */
    private val across = PLACING_GAP - 1.5f / UNITS
    private val down = PLACING_GAP / PAGE_TALL - 1.5f / UNITS

    private fun clear(
        box: Box,
        taken: List<Box>,
    ) = taken.none { hits(box, it, across, down) }

    private fun onPage(box: Box) = box.x >= 0f && box.y >= 0f && box.right <= 1.0001f && box.bottom <= 1.0001f

    /** The owner's first page as he has it: five widgets that leave three cells free, bottom right. */
    private val owners =
        PageBoard(
            9,
            listOf(
                PageTile("today.owl", Cell(0, 0, 3, 3)),
                PageTile("fetch.owl", Cell(3, 0, 3, 3)),
                PageTile("koi.owl", Cell(0, 3, 6, 3)),
                PageTile("owl.owl", Cell(0, 6, 3, 3)),
                PageTile("oma.owl", Cell(3, 6, 3, 2)),
            ),
        )

    private fun PageBoard.tileBoxes() = tiles.map { box(it.cell) }

    private fun left(
        page: Annotations,
        board: PageBoard,
        now: Long,
        near: Pair<Int, Int>? = null,
    ) = leaveAgentNote(page, "did it", now, board, near) as NoteLeft.Left

    // The search itself: the cases of Cove's find-clear-region, on a page with edges.

    @Test fun anEmptyPageGivesThePlaceAskedFor() {
        val spot = clearSpot(emptyList(), 0.2f, 0.1f, 0.3f to 0.4f)
        assertEquals(Box(0.3f, 0.4f, 0.2f, 0.1f), spot.box)
        assertFalse(spot.compromised)
        assertEquals(0f, spot.overlap, 0f)
    }

    @Test fun thePlaceFoundKeepsTheGapFromEverything() {
        val taken = listOf(Box(0.25f, 0.35f, 0.3f, 0.2f), Box(0.6f, 0.3f, 0.2f, 0.3f), Box(0.1f, 0.6f, 0.5f, 0.1f))
        val spot = clearSpot(taken, 0.2f, 0.1f, 0.3f to 0.4f)
        assertFalse(spot.compromised)
        assertTrue(clear(spot.box, taken))
        assertTrue(onPage(spot.box))
        // Touching is not clear, and neither is standing closer than the gap.
        assertTrue(hits(Box(0f, 0f, 0.2f, 0.2f), Box(0.1f, 0.1f, 0.2f, 0.2f)))
        assertFalse(hits(Box(0f, 0f, 0.2f, 0.2f), Box(0.2f, 0f, 0.2f, 0.2f)))
        assertTrue(hits(Box(0f, 0f, 0.2f, 0.2f), Box(0.205f, 0f, 0.2f, 0.2f), across = 0.01f))
        assertFalse(hits(Box(0f, 0f, 0.2f, 0.2f), Box(0.215f, 0f, 0.2f, 0.2f), across = 0.01f))
    }

    @Test fun theSamePageAndQuestionAlwaysGiveTheSamePlace() {
        val taken = listOf(Box(0.25f, 0.35f, 0.3f, 0.2f), Box(0.6f, 0.3f, 0.2f, 0.3f))
        val first = clearSpot(taken, 0.2f, 0.1f, 0.3f to 0.4f)
        assertEquals(first, clearSpot(taken, 0.2f, 0.1f, 0.3f to 0.4f))
        assertEquals(first, clearSpot(taken.reversed(), 0.2f, 0.1f, 0.3f to 0.4f))
        // Asked again with what was placed added, it moves on to another clear place instead of answering the same.
        val next = clearSpot(taken + first.box, 0.2f, 0.1f, 0.3f to 0.4f)
        assertFalse(next.compromised)
        assertNotEquals(first.box, next.box)
        assertTrue(clear(next.box, taken + first.box))
    }

    @Test fun aPageWithNoClearPlaceGivesTheLeastOverlapAndSaysSo() {
        val wall = listOf(Box(0f, 0f, 1f, 1f))
        val spot = clearSpot(wall, 0.2f, 0.1f, 0.3f to 0.4f)
        assertTrue(spot.compromised)
        assertEquals(0.02f, spot.overlap, 0.0001f)
        assertTrue(onPage(spot.box))
        // All equally bad: the nearest, which is the place asked for.
        assertEquals(0.3f to 0.4f, spot.box.x to spot.box.y)
        // Least overlap comes before nearest: the left half is covered and the right has a band too short to fit in.
        val crowded = listOf(Box(0f, 0f, 0.5f, 1f), Box(0.5f, 0f, 0.5f, 0.35f), Box(0.5f, 0.65f, 0.5f, 0.35f))
        val least = clearSpot(crowded, 0.4f, 0.4f, 0.05f to 0.05f)
        assertTrue(least.compromised)
        assertTrue(least.box.x >= 0.5f)
        assertTrue(least.overlap < 0.06f)
        assertEquals(crowded.sumOf { least.box.overlap(it).toDouble() }.toFloat(), least.overlap, 0.00001f)
    }

    @Test fun whatIsOnThePageIsNotChangedByLooking() {
        val taken = listOf(Box(0.25f, 0.35f, 0.3f, 0.2f), Box(0.6f, 0.3f, 0.2f, 0.3f))
        val before = taken.toList()
        clearSpot(taken, 0.2f, 0.1f, 0.3f to 0.4f)
        owners.noteSpot(taken)
        owners.free(taken)
        assertEquals(before, taken)
        assertEquals(5, owners.tiles.size)
    }

    @Test fun aPlaceIsNeverOffThePageOrAgainstItsEdge() {
        for (near in listOf(-3f to -3f, 5f to 0.5f, 0.99f to 0.99f, 0f to 0f, Float.MAX_VALUE to -Float.MAX_VALUE)) {
            val box = clearSpot(emptyList(), 0.44f, 0.16f, near).box
            assertTrue("$near", box.x >= PLACING_GAP - 0.00001f && box.right <= 1f - PLACING_GAP + 0.00001f)
            assertTrue("$near", box.y >= PLACING_GAP / PAGE_TALL - 0.00001f && box.bottom <= 1f - PLACING_GAP / PAGE_TALL + 0.00001f)
        }
        // Something as big as the page still gets an answer, on the page.
        assertTrue(onPage(clearSpot(listOf(Box(0.4f, 0.4f, 0.2f, 0.2f)), 1f, 1f, 0.5f to 0.5f).box))
    }

    // The page's grid in fractions.

    @Test fun aTilesPlaceInFractionsIsWhereTheGridDrawsIt() {
        // Notes are placed in the page's rectangle, and so are the grid's tiles: on every display, for every count of rows.
        for ((width, height) in listOf(1248 to 1462, 1224 to 1339, 1080 to 2040)) for (rows in 9..12) {
            val grid = GridGeometry(width, height, rows)
            val board = PageBoard(rows)
            for (cell in listOf(Cell(0, 0, 1, 1), Cell(0, 0, 3, 3), Cell(3, 6, 3, 2), Cell(5, rows - 1, 1, 1), Cell(0, 3, 6, 3))) {
                val drawn = grid.bounds(cell)
                val box = board.box(cell)
                assertEquals((drawn.left - grid.pageLeft) / grid.pageWidth, box.x, 0.00001f)
                assertEquals((drawn.top - grid.pageTop) / grid.pageHeight, box.y, 0.00001f)
                assertEquals(drawn.width / grid.pageWidth, box.w, 0.00001f)
                assertEquals(drawn.height / grid.pageHeight, box.h, 0.00001f)
                // And back: a tile's rectangle lies on its own cells and no others.
                assertEquals(cell, board.cells(box))
            }
        }
        // Tiles keep the gap from the page's edge that a placed note keeps too.
        val last = PageBoard(9).box(Cell(5, 8, 1, 1))
        assertEquals(1f - PLACING_GAP, last.right, 0.00001f)
        assertEquals(1f - PLACING_GAP / PAGE_TALL, last.bottom, 0.00001f)
    }

    @Test fun aBoxLiesOnTheCellsWhoseTilesItWouldCover() {
        val board = PageBoard(9)
        // A hair into the next column's tile counts; ending in the gap before it doesn't.
        val tile = board.box(Cell(1, 1, 2, 2))
        assertEquals(Cell(1, 1, 2, 2), board.cells(tile.copy(w = tile.w + PLACING_GAP * 0.9f)))
        assertEquals(Cell(1, 1, 3, 2), board.cells(tile.copy(w = tile.w + PLACING_GAP * 1.5f)))
        assertEquals(Cell(0, 0, 6, 9), board.cells(Box(0f, 0f, 1f, 1f)))
        // A saved cell that reaches past the grid is where the grid shows it.
        assertEquals(listOf(Cell(4, 7, 2, 2)), PageBoard(9, listOf(PageTile("a.owl", Cell(7, 20, 2, 2)))).tiles.map { it.cell })
    }

    @Test fun freeIsTheBiggestEmptyRectanglesLargestFirst() {
        assertEquals(listOf(Cell(3, 8, 3, 1)), owners.free())
        assertEquals(listOf(Cell(0, 0, 6, 9)), PageBoard(9).free())
        assertTrue(PageBoard(9, listOf(PageTile("wall.owl", Cell(0, 0, 6, 9)))).free().isEmpty())
        // An L of free cells is two rectangles that share its corner, the bigger first; none lies inside another.
        val ell = PageBoard(9, listOf(PageTile("a.owl", Cell(0, 0, 4, 7)))).free()
        assertEquals(listOf(Cell(4, 0, 2, 9), Cell(0, 7, 6, 2)), ell)
        // What a note or a sticker lies on is not free.
        val note = owners.box(Cell(3, 8, 2, 1))
        assertEquals(listOf(Cell(5, 8, 1, 1)), owners.free(listOf(note)))
        // Only a handful are told.
        val checker = PageBoard(12, (0 until 12).flatMap { y -> (0 until 6).filter { (it + y) % 2 == 0 }.map { PageTile("$it-$y.owl", Cell(it, y, 1, 1)) } })
        assertEquals(PageBoard.FREE_RECTANGLES, checker.free().size)
    }

    // A note on the page.

    @Test fun onTheOwnersPageTheNoteLandsInTheFreeCornerAndCoversNoTile() {
        val spot = owners.noteSpot(emptyList())
        assertFalse(spot.compromised)
        assertEquals(Cell(3, 8, 3, 1), owners.cells(spot.box))
        assertTrue(clear(spot.box, owners.tileBoxes()))
        // It keeps its width and is only as tall as the one free row.
        assertEquals(NOTE_WIDTH, spot.box.w, 0f)
        assertEquals(owners.box(Cell(3, 8, 3, 1)).h, spot.box.h, 0f)
        assertTrue(spot.box.h < NOTE_HEIGHT)
        // The same through the agent's note, as it is saved.
        val left = left(Annotations(), owners, now = 1)
        assertEquals(Cell(3, 8, 3, 1), left.cells)
        assertTrue(left.clear)
        assertTrue(owners.tileBoxes().all { it.overlap(left.note.box()) == 0f })
        assertEquals(left.note, left.note.fitted())
    }

    @Test fun onAnEmptyPageANoteIsWholeAndSitsWhereATileWould() {
        val board = PageBoard(9)
        val spot = board.noteSpot(emptyList())
        assertEquals(board.box(Cell(0, 0, 1, 1)).x to board.box(Cell(0, 0, 1, 1)).y, spot.box.x to spot.box.y)
        assertEquals(NOTE_WIDTH to NOTE_HEIGHT, spot.box.w to spot.box.h)
        assertEquals(Cell(0, 0, 3, 2), board.cells(spot.box))
        // Wanted somewhere: the whole free cells nearest there.
        assertEquals(Cell(3, 4, 3, 2), board.cells(board.noteSpotAt(emptyList(), 3, 4).box))
        assertEquals(Cell(3, 7, 3, 2), board.cells(board.noteSpotAt(emptyList(), 5, 8).box))
    }

    @Test fun aNoteIsMadeSmallerOnlyWhenItFitsNowhereWhole() {
        // A whole note's cells are free at the bottom, though one row is free right where it is wanted.
        val board = PageBoard(9, listOf(PageTile("a.owl", Cell(0, 1, 6, 5))))
        val whole = board.noteSpot(emptyList())
        assertEquals(NOTE_WIDTH to NOTE_HEIGHT, whole.box.w to whole.box.h)
        assertEquals(Cell(0, 6, 3, 2), board.cells(whole.box))
        // Two columns free and no more: narrower, at its full height.
        val narrow = PageBoard(9, listOf(PageTile("a.owl", Cell(0, 0, 4, 9)))).let { it to it.noteSpot(emptyList()) }
        assertEquals(Cell(4, 0, 2, 2), narrow.first.cells(narrow.second.box))
        assertEquals(NOTE_HEIGHT, narrow.second.box.h, 0f)
        assertEquals(narrow.first.box(Cell(4, 0, 2, 1)).w, narrow.second.box.w, 0f)
        assertFalse(narrow.second.compromised)
        // On the tallest grid a note still fits one row, and is no smaller than a note may be kept.
        val tall = PageBoard(12, listOf(PageTile("a.owl", Cell(0, 0, 6, 11)), PageTile("b.owl", Cell(0, 11, 4, 1))))
        val small = tall.noteSpot(emptyList())
        assertFalse(small.compromised)
        assertEquals(Cell(4, 11, 2, 1), tall.cells(small.box))
        assertTrue(small.box.w >= AnnotationLimits.MIN_NOTE && small.box.h >= AnnotationLimits.MIN_NOTE / 2f)
    }

    @Test fun aFullPageShrinksTheNoteThenOverlapsAndSaysSo() {
        // One free cell is less than a note is made to fit: it overlaps, as little as it can, at its smallest.
        val board = PageBoard(9, listOf(PageTile("a.owl", Cell(0, 0, 6, 8)), PageTile("b.owl", Cell(0, 8, 5, 1))))
        val spot = board.noteSpot(emptyList())
        assertTrue(spot.compromised)
        assertTrue(spot.overlap > 0f)
        assertEquals(board.box(Cell(0, 0, 2, 1)).w to board.box(Cell(0, 0, 2, 1)).h, spot.box.w to spot.box.h)
        assertTrue(onPage(spot.box))
        // The free cell is part of its place, which is where it covers least: less than all of itself.
        assertTrue(board.cells(spot.box).overlaps(Cell(5, 8, 1, 1)))
        assertTrue(spot.overlap < spot.box.w * spot.box.h * 0.6f)
        val left = left(Annotations(), board, now = 1)
        assertFalse(left.clear)
        assertTrue(NOTE_OVERLAPS.contains("page is full"))
    }

    @Test fun aSecondNoteDoesNotLandOnTheFirst() {
        val board = PageBoard(9, listOf(PageTile("a.owl", Cell(0, 0, 6, 3))))
        var page = Annotations()
        repeat(AnnotationLimits.AGENT_NOTES) { page = left(page, board, now = 10L + it).also { left -> assertTrue(left.clear) }.page }
        val boxes = page.notes.map { it.box() }
        for (one in boxes) {
            assertTrue(clear(one, boxes - one))
            assertTrue(clear(one, board.tileBoxes()))
        }
        // Nor on the owner's notes or his sticker, wherever he put them. Between these two no whole cells are
        // clear, but a whole note fits off the cells: it goes there, at its full size.
        val mine = listOf(StickyNote("above", 0f, 0f, 1f, 0.4f, "mine"), StickyNote("below", 0f, 0.62f, 1f, 0.38f, "mine"))
        val star = Sticker("s1", StickerKind.Star, 0.9f, 0.5f, 0.14f)
        val between = left(Annotations(notes = mine, stickers = listOf(star)), PageBoard(9), now = 1)
        assertTrue(between.clear)
        assertTrue(clear(between.note.box(), mine.map { it.box() } + star.taken()))
        assertEquals(NOTE_WIDTH to NOTE_HEIGHT, between.note.w to between.note.h)
        assertTrue((0 until 9).none { abs(PageBoard(9).box(Cell(0, it, 1, 1)).y - between.note.y) < 0.001f })
    }

    @Test fun onAPageWithOneFreePlaceTheAgentsNextNoteTakesTheLastOnesPlace() {
        val first = left(Annotations(), owners, now = 1)
        val second = left(first.page, owners, now = 2)
        // Before anything is overlapped the agent's own older note makes way: one note, in the free cells, on no tile.
        assertEquals(first.note, second.replaced)
        assertTrue(second.clear)
        assertEquals(Cell(3, 8, 3, 1), second.cells)
        assertEquals(listOf(second.note), second.page.notes)
        // The owner's note there is never the one to go: the agent's overlaps, and says so.
        val mine = first.note.copy(id = "mine", author = Author.Owner)
        val over = left(Annotations(notes = listOf(mine)), owners, now = 3)
        assertNull(over.replaced)
        assertFalse(over.clear)
        assertTrue(mine in over.page.notes)
    }

    @Test fun aCellAskedForIsAHintAndNeverPutsTheNoteOnATile() {
        // In the middle of a widget: the nearest clear cells instead.
        val moved = left(Annotations(), owners, now = 1, near = 1 to 4)
        assertEquals(Cell(3, 8, 3, 1), moved.cells)
        assertTrue(moved.clear)
        // Free there: that is where it goes.
        assertEquals(Cell(2, 5, 3, 2), left(Annotations(), PageBoard(9), now = 1, near = 2 to 5).cells)
        // Not a cell of this grid: refused, with the grid's size.
        for (outside in listOf(6 to 0, 0 to 9, -1 to 0, 0 to -1)) {
            val refused = leaveAgentNote(Annotations(), "did it", 1, owners, outside) as NoteLeft.Refused
            assertTrue(refused.reason.contains("6 columns by 9 rows"))
        }
    }

    // Every way a note or sticker comes to be goes through the same placing.

    private val dir = Files.createTempDirectory("placing").toFile().apply { deleteOnExit() }

    private fun book(board: PageBoard): AnnotationBook {
        var time = 1_000L
        val book = AnnotationBook(AnnotationFiles(dir), background = { it() }, foreground = { it() }, defer = { _, save -> save?.invoke() }, now = { time++ }, board = { board })
        book.keep(setOf("p1"))
        book.open("p1")
        return book
    }

    @Test fun theOwnersNewNoteAndStickerKeepClearOfTheTilesToo() {
        val board = PageBoard(9, listOf(PageTile("a.owl", Cell(0, 0, 6, 2)), PageTile("b.owl", Cell(0, 2, 2, 7))))
        val book = book(board)
        try {
            // Pressed in the free part: the note comes up around his finger, on whole cells.
            val pressed = board.box(Cell(2, 6, 3, 2)).let { it.x + NOTE_WIDTH / 2f to it.y + NOTE_HEIGHT / 2f }
            val first = book.addNote("p1", pressed)
            assertEquals(Cell(2, 6, 3, 2), board.cells(book.on("p1").notes.first { it.id == first }.box()))
            // With no press, and with one on a widget: still on no tile, and not on the note before.
            for (near in listOf(null, 0.2f to 0.2f)) {
                val id = book.addNote("p1", near)
                val note = book.on("p1").notes.first { it.id == id }
                assertTrue(clear(note.box(), board.tileBoxes() + book.on("p1").notes.filter { it.id != id }.map { it.box() }))
                assertEquals(Author.Owner, note.author)
            }
            // A sticker asked for on a widget lands beside it, clear of the notes as well.
            val sticker = book.addSticker("p1", StickerKind.Star, 0.2f to 0.2f)
            val stuck = book.on("p1").stickers.first { it.id == sticker }
            assertTrue(clear(stuck.taken(), board.tileBoxes() + book.on("p1").notes.map { it.box() }))
            assertEquals(STICKER_SIZE, stuck.size, 0f)
            // And so does the agent's, through the same book.
            val left = book.leaveNote("p1", "did it") as NoteLeft.Left
            assertTrue(left.clear)
            assertTrue(clear(left.note.box(), board.tileBoxes() + book.on("p1").notes.filter { it.id != left.note.id }.map { it.box() } + stuck.taken()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun nothingPlacesANoteButThePlacing() {
        // The cascade that knew nothing of the tiles is gone, and every maker of a note or sticker asks the board.
        val sources = dev.omalauncher.theme.pack.mainSources()
        assertTrue(sources.none { it.readText().contains("cascade(") })
        val book = java.io.File("../chat/src/commonMain/kotlin/dev/omalauncher/widgets/annotations/AnnotationBook.kt").readText()
        assertTrue(book.substringAfter("fun addNote(").substringBefore("fun changeNote(").contains("board(page).noteSpot("))
        assertTrue(book.substringAfter("fun addSticker(").substringBefore("fun changeSticker(").contains("clearSpot("))
        assertTrue(book.substringAfter("fun leaveNote(").substringBefore("private fun top(").contains("leaveAgentNote(there.marks, text, now(), board(page), near)"))
        val makers = sources.filter { file -> Regex("StickyNote\\(\\s*(id = )?freshId\\(").containsMatchIn(file.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("AnnotationBook.kt", "Annotations.kt"), makers)
        // The launcher gives the book the notebook's own tiles (HomeAnnotations, the phone's; the book is shared since slice 5b).
        assertTrue(java.io.File("src/main/kotlin/dev/omalauncher/widgets/annotations/HomeAnnotations.kt").readText().contains("board = notebook::board"))
    }

    @Test fun theNotebookTellsThePagesTilesAsTheyShow() {
        val notebook = LiveNotebook()
        notebook.open(
            Notebook(
                9,
                listOf(
                    NotebookPage(
                        "p1",
                        "Page 1",
                        listOf(
                            NotebookItem("today.owl", ItemKind.Widget, Cell(0, 0, 3, 3)),
                            // Its file is missing: nothing reads it here, and its place is still its.
                            NotebookItem("gone.owl", ItemKind.Widget, Cell(3, 0, 3, 3)),
                            // An old saved size: an app shows as one cell.
                            NotebookItem("app:camera", ItemKind.App, Cell(0, 4, 2, 2), app = PinnedApp("app:camera", "Camera")),
                            NotebookItem("folder:1", ItemKind.Folder, Cell(1, 4, 1, 1), apps = listOf(PinnedApp("app:maps", "Maps"), PinnedApp("app:mail", "Mail"))),
                            // Not placed yet: it takes up nothing.
                            NotebookItem("new.owl", ItemKind.Widget, null),
                        ),
                    ),
                    NotebookPage("p2", "Page 2", listOf(NotebookItem("other.owl", ItemKind.Widget, Cell(0, 0, 6, 9)))),
                ),
            ),
            kept = false,
        )
        val board = notebook.board("p1")
        assertEquals(9, board.rows)
        assertEquals(
            listOf(PageTile("today.owl", Cell(0, 0, 3, 3)), PageTile("gone.owl", Cell(3, 0, 3, 3)), PageTile("Camera", Cell(0, 4, 1, 1), "app"), PageTile("Maps, Mail", Cell(1, 4, 1, 1), "folder")),
            board.tiles,
        )
        assertEquals(listOf(PageTile("other.owl", Cell(0, 0, 6, 9))), notebook.board("p2").tiles)
        assertTrue(notebook.board("p9").tiles.isEmpty())
    }
}
