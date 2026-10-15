package dev.omalauncher.keyboard

import java.util.Locale
import kotlin.math.hypot

/**
 * What the keyboard learns from Boss's typing, so a review can suggest new aim (Boss, 2026-10-14: the
 * learning suggests, he approves; keys never move under him on their own). It keeps only sums per letter,
 * never the text: where taps landed on each letter he kept, where the ones he fixed had landed (a letter,
 * ⌫, then a neighbour), and how often each slip happened. Plain Kotlin; the service saves [toJson] to
 * ~/.local/share/oma-kb/learn-<screen>.json and reads a reviewed table from aim-<screen>.json.
 */
class KeyboardLearning {
    class Sums {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0

        fun add(
            dx: Float,
            dy: Float,
        ) {
            n++
            sx += dx
            sy += dy
            sxx += dx * dx
            syy += dy * dy
        }
    }

    /** Taps on each letter that stood, dp from the key's centre. */
    val kept = HashMap<Char, Sums>()

    /** Taps that were fixed to this letter, dp from its centre. */
    val fixed = HashMap<Char, Sums>()

    /** "c>x": wanted c, hit x; how many times. */
    val slips = HashMap<String, Int>()

    private class Tap(
        val key: Key,
        val x: Float,
        val y: Float,
        val layout: KeyLayout,
    )

    private var last: Tap? = null
    private var erased: Tap? = null

    /** Something changed since the last save. */
    var dirty = false
        private set

    /** A letter was typed by a touch at ([x], [y]) on [layout]. */
    fun letter(
        key: Key,
        x: Float,
        y: Float,
        layout: KeyLayout,
    ) {
        val c = key.letter ?: return other()
        keep()
        erased?.let { gone ->
            val was = gone.key.letter
            if (was != null && was != c && near(gone.key, key, layout)) {
                // The erased tap was meant for this letter: where it landed, from this key's centre.
                fixed.getOrPut(c) { Sums() }.add(gone.x - key.cell.cx, gone.y - key.cell.cy)
                slips.merge("$c>$was", 1, Int::plus)
                dirty = true
            }
        }
        erased = null
        last = Tap(key, x, y, layout)
    }

    /** ⌫: the letter just typed may have been a slip; the next letter says. */
    fun back() {
        erased = last
        last = null
    }

    /** Anything else typed: the last letter stood. */
    fun other() {
        keep()
        erased = null
    }

    private fun keep() {
        val t = last ?: return
        last = null
        val c = t.key.letter ?: return
        kept.getOrPut(c) { Sums() }.add(t.x - t.key.cell.cx, t.y - t.key.cell.cy)
        dirty = true
    }

    private fun near(
        a: Key,
        b: Key,
        layout: KeyLayout,
    ) = hypot(a.cell.cx - b.cell.cx, a.cell.cy - b.cell.cy) < 1.6f * maxOf(layout.unit, layout.rowHeight)

    /** Ends the run of typing (the field closed): the last letter stood. */
    fun finish() = other()

    fun toJson(): String {
        fun sums(m: Map<Char, Sums>) =
            m.toSortedMap().entries.joinToString(",", "{", "}") { (c, s) ->
                String.format(Locale.ROOT, "\"%c\":{\"n\":%d,\"sx\":%.1f,\"sy\":%.1f,\"sxx\":%.1f,\"syy\":%.1f}", c, s.n, s.sx, s.sy, s.sxx, s.syy)
            }
        val sl = slips.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
        dirty = false
        return "{\"kept\":${sums(kept)},\"fixed\":${sums(fixed)},\"slips\":$sl}\n"
    }

    /** Adds what [json] (an earlier [toJson]) holds, so learning goes on across restarts. Ignores what it can't read. */
    fun load(json: String) {
        fun section(name: String) = Regex("\"$name\":\\{(.*?)\\}\\}").find(json)?.groupValues?.get(1).orEmpty() + "}"
        val entry = Regex("\"(.)\":\\{\"n\":(\\d+),\"sx\":([-\\d.]+),\"sy\":([-\\d.]+),\"sxx\":([-\\d.]+),\"syy\":([-\\d.]+)\\}")
        for ((name, into) in listOf("kept" to kept, "fixed" to fixed)) {
            for (m in entry.findAll(section(name))) {
                val (c, n, sx, sy, sxx, syy) = m.destructured
                into.getOrPut(c[0]) { Sums() }.apply {
                    this.n += n.toInt()
                    this.sx += sx.toDouble()
                    this.sy += sy.toDouble()
                    this.sxx += sxx.toDouble()
                    this.syy += syy.toDouble()
                }
            }
        }
        Regex("\"slips\":\\{([^}]*)\\}").find(json)?.groupValues?.get(1)?.let { body ->
            for (m in Regex("\"(.>.)\":(\\d+)").findAll(body)) slips.merge(m.groupValues[1], m.groupValues[2].toInt(), Int::plus)
        }
    }

    companion object {
        /** A reviewed aim table, `{"a":[-7.8,-7.3],…}`; null when [json] has no letter in it. */
        fun parseAim(json: String): Aim? {
            val aim =
                Regex("\"([a-z])\"\\s*:\\s*\\[\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*\\]").findAll(json).associate { m ->
                    m.groupValues[1][0] to (m.groupValues[2].toFloat() to m.groupValues[3].toFloat())
                }
            return aim.ifEmpty { null }
        }
    }
}
