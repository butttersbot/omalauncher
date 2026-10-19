package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Manga look's panel layouts: the mockup's geometry, and cells that tile the page on any grid. */
class PanelLayoutsTest {
    private val W = PanelLayouts.PAGE_W
    private val H = PanelLayouts.PAGE_H

    /** What the mockup's own panelsOf makes on its 6 by 10 grid (made with node from manga-mockup.html; see PanelLayouts.kt). */
    private val mockup = JSONObject(javaClass.getResource("/manga/mockup-panels-rows10.json")!!.readText())

    private fun near(want: Double, got: Double, what: String) = assertEquals(what, want, got, 1e-9)

    private fun points(want: JSONArray, got: List<PanelPoint>, what: String) {
        assertEquals("$what: corners", want.length(), got.size)
        for (i in got.indices) {
            near(want.getJSONArray(i).getDouble(0), got[i].x * W, "$what: corner $i x")
            near(want.getJSONArray(i).getDouble(1), got[i].y * H, "$what: corner $i y")
        }
    }

    @Test fun theEightLayoutsAreTheMockupsInItsOrderAndNames() {
        assertEquals(listOf("opening", "beats", "tallstack", "action5", "splash3", "yonkoma", "grid6", "splash"), PanelLayouts.ids)
        val names = mockup.getJSONObject("_names")
        for (id in PanelLayouts.ids) assertEquals(names.getString(id), PanelLayouts.name(id))
        assertNull(PanelLayouts.name("collage"))
        assertFalse(PanelLayouts.known("collage"))
        assertFalse(PanelLayouts.known(null))
    }

    @Test fun onTheMockupsGridEveryPanelIsTheMockupsExactly() {
        for (id in PanelLayouts.ids) {
            val want = mockup.getJSONArray(id)
            val got = PanelLayouts.panels(id, 6, 10)
            assertEquals("$id: panels", want.length(), got.size)
            for (i in got.indices) {
                val w = want.getJSONObject(i)
                val what = "$id panel $i"
                val c = w.getJSONObject("cell")
                assertEquals(what, Cell(c.getInt("c"), c.getInt("r"), c.getInt("w"), c.getInt("h")), got[i].cell)
                points(w.getJSONArray("pts"), got[i].outline, what)
                if (w.isNull("bleed")) assertNull(what, got[i].bleed) else points(w.getJSONArray("bleed"), got[i].bleed!!, "$what bleed")
                val inner = w.getJSONObject("inner")
                near(inner.getDouble("x"), got[i].inner.x * W, "$what inner x")
                near(inner.getDouble("y"), got[i].inner.y * H, "$what inner y")
                near(inner.getDouble("w"), got[i].inner.w * W, "$what inner w")
                near(inner.getDouble("h"), got[i].inner.h * H, "$what inner h")
                assertEquals(what, w.getDouble("area"), got[i].area * W * H, 1e-6)
            }
        }
    }

    @Test fun everyLayoutsCellsTileTheGridExactlyOnAnyRows() {
        for (columns in listOf(6, 4, 8)) for (rows in 4..24) for (id in PanelLayouts.ids) {
            val panels = PanelLayouts.panels(id, columns, rows)
            assertTrue("$id on $columns×$rows", panels.isNotEmpty())
            val hits = Array(rows) { IntArray(columns) }
            for (p in panels) {
                assertTrue("$id on $columns×$rows: ${p.cell} has a cell", p.cell.w >= 1 && p.cell.h >= 1)
                assertTrue("$id on $columns×$rows: ${p.cell} on the grid", p.cell.x >= 0 && p.cell.y >= 0 && p.cell.right <= columns && p.cell.bottom <= rows)
                for (y in p.cell.y until p.cell.bottom) for (x in p.cell.x until p.cell.right) hits[y][x]++
            }
            for (y in 0 until rows) for (x in 0 until columns) assertEquals("$id on $columns×$rows: cell $x,$y", 1, hits[y][x])
        }
    }

