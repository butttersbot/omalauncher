package dev.omalauncher.search

import dev.omalauncher.search.RootMatch.Match
import java.text.Collator
import kotlin.math.exp
import kotlin.math.ln

/** What a result is, and how it ranks against others that match as well: Raycast's type order. */
enum class Kind(val priority: Int) {
    APP(5),
    COMMAND(4),
    LINK(3),
    SETTING(1),
    FILE(1),
    CONTACT(0),
}

/** One thing the search can find. [id] is stable across launches: what is remembered of it hangs on it. */
data class Candidate(
    val id: String,
    val title: String,
    val kind: Kind,
    val subtitle: String? = null,
    val keywords: List<String> = emptyList(),
    val alternateTitles: List<String> = emptyList(),
)

/**
 * What search remembers of Boss's use, as Raycast does: how often and how lately each thing was opened (frecency:
 * every opening adds 100, halving every 10 days), the searches that opened it (so "ff" keeps meaning Firefox once
 * he picked it, for 17 days), aliases, favorites in their order, and what he hid. Plain text, one fact a line.
 */
class SearchMemory {
    private class Opened(var anchor: Double, var at: Long)

    private val opened = HashMap<String, Opened>()
    private val terms = HashMap<String, MutableList<Pair<String, Long>>>()
    private val aliases = HashMap<String, String>()
    private val favorites = ArrayList<String>()
    private val hidden = HashSet<String>()

    /** How much [id] is used now ([now] in ms): 1 for never, more the more and the later. */
    fun frecency(
        id: String,
        now: Long,
    ): Double = opened[id]?.let { score(it.anchor, now / 1000.0) } ?: 1.0

    /** [id] was opened at [now] (ms) after the search [query] (empty for none). */
    fun visit(
        id: String,
        query: String,
        now: Long,
    ) {
        val t = now / 1000.0
        val old = opened[id]
        val s = if (old == null) 1.0 else score(maxOf(old.anchor, t), t)
        opened[id] = Opened(t + 86400 * ln(minOf(s + 100, Double.MAX_VALUE)) / LAMBDA, now)
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val list = terms.getOrPut(id) { ArrayList() }
            list.removeAll { it.first == q }
            list.add(0, q to now)
            while (list.size > TERMS_KEPT) list.removeAt(list.lastIndex)
        }
    }

    /** The searches that opened [id], while they still count: it is used, and was opened within 17 days. */
    fun terms(
        id: String,
        now: Long,
    ): List<String> {
        val o = opened[id] ?: return emptyList()
        if (frecency(id, now) <= 1.0 || now - o.at > TERMS_FOR_MS) return emptyList()
        return terms[id]?.map { it.first }.orEmpty()
    }

    fun alias(id: String) = aliases[id]

    fun setAlias(
        id: String,
        alias: String?,
    ) {
        val a = alias?.trim()?.lowercase()
        if (a.isNullOrEmpty()) aliases.remove(id) else aliases[id] = a
    }

    /** Where [id] stands among the favorites, from 0; null if it isn't one. */
    fun favorite(id: String) = favorites.indexOf(id).takeIf { it >= 0 }

    fun favorites(): List<String> = favorites.toList()

    fun toggleFavorite(id: String) {
        if (!favorites.remove(id)) favorites.add(id)
    }

    fun hidden(id: String) = id in hidden

    fun hide(id: String) {
        hidden += id
        favorites.remove(id)
    }

    fun unhideAll() = hidden.clear()

    /** The things used most lately, most first, as Raycast's suggestions; never-opened ones aren't among them. */
    fun mostUsed(now: Long): List<String> = opened.keys.map { it to frecency(it, now) }.filter { it.second > 1.0 }.sortedByDescending { it.second }.map { it.first }

    fun toText(): String =
        buildString {
            for ((id, o) in opened) append("o\t").append(id).append('\t').append(o.anchor).append('\t').append(o.at).append('\n')
            for ((id, list) in terms) for ((q, at) in list) append("t\t").append(id).append('\t').append(q.replace('\t', ' ')).append('\t').append(at).append('\n')
            for ((id, a) in aliases) append("a\t").append(id).append('\t').append(a.replace('\t', ' ')).append('\n')
            for (id in favorites) append("f\t").append(id).append('\n')
            for (id in hidden) append("h\t").append(id).append('\n')
        }

    companion object {
        /** Halves every ten days. */
        val LAMBDA = ln(2.0) / 10
        const val TERMS_KEPT = 8
        const val TERMS_FOR_MS = 17L * 24 * 3600 * 1000

        fun score(
            anchor: Double,
            t: Double,
        ) = maxOf(1.0, exp(minOf(709.78, LAMBDA * (anchor - t) / 86400)))

        fun parse(text: String?): SearchMemory =
            SearchMemory().apply {
                for (line in text.orEmpty().lineSequence()) {
                    val p = line.split('\t')
                    when (p.getOrNull(0)) {
                        "o" -> if (p.size == 4) opened[p[1]] = Opened(p[2].toDoubleOrNull() ?: continue, p[3].toLongOrNull() ?: continue)
                        "t" -> if (p.size == 4) terms.getOrPut(p[1]) { ArrayList() }.add(p[2] to (p[3].toLongOrNull() ?: continue))
                        "a" -> if (p.size == 3) aliases[p[1]] = p[2]
                        "f" -> if (p.size == 2 && p[1] !in favorites) favorites += p[1]
                        "h" -> if (p.size == 2) hidden += p[1]
                    }
                }
            }
    }
}

