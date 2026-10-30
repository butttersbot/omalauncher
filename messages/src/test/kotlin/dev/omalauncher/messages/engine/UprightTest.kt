package dev.omalauncher.messages.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UprightTest {
    @Test
    fun aPhoneCameraPortraitIsTurnedAQuarterClockwise() {
        // Samsung's and the iPhone's portrait photos: stored sideways, EXIF 6.
        assertEquals(90 to false, Upright.of(6))
        assertEquals(270 to false, Upright.of(8))
        assertEquals(180 to false, Upright.of(3))
    }

    @Test
    fun noTagOrNormalIsLeftAsIs() {
        assertEquals(0 to false, Upright.of(0))
        assertEquals(0 to false, Upright.of(1))
        assertEquals(0 to false, Upright.of(42))
    }

    @Test
    fun mirroredOrientationsMirrorThenTurn() {
        assertEquals(0 to true, Upright.of(2))
        assertEquals(90 to true, Upright.of(5))
        assertEquals(270 to true, Upright.of(7))
    }

    @Test
    fun aVideoFrameIsTurnedOnlyWhileItIsStillTheStoredWayRound() {
        // A portrait iPhone video: stored 1920x1080 with rotation 90.
        assertTrue(Upright.frameNeedsTurn(90, 720, 405, 1920, 1080))
        assertFalse(Upright.frameNeedsTurn(90, 405, 720, 1920, 1080))
        assertFalse(Upright.frameNeedsTurn(0, 720, 405, 1920, 1080))
        assertFalse(Upright.frameNeedsTurn(180, 720, 405, 1920, 1080))
    }
}
