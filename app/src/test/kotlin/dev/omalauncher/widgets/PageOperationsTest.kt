package dev.omalauncher.widgets

import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.firstFit
import dev.omalauncher.widgets.layout.shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Adding, renaming, reordering and deleting pages (PRD AC-15, UP-5): nothing on a page is lost except by a remove that was asked for. */
class PageOperationsTest {
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val mail = PinnedApp("app:mail#10", "Mail", "com.mail/.Main", 10)
    private val maps = PinnedApp("app:maps#0", "Maps", "com.maps/.Main", 0)
    private val camera = PinnedApp("app:camera#0", "Camera", "com.camera/.Main")

    private fun widget(
        key: String,
        cell: Cell?,
    ) = NotebookItem(key, ItemKind.Widget, cell)

    private fun app(
        app: PinnedApp,
        cell: Cell?,
    ) = NotebookItem(app.key, ItemKind.App, cell, app = app)

    /** Three pages: Today with room left, Trip with a widget, an app, a folder and one not placed yet, and a page that is nearly full. */
    private val saved =
        Notebook(
            rows = 9,
            pages =
                listOf(
                    NotebookPage("p1", "Today", listOf(widget("clock.owl", Cell(0, 0, 6, 2)), app(chrome, Cell(0, 2, 1, 1)))),
                    NotebookPage(
                        "p2",
                        "Trip",
                        listOf(
                            widget("gone.owl", Cell(0, 4, 3, 3)),
                            widget("map.owl", Cell(0, 0, 6, 4)),
                            widget("new.owl", null),
                            app(maps, Cell(5, 8, 1, 1)),
                            NotebookItem("folder:a", ItemKind.Folder, Cell(0, 8, 1, 1), apps = listOf(mail, camera)),
                        ),
                    ),
                    NotebookPage("p3", "Wall", listOf(widget("wall.owl", Cell(0, 0, 6, 8)))),
                ),
        )

    /** The widget files there are: gone.owl's is missing, so its entry only holds its place. */
    private val files = setOf("clock.owl", "map.owl", "new.owl", "wall.owl")

    private val writes = ArrayList<String>()

    private fun live(
        notebook: Notebook = saved,
        kept: Boolean = true,
    ) = LiveNotebook(persist = { writes += it }, now = { 1000 }).also { it.open(notebook, kept) }

    private fun reopened(): LiveNotebook = live(NotebookStore.read(writes.last())!!)

    /** Every key in the notebook, an app in a folder too, as often as it is there. */
    private fun keys(notebook: Notebook) = notebook.pages.flatMap { page -> page.items.flatMap { item -> listOf(item.key) + item.apps.map { it.key } } }.sorted()

    private fun ids(live: LiveNotebook) = live.pages.map { it.id }

    @Test fun aPageAddedIsCalledByTheNextFreeNumber() {
        val live = live()
        assertEquals("Page 4", live.name(live.addPage()!!))
        assertEquals("Page 5", live.name(live.addPage()!!))
        // A number someone already used for a name is passed over, also once the pages were shuffled.
        assertTrue(live.renamePage("p1", "Page 6"))
        live.movePage("p1", 1)
        assertEquals("Page 7", live.name(live.addPage()!!))
        assertEquals(listOf("p2", "p1", "p3", "p4", "p5", "p6"), ids(live))
        // One write for each.
        assertEquals(5, writes.size)
        assertEquals(live.snapshot(), reopened().snapshot())
    }

    @Test fun renamingKeepsThePageAndTidiesTheName() {
        val live = live()
        val before = live.snapshot()
        assertTrue(live.renamePage("p2", "  Trip   to\tLisbon \n"))
        assertEquals("Trip to Lisbon", live.name("p2"))
        assertEquals(1, writes.size)
        // The id, the order and everything on the page are as they were.
        assertEquals(before.copy(pages = before.pages.map { if (it.id == "p2") it.copy(name = "Trip to Lisbon") else it }), live.snapshot())
        assertEquals("Trip to Lisbon", reopened().name("p2"))
        // The same name again changes nothing and writes nothing.
        assertTrue(live.renamePage("p2", "Trip to Lisbon"))
        assertEquals(1, writes.size)
    }

