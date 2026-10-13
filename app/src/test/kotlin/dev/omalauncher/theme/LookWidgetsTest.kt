package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class LookWidgetsTest {
    private fun at(hour: Int, minute: Int) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 12, hour, minute, 0) }.time

    @Test fun theTimeFollowsTheOwnersClockAndSaysNoAmOrPm() {
        assertEquals("9:48", LookWidgets.timeText(at(21, 48), twentyFour = false, Locale.US))
        assertEquals("21:48", LookWidgets.timeText(at(21, 48), twentyFour = true, Locale.US))
        assertEquals("12:05", LookWidgets.timeText(at(0, 5), twentyFour = false, Locale.US))
        assertEquals("00:05", LookWidgets.timeText(at(0, 5), twentyFour = true, Locale.US))
    }

    @Test fun theDateIsTheDayAndTheMonthInWords() {
        assertEquals("Mon, October 12", LookWidgets.dateText(at(9, 0), Locale.US))
    }

    @Test fun aPictureIsItsWidgetsSizeAndNeverOverTheMost() {
        assertEquals(520 to 220, LookWidgets.fit(260, 110, 2f))
        // A large widget on a dense screen is made smaller in one piece, its shape kept.
        val (w, h) = LookWidgets.fit(800, 400, 3f)
        assertEquals(1400, w)
        assertEquals(700, h)
        assertTrue(LookWidgets.fit(0, 0, 3f).let { it.first >= 1 && it.second >= 1 })
    }
}
