package dev.omalauncher.canvas

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.applyVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasAsksTest {
    private val needs = moduleKey(ModuleKind.Needs)!!
    private val threads = moduleKey(ModuleKind.Threads)!!
    private val timeline = moduleKey(ModuleKind.Timeline)!!
    private val rules = ScreenRules()

    /** A Fold-sized home: six columns by six rows, an app at the top left of "Home". */
    private fun fold(vararg pages: NotebookPage) = Notebook(rows = 6, pages = pages.toList(), columns = 6)

    private val home = NotebookPage("p1", "Home", listOf(NotebookItem("app.one", ItemKind.App, Cell(0, 0, 1, 1))))

    /** [words] run against [n] as one call: the canvas after it and what was said. */
    private class Pad(var n: Notebook, override val rules: ScreenRules = ScreenRules()) : CanvasPad {
        val points = RestorePoints({ text }, { text = it })
        var text: String? = null
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

    private fun cellOf(n: Notebook, key: String) = n.pages.firstNotNullOf { p -> p.items.firstOrNull { it.key == key }?.cell }
    private fun pageOf(n: Notebook, key: String) = n.pages.first { p -> p.items.any { it.key == key } }.name

    @Test fun sizeWordsAreTheFoldsCellsAndAThirdBiggerOnAWideGrid() {
        assertEquals(1 to 1, SizeWord.Dot.cells(6, 6))
        assertEquals(3 to 1, SizeWord.Line.cells(6, 6))
        assertEquals(3 to 2, SizeWord.Card.cells(6, 6))
        assertEquals(6 to 3, SizeWord.Full.cells(6, 6))
        assertEquals(4 to 3, SizeWord.Card.cells(12, 8))
        assertEquals(12 to 3, SizeWord.Full.cells(12, 8))
        assertEquals(ModuleSize.Full, moduleSize(12, 3, 12))
        assertEquals(ModuleSize.Card, SizeWord.CardBig.cells(6, 6).let { moduleSize(it.first, it.second, 6) })
    }

    @Test fun modulesAreNamedByIdTitleOrKeyAndTheChatOnlyToBeBeside() {
        assertEquals(needs, moduleKeyOf("needs"))
        assertEquals(needs, moduleKeyOf("Needs you"))
        assertEquals(needs, moduleKeyOf("needs-you"))
        assertEquals(needs, moduleKeyOf("module:needs"))
        assertEquals("module:handoff:soma", moduleKeyOf("handoff:soma"))
        assertNull(moduleKeyOf("handoff"))
        assertNull(moduleKeyOf("handoff:../etc"))
        assertNull(moduleKeyOf("weather"))
        assertNull(moduleKeyOf("chat"))
        assertEquals(moduleKey(ModuleKind.Chat), moduleKeyOf("chat", chat = true))
    }

    @Test fun makeMeAPageThenItsModulesThenUndoTakesItAllBack() {
        val pad = Pad(fold(home))
        assertTrue(pad.run("page-make Soma").getBoolean("ok"))
        assertTrue(pad.run("place needs card top-left --page Soma").getBoolean("ok"))
        assertTrue(pad.run("place threads card right-of needs").getBoolean("ok"))
        assertTrue(pad.run("place handoff:soma full below needs").getBoolean("ok"))
        assertEquals(listOf("Home", "Soma"), pad.n.pages.map { it.name })
        assertEquals(Cell(0, 0, 3, 2), cellOf(pad.n, needs))
        assertEquals(Cell(3, 0, 3, 2), cellOf(pad.n, threads))
        assertEquals(Cell(0, 2, 6, 3), cellOf(pad.n, "module:handoff:soma"))
        // One request, one restore point: undo takes the page and everything on it, and home is as it was.
        val undo = pad.run("undo")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        assertEquals(fold(home), pad.n)
        assertFalse(pad.run("undo").getBoolean("ok"))
    }

    @Test fun anUndoLeavesWhatSomeoneElsePutThereSinceAndSaysSo() {
        val pad = Pad(fold(home))
        pad.run("page-make Soma"); pad.run("place needs card --page Soma")
        // By hand, afterwards: a widget on the agent's page.
        pad.n = applyVerb(pad.n, CanvasVerb.Place("weather.owl", ItemKind.Widget, pad.n.pages[1].id, Cell(0, 4, 2, 2))).notebook
        val undo = pad.run("undo")
        assertTrue(undo.getBoolean("ok"))
        assertTrue(undo.getString("did"), undo.getString("did").contains("has things on it now"))
        assertEquals(listOf("weather.owl"), pad.n.pages[1].items.map { it.key })
    }

    @Test fun theAskedSpotFirstShrinkingALittleThenAnywhereThenTheNextPageThenANewOne() {
        val full = NotebookPage("p2", "Busy", listOf(NotebookItem("w.owl", ItemKind.Widget, Cell(0, 0, 6, 5))))
        val pad = Pad(fold(home, full, NotebookPage("p3", "Next")))
        // Top left of Home is taken by the app: still top left, beside it.
        pad.run("place needs card top-left --page Home")
        assertEquals(Cell(1, 0, 3, 2), cellOf(pad.n, needs))
        // Busy has a single free row: a card shrinks a little to a line there.
        pad.run("place threads card --page Busy")
        assertEquals("Busy" to Cell(0, 5, 3, 1), pageOf(pad.n, threads) to cellOf(pad.n, threads))
        // A full module doesn't fit even a little smaller on Busy: the next page with room.
        pad.run("place timeline full --page Busy")
        assertEquals("Next" to Cell(0, 0, 6, 3), pageOf(pad.n, timeline) to cellOf(pad.n, timeline))
        // Nothing after the last page has room: a new page, named for the one asked.
        pad.run("place pocket full --page Next"); pad.run("place sessions full --page Next")
        assertEquals(listOf("Home", "Busy", "Next", "Next 2"), pad.n.pages.map { it.name })
        assertEquals("Next 2", pageOf(pad.n, moduleKey(ModuleKind.Sessions)!!))
    }

    @Test fun besideSpotsSitBesideTheirModule() {
        val pad = Pad(fold(NotebookPage("p1", "Home")))
        pad.run("place needs card bottom-right")
        assertEquals(Cell(3, 4, 3, 2), cellOf(pad.n, needs))
        pad.run("place threads card left-of needs")
        assertEquals(Cell(0, 4, 3, 2), cellOf(pad.n, threads))
        pad.run("place newthread line above needs")
        assertEquals(Cell(3, 3, 3, 1), cellOf(pad.n, moduleKey(ModuleKind.NewThread)!!))
        pad.run("move needs top")
        assertEquals(Cell(0, 0, 3, 2), cellOf(pad.n, needs))
        val beside = pad.run("place pocket card right-of handoff:soma")
        assertFalse(beside.getBoolean("ok"))
        assertTrue(beside.getString("error").contains("not on this canvas"))
    }

    @Test fun resizeStaysPutWhenItFitsAndOtherwiseMovesNearOnItsPage() {
        val pad = Pad(fold(NotebookPage("p1", "Home", listOf(NotebookItem("w.owl", ItemKind.Widget, Cell(3, 0, 3, 2))))))
        pad.run("place needs dot top-left")
        pad.run("resize needs line")
        assertEquals(Cell(0, 0, 3, 1), cellOf(pad.n, needs))
        pad.run("resize needs full")
        assertEquals(Cell(0, 2, 6, 3), cellOf(pad.n, needs))
        pad.n = applyVerb(pad.n, CanvasVerb.Place("x.owl", ItemKind.Widget, "p1", Cell(0, 0, 3, 2))).notebook
        val no = pad.run("resize needs full")
        assertTrue(no.getBoolean("ok"))
        assertEquals(Cell(0, 2, 6, 3), cellOf(pad.n, needs))
    }

    @Test fun refusalsChangeNothingAndSayWhy() {
        val start = fold(home, NotebookPage("p2", "Apps", listOf(NotebookItem("app.two", ItemKind.App, Cell(0, 0, 1, 1)))))
        val pad = Pad(start)
        for (line in listOf(
            "place weather card", "place needs card --page Nowhere", "page-make home", "page-remove Apps", "remove needs",
            "move needs top", "resize needs huge", "place needs card sideways", "place needs card right-of", "teleport needs", "place",
            "page-rename Home --to Apps",
        )) {
            val r = pad.run(line)
            assertFalse(line, r.getBoolean("ok"))
            assertTrue(line, r.getString("error").isNotBlank())
            assertEquals(line, start, pad.n)
        }
    }

    @Test fun keptPagesAndKeysStayAndPinnedCellsAreTakenOnEveryPage() {
        val chat = moduleKey(ModuleKind.Chat)!!
        val desk = Notebook(rows = 8, columns = 12, pages = listOf(NotebookPage("soma", "Soma", listOf(NotebookItem(chat, ItemKind.Module, Cell(0, 0, 5, 8)))), NotebookPage("d2", "More")))
        val pad = Pad(desk, ScreenRules(pinned = { listOf(Cell(0, 0, 5, 8)) }, kept = setOf(chat), keptPages = setOf("soma")))
        assertFalse(pad.run("page-remove Soma").getBoolean("ok"))
        assertFalse(pad.run("remove chat").getBoolean("ok"))
        pad.run("place needs card top-left --page More")
        assertEquals(Cell(5, 0, 4, 3), cellOf(pad.n, needs))
        // Beside a chat that holds the left side, a corner is the corner of what is left: as near to it as there is room.
        pad.run("place pocket card bottom-left --page More")
        assertEquals(Cell(5, 5, 4, 3), cellOf(pad.n, moduleKey(ModuleKind.Pocket)!!))
        pad.run("place threads card right-of chat")
        assertEquals(Cell(5, 0, 4, 3), cellOf(pad.n, threads))
        assertEquals("Soma", pageOf(pad.n, threads))
    }

    @Test fun boardSaysWhatIsWhereAndHowMuchIsFree() {
        val pad = Pad(fold(home))
        pad.run("place needs card top-left")
        val board = pad.run("board Home")
        val page = board.getJSONArray("pages").getJSONObject(0)
        assertEquals("Home", page.getString("page"))
        assertEquals(36 - 1 - 6, page.getInt("free"))
        assertEquals("needs", page.getJSONArray("items").getJSONObject(1).getString("name"))
        assertTrue(pad.run("modules").getJSONArray("modules").length() >= 9)
    }

    @Test fun aRequestIsUndoneLastChangeFirst() {
        val pad = Pad(fold(NotebookPage("p1", "Home")))
        pad.run("place needs card top-left", request = "r0")
        val start = cellOf(pad.n, needs)
        // One request moves it twice: undone, it is back where it began, not where the first move left it.
        pad.run("move needs bottom", request = "r1")
        pad.run("move needs top-right", request = "r1")
        pad.run("undo")
        assertEquals(start, cellOf(pad.n, needs))
    }

    @Test fun theFirstPageRemovedComesBackFirst() {
        val start = fold(NotebookPage("p1", "Mods", listOf(NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2)))), home.copy(id = "p2"))
        val pad = Pad(start)
        pad.run("page-remove Mods")
        assertEquals(listOf("Home"), pad.n.pages.map { it.name })
        pad.run("undo")
        assertEquals(start, pad.n)
    }

    @Test fun pageNamesAreTidiedAndInvisibleOnesRefused() {
        val pad = Pad(fold(home))
        assertTrue(pad.run("page-make Trip    plans").getBoolean("ok"))
        assertEquals("Trip plans", pad.n.pages.last().name)
        for (bad in listOf("\u200B", "a\u0007b", "x\u2060y")) {
            val r = CanvasCommands.run(listOf("page-make", bad), pad, "r9", "agent:test")
            assertFalse(bad, r.getBoolean("ok"))
        }
    }

    @Test fun anUndoOfNothingUndoableSaysSo() {
        val pad = Pad(fold(home))
        pad.points.add("r1", emptyList())
        assertTrue(pad.run("undo").getString("error").startsWith("Nothing of it can be undone here"))
    }

    @Test fun aPageForAProjectGetsItsOwnNeedsYouAndTheFirstStays() {
        val pad = Pad(fold(home))
        pad.run("place needs card top-left --page Home", request = "r0")
        pad.run("page-make Soma"); pad.run("place needs card top-left --page Soma")
        // A copy on the new page; the first is where it was.
        assertEquals("Home", pageOf(pad.n, needs))
        assertEquals("Soma", pageOf(pad.n, "module:needs#2"))
        assertEquals(ModuleRef(ModuleKind.Needs, copy = 2), moduleRef("module:needs#2"))
        assertEquals(needs, moduleRef("module:needs#2")!!.base)
        // Which copy is meant: by name, or by page; neither, and it says which there are.
        val unsure = pad.run("move needs bottom")
        assertFalse(unsure.getBoolean("ok"))
        assertTrue(unsure.getString("error"), unsure.getString("error").contains("needs#2 on \"Soma\""))
        assertTrue(pad.run("move needs#2 bottom").getBoolean("ok"))
        assertTrue(pad.run("remove needs --page Soma").getBoolean("ok"))
        assertEquals("Home", pageOf(pad.n, needs))
        assertTrue(pad.n.pages.none { p -> p.items.any { it.key == "module:needs#2" } })
        // The board calls each by the name the agent uses.
        assertEquals("needs", pad.run("board Home").getJSONArray("pages").getJSONObject(0).getJSONArray("items").getJSONObject(1).getString("name"))
    }

    @Test fun aPlaceThatSpillsOntoANewPageIsUndoneWhole() {
        val full = NotebookPage("p1", "Full", listOf(NotebookItem("w.owl", ItemKind.Widget, Cell(0, 0, 6, 6))))
        val pad = Pad(fold(full))
        // One call: a new page, and the module on it. Undone in the wrong order the page would stay, still holding it.
        assertTrue(pad.run("place timeline full --page Full").getBoolean("ok"))
        assertEquals(listOf("Full", "Full 2"), pad.n.pages.map { it.name })
        assertTrue(pad.run("undo").getBoolean("ok"))
        assertEquals(fold(full), pad.n)
    }

    @Test fun pagesAreFoundByNameBeforeId() {
        val pad = Pad(fold(NotebookPage("p1", "Home"), NotebookPage("p2", "p1")))
        pad.run("place needs card --page p1")
        assertEquals("p1", pageOf(pad.n, needs))
        assertEquals("p2", pad.n.pages.first { p -> p.items.any { it.key == needs } }.id)
    }

    @Test fun aMoveWithNowhereToGoSaysItStays() {
        val pad = Pad(fold(NotebookPage("p1", "Home", listOf(NotebookItem("w.owl", ItemKind.Widget, Cell(0, 0, 6, 4))))))
        pad.run("place needs full --page Home")
        val before = pad.n
        val r = pad.run("move needs top")
        assertTrue(r.getString("did"), r.getString("did").contains("stays where it is"))
        assertEquals(before, pad.n)
    }

    @Test fun restorePointsKeepNewestFirstWithinARequestAndSurviveBadText() {
        var text: String? = "not json"
        val points = RestorePoints({ text }, { text = it })
        assertNull(points.last())
        points.add("r1", listOf(CanvasVerb.Remove("a")))
        points.add("r1", listOf(CanvasVerb.Remove("b")))
        points.add("r2", listOf(CanvasVerb.PageRemove("p9")))
        assertEquals("r2", points.last()!!.request)
        points.drop("r2")
        assertEquals(listOf(CanvasVerb.Remove("b"), CanvasVerb.Remove("a")), points.last()!!.inverses)
    }
}
