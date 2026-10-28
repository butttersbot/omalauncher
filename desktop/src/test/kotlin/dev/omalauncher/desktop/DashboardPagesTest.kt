package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.canvas.chatHostModules
import dev.omalauncher.ui.ActivePack
import dev.omalauncher.ui.BackStack
import dev.omalauncher.ui.LocalBackStack
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The dashboard's pages and looks (desktop parity slice 5c, Boss approved design/soma-dashboard-pages-mockup.html on
 * 2026-10-28): [ pages ] shows every page small; a click goes there, a drag reorders, a right-click renames, keeps as a
 * template or removes (asking first when something is on it), + page adds one; the looks strip restyles Soma.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardPagesTest {
    private val home = Files.createTempDirectory("soma-pages").toFile().also { System.setProperty("soma.home", it.path) }

    /** The dashboard with its first page, "Builds" (two blocks) and "Money" (one). */
    private fun dashboard(file: File = File(home, "canvas.json")) = DesktopCanvas(file, "test", chat = false).also { c ->
        c.load()
        c.apply(CanvasVerb.PageRename(DESKTOP_PAGE, "Soma"))
        c.apply(CanvasVerb.PageMake("page2", "Builds"))
        c.apply(CanvasVerb.Place("module:builds", ItemKind.Module, "page2", Cell(0, 0, 6, 3)))
        c.apply(CanvasVerb.Place("module:timeline#2", ItemKind.Module, "page2", Cell(6, 0, 6, 3)))
        c.apply(CanvasVerb.PageMake("page3", "Money"))
        c.apply(CanvasVerb.Place("module:needs#2", ItemKind.Module, "page3", Cell(0, 0, 4, 2)))
        c.shown = DESKTOP_PAGE
    }

    @Test fun pagesAreAddedMovedAndJumpedToAndKeptSo() {
        val file = File(home, "kept.json")
        val c = dashboard(file)
        assertEquals(listOf("Soma", "Builds", "Money"), c.page.pages.map { it.name })
        assertTrue(c.movePage("page3", 0).applied)
        assertEquals(listOf("Money", "Soma", "Builds"), c.page.pages.map { it.name })
        assertTrue(c.movePage("page3", 2).applied)
        assertEquals(listOf("Soma", "Builds", "Money"), c.page.pages.map { it.name })
        assertFalse("already there", c.movePage("page3", 2).applied)
        val added = c.addPage()
        assertEquals("Page 4", c.page.pages.last().name)
        assertEquals(added, c.shown)
        assertTrue(c.jump(2))
        assertEquals("page2", c.shown)
        assertFalse(c.jump(9))
        // Kept: read again, the pages are in the order they were left in.
        c.movePage("page2", 0)
        assertEquals(listOf("Builds", "Soma", "Money", "Page 4"), DesktopCanvas(file, "test", chat = false).also { it.load() }.page.pages.map { it.name })
    }

    @Test fun theWordsDecideNamesDropsAndWhatRemovingAsks() {
        assertEquals("Long week", pageNameOf("  Long \n  week "))
        assertNull(pageNameOf("   "))
        assertEquals(PAGE_NAME_CHARS, pageNameOf("x".repeat(80))!!.length)
        // Three across: along a row, down a row, past the edges, past the end.
        assertEquals(2, droppedAt(0, 1.6f, 0f, 4))
        assertEquals(0, droppedAt(1, -3f, 0f, 4))
        assertEquals(2, droppedAt(1, 9f, 0f, 9))
        assertEquals(4, droppedAt(1, 0f, 1f, 9))
        assertEquals(3, droppedAt(5, 0f, 1f, 4))
        assertEquals(1, droppedAt(4, 0f, -1.2f, 9))
        assertNull(removeQuestion("Empty", 0, false))
        assertEquals("click again to remove \"Builds\" with 2 blocks", removeQuestion("Builds", 2, false))
        assertEquals("click again to remove \"Notes\" with its notes, stickers or ink", removeQuestion("Notes", 0, true))
        assertEquals("click again to remove \"Both\" with 1 block and its notes, stickers or ink", removeQuestion("Both", 1, true))
    }

    @Test fun theOverviewGoesToAPageReordersRenamesKeepsATemplateRemovesAndRestyles() = runComposeUiTest {
        Looks.fonts()
        Looks.use("city-pop")
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        val canvas = dashboard()
        val back = BackStack()
        val shelf = FileShelf(File(home, "shelf"))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalBackStack provides back) {
                Box(Modifier.size(1240.dp, 820.dp)) { CanvasScreen(canvas, host, chatHostModules(host), 0L, null, onTab = {}, chatElsewhere = {}, shelf = { shelf }, pagesOverview = true) }
            }
        }
        mainClock.advanceTimeBy(600)
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/dashboard-pages-bar.png"))
        // The bar's [ pages ] shows them small, the one shown outlined; Escape goes back.
        onNodeWithText("[ pages ]").performClick()
        mainClock.advanceTimeBy(300)
        assertTrue(canvas.overview)
        onNodeWithContentDescription("Pages overview").assertExists()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/dashboard-pages-overview.png"))
        assertTrue(back.press())
        mainClock.advanceTimeBy(300)
        assertFalse(canvas.overview)
        // A click on a page goes there.
        canvas.overview = true
        mainClock.advanceTimeBy(300)
        onNodeWithContentDescription("thumbnail Builds").performClick()
        mainClock.advanceTimeBy(300)
        assertFalse(canvas.overview)
        assertEquals("page2", canvas.shown)
        // A drag along the row reorders: Money, dragged one thumbnail back, goes before Builds.
        canvas.overview = true
        mainClock.advanceTimeBy(300)
        mainClock.autoAdvance = true
        // As far as from Builds to Money, back.
        val step = onNodeWithContentDescription("thumbnail Money").getBoundsInRoot().left - onNodeWithContentDescription("thumbnail Builds").getBoundsInRoot().left
        onNodeWithContentDescription("thumbnail Money").performMouseInput {
            moveTo(center)
            press()
            repeat(20) { moveBy(Offset(-step.value * density / 20f, 0f), delayMillis = 16) }
            release()
        }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(300)
        assertEquals(listOf("Soma", "Money", "Builds"), canvas.page.pages.map { it.name })
        assertTrue("a drag doesn't go there", canvas.overview)
        // A right-click: rename.
        onNodeWithContentDescription("thumbnail Money").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("page \"Money\"").assertExists()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/dashboard-pages-menu.png"))
        onNodeWithText("rename").performClick()
        mainClock.advanceTimeBy(300)
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("Cash")
        onNodeWithText("[ done ]").performClick()
        mainClock.advanceTimeBy(300)
        assertEquals(listOf("Soma", "Cash", "Builds"), canvas.page.pages.map { it.name })
        // Use as template: kept on the shelf by the name given.
        onNodeWithContentDescription("thumbnail Builds").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("use as template…").performClick()
        mainClock.advanceTimeBy(300)
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("Build board")
        onNodeWithText("[ keep ]").performClick()
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 5_000) { shelf.template("Build board") != null }
        mainClock.autoAdvance = false
        assertEquals(2, JSONObject(shelf.template("Build board")!!).getJSONArray("items").length())
        // Remove: a page with blocks on it asks first; the second click removes it.
        mainClock.advanceTimeBy(300)
        onNodeWithContentDescription("thumbnail Builds").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("remove page").performClick()
        mainClock.advanceTimeBy(300)
        assertTrue("it asked first", canvas.page.pages.any { it.name == "Builds" })
        onNodeWithText("click again to remove \"Builds\" with 2 blocks").performClick()
        mainClock.advanceTimeBy(300)
        assertEquals(listOf("Soma", "Cash"), canvas.page.pages.map { it.name })
        // The first page has no remove.
        onNodeWithContentDescription("thumbnail Soma").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        assertTrue(onAllNodes(androidx.compose.ui.test.hasText("remove page")).fetchSemanticsNodes().isEmpty())
        assertTrue(back.press())
        mainClock.advanceTimeBy(300)
        // + page adds one.
        onNodeWithContentDescription("add a page").performClick()
        mainClock.advanceTimeBy(300)
        assertEquals("Page 3", canvas.page.pages.last().name)
        // A look from the strip restyles Soma and is remembered.
        val other = Looks.swatches.first { it.id != ActivePack.pick.id }
        onNodeWithContentDescription("look ${other.name}").performClick()
        mainClock.advanceTimeBy(300)
        assertEquals(other.id, ActivePack.pick.id)
        assertEquals(other.id, SomaHome.read(SomaHome.settings).optString("pack"))
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/dashboard-pages-restyled.png"))
        assertNotNull(Looks.swatches.firstOrNull { it.name == "City Pop" })
    }
}
