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

    private fun inner(layer: Layer = Layer.ABC, w: Float = 704f, screen: Screen = Screen.PORTRAIT) =
        KeyGeometry.layout(w, screen, layer, mech = true, upper = false, capsLock = false, returnLabel = "return", split = true)

    /** Boss, 2026-10-17: the inner screen's keyboard split in two, a half for each thumb, at the cover keyboard's key size. */
    @Test fun theInnerScreenSplitsIntoTwoThumbHalves() {
        val l = inner()
        val u = KeyGeometry.SPLIT_UNIT
        assertEquals(u, l.unit, 1e-4f)
        val right = 704f - 4f - 5 * u
        // The left half starts at the left edge and the right one ends at the right edge, with a wide gap between.
        assertEquals(4f, l.key("q").cell.x, 1e-4f)
        assertEquals(4f + 4 * u, l.key("t").cell.x, 1e-4f)
        assertEquals(right, l.key("y").cell.x, 1e-4f)
        assertEquals(704f - 4f, l.key("p").cell.x + l.key("p").cell.w, 1e-4f)
        assertTrue(l.key("y").cell.x - (l.key("t").cell.x + u) > 150f)
        // Apple's half-key step on the second row, in both halves; g left, h right.
        assertEquals(4f + u / 2, l.key("a").cell.x, 1e-4f)
        assertEquals(right + u / 2, l.key("h").cell.x, 1e-4f)
        // ⇧ and ⌫ at the outer ends; z..v on the left, b..m on the right.
        assertEquals(4f, l.key("⇧").cell.x, 1e-4f)
        assertEquals(704f - 4f, l.key("⌫").cell.x + l.key("⌫").cell.w, 1e-4f)
        assertTrue(l.key("v").cell.x < 352f && l.key("b").cell.x > 352f)
        // A space under each thumb, the mic and return on the right as in one piece.
        val bottom = l.keys.filter { it.row == 3 }.map { it.label }
        assertEquals(listOf("123", "🙂", "space", "space", "🎤", "return"), bottom)
        // Every letter once, and the same rows as Apple's.
        assertEquals(('a'..'z').toList(), l.keys.mapNotNull { it.letter }.sorted())
        for ((row, letters) in KeyGeometry.LETTERS.withIndex()) assertEquals(letters.toList(), l.keys.filter { it.row == row }.mapNotNull { it.letter })
    }

    @Test fun everyLayerSplitsWithNothingOverlappingOrOffTheBoard() {
        for (screen in listOf(Screen.PORTRAIT to 704f, Screen.LANDSCAPE to 932f)) for (layer in Layer.entries) {
            val l = inner(layer, screen.second, screen.first)
            for (row in 0..3) {
                val cells = l.keys.filter { it.row == row }.sortedBy { it.cell.x }
                assertTrue("$layer $row", cells.first().cell.x >= 4f - 1e-3f && cells.last().cell.x + cells.last().cell.w <= screen.second - 4f + 1e-3f)
                cells.zipWithNext().forEach { (a, b) -> assertTrue("$layer $row ${a.label} ${b.label}", a.cell.x + a.cell.w <= b.cell.x + 1e-3f) }
            }
            // The split has as many keys as one piece, plus the second space.
            val whole = KeyGeometry.layout(screen.second, screen.first, layer, true, false, false, "return")
            assertEquals("$layer", whole.keys.size + 1, l.keys.size)
        }
    }

    @Test fun theCoverIsNeverSplitAndATouchInTheGapGoesToTheNearestKey() {
        val asked = KeyGeometry.layout(475f, Screen.COVER, Layer.ABC, true, false, false, "return", split = true)
        assertEquals(cover().keys.map { it.label to it.cell.x }, asked.keys.map { it.label to it.cell.x })
        val l = inner()
        val y = l.key("t").cell.cy
        assertEquals("t", l.hitPlain(l.key("t").cell.x + l.key("t").cell.w + 20f, y)?.label)
        assertEquals("y", l.hitPlain(l.key("y").cell.x - 20f, y)?.label)
    }
}
