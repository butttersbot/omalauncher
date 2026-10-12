package dev.omalauncher.widgets.annotations

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.PAGE_ASPECT
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * What is free on a home page: ink strokes, sticky notes and stickers (PRD 6.6). Everything else on a page
 * sits in grid cells; these are placed as fractions of the page's own rectangle, which has one shape on every
 * display, so they land in the same place on a foldable's cover and on half of its inner screen (PRD AC-24).
 * Across is a fraction of the page's width and down a fraction of its height; a size that has to keep its
 * shape (a stroke's width, a sticker) is a fraction of the width alone.
 */

/** How many steps a fraction is kept in: a ten-thousandth of the page, an eighth of a pixel on the Fold. */
const val UNITS = 10_000

/** A page's height over its width: what a fraction down is worth in fractions across. */
const val PAGE_TALL = 1f / PAGE_ASPECT

/** [value] as a fraction of the page: within it, and on the step it is saved at, so what is saved is what was shown. */
fun unit(value: Float): Float = if (value.isNaN()) 0f else (value.coerceIn(0f, 1f) * UNITS).roundToInt() / UNITS.toFloat()

/** The bounds nothing on a page goes past, however it got there. */
object AnnotationLimits {
    /** A page takes this many strokes and then no more: it says so rather than drop old ink (PRD UP-18). */
    const val STROKES = 2_000

    /** The points one stroke keeps; a longer line is thinned while it is drawn. */
    const val POINTS = 256
    const val NOTES = 24
    const val STICKERS = 48

    /** The characters a note takes from its owner, and the fewer it takes from an agent. */
    const val NOTE_TEXT = 500
    const val AGENT_TEXT = 280

    /** How many notes an agent keeps on one page: the next one takes the place of its oldest. */
    const val AGENT_NOTES = 4

    /** A page's file is never bigger than this when every bound above is met; a bigger one isn't read. */
    const val FILE_BYTES = 8L * 1024 * 1024

    /** How far back undo goes. */
    const val HISTORY = 100

    const val MIN_WIDTH = 0.0005f
    const val MAX_WIDTH = 0.1f
    const val MIN_NOTE = 0.12f
    const val MIN_STICKER = 0.04f
    const val MAX_STICKER = 0.5f
}

/** What a stroke was drawn with. */
enum class InkTool(
    val id: String,
) {
    Pen("pen"),

    /** Wide and see-through, over whatever it crosses. */
    Highlighter("highlighter"),
}

/** Which of the theme pack's inks a stroke is in: the color itself is the pack's, so a stroke changes with the look. */
enum class InkRole(
    val id: String,
) {
    Pen("pen"),
    Second("second"),
    Highlighter("highlighter"),
}

/**
 * One line drawn without lifting the finger: [points] are x, y, x, y… as fractions of the page, in the order
 * drawn, and [width] a fraction of the page's width.
 */
class Stroke(
    val tool: InkTool,
    val role: InkRole,
    val width: Float,
    val points: FloatArray,
) {
    val count get() = points.size / 2

    /** left, top, right, bottom of its points. */
    val bounds: FloatArray by lazy {
        val b = floatArrayOf(1f, 1f, 0f, 0f)
        for (i in 0 until count) {
            b[0] = minOf(b[0], points[2 * i])
            b[1] = minOf(b[1], points[2 * i + 1])
            b[2] = maxOf(b[2], points[2 * i])
            b[3] = maxOf(b[3], points[2 * i + 1])
        }
        b
    }

    override fun equals(other: Any?) = other is Stroke && tool == other.tool && role == other.role && width == other.width && points.contentEquals(other.points)

    override fun hashCode() = 31 * (31 * (31 * tool.hashCode() + role.hashCode()) + width.hashCode()) + points.contentHashCode()

    override fun toString() = "Stroke(${tool.id}, ${role.id}, $width, $count points)"
}

/** Which of the pack's surfaces a note is cut from. */
enum class NoteRole(
    val id: String,
) {
    /** The pack's sticky note. */
    Sticky("sticky"),

    /** Its emphasised scrap: what an agent's note comes in, so it is told from the owner's at a glance. */
    Alt("alt"),

    /** Words typed straight onto the page: no paper under them, in the ink the page is written in. */
    Text("text"),
}

/** Who a note is from. */
enum class Author(
    val id: String,
) {
    Owner("me"),

    /** Left by an agent and not touched by the owner since: the only notes an agent's next one may replace. */
    Agent("oma"),
}

/** A sticky note: its top-left corner and size as fractions of the page, and [z] its place in the pile, higher on top. */
data class StickyNote(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val text: String,
    val role: NoteRole = NoteRole.Sticky,
    val z: Int = 0,
    val author: Author = Author.Owner,
    /** When it was left, by the clock file times use. */
    val at: Long = 0,
)

/** The built-in stickers, each drawn in code. */
enum class StickerKind(
    val id: String,
) {
    Star("star"),
    Heart("heart"),
    Flower("flower"),
    Check("check"),
    Bolt("bolt"),
    Smile("smile"),
}

/** A sticker: its centre as fractions of the page, [size] across as a fraction of the page's width, turned [turn] degrees. */
data class Sticker(
    val id: String,
    val kind: StickerKind,
    val x: Float,
    val y: Float,
    val size: Float,
    val turn: Int = 0,
)

/** Everything free on one page. Strokes are in the order drawn; stickers lie over notes, later ones on top. */
data class Annotations(
    val strokes: List<Stroke> = emptyList(),
    val notes: List<StickyNote> = emptyList(),
    val stickers: List<Sticker> = emptyList(),
) {
    val isEmpty get() = strokes.isEmpty() && notes.isEmpty() && stickers.isEmpty()
}

/**
 * A page's rectangle on some display, in that display's pixels: where fractions become places, and back.
 * The page has the same shape everywhere, so only its scale and where it starts differ from one to the next.
 */
data class PageArea(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    /** How far across the page a pixel is, 0 to 1; outside the page it is past either. */
    fun across(x: Float) = (x - left) / width

    fun down(y: Float) = (y - top) / height

    fun x(across: Float) = left + across * width

    fun y(down: Float) = top + down * height

    /** A length that keeps its shape: a fraction of the page's width, in pixels. */
    fun length(share: Float) = share * width

    fun share(length: Float) = length / width
}

// Thinning a line.

/** How far, in fractions of the page's width, a point may lie from the line through its neighbours and be left out. */
const val STROKE_TOLERANCE = 0.0012f

/**
 * [points] (x, y, x, y…) with those left out that the line doesn't need: every point dropped lies within
 * [tolerance] of the line that remains (Ramer–Douglas–Peucker), and the ends always stay. Never more than
 * [max] points come back: of a line that still has more, the [max] that shape it most are kept, so a long
 * scribble loses its smallest wiggles first and never its turns. [tall] is what a step down is worth against
 * a step across; 1 for pixels.
 */
fun simplify(
    points: FloatArray,
    tolerance: Float,
    max: Int = AnnotationLimits.POINTS,
    tall: Float = PAGE_TALL,
): FloatArray {
    val n = points.size / 2
    val most = max.coerceAtLeast(2)
    if (n <= 2) return points.copyOf(n * 2)
    val weights = weighed(points, n, tolerance.coerceAtLeast(0f), tall)
    var kept = (0 until n).filter { weights[it] > 0f }
    if (kept.size > most) kept = kept.sortedWith(compareByDescending<Int> { weights[it] }.thenBy { it }).take(most).sorted()
    val out = FloatArray(kept.size * 2)
    kept.forEachIndexed { at, i ->
        out[2 * at] = points[2 * i]
        out[2 * at + 1] = points[2 * i + 1]
    }
    return out
}

/**
 * How much each point shapes the line: how far it lay from the line through the points kept before it, and
 * never more than the point it was found between was worth; 0 for a point within [tolerance], which is left
 * out, and without bound for the two ends.
 */
private fun weighed(
    points: FloatArray,
    n: Int,
    tolerance: Float,
    tall: Float,
): FloatArray {
    val weights = FloatArray(n)
    weights[0] = Float.POSITIVE_INFINITY
    weights[n - 1] = Float.POSITIVE_INFINITY
    // Spans still to look at, as first index, last index and what a point found in it is worth at most: a list
    // instead of recursion, so a long line can't run out of stack.
    val spans = ArrayList<Int>()
    val worth = ArrayList<Float>()
    spans += 0
    spans += n - 1
    worth += Float.POSITIVE_INFINITY
    while (spans.isNotEmpty()) {
        val last = spans.removeAt(spans.size - 1)
        val first = spans.removeAt(spans.size - 1)
        val limit = worth.removeAt(worth.size - 1)
        var far = -1
        var farthest = tolerance
        for (i in first + 1 until last) {
            val d = distance(points[2 * i], points[2 * i + 1], points[2 * first], points[2 * first + 1], points[2 * last], points[2 * last + 1], tall)
            // Not a number is no distance: such a point is never the one kept.
            if (d > farthest) {
                farthest = d
                far = i
            }
        }
        if (far >= 0) {
            val weight = minOf(farthest, limit)
            weights[far] = weight
            spans += first
            spans += far
            worth += weight
            spans += far
            spans += last
            worth += weight
        }
    }
    return weights
}

/** How far the point p is from the piece of line from a to b, with a step down worth [tall] steps across. */
internal fun distance(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    tall: Float,
): Float {
    val dx = bx - ax
    val dy = (by - ay) * tall
    val qx = px - ax
    val qy = (py - ay) * tall
    val length = dx * dx + dy * dy
    val along = if (length <= 0f) 0f else ((qx * dx + qy * dy) / length).coerceIn(0f, 1f)
    return hypot(qx - along * dx, qy - along * dy)
}

// The eraser.

/** How far around the finger the eraser reaches, as a fraction of the page's width. */
const val ERASER_REACH = 0.025f

/** Whether the eraser at [x], [y], reaching [reach] around itself, touches [stroke]'s line at its drawn width. */
fun touches(
    stroke: Stroke,
    x: Float,
    y: Float,
    reach: Float = ERASER_REACH,
    tall: Float = PAGE_TALL,
): Boolean {
    val near = reach + stroke.width / 2f
    val b = stroke.bounds
    if (stroke.count == 0 || x < b[0] - near || x > b[2] + near || (y - b[3]) * tall > near || (b[1] - y) * tall > near) return false
    val p = stroke.points
    if (stroke.count == 1) return distance(x, y, p[0], p[1], p[0], p[1], tall) <= near
    for (i in 0 until stroke.count - 1) {
        if (distance(x, y, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3], tall) <= near) return true
    }
    return false
}

