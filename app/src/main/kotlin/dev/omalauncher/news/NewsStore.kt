package dev.omalauncher.news

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Soma News (Boss, 2026-10-21: "the delightful personal news reader", mock-up approved): what came in, read from
 * Google News ([GoogleNews]; FreshRSS on the Mac Pro until 2026-10-22, whose reader below stays for its tests), kept as one JSON file in the guest's
 * ~/.cache/soma-news/stream.json. The reader screen and the home widgets both read that file. It is brought up to
 * date on the hour and whenever he taps ↻.
 *
 * The address, user and API password are in the guest's ~/.config/omalauncher/freshrss.txt (url=, user=,
 * api_password=), never in the app.
 */
object NewsStore {
    data class Config(
        val url: String,
        val user: String,
        val password: String,
    )

    /** One story as the reader and the widgets use it. */
    data class Story(
        val id: String,
        val title: String,
        val source: String,
        val link: String,
        /** Unix seconds. */
        val published: Long,
        val image: String?,
        /** The story's HTML as the feed gave it (the reader cleans it), or its summary. */
        val html: String,
        /** Its Google News topic ([GoogleNews.TOPICS]), "" for a story from elsewhere. */
        val topic: String = "",
    ) {
        fun toJson(): JSONObject =
            JSONObject().put("id", id).put("title", title).put("source", source).put("link", link).put("published", published).put("image", image ?: JSONObject.NULL).put("html", html).put("topic", topic)

        companion object {
            fun of(j: JSONObject) =
                Story(
                    id = j.optString("id"),
                    title = j.optString("title"),
                    source = j.optString("source"),
                    link = j.optString("link"),
                    published = j.optLong("published"),
                    image = if (j.isNull("image")) null else j.optString("image"),
                    html = j.optString("html"),
                    topic = j.optString("topic"),
                )
        }
    }

    fun home(context: Context) = File(context.filesDir, "arch/root")

    fun file(context: Context) = File(home(context), ".cache/soma-news/stream.json")

    fun config(context: Context): Config? {
        val lines = runCatching { File(home(context), ".config/omalauncher/freshrss.txt").readLines() }.getOrNull() ?: return null
        val kv = lines.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        return Config(kv["url"]?.trimEnd('/') ?: return null, kv["user"] ?: return null, kv["api_password"] ?: return null)
    }

    /** What the file holds now: its JSON as text, or an empty stream. */
    fun cached(context: Context): String = runCatching { file(context).readText() }.getOrNull() ?: JSONObject().put("stories", JSONArray()).put("updated", 0).toString()

