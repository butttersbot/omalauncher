package dev.omalauncher.news

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Google News as Soma News's source (Boss, 2026-10-22: "we should get top stories for sure, but then maybe we show the
 * top five from each category"): its public feeds, top stories and its eight topics, for the US in English. Each story's
 * link is Google's own; [resolve] turns it into the publisher's, which the reader needs to fetch the whole article.
 */
object GoogleNews {
    data class Topic(val name: String, val path: String)

    /** In the order the reader shows them. */
    val TOPICS = listOf(
        Topic("Top stories", ""),
        Topic("U.S.", "headlines/section/topic/NATION"),
        Topic("World", "headlines/section/topic/WORLD"),
        Topic("Business", "headlines/section/topic/BUSINESS"),
        Topic("Technology", "headlines/section/topic/TECHNOLOGY"),
        Topic("Science", "headlines/section/topic/SCIENCE"),
        Topic("Health", "headlines/section/topic/HEALTH"),
        Topic("Entertainment", "headlines/section/topic/ENTERTAINMENT"),
        Topic("Sports", "headlines/section/topic/SPORTS"),
    )

    private const val EDITION = "hl=en-US&gl=US&ceid=US:en"
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 16; SM-F971U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    fun feedUrl(topic: Topic) = "https://news.google.com/rss" + (if (topic.path.isEmpty()) "" else "/" + topic.path) + "?" + EDITION

    fun isGoogle(link: String) = link.startsWith("https://news.google.com/")

    /** A feed's items as stories of [topic], in Google's order. The headline loses its " - Publisher" tail. */
    fun parse(xml: String, topic: String): List<NewsStore.Story> =
        Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val item = m.groupValues[1]
            fun tag(name: String) = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::cdata)
            val source = NewsStore.unescape(tag("source").orEmpty()).trim()
            val raw = NewsStore.unescape(tag("title").orEmpty()).trim()
            val title = if (source.isNotEmpty() && raw.endsWith(" - $source")) raw.removeSuffix(" - $source") else raw
            val link = tag("link").orEmpty().trim()
            val site = Regex("<source[^>]*url=\"([^\"]+)\"").find(item)?.groupValues?.get(1)?.let(NewsStore::unescape).orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            NewsStore.Story(
                id = tag("guid")?.trim()?.ifEmpty { null } ?: link,
                title = title,
                source = source,
                link = link,
                published = tag("pubDate")?.let(::seconds) ?: 0L,
                image = null,
                html = "",
                topic = topic,
                site = site,
            )
        }.toList()

    private fun cdata(s: String) = s.removePrefix("<![CDATA[").removeSuffix("]]>")

    private fun seconds(date: String): Long? =
        runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(date.trim())!!.time / 1000 }.getOrNull()

    fun fetch(topic: Topic): String? = get(feedUrl(topic))

    /** Until when Google is left alone, ms: it sent us to its "unusual traffic" page, or said too many requests. */
    @Volatile private var quietUntil = 0L
    private const val QUIET_MS = 30 * 60_000L

    /** True while Google has asked us to slow down: its feeds aren't asked then. */
    fun busy() = System.currentTimeMillis() < quietUntil

    private fun get(url: String): String? =
        runCatching {
            // Redirects followed here, so the one to Google's "unusual traffic" page is seen; the topic feeds move to
            // /rss/topics/… by an ordinary one (131's first build stopped there and lost every topic).
            var at = URL(url)
            var c: HttpURLConnection
            var hops = 0
            while (true) {
                c = at.openConnection() as HttpURLConnection
                c.connectTimeout = 6_000
                c.readTimeout = 12_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", BROWSER)
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                val code = c.responseCode
                val next = c.getHeaderField("Location").orEmpty()
                if (code == 429 || (code in 300..399 && next.contains("/sorry"))) {
                    quietUntil = System.currentTimeMillis() + QUIET_MS
                    return null
                }
                if (code in 300..399 && next.isNotEmpty() && hops++ < 4) {
                    at = URL(at, next)
                    c.disconnect()
                    continue
                }
                break
            }
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
}
