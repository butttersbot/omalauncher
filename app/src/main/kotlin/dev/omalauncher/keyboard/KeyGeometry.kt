package dev.omalauncher.keyboard

/**
 * Where every key of the Oma keyboard is, in dp: Apple's layout, the same arithmetic as geom() in
 * design/oma-keyboard-practice.html, where Boss practised it on the Fold (2026-10-14). Plain Kotlin, so it is
 * tested without Android.
 *
 * A key's cell is where a touch counts for it; its cap is what is drawn, the cell less half the gap on each
 * side. Return sits at the right of the bottom row with the mic between it and the space bar ("A", the
 * placement Boss kept: no accidental sends in his practice).
 */
enum class Screen {
    COVER,
    PORTRAIT,
    LANDSCAPE,
    ;

    companion object {
        /** The cover screen is 475 dp wide; the inner one is 704 by 932, either way up. */
        fun of(
            widthDp: Float,
            heightDp: Float,
        ) = when {
            widthDp < 560f -> COVER
            widthDp > heightDp -> LANDSCAPE
            else -> PORTRAIT
        }
    }
}

enum class Layer { ABC, NUM, SYM, EMOJI }

/** What a key does; a letter, digit, sign or emoji is [Type]. */
sealed interface KeyAction {
    data class Type(val text: String) : KeyAction

    data object Space : KeyAction

    data object Back : KeyAction

    data object Shift : KeyAction

    /** 123 / ABC at the bottom left. */
    data object Layer : KeyAction

    /** #+= and 123 on the third row of the number layers. */
    data class Go(val to: dev.omalauncher.keyboard.Layer) : KeyAction

    data object Emoji : KeyAction

    data object Mic : KeyAction

    data object Return : KeyAction
}

enum class KeyKind { LETTER, MOD, SPACE, MIC, RETURN }

class Box(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

class Key(
    val row: Int,
    /** The cell: where a touch counts. */
    val cell: Box,
    /** What is drawn. */
    val cap: Box,
    val label: String,
    val action: KeyAction,
    val kind: KeyKind,
) {
    /** The letter this key types on the letter layer, lowercase; null for anything else. */
    val letter: Char? get() = (action as? KeyAction.Type)?.text?.singleOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }
}

/** Where a learned landing spot moves each letter's aim, in dp from the key's centre (+x right, +y down). */
typealias Aim = Map<Char, Pair<Float, Float>>

class KeyLayout(
    val keys: List<Key>,
    /** Height of the whole board, dp. */
    val height: Float,
    /** Height of one row, dp. */
    val rowHeight: Float,
    /** Space above the first row, dp. */
    val top: Float,
    /** One key's width, dp. */
    val unit: Float,
) {
    fun rowOf(y: Float) = ((y - top) / rowHeight).toInt().coerceIn(0, 3)

    /** The key whose cell holds ([x], [y]); between cells or past a row's end, the nearest one in that row. */
    fun hitPlain(
        x: Float,
        y: Float,
    ): Key? {
        val row = rowOf(y)
        return keys.filter { it.row == row }.minByOrNull { k ->
            when {
                x < k.cell.x -> k.cell.x - x
                x >= k.cell.x + k.cell.w -> x - (k.cell.x + k.cell.w) + 1e-4f
                else -> 0f
            }
        }
    }

    /**
     * The key a touch is for. With [aim], a touch on a letter goes to the letter whose learned landing spot it
     * is nearest to, measured in key widths and row heights, as in the practice page; shift, ⌫ and the bottom
     * row stay exactly where they are drawn.
     */
    fun hit(
        x: Float,
        y: Float,
        aim: Aim?,
    ): Key? {
        val plain = hitPlain(x, y) ?: return null
        if (aim == null || plain.row == 3 || plain.letter == null) return plain
        return keys.filter { it.row < 3 && it.letter != null }.minByOrNull { k ->
            val (ox, oy) = aim[k.letter] ?: (0f to 0f)
            val dx = (x - k.cell.cx - ox) / k.cell.w
            val dy = (y - k.cell.cy - oy) / k.cell.h
            dx * dx + dy * dy
        } ?: plain
    }
}

object KeyGeometry {
    val LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val NUM = listOf("1234567890", "-/:;()$&@\"", ".,?!'")
    private val SYM = listOf("[]{}#%^*+=", "_\\|~<>€£¥•", ".,?!'")
    private val EMOJI =
        listOf(
            listOf("😂", "❤️", "👍", "🙏", "😭", "😊", "🔥", "✨", "🥲", "😅"),
            listOf("🎉", "👀", "💯", "😍", "🤔", "🙌", "😎", "🤣", "😢"),
            listOf("👌", "💀", "🫡", "😴", "🤷", "✅", "👋"),
        )

