package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The home page's arithmetic: a grid of 6 by R cells scaled as a whole into the room a page has. The rooms
 * here are the Fold's, in pixels: its cover (1248 wide) and half of its inner screen (1224 wide), less what
 * the status bar, the page name, the dock and the recent apps strip take, which is a guess no test here
 * depends on: every claim holds for any room.
 */
class GridGeometryTest {
    /** The cover's room for a page, and an inner half's: about 0.85 and 0.91 wide for their height. */
    private val cover = GridGeometry(1248, 1462, rows = 9)
    private val half = GridGeometry(1224, 1339, rows = 9, per = 2)

    /** A room taller than the page: the page takes its width, with margin above and below. */
    private val tall = GridGeometry(1248, 1700, rows = 9)

    /** Every cell rectangle a 6 x 9 page can hold, from a single cell to the whole page. */
    private val cells =
        buildList {
            for (x in 0 until 6) for (y in 0 until 9) for (w in 1..6 - x) for (h in 1..9 - y) add(Cell(x, y, w, h))
        }

    private fun close(
        expected: Float,
        actual: Float,
        what: String,
    ) = assertTrue("$what: $expected and $actual differ by more than 0.5%", abs(expected - actual) <= 0.005f * abs(expected))

    @Test fun thePageIsOneShapeScaledIntoItsRoomAndCentred() {
        // The cover's room is narrower for its height than the page: the page takes its whole height.
        assertEquals(1462f * PAGE_ASPECT, cover.pageWidth, 0.01f)
        assertEquals(1462f, cover.pageHeight, 0.01f)
        assertEquals((1248 - cover.pageWidth) / 2f, cover.pageLeft, 0.01f)
        assertEquals(0f, cover.pageTop, 0f)
        // A tall room: the page takes its whole width, and the rest is margin above and below.
        val tall = GridGeometry(1000, 2000, rows = 9)
        assertEquals(1000f, tall.pageWidth, 0f)
        assertEquals(1200f, tall.pageHeight, 0.01f)
        assertEquals(400f, tall.pageTop, 0.01f)
        assertEquals(PxRect(0, 400, 1000, 1600), tall.page)
        for (g in listOf(cover, half, tall)) {
            assertEquals(PAGE_ASPECT, g.pageWidth / g.pageHeight, 0.0001f)
            assertTrue(g.pageLeft >= 0f && g.pageTop >= 0f)
            assertTrue(g.pageWidth <= g.width && g.pageHeight <= g.height + 0.01f)
        }
    }

    /** AC-7: the same cell is the same shape, and the same share of the page, on the cover and on an inner half. */
    @Test fun aCellIsTheSameShapeAndShareOfThePageOnBothDisplays() {
        assertTrue(cover.pageWidth != half.pageWidth)
        // The tall room is the other case: the page is as wide as the room and centred down it.
        assertEquals(1248f, tall.pageWidth, 0f)
        assertTrue(tall.pageTop > 0f)
        assertEquals((1700 - tall.pageHeight) / 2f, tall.pageTop, 0.01f)
        assertEquals(tall.pageHeight, tall.gap / 1f + 9 * tall.cellH, 1f)
        for (other in listOf(half, tall)) {
            close(cover.cellW / cover.cellH, other.cellW / other.cellH, "a cell's shape")
            for (c in cells) {
                val a = cover.bounds(c)
                val b = other.bounds(c)
                close(a.width / a.height, b.width / b.height, "$c shape")
                close(a.width / cover.pageWidth, b.width / other.pageWidth, "$c width")
                close(a.height / cover.pageHeight, b.height / other.pageHeight, "$c height")
                // Where it starts, as a share of the page; compared against the page, since a share near 0 has no percent.
                assertEquals("$c left", (a.left - cover.pageLeft) / cover.pageWidth, (b.left - other.pageLeft) / other.pageWidth, 0.0005f)
                assertEquals("$c top", (a.top - cover.pageTop) / cover.pageHeight, (b.top - other.pageTop) / other.pageHeight, 0.0005f)
                // And it is on the page, not in the margin around it.
                assertTrue("$c", b.top >= other.pageTop && b.bottom <= other.pageTop + other.pageHeight + 0.01f)
            }
        }
    }

