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
}