    @Test fun aNameIsNeverEmptyNorLongerThanTheLimit() {
        val live = live()
        for (blank in listOf("", " ", "\t\n  ")) assertFalse(live.renamePage("p2", blank))
        assertEquals("Trip", live.name("p2"))
        assertFalse(live.renamePage("nowhere", "Name"))
        assertTrue(writes.isEmpty())
        assertTrue(live.renamePage("p2", "x".repeat(200)))
        assertEquals("x".repeat(PAGE_NAME_MAX), live.name("p2"))
        // Exactly at the limit it is kept whole, and a cut never ends on half a character or on a space.
        assertEquals("y".repeat(PAGE_NAME_MAX), pageName("y".repeat(PAGE_NAME_MAX)))
        assertEquals("z".repeat(PAGE_NAME_MAX - 1), pageName("z".repeat(PAGE_NAME_MAX - 1) + "😀"))
        assertEquals("w".repeat(PAGE_NAME_MAX - 1), pageName("w".repeat(PAGE_NAME_MAX - 1) + " and more"))
        assertEquals("a 😀", pageName(" a 😀 "))
        assertNull(pageName("   "))
        // Asking Oma for a page by name still reaches the longest name there can be.
        assertTrue(PAGE_NAME_MAX <= dev.omalauncher.phone.MAX_PAGE_QUERY)
    }

    @Test fun twoPagesMayShareANameAndWhoeverNamesOneIsTold() {
        val live = live()
        assertFalse(live.nameTaken("Journal"))
        assertTrue(live.nameTaken("today"))
        assertTrue(live.nameTaken("TODAY", except = "p2"))
        // Its own name is not someone else's.
        assertFalse(live.nameTaken("Today", except = "p1"))
        assertTrue(live.renamePage("p2", "Today"))
        assertEquals(listOf("Today", "Today", "Wall"), live.pages.map { it.name })
        assertEquals(listOf("p1", "p2", "p3"), ids(live))
    }

    @Test fun aPageMovesOnePlaceAndStopsAtEitherEnd() {
        val live = live()
        assertFalse(live.movePage("p1", -1))
        assertFalse(live.movePage("p3", 1))
        assertFalse(live.movePage("p2", 0))
        assertFalse(live.movePage("p2", 5))
        assertFalse(live.movePage("nowhere", 1))
        assertTrue(writes.isEmpty())
        assertEquals(listOf("p1", "p2", "p3"), ids(live))

        assertTrue(live.movePage("p3", -1))
        assertEquals(listOf("p1", "p3", "p2"), ids(live))
        assertTrue(live.movePage("p1", 1))
        assertEquals(listOf("p3", "p1", "p2"), ids(live))
        assertEquals(2, writes.size)
        // Each page kept its name and what is on it, in the same cells: only the order changed.
        assertEquals(saved.pages.associateBy { it.id }, live.snapshot().pages.associateBy { it.id })
        assertEquals(listOf("Wall", "Today", "Trip"), reopened().pages.map { it.name })
        assertEquals(live.snapshot(), reopened().snapshot())
    }

    @Test fun deletingAPageMovesWhatIsOnItToThePageBefore() {
        val live = live()
        val gone = live.deletePage("p2", files, move = true)!!
        assertEquals(listOf("p1", "p3"), ids(live))
        assertEquals("p1", gone.to)
        assertEquals(setOf("map.owl", "new.owl", maps.key, "folder:a"), gone.moved.toSet())
        assertEquals(emptyList<String>(), gone.spilled)
        assertNull(gone.spill)
        assertEquals(emptyList<String>(), gone.removed)
        // Each in the first free cells at its own size, top to bottom; what was on the page before keeps its place.
        assertEquals(Cell(0, 0, 6, 2), live.cell("clock.owl"))
        assertEquals(Cell(0, 2, 1, 1), live.cell(chrome.key))
        assertEquals(Cell(0, 3, 6, 4), live.cell("map.owl"))
        assertEquals(Cell(1, 2, 1, 1), live.cell("folder:a"))
        assertEquals(Cell(2, 2, 1, 1), live.cell(maps.key))
        // One not placed yet goes along without cells: home places it when it lays the page out.
        assertEquals("p1", live.pageOf("new.owl"))
        assertNull(live.cell("new.owl"))
        // The folder went whole.
        assertEquals("p1", live.pageOf(mail.key))
        assertEquals(listOf(mail, camera), live.snapshot().pages[0].items.single { it.key == "folder:a" }.apps)
        // The widget whose file is missing only held a place on the page, and goes with it.
        assertEquals(listOf("gone.owl"), gone.dropped)
        assertNull(live.pageOf("gone.owl"))
        assertNull(live.cell("gone.owl"))
        assertEquals(keys(saved) - "gone.owl", keys(live.snapshot()))
        assertEquals(1, writes.size)
        assertEquals(live.snapshot(), reopened().snapshot())
    }

