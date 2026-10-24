package dev.omalauncher.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A Google headline found on its publisher's own feed (Boss, 2026-10-23), with made-up stories. */
class PublisherFeedsTest {
    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>Example Daily</title>
        <item><title><![CDATA[Phone maker shows a new folding screen]]></title><link>https://example.com/2026/10/21/folding-screen/</link></item>
        <item><title>Council votes on the &amp; budget</title><link>https://example.com/budget</link></item>
        </channel></rss>"""

    private val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Rocket lands on the barge again</title>
        <link rel="alternate" type="text/html" href="https://wire.example/rocket"/></entry></feed>"""

    @Test fun rssAndAtomItemsAreReadWithTheirOwnAddresses() {
        val items = PublisherFeeds.items(rss)
        assertEquals(2, items.size)
        assertEquals("Phone maker shows a new folding screen", items[0].title)
        assertEquals("https://example.com/2026/10/21/folding-screen/", items[0].link)
        assertEquals("Council votes on the & budget", items[1].title)
        assertEquals("https://wire.example/rocket", PublisherFeeds.items(atom).single().link)
    }

    @Test fun theHeadlineIsMatchedExactlyOrNearly() {
        val items = PublisherFeeds.items(rss)
        assertEquals("https://example.com/2026/10/21/folding-screen/", PublisherFeeds.match("Phone maker shows a new folding screen", items)?.link)
        // Case, quotes and a " - Publisher" tail don't count; a publisher's small edit still matches.
        assertEquals("https://example.com/2026/10/21/folding-screen/", PublisherFeeds.match("Phone Maker Shows New Folding Screen - Example Daily", items)?.link)
        // Another story is not this one.
        assertNull(PublisherFeeds.match("Phone maker recalls its old charger", items))
        assertNull(PublisherFeeds.match("Rain", items))
        // Reworded by the publisher, with much in common: still the story (BBC, 2026-10-23).
        val bbc = listOf(PublisherFeeds.Item("White House lifts tariffs on some Canadian goods as midterm elections near", "https://bbc.example/a"))
        assertEquals("https://bbc.example/a", PublisherFeeds.match("US lifts tariffs on some Canadian goods before midterm elections", bbc)?.link)
    }

    @Test fun aSitesPageNamesItsFeeds() {
        val html = """<html><head><link rel="alternate" type="application/rss+xml" title="All" href="/feeds/all.rss">
            <link rel="stylesheet" href="/a.css"><link type="application/atom+xml" rel="alternate" href="https://cdn.example.com/atom.xml"></head></html>"""
        assertEquals(listOf("https://example.com/feeds/all.rss", "https://cdn.example.com/atom.xml"), PublisherFeeds.feedsIn(html, "https://example.com"))
    }
}
