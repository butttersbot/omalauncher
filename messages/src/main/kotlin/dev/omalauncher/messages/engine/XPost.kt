package dev.omalauncher.messages.engine

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * An X post, read as Cove reads one (pond src/server/xPost.ts, 2026-08-14). x.com serves a fetcher a JS shell with no
 * og: tags to scrape, so the Mac's page reader finds nothing; X's own embed widget runs on a public JSON feed, which
 * has the words, the author and the media. Undocumented, so a miss falls back to the plain link card, never breaks.
 * Only a hardcoded host with a checked numeric id is asked, and the only image taken is from X's own image host.
 */
internal object XPost {
    private val hosts = setOf("x.com", "twitter.com", "mobile.twitter.com", "m.twitter.com")
    private val statusPath = Regex("^/(?:[A-Za-z0-9_]{1,20}|i/web)/status(?:es)?/(\\d{1,20})(?:/(?:photo|video)/\\d{1,2})?/?$")
    private val shortLink = Regex("^https?://t\\.co/[A-Za-z0-9]+$")
    private const val MEDIA_HOST = "https://pbs.twimg.com/"
    private const val MAX_ENTITIES = 25

    /** What the post gives a link card. */
    data class Post(val title: String, val image: String?)

    fun statusId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in listOf("https", "http")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removeSuffix(".") ?: return null
        if (host !in hosts) return null
        return statusPath.find(uri.path.orEmpty())?.groupValues?.get(1)
    }

    fun feed(id: String) = "https://cdn.syndication.twimg.com/tweet-result?id=$id&token=a&lang=en"

    /** A feed body's post; null for a deleted or age-gated one (their own __typename) or anything not shaped like a post. */
    fun parse(body: String): Post? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (o.has("__typename") && o.optString("__typename") != "Tweet") return null
        val name = o.optJSONObject("user")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        val title = (words(o) ?: name ?: return null).take(512)
        return Post(title, image(o))
    }

    /** Words as X displays them: within the display range, a link's t.co as its readable address, a media pointer gone. */
    private fun words(o: JSONObject): String? {
        var text = o.optString("text").takeIf { o.has("text") && !o.isNull("text") } ?: return null
        o.optJSONArray("display_text_range")?.takeIf { it.length() == 2 }?.let { range ->
            val from = range.optInt(0, -1)
            val to = range.optInt(1, -1)
            val points = text.codePoints().toArray()
            if (from in 0..to && to <= points.size) text = String(points, from, to - from)
        }
        val entities = o.optJSONObject("entities")
        forEachEntity(entities?.optJSONArray("urls")) { e ->
            val short = e.optString("url")
            val shown = e.optString("display_url")
            if (shortLink.matches(short) && shown.isNotEmpty() && shown.length <= 120) text = text.replace(short, shown)
        }
        forEachEntity(entities?.optJSONArray("media")) { e ->
            val short = e.optString("url")
            if (shortLink.matches(short)) text = text.replace(short, "")
        }
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private inline fun forEachEntity(list: JSONArray?, each: (JSONObject) -> Unit) {
        if (list == null) return
        for (i in 0 until minOf(MAX_ENTITIES, list.length())) list.optJSONObject(i)?.let(each)
    }

    /** The first picture, or a video's poster frame; both are in mediaDetails, photos being the older field. */
    private fun image(o: JSONObject): String? {
        forEachEntity(o.optJSONArray("mediaDetails")) { e -> e.optString("media_url_https").takeIf(::fromX)?.let { return it } }
        forEachEntity(o.optJSONArray("photos")) { e -> e.optString("url").takeIf(::fromX)?.let { return it } }
        return null
    }

    private fun fromX(url: String) = url.startsWith(MEDIA_HOST) && url.length <= 512

    /** What the card shows: the Mac's own metadata, unless the post has what that lacks. */
    fun fill(known: RichLink?, found: RichLink?): RichLink? = when {
        found == null -> known
        known == null || known.state == "unavailable" -> found
        known.imageAttachmentId == null && found.imageAttachmentId != null -> found
        else -> known
    }
}

