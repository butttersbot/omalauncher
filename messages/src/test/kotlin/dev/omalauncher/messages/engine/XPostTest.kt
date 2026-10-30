package dev.omalauncher.messages.engine

import org.junit.Assert.*
import org.junit.Test

class XPostTest {
    /** The feed's real body for a public photo post, 2026-10-30, trimmed to the fields read. */
    private val photoPost = """{"__typename":"Tweet","display_text_range":[0,37],
        "entities":{"media":[{"display_url":"pic.x.com/bAJE6Vom","url":"http://t.co/bAJE6Vom"}]},
        "text":"Four more years. http://t.co/bAJE6Vom","user":{"name":"Barack Obama","screen_name":"BarackObama"},
        "mediaDetails":[{"media_url_https":"https://pbs.twimg.com/media/A7EiDWcCYAAZT1D.jpg","type":"photo"}],
        "photos":[{"url":"https://pbs.twimg.com/media/A7EiDWcCYAAZT1D.jpg"}]}"""

    @Test fun statusIdsFromEveryShapeAPostLinkComesIn() {
        val id = "266031293945503744"
        for (url in listOf(
            "https://x.com/BarackObama/status/$id", "https://www.x.com/BarackObama/status/$id?s=20",
            "https://twitter.com/BarackObama/status/$id/", "http://mobile.twitter.com/a/statuses/$id",
            "https://x.com/i/web/status/$id", "https://x.com/a/status/$id/photo/1",
        )) assertEquals(url, id, XPost.statusId(url))
    }

    @Test fun onlyPostsOnXAreAsked() {
        for (url in listOf(
            "https://x.com/BarackObama", "https://x.com/a/status/notanumber", "https://x.com.evil.example/a/status/1",
            "https://example.com/a/status/1", "ftp://x.com/a/status/1", "not a url",
        )) assertNull(url, XPost.statusId(url))
    }

    @Test fun photoPostGivesItsWordsWithoutThePointerAndItsPicture() {
        val post = XPost.parse(photoPost)!!
        assertEquals("Four more years.", post.title)
        assertEquals("https://pbs.twimg.com/media/A7EiDWcCYAAZT1D.jpg", post.image)
    }

    @Test fun textPostHasWordsAndNoPicture() {
        val post = XPost.parse("""{"__typename":"Tweet","text":"just setting up my twttr","user":{"name":"jack"}}""")!!
        assertEquals("just setting up my twttr", post.title)
        assertNull(post.image)
    }

    @Test fun linksInWordsReadAsTheirAddress() {
        val post = XPost.parse("""{"text":"read https://t.co/abc","entities":{"urls":[{"url":"https://t.co/abc","display_url":"example.com/story"}]}}""")!!
        assertEquals("read example.com/story", post.title)
    }

    @Test fun onlyXsOwnImageHostIsTaken() {
        val post = XPost.parse("""{"text":"hi","mediaDetails":[{"media_url_https":"https://evil.example/a.jpg"},{"media_url_https":"http://pbs.twimg.com/a.jpg"}]}""")!!
        assertNull(post.image)
    }

    @Test fun deletedOrBrokenBodiesAreNoPost() {
        assertNull(XPost.parse("""{"__typename":"TweetTombstone","tombstone":{"text":{"text":"gone"}}}"""))
        assertNull(XPost.parse("<!DOCTYPE html><html></html>"))
        assertNull(XPost.parse("{}"))
    }

    @Test fun feedAddressIsTheFixedHostWithTheId() {
        assertEquals("https://cdn.syndication.twimg.com/tweet-result?id=20&token=a&lang=en", XPost.feed("20"))
    }

    @Test fun theMacsPictureStaysAndThePostFillsWhatItLacks() {
        val post = RichLink("u", "words", "x.com", "x:1")
        val plain = RichLink("u", "words", "x.com")
        val macPicture = RichLink("u", "native", "x.com", "mac-id")
        val macWords = RichLink("u", "native", "x.com")
        val unavailable = RichLink("u", state = "unavailable")
        assertNull(XPost.fill(null, null))
        assertSame(unavailable, XPost.fill(unavailable, null))
        assertSame(post, XPost.fill(null, post))
        assertSame(post, XPost.fill(unavailable, post))
        assertSame(post, XPost.fill(macWords, post))
        assertSame(macPicture, XPost.fill(macPicture, post))
        assertSame(macWords, XPost.fill(macWords, plain))
    }
}
