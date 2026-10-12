package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGesturesTest {
    private val slop = 10f

    /** Every mode home can be in when a touch comes down: each flag alone, together, and none. */
    private val modes =
        listOf(false, true).flatMap { editing ->
            listOf(false, true).flatMap { folder -> listOf(false, true).map { drawing -> HomeModes(editing, folder, drawing) } }
        }

    /** Every start under every mode. */
    private val cases = TouchStart.entries.flatMap { start -> modes.map { start to it } }

    private fun touch(
        start: TouchStart,
        modes: HomeModes,
    ) = GestureArbiter().also { it.down(start, modes) }

    /** Who has the touch as the first finger lands, written out from home's rules rather than from the arbiter. */
    private fun atDown(
        start: TouchStart,
        modes: HomeModes,
    ): Owner =
        when {
            modes.drawing -> Owner.Draw
            modes.folderOpen -> if (start == TouchStart.Tile) Owner.Undecided else Owner.Editor
            !modes.editing -> Owner.Undecided
            start == TouchStart.Bar -> Owner.Buttons
            start == TouchStart.Handle -> Owner.Resize
            start == TouchStart.Selected -> Owner.Arrange
            else -> Owner.Editor
        }

    @Test fun theFirstFingerDownIsOwnedAsHomeAlwaysHadIt() {
        for ((start, modes) in cases) assertEquals("$start $modes", atDown(start, modes), touch(start, modes).owner)
        // With nothing going on, no touch is anyone's until it does something.
        for (start in TouchStart.entries) assertEquals(Owner.Undecided, touch(start, HomeModes()).owner)
    }

    @Test fun oneFingerDragsGoWhereTheyAlwaysWent() {
        for ((start, modes) in cases) {
            val before = atDown(start, modes)
            for ((dx, dy, free) in listOf(
                Triple(40f, 5f, Owner.Pager),
                Triple(-40f, -5f, Owner.Pager),
                Triple(3f, -40f, Owner.Drawer),
                Triple(3f, 40f, Owner.Nobody),
            )) {
                val expected =
                    when {
                        before != Owner.Undecided -> before
                        start == TouchStart.Widget -> Owner.Widget
                        else -> free
                    }
                assertEquals("$start $modes by $dx,$dy", expected, touch(start, modes).also { it.pointers(1) }.moved(dx, dy, 0f, slop))
            }
            // Within the slop nothing is decided, and a tap is still a tap.
            assertEquals("$start $modes", before, touch(start, modes).moved(6f, 6f, 0f, slop))
        }
    }

    @Test fun aLongPressLiftsATileOrOpensTheMenuAndNothingElse() {
        for ((start, modes) in cases) {
            val before = atDown(start, modes)
            val expected =
                when {
                    before != Owner.Undecided -> before
                    start == TouchStart.Page -> Owner.Menu
                    else -> Owner.Arrange
                }
            assertEquals("$start $modes", expected, touch(start, modes).longPress())
            // Once it has moved off, resting there is no long press.
            val dragged = touch(start, modes).also { it.moved(40f, 0f, 0f, slop) }
            assertEquals("$start $modes", dragged.owner, dragged.longPress())
            assertFalse(dragged.owner == Owner.Menu || (dragged.owner == Owner.Arrange && before != Owner.Arrange))
        }
    }

    @Test fun twoFingersMovingApartOrTogetherAreAlwaysThePinch() {
        for ((start, modes) in cases) {
            for (spread in listOf(-30f, 30f)) {
                // Landing together.
                val together = touch(start, modes).also { it.pointers(2) }
                assertEquals("$start $modes", Owner.Pinch, together.moved(0f, 0f, spread, slop))
                // After the first finger began a drag of its own: the page turning, a widget's own drag (UP-15), a stroke.
                val dragging = touch(start, modes).also { it.moved(40f, 0f, 0f, slop) }
                val carried = dragging.owner == Owner.Arrange || dragging.owner == Owner.Resize
                dragging.pointers(2)
                assertEquals("$start $modes", if (carried) dragging.owner else Owner.Pinch, dragging.moved(0f, 0f, spread, slop))
                // After a long press lifted a tile that hasn't moved yet.
                val lifted = touch(start, modes).also { it.longPress() }
                lifted.pointers(2)
                assertEquals("$start $modes", Owner.Pinch, lifted.moved(0f, 0f, spread, slop))
            }
        }
    }

    @Test fun aPinchKeepsTheTouchUntilEveryFingerIsUp() {
        val arbiter = touch(TouchStart.Widget, HomeModes())
        arbiter.pointers(2)
        arbiter.moved(0f, 0f, -30f, slop)
        // Back within the slop, one finger lifted, the other wandering, a rest as long as a long press: still the pinch.
        assertEquals(Owner.Pinch, arbiter.moved(0f, 0f, 0f, slop))
        assertEquals(Owner.Pinch, arbiter.pointers(1))
        assertEquals(Owner.Pinch, arbiter.moved(80f, 0f, 0f, slop))
        assertEquals(Owner.Pinch, arbiter.longPress())
        arbiter.up()
        assertEquals(Owner.Undecided, arbiter.owner)
        // And the next touch starts from nothing.
        assertEquals(Owner.Pager, touch(TouchStart.Page, HomeModes()).moved(40f, 0f, 0f, slop))
    }

    @Test fun twoFingersThatDoNotSpreadAreNoPinchAndNoLongPress() {
        for ((start, modes) in cases) {
            val before = atDown(start, modes)
            val arbiter = touch(start, modes).also { it.pointers(2) }
            assertEquals("$start $modes", before, arbiter.moved(0f, 0f, 8f, slop))
            // Two fingers resting never lift a tile or open the menu, nor does the one left when the other lifts.
            assertEquals("$start $modes", before, arbiter.longPress())
            arbiter.pointers(1)
            assertEquals("$start $modes", before, arbiter.longPress())
            // And that finger's wandering is not a drag home acts on.
            assertEquals("$start $modes", before, arbiter.moved(40f, 0f, 0f, slop))
        }
    }

    @Test fun aTileBeingCarriedOrResizedIsNotDroppedForASecondFinger() {
        val editing = HomeModes(editing = true)
        for (start in listOf(TouchStart.Selected, TouchStart.Handle)) {
            val arbiter = touch(start, editing)
            val owner = arbiter.moved(0f, 40f, 0f, slop)
            arbiter.pointers(2)
            assertEquals(owner, arbiter.moved(0f, 0f, -60f, slop))
            arbiter.pointers(1)
            assertEquals(owner, arbiter.moved(0f, 80f, 0f, slop))
        }
        // The same for a tile lifted by a long press and then moved.
        val lifted = touch(TouchStart.Tile, HomeModes())
        assertEquals(Owner.Arrange, lifted.longPress())
        lifted.moved(40f, 0f, 0f, slop)
        lifted.pointers(2)
        assertEquals(Owner.Arrange, lifted.moved(0f, 0f, 60f, slop))
    }

    /** PRD AC-22's rule; the drawing itself comes with a later slice, and nothing turns the mode on yet. */
    @Test fun inDrawingModeOneFingerDrawsAndTwoStillPinch() {
        for (start in TouchStart.entries) {
            for (modes in modes.filter { it.drawing }) {
                for ((dx, dy) in listOf(40f to 0f, -40f to 0f, 0f to -40f, 0f to 40f, 3f to 3f)) {
                    val arbiter = touch(start, modes)
                    // Not the pager's, so no page turns; not a tile's, so none is lifted, carried or resized; no menu.
                    assertEquals("$start $modes", Owner.Draw, arbiter.moved(dx, dy, 0f, slop))
                    assertEquals("$start $modes", Owner.Draw, arbiter.longPress())
                    arbiter.pointers(2)
                    assertEquals("$start $modes", Owner.Pinch, arbiter.moved(dx, dy, -30f, slop))
                }
            }
        }
    }

    @Test fun aPinchSettlesIntoTheOverviewAtPointEightTwoOrLess() {
        assertTrue(settlesIntoOverview(0.82f))
        assertTrue(settlesIntoOverview(0.8199f))
        assertTrue(settlesIntoOverview(0.5f))
        assertFalse(settlesIntoOverview(0.8201f))
        assertFalse(settlesIntoOverview(0.83f))
        assertFalse(settlesIntoOverview(1f))
        assertFalse(settlesIntoOverview(1.08f))
        assertEquals(0.82f, OVERVIEW_AT, 0f)
        // From the fingers: 100 apart closing to 82 settles, to 83 springs back.
        assertTrue(settlesIntoOverview(pinchScale(100f, 82f)))
        assertFalse(settlesIntoOverview(pinchScale(100f, 83f)))
        // Spreading on home never does, however far.
        assertFalse(settlesIntoOverview(pinchScale(100f, 900f)))
    }

    @Test fun thePageFollowsThePinchOnlySoFar() {
        assertEquals(0.9f, pinchScale(200f, 180f), 1e-6f)
        assertEquals(0.5f, pinchScale(200f, 10f), 0f)
        assertEquals(1.08f, pinchScale(200f, 400f), 0f)
        // Fingers that landed on one spot, or a distance that isn't one, leave the page as it is.
        assertEquals(1f, pinchScale(0f, 50f), 0f)
        assertEquals(1f, pinchScale(100f, Float.NaN), 0f)
        assertEquals(1f, spanRatio(-1f, 50f), 0f)
        assertEquals(1f, spanRatio(100f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun aPinchOutwardsOpensAPageOfTheOverview() {
        assertTrue(opensFromOverview(spanRatio(100f, 118f)))
        assertTrue(opensFromOverview(2f))
        assertFalse(opensFromOverview(spanRatio(100f, 117f)))
        assertFalse(opensFromOverview(1f))
        assertFalse(opensFromOverview(0.5f))
    }
}