    /** Side padding, dp. */
    const val PAD = 4f

    /** Row heights per screen, dp: what Boss practised on. */
    fun rowHeight(screen: Screen) =
        when (screen) {
            Screen.COVER -> 54f
            Screen.PORTRAIT -> 58f
            Screen.LANDSCAPE -> 50f
        }

    /**
     * One key's width when the inner screen's keys are split ([layout]'s split): the cover keyboard's, the size his
     * thumbs practised on (475 dp across ten keys), so each half is that keyboard's half.
     */
    const val SPLIT_UNIT = 46.7f

    /**
     * The keys for a board [widthDp] wide on [screen]. With [split], on the inner screen only (Boss, 2026-10-17: "the
     * split screen keyboard"), each row is cut in two and its halves pushed to the two edges, one for each thumb: the
     * left half of each row at the left, the rest at the right, a gap between, ⇧ and ⌫ at the outer ends, and the
     * space bar in two, one under each thumb. On the cover screen it is always one piece.
     */
    fun layout(
        widthDp: Float,
        screen: Screen,
        layer: Layer,
        mech: Boolean,
        upper: Boolean,
        capsLock: Boolean,
        returnLabel: String,
        split: Boolean = false,
    ): KeyLayout {
        val top = if (mech) 10f else 6f
        val bottom = 8f
        val h = rowHeight(screen)
        // Mechanical caps sit nearly flush (2 dp apart, as Boss asked); flat ones keep Apple's gaps.
        val gx = if (mech) 2f else 6f
        val gy = if (mech) 4f else 10f
        val keys = ArrayList<Key>()

        fun add(
            row: Int,
            x: Float,
            w: Float,
            label: String,
            action: KeyAction,
            kind: KeyKind,
            visX: Float = x,
            visW: Float = w,
        ) {
            val y = top + row * h
            keys += Key(row, Box(x, y, w, h), Box(visX + gx / 2, y + gy / 2, visW - gx, h - gy), label, action, kind)
        }

        val rows: List<List<String>> =
            when (layer) {
                Layer.ABC -> LETTERS.map { r -> r.map { it.toString() } }
                Layer.NUM -> NUM.map { r -> r.map { it.toString() } }
                Layer.SYM -> SYM.map { r -> r.map { it.toString() } }
                Layer.EMOJI -> EMOJI
            }
        val shown = { s: String -> if (layer == Layer.ABC && upper) s.uppercase() else s }
        val (modLabel, modAction) =
            when (layer) {
                Layer.ABC -> (if (capsLock) "⇪" else "⇧") to KeyAction.Shift
                Layer.NUM -> "#+=" to KeyAction.Go(Layer.SYM)
                Layer.SYM -> "123" to KeyAction.Go(Layer.NUM)
                Layer.EMOJI -> "ABC" to KeyAction.Go(Layer.ABC)
            }
        val x0 = PAD

        if (split && screen != Screen.COVER) {
            val u = minOf(SPLIT_UNIT, (widthDp - 2 * PAD) / 10)
            // Where the right half's five units begin: its keys end at the right edge as the left half's begin at the left.
            val r = widthDp - PAD - 5 * u

            fun halves(row: List<String>) = row.take((row.size + 1) / 2) to row.drop((row.size + 1) / 2)
            fun letters(row: Int, chars: List<String>, from: Float) =
                chars.forEachIndexed { i, c -> add(row, from + i * u, u, shown(c), KeyAction.Type(c), KeyKind.LETTER) }
            val (l0, r0) = halves(rows[0])
            letters(0, l0, x0)
            letters(0, r0, r + (5 - r0.size) * u)
            // Apple's half-key step on the second row of letters, in both halves.
            val step = if (rows[1].size < 10) u / 2 else 0f
            val (l1, r1) = halves(rows[1])
            letters(1, l1, x0 + step)
            letters(1, r1, r + step)
            // Third row: ⇧ at the left end and ⌫ at the right one, each 1.5 units of touch showing 1.25, as in one piece.
            val (l2, r2) = halves(rows[2])
            add(2, x0, 1.5f * u, modLabel, modAction, KeyKind.MOD, visW = 1.25f * u)
            letters(2, l2, x0 + 1.5f * u)
            letters(2, r2, r + 3.5f * u - r2.size * u)
            add(2, r + 3.5f * u, 1.5f * u, "⌫", KeyAction.Back, KeyKind.MOD, visX = r + 3.75f * u, visW = 1.25f * u)
            // Bottom row: 123, 🙂 and a space for the left thumb; a space, the mic and return for the right one.
            var x = x0

            fun put(w: Float, label: String, action: KeyAction, kind: KeyKind) {
                add(3, x, w * u, label, action, kind)
                x += w * u
            }
            put(1.25f, if (layer == Layer.ABC) "123" else "ABC", KeyAction.Layer, KeyKind.MOD)
            put(1.1f, "🙂", KeyAction.Emoji, KeyKind.MOD)
            put(2.65f, "space", KeyAction.Space, KeyKind.SPACE)
            x = r
            put(1.75f, "space", KeyAction.Space, KeyKind.SPACE)
            put(1.5f, "🎤", KeyAction.Mic, KeyKind.MIC)
            put(1.75f, returnLabel, KeyAction.Return, KeyKind.RETURN)
            return KeyLayout(keys, top + 4 * h + bottom, h, top, u)
        }

        val u = (widthDp - 2 * PAD) / 10

        rows[0].forEachIndexed { i, c -> add(0, x0 + i * u, u, shown(c), KeyAction.Type(c), KeyKind.LETTER) }
        val off = (10 - rows[1].size) / 2f * u
        rows[1].forEachIndexed { i, c -> add(1, x0 + off + i * u, u, shown(c), KeyAction.Type(c), KeyKind.LETTER) }
        // Third row: shift and ⌫ take 1.5 units of touch but show 1.25, leaving the small gap Apple has.
        add(2, x0, 1.5f * u, modLabel, modAction, KeyKind.MOD, visW = 1.25f * u)
        val mw = 7 * u / rows[2].size
        rows[2].forEachIndexed { i, c -> add(2, x0 + 1.5f * u + i * mw, mw, shown(c), KeyAction.Type(c), KeyKind.LETTER) }
        add(2, x0 + 8.5f * u, 1.5f * u, "⌫", KeyAction.Back, KeyKind.MOD, visX = x0 + 8.75f * u, visW = 1.25f * u)

        var x = x0

        fun put(
            w: Float,
            label: String,
            action: KeyAction,
            kind: KeyKind,
        ) {
            add(3, x, w * u, label, action, kind)
            x += w * u
        }
        put(1.25f, if (layer == Layer.ABC) "123" else "ABC", KeyAction.Layer, KeyKind.MOD)
        put(1.1f, "🙂", KeyAction.Emoji, KeyKind.MOD)
        put(4.4f, "space", KeyAction.Space, KeyKind.SPACE)
        put(1.5f, "🎤", KeyAction.Mic, KeyKind.MIC)
        put(1.75f, returnLabel, KeyAction.Return, KeyKind.RETURN)
        return KeyLayout(keys, top + 4 * h + bottom, h, top, u)
    }

