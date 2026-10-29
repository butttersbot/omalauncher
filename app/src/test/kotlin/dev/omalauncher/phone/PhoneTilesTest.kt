package dev.omalauncher.phone

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.NoteLeft
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.PageTile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `oma-phone tile resize`: a widget's tile grows or shrinks from its corner, and never over another or off the grid. */
class PhoneTilesTest {
    private val deco = PageTile("deco.owl", Cell(0, 0, 6, 3))
    private val clock = PageTile("clock.owl", Cell(0, 6, 3, 2))

    private fun board(vararg tiles: PageTile) = PageBoard(rows = 9, tiles = tiles.toList())

    @Test fun growsFromItsCorner() {
        assertEquals(TileFit.Fits(Cell(0, 0, 6, 5)), PhoneTiles.fit(board(deco), "deco.owl", 6, 5, 3 to 3))
    }

    @Test fun shrinksToo() {
        assertEquals(TileFit.Fits(Cell(0, 0, 3, 3)), PhoneTiles.fit(board(deco), "deco.owl", 3, 3, 3 to 3))
    }

    @Test fun neverUnderItsMinSize() {
        assertTrue(PhoneTiles.fit(board(deco), "deco.owl", 2, 3, 3 to 3) is TileFit.Refused)
    }

    @Test fun neverPastTheGrid() {
        assertTrue(PhoneTiles.fit(board(deco), "deco.owl", 7, 3, 1 to 1) is TileFit.Refused)
        assertTrue(PhoneTiles.fit(board(deco), "deco.owl", 6, 10, 1 to 1) is TileFit.Refused)
    }

    @Test fun neverOverAnotherTile() {
        val refused = PhoneTiles.fit(board(deco, clock), "deco.owl", 6, 7, 1 to 1)
        assertTrue(refused is TileFit.Refused)
        assertTrue((refused as TileFit.Refused).reason.contains("clock.owl"))
        assertEquals(TileFit.Fits(Cell(0, 0, 6, 6)), PhoneTiles.fit(board(deco, clock), "deco.owl", 6, 6, 1 to 1))
    }

    @Test fun onlyWidgets() {
        val app = PageTile("Camera", Cell(0, 0, 1, 1), "app")
        assertTrue(PhoneTiles.fit(board(app), "Camera", 2, 2, 1 to 1) is TileFit.Refused)
    }

    @Test fun theReplyResizesOnlyWhatFits() {
        val pad = Pad(board(deco, clock))
        var answer: JSONObject? = null
        PhoneTiles.reply(JSONObject().put("action", "resize").put("name", "deco").put("size", "6x5"), pad) { answer = it }
        assertTrue(answer!!.getBoolean("ok"))
        assertEquals(5, answer!!.getJSONObject("cells").getInt("h"))
        assertEquals(3, answer!!.getJSONObject("was").getInt("h"))
        assertEquals("deco.owl" to Cell(0, 0, 6, 5), pad.resized)

        val refusing = Pad(board(deco, clock))
        PhoneTiles.reply(JSONObject().put("action", "resize").put("name", "deco.owl").put("size", "6x8"), refusing) { answer = it }
        assertFalse(answer!!.getBoolean("ok"))
        assertNull(refusing.resized)
    }

    private class Pad(
        private val board: PageBoard,
    ) : NotePad {
        var resized: Pair<String, Cell>? = null
        override val asked: String? = null
        override val showing: String? = "p6"
        override val pages = listOf("p6")

        override fun name(page: String) = "Page 6"

        override fun leave(
            page: String,
            text: String,
            near: Pair<Int, Int>?,
            then: (NoteLeft) -> Unit,
        ) = error("not used")

        override fun look(
            page: String,
            then: (PageBoard, Annotations?) -> Unit,
        ) = then(board, null)

        override fun pageOf(widget: String) = "p6".takeIf { board.tiles.any { it.name == widget } }

        override fun minSize(widget: String) = 3 to 3

        override fun resize(
            widget: String,
            cell: Cell,
        ) {
            resized = widget to cell
        }
    }
}
