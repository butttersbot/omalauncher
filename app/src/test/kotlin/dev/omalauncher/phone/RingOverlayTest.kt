package dev.omalauncher.phone

import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.view.WindowManager
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The ring over other apps is built but off (PRD AC-30, UP-11): what keeps it off, and the rules it would follow if it were on. */
class RingOverlayTest {
    /** Counts what the overlay does to its window. */
    private class FakeWindow(var accepts: Boolean = true) : RingWindow {
        var adds = 0
        var removes = 0
        val up get() = adds - removes == 1

        override fun add(): Boolean {
            if (accepts) adds++
            return accepts
        }

        /** While true, the window won't come down. */
        var stuck = false

        override fun remove(): Boolean {
            if (!stuck) removes++
            return !stuck
        }
    }

    /** Stored preferences as a map, strict about types as Android's are. */
    private class Prefs(private val stored: Map<String, Any?> = emptyMap()) : SharedPreferences {
        override fun getAll() = stored.toMutableMap()
        override fun getString(key: String, default: String?) = if (key in stored) stored[key] as String? else default
        override fun getStringSet(key: String, default: MutableSet<String>?) = default
        override fun getInt(key: String, default: Int) = if (key in stored) stored[key] as Int else default
        override fun getLong(key: String, default: Long) = if (key in stored) stored[key] as Long else default
        override fun getFloat(key: String, default: Float) = if (key in stored) stored[key] as Float else default
        override fun getBoolean(key: String, default: Boolean) = if (key in stored) stored[key] as Boolean else default
        override fun contains(key: String) = key in stored
        override fun edit(): SharedPreferences.Editor = error("the overlay's setting is never written")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    }

    /** Everything in the overlay's favor. */
    private val allGo = OverlayFacts(allowed = true, connected = true, paused = false, locked = false, awake = true, capturing = false, assistantUp = false, ownScreen = false, lit = true)

    /** Every combination of the facts, with [allowed] as given. */
    private fun everyCase(allowed: Boolean): List<OverlayFacts> = (0 until 256).map { bits ->
        fun bit(n: Int) = bits shr n and 1 == 1
        OverlayFacts(allowed, bit(0), bit(1), bit(2), bit(3), bit(4), bit(5), bit(6), bit(7))
    }

    // The setting.

    /** The owner switched the ring over other apps on (2026-10-12): it is on unless the stored setting is false. */
    @Test fun theOverlayIsOnUnlessTheStoredSettingSaysNo() {
        assertTrue("with nothing stored, as on every install", ringOverlayAllowed(Prefs()))
        assertFalse(ringOverlayAllowed(Prefs(mapOf("ring_overlay" to false))))
        assertFalse("a value that isn't a boolean is off, not a crash", ringOverlayAllowed(Prefs(mapOf("ring_overlay" to "true"))))
        assertFalse(ringOverlayAllowed(Prefs(mapOf("ring_overlay" to 1))))
        assertFalse(ringOverlayAllowed(Prefs(mapOf("ring_overlay" to null))))
        assertTrue("another key says nothing about it", ringOverlayAllowed(Prefs(mapOf("overlay" to false, "told_home_only" to true))))
        assertTrue(ringOverlayAllowed(Prefs(mapOf("ring_overlay" to true))))
    }

    @Test fun factsStartWithEverythingAgainstTheOverlay() {
        assertFalse(OverlayFacts().allowed)
        assertFalse(ringOverlayWanted(OverlayFacts()))
        assertFalse(ringOverlayWatching(OverlayFacts()))
    }

    @Test fun whileNotAllowedNothingEverPutsTheWindowUp() {
        for (case in everyCase(allowed = false)) {
            assertFalse(ringOverlayWanted(case))
            assertFalse("and it isn't even looked at again", ringOverlayWatching(case))
            val window = FakeWindow()
            var facts = case
            val overlay = RingOverlay(window) { facts }
            assertFalse(overlay.sync())
            assertFalse(overlay.relayout())
            assertEquals("nothing to wait for before a screenshot", 0L, overlay.beforeCapture())
            assertFalse(overlay.afterCapture())
            facts = case.copy(lit = !case.lit)
            overlay.sync()
            overlay.close()
            overlay.sync()
            assertEquals(0, window.adds)
            assertEquals(0, window.removes)
            assertFalse(overlay.shown)
        }
    }

