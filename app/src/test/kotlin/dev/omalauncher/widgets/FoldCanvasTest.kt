package dev.omalauncher.widgets

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.RestorePoints
import dev.omalauncher.canvas.moduleKey
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

/** The Fold's home as the agents' canvas verbs reach it (slice 6): through LiveNotebook, home's only writer, and undone whole. */
class FoldCanvasTest {
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val saved = Notebook(
        rows = 8,
        pages = listOf(
            NotebookPage("p1", "Today", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)), NotebookItem(chrome.key, ItemKind.App, Cell(3, 0, 2, 2), app = chrome))),
            NotebookPage("p2", "Agents"),
        ),
    )
    private val needs = moduleKey(ModuleKind.Needs)!!

    private class Home(saved: Notebook) {
        val written = ArrayList<String>()
        val notebook = LiveNotebook(persist = { written += it }).apply { open(saved, kept = true) }
        var undo: String? = null
        val pad = FoldCanvasPad(notebook, addPage = { notebook.addPage() }, removePage = { notebook.deletePage(it, emptySet(), move = false) != null }, points = RestorePoints({ undo }, { undo = it }))
        fun run(line: String, request: String = "quick-1") = CanvasCommands.run(line.split(' '), pad, request, "agent:quick")
    }

    @Test fun aPageForAProjectLandsThroughTheNotebookAndUndoTakesItAway() {
        val home = Home(saved)
        assertTrue(home.run("page-make Soma").getBoolean("ok"))
        assertTrue(home.run("place needs card top-left --page Soma").getBoolean("ok"))
        assertTrue(home.run("place project:soma card right-of needs").getBoolean("ok"))
        val made = home.notebook.pages.last()
        assertEquals("Soma", made.name)
        assertEquals(made.id, home.notebook.pageOf(needs))
        assertEquals(Cell(0, 0, 3, 2), home.notebook.cell(needs))
        assertEquals(Cell(3, 0, 3, 2), home.notebook.cell("module:project:soma"))
        // Written as the one document, with the modules in it: what the launcher reads back next start.
        val back = NotebookStore.read(home.written.last())!!
        assertEquals(listOf("Today", "Agents", "Soma"), back.pages.map { it.name })
        assertEquals(setOf(needs, "module:project:soma"), back.pages.last().items.map { it.key }.toSet())
        // One request, one restore point: the page goes with what was put on it, and the rest of home is as it was.
        val undo = home.run("undo")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        assertEquals(listOf("Today", "Agents"), home.notebook.pages.map { it.name })
        assertEquals(null, home.notebook.pageOf(needs))
        assertEquals(saved.pages.map { p -> p.items.map { it.key to it.cell } }, home.notebook.snapshot().pages.map { p -> p.items.map { it.key to it.cell } })
        assertFalse(home.run("undo").getBoolean("ok"))
    }

    @Test fun anAppTakesOneCellAndAModuleIsNeverPutOverIt() {
        val home = Home(saved)
        // Chrome's old saved cell says 2x2, but an app shows as 1x1: the row under it is free beside the clock.
        home.run("place needs line top --page Today")
        assertEquals(Cell(3, 1, 3, 1), home.notebook.cell(needs))
    }

    @Test fun movesAndRemovalsAreUndoneToWhereTheyWere() {
        val home = Home(saved)
        home.run("place needs card bottom --page Today", request = "quick-1")
        val first = home.notebook.cell(needs)
        home.run("move needs top --page Agents", request = "quick-2")
        assertEquals("p2", home.notebook.pageOf(needs))
        home.run("undo")
        assertEquals("p1" to first, home.notebook.pageOf(needs) to home.notebook.cell(needs))
        home.run("remove needs", request = "quick-3")
        assertEquals(null, home.notebook.pageOf(needs))
        home.run("undo")
        assertEquals("p1" to first, home.notebook.pageOf(needs) to home.notebook.cell(needs))
    }

    @Test fun aRemovedPageOfModulesComesBackInItsPlace() {
        val home = Home(saved)
        home.run("place needs card --page Agents", request = "quick-1")
        home.run("page-remove Agents", request = "quick-2")
        assertEquals(listOf("Today"), home.notebook.pages.map { it.name })
        home.run("undo")
        assertEquals(listOf("Today", "Agents"), home.notebook.pages.map { it.name })
        assertEquals(home.notebook.pages[1].id, home.notebook.pageOf(needs))
        // A page with a widget or an app on it is his to remove by hand.
        assertFalse(home.run("page-remove Today").getBoolean("ok"))
    }

    @Test fun theFirstPageRemovedComesBackFirst() {
        val first = Notebook(rows = 8, pages = listOf(NotebookPage("p1", "Mods", listOf(NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2))))) + saved.pages.map { it.copy(id = "p" + (it.id.removePrefix("p").toInt() + 1)) })
        val home = Home(first)
        home.run("page-remove Mods")
        assertEquals(listOf("Today", "Agents"), home.notebook.pages.map { it.name })
        assertTrue(home.run("undo").getBoolean("ok"))
        assertEquals(listOf("Mods", "Today", "Agents"), home.notebook.pages.map { it.name })
        assertEquals(home.notebook.pages[0].id, home.notebook.pageOf(needs))
    }

    @Test fun anUndoThatCantFinishIsKeptToAskAgain() {
        val home = Home(saved)
        home.run("page-make Soma")
        home.run("place needs card --page Soma")
        // Home can't take the page away just now: the modules come off, the page stays, and the undo is still there.
        val stuck = FoldCanvasPad(home.notebook, { home.notebook.addPage() }, { false }, RestorePoints({ home.undo }, { home.undo = it }))
        val r = CanvasCommands.run(listOf("undo"), stuck, "q", "agent:quick")
        assertFalse(r.toString(), r.getBoolean("ok"))
        assertTrue(r.getString("error").contains("stopped partway"))
        assertTrue(home.run("undo").getBoolean("ok"))
        assertEquals(listOf("Today", "Agents"), home.notebook.pages.map { it.name })
    }

    @Test fun aPageWithHisNotesOrStickersIsNeverTakenAway() {
        val home = Home(saved)
        home.run("page-make Soma")
        home.run("place needs card --page Soma")
        val soma = home.notebook.pages.last().id
        // A sticker he put there since: the agent's removal and its undo both leave the page.
        val marked = FoldCanvasPad(home.notebook, { home.notebook.addPage() }, { home.notebook.deletePage(it, emptySet(), move = false) != null }, RestorePoints({ home.undo }, { home.undo = it }), marked = { it == soma })
        val removed = CanvasCommands.run(listOf("page-remove", "Soma"), marked, "q2", "agent:quick")
        assertFalse(removed.toString(), removed.getBoolean("ok"))
        assertTrue(removed.getString("error").contains("notes, stickers or ink"))
        val undo = CanvasCommands.run(listOf("undo"), marked, "q3", "agent:quick")
        assertTrue(home.notebook.pages.any { it.id == soma })
        assertTrue(undo.toString(), !undo.getBoolean("ok") || undo.getString("did").contains("left"))
    }

    @Test fun aPageMadeForTheMomentGoesAgainThoughNoPagesNotesAreReadYet() {
        // Loading a layout whose things trade places goes by way of a page made for the moment ("Moving"). On the Fold a page whose
        // notes aren't read counts as marked, and a page made just now never was: it must still go (storyboard critic, round 2).
        val threads = moduleKey(ModuleKind.Threads)!!
        val here = Notebook(rows = 8, pages = listOf(NotebookPage("p1", "Today", listOf(NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2)), NotebookItem(threads, ItemKind.Module, Cell(3, 0, 3, 2)))), NotebookPage("p2", "Spare")))
        val home = Home(here)
        val unread = FoldCanvasPad(home.notebook, { home.notebook.addPage() }, { home.notebook.deletePage(it, emptySet(), move = false) != null }, RestorePoints({ home.undo }, { home.undo = it }), marked = { true })
        var text: String? = null
        val shelf = dev.omalauncher.canvas.LayoutShelf({ text }, { text = it })
        shelf.save("Traded", here.copy(pages = listOf(here.pages[0].copy(items = listOf(NotebookItem(needs, ItemKind.Module, Cell(3, 0, 3, 2)), NotebookItem(threads, ItemKind.Module, Cell(0, 0, 3, 2)))), here.pages[1])))
        val said = shelf.load("Traded", unread, "q-load", "hand")
        assertEquals(said, listOf("Today", "Spare"), home.notebook.pages.map { it.name })
        assertEquals(Cell(3, 0, 3, 2), home.notebook.cell(needs))
        assertEquals(Cell(0, 0, 3, 2), home.notebook.cell(threads))
        // Pages that were there before are still kept by the rule.
        val kept = CanvasCommands.run(listOf("page-remove", "Spare"), unread, "q2", "agent:quick")
        assertTrue(kept.toString(), kept.optString("error").contains("notes, stickers or ink"))
    }

    @Test fun theBuilderIsToldHowToArrangeAndUndoModules() {
        val told = dev.omalauncher.phone.PhoneBridge.INSTRUCTIONS
        for (words in listOf("`oma-phone canvas help`", "`oma-phone canvas page-make NAME`", "`oma-phone canvas place needs card top-left --page NAME`", "`oma-phone canvas undo`", "never by tapping"))
            assertTrue(words, told.contains(words))
    }

    @Test fun nothingIsDoneBeforeHomeIsRead() {
        val notebook = LiveNotebook()
        val pad = FoldCanvasPad(notebook, { notebook.addPage() }, { false }, RestorePoints({ null }, {}))
        assertFalse(CanvasCommands.run(listOf("page-make", "Soma"), pad, "r", "agent:quick").getBoolean("ok"))
    }
}
