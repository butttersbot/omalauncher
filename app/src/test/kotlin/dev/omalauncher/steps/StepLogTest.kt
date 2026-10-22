package dev.omalauncher.steps

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StepLogTest {
    @Test fun firstReadingOnlySetsTheMark() {
        val l = StepLog()
        assertEquals(0, l.add(12_000, "2026-10-21"))
        assertEquals(250, l.add(12_250, "2026-10-21"))
        assertEquals(250L, l.days["2026-10-21"])
    }

    @Test fun stepsGoToTheDayTheyWereReadOn() {
        val l = StepLog()
        l.add(100, "2026-10-20")
        l.add(900, "2026-10-20")
        l.add(1_000, "2026-10-21")
        assertEquals(800L, l.days["2026-10-20"])
        assertEquals(100L, l.days["2026-10-21"])
    }

    @Test fun aRestartCountsFromZero() {
        val l = StepLog()
        l.add(5_000, "2026-10-21")
        assertEquals(40, l.add(40, "2026-10-21"))
        assertEquals(60, l.add(100, "2026-10-21"))
        assertEquals(100L, l.days["2026-10-21"])
    }

    @Test fun keepsTwoWeeksAndGivesTheWeekOldestFirst() {
        val l = StepLog()
        val start = LocalDate.parse("2026-10-01")
        l.add(0, start.toString())
        for (i in 0 until 20) l.add((i + 1) * 100L, start.plusDays(i.toLong()).toString())
        assertEquals(StepLog.DAYS, l.days.size)
        val week = l.week(LocalDate.parse("2026-10-20"))
        assertEquals(7, week.size)
        assertEquals(LocalDate.parse("2026-10-14"), week.first().first)
        assertEquals(100L, week.last().second)
        assertEquals(0L, l.week(LocalDate.parse("2026-10-25")).last().second)
    }

    @Test fun survivesItsOwnJson() {
        val l = StepLog()
        l.add(10, "2026-10-21")
        l.add(30, "2026-10-21")
        val back = StepLog.parse(l.json().toString())
        assertEquals(30L, back.last)
        assertEquals(20L, back.days["2026-10-21"])
        assertEquals(-1L, StepLog.parse("nope").last)
    }
}