/**
 * Orders what matches a query, as Raycast's root search does (oma-cast reference/02-backend.md §4.4-4.5): an exact
 * alias first, then what he picked before for this very search, an exact name, an exact subtitle, an alias or past
 * search the query begins, then the best match score, then use, then the type of thing.
 */
object SearchRank {
    private enum class Term { NONE, OVER, PREFIX, EXACT }

    private class Record(
        val c: Candidate,
        val aliasExact: Boolean,
        val aliasPrefix: Boolean,
        val term: Term,
        val overCount: Int,
        val titleExact: Boolean,
        val titleScore: Double,
        val subtitleExact: Boolean,
        val subtitleScore: Double,
        val frecency: Double,
        val hasAlias: Boolean,
    )

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    /** What of [candidates] matches [rawQuery], best first; hidden ones never show. */
    fun rank(
        candidates: List<Candidate>,
        rawQuery: String,
        memory: SearchMemory,
        now: Long,
        sensitivity: RootMatch.Sensitivity = RootMatch.Sensitivity.MEDIUM,
    ): List<Candidate> {
        val q = RootMatch.fold(rawQuery.trim())
        if (q.isEmpty()) return emptyList()
        val records = ArrayList<Record>()
        for (c in candidates) {
            if (memory.hidden(c.id)) continue
            record(c, q, memory, now, sensitivity)?.let(records::add)
        }
        return records.sortedWith(::compare).map { it.c }
    }

    private fun record(
        c: Candidate,
        q: String,
        memory: SearchMemory,
        now: Long,
        sensitivity: RootMatch.Sensitivity,
    ): Record? {
        val alias = memory.alias(c.id)
        val aliasExact = alias != null && alias == q
        val aliasPrefix = alias != null && !aliasExact && alias.startsWith(q)
        val title = RootMatch.match(c.title, q, literalDots = '.' in c.title)
        val alternates = c.alternateTitles.map { RootMatch.match(it, q) }
        val subtitle = c.subtitle?.takeIf { RootMatch.fold(it) != RootMatch.fold(c.title) }?.let { RootMatch.match(it, q) }
        // Learned searches: exact, one the query begins, or one the query runs past by up to three letters.
        var term = Term.NONE
        var over = 0
        for (s in memory.terms(c.id, now)) {
            when {
                s == q -> term = Term.EXACT
                s.startsWith(q) && s.length > q.length -> if (term < Term.PREFIX) term = Term.PREFIX
                q.startsWith(s) && q.length - s.length in 1..3 && term <= Term.OVER -> {
                    term = Term.OVER
                    over = maxOf(over, s.length)
                }
            }
        }
        val accepted = { m: Match -> RootMatch.accepts(m, q.length, sensitivity) }
        var matching = aliasExact || aliasPrefix || accepted(title) || alternates.any(accepted) || (subtitle != null && accepted(subtitle)) || term >= Term.PREFIX
        if (!matching) {
            val keywords = c.keywords + listOfNotNull(c.subtitle?.let { "${c.title} $it" }, c.subtitle?.let { "$it ${c.title}" })
            matching = keywords.any { accepted(RootMatch.match(it, q)) }
        }
        if (!matching) return null
        val fTitle = RootMatch.fold(c.title)
        var titleExact = title == Match.Exact
        val titlePrefix = !titleExact && (fTitle.startsWith(q) || c.alternateTitles.any { RootMatch.fold(it).startsWith(q) })
        var titleScore = RootMatch.value(title).toDouble().let { if (title == Match.Unmatched) -1.0 else it }
        if (alternates.any { it == Match.Exact }) titleExact = true
        titleScore = maxOf(titleScore, alternates.maxOfOrNull { if (it == Match.Unmatched) -1.0 else RootMatch.value(it).toDouble() } ?: -1.0)
        // A query of three letters or more that stands in the name as a whole word scores as if it began it.
        if (!titleExact && q.length >= 3 && q.none { it.isWhitespace() } && RootMatch.wholeWord(c.title, q)) {
            titleScore = maxOf(titleScore, 4 + (q.length - 1) * 2 + 0.5)
        }
        // A prefix counts above any scattered match of the same letters.
        if (titlePrefix) titleScore = maxOf(titleScore, 4 + (q.length - 1) * 3.0)
        return Record(
            c,
            aliasExact,
            aliasPrefix,
            term,
            over,
            titleExact,
            if (titleExact) Double.MAX_VALUE else titleScore,
            subtitle == Match.Exact,
            subtitle?.let { if (it == Match.Unmatched) -1.0 else RootMatch.value(it).toDouble() } ?: -1.0,
            memory.frecency(c.id, now),
            alias != null,
        )
    }