/** The places in [strokes] of those the eraser touches at [x], [y]: whole strokes go, never part of one. */
fun touched(
    strokes: List<Stroke>,
    x: Float,
    y: Float,
    reach: Float = ERASER_REACH,
    tall: Float = PAGE_TALL,
): List<Int> = strokes.indices.filter { touches(strokes[it], x, y, reach, tall) }

// Undo and redo.

/** One thing done to a page's ink that can be taken back. */
sealed interface InkEdit {
    /** A stroke was added at the end. */
    class Drew(
        val stroke: Stroke,
    ) : InkEdit

    /** Strokes were erased: each with the place it had, in ascending order. */
    class Erased(
        val gone: List<Pair<Int, Stroke>>,
    ) : InkEdit
}

/**
 * A page's strokes with what was [done] to them this session and what was [undone] and can be done again.
 * Drawing or erasing forgets what was undone, as everywhere. None of the history is saved.
 */
data class Ink(
    val strokes: List<Stroke> = emptyList(),
    val done: List<InkEdit> = emptyList(),
    val undone: List<InkEdit> = emptyList(),
) {
    val full get() = strokes.size >= AnnotationLimits.STROKES
    val canUndo get() = done.isNotEmpty()
    val canRedo get() = undone.isNotEmpty()

    /** With [stroke] added; null when the page is full, and nothing changes: old ink is never dropped for new. */
    fun drew(stroke: Stroke): Ink? = if (full) null else Ink(strokes + stroke, remembered(InkEdit.Drew(stroke)), emptyList())

    /** With the strokes at [places] erased, as one step; this as it is when none of them is a stroke. */
    fun erased(places: Collection<Int>): Ink {
        val gone = places.filter { it in strokes.indices }.distinct().sorted()
        if (gone.isEmpty()) return this
        val out = gone.toSet()
        return Ink(strokes.filterIndexed { i, _ -> i !in out }, remembered(InkEdit.Erased(gone.map { it to strokes[it] })), emptyList())
    }

    fun undo(): Ink {
        val last = done.lastOrNull() ?: return this
        return Ink(reverted(last), done.dropLast(1), undone + last)
    }

    fun redo(): Ink {
        val next = undone.lastOrNull() ?: return this
        // What undo left is what was there before the step, so a stroke drawn again can't go past the bound.
        val again =
            when (next) {
                is InkEdit.Drew -> strokes + next.stroke
                is InkEdit.Erased -> next.gone.map { it.first }.toSet().let { out -> strokes.filterIndexed { i, _ -> i !in out } }
            }
        return Ink(again, done + next, undone.dropLast(1))
    }

    private fun remembered(edit: InkEdit) = (done + edit).takeLast(AnnotationLimits.HISTORY)

    private fun reverted(edit: InkEdit): List<Stroke> =
        when (edit) {
            // The one drawn, wherever it is: the last of the strokes, as nothing was added after it.
            is InkEdit.Drew -> strokes.indexOfLast { it === edit.stroke }.let { at -> if (at < 0) strokes else strokes.filterIndexed { i, _ -> i != at } }
            is InkEdit.Erased -> strokes.toMutableList().also { back -> edit.gone.forEach { (at, stroke) -> back.add(at.coerceIn(0, back.size), stroke) } }
        }
}