    @Test fun deletingTheFirstPageMovesItsThingsToTheNext() {
        val live = live(saved.copy(pages = listOf(saved.pages[0], NotebookPage("p2", "Empty"), saved.pages[2])))
        val gone = live.deletePage("p1", files, move = true)!!
        assertEquals("p2", gone.to)
        assertEquals(listOf("p2", "p3"), ids(live))
        assertEquals("p2", live.pageOf("clock.owl"))
        assertEquals("p2", live.pageOf(chrome.key))
        assertEquals(Cell(0, 0, 6, 2), live.cell("clock.owl"))
        assertEquals(Cell(0, 2, 1, 1), live.cell(chrome.key))
    }

    @Test fun whatDoesNotFitOnTheNeighbourGetsANewPageInsteadOfBeingDropped() {
        // Trip stands after the wall here, so the wall is where its things would go.
        val live = live(saved.copy(pages = listOf(saved.pages[2], saved.pages[1], saved.pages[0])))
        val gone = live.deletePage("p2", files, move = true)!!
        assertEquals("p3", gone.to)
        // The wall leaves one row: the apps and the folder fit there, the map does not.
        assertEquals(setOf(maps.key, "folder:a", "new.owl"), gone.moved.toSet())
        assertEquals(listOf("map.owl"), gone.spilled)
        assertEquals(Cell(0, 8, 1, 1), live.cell("folder:a"))
        assertEquals(Cell(1, 8, 1, 1), live.cell(maps.key))
        // A new page stands where the deleted one was, with an id no page had, and the map in its own cells.
        val spill = gone.spill!!
        assertEquals(listOf("p3", spill, "p1"), ids(live))
        assertEquals("p4", spill)
        assertEquals("Page 3", live.name(spill))
        assertEquals(spill, live.pageOf("map.owl"))
        assertEquals(Cell(0, 0, 6, 4), live.cell("map.owl"))
        assertEquals(keys(saved) - "gone.owl", keys(live.snapshot()))
        assertEquals(1, writes.size)
        assertEquals(live.snapshot(), reopened().snapshot())
    }

    @Test fun aSavedCellTooBigForTheGridSpillsRatherThanVanishes() {
        val tall = Notebook(9, listOf(NotebookPage("p1", "One"), NotebookPage("p2", "Two", listOf(widget("tall.owl", Cell(0, 0, 2, 12))))))
        val live = live(tall)
        val gone = live.deletePage("p2", setOf("tall.owl"), move = true)!!
        assertEquals(listOf("tall.owl"), gone.spilled)
        assertEquals(gone.spill, live.pageOf("tall.owl"))
        assertEquals(Cell(0, 0, 2, 12), live.cell("tall.owl"))
        assertEquals(2, live.pages.size)
    }

    @Test fun deletingWithRemoveTakesItsThingsOffHome() {
        val live = live()
        val gone = live.deletePage("p2", files, move = false)!!
        assertEquals(listOf("p1", "p3"), ids(live))
        assertNull(gone.to)
        assertEquals(emptyList<String>(), gone.moved)
        assertEquals(setOf("map.owl", "new.owl", maps.key, "folder:a"), gone.removed.toSet())
        assertEquals(listOf("gone.owl"), gone.dropped)
        // The apps are unpinned, those in the folder with it; nothing else was touched.
        assertEquals(listOf(chrome.key), live.apps.map { it.app.key })
        assertTrue(live.folders.isEmpty())
        for (key in listOf("map.owl", "new.owl", "gone.owl", maps.key, "folder:a", mail.key, camera.key)) {
            assertNull(key, live.pageOf(key))
            assertNull(key, live.cell(key))
        }
        assertEquals(saved.pages[0], live.snapshot().pages[0])
        assertEquals(saved.pages[2], live.snapshot().pages[1])
        assertEquals(1, writes.size)
        assertEquals(live.snapshot(), reopened().snapshot())
    }

