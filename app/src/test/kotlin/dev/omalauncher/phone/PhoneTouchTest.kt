package dev.omalauncher.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long a read waits after an action, where a click that Android refused is tapped instead, and how long a swipe may last. */
class PhoneTouchTest {
    @Test fun aReadWaitsOnlyUntilTheScreenGoesQuiet() {
        // Nothing was ever done to the screen: there is nothing to wait for.
        assertEquals(0L, phoneSettleWait(now = 5_000, acted = null, changed = null))
        // Right after a move, at least the least wait, even with the screen saying nothing yet.
        assertEquals(PHONE_SETTLE_MIN_MILLIS, phoneSettleWait(now = 5_000, acted = 5_000, changed = null))
        // A screen still changing is waited for until it has been quiet a while.
        assertEquals(PHONE_QUIET_MILLIS, phoneSettleWait(now = 5_200, acted = 5_000, changed = 5_200))
        // Quiet long enough and past the least wait: read at once, well before the old flat wait was over.
        assertEquals(0L, phoneSettleWait(now = 5_160, acted = 5_000, changed = 5_030))
    }

    @Test fun aReadNeverWaitsLongerThanTheSettling() {
        // A screen that never goes quiet, as a video, is read when the old flat wait would have been over: never later.
        assertEquals(PhoneTouch.SETTLE_MILLIS - 300, phoneSettleWait(now = 5_300, acted = 5_000, changed = 5_300))
        assertEquals(0L, phoneSettleWait(now = 5_000 + PhoneTouch.SETTLE_MILLIS, acted = 5_000, changed = 5_000 + PhoneTouch.SETTLE_MILLIS))
        assertEquals(0L, phoneSettleWait(now = 60_000, acted = 5_000, changed = 59_990))
        // An action stamped after now is still only one settling away.
        assertTrue(phoneSettleWait(now = 5_000, acted = 9_000, changed = null) <= PhoneTouch.SETTLE_MILLIS)
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

    @Test fun switchedOnAndUnboundIsDisconnectedOnlyAfterTheTimeToBind() {
        assertEquals(PhoneLink.On, phoneLink(bound = true, switchedOn = true, upMillis = 0))
        assertEquals(PhoneLink.On, phoneLink(bound = true, switchedOn = false, upMillis = 60_000))
        assertEquals(PhoneLink.Off, phoneLink(bound = false, switchedOn = false, upMillis = 60_000))
        assertEquals(PhoneLink.On, phoneLink(bound = false, switchedOn = true, upMillis = PHONE_BIND_GRACE_MILLIS - 1))
        assertEquals(PhoneLink.Disconnected, phoneLink(bound = false, switchedOn = true, upMillis = PHONE_BIND_GRACE_MILLIS))
    }
}
