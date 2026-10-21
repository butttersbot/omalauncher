package dev.omalauncher.phone

import org.junit.Assert.*
import org.junit.Test

/**
 * What an agent's touches are held to (PhoneGuard.kt): the app it opened, the app it names, and never Soma's own chat
 * box unless named. Each case is one of builder A's real mistakes of 2026-10-21, or the way out of one.
 */
class PhoneGuardTest {
    private val own = "dev.omalauncher.boss"
    private val brave = "com.brave.browser"

    private fun refused(command: String, front: String, asked: String = "", any: Boolean = false, opened: String? = null, chat: Boolean = false) =
        PhoneGuard.refusal(command, front, asked, any, opened, own, chat)

    @Test fun afterOpeningAnAppTouchesElsewhereAreRefusedAndSaySo() {
        // He switched back to Oma's chat after the agent opened Brave: the fill that went into his composer.
        val why = refused("fill", front = own, opened = brave, chat = true)
        assertNotNull(why)
        assertTrue(why!!, why.contains("You opened $brave") && why.contains("$own is in front") && why.contains("Nothing was done"))
        // A stray back left Brave for home: the taps that followed.
        for (command in listOf("tap", "swipe", "press", "click", "type", "scroll", "submit", "back")) {
            assertNotNull(command, refused(command, front = own, opened = brave))
        }
        // In the app it opened, everything goes through.
        for (command in PhoneGuard.touching) assertNull(command, refused(command, front = brave, opened = brave))
    }

    @Test fun lookingIsNeverHeld() {
        for (command in listOf("screen", "read", "screenshot", "apps", "open", "url", "home", "recents", "notifications", "status", "page")) {
            assertNull(command, refused(command, front = own, opened = brave, chat = true))
        }
    }

    @Test fun namingAnAppOrAnyLiftsTheHold() {
        assertNull(refused("tap", front = "com.google.android.gms", asked = "com.google.android.gms", opened = brave))
        assertNull(refused("tap", front = "com.google.android.gms", any = true, opened = brave))
        // A named app that isn't in front still refuses, --any or not.
        val why = refused("press", front = own, asked = brave, any = true)
        assertEquals("Expected $brave in front, but $own is. Nothing was done.", why)
        assertEquals("Expected $brave in front, but nothing readable is. Nothing was done.", refused("press", front = "", asked = brave))
        // Nothing readable in front of an app it opened is not that app.
        assertNotNull(refused("tap", front = "", opened = brave))
    }

    @Test fun somasChatBoxIsNeverTypedIntoUnlessNamed() {
        // With nothing opened, and even with --any: words don't go into his chat box.
        for (command in PhoneGuard.typing) {
            val why = refused(command, front = own, chat = true)
            assertNotNull(command, why)
            assertTrue(why!!, why.contains("Soma's own chat box") && why.contains("--in $own"))
            assertNotNull(command, refused(command, front = own, any = true, chat = true))
            // Named, it is what was asked.
            assertNull(command, refused(command, front = own, asked = own, chat = true))
        }
        // Pressing in the chat (a tab, a menu) is not typing.
        assertNull(refused("press", front = own, chat = true))
        assertNull(refused("tap", front = own, chat = true))
        // Soma's home is not the chat: its fields can be filled.
        assertNull(refused("fill", front = own, chat = false))
        // Another app's field, nothing opened: as before.
        assertNull(refused("fill", front = brave, chat = true))
    }

    @Test fun androidsChooserIsNoAppToHoldTo() {
        assertEquals(brave, PhoneGuard.opened(brave))
        assertNull(PhoneGuard.opened(PhoneGuard.CHOOSER))
        assertNull(PhoneGuard.opened(null))
        assertNull(PhoneGuard.opened(" "))
    }

    @Test fun onlyWholeWebLinksAreOpened() {
        for (link in listOf("https://example.com", "http://192.168.68.58:8090/mock.html", "HTTPS://Example.com/a?b=1#c", "  https://x.io/path  ")) {
            assertEquals(link, link.trim(), PhoneGuard.webUrl(link).getOrThrow())
        }
        for (bad in listOf("", "   ", "example.com", "javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "content://a/b",
            "https://", "https:///path", "https://a b.com", "https://a.com/\nx", "ftp://a.com", "x".repeat(10) + "https://a.com")) {
            assertTrue(bad, PhoneGuard.webUrl(bad).isFailure)
        }
        assertTrue(PhoneGuard.webUrl("https://a.com/" + "x".repeat(PhoneGuard.MAX_URL)).isFailure)
        assertEquals("Only http:// and https:// links are opened this way. Nothing was opened.", PhoneGuard.webUrl("file:///etc/passwd").exceptionOrNull()?.message)
    }

    @Test fun agentsAreToldOfUrlSubmitTheHoldAndTheLock() {
        val told = PhoneBridge.INSTRUCTIONS
        for (words in listOf("oma-phone url URL", "oma-phone submit", "never type a link into an address bar", "--in PACKAGE", "--any",
            "Never type into Soma's own chat box", "If locked is true")) {
            assertTrue(words, told.contains(words))
        }
    }
}
