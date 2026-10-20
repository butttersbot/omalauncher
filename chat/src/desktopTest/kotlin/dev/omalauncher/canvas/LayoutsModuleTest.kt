package dev.omalauncher.canvas

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.applyVerb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Layouts module (Manga look, piece D) operated: a tap on a layout asks inside the tile and loads only on yes, "Save this"
 * keeps home under the next free name, and "Art: Ink / Color" is the art preference. Nothing loads on the first tap.
 */
class LayoutsModuleTest {
    private val needs = moduleKey(ModuleKind.Needs)!!

    private class Pad(var n: Notebook) : CanvasPad {
        override val rules = ScreenRules()
        val applied = ArrayList<Pair<String, String>>()
        override fun notebook() = n
        override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
            for (v in verbs) n = applyVerb(n, v).notebook
            applied += request to actor
            return null
        }
        override fun lastRestorePoint(): RestorePoint? = null
        override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? = null
    }

    private val home = Notebook(rows = 4, pages = listOf(NotebookPage("p1", "Today", listOf(NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2))))))

    @After fun pickReset() = MangaPrefs.open(null) {}

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aTapAsksYesLoadsAndNoGoesBack() = androidx.compose.ui.test.runComposeUiTest {
        var text: String? = null
        val shelf = LayoutShelf({ text }, { text = it }, look = { "manga" })
        shelf.save("Work", home)
        val pad = Pad(home.copy(pages = listOf(home.pages[0].copy(items = emptyList()))))
        val source = LayoutsSource(shelf, pad = { pad }, now = { 7L })
        setContent { LayoutsModule(source, ModuleSize.Card, 0L, Modifier.size(420.dp, 260.dp)) }
        onNodeWithText("Work").performClick()
        onNodeWithText("Load \"Work\"? Home becomes that layout; undo takes it back.").assertExists()
        // No goes back to the list, and nothing was loaded.
        onNodeWithText("[ no ]").performClick()
        onNodeWithText("Work").assertExists()
        assertTrue(pad.applied.isEmpty())
        onNodeWithText("Work").performClick()
        onNodeWithText("[ yes ]").performClick()
        // Loaded as his own change, as one request, and said.
        assertEquals(listOf("tile-7" to "hand"), pad.applied)
        assertEquals(listOf(needs), pad.n.pages[0].items.map { it.key })
        onNodeWithText("laid out the layout \"Work\" (saved in the look \"manga\")").assertExists()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun saveThisKeepsHomeUnderTheNextFreeNameAndShowsIt() = androidx.compose.ui.test.runComposeUiTest {
        var text: String? = null
        val shelf = LayoutShelf({ text }, { text = it })
        val source = LayoutsSource(shelf, pad = { Pad(home) })
        setContent { LayoutsModule(source, ModuleSize.Card, 0L, Modifier.size(420.dp, 260.dp)) }
        onNodeWithText("Nothing saved yet: save this home to come back to it.").assertExists()
        onNodeWithText("[ save this ]").performClick()
        onNodeWithText("kept as \"Layout 1\"").assertExists()
        onNodeWithText("Layout 1").assertExists()
        onNodeWithText("[ save this ]").performClick()
        onNodeWithText("Layout 2").assertExists()
        assertEquals(listOf("Layout 2", "Layout 1"), shelf.list().map { it.name })
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aLoadThatCantBeDoneIsSaidInTheTile() = androidx.compose.ui.test.runComposeUiTest {
        var text: String? = null
        val shelf = LayoutShelf({ text }, { text = it })
        shelf.save("Work", home)
        // Home not open yet: nothing loads, and the tile says why.
        val source = LayoutsSource(shelf, pad = { null })
        setContent { LayoutsModule(source, ModuleSize.Card, 0L, Modifier.size(420.dp, 260.dp)) }
        onNodeWithText("Work").performClick()
        onNodeWithText("[ yes ]").performClick()
        onNodeWithText("Home is still loading. Try again in a moment.").assertExists()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun artIsInkUntilHePicksColorAndHisPickIsKept() = androidx.compose.ui.test.runComposeUiTest {
        val kept = ArrayList<Boolean>()
        MangaPrefs.open(null) { kept += it }
        MangaPrefs.lookDefault = null
        assertTrue(MangaPrefs.artInk)
        // The look's own default counts until he picks.
        MangaPrefs.lookDefault = false
        assertFalse(MangaPrefs.artInk)
        MangaPrefs.lookDefault = null
        val source = LayoutsSource(LayoutShelf({ null }, {}), pad = { null })
        setContent { LayoutsModule(source, ModuleSize.Card, 0L, Modifier.size(420.dp, 260.dp)) }
        onNodeWithText("[ color ]").performClick()
        assertFalse(MangaPrefs.artInk)
        onNodeWithText("[ ink ]").performClick()
        assertTrue(MangaPrefs.artInk)
        assertEquals(listOf(false, true), kept)
        MangaPrefs.lookDefault = false
        assertTrue("his pick beats the look's", MangaPrefs.artInk)
        MangaPrefs.lookDefault = null
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aScreenWithoutLayoutsSaysWhereTheyAreAndTheLineAsksOnOneRow() = androidx.compose.ui.test.runComposeUiTest {
        var text: String? = null
        val shelf = LayoutShelf({ text }, { text = it })
        shelf.save("Work", home)
        var which by androidx.compose.runtime.mutableStateOf(0)
        val source = LayoutsSource(shelf, pad = { Pad(home) })
        setContent {
            when (which) {
                0 -> LayoutsModule(null, ModuleSize.Card, 0L, Modifier.size(420.dp, 260.dp))
                else -> LayoutsModule(source, ModuleSize.Line, 0L, Modifier.size(600.dp, 60.dp))
            }
        }
        onNodeWithText("Saved layouts are kept on the Fold.").assertExists()
        which = 1
        onNodeWithText("[ Work ]").performClick()
        onNodeWithText("Load \"Work\"?").assertExists()
        onNodeWithText("[ no ]").performClick()
        onNodeWithText("[ Work ]").assertExists()
        // What a tap came to stands in for the names on one row, until he taps it away.
        onNodeWithText("[ save this ]").performClick()
        onNodeWithText("kept as \"Layout 1\"").assertExists()
        onNodeWithText("[ Work ]").assertDoesNotExist()
        onNodeWithText("kept as \"Layout 1\"").performClick()
        onNodeWithText("[ Work ]").assertExists()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun onAPageCutIntoPanelsTheTileOffersItsLayoutsAndATapCutsThePage() = androidx.compose.ui.test.runComposeUiTest {
        val tile = moduleKey(ModuleKind.Layouts)!!
        val cut = Notebook(rows = 8, pages = listOf(
            NotebookPage("p1", "Free", listOf(NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2)))),
            NotebookPage("p2", "Manga", listOf(NotebookItem(tile, ItemKind.Module, Cell(0, 0, 3, 2))), panels = "opening"),
        ))
        val pad = Pad(cut)
        val sent = ArrayList<Pair<List<String>, String>>()
        val source = LayoutsSource(LayoutShelf({ null }, {}), pad = { pad }, now = { 9L }, words = { w, _, request, actor ->
            sent += w to "$request/$actor"
            org.json.JSONObject().put("ok", true).put("did", "\"Manga\" is now Beats")
        })
        var key by androidx.compose.runtime.mutableStateOf(tile)
        setContent { LayoutsModule(source, ModuleSize.Card, 0L, Modifier.size(420.dp, 300.dp), key = key) }
        onNodeWithContentDescription("panels Opening, this page").assertExists()
        onNodeWithContentDescription("panels Three beats").performClick()
        // Through the canvas words, by the page's id, as his own change.
        assertEquals(listOf(listOf("page.panels", "p2", "beats") to "tile-9/hand"), sent)
        onNodeWithText("\"Manga\" is now Beats").assertExists()
        // On a free page (here, its first copy's page has no panels) there is no row.
        key = needs
        waitForIdle()
        onNodeWithContentDescription("panels Three beats").assertDoesNotExist()
    }
}
