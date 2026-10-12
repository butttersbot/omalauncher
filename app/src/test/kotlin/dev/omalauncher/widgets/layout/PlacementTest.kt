package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins how the home grid finds cells today, as [dev.omalauncher.widgets.WidgetLayout] did before the arithmetic moved here. */
class PlacementTest {
    private val cells = LinkedHashMap<String, Cell>()
    private var saves = 0
    private val placement = Placement<Tile>(cells, { saves++ }) { it }

    private fun widget(
        key: String,
        w: Int,
        h: Int,
    ) = Tile(key, w to h)

    private fun app(key: String) = Tile(key, 1 to 1, resizable = false)

    @Test fun firstFreeSpotRowByRow() {
        val items = listOf(widget("a", 4, 2), widget("b", 2, 2), widget("c", 3, 1), app("d"))
        assertEquals(emptyList<Tile>(), placement.place(items, 6, 9))
        assertEquals(Cell(0, 0, 4, 2), cells["a"])
        assertEquals(Cell(4, 0, 2, 2), cells["b"])
        assertEquals(Cell(0, 2, 3, 1), cells["c"])
        assertEquals(Cell(3, 2, 1, 1), cells["d"])
        assertEquals(1, saves)
        assertEquals(6 to 9, placement.grid)
    }

    @Test fun withoutADeclaredSizeHalfTheWidthByThree() {
        placement.place(listOf(Tile("a", null), Tile("b", null), Tile("c", null)), 6, 9)
        assertEquals(listOf(Cell(0, 0, 3, 3), Cell(3, 0, 3, 3), Cell(0, 3, 3, 3)), cells.values.toList())
        cells.clear()
        // On a narrower grid, half of that; never under one cell, never taller than the grid.
        placement.place(listOf(Tile("a", null)), 1, 2)
        assertEquals(Cell(0, 0, 1, 2), cells["a"])
    }

    @Test fun savedCellsAreNeverRewritten() {
        // Saved on a taller grid, and over each other: both stay.
        cells["a"] = Cell(0, 10, 6, 4)
        cells["b"] = Cell(0, 11, 2, 2)
        assertEquals(emptyList<Tile>(), placement.place(listOf(widget("a", 1, 1), widget("b", 1, 1)), 6, 5))
        assertEquals(Cell(0, 10, 6, 4), cells["a"])
        assertEquals(Cell(0, 11, 2, 2), cells["b"])
        assertEquals(0, saves)
    }

    @Test fun aFullPageLeavesItemsWithoutACell() {
        cells["wall"] = Cell(0, 0, 6, 9)
        val wall = widget("wall", 6, 9)
        val late = app("late")
        val big = widget("big", 2, 2)
        assertEquals(listOf(late, big), placement.place(listOf(wall, late, big), 6, 9))
        assertNull(cells["late"])
        assertNull(cells["big"])
        assertEquals(0, saves)
    }

    @Test fun whatFitsIsPlacedEvenWhenAnEarlierItemDoesNot() {
        cells["wall"] = Cell(0, 0, 6, 8)
        val big = widget("big", 2, 2)
        val small = app("small")
        assertEquals(listOf(big), placement.place(listOf(widget("wall", 6, 8), big, small), 6, 9))
        assertEquals(Cell(0, 8, 1, 1), cells["small"])
        assertEquals(1, saves)
    }

    @Test fun anItemBiggerThanTheGridIsCutToIt() {
        placement.place(listOf(widget("huge", 9, 30)), 6, 9)
        assertEquals(Cell(0, 0, 6, 9), cells["huge"])
        cells.clear()
        // A size of nothing still takes a cell.
        placement.place(listOf(widget("none", 0, -2)), 6, 9)
        assertEquals(Cell(0, 0, 1, 1), cells["none"])
    }

    @Test fun shortcutsCountAsOneCellWhateverAnOldSavedCellSays() {
        // Saved 3x3 before apps were fixed at 1x1; it takes up only its top-left cell.
        cells["app"] = Cell(0, 0, 3, 3)
        placement.place(listOf(app("app"), widget("w", 5, 3)), 6, 9)
        assertEquals(Cell(1, 0, 5, 3), cells["w"])
        // The saved cell itself is left as it was.
        assertEquals(Cell(0, 0, 3, 3), cells["app"])
    }

    @Test fun onlyTheItemsGivenAreInTheWay() {
        // A cell saved for something on another page doesn't block this one.
        cells["elsewhere"] = Cell(0, 0, 6, 9)
        placement.place(listOf(widget("a", 2, 2)), 6, 9)
        assertEquals(Cell(0, 0, 2, 2), cells["a"])
    }

    @Test fun itemsAreIdentifiedByKey() {
        // The same key twice: the first gets the cell, the second is already placed.
        assertEquals(emptyList<Tile>(), placement.place(listOf(widget("a", 2, 2), widget("a", 3, 3)), 6, 9))
        assertEquals(mapOf("a" to Cell(0, 0, 2, 2)), cells)
    }

    @Test fun claimNeedsAPageLaidOutFirst() {
        assertFalse(placement.claim(widget("a", 2, 2), emptyList()))
        assertTrue(cells.isEmpty())
        assertEquals(0, saves)
    }