    @Test fun anEmptyPageIsDeletedEitherWay() {
        for (move in listOf(true, false)) {
            val live = live(saved.copy(pages = saved.pages + NotebookPage("p9", "Spare")))
            val gone = live.deletePage("p9", files, move)!!
            assertEquals(PageGone(if (move) "p3" else null, emptyList(), null, emptyList(), emptyList(), emptyList()), gone)
            assertEquals(saved, live.snapshot())
        }
    }

    @Test fun theLastPageIsNeverDeleted() {
        val one = Notebook(9, listOf(NotebookPage("p1", "Only", listOf(widget("clock.owl", Cell(0, 0, 2, 2))))))
        val live = live(one)
        assertFalse(live.canDelete("p1"))
        assertNull(live.deletePage("p1", files, move = true))
        assertNull(live.deletePage("p1", files, move = false))
        assertEquals(one, live.snapshot())
        // Nor a page that isn't there.
        val three = live()
        assertFalse(three.canDelete("nowhere"))
        assertNull(three.deletePage("nowhere", files, move = false))
        assertEquals(saved, three.snapshot())
        assertTrue(writes.isEmpty())
        // Down to one, the rest is refused.
        assertNotNull(three.deletePage("p1", files, move = false))
        assertNotNull(three.deletePage("p2", files, move = false))
        assertNull(three.deletePage(three.pages.single().id, files, move = true))
        assertEquals(listOf(saved.pages[2]), three.snapshot().pages)
    }

    @Test fun nothingIsDoneBeforeTheNotebookIsOpened() {
        val closed = LiveNotebook(persist = { writes += it })
        assertFalse(closed.renamePage("p1", "Mine"))
        assertFalse(closed.movePage("p1", 1))
        assertFalse(closed.canDelete("p1"))
        assertNull(closed.deletePage("p1", files, move = true))
        assertNull(closed.deletePage("p1", files, move = false))
        assertEquals(listOf(PageLabel("p1", "Page 1")), closed.pages.toList())
        assertTrue(writes.isEmpty())
    }

    @Test fun inAnUnsavedSessionEverythingWorksAndNothingIsWritten() {
        val unkept = live(kept = false)
        assertTrue(unkept.renamePage("p1", "Now"))
        assertTrue(unkept.movePage("p1", 1))
        assertNotNull(unkept.addPage())
        assertNotNull(unkept.deletePage("p2", files, move = true))
        assertEquals(listOf("p1", "p3", "p4"), ids(unkept))
        assertEquals("Now", unkept.name("p1"))
        assertTrue(writes.isEmpty())
    }

    /**
     * Removing moves widget files aside for good. In a session that isn't written the store would still list
     * them at the next start, with their files gone: so there it is refused, and moving is what there is.
     */
    @Test fun nothingIsRemovedInAnUnsavedSession() {
        val unkept = live(kept = false)
        val before = unkept.snapshot()
        assertTrue(unkept.canDelete("p2"))
        assertNull(unkept.deletePage("p2", files, move = false))
        // An empty page too: the answer is the session's, not the page's.
        val spare = unkept.addPage()!!
        assertNull(unkept.deletePage(spare, files, move = false))
        assertTrue(unkept.withdrawPage(spare))
        assertEquals(before, unkept.snapshot())
        assertTrue(writes.isEmpty())
        // Once the session is kept, removing is there again.
        unkept.keep()
        assertNotNull(unkept.deletePage("p2", files, move = false))
        assertEquals(2, writes.size)
    }

