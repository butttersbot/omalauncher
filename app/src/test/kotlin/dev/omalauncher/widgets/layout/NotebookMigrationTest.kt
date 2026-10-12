package dev.omalauncher.widgets.layout

import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.widgets.Cell
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

private const val CHROME = "app:com.android.chrome/com.google.android.apps.chrome.Main#0"
private const val CAMERA = "app:com.sec.android.app.camera/com.sec.android.app.camera.Camera#0"
private const val GMAIL = "app:com.google.android.gm/com.google.android.gm.ConversationListActivityGmail#0"
private const val WORK_GMAIL = "app:com.google.android.gm/com.google.android.gm.ConversationListActivityGmail#10"

/** What every migration must hold to, whatever it was given; returns the result for the case's own checks. */
internal fun checked(
    old: OldStores,
    widgets: List<OldWidget>,
): Migration {
    val m = migrate(old, widgets)
    val notebook = m.notebook
    val report = m.report
    assertTrue("rows ${notebook.rows}", notebook.rows in NOTEBOOK_MIN_ROWS..NOTEBOOK_MAX_ROWS)
    assertEquals(notebook.rows, report.rows)
    assertEquals(6, notebook.columns)
    assertTrue(notebook.dock.isEmpty())
    assertTrue(notebook.pages.isNotEmpty())
    assertEquals(notebook.pages.indices.map { "Page ${it + 1}" }, notebook.pages.map { it.name })
    assertEquals(notebook.pages.size, notebook.pages.map { it.id }.toSet().size)
    assertEquals(notebook.pages.size, report.pageIds.size + report.pagesAdded)
    assertTrue(notebook.pages.map { it.id }.containsAll(report.pageIds.values))
    // Old pages keep their order.
    assertEquals(report.pageIds.keys.sorted().map { report.pageIds[it] }, notebook.pages.map { it.id }.filter { it in report.pageIds.values })

    val items = notebook.pages.flatMap { it.items }
    assertEquals("an item is on the grid twice", items.size, items.map { it.key }.toSet().size)
    assertEquals(report.items, items.size)
    val movedKeys = report.moved.map { it.key }
    assertEquals(movedKeys.size, movedKeys.toSet().size)
    assertEquals(report.moved.size, report.replaced + report.placed)
    assertEquals(report.replaced, report.moved.count { it.from != null })
    for (page in notebook.pages) {
        for (item in page.items) {
            assertNotNull("${item.key} has no cell", item.cell)
            val c = item.cell!!
            assertTrue("${item.key} at $c is off a 6 x ${notebook.rows} page", c.x >= 0 && c.y >= 0 && c.w >= 1 && c.h >= 1 && c.right <= 6 && c.bottom <= notebook.rows)
            if (item.kind != ItemKind.Widget) assertEquals("${item.key} is an icon", 1 to 1, c.w to c.h)
            assertEquals(item.kind == ItemKind.App, item.app != null)
            if (item.kind != ItemKind.Folder) assertTrue(item.apps.isEmpty())
            // What was given a cell here never lands on another tile.
            val move = report.moved.firstOrNull { it.key == item.key }
            if (move != null) {
                assertEquals(page.id, move.page)
                assertEquals(c, move.to)
                // Only a cell pulled in from over the side, where home drew it already, may lie over another.
                val pulledIn =
                    move.from?.shown(item.kind == ItemKind.Widget)?.let { s ->
                        val w = s.w.coerceIn(1, 6)
                        !move.newPage && c == Cell(s.x.coerceIn(0, 6 - w), s.y.coerceAtLeast(0), w, s.h.coerceAtLeast(1))
                    } == true
                if (!pulledIn) for (other in page.items) if (other.key != item.key) assertFalse("${item.key} was put on ${other.key}", other.cell!!.overlaps(c))
            }
        }
    }
    // Saved as the notebook store and read back, it is the same.
    assertEquals(notebook, NotebookStore.read(NotebookStore.write(notebook)))
    // Done again from the same old stores, it comes out the same.
    val again = migrate(old, widgets)
    assertEquals(notebook, again.notebook)
    assertEquals(report, again.report)
    assertEquals(NotebookStore.write(notebook), NotebookStore.write(again.notebook))
    return m
}