    @Test fun nothingInTheAppWritesTheSettingOrReadsItElsewhere() {
        val root = moduleFile("src/main/kotlin/dev/omalauncher")
        assertTrue("the launcher's sources aren't at $root", File(root, "phone/RingOverlay.kt").isFile)
        val sources = root.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        fun count(text: String, what: String) = text.windowed(what.length).count { it == what }

        val home = "phone/RingOverlay.kt"
        // The key: declared once, read once, both in one file.
        assertEquals(setOf(home), sources.filterValues { "RING_OVERLAY_KEY" in it }.keys)
        assertEquals(2, count(sources.getValue(home), "RING_OVERLAY_KEY"))
        assertEquals(1, sources.values.sumOf { count(it, "\"ring_overlay\"") })
        val read = Regex("""fun ringOverlayAllowed\(prefs: SharedPreferences\): Boolean = runCatching \{ prefs\.getBoolean\(RING_OVERLAY_KEY, true\) \}\.getOrDefault\(false\)""")
        assertTrue("the setting is no longer read as on by default and off when unreadable", read.containsMatchIn(sources.getValue(home)))
        // Its file: opened through ringPrefs only, and written once, to remember the one-time line.
        assertEquals(setOf(home, "phone/PhoneService.kt"), sources.filterValues { "ringPrefs(" in it }.keys)
        assertEquals(1, sources.values.sumOf { count(it, "RING_PREFS,") })
        assertEquals(0, sources.values.sumOf { count(it, "getSharedPreferences(\"ring\"") })
        val writes = sources.getValue(home).lines().filter { ".edit()" in it }
        assertEquals(1, writes.size)
        assertTrue(writes.single().trim() == "prefs.edit().putBoolean(RING_TOLD_KEY, true).apply()")
        assertEquals(0, count(sources.getValue("phone/PhoneService.kt"), ".edit()"))
        // Whether it is allowed is asked in two places, and nowhere is the answer made up.
        assertEquals(setOf(home, "phone/PhoneService.kt"), sources.filterValues { "ringOverlayAllowed(" in it }.keys)
        assertEquals(0, sources.values.sumOf { count(it, "allowed = true") } - count(sources.getValue("phone/PhoneService.kt"), "allowed = true"))
        assertEquals(1, count(sources.getValue("phone/PhoneService.kt"), "allowed = true"))
        assertTrue("if (!ringOverlayAllowed(ringPrefs(this))) return OverlayFacts()" in sources.getValue("phone/PhoneService.kt"))
        // Three overlay windows in all, each added in one place: the ring's, the answer card's, and the herd sheep's
        // (Boss, 2026-10-15: "have the pet always on top … even when I'm in an app"), each asked for by the owner.
        assertEquals(setOf(home, "phone/AnswerCard.kt", "herd/SheepOverlay.kt"), sources.filterValues { "TYPE_ACCESSIBILITY_OVERLAY" in it }.keys)
        assertEquals(1, count(sources.getValue("herd/SheepOverlay.kt"), "TYPE_ACCESSIBILITY_OVERLAY"))
        assertEquals(1, count(sources.getValue("phone/AnswerCard.kt"), "TYPE_ACCESSIBILITY_OVERLAY"))
        assertEquals(setOf(home, "phone/PhoneService.kt"), sources.filterValues { "ServiceRingWindow(" in it }.keys)
        assertEquals(1, count(sources.getValue("phone/PhoneService.kt"), "ServiceRingWindow("))
        assertEquals(1, count(sources.getValue(home), ".addView("))
    }