// Notes and stickers.

/** A note or a sticker under a finger. */
sealed interface Scrap {
    val id: String

    data class Note(
        val note: StickyNote,
    ) : Scrap {
        override val id get() = note.id
    }

    data class Stuck(
        val sticker: Sticker,
    ) : Scrap {
        override val id get() = sticker.id
    }
}

/** A stroke as a page keeps it: every point on the page and on its step, and its width within bounds. */
fun Stroke.fitted() = Stroke(tool, role, unit(width.coerceIn(AnnotationLimits.MIN_WIDTH, AnnotationLimits.MAX_WIDTH)), FloatArray(points.size - points.size % 2) { unit(points[it]) })

/** A sticker's box as fractions of the page: left, top, right, bottom. It is square on screen, so less tall than wide in fractions. */
fun Sticker.box(tall: Float = PAGE_TALL) = floatArrayOf(x - size / 2f, y - size / 2f / tall, x + size / 2f, y + size / 2f / tall)

/** The topmost note or sticker at the fractions [x], [y] of the page, if any: stickers lie over notes. */
fun Annotations.scrapAt(
    x: Float,
    y: Float,
    tall: Float = PAGE_TALL,
): Scrap? {
    stickers.lastOrNull { s -> s.box(tall).let { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] } }?.let { return Scrap.Stuck(it) }
    return notes.sortedBy { it.z }.lastOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }?.let(Scrap::Note)
}

