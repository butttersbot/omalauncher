package dev.omalauncher.search

import dev.omalauncher.search.RootMatch.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search's accuracy, held to a set of searches and the answer each should give first (Boss, 2026-10-20: "the
 * other thing I can't compromise on is accuracy"). Every case is something he types; a change that drops one fails
 * the build. The candidates are like what is on his Fold: his apps, Soma's commands, and Android's settings.
 */
class SearchRankTest {
    private fun app(title: String, vararg keywords: String) = Candidate("app:$title", title, Kind.APP, keywords = keywords.toList())

    private fun cmd(title: String, subtitle: String? = null, vararg keywords: String) = Candidate("cmd:$title", title, Kind.COMMAND, subtitle, keywords.toList())

    private fun setting(title: String, vararg keywords: String) = Candidate("set:$title", title, Kind.SETTING, "Settings", keywords.toList())

    private val all =
        listOf(
            app("Discord"), app("Brave", "browser"), app("Firefox", "browser"), app("Spotify", "music"), app("Camera"),
            app("Gallery", "photos"), app("X", "twitter"), app("YouTube"), app("YouTube Music"), app("Tailscale", "vpn"),
            app("Google Sheets"), app("Google"), app("Gmail", "mail"), app("Messages", "sms text"), app("Phone", "call"),
            app("Settings"), app("Samsung Notes", "notes"), app("Clock", "alarm timer"), app("Calculator"), app("Calendar"),
            app("Ghostty", "terminal"), app("GIMP", "image editor"), app("My Files", "files"), app("Instagram"), app("WhatsApp"),
            cmd("New chat", "Soma", "agent", "ask"), cmd("Clear pen marks", "This page", "ink", "erase"),
            cmd("Page 2", "Go to page"), cmd("Manga", "Look", "theme"), cmd("City Pop", "Look", "theme"),
            setting("Wi-Fi", "wifi", "internet", "network"), setting("Bluetooth", "bt"), setting("Battery", "power", "charge"),
            setting("Display", "brightness", "screen", "dark mode"), setting("Do Not Disturb", "dnd", "silent", "focus"),
            setting("Flashlight", "torch", "light"), setting("Wireless debugging", "adb", "developer"), setting("Sound", "volume"),
        )

    private val now = 1_800_000_000_000L

    private fun top(
        query: String,
        memory: SearchMemory = SearchMemory(),
    ) = SearchRank.rank(all, query, memory, now).firstOrNull()?.title

    private fun topThree(query: String) = SearchRank.rank(all, query, SearchMemory(), now).take(3).map { it.title }

    // ---- The answer first ----

    @Test fun anAppByItsName() {
        assertEquals("Discord", top("discord"))
        assertEquals("Discord", top("disc"))
        assertEquals("Spotify", top("spot"))
        assertEquals("Tailscale", top("tail"))
        assertEquals("Instagram", top("insta"))
    }

    @Test fun anExactNameBeatsALongerOneThatBeginsTheSame() {
        assertEquals("YouTube", top("youtube"))
        assertEquals("Google", top("google"))
        assertEquals("X", top("x"))
    }

    @Test fun initialsAndWordStarts() {
        assertEquals("Google Sheets", top("gs"))
        assertEquals("YouTube Music", top("ytm"))
        assertEquals("Do Not Disturb", top("dnd"))
        assertEquals("Samsung Notes", top("notes"))
        assertEquals("My Files", top("files"))
    }

    @Test fun settingsByWhatTheyAreCalledAndWhatTheyDo() {
        assertEquals("Wi-Fi", top("wifi"))
        assertEquals("Wi-Fi", top("wi-fi"))
        assertEquals("Bluetooth", top("blue"))
        assertEquals("Flashlight", top("torch"))
        assertEquals("Wireless debugging", top("adb"))
        assertEquals("Display", top("brightness"))
    }

    @Test fun somasOwnCommands() {
        assertEquals("Clear pen marks", top("clear pen"))
        assertEquals("New chat", top("new chat"))
        assertEquals("Manga", top("manga"))
        assertEquals("Page 2", top("page 2"))
    }

    @Test fun wordsInTheMiddleOfAName() {
        assertTrue("Music" in topThree("music").joinToString())
        assertEquals("Samsung Notes", top("notes"))
    }

    @Test fun noiseFindsNothing() {
        assertTrue(SearchRank.rank(all, "zqxv", SearchMemory(), now).isEmpty())
        assertTrue(SearchRank.rank(all, "   ", SearchMemory(), now).isEmpty())
        // Letters scattered across a long name are not a match.
        assertFalse(SearchRank.rank(all, "dsr", SearchMemory(), now).any { it.title == "Wireless debugging" })
    }