    @Test fun theManifestAsksForNoOverlayPermission() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertFalse("SYSTEM_ALERT_WINDOW" in manifest)
    }

    // When it would show, were it allowed.

    @Test fun itShowsOnlyWhenEveryFactAgrees() {
        assertTrue(ringOverlayWanted(allGo))
        assertEquals(1, everyCase(allowed = true).count(::ringOverlayWanted))
        assertFalse("not while the service is unbound", ringOverlayWanted(allGo.copy(connected = false)))
        assertFalse("not while phone control is paused", ringOverlayWanted(allGo.copy(paused = true)))
        assertFalse("never on the lock screen", ringOverlayWanted(allGo.copy(locked = true)))
        assertFalse("not with the screen off", ringOverlayWanted(allGo.copy(awake = false)))
        assertFalse("never in the agent's screenshot", ringOverlayWanted(allGo.copy(capturing = true)))
        assertFalse("never in the assistant's screenshot", ringOverlayWanted(allGo.copy(assistantUp = true)))
        assertFalse("home draws its own", ringOverlayWanted(allGo.copy(ownScreen = true)))
        assertFalse("nothing to show", ringOverlayWanted(allGo.copy(lit = false)))
    }

    @Test fun itComesDownAtTheLockAndGoesBackAfter() {
        val window = FakeWindow()
        var facts = allGo
        val overlay = RingOverlay(window) { facts }
        overlay.sync()
        assertTrue(window.up)
        facts = allGo.copy(locked = true)
        assertTrue("still worth looking at: it returns when unlocked", overlay.sync())
        assertFalse(window.up)
        facts = allGo
        overlay.sync()
        assertTrue(window.up)
        // Looking again without a change doesn't add a second window.
        overlay.sync()
        overlay.sync()
        assertEquals(2, window.adds)
        assertEquals(1, window.removes)
    }

    @Test fun itIsOutOfTheWayBeforeAScreenshotAndBackAfter() {
        val window = FakeWindow()
        val overlay = RingOverlay(window) { allGo }
        overlay.sync()
        assertEquals(CAPTURE_SETTLE_MILLIS, overlay.beforeCapture())
        assertFalse(window.up)
        // Nothing brings it back while the screenshot is being taken.
        overlay.sync()
        overlay.relayout()
        assertFalse(window.up)
        overlay.afterCapture()
        assertTrue(window.up)
    }

    @Test fun twoScreenshotsAtOnceKeepItDownUntilBothAreTaken() {
        val window = FakeWindow()
        val overlay = RingOverlay(window) { allGo }
        overlay.sync()
        assertEquals(CAPTURE_SETTLE_MILLIS, overlay.beforeCapture())
        assertEquals("already down", 0L, overlay.beforeCapture())
        overlay.afterCapture()
        assertFalse(window.up)
        overlay.afterCapture()
        assertTrue(window.up)
        // One more "after" than "before" doesn't leave it stuck either way.
        overlay.afterCapture()
        assertEquals(CAPTURE_SETTLE_MILLIS, overlay.beforeCapture())
        assertFalse(window.up)
    }

    @Test fun aWindowThatWillNotComeDownRefusesTheScreenshot() {
        val window = FakeWindow()
        var facts = allGo
        val overlay = RingOverlay(window) { facts }
        overlay.sync()
        window.stuck = true
        assertEquals("no picture with the ring in it", CAPTURE_REFUSED, overlay.beforeCapture())
        assertTrue("it is still on the screen, and counted so", overlay.shown)
        assertEquals(CAPTURE_REFUSED, overlay.beforeCapture())
        // Wanted down for any other reason, it stays counted as up and is tried again at the next look.
        facts = allGo.copy(locked = true)
        overlay.sync()
        overlay.close()
        assertTrue(overlay.shown)
        assertEquals(0, window.removes)
        window.stuck = false
        overlay.sync()
        assertFalse(overlay.shown)
        assertEquals(1, window.removes)
        // The refused screenshots left nothing held down: it comes back as soon as the facts allow.
        facts = allGo
        overlay.sync()
        assertTrue(window.up)
        assertEquals(CAPTURE_SETTLE_MILLIS, overlay.beforeCapture())
        assertFalse(window.up)
        overlay.afterCapture()
        assertTrue(window.up)
    }

    @Test fun itIsOutOfTheWayBeforeTheAssistantShowsAndBackAfter() {
        val window = FakeWindow()
        var facts = allGo
        val overlay = RingOverlay(window) { facts }
        overlay.sync()
        facts = allGo.copy(assistantUp = true)
        overlay.sync()
        assertFalse(window.up)
        facts = allGo
        overlay.sync()
        assertTrue(window.up)
    }

    @Test fun aFoldOrATurnLaysItOutAgain() {
        val window = FakeWindow()
        val overlay = RingOverlay(window) { allGo }
        overlay.sync()
        overlay.relayout()
        assertEquals(2, window.adds)
        assertEquals(1, window.removes)
        assertTrue(window.up)
        // Nothing to lay out while it is down.
        val idle = FakeWindow()
        RingOverlay(idle) { allGo.copy(lit = false) }.relayout()
        assertEquals(0, idle.adds + idle.removes)
    }

    @Test fun pausedOrUnboundTakesItDownAndKeepsItDown() {
        val window = FakeWindow()
        var facts = allGo
        val overlay = RingOverlay(window) { facts }
        overlay.sync()
        facts = allGo.copy(paused = true)
        overlay.sync()
        assertFalse(window.up)
        facts = allGo
        overlay.sync()
        overlay.close()
        assertFalse(window.up)
        assertFalse(overlay.shown)
        facts = allGo.copy(connected = false)
        assertFalse("an unbound service stops looking", overlay.sync())
        assertFalse(window.up)
    }

    @Test fun aWindowAndroidRefusesIsNotCountedAsUp() {
        val window = FakeWindow(accepts = false)
        val overlay = RingOverlay(window) { allGo }
        overlay.sync()
        assertFalse(overlay.shown)
        assertEquals("nothing to take down or wait for", 0L, overlay.beforeCapture())
        overlay.afterCapture()
        overlay.close()
        assertEquals(0, window.removes)
        window.accepts = true
        overlay.sync()
        assertTrue(overlay.shown)
    }

    // The window itself.

    @Test fun theWindowCanNeverBeTouchedOrFocused() {
        for (sdk in listOf(29, 30, 34, 36)) {
            val window = ringOverlayWindow(sdk)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, window.type)
            assertTrue(window.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(window.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            // Nothing that would take a touch outside it, show it over the lock, wake the screen, or blank a screenshot.
            val never = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            assertEquals(0, window.flags and never)
            val only = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            assertEquals(only, window.flags)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.width)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.height)
            assertEquals(PixelFormat.TRANSLUCENT, window.format)
        }
        // The numbers Android gives these names, so a stubbed constant can't make the checks above empty.
        assertEquals(2032, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        assertEquals(0x10, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(0x8, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(0x80000, WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        assertEquals(0x2000, WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test fun theWindowReachesTheRealEdgePastACutout() {
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, ringOverlayWindow(29).cutoutMode)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, ringOverlayWindow(30).cutoutMode)
    }

    @Test fun theOverlaysViewOnlyDrawsTheRing() {
        // The view the window holds is RingView: a plain View with no children and no text, drawing through the one painter.
        val root = moduleFile("src/main/kotlin/dev/omalauncher")
        val view = File(root, "oma/ring/RingPainter.kt").readText()
        assertTrue("class RingView(context: Context) : View(context)" in view)
        for (text in listOf("drawText", "TextView", "StaticLayout", "setOnClickListener", "setOnTouchListener", "onTouchEvent")) assertFalse(text, text in view)
        assertTrue("val ring = RingView(service)" in File(root, "phone/RingOverlay.kt").readText())
    }

    // The one-time line.

    @Test fun theRingSaysOnceThatItStaysHomeOnlyWhenItWouldHaveFollowed() {
        assertTrue(ringStaysHome(allowed = true, serviceActive = false, lit = true, told = false))
        assertFalse("said already", ringStaysHome(allowed = true, serviceActive = false, lit = true, told = true))
        assertFalse("it can follow", ringStaysHome(allowed = true, serviceActive = true, lit = true, told = false))
        assertFalse("nothing is showing", ringStaysHome(allowed = true, serviceActive = false, lit = false, told = false))
        // While the overlay isn't allowed the ring was never going to follow, so there is nothing to explain.
        for (active in listOf(true, false)) for (lit in listOf(true, false)) for (told in listOf(true, false)) {
            assertFalse(ringStaysHome(allowed = false, serviceActive = active, lit = lit, told = told))
        }
    }
}
