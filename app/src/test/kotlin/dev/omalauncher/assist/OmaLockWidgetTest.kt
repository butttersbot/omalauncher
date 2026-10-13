package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class OmaLockWidgetTest {
    @Test fun theLockScreenIsToldTheStateAndNothingOfTheRequest() {
        assertEquals("needs your OK", OmaLockWidget.stateLine(working = true, label = "Approval needed", approvals = 1))
        // What it is doing may name a file or a command: on a lock screen it is only "working".
        assertEquals("working", OmaLockWidget.stateLine(working = true, label = "Using Bash: cat ~/notes.txt", approvals = 0))
        assertEquals("done", OmaLockWidget.stateLine(false, "Done", 0))
        assertEquals("stopped", OmaLockWidget.stateLine(false, "Stopped", 0))
        assertEquals("ready", OmaLockWidget.stateLine(false, "Ready", 0))
        assertEquals("needs attention", OmaLockWidget.stateLine(false, "Could not run agent", 0))
    }
}
