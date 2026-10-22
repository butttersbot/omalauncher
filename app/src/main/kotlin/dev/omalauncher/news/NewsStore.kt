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
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Soma News (Boss, 2026-10-21: "the delightful personal news reader", mock-up approved): what came in, read from
 * FreshRSS on the Mac Pro over the tailnet by its Google Reader API, kept as one JSON file in the guest's
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
    ) {
        fun toJson(): JSONObject =
            JSONObject().put("id", id).put("title", title).put("source", source).put("link", link).put("published", published).put("image", image ?: JSONObject.NULL).put("html", html)
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

    @Volatile private var token: String? = null

    private fun login(c: Config): String? {
        token?.let { return it }
        return runCatching {
            val conn = URL("${c.url}/api/greader.php/accounts/ClientLogin").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write("Email=${URLEncoder.encode(c.user, "UTF-8")}&Passwd=${URLEncoder.encode(c.password, "UTF-8")}".toByteArray()) }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }.lineSequence().firstOrNull { it.startsWith("Auth=") }?.removePrefix("Auth=")?.trim()
        }.getOrNull()?.also { token = it }
    }

    /**
     * Fetches the newest [count] stories and writes the file; the number of stories, or null with the reason it
     * couldn't (no config, the Mac away, a refused password).
     */
    fun refresh(
        context: Context,
        count: Int = 80,
    ): Result<Int> {
        val c = config(context) ?: return Result.failure(IllegalStateException("No FreshRSS settings in ~/.config/omalauncher/freshrss.txt"))
        val auth = login(c) ?: return Result.failure(IllegalStateException("FreshRSS on the Mac Pro didn't answer, or refused the password"))
        val json =
            runCatching {
                val conn = URL("${c.url}/api/greader.php/reader/api/0/stream/contents/user/-/state/com.google/reading-list?n=$count&output=json").openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Authorization", "GoogleLogin auth=$auth")
                if (conn.responseCode == 401) {
                    token = null
                    return Result.failure(IllegalStateException("FreshRSS refused the login; trying again next time"))
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse { return Result.failure(it) }
        val stories = parse(json)
        // A story whose feed has no picture keeps the one an earlier refresh found for it.
        val known = runCatching { JSONObject(cached(context)).getJSONArray("stories") }.getOrNull()
        val images = HashMap<String, String>()
        if (known != null) for (i in 0 until known.length()) known.getJSONObject(i).let { o -> if (!o.isNull("image")) images[o.getString("id")] = o.getString("image") }
        val filled = stories.map { s -> if (s.image == null && images[s.id] != null) s.copy(image = images[s.id]) else s }
        // The rest get the page's own picture (its og:image), a few at a time, so the stream is pictures, not text.
        var looked = 0
        val withPictures =
            filled.map { s ->
                if (s.image != null || looked >= PAGE_LOOKUPS || s.link.isEmpty()) {
                    s
                } else {
                    looked++
                    s.copy(image = pageImage(s.link))
                }
            }
        write(context, withPictures)
        return Result.success(withPictures.size)
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

    fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")

    /** Stories the home widget's file keeps: the large widget shows a lead and three more. */
    private const val WIDGET_STORIES = 6

    /** Pages read for a picture in one refresh, at most. */
    private const val PAGE_LOOKUPS = 25
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
