package dev.omalauncher.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLearningTest {
    private val l = KeyGeometry.layout(475f, Screen.COVER, Layer.ABC, true, upper = false, capsLock = false, returnLabel = "return")

    private fun key(c: String) = l.keys.first { it.label == c }

    private fun KeyboardLearning.tap(
        c: String,
        dx: Float = 0f,
        dy: Float = 0f,
    ) = key(c).let { letter(it, it.cell.cx + dx, it.cell.cy + dy, l) }

    @Test fun aLetterThatStandsIsKeptOnceTheNextKeyComes() {
        val learn = KeyboardLearning()
        learn.tap("e", 2f, 5f)
        assertTrue(learn.kept.isEmpty())
        learn.other()
        assertEquals(1, learn.kept['e']!!.n)
        assertEquals(2.0, learn.kept['e']!!.sx, 1e-6)
        assertEquals(5.0, learn.kept['e']!!.sy, 1e-6)
    }

    @Test fun aLetterErasedAndReplacedByItsNeighbourIsASlip() {
        val learn = KeyboardLearning()
        // Meant C, hit X (its left neighbour), erased it, typed C.
        val x = key("x")
        val c = key("c")
        learn.letter(x, c.cell.cx - 15f, c.cell.cy, l)
        learn.back()
        learn.tap("c")
        learn.finish()
        assertEquals(1, learn.slips["c>x"])
        assertEquals(-15.0, learn.fixed['c']!!.sx, 1e-4)
        assertNull(learn.kept['x'])
        assertEquals(1, learn.kept['c']!!.n)
    }

    @Test fun anErasedLetterReplacedByAFarOneIsNoSlip() {
        val learn = KeyboardLearning()
        learn.tap("q")
        learn.back()
        learn.tap("m")
        learn.finish()
        assertTrue(learn.slips.isEmpty())
        assertTrue(learn.fixed.isEmpty())
    }

    @Test fun whatIsSavedHasNoTextAndComesBack() {
        val learn = KeyboardLearning()
        for (w in "hello there") if (w == ' ') learn.other() else learn.tap(w.toString(), 1f, -2f)
        learn.letter(key("w"), key("e").cell.cx - 20f, key("e").cell.cy, l)
        learn.back()
        learn.tap("e")
        learn.finish()
        val json = learn.toJson()
        assertFalse(learn.dirty)
        // Only per-letter sums: no run of letters in the order typed.
        assertFalse("hel" in json || "there" in json)
        val again = KeyboardLearning().apply { load(json) }
        assertEquals(learn.kept.keys, again.kept.keys)
        assertEquals(learn.kept['l']!!.n, again.kept['l']!!.n)
        assertEquals(learn.kept['e']!!.sx, again.kept['e']!!.sx, 0.05)
        assertEquals(1, again.slips["e>w"])
        assertEquals(1, again.fixed['e']!!.n)
    }

    @Test fun aReviewedAimTableIsRead() {
        val aim = KeyboardLearning.parseAim("{\"a\": [-7.8, -7.3], \"c\":[-15,3.4]}")!!
        assertEquals(-7.8f to -7.3f, aim['a'])
        assertEquals(-15f to 3.4f, aim['c'])
        assertNull(KeyboardLearning.parseAim("{}"))
    }
}
