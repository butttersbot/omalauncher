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
                Triple(3f, 40f, Owner.Search),
            )) {
                val expected =
                    when {
                        before != Owner.Undecided -> before
                        start == TouchStart.ScrollingWidget -> Owner.Widget
                        // Straight up and down are the app list's and the search's from the page, an app or a widget.
                        (free == Owner.Drawer || free == Owner.Search) && !modes.folderOpen && start == TouchStart.Widget -> free
                        start == TouchStart.Widget -> Owner.Widget
                        // Upwards is the app list's only from the bare page or an app, and not over an open folder.
                        free == Owner.Drawer && (modes.folderOpen || (start != TouchStart.Page && start != TouchStart.Tile)) -> Owner.Nobody
                        // Downwards is the search's from the same places only.
                        free == Owner.Search && (modes.folderOpen || (start != TouchStart.Page && start != TouchStart.Tile)) -> Owner.Nobody
                        else -> free
                    }
                assertEquals("$start $modes by $dx,$dy", expected, touch(start, modes).also { it.pointers(1) }.moved(dx, dy, 0f, slop))
            }
            // Within the slop nothing is decided, and a tap is still a tap.
            assertEquals("$start $modes", before, touch(start, modes).moved(6f, 6f, 0f, slop))
        }
    }

    /** PRD AC-1: the one touch that opens the app list on home. */
    @Test fun aDragUpwardsOpensTheAppListOnlyWhereNothingElseWantsIt() {
        for ((start, modes) in cases) {
            val opens = modes == HomeModes() && (start == TouchStart.Page || start == TouchStart.Tile || start == TouchStart.Widget)
            for ((dx, dy) in listOf(0f to -40f, 12f to -40f, -26f to -40f, 0f to -21f)) {
                val owner = touch(start, modes).moved(dx, dy, 0f, slop)
                assertEquals("$start $modes by $dx,$dy", opens, owner == Owner.Drawer)
            }
            // Never from a drag downwards or sideways, whatever it started on.
            for ((dx, dy) in listOf(0f to 40f, 40f to 0f, -40f to -12f, 41f to -40f, -39f to -40f, 27f to -40f, 0f to -20f, 0f to -12f)) {
                assertFalse("$start $modes by $dx,$dy", touch(start, modes).moved(dx, dy, 0f, slop) == Owner.Drawer)
            }
        }
        // A widget gives straight up and down to home, and keeps the rest; one that scrolls (a terminal) keeps them all.
        assertEquals(Owner.Drawer, touch(TouchStart.Widget, HomeModes()).moved(0f, -40f, 0f, slop))
        assertEquals(Owner.Search, touch(TouchStart.Widget, HomeModes()).moved(0f, 40f, 0f, slop))
        assertEquals(Owner.Widget, touch(TouchStart.Widget, HomeModes()).moved(40f, 5f, 0f, slop))
        assertEquals(Owner.Widget, touch(TouchStart.Widget, HomeModes()).moved(30f, -30f, 0f, slop))
        assertEquals(Owner.Undecided, touch(TouchStart.Widget, HomeModes()).moved(0f, 15f, 0f, slop))
        assertEquals(Owner.Widget, touch(TouchStart.ScrollingWidget, HomeModes()).moved(0f, -40f, 0f, slop))
        assertEquals(Owner.Widget, touch(TouchStart.ScrollingWidget, HomeModes()).moved(0f, 40f, 0f, slop))
        // A tile being edited, its handles and its bar are the editor's; an open folder's and the pen's likewise.
        assertEquals(Owner.Arrange, touch(TouchStart.Selected, HomeModes(editing = true)).moved(0f, -40f, 0f, slop))
        assertEquals(Owner.Resize, touch(TouchStart.Handle, HomeModes(editing = true)).moved(0f, -40f, 0f, slop))
        assertEquals(Owner.Editor, touch(TouchStart.Page, HomeModes(editing = true)).moved(0f, -40f, 0f, slop))
        assertEquals(Owner.Nobody, touch(TouchStart.Tile, HomeModes(folderOpen = true)).moved(0f, -40f, 0f, slop))
        assertEquals(Owner.Draw, touch(TouchStart.Page, HomeModes(drawing = true)).moved(0f, -40f, 0f, slop))
    }

    @Test fun upwardsOrSidewaysIsDecidedWhereTheFingerLeavesTheSlop() {
        fun drag(
            dx: Float,
            dy: Float,
        ) = touch(TouchStart.Page, HomeModes()).moved(dx, dy, 0f, slop)
        // The list takes twice the slop, straight up: at the slop, and all the way to twice it, nothing is decided.
        assertEquals(Owner.Undecided, drag(0f, -10f))
        assertEquals(Owner.Undecided, drag(0f, -10.5f))
        assertEquals(Owner.Undecided, drag(3f, -15f))
        assertEquals(Owner.Undecided, drag(0f, -20f))
        assertEquals(Owner.Drawer, drag(0f, -20.5f))
        assertEquals(2f, DRAWER_SLOPS, 0f)
        // And one and a half times as far up as sideways: at that slant it is the list's, a hair flatter the pager's.
        assertEquals(Owner.Drawer, drag(20f, -30f))
        assertEquals(Owner.Drawer, drag(-20f, -30f))
        assertEquals(Owner.Pager, drag(20.5f, -30f))
        assertEquals(Owner.Pager, drag(-20.5f, -30f))
        assertEquals(1.5f, DRAWER_STEEP, 0f)
        // Ties and near-ties go to the pager, which keeps its own slop: it doesn't wait for twice of it.
        assertEquals(Owner.Pager, drag(20f, -20f))
        assertEquals(Owner.Pager, drag(8f, -8f))
        assertEquals(Owner.Pager, drag(7f, -9f))
        assertEquals(Owner.Pager, drag(10.5f, 0f))
        assertEquals(Owner.Pager, drag(-11f, 3f))
        assertEquals(Owner.Nobody, drag(20f, 20f))
        // Straight down is the search's, as far and as steep as the list wants upwards.
        assertEquals(Owner.Undecided, drag(0f, 10.5f))
        assertEquals(Owner.Undecided, drag(0f, 20f))
        assertEquals(Owner.Search, drag(0f, 20.5f))
        assertEquals(Owner.Search, drag(20f, 30f))
        assertEquals(Owner.Nobody, drag(21f, 30f))
        // Decided once: a swipe that began sideways stays the pager's when it curls upwards, and the other way round.
        val sideways = touch(TouchStart.Page, HomeModes()).also { it.moved(30f, -5f, 0f, slop) }
        assertEquals(Owner.Pager, sideways.moved(30f, -200f, 0f, slop))
        val upwards = touch(TouchStart.Tile, HomeModes()).also { it.moved(2f, -30f, 0f, slop) }
        assertEquals(Owner.Drawer, upwards.moved(200f, -30f, 0f, slop))
        assertEquals(Owner.Drawer, upwards.moved(0f, 50f, 0f, slop))
    }

    @Test fun aTapWhoseFingerDriftsUpwardsIsStillATap() {
        for (start in listOf(TouchStart.Page, TouchStart.Tile)) {
            val arbiter = touch(start, HomeModes())
            // Past the slop, short of twice it, sample after sample: nobody takes the touch, so nothing is consumed
            // and the app, the dock icon or the page name under the finger still gets its tap when it lifts.
            for ((dx, dy) in listOf(0f to -6f, 1f to -11f, 2f to -14f, 2f to -19f, 1f to -16f)) {
                assertEquals("$start by $dx,$dy", Owner.Undecided, arbiter.moved(dx, dy, 0f, slop))
            }
            // Nor is it a long press any more: a finger that wandered and then rests lifts no tile and opens no menu.
            assertEquals(Owner.Undecided, arbiter.longPress())
            arbiter.up()
            assertEquals(Owner.Undecided, arbiter.owner)
        }
        // Going on upwards from such a drift is the list's after all, from where it passes twice the slop.
        val goesOn = touch(TouchStart.Tile, HomeModes())
        assertEquals(Owner.Undecided, goesOn.moved(1f, -15f, 0f, slop))
        assertEquals(Owner.Drawer, goesOn.moved(2f, -24f, 0f, slop))
    }

    @Test fun aSwipeThatArcsUpwardsStaysThePagers() {
        // A thumb's arc: it leaves the slop going mostly up, then sweeps sideways.
        val arc = touch(TouchStart.Page, HomeModes())
        assertEquals(Owner.Undecided, arc.moved(3f, -11f, 0f, slop))
        assertEquals(Owner.Pager, arc.moved(13f, -17f, 0f, slop))
        for ((dx, dy) in listOf(40f to -30f, 120f to -45f, 200f to -20f, 30f to -300f)) assertEquals(Owner.Pager, arc.moved(dx, dy, 0f, slop))
        // One that is as much up as sideways from its first sample past the slop is the pager's at once.
        assertEquals(Owner.Pager, touch(TouchStart.Tile, HomeModes()).moved(8f, -9f, 0f, slop))
        // And the page turns from the pager's own slop, however the finger got there.
        val flat = touch(TouchStart.Page, HomeModes())
        assertEquals(Owner.Undecided, flat.moved(9f, -2f, 0f, slop))
        assertEquals(Owner.Pager, flat.moved(11f, -2f, 0f, slop))
    }

    @Test fun theMenuTheLiftAndThePinchStillWinOverTheAppList() {
        // Rested as long as a long press first: the home menu on the bare page, a lift on a tile, whatever comes after.
        val menu = touch(TouchStart.Page, HomeModes())
        assertEquals(Owner.Menu, menu.longPress())
        assertEquals(Owner.Menu, menu.moved(0f, -80f, 0f, slop))
        val lifted = touch(TouchStart.Tile, HomeModes())
        assertEquals(Owner.Arrange, lifted.longPress())
        assertEquals(Owner.Arrange, lifted.moved(0f, -80f, 0f, slop))
        // Two fingers down before either moved: the pinch, and no list from the finger left over.
        val two = touch(TouchStart.Page, HomeModes()).also { it.pointers(2) }
        assertEquals(Owner.Undecided, two.moved(0f, -80f, 5f, slop))
        assertEquals(Owner.Pinch, two.moved(0f, -80f, -30f, slop))
        val left = touch(TouchStart.Page, HomeModes()).also { it.pointers(2) }
        left.pointers(1)
        assertEquals(Owner.Undecided, left.moved(0f, -80f, 0f, slop))
        // Once the list is coming up under a finger, a second one doesn't leave it half open, and no long press comes of it.
        val pulling = touch(TouchStart.Page, HomeModes())
        assertEquals(Owner.Drawer, pulling.moved(0f, -40f, 0f, slop))
        pulling.pointers(2)
        assertEquals(Owner.Drawer, pulling.moved(0f, -40f, -60f, slop))
        assertEquals(Owner.Drawer, pulling.longPress())
        pulling.up()
        assertEquals(Owner.Undecided, pulling.owner)
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
