package dev.omalauncher.theme

import dev.omalauncher.theme.pack.PageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPaperTest {
    private val cover = LockPaper.key("riso", 1248, 1972)
    private val inner = LockPaper.key("riso", 2448, 1848)

    @Test fun eachDisplayOfALookIsItsOwn() {
        assertEquals("riso|1248x1972", cover)
        assertFalse(cover == inner)
        assertFalse(cover == LockPaper.key("bujo", 1248, 1972))
    }

    @Test fun paperIsWantedOnceForADisplayAndOnlyWhenTurnedOn() {
        assertTrue(LockPaper.wanted(true, PageKind.Paper, emptySet(), cover))
        assertTrue(LockPaper.wanted(true, PageKind.Wallpaper, setOf(cover), inner))
        assertFalse(LockPaper.wanted(true, PageKind.Paper, setOf(cover), cover))
        assertFalse(LockPaper.wanted(false, PageKind.Paper, emptySet(), cover))
    }

    @Test fun aLookThatLeavesThePageToTheSystemHasNoPaperToGive() {
        assertFalse(LockPaper.wanted(true, PageKind.System, emptySet(), cover))
    }

    @Test fun theScrapsKeepClearOfSamsungsClockAndShortcuts() {
        // Folded: the clock is in the top third and the shortcuts in the bottom tenth.
        val cover = LockPaper.scraps(1248, 1972)
        assertEquals(2, cover.size)
        assertTrue(cover.all { it.top >= 1972 * 0.45f && it.bottom <= 1972 * 0.80f && it.left >= 0f && it.right <= 1248f })
        // Unfolded they stand to the right, where the clock is not.
        val inner = LockPaper.scraps(2448, 1848)
        assertTrue(inner.all { it.left >= 2448 * 0.5f && it.right <= 2448f && it.bottom <= 1848 * 0.75f })
        assertEquals(1, cover.count { it.taped })
        assertEquals(1, cover.count { it.alt })
    }

    @Test fun theOtherDisplayOfAFoldIsTheOneOtherSizeSeen() {
        val seen = setOf("1248x1972", "2448x1848")
        // Open, the other is the cover; folded, the other is the open display.
        val cover = LockPaper.otherDisplay(seen, 2448, 1848)!!
        assertEquals(listOf(1248, 1972, LockPaper.SUB_DISPLAY), listOf(cover.width, cover.height, cover.mark))
        val open = LockPaper.otherDisplay(seen, 1248, 1972)!!
        assertEquals(listOf(2448, 1848, LockPaper.MAIN_DISPLAY), listOf(open.width, open.height, open.mark))
        // A phone with one display, or sizes that can't be told apart into one other, has none.
        assertEquals(null, LockPaper.otherDisplay(setOf("1248x1972"), 1248, 1972))
        assertEquals(null, LockPaper.otherDisplay(setOf("1248x1972", "2448x1848", "1848x2448"), 1248, 1972))
        assertEquals(null, LockPaper.otherDisplay(setOf("junk", "0x0"), 1248, 1972))
    }
}
