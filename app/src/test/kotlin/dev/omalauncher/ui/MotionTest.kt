package dev.omalauncher.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic of home's motion: what grows out of a tile, when it fades instead, and when nothing moves at all. */
class MotionTest {
    private val window = Rect(0f, 0f, 1812f, 2176f)
    private val tile = Rect(120f, 640f, 700f, 1000f)

    @Test fun aGrowStartsAtTheTileAndEndsAtTheWindow() {
        assertEquals(tile, growRect(tile, window, 0f))
        assertEquals(window, growRect(tile, window, 1f))
        // A value past either end is held to it.
        assertEquals(tile, growRect(tile, window, -0.2f))
        assertEquals(window, growRect(tile, window, 1.3f))
    }

    @Test fun aGrowOnlyEverGetsBigger() {
        var last = growRect(tile, window, 0f)
        for (step in 1..100) {
            val now = growRect(tile, window, step / 100f)
            assertTrue("left at $step", now.left <= last.left)
            assertTrue("top at $step", now.top <= last.top)
            assertTrue("right at $step", now.right >= last.right)
            assertTrue("bottom at $step", now.bottom >= last.bottom)
            assertTrue("inside the window at $step", now.left >= window.left && now.top >= window.top && now.right <= window.right && now.bottom <= window.bottom)
            last = now
        }
        assertEquals(window, last)
    }

    @Test fun halfWayIsHalfWayOnEveryEdge() {
        assertEquals(Rect(60f, 320f, 1256f, 1588f), growRect(tile, window, 0.5f))
    }

    @Test fun itGrowsFromATileThatIsShowing() {
        assertTrue(growsFromTile(tile, window))
        // Most of it showing, at the window's edge.
        assertTrue(growsFromTile(Rect(-100f, 640f, 480f, 1000f), window))
    }

    @Test fun itFadesWithoutATileToGrowFrom() {
        // Removed, or on a page that isn't composed.
        assertFalse(growsFromTile(null, window))
        // On a page that turned away: what is reported of a tile clipped out of sight has no size.
        assertFalse(growsFromTile(Rect.Zero, window))
        assertFalse(growsFromTile(Rect(300f, 300f, 300f, 700f), window))
        // Off to one side, as on the other page after a fold.
        assertFalse(growsFromTile(Rect(2000f, 640f, 2580f, 1000f), window))
        assertFalse(growsFromTile(Rect(1812f, 640f, 2392f, 1000f), window))
        // Less than half of it left on screen.
        assertFalse(growsFromTile(Rect(-400f, 640f, 180f, 1000f), window))
        // A window with no size yet.
        assertFalse(growsFromTile(tile, Rect.Zero))
    }

    @Test fun theGrowingScreenIsBroughtInOverItsTile() {
        assertEquals(0f, growAlpha(0f), 0f)
        assertEquals(1f, growAlpha(GROW_IN_BY), 0f)
        assertEquals(1f, growAlpha(1f), 0f)
        assertEquals(0f, growAlpha(-1f), 0f)
        var last = 0f
        for (step in 0..100) {
            val now = growAlpha(step / 100f)
            assertTrue(now >= last)
            last = now
        }
    }

    @Test fun theTilesPaperAndTiltEaseOutToNone() {
        // On the tile itself there is nothing over it: the real tile shows.
        assertEquals(0f, growPaper(0f), 0f)
        assertEquals(1f, growPaper(GROW_IN_BY), 1e-6f)
        assertEquals(0f, growPaper(GROW_PAPER_OFF_BY), 0f)
        assertEquals(0f, growPaper(1f), 0f)
        for (step in 0..100) assertTrue(growPaper(step / 100f) in 0f..1f)
        var last = 1f
        for (step in 12..100) {
            val now = growPaper(step / 100f)
            assertTrue("at $step", now <= last)
            last = now
        }
        assertEquals(-3f, growTilt(-3f, 0f), 0f)
        assertEquals(-1.5f, growTilt(-3f, 0.5f), 0f)
        assertEquals(0f, growTilt(-3f, 1f), 0f)
        assertEquals(0f, growTilt(-3f, 1.4f), 0f)
    }

    @Test fun animationsOffInTheSystemMeansNoMotion() {
        assertTrue(reducedMotion(0f))
        // Slower or faster is still motion, at the system's own scale.
        assertFalse(reducedMotion(1f))
        assertFalse(reducedMotion(0.5f))
        assertFalse(reducedMotion(10f))
        // Nothing sets a scale below zero; if something did, it would not be read as "animate".
        assertTrue(reducedMotion(-1f))
    }

    @Test fun theSpringNeverOvershoots() {
        // At 1 or over, a spring comes to rest without passing its target: a clip never grows past the window.
        assertTrue(Motion.DAMPING >= 1f)
        assertTrue(Motion.FADE_MS in 1..300)
    }

    @Test fun somethingEasingInIsOpaqueHalfWayAndOnlyGrowsAfter() {
        assertEquals(0f, appearAlpha(0f), 0f)
        assertEquals(0.5f, appearAlpha(APPEAR_IN_BY / 2f), 1e-6f)
        assertEquals(1f, appearAlpha(APPEAR_IN_BY), 0f)
        assertEquals(1f, appearAlpha(1f), 0f)
        assertEquals(0f, appearAlpha(-0.3f), 0f)
        assertTrue(APPEAR_SCALE > 0f && APPEAR_SCALE < 1f)
    }

