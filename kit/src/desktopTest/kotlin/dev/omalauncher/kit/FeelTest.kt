package dev.omalauncher.kit

import androidx.compose.ui.unit.Density
import dev.omalauncher.ui.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers Messages 0.8.7 shipped with, which Boss called smooth: moving one should be a decision, not an accident. */
class FeelTest {
    @Test fun theNumbersAreMessagesOwn() {
        assertEquals(1000f, Motion.STIFFNESS)
        assertEquals(1f, Motion.DAMPING)
        assertEquals(150, Motion.FADE_MS)
        assertEquals(270, Feel.OPEN_MS)
        assertEquals(200, Feel.CLOSE_MS)
        assertEquals(370, Feel.ENTRANCE_MS)
        assertEquals(180, Feel.OVERLAY_MS)
        assertEquals(0.92f, Feel.ENTRANCE_SCALE)
        assertEquals(0.6f, Feel.ENTRANCE_ALPHA)
        assertEquals(24f, Feel.ENTRANCE_RISE.value)
        assertEquals(0.96f, Feel.OVERLAY_SCALE)
        assertEquals(0.18f, Feel.SCRIM_ALPHA)
        assertEquals(24f, Feel.EDGE_GUARD.value)
        assertEquals(0.35f, Feel.SLIDE_AWAY_AT)
        assertEquals(500, Feel.SLIDE_AWAY_FLING)
        assertEquals(64f, Feel.PULL_MARK.value)
        assertEquals(1.3f, Feel.PULL_STIFFENS_AT)
        assertEquals(0.3f, Feel.PULL_GIVE)
    }

    @Test fun aPullIsOneToOneThenStiff() {
        assertEquals(50f, pulled(50f, 100f))
        assertEquals(130f, pulled(130f, 100f))
        assertEquals(133f, pulled(140f, 100f), 0.001f)
    }

    @Test fun aSlideGoesFarEnoughOrFastEnough() {
        assertFalse(slidesAway(0.35f, 0f, 1000f))
        assertTrue(slidesAway(0.36f, 0f, 1000f))
        assertTrue(slidesAway(0.01f, 1001f, 1000f))
        assertFalse(slidesAway(0.01f, 1000f, 1000f))
    }

    @Test fun aBigThingMovesTheSameFewPixelsAsASmallOne() {
        val density = Density(1f)
        assertEquals(1f, pressedScale(100f, 0f, density))
        assertEquals(Feel.PRESS_SCALE, pressedScale(100f, 1f, density), 0.0001f)
        // 1000 px long: 3% would be 30 px, so it gives its 12 instead (6 dp at each end).
        assertEquals(1f - 12f / 1000f, pressedScale(1000f, 1f, density), 0.0001f)
    }
}