    private fun tiebreak(
        a: Record,
        b: Record,
    ): Int {
        if (a.frecency != b.frecency) return b.frecency.compareTo(a.frecency)
        if (a.hasAlias != b.hasAlias) return if (a.hasAlias) -1 else 1
        if (a.c.kind.priority != b.c.kind.priority) return b.c.kind.priority - a.c.kind.priority
        return collator.compare(a.c.title, b.c.title)
    }

    private fun compare(
        a: Record,
        b: Record,
    ): Int {
        if (a.aliasExact != b.aliasExact) return if (a.aliasExact) -1 else 1
        // An exact past search beats even an exact name: he picked that for this before.
        if ((a.term == Term.EXACT) != (b.term == Term.EXACT)) return if (a.term == Term.EXACT) -1 else 1
        if (a.term == Term.EXACT && b.term == Term.EXACT && a.frecency != b.frecency) return b.frecency.compareTo(a.frecency)
        // A contact gives way unless it is used more, or matched much better.
        if ((a.c.kind == Kind.CONTACT) != (b.c.kind == Kind.CONTACT)) {
            val contact = if (a.c.kind == Kind.CONTACT) a else b
            val other = if (contact === a) b else a
            val strong = { r: Record -> r.aliasExact || r.aliasPrefix || r.titleExact || r.subtitleExact || r.term >= Term.PREFIX }
            if (!(contact.frecency > 1 && contact.frecency > other.frecency) && !(strong(contact) && !strong(other))) return if (contact === a) 1 else -1
        }
        if (a.titleExact != b.titleExact) return if (a.titleExact) -1 else 1
        if (a.titleExact && b.titleExact) {
            if (a.term != b.term) return b.term.compareTo(a.term)
            return tiebreak(a, b)
        }
        if (a.subtitleExact != b.subtitleExact) return if (a.subtitleExact) -1 else 1
        if (a.aliasPrefix != b.aliasPrefix) return if (a.aliasPrefix) -1 else 1
        if ((a.term == Term.PREFIX) != (b.term == Term.PREFIX)) return if (a.term == Term.PREFIX) -1 else 1
        if (a.term == Term.PREFIX && b.term == Term.PREFIX && a.frecency != b.frecency) return b.frecency.compareTo(a.frecency)
        if (maxOf(a.overCount, b.overCount) >= 3 && a.overCount != b.overCount) return b.overCount - a.overCount
        val am = maxOf(a.titleScore, a.subtitleScore)
        val bm = maxOf(b.titleScore, b.subtitleScore)
        if (am != bm) return bm.compareTo(am)
        if (a.frecency != b.frecency) return b.frecency.compareTo(a.frecency)
        if (a.titleScore != b.titleScore) return b.titleScore.compareTo(a.titleScore)
        return tiebreak(a, b)
    }
}
