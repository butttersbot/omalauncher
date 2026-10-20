package dev.omalauncher.widgets

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.RestorePoints
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.artKey
import dev.omalauncher.widgets.layout.inPanels
import dev.omalauncher.widgets.layout.shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Manga look's fields on the Fold's home: kept by LiveNotebook, home's only writer, and changed by `page.panels`. */
class FoldPanelsTest {
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val art = artKey("0123456789abcdef")
    private val saved = Notebook(
        rows = 10,
        pages = listOf(
            NotebookPage(
                "p1", "Today",
                listOf(
                    NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 6, 4)),
                    NotebookItem(art, ItemKind.Art, Cell(0, 4, 3, 2)),
                    NotebookItem("module:needs", ItemKind.Module, Cell(3, 4, 3, 2)),
                    NotebookItem(chrome.key, ItemKind.App, Cell(0, 6, 6, 4), app = chrome),
                ),
                panels = "opening",
            ),
            NotebookPage("p2", "Later"),
        ),
    )

    private class Home(saved: Notebook) {
        val written = ArrayList<String>()
        val notebook = LiveNotebook(persist = { written += it }).apply { open(saved, kept = true) }
        var undo: String? = null
        val pad = FoldCanvasPad(notebook, addPage = { notebook.addPage() }, removePage = { notebook.deletePage(it, emptySet(), move = false) != null }, points = RestorePoints({ undo }, { undo = it }))
        fun run(line: String, request: String = "quick-1") = CanvasCommands.run(line.split(' '), pad, request, "agent:quick")
    }

    private fun shape(n: Notebook) = n.pages.map { p -> listOf(p.id, p.name, p.panels, p.items.associate { it.key to it.cell }) }

    @Test fun homeKeepsAPagesPanelsAndItsArt() {
        val home = Home(saved)
        assertEquals("opening", home.notebook.pages[0].panels)
        assertEquals(shape(saved), shape(home.notebook.snapshot()))
        assertEquals(ItemKind.Art, home.notebook.snapshot().pages[0].items.first { it.key == art }.kind)
        // Saved and read back as they were: nothing drops them on the way to disk.
        home.notebook.save()
        assertEquals(shape(saved), shape(NotebookStore.read(home.written.last())!!))
        // A page renamed or moved keeps its layout.
        assertTrue(home.notebook.renamePage("p1", "Now"))
        assertTrue(home.notebook.movePage("p1", 1))
        assertEquals("opening", home.notebook.pages.first { it.id == "p1" }.panels)
    }

    @Test fun deletingAPageMovesItsArtLikeAnyTile() {
        val home = Home(saved)
        val gone = home.notebook.deletePage("p1", widgets = setOf("clock.owl"), move = true)!!
        assertTrue(gone.toString(), art in gone.moved)
        assertTrue(gone.dropped.isEmpty())
        assertEquals("p2", home.notebook.pageOf(art))
        assertEquals("art", home.notebook.board("p2").tiles.first { it.name == art }.kind)
    }

    @Test fun setPanelsChangesTheLayoutAndCellsAtOnce() {
        val home = Home(saved)
        val written = home.written.size
        assertTrue(home.notebook.setPanels("p1", null, mapOf("clock.owl" to Cell(0, 0, 3, 2), "elsewhere.owl" to Cell(0, 0, 1, 1))))
        assertNull(home.notebook.pages[0].panels)
        assertEquals(Cell(0, 0, 3, 2), home.notebook.cell("clock.owl"))
        // Only what is on that page moves.
        assertNull(home.notebook.cell("elsewhere.owl"))
        assertEquals(written + 1, home.written.size)
        assertTrue(!home.notebook.setPanels("p9", "beats"))
    }

    @Test fun anAgentRecutsAPageThroughTheNotebookAndUndoPutsItBack() {
        val home = Home(saved)
        val did = home.run("page.panels Today splash")
        assertTrue(did.toString(), did.getBoolean("ok"))
        val now = home.notebook.snapshot()
        assertEquals("splash", now.pages[0].panels)
        // The clock is heaviest (with Chrome, which home shows as one cell); it takes the one panel.
        assertEquals(mapOf("clock.owl" to Cell(0, 0, 6, 10)), now.pages[0].items.associate { it.key to it.cell })
        assertTrue(inPanels(now.copy(columns = GRID_COLUMNS), now.pages[0]))
        // Needs you and Chrome went to a strip page made at the end; the art left home.
        val made = now.pages.last()
        assertEquals(listOf("Page 3", "yonkoma"), listOf(made.name, made.panels))
        assertEquals(PanelLayouts.panels("yonkoma", GRID_COLUMNS, 10).take(2).map { it.cell }.toSet(), made.items.map { it.cell }.toSet())
        assertTrue(now.pages.none { p -> p.items.any { it.key == art } })
        assertEquals(shape(now), shape(NotebookStore.read(home.written.last())!!))
        val undo = home.run("undo")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        assertEquals(shape(saved).map { it.take(3) }, shape(home.notebook.snapshot()).map { it.take(3) })
        // Every item back in its cell (an app as the one cell home shows it in).
        assertEquals(saved.pages[0].items.associate { it.key to it.cell?.shown(it.kind != ItemKind.App) }, home.notebook.snapshot().pages[0].items.associate { it.key to it.cell?.shown(it.kind != ItemKind.App) })
    }
}