    @Test fun aNameHasNothingInItThatDoesNotShow() {
        // Marks that turn the writing direction round, zero-width ones, and control characters go.
        assertEquals("Trip", pageName("\u202ETrip\u202C"))
        assertEquals("Trip", pageName("\u200BT\u200Dr\u2066i\u2069p\uFEFF\u0000\u0007\u001B\u007F\u0085"))
        assertEquals("Trip", pageName("\u200E\u200FTrip\u061C"))
        // Every kind of blank space is one space, and none is left at the ends.
        assertEquals("Trip to Lisbon", pageName("\u00A0Trip\u2003\u3000to\u2028\u2029Lisbon\u1680\u202F"))
        assertEquals("a b", pageName("a \u200B b"))
        // What is only such characters is no name.
        for (nothing in listOf("\u202E\u202C", "\u00A0\u3000", "\u200B", "\u0000 \t")) assertNull(pageName(nothing))
        val live = live()
        assertFalse(live.renamePage("p2", "\u202E\u00A0\u200B"))
        assertTrue(live.renamePage("p2", "\u202Eevil\u202C\u00A0page"))
        assertEquals("evil page", live.name("p2"))
        // Letters of every script and marks on them stay as typed.
        assertEquals("旅行 – São Paulo ✈", pageName("旅行 – São Paulo ✈"))
    }

    @Test fun whatIsTypedOrPastedPastTheLimitIsCutOff() {
        assertEquals("abc", clipped("abc"))
        assertEquals("x".repeat(PAGE_NAME_MAX), clipped("x".repeat(PAGE_NAME_MAX)))
        assertEquals("x".repeat(PAGE_NAME_MAX), clipped("x".repeat(PAGE_NAME_MAX) + "y".repeat(500)))
        // Not through the middle of a character that takes two, and on one line; a space at the end stays while typing.
        assertEquals("x".repeat(PAGE_NAME_MAX - 1), clipped("x".repeat(PAGE_NAME_MAX - 1) + "😀"))
        assertEquals("a b c ", clipped("a\nb\rc "))
        assertEquals("", clipped(""))
    }

    /** Tiles as home shows them: no two on a page share a cell. */
    private fun overlaps(notebook: Notebook): List<String> =
        notebook.pages.flatMap { page ->
            val placed = page.items.mapNotNull { item -> item.cell?.shown(item.kind == ItemKind.Widget)?.let { item.key to it } }
            placed.flatMap { (a, ca) -> placed.filter { (b, cb) -> a < b && ca.overlaps(cb) }.map { (b) -> "${page.id}: $a over $b" } }
        }

    /** A notebook of a few pages, some full, some empty, with widgets, apps, folders, things not placed yet and missing files. */
    private fun scattered(random: Random): Pair<Notebook, Set<String>> {
        val rows = 9
        val present = HashSet<String>()
        var n = 0
        val pages =
            (1..random.nextInt(1, 7)).map { p ->
                val taken = ArrayList<Cell>()
                val items = ArrayList<NotebookItem>()
                repeat(random.nextInt(0, 9)) {
                    val key = "k${n++}"
                    val widget = random.nextInt(3) > 0
                    val (w, h) = if (widget) random.nextInt(1, 7) to random.nextInt(1, 6) else 1 to 1
                    val cell = if (random.nextInt(8) == 0) null else firstFit(w, h, taken, GRID_COLUMNS, rows)
                    if (cell != null) taken += cell
                    when {
                        widget -> {
                            // One in six has no file: its entry only holds its place.
                            if (random.nextInt(6) > 0) present += "$key.owl"
                            items += NotebookItem("$key.owl", ItemKind.Widget, cell)
                        }
                        random.nextBoolean() -> items += NotebookItem("app:$key", ItemKind.App, cell, app = PinnedApp("app:$key", key, "c/$key"))
                        else -> items += NotebookItem("folder:$key", ItemKind.Folder, cell, apps = (0..random.nextInt(1, 4)).map { PinnedApp("app:$key-$it", "$key $it", "c/$key$it") })
                    }
                }
                NotebookPage("p$p", "Page $p", items)
            }
        return Notebook(rows, pages) to present
    }

