package dev.omalauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugExecTest {
    private val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test fun theDoorOpensOnlyToTheKeyInTheSwitchFile() {
        assertTrue(debugExecAllowed(key, key))
        // As written by a shell, with its line end.
        assertTrue(debugExecAllowed("$key\n", key))
        assertFalse(debugExecAllowed(key, key.dropLast(1) + "0"))
        assertFalse(debugExecAllowed(key, key + "0"))
        assertFalse(debugExecAllowed(key, ""))
        assertFalse(debugExecAllowed(key, null))
    }

    @Test fun noFileAnEmptyFileOrAShortKeyKeepsItShut() {
        assertFalse(debugExecAllowed(null, key))
        // The switch as it used to be turned on, by touching the file: any app could then send a command.
        assertFalse(debugExecAllowed("", ""))
        assertFalse(debugExecAllowed("\n", ""))
        assertFalse(debugExecAllowed("short", "short"))
        assertTrue(debugExecAllowed("a".repeat(DEBUG_EXEC_KEY_LENGTH), "a".repeat(DEBUG_EXEC_KEY_LENGTH)))
        assertFalse(debugExecAllowed("a".repeat(DEBUG_EXEC_KEY_LENGTH - 1), "a".repeat(DEBUG_EXEC_KEY_LENGTH - 1)))
    }
}
