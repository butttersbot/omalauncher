package dev.omalauncher.widgets.layout

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.CanvasPad
import dev.omalauncher.canvas.RestorePoint
import dev.omalauncher.canvas.RestorePoints
import dev.omalauncher.canvas.ScreenRules
import dev.omalauncher.canvas.diffToVerbs
import dev.omalauncher.canvas.replayLog
import dev.omalauncher.canvas.restorePoint
import dev.omalauncher.canvas.undoOn
import dev.omalauncher.widgets.Cell
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking a page's panel layout: the refit (the mockup's setSheet), the verb `page.panels`, its JSON, undo and sync. */
class PanelVerbsTest {
    private val art = artKey("00000000000000aa")
    private val app = PinnedApp("app:files#0", "Files", "com.files/.Main", 0)

    /** The mockup's grid: six columns by ten rows. */
    private fun book(vararg pages: NotebookPage) = Notebook(rows = 10, pages = pages.toList(), columns = 6)

    private val today = NotebookPage(
        "p1", "Today",
        listOf(
            NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)),
            NotebookItem("module:needs", ItemKind.Module, Cell(0, 2, 6, 4)),
            NotebookItem(art, ItemKind.Art, Cell(3, 0, 2, 2)),
            NotebookItem(app.key, ItemKind.App, Cell(5, 0, 1, 1), app = app),
        ),
    )
    private val later = NotebookPage("p2", "Later", listOf(NotebookItem("pocket.owl", ItemKind.Widget, Cell(0, 0, 2, 2))))

    /** What a notebook holds, whatever order items are listed in: each page's id, name, layout and items' cells. */
    private fun shape(n: Notebook) = n.pages.map { p -> listOf(p.id, p.name, p.panels, p.items.associate { it.key to it.cell }) }

    private fun cells(n: Notebook, page: String) = n.pages.first { it.id == page }.items.associate { it.key to it.cell }

    private fun panelCells(id: String) = PanelLayouts.panels(id, 6, 10).map { it.cell }

    private fun biggestFirst(id: String) = PanelLayouts.panels(id, 6, 10).let { p -> p.indices.sortedByDescending { p[it].area }.map { p[it].cell } }

    @Test fun theHeaviestGoesIntoTheBiggestPanel() {
        val fit = fitIntoPanels(book(today, later), "p1", "beats")
        val order = biggestFirst("beats")
        val after = cells(fit.notebook, "p1")
        // Heaviest by the cells they take now: Needs you (24), the clock (6), the art (4); the app (1) finds no panel left.
        assertEquals(order[0], after["module:needs"])
        assertEquals(order[1], after["clock.owl"])
        assertEquals(order[2], after[art])
        assertEquals("beats", fit.notebook.pages[0].panels)
        assertTrue(inPanels(fit.notebook, fit.notebook.pages[0]))
    }

    @Test fun aLeftoverWidgetGoesToANewStripPageWhenNoPanelPageHasRoom() {
        // "Later" is the free grid: it has no panels to be empty, so a page is made for the app at the end.
        val fit = fitIntoPanels(book(today, later), "p1", "beats")
        assertEquals(listOf("p3"), fit.made)
        val made = fit.notebook.pages.last()
        assertEquals(listOf("p3", "Page 3", OVERFLOW_PANELS), listOf(made.id, made.name, made.panels))
        assertEquals(mapOf(app.key to panelCells(OVERFLOW_PANELS).first()), cells(fit.notebook, "p3"))
        assertEquals(listOf(app.key to "p3"), fit.moved)
        assertTrue(fit.removed.isEmpty())
        assertEquals(later, fit.notebook.pages[1])
    }

    @Test fun leftoversFillThePanelsOfTheNextPanelPageRoundToTheStart() {
        // The first page is a panel page with two panels empty; the last page is refit into one panel.
        val first = NotebookPage("p1", "One", listOf(NotebookItem("a.owl", ItemKind.Widget, panelCells("beats")[1])), panels = "beats")
        val last = NotebookPage("p3", "Three", listOf(NotebookItem("b.owl", ItemKind.Widget, Cell(0, 0, 6, 5)), NotebookItem("c.owl", ItemKind.Widget, Cell(0, 5, 2, 2)), NotebookItem("d.owl", ItemKind.Widget, Cell(2, 5, 1, 1))))
        val fit = fitIntoPanels(book(first, later, last), "p3", "splash")
        assertEquals(mapOf("b.owl" to Cell(0, 0, 6, 10)), cells(fit.notebook, "p3"))
        // Into the first page's empty panels in its order, never onto the free page between.
        assertEquals(listOf("c.owl" to "p1", "d.owl" to "p1"), fit.moved)
        assertEquals(panelCells("beats")[0], cells(fit.notebook, "p1")["c.owl"])
        assertEquals(panelCells("beats")[2], cells(fit.notebook, "p1")["d.owl"])
        assertTrue(fit.made.isEmpty())
        assertTrue(fit.notebook.pages.all { it.panels == null || inPanels(fit.notebook, it) })
    }

    @Test fun leftoverArtLeavesThePage() {
        val small = artKey("00000000000000bb")
        val page = NotebookPage("p1", "Art", listOf(NotebookItem(art, ItemKind.Art, Cell(0, 0, 6, 6)), NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 6, 5, 4)), NotebookItem(small, ItemKind.Art, Cell(5, 9, 1, 1))))
        // A widget lighter than art still finds a panel, on a page made for it; the art left over leaves home (its file is kept).
        val one = fitIntoPanels(book(page), "p1", "splash")
        assertEquals(mapOf(art to Cell(0, 0, 6, 10)), cells(one.notebook, "p1"))
        assertEquals(listOf("clock.owl" to "p2"), one.moved)
        assertEquals(listOf(small), one.removed)
        assertTrue(one.notebook.pages.none { p -> p.items.any { it.key == small } })
        // With a panel for each, nothing leaves.
        val three = fitIntoPanels(book(page), "p1", "beats")
        assertTrue(three.moved.isEmpty() && three.removed.isEmpty() && three.made.isEmpty())
        assertEquals(setOf(art, "clock.owl", small), cells(three.notebook, "p1").keys)
    }

    @Test fun noneMakesItTheFreeGridAndLeavesEveryCell() {
        val panelled = fitIntoPanels(book(today, later), "p1", "grid6").notebook
        val free = fitIntoPanels(panelled, "p1", null)
        assertNull(free.notebook.pages[0].panels)
        assertEquals(cells(panelled, "p1"), cells(free.notebook, "p1"))
        assertEquals(listOf<CanvasVerb>(CanvasVerb.PagePanels("p1", null)), free.verbs)
    }

    @Test fun theVerbsReplayToTheFitAndOneRestorePointUndoesAllOfIt() {
        val before = book(today, later)
        for (layout in PanelLayouts.ids) {
            val fit = fitIntoPanels(before, "p1", layout)
            assertEquals(layout, shape(fit.notebook), shape(fit.verbs.fold(before) { n, v -> applyVerb(n, v).also { assertTrue("$layout $v: ${it.why}", it.applied) }.notebook }))
            val undo = undoOn(fit.notebook, restorePoint(before, fit.verbs))
            assertTrue("$layout: ${undo.left}", undo.left.isEmpty())
            assertEquals(layout, shape(before), shape(undo.notebook))
        }
    }

    @Test fun pagePanelsAppliesExactlyOrNotAtAll() {
        val n = book(today, later)
        val good = CanvasVerb.PagePanels("p1", "splash", mapOf("module:needs" to Cell(0, 0, 6, 10)))
        // The others are still where they were, under it.
        assertFalse(applyVerb(n, good).applied)
        val alone = book(NotebookPage("p1", "Today", listOf(NotebookItem("module:needs", ItemKind.Module, Cell(0, 2, 6, 4)))))
        val done = applyVerb(alone, good)
        assertTrue(done.why, done.applied)
        assertEquals("splash", done.notebook.pages[0].panels)
        assertEquals(Cell(0, 0, 6, 10), done.notebook.pages[0].items[0].cell)
        for ((verb, why) in listOf(
            CanvasVerb.PagePanels("p9", "splash") to "the page is gone",
            CanvasVerb.PagePanels("p1", "collage") to "that panel layout can't be cut on this grid",
            CanvasVerb.PagePanels("p1", "beats", mapOf("pocket.owl" to Cell(0, 0, 1, 1))) to "something it moves is not on that page",
            CanvasVerb.PagePanels("p1", "beats", mapOf("clock.owl" to Cell(5, 9, 2, 2))) to "that spot is off the grid",
            CanvasVerb.PagePanels("p1", "beats", mapOf("clock.owl" to Cell(0, 2, 1, 1))) to "that spot is taken",
        )) {
            val r = applyVerb(n, verb)
            assertFalse(verb.toString(), r.applied)
            assertEquals(why, r.why)
            assertEquals(n, r.notebook)
        }
        assertFalse(applyVerb(Notebook(rows = 3, pages = listOf(NotebookPage("p1", "Small"))), CanvasVerb.PagePanels("p1", "yonkoma")).applied)
    }

    @Test fun theVerbsJsonRoundTripsAndANewerLayoutIsKeptUnapplied() {
        for (v in listOf(
            CanvasVerb.PagePanels("p1", "opening", mapOf("clock.owl" to Cell(0, 0, 6, 4), art to Cell(0, 4, 3, 2))),
            CanvasVerb.PagePanels("p2", null),
            CanvasVerb.PagePanels("p3", "yonkoma"),
        )) {
            val o = VerbJson.verb(v)
            assertEquals("page.panels", o.getString("verb"))
            assertEquals(v.panels == null, !o.has("panels"))
            assertEquals(v, VerbJson.verbOf(JSONObject(o.toString())))
        }
        val newer = JSONObject().put("verb", "page.panels").put("id", "p1").put("panels", "collage").put("cells", JSONObject())
        assertTrue(VerbJson.verbOf(newer) is CanvasVerb.Unknown)
        val log = VerbLog("fold").append("r1", VerbOrigin("fold", "agent:a"), CanvasVerb.PagePanels("p1", "beats", mapOf("a" to Cell(0, 0, 6, 3))))
        assertEquals(log, VerbJson.logOf(VerbJson.log(log)))
    }

    @Test fun syncSendsAPanelPagesRecutAsOneVerb() {
        val before = book(today, later)
        for (layout in PanelLayouts.ids) {
            val after = fitIntoPanels(before, "p1", layout).notebook
            val verbs = diffToVerbs(before, after)
            assertEquals(layout, 1, verbs.count { it is CanvasVerb.PagePanels && it.id == "p1" })
            // Nothing staying on the page moves on its own: it all goes in the one verb.
            assertTrue(layout, verbs.none { it is CanvasVerb.Move && it.page == "p1" })
            assertEquals(layout, shape(after), shape(verbs.fold(before) { n, v -> applyVerb(n, v).also { assertTrue("$layout $v: ${it.why}", it.applied) }.notebook }))
        }
        // A swap of two panels' contents on a panel page is one verb too, and a free page's changes are as they always were.
        val panelled = fitIntoPanels(before, "p1", "beats").notebook
        val a = cells(panelled, "p1")["clock.owl"]!!
        val b = cells(panelled, "p1")["module:needs"]!!
        val swapped = applyVerb(panelled, CanvasVerb.PagePanels("p1", "beats", mapOf("clock.owl" to b, "module:needs" to a))).notebook
        assertEquals(listOf<CanvasVerb>(CanvasVerb.PagePanels("p1", "beats", mapOf("module:needs" to a, "clock.owl" to b))), diffToVerbs(panelled, swapped))
        val moved = applyVerb(before, CanvasVerb.Move("pocket.owl", "p2", Cell(2, 2, 2, 2))).notebook
        assertEquals(listOf<CanvasVerb>(CanvasVerb.Move("pocket.owl", "p2", Cell(2, 2, 2, 2))), diffToVerbs(before, moved))
        // The service's copy of the screen takes the layout with the rest.
        val log = verbsLog(diffToVerbs(Notebook(rows = 10, pages = emptyList()), panelled))
        assertEquals(shape(panelled), shape(replayLog(log, 6, 10)))
    }

    private fun verbsLog(verbs: List<CanvasVerb>) = verbs.fold(VerbLog("fold")) { l, v -> l.append("r", VerbOrigin("fold", "hand"), v) }

    /** A screen's canvas in memory, as the canvas tests keep one. */
    private class Pad(var n: Notebook, override val rules: ScreenRules = ScreenRules()) : CanvasPad {
        var text: String? = null
        val points = RestorePoints({ text }, { text = it })
        override fun notebook() = n
        override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
            val before = n
            for (v in verbs) { val r = applyVerb(n, v); if (!r.applied) return r.why; n = r.notebook }
            points.add(request, restorePoint(before, verbs))
            return null
        }
        override fun lastRestorePoint() = points.last()
        override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? {
            for (v in verbs) n = applyVerb(n, v).notebook
            points.drop(restored.request)
            return null
        }
        fun run(line: String, request: String = "r1") = CanvasCommands.run(line.split(' '), this, request, "agent:test")
    }

    @Test fun agentsSayItInWordsAndUndoTakesItBack() {
        val before = book(today, later)
        val pad = Pad(before)
        val did = pad.run("page.panels Today beats")
        assertTrue(did.toString(), did.getBoolean("ok"))
        assertEquals("\"Today\" is now Three beats. No panel left there, so Files went to \"Page 3\"", did.getString("did"))
        assertEquals(shape(fitIntoPanels(before, "p1", "beats").notebook), shape(pad.n))
        assertEquals(1, pad.points.all().size)
        // Asked again, it is already so.
        assertEquals("\"Today\" is already Three beats", pad.run("page.panels today beats", "r2").getString("did"))
        assertEquals(1, pad.points.all().size)
        val undo = pad.run("undo")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        assertEquals(shape(before), shape(pad.n))
        assertFalse(pad.run("undo").getBoolean("ok"))
    }

    @Test fun aPageNamedInSeveralWordsAndNoneAndTheOtherSpelling() {
        val pad = Pad(book(NotebookPage("p1", "Big Day", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(1, 1, 2, 2))))))
        assertTrue(pad.run("page-panels Big Day splash").getBoolean("ok"))
        assertEquals("splash", pad.n.pages[0].panels)
        assertEquals(Cell(0, 0, 6, 10), pad.n.pages[0].items[0].cell)
        val none = pad.run("page.panels Big Day NONE", "r2")
        assertEquals("\"Big Day\" is now the free grid", none.getString("did"))
        assertNull(pad.n.pages[0].panels)
        assertEquals("\"Big Day\" is already the free grid", pad.run("page.panels p1 none", "r3").getString("did"))
    }

    @Test fun refusalsChangeNothing() {
        val before = book(today, later)
        val pad = Pad(before)
        for ((line, says) in listOf(
            "page.panels Today" to "Usage: page.panels PAGE LAYOUT",
            "page.panels Today collage" to "\"collage\" is not a panel layout. Layouts: opening (Opening), beats (Three beats)",
            "page.panels Elsewhere beats" to "No page is called \"Elsewhere\"",
        )) {
            val r = pad.run(line)
            assertFalse(line, r.getBoolean("ok"))
            assertTrue(r.getString("error"), r.getString("error").startsWith(says))
        }
        val small = Pad(Notebook(rows = 3, pages = listOf(NotebookPage("p1", "Small"))))
        assertEquals("4-koma strip needs more rows or columns than this screen has. Nothing was changed.", small.run("page.panels Small yonkoma").getString("error"))
        val desk = Pad(before, ScreenRules(pinned = { listOf(Cell(0, 0, 2, 10)) }))
        assertTrue(desk.run("page.panels Today beats").getString("error").startsWith("This screen keeps something on every page"))
        val kept = Pad(before, ScreenRules(kept = setOf("clock.owl")))
        assertTrue(kept.run("page.panels Today beats").getString("error").startsWith("clock.owl stays where it is"))
        for (p in listOf(pad, small, desk, kept)) assertTrue(p.points.all().isEmpty())
        assertEquals(before, pad.n)
    }
}
