package dev.omalauncher.widgets

import dev.omalauncher.art.DefaultArtPlacer
import dev.omalauncher.canvas.LayoutShelf
import dev.omalauncher.canvas.RestorePoints
import dev.omalauncher.ui.MangaHome
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.OVERFLOW_PANELS
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.artKey
import dev.omalauncher.widgets.layout.inPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home in panels (the Manga look, piece B): where a drop lands, a page put back in order, the look switched to, art hung. */
class PanelHomeTest {
    private val rows = 10
    private val opening = PanelLayouts.panels("opening", GRID_COLUMNS, rows)
    private val beats = PanelLayouts.panels("beats", GRID_COLUMNS, rows)

    private fun app(name: String) = PinnedApp("app:$name#0", name, "com.$name/.Main", 0)

    private fun center(p: dev.omalauncher.widgets.layout.Panel) = (p.inner.x + p.inner.w / 2) to (p.inner.y + p.inner.h / 2)

    /** Home with [pages], open and kept, with the Fold's canvas pad and a shelf of saved layouts. */
    private class Home(pages: List<NotebookPage>, rows: Int = 10) {
        val notebook = LiveNotebook().apply { open(Notebook(rows = rows, pages = pages), kept = true) }
        var undo: String? = null
        val pad = FoldCanvasPad(notebook, addPage = { notebook.addPage() }, removePage = { notebook.deletePage(it, emptySet(), move = false) != null }, points = RestorePoints({ undo }, { undo = it }))
        var shelfText: String? = null
        val shelf = LayoutShelf({ shelfText }, { shelfText = it })

        fun page(id: String) = notebook.snapshot().pages.first { it.id == id }

        fun cells(id: String) = page(id).items.associate { it.key to it.cell }

        /** A drop as home does it, through the notebook. */
        fun drop(item: String, from: String, page: String, aim: PanelAim, inFolder: Boolean = false) =
            dropInPanel(item, from, notebook.cell(item) ?: Cell(0, 0, 1, 1), page, aim.cell, aim.occupant, notebook::cell, notebook::setCell, notebook::move, inFolder)
                .also { notebook.save() }
    }

    /** A page cut as [layout] with [keys] in its panels, in order (null leaves a panel empty). */
    private fun cut(id: String, layout: String, vararg keys: String?): NotebookPage {
        val panels = PanelLayouts.panels(layout, GRID_COLUMNS, rows)
        val items = keys.mapIndexedNotNull { i, key ->
            key?.let {
                when {
                    it.startsWith("app:") -> NotebookItem(it, ItemKind.App, panels[i].cell, app = PinnedApp(it, it, "com.x/.${it.substring(4, 6)}", 0))
                    it.startsWith("art:") -> NotebookItem(it, ItemKind.Art, panels[i].cell)
                    it.startsWith("module:") -> NotebookItem(it, ItemKind.Module, panels[i].cell)
                    else -> NotebookItem(it, ItemKind.Widget, panels[i].cell)
                }
            }
        }
        return NotebookPage(id, "Page $id", items, panels = layout)
    }

    // Where a finger is.

    @Test fun aFingerOverAPanelFindsItAndAGutterOrMarginBelongsToOne() {
        for ((i, p) in opening.withIndex()) {
            val (x, y) = center(p)
            assertEquals("panel $i", i, panelAt(opening, GRID_COLUMNS, rows, x, y))
        }
        // The top margin is the first panel's; the gutter between the two middle panels is one of theirs, never neither.
        assertEquals(0, panelAt(opening, GRID_COLUMNS, rows, 0.5, 0.005))
        assertTrue(panelAt(opening, GRID_COLUMNS, rows, 0.5, 0.5) in setOf(1, 2))
        assertEquals(-1, panelAt(opening, GRID_COLUMNS, rows, 1.2, 0.5))
        assertEquals(-1, panelAt(opening, GRID_COLUMNS, rows, 0.5, -0.1))
    }

    @Test fun overAPanelWithSomethingInItTheDropSwapsOverAnEmptyOneItMovesOverItsOwnNothing() {
        val items = listOf("clock.owl" to opening[0].cell, "module:needs" to opening[1].cell)
        val (x1, y1) = center(opening[1])
        assertEquals(PanelAim(1, opening[1].cell, "module:needs"), panelAim(opening, items, "clock.owl", GRID_COLUMNS, rows, x1, y1))
        val (x3, y3) = center(opening[3])
        assertEquals(PanelAim(3, opening[3].cell, null), panelAim(opening, items, "clock.owl", GRID_COLUMNS, rows, x3, y3))
        val (x0, y0) = center(opening[0])
        assertNull(panelAim(opening, items, "clock.owl", GRID_COLUMNS, rows, x0, y0))
        // Carried in from another page (it isn't among this page's items): every panel is a place for it.
        assertEquals(PanelAim(0, opening[0].cell, null), panelAim(opening, items.drop(1), "other.owl", GRID_COLUMNS, rows, x0, y0))
    }