    /**
     * Brings the stream up to date from Google News (Boss, 2026-10-22: Google instead of the FreshRSS feeds): top stories
     * and every topic, each story once (the first topic that has it keeps it), at most [perTopic] a topic. The stories
     * the stream shows first ([RESOLVED_FIRST] a topic) get the publisher's address and picture now; the rest when
     * opened. Addresses and pictures already found are kept from one refresh to the next.
     */
    fun refresh(
        context: Context,
        perTopic: Int = PER_TOPIC,
    ): Result<Int> {
        // The feeds one after another, a moment apart: side by side they were part of the burst Google turned away.
        val got = GoogleNews.TOPICS.mapIndexed { i, t ->
            if (i > 0) Thread.sleep(FEED_GAP_MS)
            GoogleNews.fetch(t)?.let { GoogleNews.parse(it, t.name) }.orEmpty()
        }
        if (got.all { it.isEmpty() }) return Result.failure(IllegalStateException("Google News didn't answer"))
        // A topic whose feed didn't come keeps what it had: one refresh had every topic but Top stories go (the 131 walk).
        val before = runCatching { JSONObject(cached(context)).getJSONArray("stories") }.getOrNull()?.let { a -> (0 until a.length()).map { Story.of(a.getJSONObject(it)) } }.orEmpty()
        val fetched = GoogleNews.TOPICS.mapIndexed { i, t -> got[i].ifEmpty { before.filter { it.topic == t.name } } }
        val seen = HashSet<String>()
        val stories = fetched.flatMap { topic -> topic.filter { seen.add(it.id) && seen.add(it.title.lowercase()) }.take(perTopic) }
        // What earlier refreshes found: the publisher's address and its picture, by story id.
        val known = HashMap<String, JSONObject>()
        runCatching { JSONObject(cached(context)).getJSONArray("stories") }.getOrNull()?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { known[it.optString("id")] = it } }
        val kept = stories.map { s ->
            val k = known[s.id] ?: return@map s
            s.copy(link = k.optString("link").takeIf { it.isNotEmpty() && !GoogleNews.isGoogle(it) } ?: s.link, image = if (k.isNull("image")) null else k.optString("image"))
        }
        val first = kept.groupBy { it.topic }.values.flatMap { it.take(RESOLVED_FIRST) }.filter { GoogleNews.isGoogle(it.link) || it.image == null }
        // Google's addresses one at a time (GoogleNews.resolve spaces them), at most RESOLVE_PER_REFRESH a refresh,
        // the top stories first; what isn't looked up now is when it's opened, or next hour. The pictures come from the
        // publishers, so those may go side by side.
        var asked = 0
        val linked = first.associate { s ->
            val link =
                if (GoogleNews.isGoogle(s.link) && asked < RESOLVE_PER_REFRESH && !GoogleNews.busy()) {
                    asked++
                    GoogleNews.resolve(s.link) ?: s.link
                } else {
                    s.link
                }
            s.id to link
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val done = try {
            kept.map { s ->
                val link = linked[s.id] ?: return@map java.util.concurrent.CompletableFuture.completedFuture(s)
                java.util.concurrent.CompletableFuture.supplyAsync({
                    s.copy(link = link, image = s.image ?: link.takeIf { !GoogleNews.isGoogle(it) }?.let(::pageImage))
                }, pool)
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        write(context, done)
        return Result.success(done.size)
    }

    private fun write(
        context: Context,
        stories: List<Story>,
    ) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONObject().put("updated", System.currentTimeMillis() / 1000).put("stories", JSONArray(stories.map { it.toJson() })).toString())
        tmp.renameTo(f)
        // The home widget's own small file (a widget reads 1 MB at most): the newest stories, pictures first.
        val top = (stories.filter { it.image != null }.take(1) + stories).distinctBy { it.id }.take(WIDGET_STORIES)
        val w = File(f.parentFile, "widget.json")
        val wtmp = File(f.parentFile, "widget.json.tmp")
        wtmp.writeText(
            JSONObject().put("updated", System.currentTimeMillis() / 1000).put(
                "stories",
                JSONArray(top.map { JSONObject().put("title", it.title).put("source", it.source).put("image", it.image ?: JSONObject.NULL).put("published", it.published) }),
            ).toString(),
        )
        wtmp.renameTo(w)
    }

