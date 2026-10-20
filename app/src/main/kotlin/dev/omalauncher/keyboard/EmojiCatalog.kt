package dev.omalauncher.keyboard

/**
 * Every emoji the picker offers, in Apple's sections and order (Boss, 2026-10-20: "a proper emoji picker... just like
 * apple emoji picker"), read from assets/emoji/emoji.txt, which tools/emoji/make-emoji-asset.py makes from Unicode's
 * list and CLDR's English keywords. Plain Kotlin, so it is tested without Android.
 */
class EmojiItem(
    /** The emoji in its default, yellow form. */
    val emoji: String,
    /** Its five skin tones, light to dark; empty when it has none. */
    val tones: List<String>,
    /** Its name first, then the words a search finds it by. */
    val words: List<String>,
) {
    val name get() = words.first()

    /** The forms a long press offers: the default, then the five tones. */
    val forms get() = if (tones.isEmpty()) emptyList() else listOf(emoji) + tones
}

class EmojiSection(
    val title: String,
    val items: List<EmojiItem>,
)

class EmojiCatalog(
    val sections: List<EmojiSection>,
) {
    private val byForm: Map<String, EmojiItem> by lazy {
        HashMap<String, EmojiItem>().apply {
            for (s in sections) for (i in s.items) for (f in listOf(i.emoji) + i.tones) put(f, i)
        }
    }

    /** The item [emoji] is, in any of its forms. */
    fun item(emoji: String) = byForm[emoji]

    /** Only what [drawable] says the phone's font can draw: a newer emoji would show as boxes or as pieces. */
    fun filter(drawable: (String) -> Boolean) =
        EmojiCatalog(
            sections.map { s ->
                EmojiSection(
                    s.title,
                    s.items.filter { drawable(it.emoji) }.map { i ->
                        if (i.tones.isNotEmpty() && !i.tones.all(drawable)) EmojiItem(i.emoji, emptyList(), i.words) else i
                    },
                )
            },
        )

    /**
     * The emoji a search for [query] finds, best first: every word of the query must begin a word of the emoji's name
     * or keywords. A whole name or keyword matched ranks above a word that only begins with it, the name above the
     * keywords, and otherwise the picker's own order stands.
     */
    fun search(
        query: String,
        limit: Int = 80,
    ): List<EmojiItem> {
        val terms = words(query)
        if (terms.isEmpty()) return emptyList()
        val q = terms.joinToString(" ")
        val found = ArrayList<Pair<Int, EmojiItem>>()
        for (s in sections) {
            for (item in s.items) {
                var best = Int.MAX_VALUE
                for ((n, phrase) in item.words.withIndex()) {
                    val p = phrase.lowercase()
                    val pw = words(p)
                    if (!terms.all { t -> pw.any { it.startsWith(t) } }) continue
                    val rank =
                        when {
                            p == q -> 0
                            p.startsWith(q) -> 1
                            pw.contains(q) -> 2
                            else -> 3
                        } * 2 + if (n == 0) 0 else 1
                    best = minOf(best, rank)
                }
                if (best != Int.MAX_VALUE) found += best to item
            }
        }
        return found.sortedBy { it.first }.take(limit).map { it.second }
    }

    companion object {
        private val SPLIT = Regex("[^\\p{L}\\p{N}+#*]+")

        fun words(text: String) = text.lowercase().split(SPLIT).filter { it.isNotEmpty() }

        /** Reads the asset: "== Title" begins a section; each emoji is "emoji \t tones \t name | keyword | ...". */
        fun parse(text: String): EmojiCatalog {
            val sections = ArrayList<EmojiSection>()
            var title: String? = null
            var items = ArrayList<EmojiItem>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                if (line.startsWith("== ")) {
                    title?.let { sections += EmojiSection(it, items) }
                    title = line.removePrefix("== ").trim()
                    items = ArrayList()
                    continue
                }
                val parts = line.split('\t')
                if (parts.size < 3 || title == null) continue
                items += EmojiItem(parts[0], parts[1].split(' ').filter { it.isNotEmpty() }, parts[2].split('|').map { it.trim() }.filter { it.isNotEmpty() })
            }
            title?.let { sections += EmojiSection(it, items) }
            return EmojiCatalog(sections)
        }
    }
}

/**
 * What Boss uses, as Apple's keyboard keeps it: the emoji he types most and lately for Frequently Used, and the skin
 * tone he last picked for each emoji, which the picker then shows in its place.
 */
class EmojiUsage {
    private val scores = LinkedHashMap<String, Double>()
    private val tones = HashMap<String, String>()

    /** [emoji] was typed: it counts one more, and all the others fade a little, so lately counts as well as often. */
    fun used(emoji: String) {
        for (k in scores.keys) scores[k] = scores.getValue(k) * FADE
        scores[emoji] = (scores[emoji] ?: 0.0) + 1.0
        if (scores.size > KEPT) scores.entries.sortedBy { it.value }.take(scores.size - KEPT).forEach { scores.remove(it.key) }
    }

    /** The emoji for Frequently Used, most used first. */
    fun frequent(n: Int = SHOWN): List<String> = scores.entries.sortedByDescending { it.value }.take(n).map { it.key }

    /** The form of [item] the picker shows: the tone last picked for it, else the default. */
    fun shown(item: EmojiItem) = tones[item.emoji]?.takeIf { it in item.tones } ?: item.emoji

    /** [form] of [item] was picked from its tones: shown in its place from now on. */
    fun pickTone(
        item: EmojiItem,
        form: String,
    ) {
        if (form == item.emoji) tones.remove(item.emoji) else tones[item.emoji] = form
    }

    fun toText() =
        buildString {
            for ((e, s) in scores) append("u\t").append(e).append('\t').append(s).append('\n')
            for ((b, t) in tones) append("t\t").append(b).append('\t').append(t).append('\n')
        }

    companion object {
        /** How much every other emoji fades each time one is typed. */
        const val FADE = 0.97
        const val KEPT = 60

        /** Apple's Frequently Used holds about thirty. */
        const val SHOWN = 30

        fun parse(text: String?) =
            EmojiUsage().apply {
                for (line in text.orEmpty().lineSequence()) {
                    val p = line.split('\t')
                    if (p.size != 3) continue
                    when (p[0]) {
                        "u" -> p[2].toDoubleOrNull()?.let { scores[p[1]] = it }
                        "t" -> tones[p[1]] = p[2]
                    }
                }
            }
    }
}
