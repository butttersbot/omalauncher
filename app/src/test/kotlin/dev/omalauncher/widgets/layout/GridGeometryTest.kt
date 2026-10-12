package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the home grid's arithmetic as ui/HomeGrid.kt and ui/Home.kt had it. The Fold's sizes are its
 * panels' whole windows with an 18px gap (6dp at a density of 3, an assumption): the grid on the
 * phone is smaller by the status bar, the bar and the recent apps strip, which no test here knows.
 */
class GridGeometryTest {
    private val gap = 18

    @Test fun rowsOfSquareishCells() {
        // Cover, 1248x1972: cells 205 wide, 9.53 of them down.
        assertEquals(9, squareRows(1248, 1972, gap))
        // Half of the inner screen, 1224x1848.
        assertEquals(9, squareRows(1224, 1848, gap))
        // The inner screen whole, upright (1848x2448) and as a single wide page.
        assertEquals(7, squareRows(1848, 2448, gap))
        assertEquals(4, squareRows(2448, 1848, gap))
        // Never none.
        assertEquals(1, squareRows(1000, 100, gap))
    }

    @Test fun theFoldsWindows() {
        // Cover: one page, upright.
        assertFalse(showsTwoPages(1248, 1972))
        assertFalse(homeTurned(1248, 1972))
        assertEquals(9, GridGeometry(1248, 1972, gap).rows)
        // Inner, 2448x1848: each half is 0.662 wide for its height, just past what counts as phone-shaped, so today's
        // rule shows one page on its side there, with the rows portrait had (7 from the same window upright).
        assertFalse(showsTwoPages(2448, 1848))
        assertTrue(homeTurned(2448, 1848))
        assertEquals(7, GridGeometry(2448, 1848, gap, turned = true).rows)
        assertEquals(9, GridGeometry(2448, 1848, gap, turned = true, portraitRows = 9).rows)
        // Inner, upright.
        assertFalse(showsTwoPages(1848, 2448))
        assertFalse(homeTurned(1848, 2448))
        assertEquals(7, GridGeometry(1848, 2448, gap).rows)
        // Were it shown as two pages, each half would have the cover's rows.
        assertEquals(9, GridGeometry(1224, 1848, gap, per = 2).rows)
        // Both orientations of a window share their saved row count.
        assertEquals("1848x2448", windowKey(2448, 1848))
        assertEquals("1848x2448", windowKey(1848, 2448))
        assertEquals("1248x1972", windowKey(1248, 1972))
    }

    @Test fun twoPagesWhenEachHalfIsPhoneShaped() {
        assertTrue(showsTwoPages(2208, 1840))
        assertFalse(homeTurned(2208, 1840))
        // From 0.4 to 0.65 wide for its height, both included.
        assertTrue(showsTwoPages(800, 1000))
        assertFalse(showsTwoPages(798, 1000))
        assertTrue(showsTwoPages(1300, 1000))
        assertFalse(showsTwoPages(1302, 1000))
        // A phone on its side.
        assertFalse(showsTwoPages(2340, 1080))
        assertTrue(homeTurned(2340, 1080))
    }

    @Test fun neverFewerRowsThanTilesReach() {
        val grid = GridGeometry(1248, 1972, gap, used = 12)
        assertEquals(12, grid.rows)
        // The same width; shorter cells.
        assertEquals(205f, grid.cellW, 0f)
        assertEquals(1954 / 12f, grid.cellH, 0f)
        assertEquals(9, GridGeometry(1248, 1972, gap, used = 4).rows)
        // On its side too, over the rows portrait had.
        assertEquals(11, GridGeometry(1972, 1248, gap, turned = true, portraitRows = 9, used = 11).rows)
    }

    @Test fun cellsToPixels() {
        val grid = GridGeometry(1248, 1972, gap)
        assertEquals(6, grid.cols)
        assertEquals(205f, grid.cellW, 0f)
        assertEquals(1954 / 9f, grid.cellH, 0f)
        // A gap before each tile; the next cell's gap ends it.
        assertEquals(PxRect(18, 18, 615, 434), grid.rect(Cell(0, 0, 3, 2)))
        assertEquals(PxRect(633, 1538, 1230, 1954), grid.rect(Cell(3, 7, 3, 2)))
        assertEquals(PxRect(18, 18, 205, 217), grid.rect(Cell(0, 0, 1, 1)))
    }

    @Test fun landscapeLaysTheGridOnItsSide() {
        val upright = GridGeometry(1248, 1972, gap)
        val turned = GridGeometry(1972, 1248, gap, turned = true, portraitRows = upright.rows)
        assertEquals(upright.rows, turned.rows)
        // A cell's width and height trade places, and so do its tiles' sides.
        assertEquals(upright.cellH, turned.cellW, 0f)
        assertEquals(upright.cellW, turned.cellH, 0f)
        for (c in listOf(Cell(0, 0, 3, 2), Cell(3, 7, 3, 2), Cell(5, 8, 1, 1))) {
            val r = upright.rect(c)
            assertEquals(PxRect(r.top, r.left, r.bottom, r.right), turned.rect(c))
        }
        // Lines too: columns are counted down the screen.
        assertEquals(upright.colLine(420f, 1000f), turned.colLine(1000f, 420f))
        assertEquals(upright.rowLine(420f, 1000f), turned.rowLine(1000f, 420f))
    }

