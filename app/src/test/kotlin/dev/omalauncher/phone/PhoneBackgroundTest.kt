package dev.omalauncher.phone

import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneBackgroundTest {
    @Test fun commandsWithoutDisplayScopedImplementationsNeverFallThrough() {
        for (command in listOf("home", "recents", "notifications", "back", "page", "canvas", "lockstar", "stay", "takeover")) {
            assertThrows(IllegalArgumentException::class.java) { PhoneBackgroundCommands.requireAllowed(command) }
        }
    }
    @Test fun explicitlyScopedCommandsAreAccepted() {
        for (command in listOf("open", "screen", "read", "press", "fill", "submit", "screenshot", "tap", "swipe", "stop")) {
            PhoneBackgroundCommands.requireAllowed(command)
        }
    }
}
