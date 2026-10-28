package dev.omalauncher.notes.engine

import org.json.JSONObject

/** A note as tailshare-serve lists it (GET /v1/notes). */
data class Remote(
    val id: String,
    val title: String,
    val kind: String,
    val ext: String,
    val modified: Long,
    val sha: String,
    /** A project's BRIEF.md or CURRENT-STATE.md, shown from ClaudeSync on the Mac: read here, edited there. */
    val readonly: Boolean = false,
)

/**
 * What goes over the wire to /v1/notes, and the names notes get. Pure, for the JVM tests.
 *
 * A note's id is its path under ~/Notes on the Mac Pro ("Groceries.md", "Agents/claude/report.html"); agents write
 * theirs under Agents/.
 */
object Wire {
    const val AGENT = "agent"
    const val MINE = "note"

    /** The list's notes, or null when it isn't one. */
    fun list(json: String): Pair<String, List<Remote>>? =
        runCatching {
            val o = JSONObject(json)
            val a = o.getJSONArray("notes")
            o.optString("rev") to
                (0 until a.length()).map { i ->
                    val n = a.getJSONObject(i)
                    val id = n.getString("id")
                    Remote(
                        id = id,
                        title = n.optString("title").ifEmpty { fileTitle(id) },
                        kind = n.optString("kind").ifEmpty { if (id.startsWith("Agents/")) AGENT else MINE },
                        ext = n.optString("ext").ifEmpty { id.substringAfterLast('.', "md") },
                        modified = n.optLong("modified"),
                        sha = n.getString("sha"),
                        readonly = n.optBoolean("readonly"),
                    )
                }.filter { safe(it.id) }
        }.getOrNull()

    /** The 409's current text and sha, for the merge. */
    fun conflict(json: String): Pair<String, String>? =
        runCatching { JSONObject(json).let { it.getString("sha") to it.getString("text") } }.getOrNull()

    /** A save's new sha (200 {sha, rev}). */
    fun saved(json: String): String? = runCatching { JSONObject(json).getString("sha") }.getOrNull()

    /** A note's title: its first line with words in it, without Markdown's heading marks; else its file name. */
    fun title(
        text: String,
        id: String = "",
    ): String {
        if (id.endsWith(".html")) {
            Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.get(1)?.let(::plain)?.takeIf { it.isNotEmpty() }?.let { return it }
            Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.get(1)?.let(::plain)?.takeIf { it.isNotEmpty() }?.let { return it }
            return fileTitle(id)
        }
        return text.lineSequence().map { it.trim().trimStart('#').trim().removePrefix("- [ ] ").removePrefix("- [x] ") }.firstOrNull { it.any(Char::isLetterOrDigit) }?.take(MAX_TITLE)
            ?: fileTitle(id)
    }

    /** The rest of a note after its title, for a card. */
    fun preview(text: String): String =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.drop(1).take(6)
            .joinToString(" ") { it.trimStart('#', '>', ' ').replace(Regex("^[-*] (\\[[ xX]] )?"), "") }
            .replace(Regex("[*_`]"), "")
            .take(220)

    /** An HTML title's words: no tags, the common entities read, spaces collapsed. */
    private fun plain(html: String): String =
        html.replace(Regex("<[^>]+>"), "")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)

    fun fileTitle(id: String): String = id.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "Untitled" }

    /**
     * The file a new note of Boss's is saved as on the Mac: its title, made safe for a file name, unique among
     * [taken] ("Groceries.md", then "Groceries 2.md").
     */
    fun fileFor(
        title: String,
        taken: Set<String>,
    ): String {
        val base =
            title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}#]"), " ")
                .replace(Regex("\\s+"), " ").trim().trimStart('.').take(MAX_FILE).trim()
                .ifEmpty { "Note" }
        var name = "$base.md"
        var n = 2
        val lower = taken.map { it.lowercase() }.toSet()
        while (name.lowercase() in lower) name = "$base ${n++}.md"
        return name
    }

    /** An id the Mac would take: a path inside ~/Notes, not into .git or .trash. */
    fun safe(id: String): Boolean {
        if (id.isEmpty() || id.startsWith("/") || id.contains('\\') || id.contains('\u0000')) return false
        val parts = id.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return parts.first() != ".git" && parts.first() != ".trash"
    }

    /** An id in a URL path: each piece escaped, slashes too (the server's spec: "Agents%2Freport.md"). */
    fun path(id: String): String = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private const val MAX_TITLE = 120
    private const val MAX_FILE = 60
}
