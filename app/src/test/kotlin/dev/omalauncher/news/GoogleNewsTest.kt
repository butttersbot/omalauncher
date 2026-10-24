package dev.omalauncher.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape of Google News's feeds , as seen 2026-10-22 (made-up stories). */
class GoogleNewsTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Technology - Latest</title>
        <item><title>Phone maker shows a new folding screen - Example Daily</title><link>https://news.google.com/rss/articles/CBMiAbc?oc=5</link>
        <guid isPermaLink="false">CBMiAbc</guid><pubDate>Wed, 21 Oct 2026 23:16:00 GMT</pubDate><description>&lt;ol&gt;&lt;li&gt;related&lt;/li&gt;&lt;/ol&gt;</description>
        <source url="https://example.com">Example Daily</source></item>
        <item><title>A headline with &amp; in it - Some Wire</title><link>https://news.google.com/rss/articles/CBMiDef?oc=5</link>
        <guid isPermaLink="false">CBMiDef</guid><pubDate>Thu, 22 Oct 2026 01:00:00 GMT</pubDate><source url="https://wire.example">Some Wire</source></item>
        </channel></rss>"""

    @Test fun aFeedsItemsAreStoriesOfItsTopicWithoutThePublishersTail() {
        val stories = GoogleNews.parse(feed, "Technology")
        assertEquals(2, stories.size)
        val s = stories[0]
        assertEquals("Phone maker shows a new folding screen", s.title)
        assertEquals("Example Daily", s.source)
        assertEquals("CBMiAbc", s.id)
        assertEquals("Technology", s.topic)
        assertEquals(1792624560L, s.published)
        assertTrue(GoogleNews.isGoogle(s.link))
        // The cluster list in the description is Google's, not the story's.
        assertEquals("", s.html)
        assertEquals("A headline with & in it", stories[1].title)
        // The publisher's site, to find the story on its own feed (PublisherFeeds), not through Google.
        assertEquals("https://example.com", s.site)
        assertEquals("https://wire.example", stories[1].site)
    }

    @Test fun theTopicsAreTopStoriesThenEightSections() {
        assertEquals(9, GoogleNews.TOPICS.size)
        assertEquals("https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en", GoogleNews.feedUrl(GoogleNews.TOPICS[0]))
        assertTrue(GoogleNews.feedUrl(GoogleNews.TOPICS[4]).contains("/headlines/section/topic/TECHNOLOGY?"))
    }
}
