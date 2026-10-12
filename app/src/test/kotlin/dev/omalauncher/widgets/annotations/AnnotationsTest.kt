package dev.omalauncher.widgets.annotations

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.GridGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** The pure rules of what is free on a page: where it lands, how a line is thinned and erased, undo, and notes. */
class AnnotationsTest {
    /** The rooms a page is given: the Fold's cover, half of its inner screen, and a tall phone. */
    private val rooms = listOf(1248 to 1462, 1224 to 1339, 1080 to 2040)

    private fun area(
        width: Int,
        height: Int,
        rows: Int = 10,
    ) = GridGeometry(width, height, rows).let { PageArea(it.pageLeft, it.pageTop, it.pageWidth, it.pageHeight) }

    private fun stroke(vararg points: Float) = Stroke(InkTool.Pen, InkRole.Pen, 0.006f, points)

    // Fractions (PRD AC-24).

    @Test fun whatIsSavedOnOneDisplayLandsAtTheSameFractionOnAnother() {
        for ((fw, fh) in rooms) for ((tw, th) in rooms) {
            val from = area(fw, fh)
            val to = area(tw, th)
            for ((ax, ay) in listOf(0f to 0f, 0.25f to 0.5f, 0.3333f to 0.8125f, 1f to 1f)) {
                // A finger at this pixel of the one page: saved, then shown on the other, then read back there.
                val x = unit(from.across(from.x(ax)))
                val y = unit(from.down(from.y(ay)))
                assertEquals(x, unit(to.across(to.x(x))), 0f)
                assertEquals(y, unit(to.down(to.y(y))), 0f)
                // And it is the fraction the finger was at, to the step fractions are kept in.
                assertEquals(ax, x, 1f / UNITS)
                assertEquals(ay, y, 1f / UNITS)
            }
        }
    }

    @Test fun aPointOnATileStaysOnThatTileOnEveryDisplay() {
        // The corner of a tile on the cover is the corner of the same tile on an inner half: the page is one shape.
        val cells = listOf(Cell(0, 0, 1, 1), Cell(2, 3, 3, 2), Cell(5, 9, 1, 1))
        for ((fw, fh) in rooms) for ((tw, th) in rooms) {
            val from = GridGeometry(fw, fh, 10)
            val to = GridGeometry(tw, th, 10)
            val a = area(fw, fh)
            val b = area(tw, th)
            for (cell in cells) {
                val there = from.bounds(cell)
                val here = to.bounds(cell)
                assertEquals(here.left, b.x(unit(a.across(there.left))), 0.2f)
                assertEquals(here.top, b.y(unit(a.down(there.top))), 0.2f)
                assertEquals(here.right, b.x(unit(a.across(there.right))), 0.2f)
                assertEquals(here.bottom, b.y(unit(a.down(there.bottom))), 0.2f)
            }
        }
    }

    @Test fun aNoteAStickerAndAStrokeKeepTheirShareOfThePage() {
        val cover = area(1248, 1462)
        val inner = area(1224, 1339)
        val note = StickyNote("n1", 0.1f, 0.2f, 0.44f, 0.16f, "milk").fitted()
        val sticker = Sticker("s1", StickerKind.Star, 0.7f, 0.6f, 0.14f, 30).fitted()
        for ((from, to) in listOf(cover to inner, inner to cover)) {
            // The note covers the same part of the page: its edges, as shares of the page's own size, don't move.
            assertEquals(from.length(note.w) / from.width, to.length(note.w) / to.width, 1e-6f)
            assertEquals((from.y(note.y + note.h) - from.top) / from.height, (to.y(note.y + note.h) - to.top) / to.height, 1e-6f)
            // A sticker stays square, and as big against the page.
            assertEquals(from.length(sticker.size) / from.width, to.length(sticker.size) / to.width, 1e-6f)
            assertEquals(from.x(sticker.x) - from.left, (to.x(sticker.x) - to.left) * from.width / to.width, 0.01f)
            // A stroke's width scales with the page, as the page's tiles do.
            assertEquals(from.length(0.006f) / from.width, to.length(0.006f) / to.width, 1e-6f)
        }
        // The page is one shape on all of them, which is what makes a fraction down mean the same everywhere.
        for ((w, h) in rooms) assertEquals(PAGE_TALL, area(w, h).height / area(w, h).width, 1e-4f)
    }

