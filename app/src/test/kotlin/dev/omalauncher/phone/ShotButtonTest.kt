package dev.omalauncher.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ShotButtonTest {
    @Test
    fun sitsCentredJustAboveTheToolbar() {
        // The Fold's inner screen: Samsung's toolbar at 599..1849 across, 1659 down.
        assertEquals(1024 to 1529, shotButtonSpot(599, 1659, 1849, 400, 100, 30))
    }

    @Test
    fun neverOffTheScreen() {
        assertEquals(0 to 0, shotButtonSpot(0, 50, 100, 400, 100, 30))
    }
}