    @Test fun claimTakesTheFirstFreeSpotAmongTheOthers() {
        val a = widget("a", 4, 2)
        placement.place(listOf(a), 6, 9)
        saves = 0
        assertTrue(placement.claim(widget("new", 3, 3), listOf(a)))
        assertEquals(Cell(0, 2, 3, 3), cells["new"])
        assertEquals(1, saves)
    }

    /** Two pages laid out in the same frame: the other page's tiles haven't got their cells yet when one moves in. */
    @Test fun claimPlacesTheOthersFirst() {
        placement.place(emptyList(), 6, 9)
        val a = widget("a", 6, 4)
        val b = widget("b", 6, 4)
        assertTrue(placement.claim(widget("new", 3, 1), listOf(a, b)))
        // The page's own tiles keep the spots they'd have had; the newcomer takes what's left.
        assertEquals(Cell(0, 0, 6, 4), cells["a"])
        assertEquals(Cell(0, 4, 6, 4), cells["b"])
        assertEquals(Cell(0, 8, 3, 1), cells["new"])
        // Saved for the others, then for the newcomer.
        assertEquals(2, saves)
    }

    @Test fun claimOnAFullPageFailsButStillPlacesTheOthers() {
        placement.place(emptyList(), 6, 9)
        val a = widget("a", 6, 5)
        val b = widget("b", 6, 4)
        assertFalse(placement.claim(widget("new", 1, 1), listOf(a, b)))
        assertEquals(Cell(0, 0, 6, 5), cells["a"])
        assertEquals(Cell(0, 5, 6, 4), cells["b"])
        assertFalse(cells.containsKey("new"))
        // Saved once, for the others.
        assertEquals(1, saves)
    }

    @Test fun claimMovesAnItemThatAlreadyHasACell() {
        val a = widget("a", 3, 3)
        val mover = widget("m", 3, 3)
        placement.place(listOf(a), 6, 9)
        cells["m"] = Cell(0, 6, 3, 3)
        // Among the others its own old cell isn't in the way, and it is given a new one.
        assertTrue(placement.claim(mover, listOf(a, mover)))
        assertEquals(Cell(3, 0, 3, 3), cells["m"])
    }

    @Test fun rescaleMovesACellOntoSixColumns() {
        assertEquals(Cell(1, 2, 3, 4), rescale(Cell(1, 2, 3, 4), 6))
        // From four columns: positions and widths times 6/4, rounded down.
        assertEquals(Cell(0, 0, 3, 2), rescale(Cell(0, 0, 2, 2), 4))
        assertEquals(Cell(3, 1, 3, 2), rescale(Cell(2, 1, 2, 2), 4))
        assertEquals(Cell(1, 0, 1, 1), rescale(Cell(1, 0, 1, 1), 4))
        assertEquals(Cell(4, 0, 1, 1), rescale(Cell(3, 0, 1, 1), 4))
        assertEquals(Cell(0, 5, 6, 1), rescale(Cell(0, 5, 4, 1), 4))
        // From twelve: halved, but never narrower than a cell.
        assertEquals(Cell(2, 0, 1, 3), rescale(Cell(5, 0, 1, 3), 12))
        assertEquals(Cell(3, 0, 3, 1), rescale(Cell(6, 0, 6, 1), 12))
        // Rows are never touched, and a column count that makes no sense leaves the cell alone.
        assertEquals(Cell(2, 7, 2, 9), rescale(Cell(2, 7, 2, 9), 0))
        assertEquals(Cell(2, 7, 2, 9), rescale(Cell(2, 7, 2, 9), -3))
    }

    @Test fun rescaleKeepsACellOnTheGrid() {
        // A cell that hung over the right edge of its own grid ends at this one's.
        assertEquals(Cell(4, 0, 2, 1), rescale(Cell(3, 0, 5, 1), 4))
    }

    /** A cell that started past its own grid has no width left to give; loading skips such an entry. */
    @Test fun rescaleRefusesACellBeyondItsGrid() {
        assertTrue(runCatching { rescale(Cell(9, 0, 1, 1), 4) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { rescale(Cell(4, 0, 1, 1), 4) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun rowsReachedCountAppsAsOneCell() {
        assertEquals(0, usedRows(emptyList()))
        assertEquals(0, usedRows(listOf(null to true, null to false)))
        assertEquals(7, usedRows(listOf(Cell(0, 4, 2, 3) to true, Cell(0, 5, 3, 3) to false, null to true)))
        assertEquals(6, usedRows(listOf(Cell(0, 5, 3, 3) to false)))
    }

    @Test fun tileSizeInPixels() {
        val geometry = Triple(205f, 217.1f, 18)
        assertEquals(597 to 416, tilePx(3 to 2, geometry, turned = false))
        // On its side, its columns run down the screen.
        assertEquals(392 to 633, tilePx(3 to 2, geometry, turned = true))
        // Cut down to whole pixels, not rounded: 616.5 and 435.5 lose their halves.
        assertEquals(598 to 417, tilePx(3 to 2, Triple(205.5f, 217.75f, 18), turned = false))
        assertEquals(3 to 2, shownSize(3 to 2, false))
        assertEquals(2 to 3, shownSize(3 to 2, true))
    }

    @Test fun geometryGuessedFromTheScreenWidth() {
        assertEquals(Triple(205f, 205f, 18), estimatedGeometry(1248, 3f))
        assertEquals(Triple(177.16667f, 177.16667f, 17), estimatedGeometry(1080, 2.8125f))
    }
}
