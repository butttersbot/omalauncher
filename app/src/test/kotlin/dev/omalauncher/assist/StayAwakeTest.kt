package dev.omalauncher.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StayAwakeTest {
    @Test fun theProcessorStaysOnOnlyWhileSomethingWorks() {
        assertFalse(wanted(building = false, answering = false, following = false, waiting = 0))
        assertTrue(wanted(building = true, answering = false, following = false, waiting = 0))
        assertTrue(wanted(building = false, answering = true, following = false, waiting = 0))
        assertTrue(wanted(building = false, answering = false, following = true, waiting = 0))
    }

    @Test fun aRequestThatWaitsForHimLetsThePhoneSleep() {
        assertFalse(wanted(building = true, answering = false, following = true, waiting = 1))
        assertTrue(wanted(building = true, answering = true, following = false, waiting = 1))
        assertTrue(CHECK_MILLIS * 4 <= HOLD_MILLIS)
    }
}
