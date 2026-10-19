package dev.omalauncher.ui

import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.PackOption
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PackRegistry
import dev.omalauncher.theme.pack.consoleManifest
import dev.omalauncher.theme.pack.loadPack
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The look screen's plain rules: which choices are worth offering under which look, how the looks' pages
 * are laid out on the fold's two displays, and what is said under a look whose manifest isn't whole.
 */
class LookPickerTest {
    /** The looks shipped beside Console, by their folder. */
    private val shipped = listOf("riso", "custard", "tiny-magazine", "bujo", "dial-up", "city-pop", "instrument", "one-bit", "manga")

    private fun shippedPack(id: String) = PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/$id")), id).also { assertNull("$id: ${it.error}", it.error) }.pack

    @Test fun consoleOffersEverything() {
        assertEquals(StyleChoice.entries.toSet(), styleChoices(ConsolePack.pack))
    }

    /** Each of these draws its own page, treats icons its own way and has its own corners: only framed or bare apps is left to the user. */
    @Test fun everyOtherShippedLookOffersOnlyHomeApps() {
        for (id in shipped) assertEquals(id, setOf(StyleChoice.HomeApps), styleChoices(shippedPack(id)))
    }

    /** The choices follow what the look leaves to the user, token by token, not which look it is. */
    @Test fun aChoiceShowsOnlyWhereTheLookLeavesItToTheUser() {
        fun choices(vararg changes: Pair<String, Any>) = styleChoices(loadPack(consoleManifest(mapOf(*changes))).pack)
        val own = setOf(StyleChoice.Icons, StyleChoice.HomeApps, StyleChoice.RecentApps, StyleChoice.Corners)
        // A pack that is not Console never has Omarchy's theme or background, even with Console's every token.
        assertEquals(own, choices())
        assertEquals(own - StyleChoice.Icons, choices("icons.treatment" to "original"))
        assertEquals(own - StyleChoice.Icons, choices("icons.treatment" to "monochrome"))
        assertEquals(own - StyleChoice.RecentApps, choices("page.kind" to "paper"))
        assertEquals(own - StyleChoice.Corners, choices("shape.corners" to "round"))
        assertEquals(own - StyleChoice.Corners, choices("shape.corners" to "edge"))
    }

    /** The screen asks about the look being drawn, so a picked look that couldn't be used gets Console's choices. */
    @Test fun aLookThatCouldNotBeUsedOffersWhatConsoleDoes() {
        val pick = PackPick("lost", loadPack("not toml at all ["))
        assertTrue(pick.isConsole)
        assertEquals(StyleChoice.entries.toSet(), styleChoices(PackRegistry.resolve(pick, null)))
    }

    /** The cover display: the panel's 439dp at a unit of 1.25 is three rows of three, each page at its widest. */
    @Test fun nineLooksAreThreeAcrossOnTheCoverDisplay() {
        val grid = lookGrid(9, 439f, 1.25f)
        assertEquals(LookGrid(columns = 3, tile = 110f, gap = 15f), grid)
    }

    /** The open display: five and four, not eight and one. */
    @Test fun nineLooksAreFiveAcrossOnTheOpenDisplay() {
        val unit = 932f / 760f
        val grid = lookGrid(9, 896f, unit)
        assertEquals(5, grid.columns)
        assertEquals(107f, grid.tile, 0f)
    }

    /** Whatever the width, a row's pages and gaps fit in it, and no page is wider than its widest. */
    @Test fun aRowAlwaysFits() {
        for (count in 1..12) {
            for (width in listOf(280f, 324f, 360f, 439f, 600f, 896f, 1200f)) {
                for (unit in listOf(1f, 1.25f, 1.5f)) {
                    val grid = lookGrid(count, width, unit)
                    assertTrue("$count in $width at $unit: ${grid.columns} across", grid.columns in 1..count)
                    assertTrue("$count in $width at $unit", grid.tile * grid.columns + grid.gap * (grid.columns - 1) <= width)
                    assertTrue("$count in $width at $unit", grid.tile <= 88f * unit)
                    assertTrue("$count in $width at $unit", grid.tile > 0f)
                }
            }
        }
        assertEquals(1, lookGrid(0, 439f, 1.25f).columns)
    }

    @Test fun aWholeLookSaysNothing() {
        val riso = PackOption("riso", "Riso", loadPack(consoleManifest()))
        assertNull(lookProblem(riso, PackPick.Console))
        assertNull(lookProblem(riso, PackPick(riso.id, riso.load)))
        assertNull(lookProblem(PackOption(ConsolePack.ID, "Console", null), PackPick.Console))
    }

    @Test fun aLookWithSomethingWrongSaysSoUnderItself() {
        val odd = PackOption("odd", "Odd", loadPack(consoleManifest(mapOf("shape.radius" to 400))))
        assertEquals("some of it is wrong; Console's values stand in", lookProblem(odd, PackPick.Console))
        assertEquals("some of it is wrong; Console's values stand in", lookProblem(odd, PackPick(odd.id, odd.load)))
    }

    /** A look that can't be used says why wherever it stands, and what is drawn instead once it is the picked one. */
    @Test fun aLookThatCouldNotBeUsedSaysWhatIsShownInstead() {
        val load = loadPack("not toml at all [")
        val error = load.error
        assertNotNull(error)
        val lost = PackOption("lost", "lost", load)
        assertEquals(error, lookProblem(lost, PackPick.Console))
        assertEquals("$error Console is shown instead.", lookProblem(lost, PackPick(lost.id, load)))
        // Another look's page doesn't take the picked one's message.
        assertNull(lookProblem(PackOption("riso", "Riso", loadPack(consoleManifest())), PackPick(lost.id, load)))
    }
}