class NotebookMigrationTest {
    private fun fixture(name: String): Migration {
        val dir = File("src/test/resources/migration", name)
        assertTrue("no fixture $dir", File(dir, "widget_grid.xml").exists())
        return checked(readStores(dir), readWidgets(File(dir, "widgets.txt")))
    }

    private fun Migration.cells(page: Int) = notebook.pages[page].items.map { it.key to it.cell }

    /** Keys of everything in the notebook, the apps inside folders too. */
    private fun Migration.everything() = notebook.pages.flatMap { p -> p.items.flatMap { listOf(it.key) + it.apps.map { a -> a.key } } }.sorted()

    @Test fun aNormalLayoutMovesNothing() {
        val m = fixture("normal")
        assertEquals(9, m.notebook.rows)
        assertEquals(listOf("p1" to "Page 1", "p2" to "Page 2"), m.notebook.pages.map { it.id to it.name })
        assertEquals(
            listOf(
                "clock.owl" to Cell(0, 0, 3, 2),
                "weather.owl" to Cell(3, 0, 3, 2),
                CHROME to Cell(0, 2, 1, 1),
                // Saved 2x2 by an old launcher; an app shows as 1x1 and is saved so.
                CAMERA to Cell(1, 2, 1, 1),
                "folder:m1abc" to Cell(2, 2, 1, 1),
            ),
            m.cells(0),
        )
        assertEquals(
            listOf(
                // Its file says page 1; it was moved to page 2 on the phone.
                "agent-claude.owl" to Cell(0, 0, 6, 5),
                "notes.owl" to Cell(0, 5, 3, 3),
                "linux:firefox" to Cell(3, 5, 1, 1),
                "linux:htop" to Cell(4, 5, 1, 1),
            ),
            m.cells(1),
        )
        assertEquals(listOf("clock.owl", "weather.owl", "agent-claude.owl", "notes.owl", CHROME, CAMERA, "linux:firefox", "linux:htop", "folder:m1abc", GMAIL, WORK_GMAIL).sorted(), m.everything())
        val r = m.report
        assertTrue(r.summary(), r.clean)
        assertEquals(emptyList<Moved>(), r.moved)
        assertEquals(0, r.pagesAdded)
        assertEquals(0, r.overlaps)
        assertEquals(8, r.tallest)
        assertEquals(9, r.items)
        assertEquals(emptyList<String>(), r.orphans)
        assertEquals(mapOf(1 to "p1", 2 to "p2"), r.pageIds)
        assertEquals(mapOf("1248x1972" to 9, "1848x2448" to 7), r.portraitRows)
    }

    @Test fun appsCarryWhatTheyNeedToOpen() {
        val items = fixture("normal").notebook.pages.flatMap { it.items }.associateBy { it.key }
        assertEquals(ItemKind.Widget, items.getValue("clock.owl").kind)
        assertEquals(PinnedApp(CHROME, "Chrome", "com.android.chrome/com.google.android.apps.chrome.Main", 0), items.getValue(CHROME).app)
        assertEquals(
            PinnedApp("linux:firefox", "Firefox", linux = LinuxEntry("Firefox", "firefox %u", false, "firefox", "firefox", "firefox")),
            items.getValue("linux:firefox").app,
        )
        assertEquals(PinnedApp("linux:htop", "htop", linux = LinuxEntry("htop", "htop", true, "htop")), items.getValue("linux:htop").app)
        // A folder holds its apps in their order, the work profile's copy told apart by its profile.
        val folder = items.getValue("folder:m1abc")
        assertEquals(ItemKind.Folder, folder.kind)
        assertEquals(listOf(GMAIL to 0L, WORK_GMAIL to 10L), folder.apps.map { it.key to it.profile })
    }

