package dev.omalauncher.canvas

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.applyVerb
import dev.omalauncher.widgets.layout.artKey
import dev.omalauncher.widgets.layout.inPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Storyboard (ネーム, Manga look round 4): trying a panel layout changes nothing until "Use this" (the page.panels verb, one
 * restore point); panels swap and move on a page and across pages; the tray puts things in and takes them off; saved layouts
 * load on the second tap, with an undo.
 */
class StoryboardTest {
    private val needs = moduleKey(ModuleKind.Needs)!!
    private val threads = moduleKey(ModuleKind.Threads)!!
    private val timeline = moduleKey(ModuleKind.Timeline)!!
    private val pocket = moduleKey(ModuleKind.Pocket)!!
    private val layouts = moduleKey(ModuleKind.Layouts)!!
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val skyline = artKey("df82992b137092fd")
    private val cassette = artKey("0123456789abcdef")

    /** A canvas in memory: each verb checked as it applies, each request one restore point, as the Fold's pad keeps them. */
    private class Pad(var n: Notebook, val keeps: (String) -> Boolean = { false }) : CanvasPad {
        override val rules = ScreenRules()
        var text: String? = null
        val points = RestorePoints({ text }, { text = it })
        val requests = ArrayList<String>()

        override fun notebook() = n

        override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
            val before = n
            val done = ArrayList<CanvasVerb>()
            var why: String? = null
            for (v in verbs) {
                if (v is CanvasVerb.PageRemove && keeps(v.id)) { why = "the page \"${n.pages.first { it.id == v.id }.name}\" has notes, stickers or ink on it, so it stays; he removes it himself in the overview"; break }
                val r = applyVerb(n, v)
                if (!r.applied) { why = r.why; break }
                n = r.notebook
                done += v
            }
            if (done.isNotEmpty()) { points.add(request, restorePoint(before, done)); requests += request }
            return why
        }

        override fun lastRestorePoint() = points.last()

        override fun restorePoints() = points.all().reversed()

