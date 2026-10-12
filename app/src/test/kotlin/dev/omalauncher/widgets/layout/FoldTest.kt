package dev.omalauncher.widgets.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notebook's fold rule (PRD 6.2), over every posture and the windows measured on the owner's Fold
 * (density 420): the cover, 475 x 751 dp; the inner screen, 932 x 704 dp wider than tall and 704 x 933 dp turned.
 */
class FoldTest {
    private val cover = 475f to 751f
    private val coverTurned = 751f to 475f
    private val inner = 932f to 704f
    private val innerUpright = 704f to 933f

    private fun view(
        window: Pair<Float, Float>,
        posture: Posture,
        touched: Int = 0,
        pages: Int = 4,
    ) = foldView(window.first, window.second, posture, touched, pages)

    @Test fun closedShowsOnePage() {
        for (window in listOf(cover, coverTurned, inner, innerUpright)) {
            for (touched in 0 until 4) assertEquals(FoldView(1, 4, touched, false), view(window, Posture.Closed, touched))
        }
    }

    @Test fun aWindowNarrowerThanTallAndUnder600dpShowsOnePageWhateverThePosture() {
        for (posture in Posture.entries) {
            assertEquals(FoldView(1, 4, 2, false), view(cover, posture, 2))
            assertEquals(FoldView(1, 4, 2, false), view(599f to 1200f, posture, 2))
            // A phone on its side is no unfolded foldable either.
            assertEquals(FoldView(1, 4, 2, false), view(coverTurned, posture, 2))
        }
    }

    @Test fun unfoldedAndWiderThanTallShowsFixedNeighbours() {
        // 1+2, then 3+4: by position, whichever of a pair was touched.
        assertEquals(FoldView(2, 2, 0, false), view(inner, Posture.Opened, 0))
        assertEquals(FoldView(2, 2, 0, false), view(inner, Posture.Opened, 1))
        assertEquals(FoldView(2, 2, 1, false), view(inner, Posture.Opened, 2))
        assertEquals(FoldView(2, 2, 1, false), view(inner, Posture.Opened, 3))
        assertEquals(FoldView(2, 5, 4, false), view(inner, Posture.Opened, 9, pages = 10))
    }

    /** UP-6. */
    @Test fun anOddCountEndsInAnEmptyRightHalf() {
        assertEquals(FoldView(2, 1, 0, true), view(inner, Posture.Opened, 0, pages = 1))
        assertEquals(FoldView(2, 2, 1, true), view(inner, Posture.Opened, 2, pages = 3))
        assertEquals(FoldView(2, 3, 0, true), view(inner, Posture.Opened, 1, pages = 5))
        assertFalse(view(inner, Posture.Opened, 0, pages = 2).plus)
        // One page at a time has no empty half.
        for (posture in Posture.entries) assertFalse(view(cover, posture, 0, pages = 3).plus)
        assertFalse(view(innerUpright, Posture.Opened, 0, pages = 3).plus)
    }

    /** UP-7. */
    @Test fun unfoldedButTallerThanWideShowsOnePage() {
        for (touched in 0 until 4) assertEquals(FoldView(1, 4, touched, false), view(innerUpright, Posture.Opened, touched))
        // Square counts as not wider than tall.
        assertEquals(1, view(800f to 800f, Posture.Opened).per)
    }

    /** UP-7. */
    @Test fun tentAndHalfOpenedShowOnePage() {
        for (posture in listOf(Posture.Tent, Posture.HalfOpened)) {
            for (window in listOf(cover, coverTurned, inner, innerUpright)) {
                for (touched in 0 until 4) assertEquals("$posture $window", FoldView(1, 4, touched, false), view(window, posture, touched))
            }
        }
    }

    /** Every posture against every measured window: two pages only opened flat, wider than tall. */
    @Test fun everyPostureAndWindow() {
        for (posture in Posture.entries) {
            for (window in listOf(cover, coverTurned, inner, innerUpright)) {
                val two = posture == Posture.Opened && window == inner
                assertEquals("$posture $window", if (two) 2 else 1, view(window, posture).per)
            }
        }
    }

    /** AC-9. */
    @Test fun foldingShowsThePageLastTouchedAndUnfoldingItsPair() {
        // On pages 3+4, the right one touched: folding shows page 4 (position 3).
        assertEquals(1, view(inner, Posture.Opened, touched = 3).slot)
        assertEquals(3, view(cover, Posture.Closed, touched = 3).slot)
        // Neither touched since turning to them: the left one is what "last touched" holds, so the left shows.
        assertEquals(2, view(cover, Posture.Closed, touched = 2).slot)
        // Unfolding from page 4 shows 3+4; from page 1, 1+2.
        assertEquals(1, view(inner, Posture.Opened, touched = 3).slot)
        assertEquals(0, view(inner, Posture.Opened, touched = 0).slot)
        // Turned upright while unfolded, the same page stays.
        assertEquals(3, view(innerUpright, Posture.Opened, touched = 3).slot)
    }

    @Test fun aPageThatIsGoneOrNoneAtAllStillShowsSomething() {
        assertEquals(FoldView(1, 4, 0, false), view(cover, Posture.Closed, touched = -1))
        assertEquals(FoldView(1, 4, 3, false), view(cover, Posture.Closed, touched = 40))
        assertEquals(FoldView(2, 2, 1, false), view(inner, Posture.Opened, touched = 40))
        assertEquals(FoldView(1, 1, 0, false), view(cover, Posture.Closed, pages = 0))
        assertEquals(FoldView(2, 1, 0, true), view(inner, Posture.Opened, pages = 0))
    }

    @Test fun postureFromTheWindowAndTheHinge() {
        // The cover has no hinge across it, and whatever a hinge said, a window that small is the folded one.
        for (hinge in listOf(null, Hinge.Flat, Hinge.HalfOpened)) {
            assertEquals(Posture.Closed, posture(475f, 751f, hinge))
            assertEquals(Posture.Closed, posture(751f, 475f, hinge))
        }
        assertEquals(Posture.Opened, posture(932f, 704f, Hinge.Flat))
        assertEquals(Posture.Opened, posture(704f, 933f, Hinge.Flat))
        assertEquals(Posture.HalfOpened, posture(932f, 704f, Hinge.HalfOpened))
        assertEquals(Posture.HalfOpened, posture(704f, 933f, Hinge.HalfOpened))
        // A tablet has no hinge to report.
        assertEquals(Posture.Opened, posture(1280f, 800f, null))
        // What the phone gives end to end.
        assertEquals(2, foldView(932f, 704f, posture(932f, 704f, Hinge.Flat), 0, 4).per)
        assertEquals(1, foldView(932f, 704f, posture(932f, 704f, Hinge.HalfOpened), 0, 4).per)
        assertEquals(1, foldView(704f, 933f, posture(704f, 933f, Hinge.Flat), 0, 4).per)
        assertEquals(1, foldView(475f, 751f, posture(475f, 751f, null), 0, 4).per)
    }

    @Test fun theDockStandsBesideThePageOnlyInAPhonesLandscape() {
        assertTrue(dockBeside(751f, 475f))
        assertFalse(dockBeside(475f, 751f))
        // Unfolded, either way up, it stays across the bottom.
        assertFalse(dockBeside(932f, 704f))
        assertFalse(dockBeside(704f, 933f))
    }
}
