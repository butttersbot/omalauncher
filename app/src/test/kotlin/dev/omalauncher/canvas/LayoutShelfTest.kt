package dev.omalauncher.canvas

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.applyVerb
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Saved layouts (Manga look, piece D): a whole home kept by name as the notebook store writes it, loaded back exactly as one
 * change that undo takes back, named as templates are, and reached by the agents' canvas words.
 */
class LayoutShelfTest {
    private val needs = moduleKey(ModuleKind.Needs)!!
    private val threads = moduleKey(ModuleKind.Threads)!!
    private val pocket = moduleKey(ModuleKind.Pocket)!!
    private val timeline = moduleKey(ModuleKind.Timeline)!!
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val maps = PinnedApp("app:maps#0", "Maps", "com.google.android.apps.maps/.Main", 0)
    private val signal = PinnedApp("app:signal#0", "Signal", "org.thoughtcrime.securesms/.Main", 0)

    /** A canvas in memory, as the desktop's: every verb checked as it applies, and each request one restore point. */
    private class Pad(var n: Notebook, val keeps: (String) -> Boolean = { false }) : CanvasPad {
        override val rules = ScreenRules()
        val points = RestorePoints({ text }, { text = it })
        var text: String? = null
        override fun notebook() = n
        override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
            val before = n
            val done = ArrayList<CanvasVerb>()
            var why: String? = null
            for (v in verbs) {
                if (v is CanvasVerb.PageRemove && keeps(v.id)) { why = "his ink is on it"; break }
                val r = applyVerb(n, v)
                if (!r.applied) { why = r.why; break }
                n = r.notebook
                done += v
            }
            if (done.isNotEmpty()) points.add(request, restorePoint(before, done))
            return why
        }
        override fun lastRestorePoint() = points.last()
        override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? {
            for (v in verbs) n = applyVerb(n, v).notebook
            points.drop(restored.request)
            return null
        }
    }

    private class Kept { var text: String? = null }

    private var clock = 1_000L
    private fun shelf(kept: Kept = Kept(), look: String? = "citypop") = LayoutShelf({ kept.text }, { kept.text = it }, look = { look }, now = { clock++ })

    /** His Fold: a page of apps and a folder, a Soma page of modules, a page with a widget; and his dock. */
    private val home = Notebook(rows = 8, columns = 6, dock = listOf(signal), pages = listOf(
        NotebookPage("p1", "Today", listOf(
            NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)),
            NotebookItem(chrome.key, ItemKind.App, Cell(3, 0, 1, 1), app = chrome),
            NotebookItem("folder:x", ItemKind.Folder, Cell(4, 0, 1, 1), apps = listOf(maps)),
        )),
        NotebookPage("p2", "Soma", listOf(
            NotebookItem(needs, ItemKind.Module, Cell(0, 0, 3, 2)),
            NotebookItem(threads, ItemKind.Module, Cell(3, 0, 3, 2)),
            NotebookItem(timeline, ItemKind.Module, Cell(0, 2, 6, 2)),
        )),
        NotebookPage("p3", "Notes"),
    ))

    /** A page as it reads: its name and what is where, in no order. */
    private fun shape(n: Notebook) = n.pages.map { p -> Triple(p.id, p.name, p.items.map { Triple(it.key, it.kind, it.cell) }.toSet()) }

    private fun run(pad: CanvasPad, line: String, request: String = "r" + System.nanoTime()) = CanvasCommands.run(line.split(' '), pad, request, "agent:test")

    @Test fun aSavedLayoutIsTheWholeHomeAsTheStoreWritesIt() {
        val kept = Kept()
        val saved = shelf(kept).save("Work", home)
        assertEquals(NotebookStore.write(home), saved.text)
        assertEquals("citypop", saved.look)
        assertEquals(3, saved.pages)
        assertFalse(saved.auto)
        // Read back by another shelf on the same text: the same home, apps, folder and dock included.
        val again = shelf(kept).get("work")!!
        assertEquals(home, again.notebook())
        assertEquals(saved.time, again.time)
        // The shelf's own document is versioned; the notebook inside it is the store's text, unchanged.
        val doc = JSONObject(kept.text!!)
        assertEquals(LAYOUTS_VERSION, doc.getInt("v"))
        assertEquals(saved.text, doc.getJSONObject("data").getJSONArray("layouts").getJSONObject(0).getString("notebook"))
    }

    @Test fun listedNewestFirstAndAutoSavesReplaceTheirName() {
        val s = shelf()
        s.save("Work", home)
        s.autoSave("Before Manga", home)
        s.save("Weekend", home)
        assertEquals(listOf("Weekend", "Before Manga", "Work"), s.list().map { it.name })
        // Home saving by itself replaces its own by that name; he is asked for --replace.
        val second = s.autoSave("before manga", home.copy(pages = home.pages.take(1)))
        assertEquals(3, s.list().size)
        assertEquals(1, s.get("Before Manga")!!.pages)
        assertTrue(second.auto)
        val refused = runCatching { s.save("WORK", home) }.exceptionOrNull()
        assertTrue(refused?.message, refused?.message.orEmpty().contains("--replace"))
        assertEquals(1, s.save("work", home.copy(pages = home.pages.take(1)), replace = true).pages)
        assertEquals(3, s.list().size)
    }

    @Test fun namesAreTheTemplatesRule() {
        assertEquals("Work week", layoutNameOf("  Work   week "))
        for (bad in listOf("../x", "a/b", "", " ", "-lead", "x".repeat(41), "dots..dots", "Before: Manga"))
            assertTrue(bad, runCatching { layoutNameOf(bad) }.exceptionOrNull() is AskRefused)
        assertEquals("x".repeat(40), layoutNameOf("x".repeat(40)))
        // The same rule as a template's, whatever is typed.
        for (typed in listOf("Morning Run", "v1.2", "a_b-c", "../etc", "ü")) {
            assertEquals(typed, runCatching { templateNameOf(typed) }.isSuccess, runCatching { layoutNameOf(typed) }.isSuccess)
        }
        val s = shelf()
        assertTrue(runCatching { s.save("../../notebook", home) }.exceptionOrNull() is AskRefused)
        assertTrue(s.list().isEmpty())
    }

    @Test fun renameAndDelete() {
        val s = shelf()
        s.save("Work", home)
        s.save("Weekend", home)
        assertEquals("Desk", s.rename("work", "Desk").name)
        assertNull(s.get("Work"))
        assertEquals("DESK", s.rename("desk", "DESK").name)
        assertTrue(runCatching { s.rename("DESK", "weekend") }.exceptionOrNull()?.message.orEmpty().contains("already called \"Weekend\""))
        assertTrue(runCatching { s.rename("nope", "x") }.exceptionOrNull() is AskRefused)
        assertTrue(s.delete("weekend"))
        assertFalse(s.delete("weekend"))
        assertEquals(listOf("DESK"), s.list().map { it.name })
    }

    @Test fun aFullShelfMakesRoomOnlyFromWhatHomeSavedByItself() {
        val s = shelf()
        s.autoSave("Before Manga", home)
        for (i in 1 until LAYOUTS_MAX) s.save("L$i", home)
        assertEquals(LAYOUTS_MAX, s.list().size)
        // A new one of his takes the oldest home saved by itself.
        s.save("Fresh", home)
        assertNull(s.get("Before Manga"))
        assertEquals(LAYOUTS_MAX, s.list().size)
        // With only his own left, nothing of his is ever dropped: he deletes one first.
        val full = runCatching { s.save("More", home) }.exceptionOrNull()
        assertTrue(full?.message, full?.message.orEmpty().contains("delete one first"))
        assertTrue(runCatching { s.autoSave("Before Manga", home) }.exceptionOrNull() is AskRefused)
        assertNotNull(s.get("L1"))
    }

    @Test fun badTextIsAnEmptyShelfAndANewerOneIsNeverWrittenOver() {
        val kept = Kept()
        for (bad in listOf("{not json", "[1]", "", "{\"v\":1,\"data\":{\"layouts\":[{\"name\":\"../x\",\"time\":1,\"notebook\":\"\"},\"junk\"]}}")) {
            kept.text = bad
            assertTrue(bad, shelf(kept).list().isEmpty())
        }
        kept.text = JSONObject().put("v", LAYOUTS_VERSION + 1).put("data", JSONObject().put("layouts", JSONArray().put(
            JSONObject().put("name", "Future").put("time", 5).put("notebook", NotebookStore.write(home))))).toString()
        val newer = kept.text
        val s = shelf(kept)
        assertEquals(listOf("Future"), s.list().map { it.name })
        assertTrue(runCatching { s.save("Mine", home) }.exceptionOrNull()?.message.orEmpty().contains("newer Soma"))
        assertTrue(runCatching { s.delete("Future") }.isFailure)
        assertEquals(newer, kept.text)
    }

    @Test fun referencedArtIsEveryArtKeyOnEveryShelvedPage() {
        val kept = Kept()
        val s = shelf(kept)
        s.save("Plain", home)
        assertTrue(s.referencedArt().isEmpty())
        // Written as a build with piece A writes it: art items are their keys, kind "art". Read from the keys alone.
        fun withArt(vararg ids: String): String = JSONObject(NotebookStore.write(home)).also { doc ->
            val items = doc.getJSONObject("data").getJSONArray("pages").getJSONObject(2).getJSONArray("items")
            ids.forEachIndexed { i, id -> items.put(JSONObject().put("key", "art:$id").put("kind", "art").put("cell", JSONObject().put("x", i).put("y", 0).put("w", 1).put("h", 1))) }
            items.put(JSONObject().put("key", "art:NOT-AN-ID").put("kind", "art"))
        }.toString()
        val data = JSONObject(kept.text!!).getJSONObject("data")
        data.getJSONArray("layouts").put(JSONObject().put("name", "Manga").put("time", 9).put("notebook", withArt("0123456789abcdef", "fedcba9876543210")))
            .put(JSONObject().put("name", "Manga two").put("time", 10).put("notebook", withArt("0123456789abcdef")))
        kept.text = JSONObject().put("v", LAYOUTS_VERSION).put("data", data).toString()
        assertEquals(setOf("0123456789abcdef", "fedcba9876543210"), s.referencedArt())
        s.delete("Manga")
        assertEquals(setOf("0123456789abcdef"), s.referencedArt())
    }

    @Test fun loadMakesHomeTheLayoutExactlyAndUndoTakesItBackWhole() {
        val s = shelf()
        s.save("Work", home)
        // Since then: Needs you and Threads traded places, the Timeline went, a page was renamed and one taken away, a new
        // page with Pocket came, a widget landed on Soma, and Chrome moved onto the clock's cells.
        var changed = home
        for (v in listOf(
            CanvasVerb.Move(needs, "p2", Cell(0, 4, 3, 2)), CanvasVerb.Move(threads, "p2", Cell(0, 0, 3, 2)), CanvasVerb.Move(needs, "p2", Cell(3, 0, 3, 2)),
            CanvasVerb.Remove(timeline), CanvasVerb.PageRename("p1", "Morning"), CanvasVerb.PageRemove("p3"),
            CanvasVerb.PageMake("p4", "Drive"), CanvasVerb.Place(pocket, ItemKind.Module, "p4", Cell(0, 0, 3, 2)),
            CanvasVerb.Place("weather.owl", ItemKind.Widget, "p2", Cell(0, 2, 2, 2)),
            CanvasVerb.Move("clock.owl", "p1", Cell(0, 4, 3, 2)), CanvasVerb.Move(chrome.key, "p1", Cell(0, 0, 1, 1)),
        )) changed = applyVerb(changed, v).notebook.also { assertTrue(v.toString(), applyVerb(changed, v).applied) }
        val pad = Pad(changed).withLayouts(s)
        val loaded = run(pad, "layout.load work", request = "load-1")
        assertTrue(loaded.toString(), loaded.getBoolean("ok"))
        assertTrue(loaded.getString("did"), loaded.getString("did").startsWith("laid out the layout \"Work\" (saved in the look \"citypop\")"))
        assertEquals(shape(home), shape(pad.notebook()))
        // One request, one restore point: undo makes home what it was just before, every page and cell.
        assertEquals(1, (pad as LayoutPad).let { (it.layouts); 1 })
        val undo = run(pad, "undo")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        assertEquals(shape(changed).map { it.second to it.third }, shape(pad.notebook()).map { it.second to it.third })
        assertFalse(run(pad, "undo").getBoolean("ok"))
    }

    @Test fun aRingOfMovesGoesByWayOfAPageForTheMomentAndLeavesNoTrace() {
        val s = shelf()
        val three = Notebook(rows = 4, columns = 6, pages = listOf(NotebookPage("p1", "One", listOf(
            NotebookItem(needs, ItemKind.Module, Cell(0, 0, 2, 2)), NotebookItem(threads, ItemKind.Module, Cell(2, 0, 2, 2)), NotebookItem(pocket, ItemKind.Module, Cell(4, 0, 2, 2)),
            NotebookItem("a.owl", ItemKind.Widget, Cell(0, 2, 6, 2)),
        ))))
        s.save("Ring", three)
        // Each of the three where the next was, and the full-width widget over the whole bottom: nothing can move first.
        val turned = three.copy(pages = listOf(three.pages[0].copy(items = listOf(
            NotebookItem(needs, ItemKind.Module, Cell(2, 0, 2, 2)), NotebookItem(threads, ItemKind.Module, Cell(4, 0, 2, 2)), NotebookItem(pocket, ItemKind.Module, Cell(0, 0, 2, 2)),
            NotebookItem("a.owl", ItemKind.Widget, Cell(0, 2, 6, 2)),
        ))))
        val plan = loadPlan(turned, s.get("Ring")!!.notebook()!!)
        assertTrue(plan.verbs.any { it is CanvasVerb.PageMake && it.id == PARK_PAGE })
        assertEquals(CanvasVerb.PageRemove(PARK_PAGE), plan.verbs.last())
        val pad = Pad(turned).withLayouts(s)
        assertTrue(run(pad, "layout.load Ring").getBoolean("ok"))
        assertEquals(shape(three), shape(pad.notebook()))
        assertTrue(run(pad, "undo").getBoolean("ok"))
        assertEquals(shape(turned), shape(pad.notebook()))
    }

    @Test fun appsAreNeverTakenOffAndOnesNoLongerOnHomeAreSaid() {
        val s = shelf()
        s.save("Work", home)
        // Chrome was unpinned since, and Maps' folder too; the Timeline went and Signal was pinned onto its cells.
        var now = home.copy(pages = home.pages.map { p ->
            when (p.id) {
                "p1" -> p.copy(items = listOf(p.items[0]))
                "p2" -> p.copy(items = p.items.filter { it.key != timeline } + NotebookItem(signal.key, ItemKind.App, Cell(1, 3, 1, 1), app = signal))
                else -> p
            }
        })
        val pad = Pad(now).withLayouts(s)
        val did = run(pad, "layout.load Work").getString("did")
        assertTrue(did, did.contains("Chrome (no longer on home)"))
        assertTrue(did, did.contains("the folder of Maps (no longer on home)"))
        assertTrue(did, did.contains("kept what the layout doesn't have: Signal"))
        // Signal stays on its page, moved to the first free cell; the rest is the layout.
        now = pad.notebook()
        val soma = now.pages.first { it.id == "p2" }
        assertEquals(Cell(0, 4, 1, 1), soma.items.first { it.key == signal.key }.cell)
        assertEquals(shape(home)[1].third, soma.items.filter { it.key != signal.key }.map { Triple(it.key, it.kind, it.cell) }.toSet())
        assertEquals(listOf("clock.owl"), now.pages[0].items.map { it.key })
        assertEquals(shape(home).drop(2), shape(now).drop(2))
        // Signal where it was, with nothing taken off: "nothing changed", and why.
        val already = Pad(home.copy(pages = home.pages.map { p -> if (p.id == "p1") p.copy(items = listOf(p.items[0], NotebookItem(signal.key, ItemKind.App, Cell(3, 0, 1, 1), app = signal))) else p })).withLayouts(s)
        assertTrue(run(already, "layout.load Work").getString("did").startsWith("nothing changed: home already has what of the layout \"Work\" can be laid here; left: Chrome"))
    }

    @Test fun anAppWithNoRoomGoesToAPageOfItsOwnAndAPageWithHisInkStays() {
        val s = shelf()
        val full = Notebook(rows = 2, columns = 2, pages = listOf(NotebookPage("p1", "One", listOf(NotebookItem("big.owl", ItemKind.Widget, Cell(0, 0, 2, 2))))))
        s.save("Big", full)
        val now = Notebook(rows = 2, columns = 2, pages = listOf(
            NotebookPage("p1", "One", listOf(NotebookItem(chrome.key, ItemKind.App, Cell(0, 0, 1, 1), app = chrome))),
            NotebookPage("p2", "Inked"), NotebookPage("p3", "Spare"),
        ))
        val pad = Pad(now, keeps = { it == "p2" }).withLayouts(s)
        val did = run(pad, "layout.load big").getString("did")
        assertTrue(did, did.contains("the page \"Inked\" stays: his ink is on it"))
        val pages = pad.notebook().pages
        // The page for what has no room comes after the layout's own; the page that stayed after it.
        assertEquals(listOf("One", "Kept", "Inked"), pages.map { it.name })
        assertEquals(listOf("big.owl"), pages[0].items.map { it.key })
        assertEquals(listOf(chrome.key), pages[1].items.map { it.key })
    }

    @Test fun loadingWhatHomeAlreadyIsChangesNothingAndMakesNoRestorePoint() {
        val s = shelf()
        s.save("Work", home)
        val pad = Pad(home)
        val with = pad.withLayouts(s)
        assertTrue(run(with, "layout.load Work").getString("did").startsWith("home already is the layout \"Work\""))
        assertNull(pad.lastRestorePoint())
    }

    @Test fun cellsOffThisGridAreSaidAndTheRestIsLaid() {
        val s = shelf()
        s.save("Tall", home.copy(rows = 8))
        val short = home.copy(rows = 3, pages = home.pages.map { it.copy(items = it.items.filter { i -> i.cell!!.bottom <= 2 }) })
        val pad = Pad(short).withLayouts(s)
        val did = run(pad, "layout.load Tall").getString("did")
        assertTrue(did, did.contains("Timeline (its cells are off this grid)"))
        assertEquals(setOf(needs, threads), pad.notebook().pages[1].items.map { it.key }.toSet())
    }

    @Test fun theWords() {
        val s = shelf()
        val pad = Pad(home).withLayouts(s)
        assertEquals("kept home as the layout \"Work week\" (3 pages)", run(pad, "layout.save Work   week").getString("did"))
        assertFalse(run(pad, "layout.save work week").getBoolean("ok"))
        assertTrue(run(pad, "layout-save work week --replace").getBoolean("ok"))
        assertTrue(run(pad, "layout.save Desk").getBoolean("ok"))
        val list = run(pad, "layout.list").getJSONArray("layouts")
        assertEquals(listOf("Desk", "work week"), (0 until list.length()).map { list.getJSONObject(it).getString("name") })
        assertEquals("citypop", list.getJSONObject(0).getString("look"))
        assertEquals("the layout \"Desk\" is now called \"Office\"", run(pad, "layout.rename desk --to Office").getString("did"))
        assertEquals("deleted the layout \"Office\"", run(pad, "layout.delete office").getString("did"))
        val missing = run(pad, "layout.load Office")
        assertFalse(missing.getBoolean("ok"))
        assertTrue(missing.getString("error"), missing.getString("error").contains("Layouts: work week"))
        for (bad in listOf("layout.save", "layout.load", "layout.list extra", "layout.rename Work week", "layout.delete", "layout.save ../x"))
            assertFalse(bad, run(pad, bad).getBoolean("ok"))
        // The plain "layout" is still LAYOUT.md's, and a screen without saved layouts says where they are.
        assertTrue(run(pad, "layout").getString("error").contains("Templates and LAYOUT.md"))
        val bare = run(Pad(home), "layout.list")
        assertFalse(bare.getBoolean("ok"))
        assertTrue(bare.getString("error"), bare.getString("error").contains("kept on the Fold"))
        // An unknown layout word is the usage, never another verb.
        assertTrue(run(pad, "layout.swap x").getString("error").startsWith("Usage"))
    }

    @Test fun nextNameIsTheFirstFree() {
        val s = shelf()
        assertEquals("Layout 1", nextLayoutName(s))
        s.save("Layout 1", home)
        s.save("layout 3", home)
        assertEquals("Layout 2", nextLayoutName(s))
        s.save("Layout 2", home)
        assertEquals("Layout 4", nextLayoutName(s))
    }

    @Test fun aRestorePointWithAMadePageIsUndoneEvenWhenTheShelfIsGone() {
        // Undo never reads the shelf: what undoes a load is in the restore point alone.
        val kept = Kept()
        val s = shelf(kept)
        s.save("Work", home)
        val pad = Pad(home.copy(pages = home.pages.take(1))).withLayouts(s)
        assertTrue(run(pad, "layout.load Work").getBoolean("ok"))
        assertEquals(3, pad.notebook().pages.size)
        s.delete("Work")
        assertTrue(run(pad, "undo").getBoolean("ok"))
        assertEquals(listOf("Today"), pad.notebook().pages.map { it.name })
        if (kept.text == null) fail("the shelf was written")
    }
}
