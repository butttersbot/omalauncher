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
            "Never type into Soma's own chat box", "If locked is true", "oma-phone run -")) {
            assertTrue(words, told.contains(words))
        }
    }

    /** `run` (oma-phone.sh): many steps in one call, each an ordinary call with its checks, stopping at the first that fails. */
    @Test fun runTakesOnlyPhoneStepsAndNeverRunsWhatItReads() {
        val script = java.io.File("src/main/assets/oma-phone.sh").readText()
        val run = script.substringAfter("if [ \"\$command\" = run ]; then").substringBefore("\nfi\n")
        // Words are split by xargs, never eval'd, and only phone steps are taken.
        assertFalse(run.contains("eval"))
        assertTrue(run.contains("xargs printf"))
        assertTrue(run.contains("press|fill|submit|back|tap|swipe|expect|screen|open|url|home|page|stay)"))
        assertTrue(run.contains("can't go in a run"))
        // Each step is its own oma-phone call, so the hold, --in and the chat-box rule apply to every one; stdin is not theirs.
        assertTrue(run.contains("\"\${self[@]}\" \"\$verb\" \"\${args[@]}\" 2>&1 </dev/null"))
        assertTrue(run.contains("Step \$step failed"))
        assertTrue(run.contains("-le 40"))
        assertTrue(script.contains("oma-phone run [--in PACKAGE | --any] [SCRIPT_FILE | -]"))
    }

    @Test fun anAppsOwnPartsAreThatApp() {
        // Settings' search runs as its own package: still Settings to the agent that opened Settings.
        assertNull(refused("fill", front = "com.android.settings.intelligence", opened = "com.android.settings"))
        assertTrue(PhoneGuard.sameApp("com.android.settings", "com.android.settings"))
        // A name that only starts the same is another app.
        assertFalse(PhoneGuard.sameApp("com.android.settingsx", "com.android.settings"))
        assertNotNull(refused("tap", front = "com.android.settingsx", opened = "com.android.settings"))
        // Named with --in, it is exactly that app.
        assertNotNull(refused("fill", front = "com.android.settings.intelligence", asked = "com.android.settings"))
    }
}