    /**
     * Where Boss's thumbs land on each letter of the cover screen, dp from the key's centre, learned from 835
     * of his practice taps on 2026-10-14 (design/kb-taps-report.py): slips 78 -> 48 on taps held out of the
     * fit, and 8.3% -> 4.9% when he typed with it. The inner screen has none yet. A reviewed table in
     * ~/.local/share/oma-kb/aim-<screen>.json takes its place (see [KeyboardLearning]).
     */
    val COVER_AIM: Aim =
        mapOf(
            'a' to (-7.8f to -7.3f), 'b' to (-14.5f to 4.6f), 'c' to (-15.0f to 3.4f), 'd' to (-5.0f to 9.9f),
            'e' to (0.0f to 1.5f), 'f' to (-2.8f to 4.5f), 'g' to (-7.0f to 9.0f), 'h' to (-7.6f to 9.0f),
            'i' to (-6.6f to 11.5f), 'j' to (-7.9f to 4.3f), 'k' to (-16.3f to 7.6f), 'l' to (-0.5f to 10.5f),
            'm' to (-3.3f to 1.4f), 'n' to (-11.0f to 7.3f), 'o' to (-5.2f to 12.8f), 'p' to (-0.9f to 10.4f),
            'q' to (-3.9f to 3.0f), 'r' to (-1.5f to -0.6f), 's' to (1.7f to 7.6f), 't' to (-4.0f to -2.3f),
            'u' to (-2.8f to 11.6f), 'v' to (-8.0f to 4.7f), 'w' to (-1.1f to 0.2f), 'x' to (-7.6f to 4.5f),
            'y' to (-5.9f to 4.5f), 'z' to (-4.0f to 6.3f),
        )
}