    @Test fun anAppOutOfAFolderOnlyGoesIntoAnEmptyPanel() {
        val items = listOf("clock.owl" to opening[0].cell)
        val (x0, y0) = center(opening[0])
        assertNull(panelAim(opening, items, "app:maps#0", GRID_COLUMNS, rows, x0, y0, inFolder = true))
        val (x2, y2) = center(opening[2])
        assertEquals(PanelAim(2, opening[2].cell, null), panelAim(opening, items, "app:maps#0", GRID_COLUMNS, rows, x2, y2, inFolder = true))
    }

    // What a drop does.

    @Test fun aSwapOnAPageTradesCellsTheLinesStayAndEachPanelHoldsOne() {
        val home = Home(listOf(cut("p1", "opening", "clock.owl", "module:needs", null, null, "app:cc#0")))
        val aim = panelAim(opening, home.cells("p1").map { it.key to it.value!! }, "clock.owl", GRID_COLUMNS, rows, center(opening[4]).first, center(opening[4]).second)!!
        assertEquals("app:cc#0", aim.occupant)
        assertTrue(home.drop("clock.owl", "p1", "p1", aim))
        val page = home.page("p1")
        assertEquals("opening", page.panels)
        assertEquals(opening[4].cell, home.notebook.cell("clock.owl"))
        assertEquals(opening[0].cell, home.notebook.cell("app:cc#0"))
        assertEquals(opening[1].cell, home.notebook.cell("module:needs"))
        assertTrue(inPanels(home.notebook.snapshot(), page))
    }

    @Test fun aSwapAcrossTheSpreadTradesPagesTooAndAMoveIntoAnEmptyPanelTakesItsPage() {
        val home = Home(listOf(cut("p1", "opening", "clock.owl", "module:needs"), cut("p2", "beats", "weather.owl", null, null)))
        // The clock onto the weather on the facing page.
        assertTrue(home.drop("clock.owl", "p1", "p2", PanelAim(0, beats[0].cell, "weather.owl")))
        assertEquals("p2", home.notebook.pageOf("clock.owl"))
        assertEquals("p1", home.notebook.pageOf("weather.owl"))
        assertEquals(beats[0].cell, home.notebook.cell("clock.owl"))
        assertEquals(opening[0].cell, home.notebook.cell("weather.owl"))
        // Needs you into the empty middle panel over there: it moves, and the panel it left is empty.
        assertTrue(home.drop("module:needs", "p1", "p2", PanelAim(1, beats[1].cell, null)))
        assertEquals("p2", home.notebook.pageOf("module:needs"))
        assertEquals(beats[1].cell, home.notebook.cell("module:needs"))
        val n = home.notebook.snapshot()
        assertTrue(n.pages.all { inPanels(n, it) })
        assertEquals(listOf("opening", "beats"), n.pages.map { it.panels })
        assertEquals(listOf(1, 2, 3, 4), home.notebook.emptyPanelsOn("p1"))
    }

    @Test fun anAppOutOfAFolderDroppedOnAPanelWithSomethingInItStaysPut() {
        val home = Home(listOf(cut("p1", "opening", "clock.owl")))
        assertFalse(home.drop("app:maps#0", "p1", "p1", PanelAim(0, opening[0].cell, "clock.owl"), inFolder = true))
        assertEquals(opening[0].cell, home.notebook.cell("clock.owl"))
    }

    // A page put back in order.

    @Test fun whatIsInPlaceStaysAnAppAtOneCellFillsItsPanelTheRestTakeEmptyPanels() {
        val items = listOf(
            "clock.owl" to opening[0].cell,
            // An app put back at one cell, in the corner of an empty panel.
            "app:cc#0" to Cell(opening[3].cell.x, opening[3].cell.y, 1, 1),
            // A second thing in the clock's panel, a new one with no cell yet, and one across a line.
            "module:needs" to opening[0].cell,
            "new.owl" to null,
            "across.owl" to Cell(2, 3, 2, 2),
        )
        val settled = settleIntoPanels(opening, items)
        assertEquals(
            mapOf("app:cc#0" to opening[3].cell, "module:needs" to opening[1].cell, "new.owl" to opening[2].cell, "across.owl" to opening[4].cell),
            settled.cells,
        )
        assertEquals(emptyList<String>(), settled.noRoom)
    }

