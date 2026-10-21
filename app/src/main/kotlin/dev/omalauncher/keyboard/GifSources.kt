package dev.omalauncher.keyboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * One GIF the keyboard can show and place: from KLIPY, or one saved on the phone. [preview] is what the grid plays
 * (a small copy: a URL, or the phone's own content URI), [full] what goes into the app's message box, [link] what is
 * typed instead when the app takes no pictures from a keyboard.
 */
data class GifItem(
    val id: String,
    val title: String,
    val preview: String,
    val full: String,
    /** Its shape, width over height; 1 until it is known (a saved one, before it is first read). */
    val ratio: Float,
    val local: Boolean,
    val link: String = full,
) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("title", title).put("preview", preview).put("full", full).put("ratio", ratio.toDouble()).put("local", local).put("link", link)

    companion object {
        fun fromJson(o: JSONObject) =
            GifItem(o.getString("id"), o.optString("title"), o.getString("preview"), o.getString("full"), o.optDouble("ratio", 1.0).toFloat(), o.optBoolean("local"), o.optString("link", o.getString("full")))
    }
}

/**
 * KLIPY, the GIF library the keyboard searches (Boss, 2026-10-21: Tenor's public API closed on 2026-06-30; KLIPY,
 * made by former Tenor people, "is fine"). Its Tenor-shaped API, `/v2/search` and `/v2/featured`. The key is his,
 * in the guest's ~/.config/omalauncher/klipy.txt, never in the app.
 */
object Klipy {
    const val BASE = "https://api.klipy.com/v2"
    const val PAGE = 24

    /** A page of GIFs and where the next one starts (null at the end). */
    class Page(
        val items: List<GifItem>,
        val next: String?,
    )

    fun key(filesDir: File): String? =
        runCatching { File(filesDir, "arch/root/.config/omalauncher/klipy.txt").readText().trim() }.getOrNull()?.takeIf { it.length > 10 }

    /** What the API answered, as GIFs: the small looping copy to show, the full one to place. */
    fun parse(json: String): Page {
        val o = JSONObject(json)
        val results = o.optJSONArray("results") ?: JSONArray()
        val items =
            (0 until results.length()).mapNotNull { i ->
                val r = results.getJSONObject(i)
                val f = r.optJSONObject("media_formats") ?: return@mapNotNull null
                val small = f.optJSONObject("tinygif") ?: f.optJSONObject("nanogif") ?: f.optJSONObject("gif") ?: return@mapNotNull null
                val big = f.optJSONObject("gif") ?: f.optJSONObject("mediumgif") ?: small
                val dims = small.optJSONArray("dims")
                val ratio = if (dims != null && dims.length() == 2 && dims.getDouble(1) > 0) (dims.getDouble(0) / dims.getDouble(1)).toFloat() else 1f
                GifItem("klipy:" + r.getString("id"), r.optString("title"), small.getString("url"), big.getString("url"), ratio.coerceIn(0.4f, 2.5f), local = false)
            }
        return Page(items, o.optString("next").takeIf { it.isNotEmpty() && it != "0" })
    }

    /** GIFs for [query], or what's trending with none; [pos] from the last page's next. Null if KLIPY didn't answer. */
    fun fetch(
        key: String,
        query: String?,
        pos: String?,
        customer: String,
    ): Page? =
        runCatching {
            val q = query?.trim().orEmpty()
            val url =
                buildString {
                    append(BASE).append(if (q.isEmpty()) "/featured" else "/search")
                    append("?key=").append(URLEncoder.encode(key, "UTF-8"))
                    if (q.isNotEmpty()) append("&q=").append(URLEncoder.encode(q, "UTF-8"))
                    append("&limit=").append(PAGE).append("&customer_id=").append(URLEncoder.encode(customer, "UTF-8"))
                    if (pos != null) append("&pos=").append(URLEncoder.encode(pos, "UTF-8"))
                }
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4_000
            c.readTimeout = 6_000
            if (c.responseCode != 200) return null
            parse(c.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()
}

/**
 * What the keyboard keeps of GIFs: the KLIPY ones he hearted (Saved, with the GIFs saved on the phone) and the ones
 * he placed lately (Recent), newest first, in the app's own files.
 */
class GifStore(
    private val file: File?,
) {
    private val hearted = ArrayList<GifItem>()
    private val recent = ArrayList<GifItem>()

    init {
        runCatching {
            val o = JSONObject(file!!.readText())
            o.optJSONArray("hearted")?.let { a -> for (i in 0 until a.length()) hearted += GifItem.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("recent")?.let { a -> for (i in 0 until a.length()) recent += GifItem.fromJson(a.getJSONObject(i)) }
        }
    }

    @Synchronized fun hearted(): List<GifItem> = hearted.toList()

    @Synchronized fun recent(): List<GifItem> = recent.toList()

    @Synchronized fun isHearted(id: String) = hearted.any { it.id == id }

    /** Hearts [item], or takes the heart back; true if it is hearted now. */
    @Synchronized fun toggleHeart(item: GifItem): Boolean {
        val had = hearted.removeAll { it.id == item.id }
        if (!had) hearted.add(0, item)
        save()
        return !had
    }

    @Synchronized fun placed(item: GifItem) {
        recent.removeAll { it.id == item.id }
        recent.add(0, item)
        while (recent.size > RECENT_KEPT) recent.removeAt(recent.lastIndex)
        save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val o = JSONObject().put("hearted", JSONArray(hearted.map { it.toJson() })).put("recent", JSONArray(recent.map { it.toJson() }))
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    companion object {
        const val RECENT_KEPT = 30

        @Volatile private var shared: GifStore? = null

        fun of(context: android.content.Context): GifStore =
            shared ?: synchronized(this) { shared ?: GifStore(File(context.filesDir, "gifs/store.json")).also { shared = it } }
    }
}

/** Where tiles go in a grid of columns: each next one under the shortest column, so they stay even down the page. */
object Masonry {
    class Cell(
        val index: Int,
        val column: Int,
        val top: Float,
        val height: Float,
    )

    /** [ratios] width over height, [columns] across, each [width] wide, [gap] between tiles; the cells and the full height. */
    fun lay(
        ratios: List<Float>,
        columns: Int,
        width: Float,
        gap: Float,
    ): Pair<List<Cell>, Float> {
        val tops = FloatArray(columns)
        val cells =
            ratios.mapIndexed { i, r ->
                val c = tops.indices.minByOrNull { tops[it] } ?: 0
                val h = width / r.coerceIn(0.4f, 2.5f)
                Cell(i, c, tops[c], h).also { tops[c] += h + gap }
            }
        return cells to (tops.maxOrNull() ?: 0f)
    }
}