fun Annotations.scrap(id: String): Scrap? = notes.firstOrNull { it.id == id }?.let(Scrap::Note) ?: stickers.firstOrNull { it.id == id }?.let(Scrap::Stuck)

/** A note or sticker carried from one page to another: both pages after it, and its id on the one it went to. */
data class Crossed(
    val from: Annotations,
    val to: Annotations,
    val id: String,
)

/**
 * Carries the note or sticker [id] off the page [from] and onto [to] at [x], [y] of that page: a note's top
 * left corner, a sticker's centre. As when the owner moves one on its page, a note he carries over is his and
 * lies on top. It keeps its id unless [to] has one like it. Null, and nothing changed, if [from] has none such
 * or [to] already holds all it takes of them.
 */
fun carryScrap(
    from: Annotations,
    to: Annotations,
    id: String,
    x: Float,
    y: Float,
    now: Long,
): Crossed? =
    when (val scrap = from.scrap(id)) {
        null -> null
        is Scrap.Note -> {
            if (to.notes.size >= AnnotationLimits.NOTES) return null
            val there = if (to.scrap(id) == null) id else freshId(to, "n", now)
            val note = scrap.note.copy(id = there, x = x, y = y, author = Author.Owner, z = (to.notes.maxOfOrNull { it.z } ?: 0) + 1).fitted()
            Crossed(from.copy(notes = from.notes.filter { it.id != id }), to.copy(notes = to.notes + note), there)
        }
        is Scrap.Stuck -> {
            if (to.stickers.size >= AnnotationLimits.STICKERS) return null
            val there = if (to.scrap(id) == null) id else freshId(to, "s", now)
            val sticker = scrap.sticker.copy(id = there, x = x, y = y).fitted()
            Crossed(from.copy(stickers = from.stickers.filter { it.id != id }), to.copy(stickers = to.stickers + sticker), there)
        }
    }

