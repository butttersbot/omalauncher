package dev.omalauncher.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneLaunchPolicyTest {
    @Test fun mainKeyboardDefersOnlyBackgroundActivityLaunches() {
        for (command in listOf("open", "url")) {
            assertTrue(PhoneLaunchPolicy.waits(true, command, true))
            assertFalse(PhoneLaunchPolicy.waits(true, command, false))
            assertFalse(PhoneLaunchPolicy.waits(false, command, true))
        }
        for (command in listOf("screen", "read", "press", "tap", "fill", "submit", "screenshot", "status"))
            assertFalse(command, PhoneLaunchPolicy.waits(true, command, true))
    }

    @Test fun waitingIsAnExplicitNonSuccessWithNoActionToReplayBlindly() {
        val result = PhoneLaunchPolicy.waiting()
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getBoolean("waiting"))
        assertFalse(result.getBoolean("actionMayHaveRun"))
        assertEquals("boss-typing", result.getString("reason"))
        assertTrue(result.getString("error").contains("Boss is typing"))
    }
}
