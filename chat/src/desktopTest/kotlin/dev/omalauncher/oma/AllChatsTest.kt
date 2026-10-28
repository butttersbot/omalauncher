package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** All chats (slice 4): the rows, their groups and times, the search and what it shows, as the mockup has them. */
class AllChatsTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = ZonedDateTime.of(2026, 10, 28, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(days: Long, hour: Int = 10) = ZonedDateTime.of(2026, 10, 28, hour, 30, 0, 0, zone).minusDays(days).toInstant().toEpochMilli()
    private fun thread(key: String, name: String, worked: Long?, doing: String = "", machine: String = "") =
        AgentThread(key, name, ThreadState.Idle, doing, "done", worked = worked, machine = machine)

    @Test fun groupsAndTimesAreByTheDay() {
        assertEquals("Today", allChatGroup(at(0), now, zone))
        assertEquals("Yesterday", allChatGroup(at(1), now, zone))
        assertEquals("This week", allChatGroup(at(5), now, zone))
        assertEquals("Earlier", allChatGroup(at(9), now, zone))
        assertEquals("Earlier", allChatGroup(null, now, zone))
        assertEquals("10:30 AM", allChatWhen(at(0), now, zone))
        assertEquals("FRI", allChatWhen(at(5), now, zone))
        // Yesterday's by its time, as the mockup has it.
        assertEquals("10:30 AM", allChatWhen(at(1), now, zone))
        // A computer's clock a little ahead, past midnight here: today's, never "This week" (a second heading of it
        // crashed the list, slice 4 review).
        val tomorrow = now + 10L * 60 * 60 * 1000
        assertEquals("Today", allChatGroup(tomorrow, now, zone))
        assertEquals("1:00 AM", allChatWhen(tomorrow, now, zone))
        assertEquals("OCT 19", allChatWhen(at(9), now, zone))
    }

    @Test fun rowsAreNewestFirstAndSayWhereTheyAre() {
        val all = listOf(
            thread("mac:jacks-mac-pro/t1", "Soma desktop parity", at(0, 9), "signed build installed", "Mac Pro"),
            thread("phone", "Phone A", at(0, 14), "running the tests"),
            thread("mac:galaxy-z-fold8/builder-b", "Phone B", at(9), "build the app", "Fold"),
        )
        val rows = allChatRows(all, shown = setOf("phone"), archived = setOf("mac:galaxy-z-fold8/builder-b"), kind = { if (it.startsWith("mac:")) "Claude" else "" }, entries = { emptyList() })
        assertEquals(listOf("Phone A", "Soma desktop parity", "Phone B"), rows.map { it.name })
        assertEquals("Fold", rows[0].where)
        assertTrue(rows[0].open)
        assertTrue(rows[2].archived)
        assertEquals("Mac Pro", rows[1].where)
    }

    @Test fun searchFindsNamesLastWordsAndWhatWasSaidHere() {
        val all = listOf(thread("mac:m/t1", "Keyboard GIFs", at(9), "pills swipe sideways"), thread("mac:m/t2", "Fold GPU", at(2), "upstream passed"), thread("mac:m/t3", "Lighthouse", at(0)))
        val said = mapOf("mac:m/t3" to listOf(ChatTask(1L, "Claude", "Write a story about a lighthouse keeper", result = "The lamp had not failed in forty years, and the build went fine.")))
        val rows = allChatRows(all, emptySet(), emptySet(), { "" }, entries = { said[it].orEmpty() })
        val build = allChatMatches(rows, "BUILD", "", { said[it].orEmpty() })
        assertEquals(listOf("Lighthouse"), build.map { it.first.name })
        assertTrue("the words around the match: ${build[0].second}", build[0].second.contains("the build went fine"))
        assertEquals(listOf("Keyboard GIFs"), allChatMatches(rows, "gifs", "", { said[it].orEmpty() }).map { it.first.name })
        assertEquals(3, allChatMatches(rows, " ", "", { emptyList() }).size)
        assertEquals("1 of 3 chats match “build”. Clear the field to see them all, newest first.", allChatCount(1, 3, " build "))
        assertEquals("3 chats, newest first.", allChatCount(3, 3, ""))
    }

    @Test fun aMatchOnlyInMarkupIsNoMatch() {
        val all = listOf(thread("mac:m/t1", "Links", at(0)))
        val said = listOf(ChatTask(1L, "Claude", "where is it", result = "See [the notes](https://example.com/zebra) for more."))
        val rows = allChatRows(all, emptySet(), emptySet(), { "" }, entries = { said })
        assertTrue(allChatMatches(rows, "zebra", "", { said }).isEmpty())
        assertEquals(1, allChatMatches(rows, "notes", "", { said }).size)
    }

    @Test fun aComputerPickedLeavesTheOthersOut() {
        val all = listOf(thread("mac:a/t1", "On the Mac", at(0), machine = "Mac Pro"), thread("mac:f/t1", "On the Fold", at(0), machine = "Fold"))
        val rows = allChatRows(all, emptySet(), emptySet(), { "" }, entries = { emptyList() })
        assertEquals(listOf("On the Fold"), allChatMatches(rows, "", "Fold", { emptyList() }).map { it.first.name })
    }

    @Test fun aSnippetIsAroundTheMatch() {
        val text = "a".repeat(100) + " the needle here " + "b".repeat(100)
        val cut = allChatSnippet(text, "needle", around = 10)
        assertTrue(cut.startsWith("…") && cut.endsWith("…") && cut.contains("needle"))
    }
}