/**
 * The pages a note or sticker in hand may go to from [on], the page it is on, in the order to ask them.
 * [showing] are the pages on screen, the one under the finger first. While its own page shows it goes only to
 * the page under the finger. Once its own has been turned away it goes to whichever page showing takes it,
 * so that it is never left out of sight while a page in sight has room for it.
 */
fun landings(
    on: String,
    showing: List<String>,
): List<String> =
    when {
        showing.firstOrNull() == on -> emptyList()
        on in showing -> showing.take(1)
        else -> showing
    }

/** A note of this size and place as a page keeps it: no smaller than can be read or taken hold of, and all of it on the page. */
fun StickyNote.fitted(): StickyNote {
    val w = unit(w.coerceIn(AnnotationLimits.MIN_NOTE, 1f))
    val h = unit(h.coerceIn(AnnotationLimits.MIN_NOTE / 2f, 1f))
    return copy(x = unit(x.coerceIn(0f, 1f - w)), y = unit(y.coerceIn(0f, 1f - h)), w = w, h = h)
}

/** A sticker as a page keeps it: its centre on the page, its size within bounds, turned by whole degrees under a full turn. */
fun Sticker.fitted() =
    copy(
        x = unit(x),
        y = unit(y),
        size = unit(size.coerceIn(AnnotationLimits.MIN_STICKER, AnnotationLimits.MAX_STICKER)),
        turn = ((turn % 360) + 360) % 360,
    )

/** How far a sticker turns at a time. */
const val TURN_STEP = 15

private val LINE_ENDS = Regex("\r\n?")

