package dev.omalauncher.ui

import androidx.compose.ui.geometry.Offset
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.AnnotationFiles
import dev.omalauncher.widgets.annotations.AnnotationLimits
import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.Author
import dev.omalauncher.widgets.annotations.InkRole
import dev.omalauncher.widgets.annotations.NoteLeft
import dev.omalauncher.widgets.annotations.InkTool
import dev.omalauncher.widgets.annotations.PageArea
import dev.omalauncher.widgets.annotations.Scrap
import dev.omalauncher.widgets.annotations.Sticker
import dev.omalauncher.widgets.annotations.StickerKind
import dev.omalauncher.widgets.annotations.StickyNote
import dev.omalauncher.widgets.annotations.scrapAt
import dev.omalauncher.widgets.annotations.Stroke
import dev.omalauncher.widgets.layout.GridGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Whose a touch is once pages can be drawn on and hold notes (PRD AC-22, AC-23), and what the pen makes of a finger. */
class InkGesturesTest {
    private val slop = 10f

    private val modes =
        listOf(false, true).flatMap { editing ->
            listOf(false, true).flatMap { folder -> listOf(false, true).map { drawing -> HomeModes(editing, folder, drawing) } }
        }

    private fun touch(
        start: TouchStart,
        modes: HomeModes,
        note: Boolean = false,
    ) = GestureArbiter().also { it.down(start, modes, note) }

    private val drags = listOf(40f to 0f, -40f to 0f, 0f to -40f, 0f to 40f, 30f to -30f, 3f to 3f)

    // The arbiter.

    @Test fun inDrawingModeOneFingerDrawsWhateverItLandsOn() {
        for (start in TouchStart.entries) for (modes in modes.filter { it.drawing }) for (note in listOf(false, true)) for ((dx, dy) in drags) {
            val arbiter = touch(start, modes, note)
            assertEquals(Owner.Draw, arbiter.owner)
            // No page turns, no app list comes up, no tile or note is lifted, carried or resized, and no menu opens.
            assertEquals("$start $modes", Owner.Draw, arbiter.moved(dx, dy, 0f, slop))
            assertEquals("$start $modes", Owner.Draw, arbiter.longPress())
            assertEquals("$start $modes", Owner.Draw, arbiter.moved(dx * 3, dy * 3, 0f, slop))
        }
    }

    @Test fun inDrawingModeTwoFingersStillPinch() {
        for (start in TouchStart.entries) for (modes in modes.filter { it.drawing }) for (spread in listOf(-30f, 30f)) {
            // Landing together, and after the first has already drawn some way.
            val together = touch(start, modes).also { it.pointers(2) }
            assertEquals(Owner.Pinch, together.moved(0f, 0f, spread, slop))
            val drawn = touch(start, modes).also { it.moved(80f, 20f, 0f, slop) }
            drawn.pointers(2)
            assertEquals(Owner.Pinch, drawn.moved(0f, 0f, spread, slop))
            // And it stays the pinch's when one of them lifts.
            assertEquals(Owner.Pinch, drawn.pointers(1))
            assertEquals(Owner.Pinch, drawn.moved(90f, 0f, 0f, slop))
        }
    }

    @Test fun outsideDrawingModeNothingDraws() {
        for (start in TouchStart.entries) for (modes in modes.filter { !it.drawing }) for (note in listOf(false, true)) {
            assertFalse(touch(start, modes, note).owner == Owner.Draw)
            assertFalse(touch(start, modes, note).longPress() == Owner.Draw)
            for ((dx, dy) in drags) {
                val arbiter = touch(start, modes, note)
                assertFalse("$start $modes", arbiter.moved(dx, dy, 0f, slop) == Owner.Draw)
                assertFalse("$start $modes", arbiter.longPress() == Owner.Draw)
                arbiter.pointers(2)
                assertFalse("$start $modes", arbiter.moved(dx, dy, 30f, slop) == Owner.Draw)
            }
        }
    }

    @Test fun withNoNoteUnderTheFingerNothingIsEverANotes() {
        // Home as it was: every touch that isn't on a note goes exactly where it went.
        for (start in TouchStart.entries) for (modes in modes) for ((dx, dy) in drags) {
            val arbiter = touch(start, modes)
            assertFalse(arbiter.owner == Owner.Note)
            assertFalse(arbiter.moved(dx, dy, 0f, slop) == Owner.Note)
            assertFalse(arbiter.longPress() == Owner.Note)
            assertFalse(touch(start, modes).longPress() == Owner.Note)
        }
    }

