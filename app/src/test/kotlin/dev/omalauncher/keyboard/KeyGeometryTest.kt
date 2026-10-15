package dev.omalauncher.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeometryTest {
    private fun cover(
        layer: Layer = Layer.ABC,
        mech: Boolean = true,
    ) = KeyGeometry.layout(475f, Screen.COVER, layer, mech, upper = false, capsLock = false, returnLabel = "return")

    private fun KeyLayout.key(label: String) = keys.first { it.label == label }

    @Test fun theScreensAreToldApartByTheirSize() {
        assertEquals(Screen.COVER, Screen.of(475f, 751f))
        assertEquals(Screen.PORTRAIT, Screen.of(704f, 932f))
        assertEquals(Screen.LANDSCAPE, Screen.of(932f, 704f))
    }

    /** The same numbers as geom() in design/oma-keyboard-practice.html, where Boss practised. */
    @Test fun theCoverMatchesThePracticePage() {
        val l = cover()
        val u = (475f - 8f) / 10
        assertEquals(u, l.unit, 1e-4f)
        assertEquals(4f, l.key("q").cell.x, 1e-4f)
        assertEquals(4f + u / 2, l.key("a").cell.x, 1e-4f)
        assertEquals(4f + 1.5f * u, l.key("z").cell.x, 1e-4f)
        assertEquals(10f + 4 * 54f + 8f, l.height, 1e-4f)
        // Mechanical caps are 2 dp apart; flat ones 6.
        assertEquals(u - 2f, l.key("q").cap.w, 1e-4f)
        assertEquals(u - 6f, cover(mech = false).key("q").cap.w, 1e-4f)
        // Shift and ⌫ show 1.25 units of their 1.5.
        assertEquals(1.25f * u - 2f, l.key("⇧").cap.w, 1e-4f)
        assertEquals(4f + 8.75f * u + 1f, l.key("⌫").cap.x, 1e-4f)
    }

    @Test fun theBottomRowIsReturnPlacementA() {
        val row = cover().keys.filter { it.row == 3 }
        assertEquals(listOf("123", "🙂", "space", "🎤", "return"), row.map { it.label })
        assertEquals(475f - 8f, row.sumOf { it.cell.w.toDouble() }.toFloat(), 1e-3f)
        assertEquals(KeyAction.Return, row.last().action)
    }

    @Test fun everyRowFillsTheWidth() {
        for (layer in Layer.entries) {
            val l = cover(layer)
            for (r in 0..3) {
                val row = l.keys.filter { it.row == r }
                assertTrue("$layer row $r starts in", row.first().cell.x >= 4f - 1e-3f)
                assertTrue("$layer row $r ends in", row.last().cell.x + row.last().cell.w <= 471f + 1e-3f)
            }
        }
    }

    @Test fun aTouchInAGapGoesToTheNearestKeyInItsRow() {
        val l = cover()
        val a = l.key("a")
        // Left of A, in the half-key gap at the row's start.
        assertEquals("a", l.hitPlain(5f, a.cell.cy)?.label)
        assertEquals("s", l.hitPlain(l.key("s").cell.cx, a.cell.cy)?.label)
        // Above the first row and below the last stay in them.
        assertEquals(0, l.hitPlain(100f, -20f)?.row)
        assertEquals(3, l.hitPlain(100f, 900f)?.row)
    }

    @Test fun theAimMovesALetterOnlyWhereItsSpotIsNearer() {
        val l = cover()
        val c = l.key("c")
        val x = l.key("x")
        // A touch just past C's left edge is X's cell; Boss lands on C some 15 dp left of its centre, so with his aim it is C.
        val tx = c.cell.x - 2f
        val ty = c.cell.cy + 3f
        assertEquals("x", l.hit(tx, ty, null)?.label)
        assertEquals("c", l.hit(tx, ty, KeyGeometry.COVER_AIM)?.label)
        // X's own centre is still X.
        assertEquals("x", l.hit(x.cell.cx, x.cell.cy, KeyGeometry.COVER_AIM)?.label)
    }

    @Test fun theAimLeavesShiftBackspaceAndTheBottomRowAlone() {
        val l = cover()
        for (label in listOf("⇧", "⌫", "space", "return", "🎤")) {
            val k = l.key(label)
            assertEquals(label, l.hit(k.cell.cx, k.cell.cy, KeyGeometry.COVER_AIM)?.label)
        }
    }

    @Test fun upperCaseShowsOnlyOnTheLetterLayer() {
        val up = KeyGeometry.layout(475f, Screen.COVER, Layer.ABC, true, upper = true, capsLock = true, returnLabel = "send")
        assertEquals("Q", up.keys.first().label)
        assertEquals("⇪", up.keys.first { it.action == KeyAction.Shift }.label)
        assertEquals("send", up.keys.last().label)
        assertNull(up.keys.first { it.label == "Q" }.letter?.takeIf { it != 'q' })
    }
}
