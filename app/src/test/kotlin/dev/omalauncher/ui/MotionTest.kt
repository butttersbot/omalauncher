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
