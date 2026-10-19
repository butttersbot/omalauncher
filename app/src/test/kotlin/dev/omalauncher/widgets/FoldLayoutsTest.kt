package dev.omalauncher.widgets

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.LayoutShelf
import dev.omalauncher.canvas.LayoutsSource
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.RestorePoints
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.canvas.withLayouts
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PinnedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved layouts on the Fold's home (piece D): loaded through LiveNotebook, home's only writer, and undone whole. */
class FoldLayoutsTest {
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val needs = moduleKey(ModuleKind.Needs)!!
    private val threads = moduleKey(ModuleKind.Threads)!!
    private val saved = Notebook(
        rows = 8,
        pages = listOf(
            NotebookPage("p1", "Today", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)), NotebookItem(chrome.key, ItemKind.App, Cell(3, 0, 1, 1), app = chrome))),
            NotebookPage("p2", "Soma", listOf(NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2)), NotebookItem(threads, ItemKind.Module, Cell(3, 0, 3, 2)))),
        ),
    )

    private class Home(saved: Notebook, marked: (String) -> Boolean = { false }) {
        val written = ArrayList<String>()
        val notebook = LiveNotebook(persist = { written += it }).apply { open(saved, kept = true) }
        var undo: String? = null
        var shelfText: String? = null
        val shelf = LayoutShelf({ shelfText }, { shelfText = it }, look = { "manga" })
        val pad = FoldCanvasPad(notebook, addPage = { notebook.addPage() }, removePage = { notebook.deletePage(it, emptySet(), move = false) != null },
            points = RestorePoints({ undo }, { undo = it }), marked = marked).withLayouts(shelf)
        fun run(line: String, request: String = "quick-" + System.nanoTime()) = CanvasCommands.run(line.split(' '), pad, request, "agent:quick")
        fun shape() = notebook.snapshot().pages.map { p -> p.name to p.items.map { it.key to it.cell }.toSet() }
    }

    private fun shape(n: Notebook) = n.pages.map { p -> p.name to p.items.map { it.key to it.cell }.toSet() }

    @Test fun aLayoutComesBackOnHomeAndUndoTakesItAwayAgain() {
        val home = Home(saved)
        assertTrue(home.run("layout.save Work").getBoolean("ok"))
        assertEquals("manga", home.shelf.get("Work")!!.look)
        // Since: a page came with a copy of Needs you, the Soma page went (its modules with it), and the clock and Chrome
        // traded places.
        assertTrue(home.run("page-make Drive").getBoolean("ok"))
        assertTrue(home.run("place needs card top-left --page Drive").getBoolean("ok"))
        assertTrue(home.run("page-remove Soma").getBoolean("ok"))
        home.notebook.setCell("clock.owl", Cell(0, 2, 3, 2))
        home.notebook.setCell(chrome.key, Cell(0, 0, 1, 1))
        home.notebook.save()
        assertEquals(listOf("Today", "Drive"), home.notebook.pages.map { it.name })
        val before = home.shape()
        val loaded = home.run("layout.load Work", request = "quick-load")
        assertTrue(loaded.toString(), loaded.getBoolean("ok"))
        // The pages, names and cells are the saved ones; the page made again has an id home gave it.
        assertEquals(shape(saved), home.shape())
        // Written as the one document the launcher reads back next start.
        assertEquals(shape(saved), shape(NotebookStore.read(home.written.last())!!))
        val undo = home.run("undo")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        assertEquals(before, home.shape())
    }

    @Test fun aPageWithHisInkIsKeptAndTheRestIsLaid() {
        var ink = ""
        val home = Home(saved, marked = { it == ink })
        assertTrue(home.run("layout.save Work").getBoolean("ok"))
        assertTrue(home.run("page-make Sketch").getBoolean("ok"))
        ink = home.notebook.pages.last().id
        assertTrue(home.run("place pocket card --page Sketch").getBoolean("ok"))
        val did = home.run("layout.load Work").getString("did")
        assertTrue(did, did.contains("left: the page \"Sketch\" has notes, stickers or ink on it, so it stays;"))
        assertEquals(shape(saved) + ("Sketch" to emptySet<Pair<String, Cell?>>()), home.shape())
    }

    @Test fun theTileLoadsAndSavesAsHisOwnChangeThatUndoTakesBack() {
        val home = Home(saved)
        home.shelf.save("Work", home.pad.notebook())
        assertTrue(home.run("remove needs").getBoolean("ok"))
        val tile = LayoutsSource(home.shelf, pad = { home.pad }, now = { 42L })
        val said = tile.load("Work")
        assertTrue(said.text, said.ok)
        assertEquals(shape(saved), home.shape())
        val kept = tile.save()
        assertEquals(LayoutsSource.Said("kept as \"Layout 1\"", true), kept)
        assertTrue(home.run("undo").getBoolean("ok"))
        assertEquals(null, home.notebook.pageOf(needs))
        // Before home is open, the tile says so and changes nothing.
        val closed = LayoutsSource(home.shelf, pad = { null }).load("Work")
        assertFalse(closed.ok)
        assertTrue(closed.text, closed.text.contains("still loading"))
    }

    /** Critic round 1: a page made again took a fresh number that a later page of the layout had, and the load stopped partway. */
    @Test fun pagesMadeAgainKeepTheirIdsSoNoneMeetsAnother() {
        val three = Notebook(rows = 8, pages = listOf(
            NotebookPage("p1", "One", listOf(NotebookItem("a.owl", ItemKind.Widget, Cell(0, 0, 2, 2)))),
            NotebookPage("p2", "Two", listOf(NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2)))),
            NotebookPage("p3", "Three", listOf(NotebookItem(threads, ItemKind.Module, Cell(0, 0, 3, 2)))),
        ))
        for (kept in listOf(listOf("p2"), listOf("p3"), listOf("p1"))) {
            val home = Home(three.copy(pages = three.pages.filter { it.id in kept }))
            home.shelf.save("Three", three)
            val loaded = home.run("layout.load Three")
            assertTrue("$kept: $loaded", loaded.getBoolean("ok"))
            assertEquals(shape(three), home.shape())
            assertEquals(listOf("p1", "p2", "p3"), home.notebook.pages.map { it.id })
            assertTrue(home.run("undo").getBoolean("ok"))
            assertEquals(kept, home.notebook.pages.map { it.id })
        }
        // Saved in another order than home's numbers: still laid whole.
        val home = Home(three.copy(pages = three.pages.take(1)))
        home.shelf.save("Odd", three.copy(pages = listOf(three.pages[0], three.pages[2], three.pages[1])))
        assertTrue(home.run("layout.load Odd").getBoolean("ok"))
        assertEquals(listOf("One", "Three", "Two"), home.notebook.pages.map { it.name })
    }

    /** Critic round 1: undoing a load that took two pages away stopped partway, and asked again it lost a module. */
    @Test fun undoingALoadThatTookPagesAwayBringsEachBackWithWhatWasOnIt() {
        val pocket = moduleKey(ModuleKind.Pocket)!!
        val start = Notebook(rows = 8, pages = listOf(
            NotebookPage("p1", "A", listOf(NotebookItem("a.owl", ItemKind.Widget, Cell(0, 0, 2, 2)))),
            NotebookPage("p2", "B", listOf(NotebookItem(threads, ItemKind.Module, Cell(0, 0, 3, 2)))),
            NotebookPage("p3", "C", listOf(NotebookItem(pocket, ItemKind.Module, Cell(0, 0, 3, 2)))),
        ))
        val home = Home(start)
        home.shelf.save("Only A", start.copy(pages = start.pages.take(1)))
        assertTrue(home.run("layout.load Only A").getBoolean("ok"))
        assertEquals(listOf("A"), home.notebook.pages.map { it.name })
        val undo = home.run("undo")
        assertTrue(undo.toString(), undo.getBoolean("ok") && !undo.getString("did").contains("left"))
        assertEquals(shape(start), home.shape())
        assertEquals(listOf("p1", "p2", "p3"), home.notebook.pages.map { it.id })
    }

    @Test fun aMangaLayoutComesBackCutIntoItsPanelsOnHomeAndUndoUncutsIt() {
        val home = Home(saved)
        val manga = saved.copy(pages = saved.pages.map { if (it.id == "p2") it.copy(panels = "beats") else it })
        // The Soma page in Three beats: Needs you and Threads each in a panel's cells.
        val beats = dev.omalauncher.widgets.layout.PanelLayouts.panels("beats", 6, 8).map { it.cell }
        val cut = manga.copy(pages = manga.pages.map { p -> if (p.id == "p2") p.copy(items = p.items.mapIndexed { i, it -> it.copy(cell = beats[i]) }) else p })
        home.shelf.save("Manga", cut)
        assertTrue(home.run("layout.load Manga").getBoolean("ok"))
        assertEquals("beats", home.notebook.snapshot().pages[1].panels)
        assertEquals(shape(cut), home.shape())
        assertEquals("beats", NotebookStore.read(home.written.last())!!.pages[1].panels)
        assertTrue(home.run("undo").getBoolean("ok"))
        assertEquals(null, home.notebook.snapshot().pages[1].panels)
        assertEquals(shape(saved), home.shape())
    }

    @Test fun theTilesThumbnailCutsItsPageThroughPagePanelsAndUndoTakesItBack() {
        val home = Home(saved)
        val tile = LayoutsSource(home.shelf, pad = { home.pad }, now = { 5L })
        val said = tile.panels("p2", "beats")
        assertTrue(said.text, said.ok)
        assertEquals("beats", home.notebook.snapshot().pages[1].panels)
        assertTrue(home.run("undo").getBoolean("ok"))
        assertEquals(null, home.notebook.snapshot().pages[1].panels)
        assertEquals(shape(saved), home.shape())
        assertFalse(tile.panels("p2", "nonsense").ok)
    }
}
