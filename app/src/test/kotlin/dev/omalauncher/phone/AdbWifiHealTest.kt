package dev.omalauncher.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbWifiHealTest {
    @Test
    fun turnsItOnWhenOffOnWifi() {
        assertTrue(adbWifiWanted(granted = true, devOptions = true, onWifi = true, on = 0))
        assertTrue(adbWifiWanted(granted = true, devOptions = true, onWifi = true, on = null))
    }

    @Test
    fun leavesItWhenAlreadyOn() = assertFalse(adbWifiWanted(granted = true, devOptions = true, onWifi = true, on = 1))

    @Test
    fun leavesItWithoutWhatItNeeds() {
        assertFalse(adbWifiWanted(granted = false, devOptions = true, onWifi = true, on = 0))
        assertFalse(adbWifiWanted(granted = true, devOptions = false, onWifi = true, on = 0))
        assertFalse(adbWifiWanted(granted = true, devOptions = true, onWifi = false, on = 0))
    }
}
