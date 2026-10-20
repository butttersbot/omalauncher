package dev.omalauncher.ui

import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's dock and recent apps strip are hidden only in a look drawn in panels (the Manga look, whose pages run to the foot
 * of the screen); every other look keeps them, and a phone's landscape keeps its rail beside the page in every look.
 */
class HomeDockTest {
    private val packs = moduleFile("src/main/assets/packs").listFiles()!!.filter { java.io.File(it, PackLoader.MANIFEST).isFile }.sortedBy { it.name }

    private fun panelsOf(id: String) = PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/$id")), id).pack.panels

    @Test fun onlyALookInPanelsHidesTheDock() {
        assertTrue(packs.size >= 10)
        val hidden = packs.map { it.name }.filter { !homeDockShown(panelsOf(it), side = null) }
        assertEquals(listOf("manga"), hidden)
    }

    @Test fun everyOtherLookKeepsIt() {
        for (id in listOf("city-pop", "console", "riso", "bujo")) assertTrue(id, homeDockShown(panelsOf(id), side = null))
        assertTrue(homeDockShown(null, side = null))
    }

    /** Landscape's rail stands beside the page and takes none of its height: it stays, in the Manga look too. */
    @Test fun theLandscapeRailStays() {
        val manga = panelsOf("manga")
        assertFalse(homeDockShown(manga, side = null))
        for (side in HomeSide.entries) {
            assertTrue(homeDockShown(manga, side))
            assertTrue(homeDockShown(null, side))
        }
    }
}
