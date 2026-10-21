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
     * them. Null when there is nothing to look for. Written bare (`name:lease*`): FTS4 finds nothing for a quoted word
     * with a star after it (`name:"lease"*`, as 105 to 107 asked, 2026-10-21). The words are letters and digits only,
     * and folded small, so none can be read as an operator (those are capitals: OR, AND, NOT, NEAR).
     */
    fun match(
        query: String,
        column: String,
    ): String? {
        val w = words(query)
        if (w.isEmpty()) return null
        return w.joinToString(" ") { "$column:$it*" }
    }

    /**
     * How many letters apart [a] and [b] are: a letter added, dropped, changed, or two side by side swapped each count
     * one (Damerau's distance, as typos happen). Stops early past [most], returning more than it.
     */
    fun distance(
        a: String,
        b: String,
        most: Int = Int.MAX_VALUE,
    ): Int {
        if (kotlin.math.abs(a.length - b.length) > most) return most + 1
        var before = IntArray(b.length + 1)
        var row = IntArray(b.length + 1) { it }
        var next = IntArray(b.length + 1)
        for (i in 1..a.length) {
            next[0] = i
            var low = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var d = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, before[j - 2] + 1)
                next[j] = d
                low = minOf(low, d)
            }
            if (low > most) return most + 1
            val t = before
            before = row
            row = next
            next = t
        }
        return row[b.length]
    }

    /** How many typos a word of [length] letters may have and still be read as another: none in a short one. */
    fun typos(length: Int) =
        when {
            length < 4 -> 0
            length < 7 -> 1
            else -> 2
        }

    /**
     * The word of the index [word] was most likely meant to be, from [terms] (each with how many files have it), or
     * null if none is near enough. The fewest typos win, then the commoner word. The [last] word may still be being
     * typed, so it is also compared with the beginnings of longer words.
     */
    fun corrected(
        word: String,
        terms: List<Pair<String, Int>>,
        last: Boolean,
    ): String? {
        val most = typos(word.length)
        if (most == 0) return null
        var best: String? = null
        var bestD = most + 1
        var bestN = -1
        for ((t, n) in terms) {
            if (t == word) return null
            var d = distance(word, t, most)
            if (last && t.length > word.length) d = minOf(d, distance(word, t.substring(0, word.length), most))
            if (d < bestD || (d == bestD && n > bestN)) {
                best = t
                bestD = d
                bestN = n
            }
        }
        return best.takeIf { bestD <= most }
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

    /**
     * [hits] in a new order once the Mac has said how near each is in meaning to the query ([nearness], one each, in
     * the model's narrow band): a blend of where each stood ([lexical] of the weight) and how near it is, both scaled
     * to 0..1 among these hits. What stood first by an exact name stays first.
     */
    fun blend(
        hits: List<Hit>,
        nearness: FloatArray,
        lexical: Double,
        query: String,
    ): List<Hit> {
        if (hits.size < 2 || nearness.size != hits.size) return hits
        val lo = nearness.min()
        val hi = nearness.max()
        val q = RootMatch.fold(query.trim())
        val pinned = hits.first().let { RootMatch.fold(it.name.substringBeforeLast('.', it.name)) == q || RootMatch.fold(it.name) == q }
        return hits.indices
            .map { i ->
                val stood = 1.0 - i.toDouble() / hits.size
                val near = if (hi - lo < 1e-4f) 0.5 else ((nearness[i] - lo) / (hi - lo)).toDouble()
                val score = if (pinned && i == 0) Double.MAX_VALUE else lexical * stood + (1 - lexical) * near
                hits[i] to score
            }.sortedByDescending { it.second }
            .map { it.first }
    }

    /** A hit's words as the Mac compares them with the query: its name's words and folder, and the text around a match. */
    fun meaning(
        hit: Hit,
        folder: String,
    ): String =
        buildString {
            append(nameWords(hit.name.substringBeforeLast('.', hit.name)))
            if (folder.isNotEmpty()) append(" · ").append(folder)
            hit.snippet?.let { append(": ").append(it.filter { c -> c != MARK_OPEN && c != MARK_CLOSE }) }
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
