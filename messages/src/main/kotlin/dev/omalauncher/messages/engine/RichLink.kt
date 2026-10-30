package dev.omalauncher.messages.engine

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Metadata is data, never executable HTML or an unauthenticated remote image URL. */
data class RichLink(
    val url: String,
    val title: String? = null,
    val site: String? = null,
    val imageAttachmentId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val state: String = "ready",
) {
    fun json(): JSONObject = JSONObject().put("url", url).put("title", title).put("site", site)
        .put("imageAttachmentId", imageAttachmentId).put("width", width).put("height", height).put("state", state)

    companion object {
        fun read(o: JSONObject): RichLink? {
            val url = o.optString("url")
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in listOf("https", "http") || uri.host.isNullOrEmpty() || uri.userInfo != null) return null
            fun text(key: String, max: Int) = o.optString(key).takeIf { !o.isNull(key) && it.isNotBlank() }?.take(max)
            return RichLink(url, text("title", 512), text("site", 128), text("imageAttachmentId", 512),
                o.optInt("width").coerceIn(0, 8192), o.optInt("height").coerceIn(0, 8192), text("state", 32) ?: "ready")
        }
        fun read(list: JSONArray?): List<RichLink> = if (list == null) emptyList() else
            (0 until minOf(10, list.length())).mapNotNull { list.optJSONObject(it)?.let(::read) }
    }
}
