package dev.omalauncher.widgets

import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.PinnedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PRD UP-1: a tile carried to a page with no room for it is refused there, and the next page with room is offered. */
class FullPageDropTest {
    private val rows = 4
    private val wall = Cell(0, 0, 6, 4)
    private val pages = listOf("p1", "p2", "p3", "p4", "p5")

    private fun next(
        from: Int,
        w: Int,
        h: Int,
        taken: Map<String, List<Cell>>,
    ) = nextPageWithRoom(pages, from, w, h, GRID_COLUMNS, rows) { taken[it].orEmpty() }

    @Test fun theNextPageWithRoomIsOffered() {
        // Every page after the first is empty: the very next one.
        assertEquals("p2", next(0, 3, 2, mapOf("p1" to listOf(wall))))
        // Full pages in between are passed over.
        assertEquals("p4", next(0, 3, 2, mapOf("p2" to listOf(wall), "p3" to listOf(wall))))
        assertEquals("p5", next(1, 3, 2, mapOf("p3" to listOf(wall), "p4" to listOf(wall))))
    }

    @Test fun roomIsRoomAtTheTilesOwnSize() {
        // Two rows are free on p2 and one cell on p3: a 6x2 fits the first, a 6x3 neither, an app both.
        val taken = mapOf("p2" to listOf(Cell(0, 0, 6, 2)), "p3" to listOf(Cell(0, 0, 6, 3), Cell(0, 3, 5, 1)), "p4" to listOf(wall), "p5" to listOf(wall))
        assertEquals("p2", next(0, 6, 2, taken))
        assertNull(next(0, 6, 3, taken))
        assertEquals("p2", next(0, 1, 1, taken))
        assertEquals("p3", next(1, 1, 1, taken))
        // A tile bigger than the grid has room nowhere.
        assertNull(next(0, 7, 1, emptyMap()))
        assertNull(next(0, 1, 5, emptyMap()))
    }

    @Test fun neverAnEarlierPageNorTheOneItIsOver() {
        // Only p1 and p2 have room: from p2 on there is nothing to offer but a new page.
        val taken = mapOf("p3" to listOf(wall), "p4" to listOf(wall), "p5" to listOf(wall))
        assertEquals("p2", next(0, 2, 2, taken))
        assertNull(next(1, 2, 2, taken))
        assertNull(next(2, 2, 2, taken))
        // Past the last page, and from nowhere, there is none either; from before the first, the first counts.
        assertNull(next(4, 1, 1, emptyMap()))
        assertNull(next(9, 1, 1, emptyMap()))
        assertEquals("p1", next(-1, 1, 1, emptyMap()))
    }

    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)

    /**
     * Today has room; Trip is full but for one cell; Work's only free rows are kept for a widget whose file is
     * missing; Wall is full; Last is empty.
     */
    private val saved =
        Notebook(
            rows = rows,
            pages =
                listOf(
                    NotebookPage("p1", "Today", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)))),
                    NotebookPage(
                        "p2",
                        "Trip",
                        listOf(
                            NotebookItem("map.owl", ItemKind.Widget, Cell(0, 0, 6, 3)),
                            // Saved wider than an app shows: it takes one cell, whatever this says.
                            NotebookItem(chrome.key, ItemKind.App, Cell(0, 3, 5, 1), app = chrome),
                        ),
                    ),
                    NotebookPage("p3", "Work", listOf(NotebookItem("mail.owl", ItemKind.Widget, Cell(0, 0, 6, 2)), NotebookItem("gone.owl", ItemKind.Widget, Cell(0, 2, 6, 2)))),
                    NotebookPage("p4", "Wall", listOf(NotebookItem("wall.owl", ItemKind.Widget, wall))),
                    NotebookPage("p5", "Last"),
                ),
        )

    private fun widget(name: String) = WidgetItem(WidgetSpec(File(name), name.removeSuffix(".owl"), startPage = 1, order = 0, program = null))

    private val notebook = LiveNotebook().also { it.open(saved, kept = true) }
    private val layout = WidgetLayout(notebook)
    private val tiles: List<HomeItem> = listOf("clock.owl", "map.owl", "mail.owl", "wall.owl").map(::widget) + Shortcut(chrome.key, chrome.label)

    private fun itemsOn(page: String) = tiles.filter { notebook.pageOf(it.key) == page }

    private fun room(
        cell: Cell,
        page: String,
        mover: String = "clock.owl",
    ) = layout.roomFor(cell, mover, page, itemsOn(page), GRID_COLUMNS, rows)

    @Test fun aPageHasRoomOnlyWhereNothingShowsAndNothingIsKept() {
        val clock = notebook.cell("clock.owl")!!
        // Trip: the app shows as one cell, so five are free beside it; that is room for an app, not for the clock.
        assertFalse(room(clock, "p2"))
        assertTrue(room(Cell(0, 0, 1, 1), "p2"))
        assertTrue(room(Cell(0, 0, 5, 1), "p2"))
        assertFalse(room(Cell(0, 0, 6, 1), "p2"))
        // Work: the rows that look free are a missing widget's place, and stay its.
        assertFalse(room(clock, "p3"))
        assertFalse(room(Cell(0, 0, 1, 1), "p3"))
        assertFalse(room(clock, "p4"))
        assertTrue(room(clock, "p5"))
        // The tile's own cells are not in its way.
        assertTrue(room(wall, "p4", mover = "wall.owl"))
    }

    @Test fun theClockCarriedOverAFullPageIsOfferedTheNextWithRoom() {
        val clock = notebook.cell("clock.owl")!!
        val before = notebook.snapshot()
        // Over Trip, Work or Wall: none has room, the last page does.
        for (over in listOf("p2", "p3", "p4")) assertEquals("p5", layout.nextWithRoom(clock, "clock.owl", over, ::itemsOn, GRID_COLUMNS, rows))
        // An app over the wall likewise; from Today it would have been Trip, which has a cell for it.
        assertEquals("p5", layout.nextWithRoom(Cell(0, 0, 1, 1), chrome.key, "p4", ::itemsOn, GRID_COLUMNS, rows))
        assertEquals("p2", layout.nextWithRoom(Cell(0, 0, 1, 1), "new", "p1", ::itemsOn, GRID_COLUMNS, rows))
        // With the last page full too there is nothing to turn to: a new page is the offer.
        notebook.setCell("filler.owl", wall)
        notebook.expect("filler.owl", "p5")
        assertNull(layout.nextWithRoom(clock, "clock.owl", "p2", ::itemsOn, GRID_COLUMNS, rows))
        // Asking moved nothing: the clock is where it was, and so is everything else.
        notebook.forget("filler.owl")
        assertEquals(before, notebook.snapshot())
    }
}