    @Test fun fractionsStayOnThePageAndOnTheirStep() {
        assertEquals(0f, unit(-3f), 0f)
        assertEquals(1f, unit(7f), 0f)
        assertEquals(0f, unit(Float.NaN), 0f)
        assertEquals(1f, unit(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.1235f, unit(0.12346f), 0f)
        // Kept as it is: saving and reading it again changes nothing.
        for (i in 0..UNITS step 7) assertEquals(i / UNITS.toFloat(), unit(unit(i / UNITS.toFloat())), 0f)
    }

    // Thinning.

    @Test fun aStraightLineKeepsOnlyItsEnds() {
        val line = FloatArray(200) { i -> if (i % 2 == 0) i / 400f else 0.5f }
        assertEquals(listOf(0f, 0.5f, 198 / 400f, 0.5f), simplify(line, STROKE_TOLERANCE).toList())
    }

    @Test fun cornersAndEndsAreKeptAndNothingStraysFurtherThanTheTolerance() {
        // An L: along the top, then down.
        val points = ArrayList<Float>()
        for (i in 0..50) points += listOf(0.1f + i * 0.01f, 0.2f)
        for (i in 1..50) points += listOf(0.6f, 0.2f + i * 0.01f)
        val thin = simplify(points.toFloatArray(), STROKE_TOLERANCE)
        assertEquals(listOf(0.1f, 0.2f, 0.6f, 0.2f, 0.6f, 0.7f), thin.toList())
        // A wave: every point left out lies within the tolerance of what is left.
        val wave = FloatArray(2 * 400) { i -> if (i % 2 == 0) (i / 2) / 400f else 0.5f + 0.1f * sin(i / 2 / 20f) }
        val kept = simplify(wave, 0.002f)
        assertTrue(kept.size < wave.size / 4)
        assertEquals(wave[0], kept[0], 0f)
        assertEquals(wave[wave.size - 1], kept[kept.size - 1], 0f)
        for (i in 0 until 400) {
            val near = (0 until kept.size / 2 - 1).minOf { k -> distance(wave[2 * i], wave[2 * i + 1], kept[2 * k], kept[2 * k + 1], kept[2 * k + 2], kept[2 * k + 3], PAGE_TALL) }
            assertTrue("point $i strays $near", near <= 0.002f + 1e-5f)
        }
    }

    @Test fun aLineNeverKeepsMoreThanTheBound() {
        // A scribble no tolerance thins much: every other point far from its neighbours.
        val scribble = FloatArray(2 * 5000) { i -> if (i % 2 == 0) ((i / 2) % 97) / 97f else ((i / 2) % 89) / 89f }
        val kept = simplify(scribble, STROKE_TOLERANCE)
        assertTrue(kept.size / 2 <= AnnotationLimits.POINTS)
        assertTrue(kept.size >= 4)
        assertEquals(scribble[0], kept[0], 0f)
        assertEquals(scribble[scribble.size - 1], kept[kept.size - 1], 0f)
        assertEquals(10, simplify(scribble, 0f, max = 5).size)
        // Numbers that aren't numbers still come back bounded.
        val broken = FloatArray(2 * 1000) { Float.NaN }
        assertTrue(simplify(broken, STROKE_TOLERANCE).size / 2 <= AnnotationLimits.POINTS)
    }

    @Test fun shortLinesComeBackAsTheyAre() {
        assertEquals(0, simplify(FloatArray(0), STROKE_TOLERANCE).size)
        assertEquals(listOf(0.5f, 0.5f), simplify(floatArrayOf(0.5f, 0.5f), STROKE_TOLERANCE).toList())
        assertEquals(listOf(0.1f, 0.1f, 0.2f, 0.2f), simplify(floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), STROKE_TOLERANCE).toList())
        // A dot drawn as many points on one spot.
        assertEquals(listOf(0.3f, 0.3f, 0.3f, 0.3f), simplify(FloatArray(40) { 0.3f }, STROKE_TOLERANCE).toList())
        // The array given is never the one handed back to be changed.
        val two = floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)
        assertFalse(simplify(two, STROKE_TOLERANCE) === two)
    }

    // The eraser.

    @Test fun theEraserTakesWholeStrokesItTouchesAndNoOthers() {
        val across = stroke(0.1f, 0.5f, 0.9f, 0.5f)
        val down = stroke(0.5f, 0.1f, 0.5f, 0.9f)
        val dot = stroke(0.2f, 0.2f)
        val strokes = listOf(across, down, dot)
        assertEquals(listOf(0, 1), touched(strokes, 0.5f, 0.5f))
        assertEquals(listOf(0), touched(strokes, 0.3f, 0.5f))
        // The middle of a long piece of line counts, not only its points.
        assertEquals(listOf(1), touched(strokes, 0.5f, 0.3f))
        assertEquals(listOf(2), touched(strokes, 0.21f, 0.2f))
        assertEquals(emptyList<Int>(), touched(strokes, 0.8f, 0.8f))
        // Past the end of a line it isn't touched.
        assertEquals(emptyList<Int>(), touched(strokes, 0.97f, 0.5f))
    }

    @Test fun theEraserReachesAsFarDownAsAcrossOnThePage() {
        val line = stroke(0.1f, 0.5f, 0.9f, 0.5f)
        val reach = ERASER_REACH + line.width / 2f
        // Down is in fractions of the page's height, which is taller than it is wide: the same distance is a smaller fraction.
        assertTrue(touches(line, 0.5f, 0.5f + (reach - 0.001f) / PAGE_TALL))
        assertFalse(touches(line, 0.5f, 0.5f + (reach + 0.001f) / PAGE_TALL))
        val upright = stroke(0.5f, 0.1f, 0.5f, 0.9f)
        assertTrue(touches(upright, 0.5f + reach - 0.001f, 0.5f))
        assertFalse(touches(upright, 0.5f + reach + 0.001f, 0.5f))
        // A wide stroke is touched from further off than a thin one.
        val wide = Stroke(InkTool.Highlighter, InkRole.Highlighter, 0.04f, floatArrayOf(0.1f, 0.5f, 0.9f, 0.5f))
        assertTrue(touches(wide, 0.5f, 0.5f + 0.04f / PAGE_TALL))
        assertFalse(touches(line, 0.5f, 0.5f + 0.04f / PAGE_TALL))
        assertFalse(touches(Stroke(InkTool.Pen, InkRole.Pen, 0.006f, FloatArray(0)), 0.5f, 0.5f))
    }

    // Undo and redo.

    @Test fun undoTakesBackTheLastStepAndRedoDoesItAgain() {
        val a = stroke(0.1f, 0.1f, 0.2f, 0.2f)
        val b = stroke(0.3f, 0.3f, 0.4f, 0.4f)
        val c = stroke(0.5f, 0.5f, 0.6f, 0.6f)
        var ink = Ink().drew(a)!!.drew(b)!!.drew(c)!!
        assertEquals(listOf(a, b, c), ink.strokes)
        ink = ink.undo()
        assertEquals(listOf(a, b), ink.strokes)
        assertTrue(ink.canRedo)
        ink = ink.redo()
        assertEquals(listOf(a, b, c), ink.strokes)
        assertFalse(ink.canRedo)
        // Erasing two is one step, and they come back where they were.
        ink = ink.erased(listOf(2, 0))
        assertEquals(listOf(b), ink.strokes)
        ink = ink.undo()
        assertEquals(listOf(a, b, c), ink.strokes)
        assertSame(a, ink.strokes[0])
        ink = ink.redo()
        assertEquals(listOf(b), ink.strokes)
        // All the way back, and no further.
        repeat(10) { ink = ink.undo() }
        assertEquals(emptyList<Stroke>(), ink.strokes)
        assertFalse(ink.canUndo)
        assertSame(ink, ink.undo())
        repeat(10) { ink = ink.redo() }
        assertEquals(listOf(b), ink.strokes)
        assertSame(ink, ink.redo())
    }

    @Test fun aNewStepForgetsWhatWasUndone() {
        val a = stroke(0.1f, 0.1f)
        val b = stroke(0.2f, 0.2f)
        val ink = Ink().drew(a)!!.undo().drew(b)!!
        assertFalse(ink.canRedo)
        assertEquals(listOf(b), ink.redo().strokes)
        assertEquals(emptyList<Stroke>(), ink.undo().strokes)
        // Erasing nothing is no step.
        assertSame(ink, ink.erased(emptyList()))
        assertSame(ink, ink.erased(listOf(-1, 7)))
    }

    @Test fun twoStrokesThatLookAlikeAreStillTwo() {
        val a = stroke(0.1f, 0.1f, 0.2f, 0.2f)
        val twin = stroke(0.1f, 0.1f, 0.2f, 0.2f)
        assertEquals(a, twin)
        val ink = Ink(listOf(a)).drew(twin)!!
        assertEquals(2, ink.strokes.size)
        assertSame(a, ink.undo().strokes.single())
    }

    @Test fun inkLoadedFromAFileCanBeErasedAndBroughtBackButNotUndoneAway() {
        val a = stroke(0.1f, 0.1f)
        val b = stroke(0.2f, 0.2f)
        val loaded = Ink(listOf(a, b))
        assertFalse(loaded.canUndo)
        assertEquals(listOf(a, b), loaded.undo().strokes)
        assertEquals(listOf(a, b), loaded.erased(listOf(0)).undo().strokes)
    }

    @Test fun aFullPageTakesNoMoreInkAndKeepsWhatItHas() {
        val old = (0 until AnnotationLimits.STROKES).map { stroke(it / 4000f, 0.5f) }
        val full = Ink(old)
        assertTrue(full.full)
        assertNull(full.drew(stroke(0.9f, 0.9f)))
        // One erased, one fits again; undoing the eraser at the bound brings back only what was there.
        val one = full.erased(listOf(0))
        val again = one.drew(stroke(0.9f, 0.9f))!!
        assertEquals(AnnotationLimits.STROKES, again.strokes.size)
        assertNull(again.drew(stroke(0.8f, 0.8f)))
        assertEquals(old, one.undo().strokes)
        assertEquals(AnnotationLimits.STROKES, again.undo().redo().strokes.size)
        assertEquals(2_000, AnnotationLimits.STROKES)
    }

    @Test fun undoGoesBackOnlySoFar() {
        var ink = Ink()
        repeat(AnnotationLimits.HISTORY + 20) { ink = ink.drew(stroke(it / 1000f, 0.5f))!! }
        var steps = 0
        while (ink.canUndo) {
            ink = ink.undo()
            steps++
        }
        assertEquals(AnnotationLimits.HISTORY, steps)
        assertEquals(20, ink.strokes.size)
    }

    // Notes and stickers.

    @Test fun aNoteStaysOnThePageAndBigEnoughToHold() {
        val off = StickyNote("n", 0.9f, 0.95f, 0.44f, 0.16f, "x").fitted()
        assertEquals(0.56f, off.x, 1e-4f)
        assertEquals(0.84f, off.y, 1e-4f)
        val tiny = StickyNote("n", 0.5f, 0.5f, 0f, 0f, "x").fitted()
        assertEquals(AnnotationLimits.MIN_NOTE, tiny.w, 1e-4f)
        assertTrue(tiny.h > 0f)
        val huge = StickyNote("n", -1f, -1f, 9f, 9f, "x").fitted()
        assertEquals(listOf(0f, 0f, 1f, 1f), listOf(huge.x, huge.y, huge.w, huge.h))
        val sticker = Sticker("s", StickerKind.Heart, 2f, -1f, 9f, -30).fitted()
        assertEquals(listOf(1f, 0f, AnnotationLimits.MAX_STICKER), listOf(sticker.x, sticker.y, sticker.size))
        assertEquals(330, sticker.turn)
        assertEquals(0, Sticker("s", StickerKind.Heart, 0.5f, 0.5f, 0.1f, 720).fitted().turn)
    }

    @Test fun theTopmostThingUnderAFingerIsTheOneTaken() {
        val under = StickyNote("n1", 0.1f, 0.1f, 0.4f, 0.2f, "under", z = 1)
        val over = StickyNote("n2", 0.3f, 0.2f, 0.4f, 0.2f, "over", z = 5)
        val sticker = Sticker("s1", StickerKind.Star, 0.35f, 0.25f, 0.1f)
        val page = Annotations(notes = listOf(over, under), stickers = listOf(sticker))
        assertEquals("n1", page.scrapAt(0.15f, 0.15f)?.id)
        // Where the two notes overlap, the one higher in the pile.
        assertEquals("n2", page.scrapAt(0.45f, 0.25f)?.id)
        // A sticker lies over both.
        assertEquals("s1", page.scrapAt(0.35f, 0.25f)?.id)
        assertNull(page.scrapAt(0.9f, 0.9f))
        // A sticker is square on screen: it reaches less far down, in fractions, than across.
        assertEquals("s1", page.scrapAt(0.35f + 0.049f, 0.25f)?.id)
        assertEquals("n2", page.scrapAt(0.35f, 0.25f + 0.049f)?.id)
        assertEquals("s1", page.scrap("s1")?.id)
        assertNull(page.scrap("nope"))
    }

    @Test fun aNotesTextIsPlainAndBounded() {
        assertEquals("milk\neggs", noteText("milk\r\neggs"))
        assertEquals("a b", noteText("a\tb"))
        // Characters that don't show, and the ones that turn the writing round, are gone; letters of any script stay.
        assertEquals("abcd", noteText("a\u0000b‮c‏d\u0007"))
        assertEquals("日本語 café ☕", noteText("日本語 café ☕"))
        assertEquals("a\n\nb", noteText("a\n\n\n\n\nb"))
        // Markup is only letters.
        assertEquals("<b>hi</b> [x](y) \$(rm)", noteText("<b>hi</b> [x](y) \$(rm)"))
        assertEquals(AnnotationLimits.NOTE_TEXT, noteText("x".repeat(5000)).length)
        // Never cut through the middle of a character that takes two.
        val cut = noteText("x".repeat(9) + "😀", 10)
        assertEquals("x".repeat(9), cut)
        assertEquals("", noteText(""))
    }

    @Test fun newThingsCascadeSoNoneLiesExactlyOnAnother() {
        val taken = ArrayList<Pair<Float, Float>>()
        repeat(30) {
            val spot = cascade(taken, NOTE_WIDTH, NOTE_HEIGHT)
            assertTrue("$spot is taken", taken.none { abs(it.first - spot.first) < 0.02f && abs(it.second - spot.second) < 0.02f })
            // All of it is on the page.
            assertTrue(spot.first >= 0f && spot.first + NOTE_WIDTH <= 1.0001f)
            assertTrue(spot.second >= 0f && spot.second + NOTE_HEIGHT <= 1.0001f)
            taken += spot
        }
        // The first is at the top-left, and the next a little right of and below it.
        assertEquals(0.06f to 0.06f, taken[0])
        assertEquals(0.1f to 0.11f, taken[1])
        // A place that was freed is the next one given.
        assertEquals(taken[3], cascade(taken - taken[3], NOTE_WIDTH, NOTE_HEIGHT))
        // With every place taken it still answers, on the page.
        val all = ArrayList<Pair<Float, Float>>()
        repeat(200) { all += cascade(all, NOTE_WIDTH, NOTE_HEIGHT) }
        assertTrue(all.all { it.first in 0f..1f - NOTE_WIDTH + 0.0001f && it.second in 0f..1f - NOTE_HEIGHT + 0.0001f })
    }

    // The agent's note (PRD AC-29).

    private fun left(
        page: Annotations,
        text: String,
        now: Long = 1_000,
    ) = leaveAgentNote(page, text, now) as NoteLeft.Left

    @Test fun anAgentsNoteIsPlainBoundedTextOnTop() {
        val mine = StickyNote("mine", 0.06f, 0.06f, 0.44f, 0.16f, "call mum", z = 7)
        val result = left(Annotations(notes = listOf(mine)), "  Booked the‮ table for 8.\r\n<b>Done</b>  ")
        assertEquals("Booked the table for 8.\n<b>Done</b>", result.note.text)
        assertEquals(Author.Agent, result.note.author)
        assertEquals(NoteRole.Alt, result.note.role)
        assertEquals(8, result.note.z)
        assertEquals(1_000L, result.note.at)
        assertNull(result.replaced)
        // Not exactly on the owner's note, which is where the cascade starts.
        assertEquals(0.1f to 0.11f, result.note.x to result.note.y)
        assertEquals(listOf(mine, result.note), result.page.notes)

        assertTrue(leaveAgentNote(Annotations(), "", 1) is NoteLeft.Refused)
        assertTrue(leaveAgentNote(Annotations(), " \n\t ", 1) is NoteLeft.Refused)
        assertTrue(leaveAgentNote(Annotations(), "‮\u0000", 1) is NoteLeft.Refused)
        assertTrue(leaveAgentNote(Annotations(), "x".repeat(AnnotationLimits.AGENT_TEXT + 1), 1) is NoteLeft.Refused)
        assertEquals(AnnotationLimits.AGENT_TEXT, left(Annotations(), "x".repeat(AnnotationLimits.AGENT_TEXT)).note.text.length)
        // Blank space around it doesn't count against the length.
        assertEquals("ok", left(Annotations(), " ".repeat(500) + "ok" + "\n".repeat(500)).note.text)
    }

    @Test fun anAgentKeepsAHandfulOfNotesAndReplacesOnlyItsOwnOldest() {
        val mine = (1..3).map { StickyNote("mine$it", 0.5f, 0.1f * it, 0.3f, 0.1f, "mine $it", z = it, at = it.toLong()) }
        var page = Annotations(notes = mine)
        val made = ArrayList<StickyNote>()
        for (i in 1..AnnotationLimits.AGENT_NOTES) {
            val result = left(page, "did $i", now = 100L + i)
            assertNull(result.replaced)
            page = result.page
            made += result.note
        }
        assertEquals(3 + AnnotationLimits.AGENT_NOTES, page.notes.size)
        // One more: the oldest of the agent's makes way, and nothing of the owner's is touched.
        val fifth = left(page, "did 5", now = 200)
        assertEquals(made[0], fifth.replaced)
        assertEquals(3 + AnnotationLimits.AGENT_NOTES, fifth.page.notes.size)
        assertEquals(mine, fifth.page.notes.filter { it.author == Author.Owner })
        assertEquals(listOf("did 2", "did 3", "did 4", "did 5"), fifth.page.notes.filter { it.author == Author.Agent }.map { it.text })
        // No two of them lie exactly on each other.
        val spots = fifth.page.notes.filter { it.author == Author.Agent }.map { it.x to it.y }
        assertEquals(spots.size, spots.toSet().size)
        // Ids stay unique even when two are left in the same millisecond.
        val twice = left(left(Annotations(), "a", now = 5).page, "b", now = 5)
        assertEquals(2, twice.page.notes.map { it.id }.toSet().size)
    }

    @Test fun aNoteTheOwnerMadeHisIsNeverReplaced() {
        // Four of the agent's, the oldest of which the owner has since edited: it is his now.
        val adopted = StickyNote("a1", 0.1f, 0.1f, 0.4f, 0.16f, "edited by me", author = Author.Owner, at = 1)
        val agents = (2..4).map { StickyNote("a$it", 0.1f, 0.1f * it, 0.4f, 0.16f, "did $it", author = Author.Agent, at = it.toLong(), z = it) }
        val page = Annotations(notes = listOf(adopted) + agents)
        val next = left(page, "did 5", now = 50)
        assertNull(next.replaced)
        val after = left(next.page, "did 6", now = 60)
        assertEquals("a2", after.replaced?.id)
        assertTrue(adopted in after.page.notes)
    }

    @Test fun aPageFullOfTheOwnersNotesTakesNoneFromAnAgent() {
        val mine = (0 until AnnotationLimits.NOTES).map { StickyNote("m$it", 0.1f, 0.03f * it, 0.3f, 0.1f, "mine", z = it) }
        val refused = leaveAgentNote(Annotations(notes = mine), "did it", 1)
        assertTrue(refused is NoteLeft.Refused)
        // Full, but one of them is the agent's own: that one makes way, and the count stays at the bound.
        val withOne = mine.dropLast(1) + StickyNote("old", 0.2f, 0.2f, 0.3f, 0.1f, "earlier", author = Author.Agent, at = 3)
        val swapped = left(Annotations(notes = withOne), "did it", now = 9)
        assertEquals("old", swapped.replaced?.id)
        assertEquals(AnnotationLimits.NOTES, swapped.page.notes.size)
        assertEquals(mine.dropLast(1), swapped.page.notes.filter { it.author == Author.Owner })
    }

    @Test fun anAgentsNoteLeavesInkAndStickersAlone() {
        val ink = listOf(stroke(0.1f, 0.1f, 0.2f, 0.2f))
        val stickers = listOf(Sticker("s1", StickerKind.Star, 0.5f, 0.5f, 0.14f))
        val result = left(Annotations(strokes = ink, stickers = stickers), "done")
        assertSame(ink, result.page.strokes)
        assertSame(stickers, result.page.stickers)
        // Not a sticker's id either.
        assertFalse(result.note.id in stickers.map { it.id })
    }
}
