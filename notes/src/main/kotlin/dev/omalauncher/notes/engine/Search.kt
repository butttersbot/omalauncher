package dev.omalauncher.notes.engine

/**
 * Notes found by words, for Soma's own search on home (Boss, 2026-10-24: "couple that with our spotlight search and
 * that gives me an easy way to search docs"): every word in the title or the text, titles first, then newest. Pure,
 * for the JVM tests.
 */
object Search {
    data class Hit(
        val id: String,
        val title: String,
        /** The agent project it's filed under, or null for one of Boss's own. */
        val project: String?,
        val snippet: String,
        val modified: Long,
    )

    fun find(
        notes: List<Note>,
        text: (String) -> String,
        query: String,
        titlesOnly: Boolean = false,
        limit: Int = 20,
    ): List<Hit> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return notes.asSequence()
            .filter { !it.gone }
            .mapNotNull { n ->
                val title = n.title.lowercase()
                val inTitle = words.count { it in title }
                if (titlesOnly && inTitle < words.size) return@mapNotNull null
                val body = if (inTitle == words.size) "" else text(n.id)
                val lower = body.lowercase()
                if (!words.all { it in title || it in lower }) return@mapNotNull null
                val snip = if (body.isEmpty()) n.preview else snippet(body, words.first { it !in title })
                Triple(n, inTitle, snip)
            }
            .sortedWith(compareByDescending<Triple<Note, Int, String>> { it.second }.thenByDescending { it.first.modified })
            .take(limit)
            .map { (n, _, snip) -> Hit(n.id, n.title, n.id.split('/').takeIf { n.agent && it.size > 2 }?.get(1), snip, n.modified) }
            .toList()
    }

    /** About a line of [text] around [word]'s first place, on one line. */
    fun snippet(
        text: String,
        word: String,
    ): String {
        val flat = text.replace(Regex("[#*_`>|]"), " ").replace(Regex("\\s+"), " ").trim()
        val at = flat.lowercase().indexOf(word)
        if (at < 0) return flat.take(SNIPPET)
        val from = (at - SNIPPET / 3).coerceAtLeast(0).let { s -> if (s == 0) 0 else flat.indexOf(' ', s).let { if (it in s..at) it + 1 else s } }
        val cut = flat.substring(from).take(SNIPPET)
        return (if (from > 0) "…" else "") + cut + if (from + cut.length < flat.length) "…" else ""
    }

    private const val SNIPPET = 120
}
