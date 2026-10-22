package dev.omalauncher

import dev.omalauncher.phone.PhoneControlPolicy
import org.junit.Assert.*
import org.junit.Test

class PhoneControlPolicyTest {
    @Test fun accessibilityConsentAllowsUseWithoutStartingSession() {
        PhoneControlPolicy.requireAllowed(connected = true, paused = false, locked = false, awake = true)
    }
    @Test fun cannotOperateWithoutAccessibilityConsent() {
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireAllowed(false, false, false, true) }
    }
    @Test fun explicitUserPauseWinsOverEnabledAccessibility() {
        val error = assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireAllowed(true, true, false, true) }
        assertTrue(error.message!!.contains("paused"))
    }
    @Test fun consentDoesNotAllowLockedOrScreenOffPhone() {
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireAllowed(true, false, true, true) }
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireAllowed(true, false, false, false) }
    }
    @Test fun managedBackgroundDisplayCanWorkWhileMainIsLockedAndOff() {
        PhoneControlPolicy.requireAllowed(true, false, true, false, background = true)
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireAllowed(true, true, true, false, background = true) }
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireAllowed(false, false, true, false, background = true) }
    }
    @Test fun backgroundMutationsStillWaitForOwnerApproval() {
        PhoneControlPolicy.requireNoPendingApproval(1, "screen")
        for (command in listOf("open", "press", "fill", "submit", "tap", "swipe")) {
            assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireNoPendingApproval(1, command) }
        }
    }
}
