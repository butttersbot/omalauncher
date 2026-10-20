package dev.omalauncher.widgets.layout

import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.GRID_COLUMNS
import dev.omalauncher.widgets.style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manga page fills the room it has, top to bottom (the Manga look, with no dock below it): its panels are laid over the
 * whole room, the look's margins and gutters stay as wide on screen as on the page of [PAGE_ASPECT] the room would fit,
 * and nothing saved changes: the same layout cuts the same cells. The rooms are the Fold-sized emulator's, in pixels: the
 * cover's (968 wide, the page names and the status bar above, nothing below) and half the inner screen's.
 */
class FilledPageTest {
    private val rows = 10
    private val cover = GridGeometry(968, 2150, rows)
    private val coverFilled = GridGeometry(968, 2150, rows, fill = true)
    private val half = GridGeometry(1104, 1700, rows, per = 2)
    private val halfFilled = GridGeometry(1104, 1700, rows, per = 2, fill = true)

    /** The Manga look's own [panels] style, as shipped. */
    private val manga = PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/manga")), "manga").pack.panels!!.style()

    @Test fun aFilledPageIsTheWholeRoom() {
        for (g in listOf(coverFilled, halfFilled)) {
            assertEquals(PxRect(0, 0, g.width, g.height), g.page)
            assertEquals(g.width.toFloat(), g.pageWidth, 0f)
            assertEquals(g.height.toFloat(), g.pageHeight, 0f)
        }
        // Unfilled, the cover's tall room keeps its page in the middle with paper above and below: what Boss saw stop short.
        assertTrue(cover.pageHeight < cover.height * 0.6f)
        assertTrue(cover.pageTop > 0f)
        // The page of PAGE_ASPECT a filled room would fit is the unfilled page.
        assertEquals(cover.pageWidth, coverFilled.fitWidth, 0f)
        assertEquals(cover.pageHeight, coverFilled.fitHeight, 0f)
        assertEquals(half.pageWidth, halfFilled.fitWidth, 0f)
    }

    /** The same cells, only taller: a cell is the same share of the page, and the bottom row ends at the room's foot. */
    @Test fun aFilledPageKeepsItsCellsAndRunsToTheFoot() {
        assertEquals(cover.cols, coverFilled.cols)
        assertEquals(cover.rows, coverFilled.rows)
        // The bottom row ends a gap above the room's foot, as every tile keeps a gap after it.
        val last = coverFilled.rect(Cell(0, rows - 1, GRID_COLUMNS, 1))
        assertEquals((coverFilled.height - coverFilled.gap).toFloat(), last.bottom.toFloat(), 1f)
        val whole = coverFilled.rect(Cell(0, 0, GRID_COLUMNS, rows))
        assertEquals(coverFilled.gap, whole.top)
        assertTrue(coverFilled.cellH > cover.cellH * 1.5f)
        assertEquals(cover.cellW, coverFilled.cellW, 0.01f)
    }

    /** An unfilled page's panel style is the look's own; free pages and other looks are as they were. */
    @Test fun anUnfilledPageKeepsTheLooksStyle() {
        assertEquals(manga, cover.panelStyle(manga))
        assertEquals(manga, half.panelStyle(manga))
    }

    /** For every layout on both rooms and any rows a notebook has: the cells are the unfilled page's, cut the same way, in order. */
    @Test fun everyLayoutCutsTheSameCellsFilled() {
        for (n in listOf(9, 10, 12)) for (id in PanelLayouts.ids) for (room in listOf(968 to 2150, 1104 to 1700, 1080 to 1690)) {
            val g = GridGeometry(room.first, room.second, n, fill = true)
            val plain = PanelLayouts.panels(id, GRID_COLUMNS, n, manga)
            val filled = PanelLayouts.panels(id, GRID_COLUMNS, n, g.panelStyle(manga))
            assertTrue(id, plain.isNotEmpty())
            assertEquals(id, plain.map { it.cell }, filled.map { it.cell })
            // The full-width panels still bleed, and only they.
            assertEquals(id, plain.map { it.bleed != null }, filled.map { it.bleed != null })
        }
    }

    /**
     * On screen, the filled page's margins and gutters are as wide as the unfilled page's, in pixels, while its panels reach
     * from just under the room's top to just over its foot.
     */
    @Test fun marginsAndGuttersKeepTheirSizeOnScreen() {
        for ((plain, filled) in listOf(cover to coverFilled, half to halfFilled)) {
            fun px(
                g: GridGeometry,
                panels: List<Panel>,
            ): DoubleArray {
                val top = panels.minOf { p -> p.outline.minOf { it.y } } * g.pageHeight
                val bottom = g.pageHeight - panels.maxOf { p -> p.outline.maxOf { it.y } } * g.pageHeight
                val left = panels.minOf { p -> p.outline.minOf { it.x } } * g.pageWidth
                val right = g.pageWidth - panels.maxOf { p -> p.outline.maxOf { it.x } } * g.pageWidth
                return doubleArrayOf(top, bottom, left, right)
            }
            val a = px(plain, PanelLayouts.panels("beats", GRID_COLUMNS, rows, plain.panelStyle(manga)))
            val b = px(filled, PanelLayouts.panels("beats", GRID_COLUMNS, rows, filled.panelStyle(manga)))
            for (i in a.indices) assertEquals("margin $i", a[i], b[i], 0.5)
            // The filled page's panels run down to within its bottom margin of the room's foot.
            assertEquals(filled.height - b[1], filled.height * PanelLayouts.panels("beats", GRID_COLUMNS, rows, filled.panelStyle(manga)).maxOf { p -> p.outline.maxOf { it.y } }, 0.5)

            // A row gutter: between the stacked panels of "beats", the gap down from the first to the second.
            fun rowGap(
                g: GridGeometry,
                style: PanelStyle,
            ): Double {
                val (one, two) = PanelLayouts.panels("beats", GRID_COLUMNS, rows, style)
                return (two.outline.minOf { it.y } - one.outline.maxOf { it.y }) * g.pageHeight
            }
            assertEquals(rowGap(plain, plain.panelStyle(manga)), rowGap(filled, filled.panelStyle(manga)), 0.5)

            // A column gutter: between the two panels side by side in grid6's first row.
            fun colGap(
                g: GridGeometry,
                style: PanelStyle,
            ): Double {
                val (one, two) = PanelLayouts.panels("grid6", GRID_COLUMNS, rows, style)
                return (two.outline.minOf { it.x } - one.outline.maxOf { it.x }) * g.pageWidth
            }
            assertEquals(colGap(plain, plain.panelStyle(manga)), colGap(filled, filled.panelStyle(manga)), 0.5)
        }
    }

    /** On the cover the panels end far below where the unfilled page's did: in the bottom few percent of the room. */
    @Test fun onTheCoverThePanelsReachTheFoot() {
        val filled = PanelLayouts.panels("opening", GRID_COLUMNS, rows, coverFilled.panelStyle(manga))
        val foot = filled.maxOf { p -> p.outline.maxOf { it.y } } * coverFilled.pageHeight
        assertTrue("$foot", foot > coverFilled.height * 0.97)
        val plain = PanelLayouts.panels("opening", GRID_COLUMNS, rows, manga)
        val plainFoot = cover.pageTop + plain.maxOf { p -> p.outline.maxOf { it.y } } * cover.pageHeight
        assertFalse("$plainFoot", plainFoot > cover.height * 0.85)
    }
}
