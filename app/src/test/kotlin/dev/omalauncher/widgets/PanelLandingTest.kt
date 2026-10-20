package dev.omalauncher.widgets

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.RestorePoints
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.OVERFLOW_PANELS
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.inPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agents' canvas words on the Fold's home in the Manga look (piece B): a module placed on a manga page fills an empty panel. */
class PanelLandingTest {
    private val rows = 10
    private val opening = PanelLayouts.panels("opening", GRID_COLUMNS, rows)

    private class Home(pages: List<NotebookPage>) {
        val notebook = LiveNotebook().apply { open(Notebook(rows = 10, pages = pages), kept = true) }
        var undo: String? = null
        val pad = FoldCanvasPad(notebook, addPage = { notebook.addPage() }, removePage = { notebook.deletePage(it, emptySet(), move = false) != null }, points = RestorePoints({ undo }, { undo = it }))

        fun run(line: String, request: String = "quick-1") = CanvasCommands.run(line.split(' '), pad, request, "agent:quick")
    }

    private fun widget(key: String, cell: Cell) = NotebookItem(key, ItemKind.Widget, cell)

    private fun mangaPage(id: String, name: String, vararg filled: Int) = NotebookPage(id, name, filled.map { widget("w$it.owl", opening[it].cell) }, panels = "opening")

    @Test fun aModuleAnAgentPlacesOnAMangaPageFillsTheEmptyPanelNearestItsSize() {
        val home = Home(listOf(mangaPage("p1", "Today", 0, 1)))
        val did = home.run("place needs card --page Today")
        assertTrue(did.toString(), did.getBoolean("ok"))
        // A card is 3 by 2: the empty middle panel, not cells somewhere between the lines.
        assertEquals(opening[2].cell, home.notebook.cell("module:needs"))
        val n = home.notebook.snapshot()
        assertTrue(inPanels(n, n.pages[0]))
    }

    @Test fun withNoEmptyPanelThereItGoesOnToTheNextPageWithOneAndUndoTakesItBack() {
        val home = Home(listOf(mangaPage("p1", "Today", 0, 1, 2, 3, 4), mangaPage("p2", "Later", 0, 2)))
        val did = home.run("place needs --page Today")
        assertTrue(did.toString(), did.getBoolean("ok"))
        assertEquals("p2", home.notebook.pageOf("module:needs"))
        assertTrue(home.notebook.cell("module:needs") in listOf(opening[1].cell, opening[3].cell, opening[4].cell))
        val n = home.notebook.snapshot()
        assertTrue(n.pages.all { inPanels(n, it) })
        assertTrue(home.run("undo").getBoolean("ok"))
        assertEquals(null, home.notebook.pageOf("module:needs"))
    }

    @Test fun withNoPanelLeftAnywhereAStripPageIsMadeForIt() {
        val home = Home(listOf(mangaPage("p1", "Today", 0, 1, 2, 3, 4)))
        val did = home.run("place needs --page Today")
        assertTrue(did.toString(), did.getBoolean("ok"))
        val n = home.notebook.snapshot()
        assertEquals(listOf("opening", OVERFLOW_PANELS), n.pages.map { it.panels })
        assertEquals(n.pages[1].id, home.notebook.pageOf("module:needs"))
        assertTrue(n.pages.all { inPanels(n, it) })
    }

    @Test fun aMoveOnAMangaPageGoesToAnEmptyPanelAndAResizeIsRefused() {
        val home = Home(listOf(mangaPage("p1", "Today", 0).let { it.copy(items = it.items + NotebookItem("module:needs", ItemKind.Module, opening[3].cell)) }))
        val resized = home.run("resize needs card-wide")
        assertFalse(resized.toString(), resized.getBoolean("ok"))
        assertTrue(resized.getString("error"), resized.getString("error").contains("panels"))
        assertEquals(opening[3].cell, home.notebook.cell("module:needs"))
        val moved = home.run("move needs top --page Today")
        assertTrue(moved.toString(), moved.getBoolean("ok"))
        val n = home.notebook.snapshot()
        assertTrue(inPanels(n, n.pages[0]))
    }

    @Test fun aFreePageBesideMangaPagesIsArrangedExactlyAsBefore() {
        val free = NotebookPage("p2", "Free", listOf(widget("clock.owl", Cell(0, 0, 6, 3))))
        val withManga = Home(listOf(mangaPage("p1", "Today", 0, 1), free))
        val without = Home(listOf(NotebookPage("p1", "Today"), free))
        for (line in listOf("place needs card top-right --page Free", "move needs bottom --page Free", "resize needs card-big")) {
            val a = withManga.run(line)
            val b = without.run(line)
            assertEquals(line, b.toString(), a.toString())
            assertEquals(line, without.notebook.cell("module:needs"), withManga.notebook.cell("module:needs"))
        }
        assertEquals(null, withManga.notebook.pages.first { it.id == "p2" }.panels)
    }
}