    @Test fun aNoteIsLiftedByALongPressAndByNothingElse() {
        val resting = touch(TouchStart.Tile, HomeModes(), note = true)
        assertEquals(Owner.Undecided, resting.owner)
        // Within the slop nothing is decided: a tap is still the note's own.
        assertEquals(Owner.Undecided, resting.moved(4f, 4f, 0f, slop))
        assertEquals(Owner.Note, resting.longPress())
        // Carried from there on, wherever the finger goes.
        assertEquals(Owner.Note, resting.moved(200f, -300f, 0f, slop))
    }

    @Test fun aNotesDragDoesNotStartFromASwipe() {
        // Sideways across a note turns the page; upwards opens the app list and downwards the search, as from an app.
        for ((dx, dy, owner) in listOf(Triple(40f, 5f, Owner.Pager), Triple(-40f, -5f, Owner.Pager), Triple(3f, -40f, Owner.Drawer), Triple(3f, 40f, Owner.Search))) {
            val swipe = touch(TouchStart.Tile, HomeModes(), note = true)
            assertEquals(owner, swipe.moved(dx, dy, 0f, slop))
            // However long the finger then rests, the note stays where it is.
            assertEquals(owner, swipe.longPress())
            assertEquals(owner, swipe.moved(dx * 4, dy * 4, 0f, slop))
            assertEquals(owner, swipe.longPress())
        }
        // The same touches with no note there go the same way: a note is in no swipe's way.
        for ((dx, dy) in drags) {
            assertEquals(touch(TouchStart.Tile, HomeModes()).moved(dx, dy, 0f, slop), touch(TouchStart.Tile, HomeModes(), note = true).moved(dx, dy, 0f, slop))
        }
    }

    @Test fun twoFingersNeverLiftANoteAndAPinchTakesOneNotYetMoved() {
        val two = touch(TouchStart.Tile, HomeModes(), note = true).also { it.pointers(2) }
        assertEquals(Owner.Undecided, two.longPress())
        two.pointers(1)
        assertEquals(Owner.Undecided, two.longPress())
        // Lifted but not moved: a second finger makes a pinch of it, and the note stays put.
        val lifted = touch(TouchStart.Tile, HomeModes(), note = true).also { it.longPress() }
        lifted.pointers(2)
        assertEquals(Owner.Pinch, lifted.moved(0f, 0f, -30f, slop))
        // Once it has moved under the finger it isn't dropped for a second one.
        val carried = touch(TouchStart.Tile, HomeModes(), note = true).also { it.longPress() }
        assertEquals(Owner.Note, carried.moved(60f, 0f, 0f, slop))
        carried.pointers(2)
        assertEquals(Owner.Note, carried.moved(0f, 0f, -60f, slop))
        carried.pointers(1)
        assertEquals(Owner.Note, carried.moved(90f, 10f, 0f, slop))
        carried.up()
        assertEquals(Owner.Undecided, carried.owner)
        // And the touch after it is no note's.
        assertEquals(Owner.Arrange, GestureArbiter().also { it.down(TouchStart.Tile, HomeModes()) }.longPress())
    }

    @Test fun aSelectedNoteIsCarriedResizedOrLetGoLikeASelectedTile() {
        val editing = HomeModes(editing = true)
        assertEquals(Owner.Note, touch(TouchStart.Selected, editing, note = true).owner)
        assertEquals(Owner.Resize, touch(TouchStart.Handle, editing, note = true).owner)
        assertEquals(Owner.Buttons, touch(TouchStart.Bar, editing, note = true).owner)
        // Anywhere else lets it go, and does nothing more.
        for (start in listOf(TouchStart.Page, TouchStart.Tile, TouchStart.Widget)) assertEquals(Owner.Editor, touch(start, editing, note = true).owner)
        // A selected tile is still a tile's.
        assertEquals(Owner.Arrange, touch(TouchStart.Selected, editing).owner)
        // An open folder is over everything, notes too.
        assertEquals(Owner.Editor, touch(TouchStart.Selected, HomeModes(editing = true, folderOpen = true), note = true).owner)
    }

