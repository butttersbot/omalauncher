package dev.omalauncher.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmaToastTest {
    @Test
    fun namesTheMachineItWentTo() {
        assertEquals("Sent to macbook-pro", toastText(null, "macbook-pro"))
        assertEquals("Sent to macbook-pro", toastText("  ", " macbook-pro "))
    }

    @Test
    fun ownWordsComeFirstOnOneShortLine() {
        assertEquals("Copied to jacks-mac-pro", toastText("Copied to\njacks-mac-pro", "macbook-pro"))
        assertEquals(120, toastText("x".repeat(500), null)!!.length)
    }

    @Test
    fun nothingToSayIsNoToast() {
        assertNull(toastText(null, null))
        assertNull(toastText("", " "))
    }

    @Test
    fun onlyTailshareIsHeard() {
        assertEquals(mapOf("com.butttersbot.tailshare" to "Tailshare"), TOAST_SENDERS)
    }
}
