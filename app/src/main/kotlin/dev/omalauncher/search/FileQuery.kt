package dev.omalauncher.search

import kotlin.math.ln

/**
 * The plain parts of file search (Boss, 2026-10-20: step 2, files by name and by what is in them): how a file's name
 * is cut into words for the index, how a query becomes an index query, and how the files found are ordered. Plain
 * Kotlin, so it is tested without Android; [FileIndex] keeps the index itself.
 */
object FileQuery {
    /**
     * The words of a file name as the index keeps them: "Lease_agreement-2026.pdf" is "lease agreement 2026 pdf",
     * "myInvoiceOct" is "my invoice oct", with the whole name too so a run of letters across words still begins one.
     */
    fun nameWords(name: String): String {
        val out = StringBuilder()
        val word = StringBuilder()

        fun end() {
            if (word.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(RootMatch.fold(word.toString()))
                word.clear()
            }
        }
        for ((i, c) in name.withIndex()) {
            when {
                !c.isLetterOrDigit() -> end()
                // A capital after a small letter, or a digit after a letter (or the other way), starts a word.
                i > 0 && c.isUpperCase() && name[i - 1].isLowerCase() -> {
                    end()
                    word.append(c)
                }
                i > 0 && (c.isDigit() != name[i - 1].isDigit()) && name[i - 1].isLetterOrDigit() -> {
                    end()
                    word.append(c)
                }
                else -> word.append(c)
            }
        }
        end()
        return out.toString()
    }

    /** The query's words, folded, as the index sees them: letters and digits only. */
    fun words(query: String): List<String> = RootMatch.fold(query).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * The index query for the words of [query] in [column] ("name" or "body"): each word begins a word there, all of
     * them. Null when there is nothing to look for. Quotes keep any word from being read as an operator.
     */
    fun match(
        query: String,
        column: String,
    ): String? {
        val w = words(query)
        if (w.isEmpty()) return null
        return w.joinToString(" ") { "$column:\"${it.replace("\"", "")}\"*" }
    }

    /** One file the index found: what it is, and for a match inside it, the words around it. */
    data class Hit(
        val path: String,
        val name: String,
        /** Last changed, ms. */
        val modified: Long,
        val size: Long,
        /** The words around the match, the matched ones in [MARK_OPEN] [MARK_CLOSE]; null for a name match. */
        val snippet: String? = null,
    )

    const val MARK_OPEN = '\u0001'
    const val MARK_CLOSE = '\u0002'

    /**
     * Files whose name matched, best first: as an app's name would rank (an exact name, then one the query begins,
     * then the match score), then the more recently changed, then the shorter path. [opened] is how much each was
     * opened from search (frecency), so a file he keeps coming back to rises.
     */
    fun rankNames(
        hits: List<Hit>,
        query: String,
        now: Long,
        opened: (String) -> Double = { 1.0 },
    ): List<Hit> {
        val q = RootMatch.fold(query.trim())
        if (q.isEmpty()) return emptyList()
        return hits
            .map { h ->
                val stem = h.name.substringBeforeLast('.', h.name)
                val m = maxOf(RootMatch.value(RootMatch.match(h.name, q)), RootMatch.value(RootMatch.match(stem, q)))
                val exact = RootMatch.fold(stem) == q || RootMatch.fold(h.name) == q
                val prefix = RootMatch.fold(h.name).startsWith(q)
                val score = (if (exact) 1_000_000.0 else 0.0) + (if (prefix) 10_000.0 else 0.0) + m.coerceAtLeast(0) * 100.0 +
                    recency(h.modified, now) * 20 + ln(opened(h.path)) * 30
                h to score
            }.sortedWith(compareByDescending<Pair<Hit, Double>> { it.second }.thenBy { it.first.path.length })
            .map { it.first }
    }

    /** 1 for a file changed today, falling to 0 over about a year. */
    fun recency(
        modified: Long,
        now: Long,
    ): Double {
        val days = ((now - modified) / 86_400_000.0).coerceAtLeast(0.0)
        return 1.0 / (1.0 + days / 30.0)
    }

    /** Where a file is, for its row: "Download", "Documents/Taxes", "Linux · ~/notes". */
    fun folder(
        path: String,
        storageRoot: String,
        linuxHome: String,
    ): String {
        val dir = path.substringBeforeLast('/', "")
        return when {
            dir.startsWith(linuxHome) -> "Linux · ~" + dir.removePrefix(linuxHome)
            dir == storageRoot -> "Phone"
            dir.startsWith("$storageRoot/") -> dir.removePrefix("$storageRoot/")
            else -> dir
        }
    }

    /** The extensions whose text the index reads as it is. */
    val TEXT =
        setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "log", "xml", "html", "htm", "yaml", "yml", "toml", "ini", "conf",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "rs", "go", "c", "h", "cpp", "hpp", "swift", "rb", "sh", "zsh",
            "fish", "lua", "sql", "css", "scss", "tex", "org", "rst", "owl", "srt", "vtt",
        )

    /** Office files: zip archives of XML, read for their text. */
    val OFFICE = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")

    /** Folders never indexed: hidden ones, caches, and build or package trees. (Android/data and Android/obb, other apps' own, are left out by the walk.) */
    fun skipped(name: String) =
        name.startsWith('.') || name in setOf("node_modules", "cache", "Cache", "build", "target", "__pycache__", "venv")

    /** Strips XML tags and entities from an office file's part, keeping its words apart. */
    fun xmlText(xml: String): String =
        xml
            .replace(Regex("<(w:p|a:p|text:p|row|si)[ >/]"), "\n$0")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
}
