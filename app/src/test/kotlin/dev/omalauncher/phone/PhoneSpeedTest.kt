package dev.omalauncher.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneSpeedTest {
    private fun page(text: String, id: String = "root.0") = listOf(SeenNode(id, text, "", true, false, true))

    @Test fun settingsDestinationsAreExplicitAndUnknownOnesRefuse() {
        assertEquals("android.settings.SETTINGS", PhoneSettings.action("settings"))
        assertEquals("android.settings.DISPLAY_SETTINGS", PhoneSettings.action("settings:display"))
        assertEquals("android.settings.BATTERY_SAVER_SETTINGS", PhoneSettings.action("settings:battery"))
        assertNull(PhoneSettings.action("dev.omalauncher.boss"))
        for (name in listOf("settings:", "settings:security", "settings:unknown")) {
            try { PhoneSettings.action(name); fail("Unknown destination accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun settingsOpensInSettingsNotTheChooserAndAllowsSamsungsHandOver() {
        // Samsung's My Files answers storage too: Settings is picked, never Android's "open with" chooser.
        assertEquals("com.android.settings", PhoneSettings.handler(listOf("com.sec.android.app.myfiles", "com.android.settings")))
        // Storage itself opens in My Files on Samsung: Settings would hand it on through the chooser (launcher 153).
        val storage = PhoneSettings.action("settings:storage")!!
        assertEquals("com.sec.android.app.myfiles", PhoneSettings.handler(listOf("com.android.settings", "com.sec.android.app.myfiles"), storage))
        assertEquals("com.android.settings", PhoneSettings.handler(listOf("com.android.settings"), storage))
        assertEquals("com.android.settings", PhoneSettings.handler(listOf("com.sec.android.app.myfiles", "com.android.settings"), PhoneSettings.action("settings:display")!!))
        assertEquals("com.samsung.android.settings", PhoneSettings.handler(listOf("android", "com.samsung.android.settings")))
        assertNull(PhoneSettings.handler(listOf("android")))
        assertNull(PhoneSettings.handler(emptyList()))
        // Battery is handed on to Device Care, and that is still the page asked for.
        assertTrue("com.samsung.android.lool" in PhoneSettings.handsOverTo("com.android.settings"))
        assertTrue(PhoneSettings.handsOverTo("com.example.app").isEmpty())
    }

    @Test fun waitsReadOnlyWhenSomethingChangedOrTheFallbackIsDue() {
        assertTrue(PhoneWait.readAgain(1_000, lastRead = null, lastChange = null))
        // Nothing changed since the last read, and it was only now: no read.
        assertFalse(PhoneWait.readAgain(1_100, lastRead = 1_000, lastChange = 900))
        // Android said it changed after (or at) the last read: read.
        assertTrue(PhoneWait.readAgain(1_100, lastRead = 1_000, lastChange = 1_050))
        assertTrue(PhoneWait.readAgain(1_100, lastRead = 1_000, lastChange = 1_000))
        // A change that came without an event is caught by the fallback.
        assertTrue(PhoneWait.readAgain(1_000 + PhoneWait.FALLBACK_MILLIS, lastRead = 1_000, lastChange = 900))
    }

    @Test fun anUnchangedScreenIsOnlyTheSnapshotTheReaderHas() {
        assertTrue(PhoneWait.unchanged("s1", "s1", readAt = 1_000, lastChange = 900))
        assertTrue(PhoneWait.unchanged("s1", "s1", readAt = 1_000, lastChange = null))
        assertFalse(PhoneWait.unchanged("s1", "s1", readAt = 1_000, lastChange = 1_000))
        assertFalse(PhoneWait.unchanged("s0", "s1", readAt = 1_000, lastChange = 900))
        assertFalse(PhoneWait.unchanged("", "", readAt = 1_000, lastChange = 900))
        assertFalse(PhoneWait.unchanged("s1", "s1", readAt = null, lastChange = null))
    }

    @Test fun repeatedPagesStopEvenWhenNodePathsChange() {
        val search = PhoneSearchProgress()
        assertFalse(search.repeated(page("Top", "root.1"), true))
        assertTrue(search.repeated(page("Top", "root.9"), true))
    }

    @Test fun recycledListIdsDoNotHideDifferentPagesAndReverseSearchIsIndependent() {
        val search = PhoneSearchProgress()
        assertFalse(search.repeated(page("Top"), true))
        assertFalse(search.repeated(page("Bottom"), true))
        assertFalse(search.repeated(page("Bottom"), false))
        assertFalse(search.repeated(page("Top"), false))
        assertTrue(search.repeated(page("Bottom"), false))
    }

    @Test fun blankScreensDoNotProveARepeatedPage() {
        val search = PhoneSearchProgress()
        assertFalse(search.repeated(page(""), true))
        assertFalse(search.repeated(page(""), true))
    }
}