    // The pen.

    private val dir = Files.createTempDirectory("ink").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun marks(vararg pages: String = arrayOf("p1")): Marks {
        val book = AnnotationBook(AnnotationFiles(dir), background = { it() }, foreground = { it() }, defer = { _, _ -> })
        book.keep(pages.toSet())
        pages.forEach(book::open)
        return Marks().also { it.book = book }
    }

    /** The cover's page, and half of the inner screen's. */
    private val cover = GridGeometry(1248, 1462, 10).let { PageArea(it.pageLeft, it.pageTop, it.pageWidth, it.pageHeight) }
    private val inner = GridGeometry(1224, 1339, 10).let { PageArea(it.pageLeft, it.pageTop, it.pageWidth, it.pageHeight) }

    private fun at(
        area: PageArea,
        x: Float,
        y: Float,
    ) = Offset(area.x(x), area.y(y))

    @Test fun aStrokeIsKeptAsFractionsOfThePageItWasDrawnOn() {
        val marks = marks()
        assertTrue(marks.begin("p1", cover, at(cover, 0.2f, 0.3f), 8f))
        marks.extend(at(cover, 0.5f, 0.3f))
        marks.extend(at(cover, 0.5f, 0.7f))
        marks.finish()
        val stroke = marks.book!!.on("p1").strokes.single()
        assertEquals(listOf(0.2f, 0.3f, 0.5f, 0.3f, 0.5f, 0.7f), stroke.points.toList())
        assertEquals(InkTool.Pen, stroke.tool)
        assertEquals(InkRole.Pen, stroke.role)
        // Its width is a share of the page's, so it scales with the page.
        assertEquals(8f / cover.width, stroke.width, 1f / 10_000)
        assertNull(marks.live.page)
        // The same fingers' path on the inner screen's page gives the same stroke.
        val other = marks("p2")
        other.begin("p2", inner, at(inner, 0.2f, 0.3f), 8f * inner.width / cover.width)
        other.extend(at(inner, 0.5f, 0.3f))
        other.extend(at(inner, 0.5f, 0.7f))
        other.finish()
        assertEquals(stroke, other.book!!.on("p2").strokes.single())
    }

    @Test fun theToolInHandDecidesWhatTheStrokeIs() {
        val marks = marks()
        marks.second = true
        marks.begin("p1", cover, at(cover, 0.1f, 0.1f), 8f)
        marks.finish()
        marks.tool = DrawTool.Highlighter
        marks.begin("p1", cover, at(cover, 0.2f, 0.2f), 8f)
        marks.extend(at(cover, 0.4f, 0.2f))
        marks.finish()
        val (dot, wide) = marks.book!!.on("p1").strokes
        // A tap is a dot.
        assertEquals(1, dot.count)
        assertEquals(InkRole.Second, dot.role)
        assertEquals(InkTool.Highlighter, wide.tool)
        assertEquals(InkRole.Highlighter, wide.role)
        assertEquals(dot.width * HIGHLIGHTER_WIDE, wide.width, 5f / 10_000)
    }

    @Test fun aLineIsThinnedWhileItIsDrawnAndStaysOnThePage() {
        val marks = marks()
        marks.begin("p1", cover, at(cover, 0.5f, 0.5f), 8f)
        // A long scribble, off the page's edges and back.
        for (i in 1..6000) marks.extend(at(cover, 0.5f + 0.7f * kotlin.math.sin(i / 37f), 0.5f + 0.7f * kotlin.math.cos(i / 53f)))
        // What is held while drawing is bounded too.
        assertTrue(marks.live.count <= 2 * AnnotationLimits.POINTS + 1)
        marks.finish()
        val stroke = marks.book!!.on("p1").strokes.single()
        assertTrue(stroke.count in 3..AnnotationLimits.POINTS)
        assertTrue(stroke.points.all { it in 0f..1f })
    }

