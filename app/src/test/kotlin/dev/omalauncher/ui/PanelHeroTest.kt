package dev.omalauncher.ui

import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.widgets.ArtItem
import dev.omalauncher.widgets.GRID_COLUMNS
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.ModuleItem
import dev.omalauncher.widgets.layout.PanelLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manga page's hero (the Manga look): only art in the top row's page-wide panel runs up past the page's name to the top of
 * the screen, and only over it is the name lettered light and the status bar's icons light.
 */
class PanelHeroTest {
    private val rows = 10
    private val art = ArtItem("art:df82992b137092fd")
    private val other = ArtItem("art:0123456789abcdef")
    private val module = ModuleItem("module:clock")

    /** The Manga look's own [panels], as shipped. */
    private val manga = PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/manga")), "manga").pack.panels!!

    private fun panels(layout: String) = PanelLayouts.panels(layout, GRID_COLUMNS, rows)

    /** [layout]'s panels holding [held] in order (null leaves one empty). */
    private fun hero(
        layout: String,
        vararg held: HomeItem?,
        carried: String? = null,
    ) = heroPanel(panels(layout), held.toList() + List(panels(layout).size - held.size) { null }, carried)

    @Test fun artInTheOpeningsTopPanelIsTheHero() {
        val top = panels("opening")[0]
        assertEquals(0, top.cell.y)
        assertEquals(GRID_COLUMNS, top.cell.w)
        assertEquals(0, hero("opening", art))
    }

    /** A full splash is one page-wide panel from the top: its art runs up too. */
    @Test fun aFullSplashOfArtIsAHero() {
        assertEquals(0, hero("splash", art))
    }

    @Test fun aWidgetOrNothingInTheTopPanelIsNoHero() {
        assertNull(hero("opening", module))
        assertNull(hero("opening"))
    }

    /** Art lower down bleeds to the sides as it always did, but runs up past nothing. */
    @Test fun aPageWidePanelBelowTheTopIsNoHero() {
        val middle = panels("beats")[1]
        assertTrue(middle.cell.y > 0)
        assertTrue(middle.bleed != null)
        assertNull(hero("beats", module, art))
    }

    /** Art in a top panel narrower than the page keeps the page's margins. */
    @Test fun aNarrowTopPanelIsNoHero() {
        val left = panels("grid6")[0]
        assertEquals(0, left.cell.y)
        assertTrue(left.cell.w < GRID_COLUMNS)
        assertNull(hero("grid6", art, other))
    }

    /** Lifted, the panel is toned and dashed like any other: nothing runs up while it is carried. */
    @Test fun carriedArtIsNoHero() {
        assertNull(hero("opening", art, carried = art.key))
        assertEquals(0, hero("opening", art, carried = other.key))
    }

    @Test fun onlyAPanelPageInTheMangaLookHasAHero() {
        val cells = panels("opening").map { it.cell }
        val cellOf = { key: String -> if (key == art.key) cells[0] else null }
        assertTrue(hasHero("opening", rows, manga, listOf(art), cellOf, null))
        // The free grid, and every other look (no [panels]), stay as they are.
        assertFalse(hasHero(null, rows, manga, listOf(art), cellOf, null))
        assertFalse(hasHero("opening", rows, null, listOf(art), cellOf, null))
        // Art in the page's last panel, not its top one.
        assertFalse(hasHero("opening", rows, manga, listOf(art), { _: String -> cells.last() }, null))
    }

    /** Page 1 (slot 0) has a hero, page 2 (slot 1) none: one page to a slot, folded. */
    private val firstOnly = { slot: Int, _: Int -> slot == 0 }

    @Test fun theNameIsOverTheHeroUntilATurnHasSlidItAway() {
        val name = 0.05f
        assertTrue(heroUnder(name, 0f, 1, firstOnly))
        // Turned most of the way to page 2: the hero's last edge is still under the name.
        assertTrue(heroUnder(name, 0.9f, 1, firstOnly))
        assertFalse(heroUnder(name, 0.96f, 1, firstOnly))
        assertFalse(heroUnder(name, 1f, 1, firstOnly))
        // Coming back from page 2, the hero is under the name as soon as it slides in under it.
        assertTrue(heroUnder(name, 0.94f, 1, firstOnly))
        // No page before the first.
        assertFalse(heroUnder(name, -0.2f, 1, firstOnly))
    }

    /** Unfolded, two pages to a slot: the hero is under the left half only when the left page has it. */
    @Test fun onASpreadEachHalfHasItsOwnPage() {
        val leftOnly = { slot: Int, side: Int -> slot == 0 && side == 0 }
        assertTrue(heroUnder(0.05f, 0f, 2, leftOnly))
        assertFalse(heroUnder(0.55f, 0f, 2, leftOnly))
        val rightOnly = { slot: Int, side: Int -> slot == 0 && side == 1 }
        assertFalse(heroUnder(0.05f, 0f, 2, rightOnly))
        assertTrue(heroUnder(0.55f, 0f, 2, rightOnly))
    }

    /** Light icons only where heroes run under all of the bar: on a spread with one, the other end is over paper. */
    @Test fun theBarIsOverHeroesOnlyWhenEveryPageShowingHasOne() {
        assertTrue(heroAcross(0, 1, firstOnly))
        assertFalse(heroAcross(1, 1, firstOnly))
        assertFalse(heroAcross(0, 2) { slot, side -> slot == 0 && side == 0 })
        assertTrue(heroAcross(0, 2) { slot, _ -> slot == 0 })
    }

    @Test fun theStatusBarIsLightOverAHeroUntilSomethingCoversHome() {
        // The Manga look's paper has dark icons.
        assertTrue(statusBarOnLight(onLight = true, hero = false, drawer = false, panel = false))
        assertFalse(statusBarOnLight(onLight = true, hero = true, drawer = false, panel = false))
        // The app list or a full-screen panel over home: as the look says.
        assertTrue(statusBarOnLight(onLight = true, hero = true, drawer = true, panel = false))
        assertTrue(statusBarOnLight(onLight = true, hero = true, drawer = false, panel = true))
        // A look whose icons are light already stays light.
        assertFalse(statusBarOnLight(onLight = false, hero = false, drawer = false, panel = false))
    }
}