    // ---- What he does teaches it ----

    @Test fun aSearchHePickedFromKeepsMeaningThat() {
        val m = SearchMemory()
        // "b" first finds Brave or Bluetooth; once he picks Firefox after typing "b", "b" means Firefox.
        m.visit("app:Firefox", "b", now - 60_000)
        assertEquals("Firefox", top("b", m))
        // And its first letters of it still do.
        m.visit("app:Firefox", "ff", now - 30_000)
        assertEquals("Firefox", top("ff", m))
    }

    @Test fun anAliasWinsOutright() {
        val m = SearchMemory()
        m.setAlias("app:Ghostty", "t")
        assertEquals("Ghostty", top("t", m))
    }

    @Test fun moreUsedWinsATie() {
        val m = SearchMemory()
        repeat(3) { m.visit("app:Gmail", "", now - 3_600_000L * it) }
        assertEquals("Gmail", top("g", m))
    }

    @Test fun learnedSearchesExpire() {
        val m = SearchMemory()
        m.visit("app:Firefox", "b", now - 18L * 24 * 3600 * 1000)
        assertTrue(m.terms("app:Firefox", now).isEmpty())
    }

    @Test fun whatHeHidIsGone() {
        val m = SearchMemory()
        m.hide("app:Discord")
        assertNull(SearchRank.rank(all, "discord", m, now).firstOrNull { it.title == "Discord" })
    }

    @Test fun memoryIsKept() {
        val m = SearchMemory()
        m.visit("app:Firefox", "ff", now)
        m.setAlias("app:Ghostty", "t")
        m.toggleFavorite("app:Spotify")
        m.hide("app:Discord")
        val back = SearchMemory.parse(m.toText())
        assertEquals(m.frecency("app:Firefox", now), back.frecency("app:Firefox", now), 1e-6)
        assertEquals(listOf("ff"), back.terms("app:Firefox", now))
        assertEquals("t", back.alias("app:Ghostty"))
        assertEquals(0, back.favorite("app:Spotify"))
        assertTrue(back.hidden("app:Discord"))
    }

    @Test fun frecencyHalvesInTenDays() {
        val m = SearchMemory()
        m.visit("x", "", now)
        val first = m.frecency("x", now)
        assertEquals(101.0, first, 0.01)
        assertEquals(50.5, m.frecency("x", now + 10L * 24 * 3600 * 1000), 0.1)
    }

    // ---- The matcher itself, as Raycast scores ----

    @Test fun theMatcherScoresAsRaycasts() {
        assertEquals(Match.Exact, RootMatch.match("Firefox", "firefox"))
        assertEquals(Match.Scored(5, 0), RootMatch.match("Firefox", "ff"))
        assertEquals(Match.Unmatched, RootMatch.match("Firefox", "fz"))
        // A word start scores 3: "gs" is G(4) then the S of Sheets (3, after a jump: -1).
        assertEquals(Match.Scored(6, 0), RootMatch.match("Google Sheets", "gs"))
        assertTrue(RootMatch.wholeWord("YouTube Music", "music"))
        assertFalse(RootMatch.wholeWord("Musicolet", "music"))
        assertEquals("cafe", RootMatch.fold("Café"))
    }

    // ---- The calculator ----

    @Test fun sums() {
        assertEquals("87", Calculator.answer("12*7+3")?.plain)
        assertEquals("1,024", Calculator.answer("2^10")?.shown)
        assertEquals("12.6", Calculator.answer("15% of 84")?.plain)
        assertEquals("96.6", Calculator.answer("84 + 15%")?.plain)
        assertEquals("1.414213562", Calculator.answer("sqrt(2)")?.plain)
        assertEquals("2.5", Calculator.answer("(2+3)/2")?.plain)
        assertEquals("-6", Calculator.answer("-2*3")?.plain)
    }

    @Test fun units() {
        assertEquals("8.04672 km", Calculator.answer("5 mi to km")?.shown)
        assertEquals("22.22222222 °C", Calculator.answer("72 f in c")?.shown)
        assertEquals("3,000 MB", Calculator.answer("3 gb in mb")?.shown)
        assertNull(Calculator.answer("5 kg to km"))
    }

    @Test fun notEverythingIsASum() {
        assertNull(Calculator.answer("42"))
        assertNull(Calculator.answer("discord"))
        assertNull(Calculator.answer("page 2"))
        assertNull(Calculator.answer("wi-fi"))
        assertNotNull(Calculator.answer("10-4"))
    }
}