    @Test fun aSecondFingerLeavesNothingOfTheStroke() {
        val marks = marks()
        marks.begin("p1", cover, at(cover, 0.2f, 0.2f), 8f)
        marks.extend(at(cover, 0.6f, 0.6f))
        marks.cancel()
        assertTrue(marks.book!!.on("p1").isEmpty)
        assertNull(marks.live.page)
        // Points after it don't start a stroke by themselves.
        marks.extend(at(cover, 0.7f, 0.7f))
        marks.finish()
        assertTrue(marks.book!!.on("p1").isEmpty)
    }

    @Test fun aTouchOffThePageOrOnAPageThatTakesNoInkDrawsNothing() {
        val marks = marks()
        assertFalse(marks.begin("p1", cover, Offset(cover.left - 5f, cover.top + 50f), 8f))
        assertFalse(marks.begin("p1", cover, Offset(cover.left + 50f, cover.top + cover.height + 5f), 8f))
        // A page that was never read, and one with no book at all.
        assertFalse(marks.begin("p9", cover, at(cover, 0.5f, 0.5f), 8f))
        assertFalse(Marks().begin("p1", cover, at(cover, 0.5f, 0.5f), 8f))
        marks.finish()
        assertTrue(marks.book!!.on("p1").isEmpty)
    }

    @Test fun theEraserTakesWhatItPassesOverInOneStepAndGivesItBackIfTheTouchWasAPinch() {
        val marks = marks()
        val book = marks.book!!
        val lines = listOf(0.2f, 0.4f, 0.6f, 0.8f).map { x -> Stroke(InkTool.Pen, InkRole.Pen, 0.006f, floatArrayOf(x, 0.1f, x, 0.9f)) }
        lines.forEach { book.draw("p1", it) }
        marks.tool = DrawTool.Eraser
        // One quick pass across the first three: the finger is reported at two places far apart, and nothing between is stepped over.
        assertTrue(marks.begin("p1", cover, at(cover, 0.1f, 0.5f), 8f))
        marks.extend(at(cover, 0.7f, 0.5f))
        assertEquals(listOf(lines[3]), book.on("p1").strokes)
        marks.finish()
        assertEquals(listOf(lines[3]), book.on("p1").strokes)
        // One step back brings all three.
        book.undo("p1")
        assertEquals(lines, book.on("p1").strokes)
        // A pass that turned out to be the start of a pinch takes nothing.
        marks.begin("p1", cover, at(cover, 0.1f, 0.5f), 8f)
        marks.extend(at(cover, 0.5f, 0.5f))
        assertEquals(2, book.on("p1").strokes.size)
        marks.cancel()
        assertEquals(lines, book.on("p1").strokes)
        // The eraser draws nothing of its own.
        marks.begin("p1", cover, at(cover, 0.95f, 0.95f), 8f)
        marks.finish()
        assertEquals(lines, book.on("p1").strokes)
    }

    @Test fun aNoteAndAStickerAreWhereTheirFractionsSayOnEitherDisplay() {
        val note = Scrap.Note(StickyNote("n", 0.25f, 0.5f, 0.5f, 0.25f, "x"))
        val sticker = Scrap.Stuck(Sticker("s", StickerKind.Star, 0.5f, 0.5f, 0.2f))
        for (area in listOf(cover, inner)) {
            val n = note.rect(area)
            assertEquals(area.left + area.width / 4f, n.left, 0.01f)
            assertEquals(area.top + area.height / 2f, n.top, 0.01f)
            assertEquals(area.width / 2f, n.width, 0.01f)
            assertEquals(area.height / 4f, n.height, 0.01f)
            // A sticker is square in pixels, centred where it was put, and a fifth of the page across.
            val s = sticker.rect(area)
            assertEquals(s.width, s.height, 0.01f)
            assertEquals(area.width / 5f, s.width, 0.01f)
            assertEquals(area.left + area.width / 2f, s.center.x, 0.01f)
            assertEquals(area.top + area.height / 2f, s.center.y, 0.01f)
            // Where a finger takes hold of it is where it is drawn.
            val page = Annotations(notes = listOf(note.note), stickers = listOf(sticker.sticker))
            assertEquals("s", page.scrapAt(area.across(s.right - 1f), area.down(s.bottom - 1f))?.id)
            assertEquals("n", page.scrapAt(area.across(s.right + 1f), area.down(s.bottom + 1f))?.id)
            assertNull(page.scrapAt(area.across(n.right + 1f), area.down(n.bottom + 1f)))
        }
    }

