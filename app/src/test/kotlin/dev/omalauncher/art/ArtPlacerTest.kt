package dev.omalauncher.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtPlacerTest {
    /** A home of panel pages that records what was hung where. */
    private class FakeHome(
        var pages: List<PanelPage>,
        val accepts: Boolean = true,
    ) : PanelHome {
        val hung = mutableListOf<Triple<String, String, Int>>()

        override fun panelPages() = pages

        override fun put(
            artId: String,
            page: String,
            panel: Int,
        ): Boolean {
            if (accepts) hung += Triple(artId, page, panel)
            return accepts
        }
    }

    private val said = mutableListOf<String>()

    private fun placer(home: PanelHome?) = DefaultArtPlacer({ home }) { said += it }

    private val p1 = PanelPage("p1", "Page 1", listOf(false, false, false))
    private val p2 = PanelPage("p2", "Page 2", listOf(false, true, true))
    private val p3 = PanelPage("p3", "Page 3", listOf(true, false))

    @Test fun takesTheAskedPanelWhenEmpty() {
        val home = FakeHome(listOf(p1, p2, p3))
        placer(home).place("a", "p2", 2)
        assertEquals(listOf(Triple("a", "p2", 2)), home.hung)
        assertEquals(emptyList<String>(), said)
    }

    @Test fun sharedArtGoesToTheFirstEmptyPanelInPageOrderAndSaysWhere() {
        val home = FakeHome(listOf(p1, p2, p3))
        placer(home).place("a", null, null)
        assertEquals(listOf(Triple("a", "p2", 1)), home.hung)
        assertEquals(listOf("Hung on Page 2."), said)
    }

    @Test fun aTakenPanelFallsBackToTheAskedPageFirst() {
        val home = FakeHome(listOf(p1, p2, p3))
        placer(home).place("a", "p3", 1)
        assertEquals(listOf(Triple("a", "p3", 0)), home.hung)
        assertEquals(listOf("Hung on Page 3."), said)
    }

    @Test fun anUnknownPageOrPanelFallsBackToTheFirstEmpty() {
        val home = FakeHome(listOf(p1, p2, p3))
        placer(home).place("a", "gone", 0)
        placer(home).place("b", "p3", 9)
        assertEquals(listOf(Triple("a", "p2", 1), Triple("b", "p3", 0)), home.hung)
    }

    @Test fun nowhereWhenThereIsNoPanelPageAndSaysSo() {
        placer(null).place("a", null, null)
        placer(FakeHome(emptyList())).place("b", "p1", 0)
        assertEquals(2, said.size)
        said.forEach { assertTrue(it, it.contains("no manga page")) }
    }

    @Test fun nowhereWhenEveryPanelIsTakenAndSaysSo() {
        val home = FakeHome(listOf(p1, PanelPage("p4", "Page 4", listOf(false))))
        placer(home).place("a", null, null)
        assertTrue(home.hung.isEmpty())
        assertEquals(listOf("Every panel is taken. Empty one first."), said)
    }

    @Test fun saysSoWhenHomeRefuses() {
        val home = FakeHome(listOf(p2), accepts = false)
        placer(home).place("a", "p2", 1)
        assertEquals(listOf("It couldn't be hung."), said)
    }

    @Test fun spotIsPure() {
        assertNull(DefaultArtPlacer.spot(emptyList(), null, null))
        assertEquals(p2 to 1, DefaultArtPlacer.spot(listOf(p1, p2), null, null))
        assertEquals(p2 to 2, DefaultArtPlacer.spot(listOf(p1, p2), "p2", 2))
        assertEquals(p2 to 1, DefaultArtPlacer.spot(listOf(p1, p2), "p2", -1))
    }

    @Test fun theCurrentPlacerIsTheDefaultUntilHomeSetsOne() {
        assertTrue(ArtPlacers.current is DefaultArtPlacer)
        val home = FakeHome(listOf(p3))
        ArtPlacers.home = home
        try {
            ArtPlacers.current.place("a", null, null)
            assertEquals(listOf(Triple("a", "p3", 0)), home.hung)
        } finally {
            ArtPlacers.home = null
        }
    }
}
