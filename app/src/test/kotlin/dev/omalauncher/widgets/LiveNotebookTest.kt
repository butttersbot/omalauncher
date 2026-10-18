package dev.omalauncher.widgets

import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.LinuxEntry
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.ShortcutEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotebookTest {
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val camera = PinnedApp("app:camera#0", "Camera", "com.camera/.Main")
    private val mail = PinnedApp("app:mail#10", "Mail", "com.mail/.Main", 10)
    private val foot = PinnedApp("linux:foot", "Foot", linux = LinuxEntry("Foot", "foot", false, "foot"))

    private val saved =
        Notebook(
            rows = 10,
            pages =
                listOf(
                    NotebookPage(
                        "p1",
                        "Today",
                        listOf(
                            NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)),
                            NotebookItem(chrome.key, ItemKind.App, Cell(0, 2, 1, 1), app = chrome),
                            NotebookItem("folder:a", ItemKind.Folder, Cell(1, 2, 1, 1), apps = listOf(mail, foot)),
                        ),
                    ),
                    NotebookPage("p2", "Empty on purpose"),
                    NotebookPage("p3", "Trip", listOf(NotebookItem("map.owl", ItemKind.Widget, Cell(0, 0, 6, 4)))),
                ),
            dock = listOf(camera),
        )

    /** What was persisted, newest last. */
    private val writes = ArrayList<String>()

    private fun live(
        notebook: Notebook = saved,
        kept: Boolean = true,
    ) = LiveNotebook(persist = { writes += it }, now = { 1000 }).also { it.open(notebook, kept) }

    /** As the next start of the launcher would have it. */
    private fun reopened(): LiveNotebook = live(NotebookStore.read(writes.last())!!)

    @Test fun opensWhatWasSavedAndSavesItBackTheSame() {
        val live = live()
        assertEquals(saved, live.snapshot())
        assertEquals(10, live.rows)
        assertEquals(listOf("p1", "p2", "p3"), live.pages.map { it.id })
        assertEquals("Trip", live.name("p3"))
        assertEquals(Cell(0, 0, 6, 4), live.cell("map.owl"))
        assertEquals("p3", live.pageOf("map.owl"))
        // An app in a folder is where its folder is, with no cell of its own.
        assertEquals("p1", live.pageOf(mail.key))
        assertNull(live.cell(mail.key))
        assertNull(live.pageOf("nothing.owl"))
        // Opening writes nothing.
        assertTrue(writes.isEmpty())
    }

    /** AC-10. */
    @Test fun emptyPagesSurviveASaveAndLoad() {
        val live = live()
        // The page that was empty stays, and so does one that is emptied.
        live.move("map.owl", "p1")
        assertEquals(listOf("p1", "p2", "p3"), live.pages.map { it.id })
        val again = reopened()
        assertEquals(listOf("p1" to "Today", "p2" to "Empty on purpose", "p3" to "Trip"), again.pages.map { it.id to it.name })
        assertEquals("p1", again.pageOf("map.owl"))
        assertEquals(emptyList<NotebookItem>(), again.snapshot().pages[1].items)
        assertEquals(emptyList<NotebookItem>(), again.snapshot().pages[2].items)
        // Removing its last item doesn't remove a page either.
        again.forget("map.owl")
        again.unpin(chrome.key)
        again.unpin("folder:a")
        again.forget("clock.owl")
        assertEquals(3, reopened().pages.size)
        assertTrue(reopened().snapshot().pages.all { it.items.isEmpty() })
    }

    @Test fun aNewPageGoesLastWithAFreeNameAndId() {
        val live = live()
        val id = live.addPage()
        assertEquals("p4", id)
        assertEquals(listOf("p1", "p2", "p3", "p4"), live.pages.map { it.id })
        assertEquals("Page 4", live.name(id!!))
        assertEquals(live.snapshot(), reopened().snapshot())
        // Ids are never reused for a page that is still there, and a taken name is passed over.
        val named = live(Notebook(9, listOf(NotebookPage("p7", "Page 3"), NotebookPage("home", "Home"))))
        assertEquals("p8", named.addPage())
        assertEquals("Page 4", named.name("p8"))
    }

    @Test fun anOfferedPageIsTakenBackOnlyWhileEmptyAndNeverTheLastOne() {
        val live = live()
        val offered = live.addPage()!!
        assertTrue(live.withdrawPage(offered))
        assertEquals(listOf("p1", "p2", "p3"), live.pages.map { it.id })
        assertFalse(live.withdrawPage("p1"))
        assertFalse(live.withdrawPage("nowhere"))
        val used = live.addPage()!!
        live.move("clock.owl", used)
        assertFalse(live.withdrawPage(used))
        val single = live(Notebook(9, listOf(NotebookPage("p1", "Page 1"))))
        assertFalse(single.withdrawPage("p1"))
    }

    @Test fun aNewWidgetFileStartsOnThePageItAsksForByPosition() {
        val live = live()
        live.adopt(listOf("new.owl" to 2, "first.owl" to 1, "zero.owl" to 0))
        assertEquals("p2", live.pageOf("new.owl"))
        assertEquals("p1", live.pageOf("first.owl"))
        assertEquals("p1", live.pageOf("zero.owl"))
        // Placed by home when its page is laid out.
        assertNull(live.cell("new.owl"))
        assertEquals(NotebookItem("new.owl", ItemKind.Widget, null), reopened().snapshot().pages[1].items.single())
    }

    @Test fun widgetsAskingPastTheLastPageGetNewPagesInOrder() {
        val live = live()
        live.adopt(listOf("far.owl" to 9, "near.owl" to 5, "near-too.owl" to 5))
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), live.pages.map { it.id })
        assertEquals("p4", live.pageOf("near.owl"))
        assertEquals("p4", live.pageOf("near-too.owl"))
        assertEquals("p5", live.pageOf("far.owl"))
    }

    @Test fun aWidgetsFileNeverMovesItFromWhereTheOwnerHasIt() {
        val live = live()
        live.adopt(listOf("clock.owl" to 3, "map.owl" to 1))
        assertEquals("p1", live.pageOf("clock.owl"))
        assertEquals("p3", live.pageOf("map.owl"))
        assertEquals(Cell(0, 0, 3, 2), live.cell("clock.owl"))
        assertTrue(writes.isEmpty())
        // Nor one installed from the store to the page that was showing, whatever its file asks.
        live.expect("store.owl", "p3")
        live.adopt(listOf("store.owl" to 1))
        assertEquals("p3", live.pageOf("store.owl"))
    }

    @Test fun aWidgetWhoseFileIsGoneKeepsItsPlace() {
        // Nothing here knows which files exist: an entry stays until the engine says the file was deleted.
        val live = live()
        live.adopt(emptyList())
        assertEquals(saved, live.snapshot())
        live.forget("map.owl")
        assertNull(live.pageOf("map.owl"))
        assertNull(live.cell("map.owl"))
        assertEquals(emptyList<NotebookItem>(), reopened().snapshot().pages[2].items)
    }

    @Test fun movingBetweenPages() {
        val live = live()
        live.move("clock.owl", "p2")
        live.move("folder:a", "p3")
        live.move(chrome.key, "p3")
        assertEquals("p2", live.pageOf("clock.owl"))
        assertEquals("p3", live.pageOf("folder:a"))
        assertEquals("p3", live.pageOf(mail.key))
        assertEquals("p3", live.pageOf(chrome.key))
        // Cells go along as they are.
        assertEquals(Cell(0, 0, 3, 2), live.cell("clock.owl"))
        // To a page that doesn't exist: nothing happens.
        live.move("clock.owl", "p9")
        assertEquals("p2", live.pageOf("clock.owl"))
        assertEquals(live.snapshot(), reopened().snapshot())
    }

    @Test fun pinningGroupingAndFolders() {
        val live = live()
        live.pin(camera, "p3")
        assertEquals("p3", live.pageOf(camera.key))
        // Dropped on an app: both go into a new folder in that app's cell, on its page.
        val folder = live.group(camera.key, chrome.key)!!
        assertEquals("folder:rs", folder)
        assertEquals("p1", live.pageOf(folder))
        assertEquals(Cell(0, 2, 1, 1), live.cell(folder))
        assertNull(live.cell(chrome.key))
        assertEquals(listOf(chrome.key, camera.key), live.apps.filter { it.folder == folder }.map { it.app.key })
        // Dropped on a folder: last in it.
        assertEquals("folder:a", live.group(camera.key, "folder:a"))
        assertEquals(listOf(mail.key, foot.key, camera.key), live.apps.filter { it.folder == "folder:a" }.map { it.app.key })
        // The folder it left has one app, so it goes away and the app takes its cell and page.
        assertFalse(folder in live.folders)
        assertEquals(Cell(0, 2, 1, 1), live.cell(chrome.key))
        assertEquals("p1", live.pageOf(chrome.key))
        // Sorting within the folder.
        live.moveBefore(camera.key, mail.key)
        assertEquals(listOf(camera.key, mail.key, foot.key), live.apps.filter { it.folder == "folder:a" }.map { it.app.key })
        live.moveBefore(camera.key, null)
        assertEquals(listOf(mail.key, foot.key, camera.key), live.apps.filter { it.folder == "folder:a" }.map { it.app.key })
        // Not onto itself, a widget, or nothing.
        assertNull(live.group(chrome.key, chrome.key))
        assertNull(live.group(chrome.key, "clock.owl"))
        assertNull(live.group("app:none", chrome.key))
        // An app moved out lands on the grid on its own.
        live.move(camera.key, "p2")
        assertEquals("p2", live.pageOf(camera.key))
        assertEquals(live.snapshot(), reopened().snapshot())
        // Pinning one that is in a folder takes it out; a folder left with one app dissolves.
        live.pin(mail, "p3")
        assertEquals("p3", live.pageOf(mail.key))
        assertTrue(live.folders.isEmpty())
        assertEquals(Cell(1, 2, 1, 1), live.cell(foot.key))
        // Removing a folder removes its apps.
        val again = live()
        again.unpin("folder:a")
        assertEquals(listOf(chrome.key), again.apps.map { it.app.key })
        assertNull(again.pageOf("folder:a"))
        assertEquals(again.snapshot(), reopened().snapshot())
    }

    @Test fun pinnedShortcutsAndOwlAppsLiveInTheNotebookAndSurviveARestart() {
        val live = live()
        val site = PinnedApp("shortcut:org.chromium/#10#site-1", "Docs", profile = 10, shortcut = ShortcutEntry("org.chromium", "site-1"))
        val notes = PinnedApp("owl:notes.owl", "Notes", owl = "notes.owl")
        live.pin(site, "p2")
        live.pin(notes, "p3")
        // In a folder too, as any pinned app can be.
        assertEquals("folder:rs", live.group(notes.key, chrome.key))
        val again = reopened()
        assertEquals(live.snapshot(), again.snapshot())
        assertEquals("p2", again.pageOf(site.key))
        val shortcuts = Shortcuts(again)
        val tile = shortcuts.items.single { it.key == site.key }
        assertEquals(dev.omalauncher.ShortcutRef("org.chromium", "site-1"), tile.androidShortcut)
        assertEquals(10L, tile.profile)
        assertNull(tile.component)
        val owl = shortcuts.items.single { it.key == notes.key }
        assertEquals("notes.owl", owl.owl)
        assertEquals("folder:rs", owl.folder)
        // Taken off home once the app is gone: its folder of two dissolves, the other app keeps the cell.
        shortcuts.removeOwl("notes.owl")
        assertNull(again.pageOf(notes.key))
        assertFalse("folder:rs" in again.folders)
        assertEquals("p1", again.pageOf(chrome.key))
        // One that isn't pinned changes nothing.
        val before = writes.size
        shortcuts.removeOwl("other.owl")
        assertEquals(before, writes.size)
    }

    @Test fun theDockIsSavedWithThePages() {
        val live = live()
        assertEquals(listOf(camera), live.dock)
        live.changeDock(listOf(chrome, camera, foot))
        assertEquals(listOf(chrome, camera, foot), reopened().dock)
        assertEquals(saved.pages, reopened().snapshot().pages)
    }

    /**
     * Before the saved pages are read, the notebook is a stand-in: whatever was pinned, moved or added then would be
     * thrown away when they arrive, so every way in is shut until it is opened.
     */
    @Test fun nothingIsTakenBeforeItIsOpened() {
        val closed = LiveNotebook(persist = { writes += it })
        assertFalse(closed.opened)
        assertEquals(listOf("p1"), closed.pages.map { it.id })
        assertNull(closed.addPage())
        closed.pin(chrome, "p1")
        closed.changeDock(listOf(camera))
        closed.expect("store.owl", "p1")
        closed.adopt(listOf("clock.owl" to 1, "far.owl" to 3))
        closed.move("clock.owl", "p1")
        closed.setCell("clock.owl", Cell(0, 0, 1, 1))
        assertNull(closed.group(chrome.key, camera.key))
        closed.moveBefore(chrome.key, null)
        closed.unpin(chrome.key)
        closed.forget("clock.owl")
        assertFalse(closed.withdrawPage("p1"))
        closed.keep()
        closed.save()
        // Nothing was taken, and nothing written.
        assertEquals(listOf(PageLabel("p1", "Page 1")), closed.pages.toList())
        assertTrue(closed.apps.isEmpty())
        assertTrue(closed.folders.isEmpty())
        assertTrue(closed.dock.isEmpty())
        assertNull(closed.pageOf(chrome.key))
        assertNull(closed.pageOf("store.owl"))
        assertNull(closed.pageOf("clock.owl"))
        assertNull(closed.cell("clock.owl"))
        assertEquals(Notebook(9, listOf(NotebookPage("p1", "Page 1"))), closed.snapshot())
        assertTrue(writes.isEmpty())
        // Opened, it is what was saved, and takes changes.
        closed.open(saved, kept = true)
        assertEquals(saved, closed.snapshot())
        assertEquals("p4", closed.addPage())
        closed.pin(camera, "p2")
        assertEquals("p2", closed.pageOf(camera.key))
    }

    @Test fun nothingIsWrittenWhenItIsntKeptUntilItIs() {
        val unkept = live(kept = false)
        unkept.move("clock.owl", "p2")
        unkept.setCell("clock.owl", Cell(0, 0, 1, 1))
        unkept.save()
        unkept.addPage()
        unkept.changeDock(emptyList())
        assertTrue(writes.isEmpty())
        // It still works for the session.
        assertEquals("p2", unkept.pageOf("clock.owl"))
        // Once the start's disk work came out well, it is kept from then on, with what was changed meanwhile.
        unkept.keep()
        assertEquals(1, writes.size)
        assertEquals(unkept.snapshot(), reopened().snapshot())
        assertEquals("p2", reopened().pageOf("clock.owl"))
        unkept.keep()
        assertEquals(1, writes.size)
        unkept.move("clock.owl", "p3")
        assertEquals(2, writes.size)
    }

    @Test fun theCellsOfWhatIsNotShownAreStillHeld() {
        val live = live()
        assertEquals(setOf(Cell(0, 0, 3, 2), Cell(0, 2, 1, 1), Cell(1, 2, 1, 1)), live.cellsOn("p1", except = emptySet()).toSet())
        // clock.owl's file is gone: everything else on the page is shown, and its cell is what is held.
        assertEquals(listOf(Cell(0, 0, 3, 2)), live.cellsOn("p1", except = setOf(chrome.key, "folder:a")))
        assertEquals(emptyList<Cell>(), live.cellsOn("p2", except = emptySet()))
        // One expected but not placed yet holds nothing.
        live.expect("store.owl", "p2")
        assertEquals(emptyList<Cell>(), live.cellsOn("p2", except = emptySet()))
    }

    @Test fun aDamagedDocumentStillOpensConsistent() {
        // The same page and the same items twice, an app with nothing to open, cells saved on a 12-column grid.
        val doubled =
            Notebook(
                rows = 9,
                columns = 12,
                pages =
                    listOf(
                        NotebookPage("p1", "One", listOf(NotebookItem("a.owl", ItemKind.Widget, Cell(6, 0, 6, 2)), NotebookItem(chrome.key, ItemKind.App, Cell(0, 2, 2, 1), app = chrome), NotebookItem("app:ghost", ItemKind.App, Cell(4, 4, 2, 1)))),
                        NotebookPage("p1", "One again", listOf(NotebookItem("b.owl", ItemKind.Widget, null))),
                        NotebookPage("p2", "Two", listOf(NotebookItem("a.owl", ItemKind.Widget, Cell(0, 0, 2, 2)), NotebookItem(chrome.key, ItemKind.App, null, app = chrome))),
                    ),
            )
        val live = live(doubled)
        assertEquals(listOf("p1", "p2"), live.pages.map { it.id })
        assertEquals("p1", live.pageOf("a.owl"))
        assertEquals(Cell(3, 0, 3, 2), live.cell("a.owl"))
        assertEquals("p1", live.pageOf(chrome.key))
        assertEquals(1, live.apps.size)
        assertNull(live.pageOf("app:ghost"))
        assertEquals(6, live.snapshot().columns)
    }
}
