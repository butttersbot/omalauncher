package dev.omalauncher.ui

import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether the status and navigation bars' clock and icons are dark or light over home: dark where what is
 * behind them is light. Each shipped look is held to what its own page, app list and panels come to, so a
 * look whose paper is changed has its bars looked at again.
 */
class SystemBarsTest {
    private fun look(id: String) =
        PackLook(PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/$id")), id).also { assertNull("$id: ${it.error}", it.error) }.pack)

    /** Dark icons over the page, over the open app list and over a full-screen panel, in that order. */
    private fun bars(look: PackLook) = listOf(look.barsOnLight(drawer = false, panel = false), look.barsOnLight(drawer = true, panel = false), look.barsOnLight(drawer = false, panel = true))

    @Test fun lightPaperHasDarkIconsEverywhere() {
        for (id in listOf("bujo", "custard", "dial-up", "manga", "one-bit", "riso")) assertEquals(id, listOf(true, true, true), bars(look(id)))
    }

    /** White page ink on blue: light icons over the page and the app list, dark ones over the white panels. */
    @Test fun bluePagesHaveLightIconsUntilAPanelCoversThem() {
        for (id in listOf("city-pop", "tiny-magazine")) assertEquals(id, listOf(false, false, true), bars(look(id)))
    }

    /** A pale chassis with dark panels: the other way round. */
    @Test fun instrumentHasDarkIconsUntilAPanelCoversThem() {
        assertEquals(listOf(true, true, false), bars(look("instrument")))
    }

    /** The app list over a panel is still under the panel. */
    @Test fun aPanelIsOverTheAppList() {
        for (id in listOf("city-pop", "instrument")) {
            val look = look(id)
            assertEquals(id, look.barsOnLight(drawer = false, panel = true), look.barsOnLight(drawer = true, panel = true))
        }
    }

    /** Console's page is a wallpaper nobody here has seen, so the theme's mode decides, whatever is open. */
    @Test fun consoleFollowsTheThemesMode() {
        for (mode in listOf("light", "dark")) {
            val look = PackLook(ConsolePack.withColors(mapOf("mode" to mode)))
            for (drawer in listOf(false, true)) for (panel in listOf(false, true)) assertEquals("$mode $drawer $panel", mode == "light", look.barsOnLight(drawer, panel))
        }
    }
}