/**
 * Previews of X posts, fetched by the phone for reading and kept in its cache. Never given to [MacLink.previews] nor
 * to a send: what goes out with a message is still only what the Mac chooses.
 */
internal object XPreviews {
    private const val PREFIX = "x:"
    private const val RETRY_MS = 300_000L
    private const val MAX_BODY = 256 * 1024
    private const val MAX_IMAGE = 4 * 1024 * 1024
    private const val MAX_KEPT = 128
    private const val TIMEOUT_MS = 6_000
    private val io = Executors.newFixedThreadPool(2)
    private val asked = ConcurrentHashMap<String, Long>()
    val found = MutableStateFlow<Map<String, RichLink>>(emptyMap())
    private val onDisk = ConcurrentHashMap<String, Pair<String, Boolean>>()

    /** Posts read in an earlier run, so a card takes its size at once rather than growing when its file is read. */
    fun preload(context: Context) {
        io.execute {
            dir(context).listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                val id = f.name.removeSuffix(".json")
                runCatching { JSONObject(f.readText()) }.getOrNull()?.let { saved ->
                    val image = saved.optBoolean("image")
                    if (!image || File(f.parentFile, "$id.img").length() > 0) onDisk[id] = saved.getString("title") to image
                }
            }
        }
    }

    fun kept(url: String): RichLink? = XPost.statusId(url)?.let { id -> onDisk[id]?.let { (title, image) -> link(url, id, title, image) } }

    fun owns(imageId: String) = imageId.startsWith(PREFIX)

    fun image(context: Context, imageId: String): File? =
        File(dir(context), imageId.removePrefix(PREFIX) + ".img").takeIf { owns(imageId) && it.isFile && it.length() > 0 }

    private fun dir(context: Context) = File(context.cacheDir, "x-posts")

    fun request(context: Context, url: String) {
        val id = XPost.statusId(url) ?: return
        val now = System.currentTimeMillis()
        val last = asked[url]
        if (url in found.value || (last != null && now - last < RETRY_MS)) return
        asked[url] = now
        val app = context.applicationContext
        io.execute { runCatching { load(app, url, id)?.let { publish(url, it) } } }
    }

    @Synchronized private fun publish(url: String, link: RichLink) {
        found.value = found.value.entries.toList().takeLast(MAX_KEPT - 1).associate { it.toPair() } + (url to link)
    }

    private fun link(url: String, id: String, title: String, image: Boolean) =
        RichLink(url, title, "x.com", if (image) PREFIX + id else null)

    private fun load(context: Context, url: String, id: String): RichLink? {
        val folder = dir(context).apply { mkdirs() }
        val kept = File(folder, "$id.json")
        val picture = File(folder, "$id.img")
        if (kept.isFile) runCatching { JSONObject(kept.readText()) }.getOrNull()?.let { saved ->
            val wanted = saved.optBoolean("image")
            if (!wanted || picture.length() > 0) return link(url, id, saved.getString("title"), wanted)
        }
        val post = XPost.parse(String(get(XPost.feed(id), MAX_BODY) ?: return null, Charsets.UTF_8)) ?: return null
        val image = post.image?.let { get(it, MAX_IMAGE) }?.takeIf(::isPicture)
        if (image != null) {
            val part = File(folder, "$id.part")
            part.writeBytes(image)
            part.renameTo(picture)
        }
        // A picture that failed this time is tried again next time: only a post with none is remembered as having none.
        if (image != null || post.image == null) kept.writeText(JSONObject().put("title", post.title).put("image", image != null).toString())
        return link(url, id, post.title, image != null)
    }

    private fun isPicture(bytes: ByteArray): Boolean {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /** The body of a plain 200, no cookies, no redirects, no more than [max] bytes; null otherwise. */
    private fun get(address: String, max: Int): ByteArray? {
        val http = URL(address).openConnection() as HttpURLConnection
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = TIMEOUT_MS
            http.readTimeout = TIMEOUT_MS
            if (http.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            http.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > max) return null
                }
            }
            return out.toByteArray()
        } finally {
            http.disconnect()
        }
    }
}