    /** The same in whole pixels, where a tile's edges are rounded: its share of the page stays within 0.5% of the page. */
    @Test fun roundedToPixelsATileKeepsItsShareOfThePage() {
        for (other in listOf(half, tall)) {
            for (c in cells) {
                val a = cover.rect(c)
                val b = other.rect(c)
                assertEquals("$c width", a.width / cover.pageWidth, b.width / other.pageWidth, 0.005f)
                assertEquals("$c height", a.height / cover.pageHeight, b.height / other.pageHeight, 0.005f)
                assertEquals("$c left", (a.left - cover.pageLeft) / cover.pageWidth, (b.left - other.pageLeft) / other.pageWidth, 0.005f)
                assertEquals("$c top", (a.top - cover.pageTop) / cover.pageHeight, (b.top - other.pageTop) / other.pageHeight, 0.005f)
            }
        }
    }

    /** The gap is 6dp on a page 468dp wide, and scales with the page. */
    @Test fun theGapIsAFixedShareOfThePagesWidth() {
        assertEquals(6f / 468f, GAP_SHARE, 0f)
        assertEquals(16, GridGeometry(1248, 1700, rows = 9).gap)
        assertEquals(8, GridGeometry(624, 850, rows = 9).gap)
        for (g in listOf(cover, half, tall)) {
            val first = g.bounds(Cell(0, 0, 1, 1))
            assertEquals(g.pageWidth / 78f, first.left - g.pageLeft, 0.001f)
            assertEquals(g.pageWidth / 78f, first.top - g.pageTop, 0.001f)
            assertEquals((g.pageWidth - g.pageWidth / 78f) / 6f, g.cellW, 0.001f)
            assertEquals((g.pageHeight - g.pageWidth / 78f) / 9f, g.cellH, 0.001f)
        }
    }

    @Test fun everyCellOfOneGridHasOneShape() {
        for (g in listOf(cover, half, tall)) {
            val one = g.bounds(Cell(0, 0, 1, 1))
            for (x in 0 until 6) for (y in 0 until 9) {
                val b = g.bounds(Cell(x, y, 1, 1))
                close(one.width, b.width, "width at $x,$y")
                close(one.height, b.height, "height at $x,$y")
            }
        }
    }

    /** Nothing is transposed: columns run across and rows down in every room, a wide one included. */
    @Test fun nothingIsLaidOnItsSide() {
        val wide = GridGeometry(2448, 1400, rows = 9)
        for (g in listOf(cover, half, wide)) {
            assertEquals(6, g.cols)
            assertEquals(9, g.rows)
            val first = g.rect(Cell(0, 0, 1, 1))
            val across = g.rect(Cell(1, 0, 1, 1))
            val down = g.rect(Cell(0, 1, 1, 1))
            assertTrue(across.left > first.left && across.top == first.top)
            assertTrue(down.top > first.top && down.left == first.left)
            // A 3x2 tile is wider than tall wherever it is shown.
            g.rect(Cell(0, 0, 3, 2)).let { assertTrue(it.width > it.height) }
        }
        // The wide room holds the same page, as large as its height allows, in the middle.
        assertEquals(1400f, wide.pageHeight, 0.01f)
        assertEquals((2448 - wide.pageWidth) / 2f, wide.pageLeft, 0.01f)
    }

    @Test fun theRowsAreTheNotebooksWhateverTheRoom() {
        for ((w, h) in listOf(1248 to 1462, 1224 to 1339, 2448 to 1400, 300 to 200, 1848 to 1900)) {
            assertEquals(9, GridGeometry(w, h, rows = 9).rows)
            assertEquals(12, GridGeometry(w, h, rows = 12).rows)
        }
        // More rows on the same page are shorter cells, not a longer page.
        val twelve = GridGeometry(1248, 1462, rows = 12)
        assertEquals(cover.pageHeight, twelve.pageHeight, 0f)
        assertEquals(cover.cellW, twelve.cellW, 0f)
        assertTrue(twelve.cellH < cover.cellH)
    }