    @Test fun whatFindsNoEmptyPanelHasNoRoomAndAPlaceKeptForAMissingWidgetIsntFree() {
        val items = listOf("a.owl" to beats[0].cell, "b.owl" to null, "c.owl" to null)
        val settled = settleIntoPanels(beats, items, held = listOf(beats[1].cell))
        assertEquals(mapOf("b.owl" to beats[2].cell), settled.cells)
        assertEquals(listOf("c.owl"), settled.noRoom)
        // A page in order is left exactly as it is.
        assertEquals(PanelSettle(emptyMap(), emptyList()), settleIntoPanels(beats, listOf("a.owl" to beats[0].cell, "b.owl" to beats[2].cell)))
    }

    @Test fun whatAPanelPageHasNoRoomForGoesToTheNextWithAnEmptyPanelThenAStripPage() {
        val home = Home(listOf(cut("p1", "beats", "a.owl", "b.owl", "c.owl"), cut("p2", "beats", "d.owl", "e.owl", null)))
        home.notebook.move("x.owl", "p1")
        assertEquals("p2", home.notebook.overflowIntoPanels("x.owl", "opening", claimFree = { false }))
        assertEquals(beats[2].cell, home.notebook.cell("x.owl"))
        home.notebook.move("y.owl", "p1")
        val made = home.notebook.overflowIntoPanels("y.owl", "opening", claimFree = { false })!!
        assertEquals(OVERFLOW_PANELS, home.notebook.pages.first { it.id == made }.panels)
        assertEquals(PanelLayouts.panels(OVERFLOW_PANELS, GRID_COLUMNS, rows)[0].cell, home.notebook.cell("y.owl"))
        val n = home.notebook.snapshot()
        assertTrue(n.pages.all { inPanels(n, it) })
    }

    @Test fun overflowGoesRoundToTheStartBeforeMakingAPage() {
        val home = Home(listOf(cut("p1", "beats", "a.owl", null, "c.owl"), cut("p2", "beats", "d.owl", "e.owl", "f.owl")))
        home.notebook.move("x.owl", "p2")
        assertEquals("p1", home.notebook.overflowIntoPanels("x.owl", "opening", claimFree = { false }))
        assertEquals(beats[1].cell, home.notebook.cell("x.owl"))
        assertEquals(2, home.notebook.pages.size)
    }

    @Test fun whatAPageDeletedOntoAMangaPageLeavesGoesIntoItsPanelsAtOnce() {
        val maps = app("maps")
        val home = Home(
            listOf(
                cut("p1", "beats", "a.owl", null, "c.owl"),
                NotebookPage("p2", "Spare", listOf(NotebookItem(maps.key, ItemKind.App, Cell(0, 0, 1, 1), app = maps), NotebookItem("w.owl", ItemKind.Widget, Cell(2, 0, 2, 2)))),
            ),
        )
        val gone = home.notebook.deletePage("p2", widgets = setOf("a.owl", "c.owl", "w.owl"), move = true)!!
        assertEquals("p1", gone.to)
        home.notebook.settlePanelPage("p1", "opening", claimFree = { _, _ -> false })
        val n = home.notebook.snapshot()
        assertTrue(n.pages.all { it.panels == null || inPanels(n, it) })
        // One went into the empty panel; the other had none left and went on to a strip page made for it.
        assertEquals(setOf(maps.key, "w.owl"), n.pages.flatMap { p -> p.items.map { it.key } }.toSet() - setOf("a.owl", "c.owl"))
        assertEquals(listOf("beats", OVERFLOW_PANELS), n.pages.map { it.panels })
    }

    @Test fun aPageDeletedOntoAFullMangaPageSpillsOntoAMangaPageAndNothingEndsOnAFreeGrid() {
        val apps = (1..3).map { app("c$it") }
        val home = Home(
            listOf(
                cut("p1", "beats", "a.owl", "b.owl", "c.owl"),
                NotebookPage(
                    "p2", "Spare",
                    listOf(NotebookItem("koi.owl", ItemKind.Widget, Cell(0, 0, 6, 4))) +
                        apps.mapIndexed { i, a -> NotebookItem(a.key, ItemKind.App, Cell(i, 5, 1, 1), app = a) },
                    panels = "opening",
                ),
            ),
        )
        val gone = home.notebook.deletePage("p2", widgets = setOf("a.owl", "b.owl", "c.owl", "koi.owl"), move = true)!!
        assertEquals("p1", gone.to)
        home.notebook.settleAfterDelete(gone, "opening", claimFree = { _, _ -> true })
        val n = home.notebook.snapshot()
        // Nothing is on a free page, and every manga page holds one thing to a panel.
        assertTrue(n.toString(), n.pages.all { it.panels != null && inPanels(n, it) })
        assertEquals(setOf("a.owl", "b.owl", "c.owl", "koi.owl") + apps.map { it.key }, n.pages.flatMap { p -> p.items.map { it.key } }.toSet())
    }

