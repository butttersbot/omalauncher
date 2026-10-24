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
        assertEquals("com.samsung.android.settings", PhoneSettings.handler(listOf("android", "com.samsung.android.settings")))
        assertNull(PhoneSettings.handler(listOf("android")))
        assertNull(PhoneSettings.handler(emptyList()))
        // Battery is handed on to Device Care, and that is still the page asked for.
        assertTrue("com.samsung.android.lool" in PhoneSettings.handsOverTo("com.android.settings"))
        assertTrue(PhoneSettings.handsOverTo("com.example.app").isEmpty())
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