    @Test fun tilesStayOnThePageWithAGapAround() {
        for (g in listOf(cover, half)) {
            val page = g.page
            assertTrue(g.gap >= 1)
            for (c in cells) {
                val r = g.rect(c)
                assertTrue("$c", r.left >= page.left && r.top >= page.top && r.right <= page.right && r.bottom <= page.bottom)
                assertTrue("$c", r.width > 0 && r.height > 0)
            }
            // The gap before a tile is the gap after its neighbour.
            val a = g.bounds(Cell(0, 0, 3, 2))
            val b = g.bounds(Cell(3, 0, 3, 2))
            assertEquals(a.left - g.pageLeft, b.left - a.right, 0.01f)
            assertEquals(a.left - g.pageLeft, g.pageLeft + g.pageWidth - b.right, 0.01f)
        }
    }

    @Test fun savedCellsAreShownWithinTheGrid() {
        val grid = cover
        assertEquals(Cell(1, 2, 3, 4), grid.fit(Cell(1, 2, 3, 4), true))
        // Too big: cut to the grid, and pulled in from the far edges.
        assertEquals(Cell(1, 0, 5, 9), grid.fit(Cell(4, 0, 5, 20), true))
        assertEquals(Cell(0, 0, 6, 1), grid.fit(Cell(-2, -1, 9, 0), true))
        // Apps show as one cell wherever an old cell put them.
        assertEquals(Cell(2, 3, 1, 1), grid.fit(Cell(2, 3, 3, 3), false))
        assertEquals(Cell(5, 8, 1, 1), grid.fit(Cell(7, 12, 3, 3), false))
    }

    @Test fun nearestLines() {
        for (g in listOf(cover, half)) {
            // Lines run down the middle of the gaps: each cell's own corner is nearest its own lines.
            for (x in 0..6) assertEquals(x, g.colLine(g.pageLeft + g.gap / 2f + x * g.cellW))
            for (y in 0..9) assertEquals(y, g.rowLine(g.pageTop + g.gap / 2f + y * g.cellH))
            assertEquals(2, g.colLine(g.pageLeft + g.gap / 2f + 2.4f * g.cellW))
            assertEquals(3, g.colLine(g.pageLeft + g.gap / 2f + 2.6f * g.cellW))
            // In the margin beside the page, and beyond it: the first and last lines.
            assertEquals(0, g.colLine(0f))
            assertEquals(0, g.colLine(-500f))
            assertEquals(6, g.colLine(g.width.toFloat()))
            assertEquals(6, g.colLine(9000f))
            assertEquals(0, g.rowLine(-1f))
            assertEquals(9, g.rowLine(9000f))
        }
    }

    @Test fun pagesSideBySide() {
        assertEquals(0, half.side(0f))
        assertEquals(0, half.side(1223f))
        assertEquals(1, half.side(1224f))
        assertEquals(1, half.side(5000f))
        assertEquals(0, half.side(-40f))
        assertEquals(0, half.left(0))
        assertEquals(1224, half.left(1))
        // One page: every point is over it.
        assertEquals(0, cover.side(5000f))
    }

    @Test fun aSlotShowsItsPagesAndNothingPastTheLast() {
        val pages = listOf("p1", "p3", "p4")
        assertEquals(listOf("p1"), pagesAt(0, pages, 1))
        assertEquals(listOf("p4"), pagesAt(2, pages, 1))
        assertEquals(listOf("p1", "p3"), pagesAt(0, pages, 2))
        // An odd last page: the right half has no page.
        assertEquals(listOf("p4", null), pagesAt(1, pages, 2))
        assertEquals(listOf(null, null), pagesAt(2, pages, 2))
        assertEquals(listOf<String?>(null), pagesAt(3, pages, 1))
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
