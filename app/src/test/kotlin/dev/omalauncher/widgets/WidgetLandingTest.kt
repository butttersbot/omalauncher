package dev.omalauncher.widgets

import dev.omalauncher.assist.PageAsk
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A widget an agent makes lands on the page its request was sent from: which files that is, and where they go. */
class WidgetLandingTest {
    private val rows = 4

    /** Page 1 has room beside its widget, page 2 is full, page 3 has room, page 4 is full. */
    private val saved =
        Notebook(
            rows = rows,
            pages =
                listOf(
                    NotebookPage("p1", "Today", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)))),
                    NotebookPage("p2", "Trip", listOf(NotebookItem("map.owl", ItemKind.Widget, Cell(0, 0, 6, 4)))),
                    NotebookPage("p3", "Work", listOf(NotebookItem("mail.owl", ItemKind.Widget, Cell(0, 0, 6, 2)))),
                    NotebookPage("p4", "Full", listOf(NotebookItem("wall.owl", ItemKind.Widget, Cell(0, 0, 6, 4)))),
                ),
        )

    private val writes = ArrayList<String>()

    private fun live(notebook: Notebook = saved) = LiveNotebook(persist = { writes += it }).also { it.open(notebook, kept = true) }

    private fun item(
        name: String,
        size: Pair<Int, Int>? = 3 to 2,
    ) = WidgetItem(WidgetSpec(File(name), name.removeSuffix(".owl"), startPage = 1, order = 0, program = null, size = size))

    /** Home as the engine has it: the widget files that exist, by the page the notebook has each on. */
    private class Home(
        val notebook: LiveNotebook,
        val items: MutableList<WidgetItem>,
    ) {
        val layout = WidgetLayout(notebook)

        fun itemsOn(page: String): List<HomeItem> = items.filter { notebook.pageOf(it.key) == page }

        /** Home showed a page: that is when there is a grid. */
        fun layOut(page: String) = layout.place(itemsOn(page), GRID_COLUMNS, notebook.rows, page)
    }

    private fun home(notebook: LiveNotebook = live()) = Home(notebook, listOf("clock.owl", "map.owl", "mail.owl", "wall.owl").map { item(it, null) }.toMutableList())

    // Which files are an agent's doing.

    @Test fun aFileWrittenWhileTheTaskRunsIsItsDoing() {
        val running = PageAsk("p2", started = 10_000)
        assertTrue(running.covers(written = 10_000, now = 10_500))
        assertTrue(running.covers(written = 900_000, now = 900_200))
        // However long it runs.
        assertTrue(running.covers(written = 5_000_000, now = 5_000_100))
        // A file from before he asked is not, even if it is only seen now.
        assertFalse(running.covers(written = 9_999, now = 10_500))
        assertFalse(running.covers(written = 0, now = 10_500))
    }

    @Test fun afterTheTaskOnlyBrieflyOrShortlyAfterItStarted() {
        val long = PageAsk("p2", started = 10_000, ended = 600_000)
        // Written as it finished and seen a moment later.
        assertTrue(long.covers(written = 599_000, now = 600_000 + PageAsk.SEEN_MILLIS))
        assertFalse(long.covers(written = 599_000, now = 600_001 + PageAsk.SEEN_MILLIS))
        // Written later by something else: not the task's.
        assertFalse(long.covers(written = 700_000, now = 700_100))
        // A task that was over at once still covers what turns up shortly after it started, and no longer.
        val quick = PageAsk("p2", started = 10_000, ended = 12_000)
        assertTrue(quick.covers(written = 60_000, now = 60_100))
        assertTrue(quick.covers(written = 10_000 + PageAsk.SHORTLY_MILLIS - 1, now = 10_000 + PageAsk.SHORTLY_MILLIS))
        assertFalse(quick.covers(written = 10_000 + PageAsk.SHORTLY_MILLIS, now = 10_001 + PageAsk.SHORTLY_MILLIS))
        // A clock set back is not a reason to move anything.
        assertFalse(quick.covers(written = 11_000, now = 9_000))
    }

    // Which page a new file is put on.

    @Test fun aNewFileGoesToThePageHeAskedFromNotTheOneItNames() {
        val live = live()
        val landed = live.adopt(listOf("weather.owl" to 1, "notes.owl" to 3)) { name -> "p2".takeIf { name == "weather.owl" } }
        assertEquals(listOf("weather.owl"), landed)
        assertEquals("p2", live.pageOf("weather.owl"))
        // The other has no task behind it: where its file asks, as before.
        assertEquals("p3", live.pageOf("notes.owl"))
        assertEquals("p2", live(NotebookStore.read(writes.last())!!).pageOf("weather.owl"))
    }

    @Test fun aFileTheNotebookKnowsIsNeverMoved() {
        val live = live()
        val landed = live.adopt(listOf("clock.owl" to 1, "map.owl" to 2, "mail.owl" to 3)) { "p4" }
        assertEquals(emptyList<String>(), landed)
        assertEquals(saved, live.snapshot())
        assertTrue(writes.isEmpty())
        // Nor one whose place was chosen on the phone before its file was written.
        live.expect("store.owl", "p1")
        assertEquals(emptyList<String>(), live.adopt(listOf("store.owl" to 3)) { "p4" })
        assertEquals("p1", live.pageOf("store.owl"))
        // Nor a widget whose file is away for now: it keeps its page and its cells for when it is back.
        assertEquals(emptyList<String>(), live.adopt(emptyList()) { "p4" })
        live.adopt(listOf("map.owl" to 1)) { "p4" }
        assertEquals("p2", live.pageOf("map.owl"))
        assertEquals(Cell(0, 0, 6, 4), live.cell("map.owl"))
    }

    @Test fun withNoTaskBehindItAFileBehavesAsBefore() {
        val plain = live()
        plain.adopt(listOf("a.owl" to 2, "far.owl" to 9, "zero.owl" to 0))
        val asked = live()
        assertEquals(emptyList<String>(), asked.adopt(listOf("a.owl" to 2, "far.owl" to 9, "zero.owl" to 0)) { null })
        assertEquals(plain.snapshot(), asked.snapshot())
        assertEquals("p2", plain.pageOf("a.owl"))
        assertEquals("p5", plain.pageOf("far.owl"))
        assertEquals("p1", plain.pageOf("zero.owl"))
    }

    @Test fun aPageThatIsGoneIsNotPreferred() {
        val live = live()
        assertEquals(emptyList<String>(), live.adopt(listOf("weather.owl" to 3)) { "p9" })
        assertEquals("p3", live.pageOf("weather.owl"))
        assertEquals(listOf("p1", "p2", "p3", "p4"), live.pages.map { it.id })
    }

    @Test fun beforeTheNotebookIsOpenedNothingIsAdopted() {
        val closed = LiveNotebook(persist = { writes += it })
        assertEquals(emptyList<String>(), closed.adopt(listOf("weather.owl" to 1)) { "p1" })
        assertNull(closed.pageOf("weather.owl"))
        assertTrue(writes.isEmpty())
    }

    // Where on it.

    @Test fun itTakesTheFirstFreeCellsOfThatPage() {
        val home = home()
        home.layOut("p1")
        val weather = item("weather.owl")
        home.items += weather
        home.notebook.adopt(listOf("weather.owl" to 4)) { "p1" }
        assertEquals("p1", home.layout.land(weather, home::itemsOn))
        assertEquals("p1", home.notebook.pageOf("weather.owl"))
        // Beside the clock, which keeps its cells.
        assertEquals(Cell(3, 0, 3, 2), home.notebook.cell("weather.owl"))
        assertEquals(Cell(0, 0, 3, 2), home.notebook.cell("clock.owl"))
        assertEquals(4, home.notebook.pages.size)
    }

    @Test fun withNoRoomThereItGoesToTheNextPageWithRoom() {
        val home = home()
        home.layOut("p1")
        val weather = item("weather.owl")
        home.items += weather
        home.notebook.adopt(listOf("weather.owl" to 1)) { "p2" }
        // Page 2 is full; page 3 has two free rows. Page 1 has room too, but it is not a next page.
        assertEquals("p3", home.layout.land(weather, home::itemsOn))
        assertEquals("p3", home.notebook.pageOf("weather.owl"))
        assertEquals(Cell(0, 2, 3, 2), home.notebook.cell("weather.owl"))
        // Nothing that was there moved.
        assertEquals(Cell(0, 0, 6, 4), home.notebook.cell("map.owl"))
        assertEquals(Cell(0, 0, 6, 2), home.notebook.cell("mail.owl"))
        assertEquals("p3", live(NotebookStore.read(writes.last())!!).pageOf("weather.owl"))
    }

    @Test fun withNoRoomAnywhereAfterItGetsANewPage() {
        val home = home()
        home.layOut("p1")
        val weather = item("weather.owl")
        home.items += weather
        home.notebook.adopt(listOf("weather.owl" to 1)) { "p4" }
        val page = home.layout.land(weather, home::itemsOn)
        assertEquals("p5", page)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), home.notebook.pages.map { it.id })
        assertEquals("p5", home.notebook.pageOf("weather.owl"))
        // Its cells come when that page is laid out: the first of an empty page.
        assertTrue(home.layOut("p5").isEmpty())
        assertEquals(Cell(0, 0, 3, 2), home.notebook.cell("weather.owl"))
        assertEquals(Cell(0, 0, 6, 4), home.notebook.cell("wall.owl"))
    }

    @Test fun theCellsOfAWidgetWhoseFileIsAwayAreNotTaken() {
        val home = home()
        home.layOut("p1")
        // map.owl's file is gone for now; its page still looks full to a newcomer.
        home.items.removeAll { it.key == "map.owl" }
        val weather = item("weather.owl")
        home.items += weather
        home.notebook.adopt(listOf("weather.owl" to 1)) { "p2" }
        assertEquals("p3", home.layout.land(weather, home::itemsOn))
        assertEquals(Cell(0, 0, 6, 4), home.notebook.cell("map.owl"))
        assertEquals("p2", home.notebook.pageOf("map.owl"))
    }

    @Test fun landingAPlacedWidgetChangesNothing() {
        val home = home()
        // Whether or not home has a grid yet.
        for (laidOut in listOf(false, true)) {
            if (laidOut) home.layOut("p1")
            // At another size than its saved cells, on a page with room for it elsewhere: it stays as it is.
            assertEquals("p1", home.layout.land(item("clock.owl", 2 to 2), home::itemsOn))
            assertEquals("p1", home.layout.land(item("clock.owl", null), home::itemsOn))
            // On a full page it isn't moved on either.
            assertEquals("p2", home.layout.land(item("map.owl", 1 to 1), home::itemsOn))
            assertEquals(saved, home.notebook.snapshot())
        }
        // A held entry: the file is away, and what is landed is some item by its name.
        home.items.removeAll { it.key == "mail.owl" }
        assertEquals("p3", home.layout.land(item("mail.owl", 2 to 2), home::itemsOn))
        assertEquals(Cell(0, 0, 6, 2), home.notebook.cell("mail.owl"))
        assertEquals(saved, home.notebook.snapshot())
        assertTrue(writes.isEmpty())
    }

    @Test fun beforeHomeHasAGridItWaitsOnItsPage() {
        val home = home()
        val weather = item("weather.owl")
        home.items += weather
        home.notebook.adopt(listOf("weather.owl" to 1)) { "p2" }
        assertNull(home.layout.land(weather, home::itemsOn))
        assertEquals("p2", home.notebook.pageOf("weather.owl"))
        assertNull(home.notebook.cell("weather.owl"))
        // Something on no page at all is left alone.
        home.layOut("p1")
        assertNull(home.layout.land(item("stranger.owl"), home::itemsOn))
        assertNull(home.notebook.pageOf("stranger.owl"))
        assertEquals(4, home.notebook.pages.size)
    }
}
