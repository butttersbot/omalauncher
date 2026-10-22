package dev.omalauncher.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Soma News reads FreshRSS's Google Reader answer: titles, sources, links and a picture worth showing. */
class NewsStoreTest {
    private val answer =
        """{"items":[
          {"id":"tag:google.com,2005:reader/item/1","title":"Tailscale &amp; RSS","published":1792000000,
           "canonical":[{"href":"https://tailscale.com/blog/rss"}],"origin":{"title":"Tailscale Blog"},
           "summary":{"content":"<p>Hello</p><img src=\"https://x.com/pixel.gif\" width=\"1\" height=\"1\"><img src=\"https://tailscale.com/a.jpg\">"}},
          {"id":"2","title":"With an enclosure","published":1791999000,"alternate":[{"href":"https://e.com/2"}],"origin":{"title":"E"},
           "enclosure":[{"href":"https://e.com/big.jpg","type":"image/jpeg"}],"summary":{"content":"<p>text</p>"}},
          {"id":"3","title":"","summary":{"content":"no title"}},
          {"id":"4","title":"No picture","origin":{"title":"F"},"summary":{"content":"<p>just words <img src=\"http://insecure/x.jpg\"></p>"}},
          {"id":"5","title":"FreshRSS 1.27.1","origin":{"title":"FreshRSS releases","streamId":"feed/https://github.com/FreshRSS/FreshRSS/releases.atom"},"summary":{"content":"x"}}
        ]}"""

    @Test fun storiesWithTheirSourceLinkAndPicture() {
        val s = NewsStore.parse(answer)
        assertEquals(listOf("1", "2", "4").map { if (it == "1") "tag:google.com,2005:reader/item/1" else it }, s.map { it.id })
        assertEquals("Tailscale & RSS", s[0].title)
        assertEquals("Tailscale Blog", s[0].source)
        assertEquals("https://tailscale.com/blog/rss", s[0].link)
        // The tracking pixel is passed over for the real picture.
        assertEquals("https://tailscale.com/a.jpg", s[0].image)
        // An image enclosure wins; a plain-http picture is never shown.
        assertEquals("https://e.com/big.jpg", s[1].image)
        assertEquals("https://e.com/2", s[1].link)
        assertNull(s[2].image)
    }
}
