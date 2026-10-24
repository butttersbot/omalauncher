package dev.omalauncher.news

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A Google News headline's own article, found on its publisher's RSS feed rather than asked of Google (Boss,
 * 2026-10-23): Google's feed names each story's publisher and site, the site names its feed, and the feed has the same
 * headline with the publisher's own address. Google rate-limits looking its links up (429 on the phone's cellular
 * address), and going round that is not ours to do; a publisher with no feed, or a story not on it, is read at its
 * source in the browser instead.
 *
 * The parsing and matching are pure, for the JVM tests; the fetching keeps each site's feed address a day and its
 * items a quarter of an hour, so a refresh asks each publisher once.
 */
object PublisherFeeds {
    class Item(
        val title: String,
        val link: String,
    )

    /** A headline as compared: the words only, lower case, without a " - Publisher" or " | Publisher" tail. */
    fun words(title: String): List<String> =
        title.substringBeforeLast(" - ").substringBeforeLast(" | ")
            .lowercase()
            .replace(Regex("[‘’'“”\"]"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    /**
     * The feed item that is [headline], or null: the same words, or nearly (a publisher may tweak a headline after
     * Google took it): at least [NEAR] of the two headlines' words in common, or [LOOSE] when [SHARED] words or more are
     * (BBC's "US lifts tariffs on some Canadian goods before midterm…" is "White House lifts tariffs on some Canadian
     * goods as midterm…" on its feed).
     */
    fun match(
        headline: String,
        items: List<Item>,
    ): Item? {
        val want = words(headline)
        if (want.size < 3) return items.firstOrNull { words(it.title) == want }
        items.firstOrNull { words(it.title) == want }?.let { return it }
        val a = want.toSet()
        return items
            .map { it to words(it.title).toSet() }
            .filter { (_, b) -> b.size >= 3 }
            .map { (item, b) -> Triple(item, (a intersect b).size, (a intersect b).size.toDouble() / (a union b).size) }
            .filter { (_, shared, near) -> near >= NEAR || (near >= LOOSE && shared >= SHARED) }
            .map { (item, _, near) -> item to near }
            .maxByOrNull { it.second }
            ?.first
    }

    /** The feeds a site's page names (<link rel="alternate" type="application/rss+xml|atom+xml" href=…>), absolute. */
    fun feedsIn(
        html: String,
        base: String,
    ): List<String> =
        Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
            .filter { tag -> Regex("rel=[\"']?alternate", RegexOption.IGNORE_CASE).containsMatchIn(tag) && Regex("application/(rss|atom)\\+xml", RegexOption.IGNORE_CASE).containsMatchIn(tag) }
            .mapNotNull { tag -> Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) }
            .mapNotNull { runCatching { URL(URL(base), NewsStore.unescape(it)).toString() }.getOrNull() }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()
            .toList()

    /** An RSS or Atom feed's items: each one's title and its own address. */
    fun items(xml: String): List<Item> {
        fun clean(s: String) = NewsStore.unescape(s.removePrefix("<![CDATA[").removeSuffix("]]>")).trim()
        val rss =
            Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val body = m.groupValues[1]
                val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::clean)
                val link = Regex("<link[^>]*>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::clean)
                    ?: Regex("<guid[^>]*isPermaLink=\"true\"[^>]*>(.*?)</guid>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::clean)
                if (title.isNullOrEmpty() || link.isNullOrEmpty() || !link.startsWith("http")) null else Item(title, link)
            }.toList()
        if (rss.isNotEmpty()) return rss
        return Regex("<entry\\b[^>]*>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::clean)
            val link = Regex("<link\\b[^>]*rel=[\"']alternate[\"'][^>]*href=[\"']([^\"']+)[\"']", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                ?: Regex("<link\\b[^>]*href=[\"']([^\"']+)[\"']", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            if (title.isNullOrEmpty() || link.isNullOrEmpty()) null else Item(title, NewsStore.unescape(link))
        }.toList()
    }

    private class Kept<T>(val value: T, val at: Long)

    private val feedOf = ConcurrentHashMap<String, Kept<List<String>>>()
    private val itemsOf = ConcurrentHashMap<String, Kept<List<Item>>>()

    /**
     * The publisher's own address for the story [headline] from [site] (Google's `<source url>`, the publisher's home
     * page), or null. Network; off the main thread.
     */
    fun find(
        site: String,
        headline: String,
    ): String? {
        if (!site.startsWith("https://") && !site.startsWith("http://")) return null
        val now = System.currentTimeMillis()
        val known = KNOWN[hostOf(site)]
        val feeds =
            known
                ?: feedOf[site]?.takeIf { now - it.at < FEEDS_FOR_MS }?.value
                ?: discover(site).also { feedOf[site] = Kept(it, now) }
        for (feed in feeds.take(known?.size ?: MAX_FEEDS)) {
            val items =
                itemsOf[feed]?.takeIf { now - it.at < ITEMS_FOR_MS }?.value
                    ?: (get(feed)?.let(::items) ?: emptyList()).also { itemsOf[feed] = Kept(it, now) }
            match(headline, items)?.let { return it.link }
        }
        return null
    }

    private fun hostOf(site: String) = runCatching { URL(site).host.removePrefix("www.") }.getOrDefault("")

    /**
     * Big publishers' own feeds, which their home pages don't name (checked 2026-10-23 against Google's top stories:
     * with these, about half of them are found; Reuters, AP, Bloomberg and Barron's have no public feed).
     */
    private val KNOWN: Map<String, List<String>> =
        mapOf(
            "nytimes.com" to listOf("HomePage", "World", "US", "Politics", "Business", "Technology", "Science", "Health", "Sports", "Arts").map { "https://rss.nytimes.com/services/xml/rss/nyt/$it.xml" },
            "bbc.com" to listOf("news", "news/world", "news/world/us_and_canada", "news/business", "news/technology", "news/science_and_environment", "news/health", "news/entertainment_and_arts", "sport").map { "https://feeds.bbci.co.uk/$it/rss.xml" },
            "bbc.co.uk" to listOf("news", "news/world", "news/uk", "news/business", "news/technology", "sport").map { "https://feeds.bbci.co.uk/$it/rss.xml" },
            "washingtonpost.com" to listOf("national", "world", "politics", "business", "business/technology", "sports").map { "https://feeds.washingtonpost.com/rss/$it" },
            "politico.com" to listOf("politics-news", "congress", "economy", "healthcare").map { "https://rss.politico.com/$it.xml" },
            "slate.com" to listOf("https://slate.com/feeds/all.rss"),
            "npr.org" to listOf("1001", "1004", "1014", "1003", "1006", "1019", "1007", "1128").map { "https://feeds.npr.org/$it/rss.xml" },
            "cnbc.com" to listOf("100003114", "10001147", "19854910", "10000664").map { "https://www.cnbc.com/id/$it/device/rss/rss.html" },
            "cbsnews.com" to listOf("https://www.cbsnews.com/latest/rss/main"),
            "abcnews.go.com" to listOf("topstories", "usheadlines", "politicsheadlines", "internationalheadlines").map { "https://abcnews.go.com/abcnews/$it" },
            "nbcnews.com" to listOf("https://feeds.nbcnews.com/nbcnews/public/news"),
            "theatlantic.com" to listOf("https://www.theatlantic.com/feed/all/"),
            "wsj.com" to listOf("RSSWorldNews", "RSSUSnews", "WSJcomUSBusiness", "RSSMarketsMain", "RSSWSJD").map { "https://feeds.content.dowjones.io/public/rss/$it" },
            "latimes.com" to listOf("local", "world-nation", "politics").map { "https://www.latimes.com/$it/rss2.0.xml" },
            "usatoday.com" to listOf("https://rssfeeds.usatoday.com/usatoday-NewsTopStories"),
            "pbs.org" to listOf("https://www.pbs.org/newshour/feeds/rss/headlines"),
        )

    /** The site's own feeds: those its home page names, then the usual places. */
    private fun discover(site: String): List<String> {
        val named = get(site)?.let { feedsIn(it, site) }.orEmpty()
        if (named.isNotEmpty()) return named
        val root = site.trimEnd('/')
        return listOf("$root/feed", "$root/rss", "$root/rss.xml", "$root/feed.xml", "$root/index.xml")
            .filter { url -> get(url)?.let { items(it).isNotEmpty() } == true }
            .take(1)
    }

    private fun get(url: String): String? =
        runCatching {
            var at = URL(url)
            var hops = 0
            while (true) {
                val c = at.openConnection() as HttpURLConnection
                c.connectTimeout = 6_000
                c.readTimeout = 10_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", NewsStore.PHONE_BROWSER)
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                val code = c.responseCode
                val next = c.getHeaderField("Location")
                if (code in 300..399 && next != null && hops++ < 4) {
                    at = URL(at, next)
                    // Never to Google: its pages are not the publisher's.
                    if (at.host.endsWith("google.com")) return null
                    c.disconnect()
                    continue
                }
                if (code != 200) return null
                return c.inputStream.bufferedReader().use { r -> r.readText().take(MAX_BYTES) }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }.getOrNull()

    /** Share of words in common for two headlines to be the same story. */
    const val NEAR = 0.75

    /** A looser share, when at least [SHARED] words are the same. */
    const val LOOSE = 0.5
    const val SHARED = 5
    private const val MAX_FEEDS = 3
    private const val MAX_BYTES = 3_000_000
    private const val FEEDS_FOR_MS = 24 * 3_600_000L
    private const val ITEMS_FOR_MS = 15 * 60_000L
}
