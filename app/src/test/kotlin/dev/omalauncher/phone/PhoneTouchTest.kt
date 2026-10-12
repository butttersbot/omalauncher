package dev.omalauncher.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long a read waits after an action, where a click that Android refused is tapped instead, and how long a swipe may last. */
class PhoneTouchTest {
    @Test fun aReadWaitsOnlyForWhatIsLeftOfTheSettling() {
        // Nothing was ever done to the screen: there is nothing to wait for.
        assertEquals(0L, PhoneTouch.settleWait(now = 5_000, acted = null))
        assertEquals(PhoneTouch.SETTLE_MILLIS, PhoneTouch.settleWait(now = 5_000, acted = 5_000))
        assertEquals(PhoneTouch.SETTLE_MILLIS - 100, PhoneTouch.settleWait(now = 5_100, acted = 5_000))
        assertEquals(1L, PhoneTouch.settleWait(now = 5_000 + PhoneTouch.SETTLE_MILLIS - 1, acted = 5_000))
        assertEquals(0L, PhoneTouch.settleWait(now = 5_000 + PhoneTouch.SETTLE_MILLIS, acted = 5_000))
        assertEquals(0L, PhoneTouch.settleWait(now = 60_000, acted = 5_000))
    }

    @Test fun aReadNeverWaitsLongerThanTheSettling() {
        // An action stamped after now is still only one settling away.
        assertEquals(PhoneTouch.SETTLE_MILLIS, PhoneTouch.settleWait(now = 5_000, acted = 9_000))
        // Far inside what the bridge waits for an answer.
        assertTrue(PhoneTouch.SETTLE_MILLIS < 8_000)
    }

    @Test fun aControlIsTappedInItsMiddle() {
        assertEquals(150f to 250f, PhoneTouch.middle(100, 200, 200, 300, width = 1080, height = 2400))
        assertEquals(100.5f to 200.5f, PhoneTouch.middle(100, 200, 101, 201, width = 1080, height = 2400))
    }

    @Test fun aControlPartlyOffScreenIsTappedWhereItShows() {
        // Half of it is past the right edge, and it starts above the top.
        assertEquals(1040f to 50f, PhoneTouch.middle(1000, -100, 1160, 100, width = 1080, height = 2400))
        assertEquals(540f to 1200f, PhoneTouch.middle(-500, -500, 5000, 5000, width = 1080, height = 2400))
    }

    @Test fun aControlWithNothingOnScreenIsNotTapped() {
        assertNull(PhoneTouch.middle(1080, 0, 1200, 100, width = 1080, height = 2400))
        assertNull(PhoneTouch.middle(0, -200, 100, 0, width = 1080, height = 2400))
        assertNull(PhoneTouch.middle(0, 2400, 100, 2500, width = 1080, height = 2400))
        // No size at all, or bounds the wrong way round.
        assertNull(PhoneTouch.middle(100, 100, 100, 300, width = 1080, height = 2400))
        assertNull(PhoneTouch.middle(300, 100, 100, 300, width = 1080, height = 2400))
        assertNull(PhoneTouch.middle(0, 0, 100, 100, width = 0, height = 0))
    }

    @Test fun theTapIsAlwaysACoordinateTheScreenHas() {
        val (x, y) = PhoneTouch.middle(1079, 2399, 1080, 2400, width = 1080, height = 2400)!!
        assertTrue(x >= 0 && x < 1080)
        assertTrue(y >= 0 && y < 2400)
    }

    @Test fun aSwipeMayHoldAtAnEdgeButNotOutlastTheBridge() {
        assertTrue(100L in PhoneTouch.SWIPE_MILLIS)
        assertTrue(5000L in PhoneTouch.SWIPE_MILLIS)
        assertFalse(99L in PhoneTouch.SWIPE_MILLIS)
        assertFalse(5001L in PhoneTouch.SWIPE_MILLIS)
        // The bridge answers a swipe when the finger lifts, and waits eight seconds for that.
        assertTrue(PhoneTouch.SWIPE_MILLIS.last + PhoneTouch.SETTLE_MILLIS < 8_000)
    }
}