        override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? {
            for (v in verbs) n = applyVerb(n, v).notebook
            points.drop(restored.request)
            return null
        }
    }

    private val rows = 12
    private val opening = PanelLayouts.panels("opening", 6, rows)
    private val beats = PanelLayouts.panels("beats", 6, rows)

    /** Page 1 cut Opening: the skyline, Needs you, Chrome, two empty panels. Page 2 cut Three beats: Threads, Timeline, one empty. */
    private fun home() = Notebook(rows = rows, pages = listOf(
        NotebookPage("p1", "Page 1", listOf(
            NotebookItem(skyline, ItemKind.Art, opening[0].cell),
            NotebookItem(needs, ItemKind.Module, opening[1].cell),
            NotebookItem(chrome.key, ItemKind.App, opening[2].cell, app = chrome),
        ), panels = "opening"),
        NotebookPage("p2", "Page 2", listOf(
            NotebookItem(threads, ItemKind.Module, beats[0].cell),
            NotebookItem(timeline, ItemKind.Module, beats[1].cell),
        ), panels = "beats"),
    ))

    private var clock = 1_000L
    private var shelfText: String? = null
    private val shelf = LayoutShelf({ shelfText }, { shelfText = it }, look = { "manga" }, now = { clock })

    private fun board(pad: Pad) = Storyboard({ pad }, shelf, artIds = { listOf("df82992b137092fd", "0123456789abcdef") }, now = { clock++ }).also { it.open("p1") }

    /** Home as it reads: each page's id, name and panels, and what is on it where, whatever order the list has. */
    private fun shape(n: Notebook) = n.pages.map { p -> Triple(p.id to p.name, p.panels, p.items.map { it.key to it.cell }.sortedBy { it.first }) }

    private fun Notebook.at(key: String): Pair<String, Cell?>? = pages.firstNotNullOfOrNull { p -> p.items.firstOrNull { it.key == key }?.let { p.id to it.cell } }

    @Test
    fun aTriedLayoutIsOnlyShownUntilUseThisMakesItOneRestorePoint() {
        val pad = Pad(home())
        val b = board(pad)
        b.tryLayout("beats")
        val t = b.trial!!
        assertEquals("beats", t.layout)
        assertEquals("nothing changes while it is tried", home(), pad.n)
        // Three things into Three beats: everything fits, so nothing is named as leaving.
        assertEquals(emptyList<String>(), t.left)
        assertTrue(inPanels(pad.n.copy(pages = pad.n.pages.map { if (it.id == "p1") t.shown else it }), t.shown))
        b.useTrial()
        assertNull(b.trial)
        assertEquals("\"Page 1\" is now Three beats.", b.said!!.text)
        assertEquals("beats", pad.n.pages[0].panels)
        assertTrue(inPanels(pad.n, pad.n.pages[0]))
        assertEquals(1, pad.requests.size)
        assertTrue(b.said!!.ok)
        // The agents' undo takes the whole refit back.
        assertTrue(CanvasCommands.run(listOf("undo"), pad, "r-undo", "agent:front").optBoolean("ok"))
        assertEquals(shape(home()), shape(pad.n))
    }

    @Test
    fun backPutsTheTrialAwayAndATrialNamesWhatWouldMoveOn() {
        val pad = Pad(home())
        val b = board(pad)
        b.tryLayout("splash")
        val t = b.trial!!
        // One panel for three things: the skyline (the biggest) stays; Needs you takes page 2's empty panel, Chrome a new page.
        assertEquals(listOf("Needs you would go to \"Page 2\"", "Chrome would go to a new page"), t.left)
        // A picture that doesn't keep a panel would go back to the tray (its file is kept).
        val hung = home().let { n -> n.copy(pages = n.pages.map { if (it.id == "p2") it.copy(items = it.items + NotebookItem(cassette, ItemKind.Art, beats[2].cell)) else it }) }
        val other = board(Pad(hung))
        other.pick("p2")
        other.tryLayout("splash")
        assertEquals(listOf("Timeline would go to \"Page 1\"", "a picture would go back to the tray"), other.trial!!.left)
        // Moving things while trying is refused, and says why.
        assertFalse(b.place(pocket, "p1", 3))
        assertEquals(Storyboard.USE_OR_BACK, b.said!!.text)
        b.back()
        assertNull(b.trial)
        assertEquals("Kept the page as it was.", b.said!!.text)
        assertEquals(home(), pad.n)
        assertTrue(pad.requests.isEmpty())
    }

    @Test
    fun theOwnLayoutPutsTheTrialAwayAndPickingAnotherPageDoesToo() {
        val b = board(Pad(home()))
        b.tryLayout("grid6")
        assertNotNull(b.trial)
        b.tryLayout("opening")
        assertNull(b.trial)
        b.tryLayout("grid6")
        b.pick("p2")
        assertNull(b.trial)
        assertEquals("p2", b.page)
    }

    @Test
    fun aSwapOnOnePageIsOneVerbAndTradesPanels() {
        val pad = Pad(home())
        val b = board(pad)
        assertTrue(b.place(needs, "p1", 0))
        assertEquals("p1" to opening[0].cell, pad.n.at(needs))
        assertEquals("p1" to opening[1].cell, pad.n.at(skyline))
        assertEquals(1, pad.requests.size)
        assertTrue(b.said!!.text.startsWith("Swapped"))
    }

    @Test
    fun aSwapAcrossPagesTradesPagesAndCellsAsOneRestorePoint() {
        val pad = Pad(home())
        val b = board(pad)
        assertTrue(b.place(needs, "p2", 1))
        assertEquals("p2" to beats[1].cell, pad.n.at(needs))
        assertEquals("p1" to opening[1].cell, pad.n.at(timeline))
        assertEquals(listOf("p1", "p2"), pad.n.pages.map { it.id })
        assertEquals(1, pad.requests.toSet().size)
        assertTrue(pad.n.pages.all { inPanels(pad.n, it) })
        assertTrue(CanvasCommands.run(listOf("undo"), pad, "r-undo", "agent:front").optBoolean("ok"))
        assertEquals(shape(home()), shape(pad.n))
    }

    @Test
    fun aSwapAcrossFullPagesMakesNoPage() {
        // Every cell of both pages taken: Full splash on page 1 with the skyline, Three beats full on page 2.
        val full = Notebook(rows = rows, pages = listOf(
            NotebookPage("p1", "Page 1", listOf(NotebookItem(skyline, ItemKind.Art, PanelLayouts.panels("splash", 6, rows)[0].cell)), panels = "splash"),
            NotebookPage("p2", "Page 2", listOf(
                NotebookItem(threads, ItemKind.Module, beats[0].cell),
                NotebookItem(timeline, ItemKind.Module, beats[1].cell),
                NotebookItem(needs, ItemKind.Module, beats[2].cell),
            ), panels = "beats"),
        ))
        val pad = Pad(full)
        val b = board(pad)
        assertTrue(b.place(skyline, "p2", 2))
        assertEquals("p2" to beats[2].cell, pad.n.at(skyline))
        assertEquals("p1", pad.n.at(needs)!!.first)
        assertEquals(listOf("p1", "p2"), pad.n.pages.map { it.id })
    }

    @Test
    fun aSwapWithAnAppAcrossPagesNeverParksInThePanelItIsGoingTo() {
        // Home's pad shows an app as one cell of its panel, so the rest of that panel looks free: the swap must not wait there.
        val maps = PinnedApp("app:maps#0", "Maps", "com.google.android.apps.maps/.Main", 0)
        val splash = PanelLayouts.panels("splash", 6, rows)[0].cell
        val n = Notebook(rows = rows, pages = listOf(
            NotebookPage("p1", "Page 1", listOf(NotebookItem(maps.key, ItemKind.App, Cell(splash.x, splash.y, 1, 1), app = maps)), panels = "splash"),
            NotebookPage("p2", "Page 2", listOf(
                NotebookItem(chrome.key, ItemKind.App, Cell(beats[0].cell.x, beats[0].cell.y, 1, 1), app = chrome),
                NotebookItem(timeline, ItemKind.Module, beats[1].cell),
                NotebookItem(needs, ItemKind.Module, beats[2].cell),
            ), panels = "beats"),
        ))
        val pad = Pad(n)
        val b = board(pad)
        assertTrue(b.said!!.text, b.place(needs, "p1", 0))
        assertEquals("p1" to splash, pad.n.at(needs))
        assertEquals("p2" to beats[2].cell, pad.n.at(maps.key))
        assertEquals(listOf("p1", "p2"), pad.n.pages.map { it.id })
        assertEquals(1, pad.requests.toSet().size)
    }

    @Test
    fun aMoveIntoAnEmptyPanelAndSendingToAPage() {
        val pad = Pad(home())
        val b = board(pad)
        assertTrue(b.place(chrome.key, "p1", 4))
        assertEquals("p1" to opening[4].cell, pad.n.at(chrome.key))
        // Dropped on page 2 in the strip: its first empty panel.
        assertTrue(b.send(needs, "p2"))
        assertEquals("p2" to beats[2].cell, pad.n.at(needs))
        // Page 2 is full now: nothing changes, and it says what to do.
        val before = pad.n
        assertFalse(b.send(skyline, "p2"))
        assertEquals(before, pad.n)
        assertFalse(b.said!!.ok)
        assertTrue("no empty panel" in b.said!!.text)
    }

    @Test
    fun theTrayPutsInTakesOffAndSendsBackWhatWasThere() {
        val pad = Pad(home())
        val b = board(pad)
        // Picked in the tray, then a panel tapped.
        b.arm(pocket)
        assertEquals(pocket, b.armed)
        b.tapPanel(3)
        assertNull(b.armed)
        assertEquals("p1" to opening[3].cell, pad.n.at(pocket))
        // Onto a module's panel: the module goes back to the tray.
        assertTrue(b.place(cassette, "p1", 1))
        assertEquals("p1" to opening[1].cell, pad.n.at(cassette))
        assertNull(pad.n.at(needs))
        assertTrue("went back to the tray" in b.said!!.text)
        // Onto an app's panel: the app can't leave home, so it moves to the first empty panel.
        assertTrue(b.place(needs, "p1", 2))
        assertEquals("p1" to opening[2].cell, pad.n.at(needs))
        assertEquals("p1" to opening[4].cell, pad.n.at(chrome.key))
        // A panel dragged to the tray: a module or a picture comes off; an app stays, and says why.
        assertTrue(b.takeOff(cassette))
        assertNull(pad.n.at(cassette))
        assertFalse(b.takeOff(chrome.key))
        assertTrue(pad.n.at(chrome.key) != null)
        assertTrue("stays on home" in b.said!!.text)
    }

    @Test
    fun aTapOnAnEmptyPanelWithNothingPickedSaysHowToFillIt() {
        val pad = Pad(home())
        val b = board(pad)
        b.tapPanel(3)
        assertEquals(home(), pad.n)
        assertTrue("tap one in the tray" in b.said!!.text)
    }

    @Test
    fun theTrayListsModulesWithTheirPagesAndEveryPicture() {
        val pad = Pad(home())
        val b = board(pad)
        val widgets = b.chips(TrayTab.Widgets)
        assertEquals("p1", widgets.first { it.key == needs }.page)
        assertNull(widgets.first { it.key == pocket }.page)
        assertEquals("Chrome", widgets.first { it.key == chrome.key }.title)
        // The Layouts module is offered only where it was placed: the Storyboard is where layouts are now.
        assertTrue(widgets.none { it.key == layouts })
        val withTile = home().let { n -> n.copy(pages = n.pages.map { if (it.id == "p1") it.copy(items = it.items + NotebookItem(layouts, ItemKind.Module, opening[3].cell)) else it }) }
        assertEquals("p1", board(Pad(withTile)).chips(TrayTab.Widgets).first { it.key == layouts }.page)
        val art = b.chips(TrayTab.Art)
        assertEquals(setOf(skyline, cassette), art.map { it.key }.toSet())
        assertEquals("p1", art.first { it.key == skyline }.page)
        assertNull(art.first { it.key == cassette }.page)
    }

    @Test
    fun aPageAddedHereIsAFourKomaStripAndIsTheOneSetUp() {
        val pad = Pad(home())
        val b = board(pad)
        b.addPage()
        val made = pad.n.pages.last()
        assertEquals("Page 3", made.name)
        assertEquals("yonkoma", made.panels)
        assertEquals(made.id, b.page)
    }

    @Test
    fun savedLayoutsLoadOnTheSecondTapAndUndoPutsHomeBack() {
        val pad = Pad(home())
        val b = board(pad)
        b.save()
        assertEquals("Saved as \"Layout 1\".", b.said!!.text)
        assertEquals(listOf("Layout 1"), b.saved().map { it.name })
        // Home changes after saving.
        b.tryLayout("grid6")
        b.useTrial()
        b.place(pocket, "p1", 5)
        val changed = pad.n
        assertTrue(changed != home())
        // One tap asks; the second loads.
        b.tapSaved("Layout 1")
        assertEquals("Layout 1", b.confirm)
        assertEquals(changed, pad.n)
        b.tapSaved("Layout 1")
        assertNull(b.confirm)
        assertEquals(shape(home()), shape(pad.n))
        val undo = b.undoable!!
        assertTrue(pad.restorePoints().any { it.request == undo })
        // A hint or a refusal meanwhile keeps the Undo; it is offered until home changes here again.
        b.tapPanel(3)
        assertFalse(b.takeOff(chrome.key))
        assertEquals(undo, b.undoable)
        b.undo()
        assertEquals(shape(changed), shape(pad.n))
        assertEquals("Put back the layout from before.", b.said!!.text)
        // Undone, the Undo is gone: a second tap has nothing to take back.
        assertNull(b.undoable)
        val after = pad.n
        b.undo()
        assertEquals(after, pad.n)
    }

    @Test
    fun anotherChangeAfterALoadTakesItsUndoAwayAndSavedLayoutsJoinTheEnd() {
        val pad = Pad(home())
        val b = board(pad)
        b.save()
        clock += 10
        b.place(pocket, "p1", 3)
        b.save()
        assertEquals(listOf("Layout 1", "Layout 2"), b.saved().map { it.name })
        b.tapSaved("Layout 1")
        b.tapSaved("Layout 1")
        assertTrue(b.undoable != null)
        b.save()
        assertTrue("saving changes nothing on home, so the Undo stays", b.undoable != null)
        b.place(needs, "p1", 0)
        assertNull(b.undoable)
    }

    @Test
    fun aLoadThatStopsPartwaySaysSoToHimAndOffersItsUndo() {
        // A screen that won't take a page away (his notes on it): a load whose moves trade places stops at its page for the moment.
        val pad = Pad(home(), keeps = { true })
        val b = board(pad)
        b.save()
        b.place(needs, "p1", 0)
        val traded = pad.n
        b.tapSaved("Layout 1")
        assertTrue(b.said!!.text, b.said!!.text.startsWith("Tap Layout 1 again"))
        b.tapSaved("Layout 1")
        val said = b.said!!
        assertFalse(said.ok)
        assertTrue(said.text, "you can delete it in the overview" in said.text)
        assertFalse(said.text, "he removes" in said.text)
        val undo = b.undoable!!
        assertTrue(pad.restorePoints().any { it.request == undo })
        b.undo()
        assertEquals(shape(traded), shape(pad.n))
    }

    @Test
    fun undoingALoadThatTookAPageAwayBringsItBackCutAsItWas() {
        val pad = Pad(home())
        val b = board(pad)
        b.save()
        b.addPage()
        val added = pad.n.pages.last()
        assertEquals("yonkoma", added.panels)
        b.tapSaved("Layout 1")
        b.tapSaved("Layout 1")
        assertTrue(pad.n.pages.none { it.id == added.id })
        b.undo()
        assertEquals("yonkoma", pad.n.pages.first { it.id == added.id }.panels)
    }

    @Test
    fun everyPanelFullWithAnAppInTheWaySaysWhyInOneSentence() {
        val maps = PinnedApp("app:maps#0", "Maps", "com.google.android.apps.maps/.Main", 0)
        val splash = PanelLayouts.panels("splash", 6, rows)[0].cell
        val pad = Pad(Notebook(rows = rows, pages = listOf(NotebookPage("p1", "Page 1", listOf(NotebookItem(maps.key, ItemKind.App, splash, app = maps)), panels = "splash"))))
        val b = board(pad)
        assertFalse(b.place(pocket, "p1", 0))
        assertEquals("Every panel is full, and Maps can't go back to the tray (it is an app). Empty a panel first, or try another layout.", b.said!!.text)
    }

    @Test
    fun nothingHappensWhileHomeIsntOpen() {
        val b = Storyboard({ null }, shelf)
        b.open(null)
        assertNull(b.page)
        assertFalse(b.place(pocket, "p1", 0))
        assertFalse(b.said!!.ok)
        assertTrue(b.chips(TrayTab.Widgets).isEmpty())
    }
}
