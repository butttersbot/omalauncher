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