    /** The Google Reader API's answer as stories, newest first. */
    fun parse(json: String): List<Story> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val html = o.optJSONObject("content")?.optString("content")?.takeIf { it.isNotBlank() } ?: o.optJSONObject("summary")?.optString("content").orEmpty()
            val link = o.optJSONArray("canonical")?.optJSONObject(0)?.optString("href") ?: o.optJSONArray("alternate")?.optJSONObject(0)?.optString("href").orEmpty()
            val enclosure =
                o.optJSONArray("enclosure")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("type").startsWith("image") }?.optString("href")
                }
            // The installer's own feed (FreshRSS's releases) is the server's business, not news.
            if (o.optJSONObject("origin")?.optString("streamId").orEmpty().contains("github.com/FreshRSS", ignoreCase = true) ||
                o.optJSONObject("origin")?.optString("title").orEmpty().startsWith("FreshRSS", ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            Story(
                id = o.optString("id"),
                title = unescape(o.optString("title")).ifBlank { return@mapNotNull null },
                source = sourceName(unescape(o.optJSONObject("origin")?.optString("title").orEmpty())),
                link = link,
                published = o.optLong("published"),
                image = enclosure?.takeIf { it.startsWith("https://") } ?: firstImage(html),
                html = html,
            )
        }
    }

    /**
     * A feed's name as a reader says it. A feed imported by its address is named by it ("https://www.theverge.com/rss/
     * index.xml"): that becomes the site ("The Verge" for the ones he follows, else "Theverge").
     */
    fun sourceName(title: String): String {
        if (!title.startsWith("http://") && !title.startsWith("https://")) return title
        val host = runCatching { URL(title).host.removePrefix("www.").removePrefix("feeds.") }.getOrNull() ?: return title
        KNOWN[host]?.let { return it }
        return host.substringBefore('.').replaceFirstChar { it.uppercase() }
    }

    private val KNOWN =
        mapOf(
            "theverge.com" to "The Verge", "tailscale.com" to "Tailscale", "hnrss.org" to "Hacker News", "importai.substack.com" to "Import AI",
            "androidpolice.com" to "Android Police", "9to5google.com" to "9to5Google", "arstechnica.com" to "Ars Technica",
            "sammobile.com" to "SamMobile", "simonwillison.net" to "Simon Willison",
        )

    /** The first picture in a story's HTML worth showing: https, and not a tracking pixel or an icon. */
    fun firstImage(html: String): String? =
        Regex("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] to it.value }.firstOrNull { (src, tag) ->
            src.startsWith("https://") && !Regex("(width|height)=[\"']?1[\"' ]").containsMatchIn(tag) && !src.contains("feeds.feedburner") && !src.endsWith(".svg")
        }?.first?.let(::unescape)

    /** A page's own picture (og:image or twitter:image), read from its first 200 KB; null if none. */
    private fun pageImage(link: String): String? =
        runCatching {
            val conn = URL(link).openConnection() as HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) Soma News")
            val head = conn.inputStream.use { s -> String(s.readNBytesCompat(200_000)) }
            Regex("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"']", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        }.getOrNull()?.let(::unescape)?.takeIf { it.startsWith("https://") }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        while (out.size() < n) {
            val r = read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    /**
     * A story's own page, as the site serves it to a phone's browser, for the reader to take the whole article from
     * (Boss, 2026-10-21: "Can we pull the full articles? I don't want to have to leave to the website"). Null when
     * it isn't a web page or doesn't answer. Off the main thread.
     */
    fun page(link: String): String? {
        if (!link.startsWith("https://") && !link.startsWith("http://")) return null
        // A Google News link is Google's page, not the story: the publisher's address first.
        if (GoogleNews.isGoogle(link)) return GoogleNews.resolve(link)?.let(::page)
        return runCatching {
            var url = URL(link)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout = 12_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", PHONE_BROWSER)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    val next = conn.getHeaderField("Location") ?: break
                    url = URL(url, next)
                    if (url.protocol != "https" && url.protocol != "http") return null
                    conn.disconnect()
                    continue
                }
                break
            }
            if (conn.responseCode != 200) return null
            val type = conn.contentType.orEmpty()
            if (type.isNotEmpty() && !type.contains("html", ignoreCase = true)) return null
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
            val bytes = conn.inputStream.use { it.readNBytesCompat(PAGE_BYTES) }
            String(bytes, runCatching { charset(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun fullDir(context: Context) = File(home(context), ".cache/soma-news/full")

    private fun fullFile(context: Context, link: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(fullDir(context), "$hash.html")
    }

    /** A story's whole article as the reader took it from its page before, or "" when it hasn't yet. */
    fun full(context: Context, link: String): String = runCatching { fullFile(context, link).readText() }.getOrDefault("")

    /** Keeps the whole article the reader took from [link]'s page, the newest [FULL_KEPT] of them. */
    fun keepFull(context: Context, link: String, html: String) {
        if (html.length > PAGE_BYTES) return
        runCatching {
            val f = fullFile(context, link)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(html)
            tmp.renameTo(f)
            fullDir(context).listFiles { x -> x.name.endsWith(".html") }?.sortedByDescending { it.lastModified() }?.drop(FULL_KEPT)?.forEach { it.delete() }
        }
    }

    fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")

    /** Stories the home widget's file keeps: the large widget shows a lead and three more. */
    private const val WIDGET_STORIES = 6

    /** How much of a story's page is read for its article, at most. */
    private const val PAGE_BYTES = 4_000_000

    /** Whole articles kept on the phone. */
    private const val FULL_KEPT = 300

    /** Sites give a phone's browser the article; some give an unknown client a wall instead. */
    private const val PHONE_BROWSER = "Mozilla/5.0 (Linux; Android 16; SM-F971U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    /** Stories kept a topic; the reader shows five and "Show more" the rest. */
    const val PER_TOPIC = 25

    /** Stories a topic whose address and picture are found at refresh, so the stream opens with pictures. */
    private const val RESOLVED_FIRST = 6
    private const val FEED_GAP_MS = 400L

    /** Google addresses looked up in one refresh, at most: found ones are kept, so few are new each hour. */
    private const val RESOLVE_PER_REFRESH = 12

}

/** Brings Soma News up to date on the hour, in the indexer's own process, whenever there is a network. */
class NewsJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "news") {
            NewsStore.refresh(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val HOURLY = 4301

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(HOURLY) != null) return
            js.schedule(JobInfo.Builder(HOURLY, ComponentName(context, NewsJob::class.java)).setPeriodic(3600_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        }
    }
}
