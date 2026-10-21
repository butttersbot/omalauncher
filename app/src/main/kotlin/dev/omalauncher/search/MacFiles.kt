package dev.omalauncher.search

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Files on the Mac Pro, by its own Spotlight (Boss's banked search list, 2026-10-20: "Mac Pro Spotlight results +
 * bring to the phone"). The Mac's embed-server answers `/spotlight` and `/file` for his own devices only
 * (tools/embed-mac); a file he picks is brought to the phone's Download/From Mac Pro and opened from there.
 */
object MacFiles {
    /** One file Spotlight found on the Mac: where it is there, and what it is. */
    data class Hit(
        val path: String,
        val name: String,
        /** Its extension, or "folder". */
        val kind: String,
        val modified: Long,
        val size: Long,
    )

    /** Files in the Mac's home with every word of [query] in their name or text, names first; empty if it is away. */
    fun search(
        base: String,
        query: String,
        n: Int = 8,
    ): List<Hit> =
        runCatching {
            val c = URL("$base/spotlight?q=${URLEncoder.encode(query, "UTF-8")}&n=$n").openConnection() as HttpURLConnection
            c.connectTimeout = 800
            c.readTimeout = 2_500
            if (c.responseCode != 200) return emptyList()
            val items = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("items")
            List(items.length()) { i ->
                val it = items.getJSONObject(i)
                Hit(it.getString("path"), it.getString("name"), it.optString("kind"), it.optLong("modified"), it.optLong("size"))
            }.filter { it.kind != "folder" }
        }.getOrDefault(emptyList())

    /** Where a file brought from the Mac is kept on the phone. */
    fun folder(storageRoot: String) = File(storageRoot, "Download/From Mac Pro")

    /**
     * Brings [hit] to [dest]'s folder, as [dest]: written beside it first and moved in whole, so a half-brought file
     * is never opened. A copy already there of the same size and no older is used as it is. False if it failed.
     */
    fun bring(
        base: String,
        hit: Hit,
        dest: File,
    ): Boolean {
        if (dest.length() == hit.size && dest.lastModified() >= hit.modified) return true
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, ".${dest.name}.part")
        return runCatching {
            val c = URL("$base/file?path=${URLEncoder.encode(hit.path, "UTF-8")}").openConnection() as HttpURLConnection
            c.connectTimeout = 1_500
            c.readTimeout = 30_000
            if (c.responseCode != 200) return false
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 256 * 1024) } }
            part.renameTo(dest)
        }.getOrDefault(false).also { if (!it) part.delete() }
    }

    /** "~/Documents/Taxes" for a Mac path under its home; the folder of anything else. */
    fun where(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        val home = Regex("^/Users/[^/]+").find(dir)?.value ?: return dir
        return "~" + dir.removePrefix(home)
    }
}