    @Test fun undoAndRedoWaitForTheStrokeOrThePassUnderWay() {
        val marks = marks()
        val book = marks.book!!
        val lines = listOf(0.2f, 0.4f, 0.6f).map { x -> Stroke(InkTool.Pen, InkRole.Pen, 0.006f, floatArrayOf(x, 0.1f, x, 0.9f)) }
        lines.forEach { book.draw("p1", it) }
        assertFalse(marks.busy)
        // The eraser is down and has taken one line; a second finger taps undo.
        marks.tool = DrawTool.Eraser
        marks.begin("p1", cover, at(cover, 0.1f, 0.5f), 8f)
        marks.extend(at(cover, 0.25f, 0.5f))
        assertTrue(marks.busy)
        assertEquals(2, book.on("p1").strokes.size)
        assertFalse(marks.undo("p1"))
        assertFalse(marks.redo("p1"))
        assertEquals(2, book.on("p1").strokes.size)
        // The pass goes on as if nothing had been tapped, and is still one step.
        marks.extend(at(cover, 0.45f, 0.5f))
        marks.finish()
        assertFalse(marks.busy)
        assertEquals(listOf(lines[2]), book.on("p1").strokes)
        assertTrue(marks.undo("p1"))
        assertEquals(lines, book.on("p1").strokes)
        assertTrue(marks.redo("p1"))
        // The same while the pen is down.
        marks.tool = DrawTool.Pen
        marks.begin("p1", cover, at(cover, 0.8f, 0.2f), 8f)
        assertFalse(marks.undo("p1"))
        marks.cancel()
        assertFalse(marks.busy)
        assertTrue(marks.undo("p1"))
    }

    @Test fun leavingDrawingModeKeepsTheStrokeUnderTheFinger() {
        val state = LauncherState()
        state.marks.book = marks("p1", "p2").book
        val book = state.marks.book!!
        state.startDrawing()
        assertTrue(state.drawing)
        // Drawn on the second page of a pair, while the first is the one last touched.
        state.currentPage = "p1"
        state.marks.begin("p2", inner, at(inner, 0.2f, 0.2f), 8f)
        state.marks.extend(at(inner, 0.6f, 0.2f))
        // Done, Back, Home, the app list and the overview all end it this way.
        state.stopDrawing()
        assertFalse(state.drawing)
        assertEquals(listOf(0.2f, 0.2f, 0.6f, 0.2f), book.on("p2").strokes.single().points.toList())
        assertTrue(book.on("p1").isEmpty)
        assertFalse(state.marks.busy)
        // What the finger does next, still down, draws nothing more.
        state.marks.extend(at(inner, 0.9f, 0.9f))
        state.marks.finish()
        assertEquals(1, book.on("p2").strokes.size)
        // The overview and Home end it too, with the stroke kept.
        for (leave in listOf<() -> Unit>({ state.openOverview() }, { state.closeScreens() })) {
            state.startDrawing()
            state.marks.begin("p1", cover, at(cover, 0.3f, 0.3f), 8f)
            leave()
            assertFalse(state.drawing)
        }
        assertEquals(2, book.on("p1").strokes.size)
        // A second finger still drops it.
        state.startDrawing()
        state.marks.begin("p1", cover, at(cover, 0.5f, 0.5f), 8f)
        state.marks.cancel()
        state.stopDrawing()
        assertEquals(2, book.on("p1").strokes.size)
    }

    @Test fun aNoteHeOpensOrSelectsIsHisAtOnce() {
        val marks = marks()
        val book = marks.book!!
        val first = (book.leaveNote("p1", "did 1") as NoteLeft.Left).note
        val second = (book.leaveNote("p1", "did 2") as NoteLeft.Left).note
        marks.editing = Chosen("p1", first.id)
        assertEquals(Author.Owner, book.on("p1").notes.first { it.id == first.id }.author)
        marks.selected = Chosen("p1", second.id)
        assertEquals(Author.Owner, book.on("p1").notes.first { it.id == second.id }.author)
        // While his editor is open the agent leaves more than it keeps: neither of his goes.
        repeat(AnnotationLimits.AGENT_NOTES + 2) { book.leaveNote("p1", "later $it") }
        assertEquals(setOf("did 1", "did 2"), book.on("p1").notes.filter { it.author == Author.Owner }.map { it.text }.toSet())
        // Letting go claims nothing, and a sticker is nobody's note.
        marks.editing = null
        marks.selected = Chosen("p1", book.addSticker("p1", StickerKind.Star)!!)
        assertEquals(AnnotationLimits.AGENT_NOTES, book.on("p1").notes.count { it.author == Author.Agent })
    }