    @Test fun aPageDeletedOntoAFreePageIsLeftAsTheFreeGridPlacedIt() {
        val maps = app("maps")
        val home = Home(listOf(NotebookPage("p1", "Free"), NotebookPage("p2", "Spare", listOf(NotebookItem(maps.key, ItemKind.App, Cell(4, 5, 1, 1), app = maps)))))
        val gone = home.notebook.deletePage("p2", widgets = emptySet(), move = true)!!
        val was = home.notebook.snapshot()
        home.notebook.settleAfterDelete(gone, "opening", claimFree = { _, _ -> true })
        assertEquals(was, home.notebook.snapshot())
    }

    // The look switched to.

    @Test fun theLookStepsOncePerSwitch() {
        assertEquals(MangaHome.Step.Fit, MangaHome.step(fitted = false, panels = true, opened = true))
        assertEquals(MangaHome.Step.None, MangaHome.step(fitted = false, panels = true, opened = false))
        assertEquals(MangaHome.Step.None, MangaHome.step(fitted = true, panels = true, opened = true))
        assertEquals(MangaHome.Step.Forget, MangaHome.step(fitted = true, panels = false, opened = true))
        assertEquals(MangaHome.Step.None, MangaHome.step(fitted = false, panels = false, opened = true))
    }

    @Test fun switchedToTheLookHomeIsKeptThenEveryFreePageIsFittedAndTheShelfBringsItBack() {
        val home = Home(
            listOf(
                NotebookPage(
                    "p1", "Today",
                    listOf(
                        NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 6, 3)),
                        NotebookItem("module:needs", ItemKind.Module, Cell(0, 3, 3, 2)),
                        NotebookItem(app("maps").key, ItemKind.App, Cell(4, 5, 1, 1), app = app("maps")),
                    ),
                ),
                NotebookPage("p2", "Later", listOf(NotebookItem("weather.owl", ItemKind.Widget, Cell(0, 0, 4, 2)))),
            ),
        )
        val before = home.notebook.snapshot()
        assertTrue(MangaHome.needsFit(home.notebook))
        home.shelf.autoSave(MangaHome.BEFORE, home.pad.notebook())
        assertTrue(MangaHome.fitInto(home.notebook, home.pad, "opening"))
        val n = home.notebook.snapshot()
        assertEquals(listOf("opening", "opening"), n.pages.map { it.panels })
        assertTrue(n.pages.all { inPanels(n, it) })
        // The heaviest into the biggest panel; nothing left home and no page was made.
        assertEquals(opening[0].cell, home.notebook.cell("clock.owl"))
        assertEquals(setOf("clock.owl", "module:needs", app("maps").key), n.pages[0].items.map { it.key }.toSet())
        assertEquals(2, n.pages.size)
        // Switched in again (or a start in the look), nothing is fitted twice, and nothing is kept over the first "Before Manga".
        assertFalse(MangaHome.needsFit(home.notebook))
        assertFalse(MangaHome.fitInto(home.notebook, home.pad, "opening"))
        assertEquals(n, home.notebook.snapshot())
        // Kept before: loading it puts every page and every thing back as it was.
        home.shelf.load(MangaHome.BEFORE, home.pad, "load-1", "home")
        val back = home.notebook.snapshot()
        assertEquals(before.pages.map { it.id to it.panels }, back.pages.map { it.id to it.panels })
        for (page in before.pages) for (item in page.items) {
            assertEquals(item.key, page.id, home.notebook.pageOf(item.key))
            assertEquals(item.key, item.cell, back.pages.first { it.id == page.id }.items.first { it.key == item.key }.cell)
        }
    }

    @Test fun aPageWithMoreThingsThanPanelsGathersItsAppsIntoOneCastFirst() {
        val apps = (1..6).map { app("a$it") }
        val home = Home(
            listOf(
                NotebookPage(
                    "p1", "Today",
                    listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 6, 3))) +
                        apps.mapIndexed { i, a -> NotebookItem(a.key, ItemKind.App, Cell(i, 4, 1, 1), app = a) },
                ),
            ),
        )
        assertTrue(MangaHome.fitInto(home.notebook, home.pad, "opening"))
        val n = home.notebook.snapshot()
        assertEquals(1, n.pages.size)
        val folder = n.pages[0].items.single { it.kind == ItemKind.Folder }
        assertEquals(apps.map { it.key }, folder.apps.map { it.key })
        assertTrue(inPanels(n, n.pages[0]))
        assertEquals(2, n.pages[0].items.size)
    }

    @Test fun aPageAlreadyInPanelsIsLeftAsItIsEvenWithAnAppFillingAPanel() {
        val home = Home(listOf(cut("p1", "opening", "app:cc#0", "clock.owl", null, "module:needs")))
        val was = home.notebook.snapshot()
        assertFalse(MangaHome.fitInto(home.notebook, home.pad, "opening"))
        assertEquals(was, home.notebook.snapshot())
        assertNull(home.undo)
    }

    @Test fun pagesWithMoreWidgetsThanPanelsOverflowAndPicturesLeaveThePage() {
        val art = artKey("0123456789abcdef")
        val home = Home(
            listOf(
                NotebookPage(
                    "p1", "Today",
                    listOf(
                        NotebookItem("a.owl", ItemKind.Widget, Cell(0, 0, 3, 3)),
                        NotebookItem("b.owl", ItemKind.Widget, Cell(3, 0, 3, 3)),
                        NotebookItem("c.owl", ItemKind.Widget, Cell(0, 3, 3, 3)),
                        NotebookItem("d.owl", ItemKind.Widget, Cell(3, 3, 3, 3)),
                        NotebookItem(art, ItemKind.Art, Cell(0, 6, 1, 1)),
                    ),
                ),
            ),
        )
        assertTrue(MangaHome.fitInto(home.notebook, home.pad, "beats"))
        val n = home.notebook.snapshot()
        assertTrue(n.pages.all { inPanels(n, it) })
        // Three panels: one widget goes on to a strip page made for it, and the picture leaves home (its file is kept).
        assertEquals(listOf("beats", OVERFLOW_PANELS), n.pages.map { it.panels })
        assertEquals(1, n.pages[1].items.size)
        assertNull(home.notebook.pageOf(art))
    }

    // Art hung.

    @Test fun artHangsInThePanelAskedForElseTheFirstEmptyOneAndNeverOutsideTheLook() {
        val home = Home(listOf(NotebookPage("p0", "Free"), cut("p1", "beats", "a.owl", null, null), cut("p2", "beats", null, "b.owl", null)))
        val hung = ArrayList<Pair<String, Int>>()
        val panels = HomePanels({ home.notebook }, on = { true }, onHung = { p, i -> hung += p to i })
        // A free page isn't a panel page.
        assertEquals(listOf("p1" to listOf(false, true, true), "p2" to listOf(true, false, true)), panels.panelPages().map { it.id to it.empty })
        val said = ArrayList<String>()
        val placer = DefaultArtPlacer({ panels }, { said += it })
        placer.place("00000000000000a1", "p2", 2)
        assertEquals(PanelLayouts.panels("beats", GRID_COLUMNS, rows)[2].cell, home.notebook.cell(artKey("00000000000000a1")))
        assertEquals("p2", home.notebook.pageOf(artKey("00000000000000a1")))
        assertEquals(emptyList<String>(), said)
        // The panel asked for is taken: the first empty one on that page.
        placer.place("00000000000000a2", "p2", 1)
        assertEquals("p2" to 0, hung.last())
        // From the share sheet: the first empty panel, and it says where.
        placer.place("00000000000000a3", null, null)
        assertEquals("p1" to 1, hung.last())
        assertTrue(said.last(), said.last().startsWith("Hung on"))
        val n = home.notebook.snapshot()
        assertTrue(n.pages.filter { it.panels != null }.all { inPanels(n, it) })
        // Not in another look: it says there is no manga page, and nothing is hung.
        val off = DefaultArtPlacer({ HomePanels({ home.notebook }, on = { false }) }, { said += it })
        off.place("00000000000000a4", "p1", 2)
        assertNull(home.notebook.pageOf(artKey("00000000000000a4")))
        assertTrue(said.last(), said.last().contains("no manga page"))
        assertFalse(panels.put("00000000000000a5", "p1", 0))
    }
}
