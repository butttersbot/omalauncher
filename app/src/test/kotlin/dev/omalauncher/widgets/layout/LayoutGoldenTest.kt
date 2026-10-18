package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home grid's arithmetic at six columns, pinned by its outputs over a sweep of inputs (Soma Canvas slice 1): the
 * layout core moved into shared code and learned other column counts, and the Fold's six-column answers must not have
 * moved by a cell or a pixel. [GOLDEN] was taken from the code before the move.
 */
class LayoutGoldenTest {
    private fun sweep(): String {
        val out = StringBuilder()
        val taken = listOf(Cell(0, 0, 2, 2), Cell(3, 1, 3, 2), Cell(1, 4, 1, 1), Cell(4, 5, 2, 3))
        for (w in 1..6) for (h in 1..4) for (rows in listOf(4, 6, 9)) out.append(firstFit(w, h, taken, 6, rows)).append(';')
        for (size in listOf(null, 1 to 1, 2 to 3, 6 to 9, 9 to 2, 0 to 0)) for (rows in listOf(3, 8)) out.append(placedSize(size, 6, rows)).append(';')
        for ((c, cols) in listOf(Cell(0, 0, 1, 1) to 4, Cell(1, 2, 3, 4) to 4, Cell(3, 2, 3, 4) to 6, Cell(2, 0, 4, 1) to 8, Cell(7, 1, 5, 2) to 12, Cell(11, 0, 1, 1) to 12)) out.append(rescale(c, cols)).append(';')
        for (wp in listOf(1080, 1248, 2448)) for (d in listOf(2f, 2.625f, 3f)) out.append(estimatedGeometry(wp, d)).append(';')
        for ((wd, ht) in listOf(1248 to 1972, 2448 to 1848, 1000 to 700)) for (rows in listOf(5, 8)) for (per in listOf(1, 2)) {
            val g = GridGeometry(wd / per, ht, rows, per)
            out.append(g.cols).append(',').append(g.gap).append(',').append(g.rect(Cell(0, 0, 1, 1))).append(',').append(g.rect(Cell(2, 1, 3, 2)))
                .append(',').append(g.colLine(wd / per / 2f)).append(',').append(g.rowLine(ht / 3f)).append(';')
        }
        out.append(usedRows(listOf(Cell(0, 2, 2, 3) to true, Cell(1, 6, 2, 2) to false, null to true)))
        return out.toString()
    }

    @Test fun theSixColumnGridAnswersAsItAlwaysDid() {
        val now = sweep()
        assertEquals("hash ${now.hashCode()} length ${now.length}", GOLDEN, "${now.hashCode()}:${now.length}")
    }

    /** Other screens' grids: the same arithmetic at 8 columns (the car) and 12 (a desktop), and landscape pages. */
    @Test fun otherColumnCountsPlaceAndMeasure() {
        val taken = listOf(Cell(0, 0, 4, 3), Cell(4, 0, 4, 2))
        assertEquals(Cell(4, 2, 4, 2), firstFit(4, 2, taken, 8, 5))
        assertEquals(null, firstFit(5, 3, taken, 8, 5))
        assertEquals(Cell(8, 0, 4, 3), firstFit(4, 3, taken, 12, 8))
        assertEquals(6 to 3, placedSize(null, 12, 8))
        assertEquals(Cell(4, 0, 4, 2), rescale(Cell(3, 0, 3, 2), 6, to = 8))
        val (w8, _, _) = estimatedGeometry(1920, 2f, columns = 8)
        assertEquals((1920 - 12) / 8f, w8, 0.001f)
        val car = GridGeometry(1920, 1200, rows = 5, columns = 8, aspect = 1920f / 1200f)
        assertEquals(8, car.cols)
        assertEquals(1920f, car.pageWidth, 0.5f)
        assertEquals(1200f, car.pageHeight, 0.5f)
        assertEquals(8, car.colLine(1919f))
        val last = car.rect(Cell(7, 4, 1, 1))
        // The last cell ends one gap short of the page's edge, as on the Fold.
        assertTrue("the last cell ends a gap from the page's edge: $last (gap ${car.gap})", kotlin.math.abs(last.right - (1920 - car.gap)) <= 1 && kotlin.math.abs(last.bottom - (1200 - car.gap)) <= 1)
    }

    private companion object {
        const val GOLDEN = "1244909117:2894"
    }
}
