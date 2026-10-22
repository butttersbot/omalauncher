package dev.omalauncher.news

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
            )
        }.toList()

    private fun cdata(s: String) = s.removePrefix("<![CDATA[").removeSuffix("]]>")

    private fun seconds(date: String): Long? =
        runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(date.trim())!!.time / 1000 }.getOrNull()

    fun fetch(topic: Topic): String? = get(feedUrl(topic))

    /**
     * The publisher's address for Google's [link], or null. Google's article page carries a signature and a time for
     * the story; its own batchexecute call answers those with the real address, as news.google.com does in a browser.
     */
    fun resolve(link: String): String? {
        if (!isGoogle(link)) return link
        val id = link.substringAfter("/articles/", "").substringBefore('?').ifEmpty { return null }
        if (busy()) return null
        // One at a time and spaced: a burst of these (every topic's first stories at once, hourly) got the phone's
        // network sent to Google's "unusual traffic" page, and then no article opened (Boss, 2026-10-22).
        synchronized(this) {
            val wait = lastResolve + RESOLVE_GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastResolve = System.currentTimeMillis()
        }
        if (busy()) return null
        val page = get("https://news.google.com/articles/$id") ?: return null
        val sig = Regex("data-n-a-sg=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: return null
        val ts = Regex("data-n-a-ts=\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val inner = JSONArray(listOf("garturlreq",
            JSONArray(listOf(JSONArray(listOf("X", "X", JSONArray(listOf("X", "X")), null, null, 1, 1, "US:en", null, 1, null, null, null, null, null, 0, 1)),
                "X", "X", 1, JSONArray(listOf(1, 1, 1)), 1, 1, null, 0, 0, null, 0)),
            id, ts, sig)).toString()
        val req = JSONArray(listOf(JSONArray(listOf(JSONArray(listOf("Fbv4je", inner, null, "generic")))))).toString()
        val answer = runCatching {
            val c = URL("https://news.google.com/_/DotsSplashUi/data/batchexecute").openConnection() as HttpURLConnection
            c.connectTimeout = 6_000
            c.readTimeout = 10_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", BROWSER)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            c.outputStream.use { it.write(("f.req=" + URLEncoder.encode(req, "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return publisherIn(answer)
    }

    /** The address in batchexecute's answer ("garturlres","https://…"). */
    fun publisherIn(answer: String): String? =
        Regex("""garturlres\\",\\"(https?://[^\\"]+)""").find(answer)?.groupValues?.get(1)?.takeIf { !isGoogle(it) }

    /** Until when Google is left alone, ms: it sent us to its "unusual traffic" page, or said too many requests. */
    @Volatile private var quietUntil = 0L
    private var lastResolve = 0L
    private const val RESOLVE_GAP_MS = 1_500L
    private const val QUIET_MS = 30 * 60_000L

    /** True while Google has asked us to slow down; addresses aren't looked up then. */
    fun busy() = System.currentTimeMillis() < quietUntil

    private fun get(url: String): String? =
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6_000
            c.readTimeout = 12_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", BROWSER)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val code = c.responseCode
            if (code == 429 || (code in 300..399 && c.getHeaderField("Location").orEmpty().contains("/sorry"))) {
                quietUntil = System.currentTimeMillis() + QUIET_MS
                return null
            }
            if (code != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
}