    @Test fun aLayerGrowsOutOfThePointThatWasTouched() {
        // Inside it: the fraction of the way across.
        assertEquals(0.25f, pivotFraction(150f, 100f, 200f), 0f)
        assertEquals(0f, pivotFraction(100f, 100f, 200f), 0f)
        assertEquals(1f, pivotFraction(300f, 100f, 200f), 0f)
        // Outside it, as a touch on the page is outside the menu in the middle of the window: beyond 0 or 1.
        assertEquals(-0.5f, pivotFraction(0f, 100f, 200f), 0f)
        assertEquals(2f, pivotFraction(500f, 100f, 200f), 0f)
        // Nothing to divide by before it is measured.
        assertEquals(0.5f, pivotFraction(150f, 100f, 0f), 0f)
        val origin = pivotAt(androidx.compose.ui.geometry.Offset(150f, 500f), androidx.compose.ui.geometry.Offset(100f, 400f), androidx.compose.ui.geometry.Size(200f, 400f))
        assertEquals(0.25f, origin.pivotFractionX, 0f)
        assertEquals(0.25f, origin.pivotFractionY, 0f)
    }

    @Test fun aFolderOpensAsWideAsItsTile() {
        assertEquals(0.25f, folderScale(panelWidth = 800, tileWidth = 200), 0f)
        // Never from a dot, never from larger than itself, and where it stands without a tile.
        assertEquals(0.2f, folderScale(panelWidth = 2000, tileWidth = 100), 0f)
        assertEquals(1f, folderScale(panelWidth = 300, tileWidth = 400), 0f)
        assertEquals(0.8f, folderScale(panelWidth = 800, tileWidth = null), 0f)
        assertEquals(0.8f, folderScale(panelWidth = 0, tileWidth = 200), 0f)
    }

    @Test fun aPageOpenedFromTheOverviewGrowsToItsPlaceOnHome() {
        // Folded: every page is the whole window.
        assertEquals(Rect(0f, 0f, 1080f, 2300f), pagePlace(0, 1, 1080f, 2300f))
        assertEquals(Rect(0f, 0f, 1080f, 2300f), pagePlace(3, 1, 1080f, 2300f))
        // Unfolded: fixed neighbours, the even positions on the left half and the odd ones on the right.
        assertEquals(Rect(0f, 0f, 906f, 2176f), pagePlace(0, 2, 1812f, 2176f))
        assertEquals(Rect(906f, 0f, 1812f, 2176f), pagePlace(1, 2, 1812f, 2176f))
        assertEquals(Rect(0f, 0f, 906f, 2176f), pagePlace(4, 2, 1812f, 2176f))
        // Nothing asks with none or less; if something did, it is the whole window.
        assertEquals(Rect(0f, 0f, 1812f, 2176f), pagePlace(1, 0, 1812f, 2176f))
    }

    @Test fun onlyTheScrapTheMenuJustAddedEasesInAndOnlyOnce() {
        val arrivals = Arrivals()
        val from = androidx.compose.ui.geometry.Offset(400f, 900f)
        assertEquals(null, arrivals.take("n1", now = 0))
        arrivals.expect("n2", from, now = 1000)
        // Every other scrap on the page is simply there.
        assertEquals(null, arrivals.take("n1", now = 1010))
        assertEquals(from, arrivals.take("n2", now = 1010)?.from)
        // The page composed again: it doesn't ease in twice.
        assertEquals(null, arrivals.take("n2", now = 1020))
        // One that only shows much later (its page wasn't showing) is simply there, and not owed an entrance after.
        arrivals.expect("n3", null, now = 5000)
        assertEquals(null, arrivals.take("n3", now = 60_000))
        assertEquals(null, arrivals.take("n3", now = 5010))
        // Without a place in the menu it still eases in, where it stands.
        arrivals.expect("n4", null, now = 7000)
        val arrival = arrivals.take("n4", now = 7001)
        assertTrue(arrival != null && arrival.from == null)
    }

    private fun note(id: String) = dev.omalauncher.widgets.annotations.Scrap.Note(dev.omalauncher.widgets.annotations.StickyNote(id, 0.1f, 0.1f, 0.3f, 0.2f, "milk"))

    @Test fun whatIsTakenOffAPageShrinksAwayOnce() {
        val leaving = ScrapsLeaving()
        val a = note("a")
        val b = note("b")
        // What is there when the page first shows is not leaving, and neither is what is added.
        leaving.see(listOf(a), still = false)
        assertTrue(leaving.none)
        leaving.see(listOf(a, b), still = false)
        assertTrue(leaving.none)
        // Taken off: it is what shrinks away, as it last was.
        leaving.see(listOf(b), still = false)
        assertEquals(listOf(a), leaving.left)
        // Composed again meanwhile: still the one, not twice.
        leaving.see(listOf(b), still = false)
        assertEquals(listOf(a), leaving.left)
        leaving.gone("a")
        assertTrue(leaving.none)
        // Put back while it shrinks: it is on the page, not leaving it.
        leaving.see(emptyList(), still = false)
        assertEquals(listOf(b), leaving.left)
        leaving.see(listOf(b), still = false)
        assertTrue(leaving.none)
        // With the system's animations off, nothing shrinks away.
        leaving.see(emptyList(), still = true)
        assertTrue(leaving.none)
    }

    @Test fun aTilesPlaceIsItsNewestTiles() {
        val tiles = TileBounds()
        val first = Any()
        val second = Any()
        assertEquals(null, tiles["clock.owl"])
        tiles.place("clock.owl", first, tile)
        assertEquals(tile, tiles["clock.owl"])
        // The same tile moved.
        tiles.place("clock.owl", first, tile.translate(10f, 0f))
        assertEquals(tile.translate(10f, 0f), tiles["clock.owl"])
        // Composed again before the old one left: the old one leaving doesn't take the new one's place.
        tiles.place("clock.owl", second, window)
        tiles.forget("clock.owl", first)
        assertEquals(window, tiles["clock.owl"])
        tiles.forget("clock.owl", second)
        assertEquals(null, tiles["clock.owl"])
    }
}
