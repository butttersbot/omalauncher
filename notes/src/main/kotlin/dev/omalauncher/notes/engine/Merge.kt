package dev.omalauncher.notes.engine

/**
 * Two edits of one note put together: the phone's ([mine], made offline perhaps) and the Mac's ([theirs], by an agent
 * or another device), both from [base], the text they last agreed on. Nothing either side wrote is lost: the
 * Mac's version, with the phone's new paragraphs and lines put in after what they followed. A list ticked on both
 * sides keeps each tick. Pure, for the JVM tests.
 */
object Merge {
    fun merge(
        base: String,
        mine: String,
        theirs: String,
    ): String {
        if (mine == theirs || mine == base) return theirs
        if (theirs == base) return mine
        val b = base.lines()
        val m = mine.lines()
        val t = theirs.lines().toMutableList()
        val baseSet = b.toSet()
        // A ticked item on the phone ticks the same item on the Mac's side.
        for (line in m) {
            val ticked = tick(line) ?: continue
            val open = t.indexOf(ticked)
            if (open >= 0 && ticked in baseSet) t[open] = line
        }
        val have = t.toMutableSet()
        // Each line new on the phone goes after the line it followed, where that is on the Mac's side; else at the end.
        var after = -1
        for ((i, line) in m.withIndex()) {
            val at = t.indexOf(line)
            if (at >= 0) {
                after = at
                continue
            }
            // Deleted on the Mac's side and untouched here, or ticked here: stays deleted.
            if (line in baseSet || line in have || tick(line)?.let { it in baseSet } == true) continue
            if (line.isBlank() && (i == 0 || m[i - 1].isBlank())) continue
            val to = if (after >= 0) after + 1 else t.size
            t.add(to.coerceAtMost(t.size), line)
            have += line
            after = to
        }
        return t.joinToString("\n")
    }

    /** "- [x] Milk" → "- [ ] Milk"; null when [line] isn't a ticked item. */
    private fun tick(line: String): String? {
        val m = Regex("^(\\s*[-*] )\\[[xX]](.*)$").find(line) ?: return null
        return m.groupValues[1] + "[ ]" + m.groupValues[2]
    }
}
