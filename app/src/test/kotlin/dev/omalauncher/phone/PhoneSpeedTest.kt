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
        // Storage: Samsung's Settings asks Android again, which asks which app; My Files, just once, as he'd tap.
        assertEquals(listOf("My Files", "Just once"), PhoneSettings.chooserAnswer(PhoneSettings.action("settings:storage")))
        assertTrue(PhoneSettings.chooserAnswer(PhoneSettings.action("settings:display")).isEmpty())
        assertTrue(PhoneSettings.chooserAnswer(null).isEmpty())
        assertTrue("com.sec.android.app.myfiles" in PhoneSettings.handsOverTo("com.android.settings"))
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

    @Test fun aLabelQueryPressesOnlyWhenExactlyOneControlSaysItWhole() {
        fun n(text: String, desc: String = "", enabled: Boolean = true) = SeenNode("", text, desc, true, false, enabled)
        // Android's search matches parts of words too: only the whole one counts, and only if it is alone.
        assertEquals(1, PhoneFind.quick(listOf(n("Send later"), n("Send")), "send"))
        assertEquals(0, PhoneFind.quick(listOf(n("", desc = "Send"), n("Sender")), "Send"))
        assertNull(PhoneFind.quick(listOf(n("Send"), n("send")), "Send"))
        assertNull(PhoneFind.quick(listOf(n("Send later")), "Send"))
        assertNull(PhoneFind.quick(listOf(n("Send", enabled = false)), "Send"))
        assertNull(PhoneFind.quick(emptyList(), "Send"))
        assertNull(PhoneFind.quick(listOf(n("")), " "))
    }

    @Test fun aDeltaIsOnlyWhatChangedSinceTheReadersScreen() {
        fun c(id: String, says: String, can: String = "press") = org.json.JSONObject().put("id", id).put("says", says).put("can", org.json.JSONArray(listOf(can)))
        fun brief(snapshot: String, pkg: String, vararg controls: org.json.JSONObject) =
            org.json.JSONObject().put("ok", true).put("snapshot", snapshot).put("package", pkg).put("controls", org.json.JSONArray(controls.toList()))
        val before = brief("s1", "app", c("root.0", "Title"), c("root.1", "Off"), c("root.2", "Gone soon"))
        val now = brief("s2", "app", c("root.0", "Title"), c("root.1", "On"), c("root.3", "New"))
        val d = PhoneScreen.delta(before, now)!!
        assertEquals("s2", d.getString("snapshot")); assertEquals("s1", d.getString("since"))
        assertEquals(listOf("root.1", "root.3"), (0 until d.getJSONArray("changed").length()).map { d.getJSONArray("changed").getJSONObject(it).getString("id") })
        assertEquals(listOf("root.2"), (0 until d.getJSONArray("gone").length()).map { d.getJSONArray("gone").getString(it) })
        assertEquals(1, d.getInt("same"))
        // Another app in front: the whole screen, not a delta.
        assertNull(PhoneScreen.delta(before, brief("s3", "other", c("root.0", "Title"))))
    }
}