    /**
     * No sequence of page operations loses anything: what is in the notebook only ever changes by a remove that
     * was asked for, and by the place-holders of missing files going with their page.
     */
    @Test fun noSequenceOfPageOperationsLosesAnItem() {
        val random = Random(20261012)
        var operations = 0
        repeat(3000) { round ->
            writes.clear()
            val (start, present) = scattered(random)
            val live = live(start)
            assertEquals("round $round", emptyList<String>(), overlaps(live.snapshot()))
            repeat(random.nextInt(1, 14)) { step ->
                val before = live.snapshot()
                val written = writes.size
                val pageIds = before.pages.map { it.id }
                // Now and then a page that isn't there.
                val page = if (random.nextInt(12) == 0) "nowhere" else pageIds.random(random)
                val at = pageIds.indexOf(page)
                val on = before.pages.getOrNull(at)?.items.orEmpty()
                val what = "round $round step $step on $page of $pageIds"
                var expected = keys(before)
                var changed = true
                when (random.nextInt(7)) {
                    0 -> {
                        val id = live.addPage()!!
                        assertEquals(what, pageIds + id, ids(live))
                        assertFalse(what, id in pageIds)
                    }
                    1 -> {
                        val name = listOf("Trip", "  today ", "", "   ", "Page 2", "n".repeat(90)).random(random)
                        changed = live.renamePage(page, name) && live.name(page) != before.pages[at].name
                        assertEquals(what, pageIds, ids(live))
                        assertTrue(what, live.pages.all { it.name.isNotBlank() && it.name.length <= PAGE_NAME_MAX })
                    }
                    2, 3 -> {
                        val by = if (random.nextBoolean()) 1 else -1
                        changed = live.movePage(page, by)
                        assertEquals(what, changed, at >= 0 && at + by in pageIds.indices)
                        assertEquals(what, pageIds.sorted(), ids(live).sorted())
                        // Whatever the order, each page is what it was.
                        assertEquals(what, before.pages.associateBy { it.id }, live.snapshot().pages.associateBy { it.id })
                    }
                    4, 5 -> {
                        val gone = live.deletePage(page, present, move = true)
                        changed = gone != null
                        assertEquals(what, at >= 0 && pageIds.size > 1, changed)
                        if (gone != null) {
                            val held = on.filter { it.kind == ItemKind.Widget && it.key !in present }.map { it.key }
                            assertEquals(what, held.sorted(), gone.dropped.sorted())
                            assertEquals(what, (on.map { it.key } - held.toSet()).sorted(), (gone.moved + gone.spilled).sorted())
                            assertEquals(what, emptyList<String>(), gone.removed)
                            expected = expected - held.toSet()
                            assertFalse(what, page in ids(live))
                            assertEquals(what, pageIds - page, ids(live) - listOfNotNull(gone.spill))
                            assertEquals(what, gone.spill != null, gone.spilled.isNotEmpty())
                            if (gone.spill != null) assertFalse(what, gone.spill in pageIds)
                        }
                    }
                    else -> {
                        val gone = live.deletePage(page, present, move = false)
                        changed = gone != null
                        assertEquals(what, at >= 0 && pageIds.size > 1, changed)
                        if (gone != null) {
                            // Exactly what was on that page is gone, and nothing from anywhere else.
                            expected = expected - on.flatMap { item -> listOf(item.key) + item.apps.map { it.key } }.toSet()
                            assertEquals(what, on.map { it.key }.sorted(), (gone.removed + gone.dropped).sorted())
                            assertEquals(what, pageIds - page, ids(live))
                        }
                    }
                }
                operations++
                val after = live.snapshot()
                assertEquals(what, expected, keys(after))
                // Nothing is there twice, nothing sits on another tile, and there is always a page.
                assertEquals(what, keys(after).distinct(), keys(after))
                assertEquals(what, emptyList<String>(), overlaps(after))
                assertTrue(what, after.pages.isNotEmpty())
                assertEquals(what, ids(live).distinct(), ids(live))
                // Written once if anything changed and not at all otherwise, and what was written is what is shown.
                assertEquals(what, if (changed) written + 1 else written, writes.size)
                if (!changed) assertEquals(what, before, after)
                if (changed) assertEquals(what, after, NotebookStore.read(writes.last()))
            }
        }
        assertTrue(operations > 15000)
    }
}
