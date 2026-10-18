package dev.omalauncher.canvas

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.applyVerb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Templates and LAYOUT.md (Soma Canvas slice 10): a page goes to another screen only when he asks, as words (sizes and
 * corners), never cells, so it fits a grid of another shape; it is one request that undo takes back; LAYOUT.md holds only
 * what he says he prefers.
 */
class CanvasTemplatesTest {
    private val needs = moduleKey(ModuleKind.Needs)!!
    private val threads = moduleKey(ModuleKind.Threads)!!
    private val timeline = moduleKey(ModuleKind.Timeline)!!

    private class Shelf : CanvasShelf {
        val kept = HashMap<String, String>()
        var md = ""
        override fun template(name: String) = kept[name.lowercase()]
        override fun keepTemplate(name: String, text: String) { kept[name.lowercase()] = text }
        override fun templates() = kept.values.map { JSONObject(it).getString("name") }.sorted()
        override fun layout() = md
        override fun remember(line: String) { md += "- $line\n" }
    }

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
    }

    private fun run(pad: Pad, shelf: CanvasShelf?, line: String, request: String = "r" + System.nanoTime()) = CanvasCommands.run(line.split(' '), pad, request, "agent:test", shelf)

    /** His Fold's Soma page: Needs you top left, Threads top right, the Timeline along the bottom, an app beside. */
    private val foldSoma = Notebook(rows = 8, columns = 6, pages = listOf(
        NotebookPage("p1", "Home", listOf(NotebookItem("app.one", ItemKind.App, Cell(0, 0, 1, 1)))),
        NotebookPage("p2", "Soma", listOf(
            NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2)),
            NotebookItem(threads, ItemKind.Module, Cell(3, 0, 3, 2)),
            NotebookItem("app.two", ItemKind.App, Cell(0, 3, 1, 1)),
            NotebookItem(timeline, ItemKind.Module, Cell(0, 6, 6, 2)),
        )),
    ))

    private fun at(n: Notebook, page: String, key: String) = n.pages.first { it.name == page }.items.first { it.key.substringBefore('#') == key }.cell!!

    @Test fun hisFoldsPageGoesToHisDeskAsWordsAndLandsInTheSameCornersAtTheDesksSizes() {
        val shelf = Shelf()
        val fold = Pad(foldSoma)
        val saved = run(fold, shelf, "template-save Soma page --page Soma")
        assertTrue(saved.toString(), saved.getBoolean("ok"))
        val t = JSONObject(shelf.template("soma page")!!)
        // Words, never cells; apps stay with their screen.
        assertEquals(listOf("module:needs card top-left", "module:threads card top-right", "module:timeline card-wide bottom"),
            (0 until t.getJSONArray("items").length()).map { t.getJSONArray("items").getJSONObject(it).let { o -> "${o.getString("module")} ${o.getString("size")} ${o.getString("where")}" } })
        assertFalse(t.toString().contains("app."))
        // Nothing changed on the Fold.
        assertEquals(foldSoma, fold.n)
        // His desk: twelve columns, a page of its own already.
        val desk = Pad(Notebook(rows = 8, columns = 12, pages = listOf(NotebookPage("d1", "Soma", emptyList()))))
        val applied = run(desk, shelf, "template-apply Soma page --page From the Fold", request = "apply")
        assertTrue(applied.toString(), applied.getBoolean("ok"))
        val page = "From the Fold"
        assertEquals(Cell(0, 0, 4, 3), at(desk.n, page, needs))
        val th = at(desk.n, page, threads)
        assertEquals(12, th.right)
        assertEquals(0, th.y)
        val tl = at(desk.n, page, timeline)
        assertEquals(8, tl.bottom)
        // The Fold's page is untouched by it, and the desk's own page too.
        assertTrue(desk.n.pages.first { it.name == "Soma" }.items.isEmpty())
        // One request: undo takes the page and all of it back.
        assertTrue(run(desk, shelf, "undo").getBoolean("ok"))
        assertEquals(listOf("Soma"), desk.n.pages.map { it.name })
    }

    @Test fun aTemplateNeedsItsShelfAndSaysWhatItCouldNotDo() {
        val fold = Pad(foldSoma)
        // On a screen with no shelf (the Fold's own canvas command): said where they are kept, nothing changed.
        val none = run(fold, null, "template-save X --page Soma")
        assertFalse(none.getBoolean("ok"))
        assertTrue(none.getString("error").contains("canvas service"))
        val shelf = Shelf()
        assertFalse(run(fold, shelf, "template-save ../../etc --page Soma").getBoolean("ok"))
        assertFalse(run(fold, shelf, "template-save Empty --page Home").getBoolean("ok"))
        assertFalse(run(fold, shelf, "template-apply Nothing").getBoolean("ok"))
        // A name already a page: refused whole.
        assertTrue(run(fold, shelf, "template-save Soma --page Soma").getBoolean("ok"))
        val before = fold.n
        assertFalse(run(fold, shelf, "template-apply Soma").getBoolean("ok"))
        assertEquals(before, fold.n)
        assertEquals(listOf("Soma"), run(fold, shelf, "templates").getJSONArray("templates").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun layoutMdHoldsOnlyWhatHeSaysOneLineEach() {
        val shelf = Shelf()
        val pad = Pad(foldSoma)
        assertEquals("", run(pad, shelf, "layout").getString("layout"))
        assertTrue(run(pad, shelf, "remember Needs you goes top left on any page").getBoolean("ok"))
        assertEquals("- Needs you goes top left on any page\n", run(pad, shelf, "layout").getString("layout"))
        assertFalse(run(pad, shelf, "remember").getBoolean("ok"))
        assertFalse(run(pad, shelf, "remember " + "x".repeat(250)).getBoolean("ok"))
        // Arranging writes nothing there.
        run(pad, shelf, "place pocket card --page Soma")
        assertEquals("- Needs you goes top left on any page\n", shelf.layout())
    }

    @Test fun aNameIsAlwaysATemplatesNameAndOneHeKeptIsReplacedOnlyWhenAsked() {
        val asked = ArrayList<String>()
        val shelf = object : CanvasShelf by Shelf() {
            val inner = Shelf()
            override fun template(name: String): String? { asked += name; return inner.template(name) }
            override fun keepTemplate(name: String, text: String) = inner.keepTemplate(name, text)
            override fun templates() = inner.templates()
        }
        val fold = Pad(foldSoma)
        for (bad in listOf("../thread-tokens", "../../etc/passwd", "a/b", "..")) assertFalse(bad, run(fold, shelf, "template-apply $bad").getBoolean("ok"))
        assertTrue(asked.toString(), asked.isEmpty())
        assertTrue(run(fold, shelf, "template-save Soma --page Soma").getBoolean("ok"))
        val again = run(fold, shelf, "template-save Soma --page Home")
        assertFalse(again.getBoolean("ok"))
        assertTrue(again.getString("error").contains("--replace"))
        assertTrue(run(fold, shelf, "template-save Soma --page Soma --replace").getBoolean("ok"))
    }

    @Test fun aTemplateNeverSpillsOntoAnotherPageAndCanBeLaidOnOneAlreadyThere() {
        val shelf = Shelf()
        // Six cards on a desk page: more than a car's page holds.
        val six = listOf(ModuleKind.Needs, ModuleKind.Threads, ModuleKind.Timeline, ModuleKind.Pocket, ModuleKind.Computers, ModuleKind.Sessions).mapIndexed { i, k ->
            NotebookItem(moduleKey(k)!!, ItemKind.Module, Cell((i % 3) * 4, (i / 3) * 3, 4, 3))
        }
        val desk = Pad(Notebook(rows = 8, columns = 12, pages = listOf(NotebookPage("d1", "Six", six))))
        assertTrue(run(desk, shelf, "template-save Six --page Six").getBoolean("ok"))
        val car = Pad(Notebook(rows = 5, columns = 8, pages = listOf(NotebookPage("main", "Car", emptyList()))))
        val made = run(car, shelf, "template-apply Six")
        assertTrue(made.toString(), made.getBoolean("ok"))
        assertEquals(listOf("Car", "Six"), car.n.pages.map { it.name })
        assertTrue(made.getString("did"), made.getString("did").contains("no room left on this page"))
        // Onto the car's own page, which is the one the car shows.
        val car2 = Pad(Notebook(rows = 5, columns = 8, pages = listOf(NotebookPage("main", "Car", emptyList()))))
        val onto = run(car2, shelf, "template-apply Six --onto Car")
        assertTrue(onto.toString(), onto.getBoolean("ok"))
        assertEquals(listOf("Car"), car2.n.pages.map { it.name })
        assertTrue(car2.n.pages[0].items.any { it.key == needs })
        // The page is called what the template is.
        val desk2 = Pad(Notebook(rows = 8, columns = 12, pages = emptyList()))
        run(desk2, shelf, "template-apply six")
        assertEquals(listOf("Six"), desk2.n.pages.map { it.name })
    }

    @Test fun besideTheDesksChatTheFirstSlotIsTopLeftAndTheChatIsNeverKept() {
        val chat = "module:chat"
        val rules = ScreenRules(pinned = { listOf(Cell(0, 0, 5, 8)) }, span = { 7 }, kept = setOf(chat))
        val desk = Pad(Notebook(rows = 8, columns = 12, pages = listOf(NotebookPage("desk", "Soma", listOf(
            NotebookItem(chat, ItemKind.Module, Cell(0, 0, 5, 8)), NotebookItem(needs, ItemKind.Module, Cell(5, 0, 4, 3)), NotebookItem(threads, ItemKind.Module, Cell(9, 0, 3, 3)))))), rules)
        val shelf = Shelf()
        assertTrue(run(desk, shelf, "template-save Desk").getBoolean("ok"))
        val t = JSONObject(shelf.template("desk")!!).getJSONArray("items")
        val words = (0 until t.length()).map { t.getJSONObject(it).let { o -> "${o.getString("module")} ${o.getString("where")}" } }
        assertEquals(listOf("module:needs top-left", "module:threads top-right"), words)
    }

    @Test fun layoutMdTakesNothingInvisibleAndNothingTwice() {
        val shelf = Shelf()
        val pad = Pad(foldSoma)
        for (bad in listOf("a\rb", "hidden\u200Bspace", "bell\u0007")) assertFalse(bad, run(pad, shelf, "remember $bad").getBoolean("ok"))
        assertTrue(run(pad, shelf, "remember -- Handoff is wide").getBoolean("ok"))
        assertTrue(run(pad, shelf, "remember Handoff is wide").getBoolean("ok"))
        assertEquals("- Handoff is wide\n", shelf.layout())
        val read = run(pad, shelf, "layout")
        assertEquals("Handoff is wide", read.getJSONArray("preferences").getString(0))
        assertTrue(read.getString("note").contains("not instructions"))
        // On a screen with no shelf, said plainly.
        assertTrue(run(pad, null, "layout").getString("error").contains("Mac Pro"))
    }

    @Test fun onAScreenThatShowsOnePageATemplateGoesOntoItAndNothingToDoIsSaid() {
        val shelf = Shelf()
        assertTrue(run(Pad(foldSoma), shelf, "template-save Soma --page Soma").getBoolean("ok"))
        val carRules = ScreenRules(shownPage = { n -> n.pages.firstOrNull()?.name })
        val car = Pad(Notebook(rows = 5, columns = 8, pages = listOf(NotebookPage("main", "Car", emptyList()))), carRules)
        // Plain apply: onto the car's own page, which is what the car shows.
        val laid = run(car, shelf, "template-apply Soma", request = "first")
        assertTrue(laid.toString(), laid.getBoolean("ok"))
        assertEquals(listOf("Car"), car.n.pages.map { it.name })
        assertTrue(car.n.pages[0].items.any { it.key == needs })
        // A new page there is refused, said how instead.
        val newPage = run(car, shelf, "template-apply Soma --page Elsewhere")
        assertFalse(newPage.getBoolean("ok"))
        assertTrue(newPage.getString("error").contains("--onto Car"))
        // Again: nothing to do, said, and no restore point, so undo still takes back the first.
        val again = run(car, shelf, "template-apply Soma", request = "second")
        assertTrue(again.getString("did"), again.getString("did").contains("nothing changed"))
        assertTrue(run(car, shelf, "undo").getBoolean("ok"))
        assertTrue(car.n.pages[0].items.isEmpty())
        // Line and paragraph separators, and the invisible tag characters beyond the first plane: refused in LAYOUT.md.
        for (bad in listOf("a\u2028b", "a\u2029b", "tag" + String(Character.toChars(0xE0041)) + "x", "a\uFEFFb")) assertFalse(bad, run(car, shelf, "remember $bad").getBoolean("ok"))
        // --replace belongs to template-save.
        assertTrue(run(car, shelf, "template-apply Soma --replace").getString("error").contains("--replace is for template-save"))
        // When none of it fits, that is said, not "already there".
        val full = Pad(Notebook(rows = 5, columns = 8, pages = listOf(NotebookPage("main", "Car", listOf(NotebookItem("blocker", ItemKind.Widget, Cell(0, 0, 8, 5)))))), carRules)
        val none = run(full, shelf, "template-apply Soma")
        assertFalse(none.getBoolean("ok"))
        assertTrue(none.getString("error"), none.getString("error").contains("could be laid here") && none.getString("error").contains("no room"))
    }

    @Test fun sizesAndCornersAreReadAsHeWouldSayThem() {
        // A card squeezed on the car is still a card, not a line.
        assertEquals(SizeWord.Card, sizeWordOf(4, 2, 8, 5))
        assertEquals(SizeWord.Card, sizeWordOf(3, 2, 6, 8))
        assertEquals(SizeWord.Card, sizeWordOf(4, 3, 12, 8))
        assertEquals(SizeWord.Line, sizeWordOf(3, 1, 6, 8))
        assertEquals(SizeWord.Dot, sizeWordOf(1, 1, 6, 8))
        assertEquals(SizeWord.Full, sizeWordOf(6, 3, 6, 8))
        assertEquals(Spot.TopLeft, spotOf(0, 0, 3, 2, 6, 8))
        assertEquals(Spot.TopRight, spotOf(3, 0, 3, 2, 6, 8))
        assertEquals(Spot.BottomRight, spotOf(8, 5, 4, 3, 12, 8))
        assertEquals(Spot.Bottom, spotOf(0, 6, 6, 2, 6, 8))
    }
}