    @Test fun theBarOfASelectedNoteStaysOnThePage() {
        val bar = 48
        val gap = 12
        val room = 1000
        // Above when there is room, else below.
        assertEquals(352, barTop(400, 600, room, bar, gap))
        assertEquals(212, barTop(30, 200, room, bar, gap))
        // A note over the whole page: inside its top edge, not above or below the page where it would be cut away.
        assertEquals(12, barTop(0, 1000, room, bar, gap))
        assertEquals(42, barTop(30, 990, room, bar, gap))
        for (top in 0..1000 step 7) for (bottom in top..1000 step 13) {
            val at = barTop(top, bottom, room, bar, gap)
            assertTrue("$top $bottom -> $at", at >= 0 && at + bar <= room)
        }
    }

    @Test fun deletingAPageAsksWheneverSomethingWouldGoWithIt() {
        // Nothing on it: deleted on the tap, as before.
        val nothing = deleteQuestion(0, marked = false)
        assertEquals(DeleteQuestion.None, nothing)
        assertFalse(nothing.asks)
        // Tiles only: the choices there always were, and no word about ink.
        val tiles = deleteQuestion(3, marked = false)
        assertEquals(DeleteQuestion.Things, tiles)
        assertTrue(tiles.asks && tiles.offersMove)
        assertFalse(tiles.saysMarksGo)
        // Ink, notes or stickers and no tiles: asked, told they go, and nothing to move.
        val marks = deleteQuestion(0, marked = true)
        assertEquals(DeleteQuestion.Marks, marks)
        assertTrue(marks.asks && marks.saysMarksGo)
        assertFalse(marks.offersMove)
        // Both: the tiles' choices and the line.
        val both = deleteQuestion(1, marked = true)
        assertEquals(DeleteQuestion.ThingsAndMarks, both)
        assertTrue(both.asks && both.offersMove && both.saysMarksGo)
        // Only a page with nothing at all goes unasked.
        assertEquals(listOf(DeleteQuestion.None), DeleteQuestion.entries.filter { !it.asks })

        // What counts as marked: anything that shows, and a file that couldn't be read.
        java.io.File(dir, "p3.json").apply { dir.mkdirs() }.writeText("{ cut sho")
        val book = marks("p1", "p2", "p3").book!!
        assertFalse(book.marked("p1"))
        assertFalse(book.marked("never opened"))
        book.addSticker("p1", StickerKind.Heart)
        book.draw("p2", Stroke(InkTool.Pen, InkRole.Pen, 0.006f, floatArrayOf(0.1f, 0.1f)))
        assertTrue(book.marked("p1"))
        assertTrue(book.marked("p2"))
        assertTrue(book.marked("p3"))
        book.undo("p2")
        assertFalse(book.marked("p2"))
    }

    @Test fun aFullPageRefusesThePenButNotTheEraser() {
        val full = (0 until AnnotationLimits.STROKES).map { Stroke(InkTool.Pen, InkRole.Pen, 0.006f, floatArrayOf(it / 2000f, 0.5f)) }
        AnnotationFiles(dir).write("p1", Annotations(strokes = full))
        val marks = marks()
        assertFalse(marks.begin("p1", cover, at(cover, 0.5f, 0.9f), 8f))
        assertEquals(AnnotationBook.FULL, marks.book!!.notice)
        assertEquals(AnnotationLimits.STROKES, marks.book!!.on("p1").strokes.size)
        marks.tool = DrawTool.Eraser
        assertTrue(marks.begin("p1", cover, at(cover, 0.5f, 0.5f), 8f))
        marks.finish()
        assertTrue(marks.book!!.on("p1").strokes.size < AnnotationLimits.STROKES)
        marks.tool = DrawTool.Pen
        assertTrue(marks.begin("p1", cover, at(cover, 0.5f, 0.9f), 8f))
    }
}