    @Test fun savedCellsAreShownWithinTheGrid() {
        val grid = GridGeometry(1248, 1972, gap)
        assertEquals(Cell(1, 2, 3, 4), grid.fit(Cell(1, 2, 3, 4), true))
        // Too big: cut to the grid, and pulled in from the far edges.
        assertEquals(Cell(1, 0, 5, 9), grid.fit(Cell(4, 0, 5, 20), true))
        assertEquals(Cell(0, 0, 6, 1), grid.fit(Cell(-2, -1, 9, 0), true))
        // Apps show as one cell wherever an old cell put them.
        assertEquals(Cell(2, 3, 1, 1), grid.fit(Cell(2, 3, 3, 3), false))
        assertEquals(Cell(5, 8, 1, 1), grid.fit(Cell(7, 12, 3, 3), false))
    }

    @Test fun nearestLines() {
        val grid = GridGeometry(1248, 1972, gap)
        assertEquals(0, grid.colLine(100f, 0f))
        assertEquals(1, grid.colLine(112f, 0f))
        // Lines run down the middle of the gaps, half a gap in: 111 is still nearer the first, 117 down too.
        assertEquals(0, grid.colLine(111f, 0f))
        assertEquals(0, grid.rowLine(0f, 117f))
        assertEquals(1, grid.rowLine(0f, 118f))
        assertEquals(2, grid.colLine(420f, 0f))
        assertEquals(6, grid.colLine(5000f, 0f))
        assertEquals(0, grid.colLine(-50f, 0f))
        assertEquals(5, grid.rowLine(0f, 1000f))
        assertEquals(9, grid.rowLine(0f, 1972f))
        assertEquals(0, grid.rowLine(0f, -1f))
    }

    @Test fun pagesSideBySide() {
        val grid = GridGeometry(1224, 1848, gap, per = 2)
        assertEquals(0, grid.side(0f))
        assertEquals(0, grid.side(1223f))
        assertEquals(1, grid.side(1224f))
        assertEquals(1, grid.side(5000f))
        assertEquals(0, grid.side(-40f))
        assertEquals(0, grid.left(0))
        assertEquals(1224, grid.left(1))
        // One page: every point is over it.
        assertEquals(0, GridGeometry(1248, 1972, gap).side(5000f))
    }

    @Test fun anEmptyPageWaitsAfterTheLast() {
        assertEquals(listOf(1), shownPages(listOf(1), 1) { true })
        assertEquals(listOf(1, 2), shownPages(listOf(1), 1) { false })
        // Page numbers may have holes; the new page follows the last.
        assertEquals(listOf(1, 3, 7, 8), shownPages(listOf(1, 3, 7), 1) { false })
        assertEquals(listOf(1, 3, 7), shownPages(listOf(1, 3, 7), 1) { it == 7 })
    }

    @Test fun inPairsAnOddLastPageGetsAPartner() {
        assertEquals(listOf(1, 2), shownPages(listOf(1), 2) { true })
        assertEquals(listOf(1, 2), shownPages(listOf(1), 2) { false })
        assertEquals(listOf(1, 3, 4, 5), shownPages(listOf(1, 3), 2) { false })
        assertEquals(listOf(1, 2, 3, 4), shownPages(listOf(1, 2, 3), 2) { it == 3 })
        assertEquals(listOf(1, 3), pagesAt(0, listOf(1, 3, 4, 5), 2))
        assertEquals(listOf(4, 5), pagesAt(1, listOf(1, 3, 4, 5), 2))
        // A slot past the end shows page 1.
        assertEquals(listOf(1, 1), pagesAt(2, listOf(1, 3, 4, 5), 2))
        assertEquals(listOf(4), pagesAt(2, listOf(1, 3, 4, 5), 1))
    }

    @Test fun wallpaperSlidesWithThePages() {
        assertEquals(0.5f, wallpaperOffset(0, 0f, 1), 0f)
        assertEquals(0.5f, wallpaperOffset(0, 0f, 0), 0f)
        assertEquals(0f, wallpaperOffset(0, 0f, 4), 0f)
        assertEquals(0.5f, wallpaperOffset(1, 0.5f, 4), 0f)
        assertEquals(1f, wallpaperOffset(3, 0f, 4), 0f)
        // Overscroll stays within the wallpaper.
        assertEquals(1f, wallpaperOffset(3, 0.2f, 4), 0f)
        assertEquals(0f, wallpaperOffset(0, -0.3f, 4), 0f)
    }
}