    @Test fun sparsePagesCloseUp() {
        val m = fixture("sparse")
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), m.notebook.pages.map { it.name })
        assertEquals(mapOf(1 to "p1", 3 to "p2", 7 to "p3"), m.report.pageIds)
        assertEquals(listOf("a.owl" to Cell(0, 0, 3, 3)), m.cells(0))
        assertEquals(listOf("b.owl" to Cell(0, 0, 6, 4)), m.cells(1))
        assertEquals(listOf("c.owl" to Cell(2, 2, 2, 2), CHROME to Cell(5, 8, 1, 1)), m.cells(2))
        assertEquals(9, m.notebook.rows)
        assertTrue(m.report.clean)
        assertEquals(emptyList<Moved>(), m.report.moved)
    }

    @Test fun aPageTallerThanNineRowsSetsTheRows() {
        val m = fixture("tall")
        assertEquals(11, m.notebook.rows)
        assertEquals(11, m.report.tallest)
        // An app's old 3x3 cell reaches as far as its top-left cell: row 11, not 13.
        assertEquals(listOf("a.owl" to Cell(0, 0, 6, 8), "b.owl" to Cell(0, 8, 3, 3), CHROME to Cell(5, 10, 1, 1)), m.cells(0))
        assertEquals(emptyList<Moved>(), m.report.moved)
        assertEquals(mapOf("1080x2340" to 11), m.report.portraitRows)
    }

    @Test fun aPageTallerThanTwelveRowsIsCutThere() {
        val m = fixture("too-tall")
        assertEquals(12, m.notebook.rows)
        assertEquals(14, m.report.tallest)
        // What reached below row 12 takes the first free spot on its page...
        assertEquals(listOf("a.owl" to Cell(0, 0, 6, 9), "c.owl" to Cell(0, 9, 3, 2), CHROME to Cell(3, 9, 1, 1)), m.cells(0))
        // ...or, with no room for it there, a new page right after, before what was page 2.
        assertEquals(listOf("b.owl" to Cell(0, 0, 6, 5)), m.cells(1))
        assertEquals(listOf("d.owl" to Cell(0, 0, 2, 2)), m.cells(2))
        assertEquals(mapOf(1 to "p1", 2 to "p3"), m.report.pageIds)
        assertEquals(1, m.report.pagesAdded)
        assertEquals(3, m.report.replaced)
        assertEquals(0, m.report.placed)
        assertEquals(
            listOf(
                Moved("b.owl", Cell(0, 9, 6, 5), Cell(0, 0, 6, 5), "p2", true),
                Moved("c.owl", Cell(0, 12, 3, 2), Cell(0, 9, 3, 2), "p1", false),
                Moved(CHROME, Cell(4, 13, 1, 1), Cell(3, 9, 1, 1), "p1", false),
            ),
            m.report.moved,
        )
        assertTrue(m.report.clean)
    }

    @Test fun itemsWithoutACellGetTheFirstFreeOne() {
        val m = fixture("no-cell")
        assertEquals(
            listOf(
                "a.owl" to Cell(0, 0, 3, 3),
                "sized.owl" to Cell(3, 0, 2, 2),
                // No declared size: half the width by three.
                "plain.owl" to Cell(3, 2, 3, 3),
                CHROME to Cell(5, 0, 1, 1),
            ),
            m.cells(0),
        )
        // Bigger than a page: cut to it, and with no room beside the others, on a page of its own.
        assertEquals(listOf("huge.owl" to Cell(0, 0, 6, 9)), m.cells(1))
        assertEquals(listOf("full.owl" to Cell(0, 0, 6, 9)), m.cells(2))
        assertEquals(listOf(CAMERA to Cell(0, 0, 1, 1)), m.cells(3))
        assertEquals(mapOf(1 to "p1", 2 to "p3"), m.report.pageIds)
        assertEquals(2, m.report.pagesAdded)
        assertEquals(5, m.report.placed)
        assertEquals(0, m.report.replaced)
        assertEquals(listOf("huge.owl", CAMERA), m.report.moved.filter { it.newPage }.map { it.key })
        assertTrue(m.report.clean)
    }

    @Test fun overlappingCellsStayAndNewcomersAvoidThem() {
        val m = fixture("overlap")
        assertEquals(
            listOf("a.owl" to Cell(0, 0, 4, 3), "b.owl" to Cell(2, 1, 4, 3), "new.owl" to Cell(0, 4, 3, 2), CHROME to Cell(0, 0, 1, 1)),
            m.cells(0),
        )
        // a with b, and the app on a.
        assertEquals(2, m.report.overlaps)
        assertEquals(listOf("new.owl"), m.report.moved.map { it.key })
        assertTrue(m.report.clean)
    }

    @Test fun aCorruptLayoutLosesTheCellsButNoItem() {
        val m = fixture("corrupt-layout")
        assertFalse(m.report.clean)
        assertEquals(1, m.report.problems.size)
        assertTrue(m.report.problems[0], "widget_grid: layout" in m.report.problems[0])
        // Everything is laid out afresh, as on a first start.
        assertEquals(
            listOf("clock.owl" to Cell(0, 0, 3, 2), "weather.owl" to Cell(3, 0, 3, 2), CHROME to Cell(0, 2, 1, 1), CAMERA to Cell(1, 2, 1, 1), "folder:m1abc" to Cell(2, 2, 1, 1)),
            m.cells(0),
        )
        assertEquals(listOf("agent-claude.owl" to Cell(0, 0, 6, 5)), m.cells(1))
        assertEquals(listOf("clock.owl", "weather.owl", "agent-claude.owl", CHROME, CAMERA, "folder:m1abc", GMAIL, WORK_GMAIL).sorted(), m.everything())
        assertEquals(6, m.report.placed)
        assertEquals(mapOf("1248x1972" to 9), m.report.portraitRows)
    }

    @Test fun partlyReadableStoresConvertWhatTheyCan() {
        val m = fixture("partly")
        assertFalse(m.report.clean)
        assertEquals(
            listOf(
                "2 cells of widget_grid: layout can't be read",
                "2 pinned apps (shortcuts: items) can't be read",
                "the folders (shortcuts: folders) can't be read",
            ),
            m.report.problems,
        )
        assertEquals(
            listOf(
                "clock.owl" to Cell(0, 0, 3, 2),
                // Their cells couldn't be read: placed as if new.
                "weather.owl" to Cell(3, 0, 3, 2),
                "notes.owl" to Cell(0, 2, 3, 3),
                CHROME to Cell(0, 5, 1, 1),
                // Its folder is gone with the folders: back on the grid on its own.
                CAMERA to Cell(3, 2, 1, 1),
            ),
            m.cells(0),
        )
        assertEquals(3, m.report.placed)
    }

    @Test fun anEmptyInstallIsOneEmptyPage() {
        val m = fixture("empty")
        assertEquals(Notebook(9, listOf(NotebookPage("p1", "Page 1"))), m.notebook)
        assertTrue(m.report.clean)
        assertEquals(0, m.report.items)
        assertEquals(mapOf(1 to "p1"), m.report.pageIds)
        assertEquals(m.notebook, checked(OldStores(), emptyList()).notebook)
    }

    @Test fun whatOlderLaunchersLeftIsReadAsTheLauncherReadsIt() {
        val m = fixture("legacy")
        // Page choices from their own old file win; cells from four columns are stretched onto six; "cells" wins over the layout.
        assertEquals(mapOf(1 to "p1", 2 to "p2", 4 to "p3"), m.report.pageIds)
        assertEquals(listOf("old.owl" to Cell(0, 6, 6, 2), CHROME to Cell(0, 0, 1, 1)), m.cells(0))
        assertEquals(listOf("a.owl" to Cell(3, 0, 3, 2)), m.cells(1))
        assertEquals(listOf("b.owl" to Cell(0, 0, 3, 2)), m.cells(2))
        assertEquals(listOf("gone.owl"), m.report.orphans)
        assertEquals(listOf(CHROME), m.report.moved.map { it.key })
        assertTrue(m.report.summary(), m.report.clean)
    }

    private fun layout(
        cells: Map<String, Cell>,
        pages: Map<String, Int> = emptyMap(),
    ): String {
        val json = JSONObject()
        for ((k, c) in cells) json.put(k, JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h))
        return Versioned.write(1, JSONObject().put("columns", 6).put("cells", json).put("pages", JSONObject(pages)))
    }

    private fun apps(vararg app: Triple<String, Int, String?>) =
        Versioned.write(1, JSONArray().also { a -> app.forEach { (key, page, folder) -> a.put(JSONObject().put("key", key).put("page", page).put("label", key).putOpt("folder", folder)) } })

    @Test fun aCellHangingOverTheSideIsKeptWhereHomeDrawsIt() {
        val m = checked(OldStores(layout = layout(mapOf("wide.owl" to Cell(4, 1, 5, 2), "app:x" to Cell(9, 3, 1, 1)))), listOf(OldWidget("wide.owl", 1)))
        assertEquals(listOf("wide.owl" to Cell(1, 1, 5, 2)), m.cells(0))
        assertEquals(listOf(Moved("wide.owl", Cell(4, 1, 5, 2), Cell(1, 1, 5, 2), "p1", false)), m.report.moved)
        assertEquals(listOf("app:x"), m.report.orphans)
    }

    @Test fun aSecondOverflowPageFollowsTheFirst() {
        // Page 1 is full; three 6x5 tiles saved below row 12 need two more pages.
        val cells = mapOf("wall.owl" to Cell(0, 0, 6, 12), "x.owl" to Cell(0, 12, 6, 5), "y.owl" to Cell(0, 17, 6, 5), "z.owl" to Cell(0, 22, 6, 5), "next.owl" to Cell(0, 0, 1, 1))
        val m = checked(OldStores(layout = layout(cells, mapOf("next.owl" to 5))), cells.keys.map { OldWidget(it, 1) })
        assertEquals(12, m.notebook.rows)
        assertEquals(listOf(listOf("wall.owl"), listOf("x.owl", "y.owl"), listOf("z.owl"), listOf("next.owl")), m.notebook.pages.map { p -> p.items.map { it.key } })
        assertEquals(listOf("x.owl" to Cell(0, 0, 6, 5), "y.owl" to Cell(0, 5, 6, 5)), m.cells(1))
        assertEquals(2, m.report.pagesAdded)
        assertEquals(mapOf(1 to "p1", 5 to "p4"), m.report.pageIds)
    }

    @Test fun foldersAndTheirAppsStayTogether() {
        val shortcuts = apps(Triple("app:a", 3, "folder:f"), Triple("app:b", 1, null), Triple("app:c", 3, "folder:f"), Triple("app:d", 2, "folder:missing"))
        val folders = Versioned.write(1, JSONArray().put(JSONObject().put("key", "folder:f").put("page", 3)).put(JSONObject().put("key", "folder:empty")))
        val m = checked(OldStores(shortcuts = shortcuts, folders = folders), emptyList())
        assertEquals(listOf(listOf("app:b", "folder:empty"), listOf("app:d"), listOf("folder:f")), m.notebook.pages.map { p -> p.items.map { it.key } })
        assertEquals(listOf("app:a", "app:c"), m.notebook.pages[2].items[0].apps.map { it.key })
        // A folder left without apps is still there, as it is on home.
        assertEquals(emptyList<PinnedApp>(), m.notebook.pages[0].items[1].apps)
        assertTrue(m.report.clean)
    }

    @Test fun aNameSeenTwiceCountsOnce() {
        val m = checked(OldStores(shortcuts = apps(Triple("app:a", 1, null), Triple("app:a", 2, null))), listOf(OldWidget("w.owl", 1), OldWidget("w.owl", 3)))
        assertEquals(listOf("w.owl", "app:a"), m.notebook.pages.flatMap { it.items }.map { it.key })
        assertEquals(1, m.notebook.pages.size)
        assertEquals(listOf("2 items share a name with another; the first of each was kept"), m.report.problems)
    }

    @Test fun nothingReadableNeverThrows() {
        val junk = listOf(null, "", " ", "null", "{", "[]", "{}", "12", "\"text\"", "{\"v\":1}", "{\"v\":\"x\",\"data\":{}}", "{\"v\":1,\"data\":null}", "{\"v\":1,\"data\":[1,2]}", "{\"v\":1,\"data\":{\"cells\":[],\"pages\":7}}", "{\"v\":1,\"data\":{\"columns\":0,\"cells\":{\"a\":{\"x\":\"q\"}}}}")
        val widgets = listOf(OldWidget("a", 1, 2 to 2), OldWidget("b", -4), OldWidget("c", Int.MAX_VALUE, 0 to 0), OldWidget("d", Int.MIN_VALUE, -5 to 99))
        for (layout in junk) for (other in junk) {
            val m = checked(OldStores(layout = layout, legacyCells = other, shortcuts = other, folders = layout, legacyPages = mapOf("a" to 0)), widgets)
            assertEquals(listOf("a", "b", "c", "d"), m.notebook.pages.flatMap { it.items }.map { it.key }.sorted())
        }
    }

    @Test fun wildCellsNeverThrowOrLoseAnItem() {
        val cells =
            mapOf(
                "a" to Cell(0, Int.MAX_VALUE, 1, 1),
                "b" to Cell(Int.MAX_VALUE, 0, Int.MAX_VALUE, Int.MAX_VALUE),
                "c" to Cell(-3, -3, -3, -3),
                "d" to Cell(0, 11, 6, Int.MAX_VALUE),
                "e" to Cell(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE),
                "app:f" to Cell(5, Int.MAX_VALUE, 0, 0),
            )
        val m = checked(OldStores(layout = layout(cells), shortcuts = apps(Triple("app:f", 1, null))), listOf("a", "b", "c", "d", "e").map { OldWidget(it, 1) })
        assertEquals(cells.keys.sorted(), m.notebook.pages.flatMap { it.items }.map { it.key }.sorted())
    }

    /** Layouts no one would save, by the thousand: the invariants of [checked] hold for each. */
    @Test fun randomLayoutsKeepEveryItem() {
        val random = Random(20261011)
        repeat(2000) {
            val widgets = (0 until random.nextInt(0, 14)).map { OldWidget("w$it.owl", random.nextInt(-1, 6), if (random.nextBoolean()) random.nextInt(-1, 9) to random.nextInt(-1, 16) else null) }
            val appKeys = (0 until random.nextInt(0, 14)).map { "app:$it" }
            val folderKeys = (0 until random.nextInt(0, 3)).map { "folder:$it" }
            val cells = HashMap<String, Cell>()
            for (key in widgets.map { it.key } + appKeys + folderKeys + "orphan") {
                if (random.nextInt(4) > 0) cells[key] = Cell(random.nextInt(-1, 8), random.nextInt(-1, 16), random.nextInt(0, 8), random.nextInt(0, 16))
            }
            val pages = widgets.filter { random.nextInt(3) == 0 }.associate { it.key to random.nextInt(0, 9) }
            val shortcuts = apps(*appKeys.map { Triple(it, random.nextInt(1, 5), (folderKeys + "folder:none").randomOrNull(random)?.takeIf { random.nextInt(3) == 0 }) }.toTypedArray())
            val folders = Versioned.write(1, JSONArray().also { a -> folderKeys.forEach { a.put(JSONObject().put("key", it).put("page", random.nextInt(1, 5))) } })
            val m = checked(OldStores(layout = layout(cells, pages), shortcuts = shortcuts, folders = folders), widgets)
            val everything = m.notebook.pages.flatMap { p -> p.items.flatMap { listOf(it.key) + it.apps.map { a -> a.key } } }
            assertEquals((widgets.map { it.key } + appKeys + folderKeys).sorted(), everything.sorted())
            assertTrue(m.report.summary(), m.report.clean)
            // A cell that was on the page as saved is where it was.
            for (page in m.notebook.pages) for (item in page.items) {
                val saved = cells[item.key] ?: continue
                val shown = saved.shown(item.kind == ItemKind.Widget)
                if (shown.x >= 0 && shown.y >= 0 && shown.w >= 1 && shown.h >= 1 && shown.right <= 6 && shown.bottom <= m.notebook.rows) assertEquals(item.key, shown, item.cell)
            }
        }
    }
}
