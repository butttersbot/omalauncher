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

    /** OPEN-BUGS #10: the Mac's work is on the lock widget too, but never which thread, whose name can say what he asked. */
    @Test fun theLockScreenIsToldTheMacsWorkWithoutItsName() {
        val working = MacGlance(working = "FF4 better menu mod")
        assertEquals("working on the Mac", OmaLockWidget.withMac(false, "Done", 0, working))
        assertEquals("running on the Mac", OmaLockWidget.withMac(false, "Ready", 0, MacGlance(background = "Copy the games")))
        assertEquals("needs your OK", OmaLockWidget.withMac(false, "Done", 0, MacGlance(waiting = "Site fix", approvals = 1)))
        assertEquals("needs your OK", OmaLockWidget.withMac(true, "Using Bash", 0, MacGlance(waiting = "Site fix")))
        // A builder at work is told first; with nothing on the Mac it is as before.
        assertEquals("working", OmaLockWidget.withMac(true, "Using Bash", 0, working))
        assertEquals("done", OmaLockWidget.withMac(false, "Done", 0, MacGlance()))
        assertEquals("needs your OK", OmaLockWidget.withMac(true, "Approval needed", 1, MacGlance()))
    }
}