/** Characters that draw nothing but change what is seen: controls (a new line aside), and the marks that turn the writing direction round. */
private val UNSEEN = Regex("[\\p{Cc}&&[^\\n]]|[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
private val BLANK_LINES = Regex("\\n{3,}")

/**
 * What was typed as a note keeps it: plain text, with its line breaks, without characters that don't show, and no
 * longer than [max] characters. Nothing in it is ever read as markup or as anything but letters to draw.
 */
fun noteText(
    typed: String,
    max: Int = AnnotationLimits.NOTE_TEXT,
): String {
    val clean = typed.replace(LINE_ENDS, "\n").replace('\t', ' ').replace(UNSEEN, "").replace(BLANK_LINES, "\n\n")
    return if (clean.length <= max) clean else clean.take(if (clean[max - 1].isHighSurrogate()) max - 1 else max)
}

/** A new note's size, as fractions of the page. */
const val NOTE_WIDTH = 0.44f
const val NOTE_HEIGHT = 0.16f

/** A new sticker's size. */
const val STICKER_SIZE = 0.14f

/** An id nothing on [page] has, from [stem] and the time. */
fun freshId(
    page: Annotations,
    stem: String,
    now: Long,
): String {
    val used = page.notes.map { it.id }.toSet() + page.stickers.map { it.id }
    val base = stem + now.toString(36)
    if (base !in used) return base
    var n = 2
    while ("$base-$n" in used) n++
    return "$base-$n"
}

/** What came of an agent asking to leave a note. */
sealed interface NoteLeft {
    /**
     * The page with the note on it; [replaced] is the agent's own older note that made way for it, if one did.
     * [cells] are the cells of the page's grid the note lies on, and [clear] whether it lies on nothing else:
     * false only when the page had no clear place left and it had to overlap ([NOTE_OVERLAPS]).
     */
    data class Left(
        val page: Annotations,
        val note: StickyNote,
        val replaced: StickyNote?,
        val cells: Cell,
        val clear: Boolean,
    ) : NoteLeft

    /** Nothing changed, and why. */
    data class Refused(
        val reason: String,
    ) : NoteLeft
}

/**
 * Puts a note from an agent on [page]: plain text of at most [AnnotationLimits.AGENT_TEXT] characters, on top,
 * in a place of [board] clear of its tiles and of the notes and stickers there ([PageBoard.noteSpot]): near
 * the cell [near] (column, row) when one is asked for, and never on a tile because it was asked for. Only on a
 * page with no clear place left does it overlap, and then the answer says so ([NoteLeft.Left.clear]).
 * A page keeps [AnnotationLimits.AGENT_NOTES] of an agent's notes;
 * one more takes the place of the oldest of them, and so does one for a page with no room for another note,
 * or with no clear place for one where taking an own note away makes one. Only an agent's own untouched notes are ever replaced: never one the owner wrote, and never one of the
 * agent's that he has since edited or moved, which is his from then on. A page full of his notes takes none.
 */
fun leaveAgentNote(
    page: Annotations,
    text: String,
    now: Long,
    board: PageBoard = PageBoard(),
    near: Pair<Int, Int>? = null,
): NoteLeft {
    if (text.trim().length > AnnotationLimits.AGENT_TEXT) return NoteLeft.Refused("Note too long: keep it to ${AnnotationLimits.AGENT_TEXT} characters. Nothing was written.")
    val clean = noteText(text.trim(), AnnotationLimits.AGENT_TEXT).trim()
    if (clean.isEmpty()) return NoteLeft.Refused("Note text required.")
    if (near != null && (near.first !in 0 until board.columns || near.second !in 0 until board.rows)) {
        return NoteLeft.Refused("No such cell: the page's grid is ${board.columns} columns by ${board.rows} rows, counted from 0. Nothing was written.")
    }
    val own = page.notes.filter { it.author == Author.Agent }.sortedWith(compareBy({ it.at }, { it.z }))
    val due = if (own.size >= AnnotationLimits.AGENT_NOTES || page.notes.size >= AnnotationLimits.NOTES) own.firstOrNull() else null
    if (page.notes.size - (if (due == null) 0 else 1) >= AnnotationLimits.NOTES) return NoteLeft.Refused("That page has no room for another note. Nothing was written.")

    fun spot(without: StickyNote?): Spot {
        val scraps = page.copy(notes = page.notes.filter { it.id != without?.id }).scraps()
        return if (near == null) board.noteSpot(scraps) else board.noteSpotAt(scraps, near.first, near.second)
    }
    // A page with no clear place left has no room either: before anything is overlapped, one of the agent's
    // own makes way, the oldest whose going leaves the new note a clear place.
    val (replaced, spot) =
        spot(due).let { first ->
            if (!first.compromised || due != null) due to first else own.firstNotNullOfOrNull { old -> spot(old).takeIf { !it.compromised }?.let { old to it } } ?: (null to first)
        }
    val rest = page.notes.filter { it.id != replaced?.id }
    val note =
        StickyNote(
            id = freshId(page.copy(notes = rest), "n", now),
            x = spot.box.x,
            y = spot.box.y,
            w = spot.box.w,
            h = spot.box.h,
            text = clean,
            role = NoteRole.Alt,
            z = (rest.maxOfOrNull { it.z } ?: 0) + 1,
            author = Author.Agent,
            at = now,
        ).fitted()
    return NoteLeft.Left(page.copy(notes = rest + note), note, replaced, board.cells(note.box()), !spot.compromised)
}
