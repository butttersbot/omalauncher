package dev.omalauncher.search

import java.text.Normalizer

/**
 * How Soma's search matches a query against a name: Raycast's root-search matcher, rebuilt from its documented
 * behaviour (typecraft-dev/oma-cast, reference/02-backend.md §4.2-4.4), not from its code (Boss, 2026-10-20: "the
 * fastest search we can make", and accurate). Plain Kotlin, so it is tested without Android.
 *
 * A match scores each query letter where it lands in the name: 4 for the name's first letter, 3 for the start of a
 * word, 2 elsewhere, 1 for a separator meeting a separator, and 1 off for every jump. So "ff" finds Firefox, "gsp"
 * Google Sheets Pro, and a name that only happens to hold the letters scattered about falls under the bar.
 */
object RootMatch {
    /** What a name made of a query: the very name, nothing, or a score with the separators the query skipped. */
    sealed interface Match {
        data object Exact : Match

        data object Unmatched : Match

        data class Scored(val score: Int, val skipped: Int) : Match
    }

    /** How strict a match must be to show; Raycast's own default is [Sensitivity.MEDIUM]. */
    enum class Sensitivity { LOW, MEDIUM, HIGH }

    private const val SEPARATORS = "\t\n -./()[]"

    fun isSeparator(c: Char) = c in SEPARATORS

    /** [text] lowercased and stripped of accents, as the matcher reads it: "Café" is "cafe". */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) out.append(c)
        return out.toString().lowercase()
    }

    /** Where each word of [text] starts, as the matcher counts them: after a separator, or a capital after a small letter or digit. */
    fun wordStarts(text: String): BooleanArray {
        val starts = BooleanArray(text.length)
        for (i in text.indices) {
            starts[i] = i == 0 || isSeparator(text[i - 1]) || (text[i].isUpperCase() && (text[i - 1].isLowerCase() || text[i - 1].isDigit()))
        }
        return starts
    }

    /** Matches [query] (already folded) against [name] as written. */
    fun match(
        name: String,
        query: String,
        literalDots: Boolean = false,
    ): Match {
        val target = fold(name)
        if (target == query) return Match.Exact
        val starts = wordStarts(name.let { if (it.length == target.length) it else target })
        return match(target, starts, query, literalDots)
    }

    private fun sep(
        c: Char,
        literalDots: Boolean,
    ) = isSeparator(c) && !(literalDots && c == '.')

    fun match(
        target: String,
        starts: BooleanArray,
        query: String,
        literalDots: Boolean,
    ): Match {
        if (target == query) return Match.Exact
        if (query.isEmpty()) return Match.Unmatched
        val seps = query.count { sep(it, literalDots) }
        val letters = query.length - seps
        val n = target.length
        if (letters > n) return Match.Unmatched
        // Quick check: every letter of the query appears in the name, in order.
        if (letters > 2) {
            var j = 0
            for (c in query) {
                if (sep(c, literalDots)) continue
                while (j < n && target[j] != c) j++
                if (j == n) return Match.Unmatched
                j++
            }
        }
        val none = Int.MIN_VALUE / 4
        var prev = IntArray(n) { none }
        var cur = IntArray(n)
        // The first column the previous row matched in, and how many rows have matched.
        var first = -1
        var rows = 0
        var skipped = 0
        for ((qi, qc) in query.withIndex()) {
            java.util.Arrays.fill(cur, none)
            val qSep = sep(qc, literalDots)
            val end = minOf(n, n - (letters - 1 - rows).coerceAtLeast(0))
            var firstHere = -1
            for (e in (first + 1) until end) {
                val tc = target[e]
                val tSep = isSeparator(tc)
                val both = qSep && tSep
                if (!(tc == qc || both)) continue
                val score =
                    when {
                        both -> 1
                        rows == 0 && e == 0 -> 4
                        starts.getOrElse(e) { false } && !tSep -> 3
                        else -> 2
                    }
                val cell =
                    if (rows == 0) {
                        score
                    } else {
                        var best = none
                        for (r in maxOf(first, 0) until e) {
                            val p = prev[r]
                            if (p == none) continue
                            val v = p + score - (if (r == e - 1) 0 else 1)
                            if (v > best) best = v
                        }
                        best
                    }
                if (cell == none) continue
                cur[e] = cell
                if (firstHere < 0) firstHere = e
            }
            if (firstHere >= 0) {
                first = firstHere
                rows++
                val t = prev
                prev = cur
                cur = t
            } else if (qSep) {
                // A separator the name doesn't have there is passed over.
                skipped++
            } else {
                return Match.Unmatched
            }
            if (qi == query.lastIndex && rows == 0) return Match.Unmatched
        }
        var best = none
        for (v in prev) if (v > best) best = v
        return if (best == none) Match.Unmatched else Match.Scored(best, skipped)
    }

    /** Whether [match] is good enough to show for a query [length] long. */
    fun accepts(
        match: Match,
        length: Int,
        sensitivity: Sensitivity = Sensitivity.MEDIUM,
    ): Boolean =
        when (match) {
            Match.Exact -> true
            Match.Unmatched -> false
            is Match.Scored ->
                when (sensitivity) {
                    Sensitivity.LOW -> true
                    Sensitivity.MEDIUM -> match.score >= 1.5 * (length - match.skipped - 2) + 4
                    Sensitivity.HIGH -> match.score > 2 * (length - match.skipped)
                }
        }

    /** The match as a number to compare: the very name highest, nothing lowest. */
    fun value(match: Match): Int =
        when (match) {
            Match.Exact -> Int.MAX_VALUE
            Match.Unmatched -> Int.MIN_VALUE
            is Match.Scored -> match.score
        }

    /** Whether [query] stands in [name] as a whole word: separators, ends or a capital on both sides. */
    fun wholeWord(
        name: String,
        query: String,
    ): Boolean {
        val target = fold(name)
        val starts = wordStarts(name.let { if (it.length == target.length) it else target })
        var at = target.indexOf(query)
        while (at >= 0) {
            val end = at + query.length
            val before = at == 0 || isSeparator(target[at - 1]) || starts[at]
            val after = end == target.length || isSeparator(target[end]) || starts.getOrElse(end) { false }
            if (before && after) return true
            at = target.indexOf(query, at + 1)
        }
        return false
    }
}