    @Test fun theFoldsRowsGiveTheSamePanelsInTheSameOrder() {
        // However many rows the Fold's notebook has, each layout keeps its count of panels and their reading order.
        for (rows in listOf(NOTEBOOK_MIN_ROWS, 10, 11, 12, 14, 16)) for (id in PanelLayouts.ids) {
            val panels = PanelLayouts.panels(id, 6, rows)
            assertEquals("$id on $rows rows", mockup.getJSONArray(id).length(), panels.size)
            assertEquals("$id on $rows rows: the first panel starts top left", 0, panels.first().cell.x + panels.first().cell.y)
        }
    }

    @Test fun onlyAPanelAsWideAsThePageBleeds() {
        for (columns in listOf(6, 8)) for (rows in listOf(9, 10, 13)) for (id in PanelLayouts.ids) for (p in PanelLayouts.panels(id, columns, rows)) {
            val wide = p.cell.x == 0 && p.cell.w == columns
            assertEquals("$id ${p.cell}", wide, p.bleed != null)
            p.bleed?.let { b ->
                // It runs to both side edges of the page, past the margins the outline keeps.
                assertEquals(0.0, b.minOf { it.x }, 1e-9)
                assertEquals(1.0, b.maxOf { it.x }, 1e-9)
                assertTrue(b.minOf { it.y } <= p.outline.minOf { it.y } + 1e-9 && b.maxOf { it.y } >= p.outline.maxOf { it.y } - 1e-9)
            }
        }
        assertNotNull(PanelLayouts.panels("splash", 6, 10).single().bleed)
    }

    @Test fun outlinesStayInsideTheMarginsAndWidgetsInsideTheirOutline() {
        val s = PanelStyle()
        for (rows in listOf(9, 10, 15)) for (id in PanelLayouts.ids) for (p in PanelLayouts.panels(id, 6, rows)) {
            assertTrue(p.outline.all { it.x >= s.marginLeft / W - 1e-9 && it.x <= 1 - s.marginRight / W + 1e-9 })
            assertTrue(p.outline.all { it.y >= s.marginTop / H - 1e-9 && it.y <= 1 - s.marginBottom / H + 1e-9 })
            assertTrue(p.inner.w > 0 && p.inner.h > 0)
            assertTrue(p.inner.x >= p.outline.minOf { it.x } && p.inner.x + p.inner.w <= p.outline.maxOf { it.x })
            assertTrue(p.inner.y >= p.outline.minOf { it.y } && p.inner.y + p.inner.h <= p.outline.maxOf { it.y })
        }
    }

    @Test fun aLooksStyleMovesTheInkButNeverTheCells() {
        val wide = PanelStyle(marginLeft = 0.0, marginRight = 0.0, marginTop = 0.0, marginBottom = 0.0, gutterRows = 6.0, gutterCols = 4.0)
        for (id in PanelLayouts.ids) {
            val plain = PanelLayouts.panels(id, 6, 12)
            val styled = PanelLayouts.panels(id, 6, 12, wide)
            assertEquals(plain.map { it.cell }, styled.map { it.cell })
        }
        assertEquals(0.0, PanelLayouts.panels("splash", 6, 12, wide).single().outline.minOf { it.x }, 1e-9)
    }

    @Test fun aGridTooSmallForALayoutsCutsHasNoPanels() {
        assertEquals(emptyList<Panel>(), PanelLayouts.panels("yonkoma", 6, 3))
        assertFalse(PanelLayouts.fits("yonkoma", 6, 3))
        assertTrue(PanelLayouts.fits("beats", 6, 3))
        assertEquals(emptyList<Panel>(), PanelLayouts.panels("grid6", 1, 10))
        assertEquals(listOf(Cell(0, 0, 1, 1)), PanelLayouts.panels("splash", 1, 1).map { it.cell })
        assertEquals(emptyList<Panel>(), PanelLayouts.panels("collage", 6, 10))
        assertEquals(emptyList<Panel>(), PanelLayouts.panels("splash", 6, 0))
    }
}
