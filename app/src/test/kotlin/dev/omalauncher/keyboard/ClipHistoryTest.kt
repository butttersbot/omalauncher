package dev.omalauncher.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The keyboard's clipboard history: newest first, once each, a week, and found by its words. */
class ClipHistoryTest {
    private val t = 1_800_000_000_000L

    @Test fun newestFirstOnceEachAndKeptToTheLimit() {
        val h = ClipHistory(null)
        assertTrue(h.add("hello there", t))
        assertTrue(h.add("1234 Main St", t + 1))
        // The same again moves nothing; one already further down comes back to the top.
        assertFalse(h.add("1234 Main St", t + 2))
        assertTrue(h.add("hello there", t + 3))
        assertEquals(listOf("hello there", "1234 Main St"), h.all(t + 4).map { it.text })
        assertFalse(h.add("   ", t + 5))
        repeat(50) { h.add("clip $it", t + 10 + it) }
        assertEquals(ClipHistory.KEPT, h.all(t + 100).size)
    }

    @Test fun aWeekThenGone() {
        val h = ClipHistory(null)
        h.add("old", t)
        assertEquals(1, h.all(t + 6 * 86_400_000L).size)
        assertEquals(0, h.all(t + 8 * 86_400_000L).size)
    }

    @Test fun foundByEveryWordInIt() {
        val h = ClipHistory(null)
        h.add("Flight UA 1532 confirmation K7XQ2P", t)
        h.add("https://example.com/lease", t + 1)
        assertEquals(listOf("Flight UA 1532 confirmation K7XQ2P"), h.find("ua confirm", t + 2).map { it.text })
        assertEquals(1, h.find("lease", t + 2).size)
        assertEquals(0, h.find("x", t + 2).size)
    }

    @Test fun keptInAFileAndReadBack() {
        val f = File.createTempFile("clips", ".json").also { it.delete() }
        ClipHistory(f).apply {
            add("one", t)
            add("two\nlines", t + 1)
        }
        assertEquals(listOf("two\nlines", "one"), ClipHistory(f).all(t + 2).map { it.text })
        f.delete()
    }

    @Test fun howLongAgoShort() {
        assertEquals("now", ClipPanel.age(5_000))
        assertEquals("5m", ClipPanel.age(5 * 60_000L))
        assertEquals("3h", ClipPanel.age(3 * 3_600_000L))
        assertEquals("2d", ClipPanel.age(2 * 86_400_000L))
        assertEquals("a b", ClipHistory.oneLine("  a\n\tb "))
    }

    @Test fun clearedStaysClearedThoughThePhoneStillHoldsTheLastCopy() {
        val f = File.createTempFile("clips", ".json").also { it.delete() }
        val h = ClipHistory(f)
        h.add("api key", t)
        h.clear(now = t + 10)
        // The keyboard comes up again and reads the same copy, made at t: it stays out.
        assertFalse(h.add("api key", t))
        assertEquals(0, h.all(t + 20).size)
        // Kept out after a restart too; a new copy gets in.
        val back = ClipHistory(f)
        assertFalse(back.add("api key", t))
        assertTrue(back.add("api key", t + 30))
        f.delete()
    }

    @Test fun aRemovedClipStaysOutUntilCopiedAgain() {
        val h = ClipHistory(null)
        h.add("a", t)
        h.add("b", t + 1)
        h.remove("a", now = t + 5)
        assertFalse(h.add("a", t))
        assertEquals(listOf("b"), h.all(t + 6).map { it.text })
        assertTrue(h.add("a", t + 9))
    }
}
