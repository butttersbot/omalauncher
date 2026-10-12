package dev.omalauncher.widgets.layout

import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.LiveNotebook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * The stores of a phone, for driving a start on the JVM. The old stores are maps that throw if anything
 * tries to change them, and [NotebookFiles] has no way to write them at all; every call is in [log].
 */
internal class FakeFiles(
    widgetGrid: Map<String, Any> = emptyMap(),
    shortcuts: Map<String, Any> = emptyMap(),
    widgetPages: Map<String, Any> = emptyMap(),
    private val dock: String? = null,
) : NotebookFiles {
    val widgetGrid: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(widgetGrid))
    val shortcuts: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(shortcuts))
    val widgetPages: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(widgetPages))

    /** The old stores as they were when the phone was handed over, to compare with afterwards. */
    private val before = listOf(widgetGrid, shortcuts, widgetPages).map { it.toString() } + dock

    fun oldStoresUntouched() = assertEquals(before, listOf(widgetGrid, shortcuts, widgetPages).map { it.toString() } + dock)

    /** The `notebook` store: absent until something is committed, or put there by a test. */
    var exists = false
    var stored: String? = null
    val log = ArrayList<String>()
    val aside = ArrayList<Pair<Long, String?>>()

    var notebookFails = false
    var oldFails = false
    var dockFails = false

    /** What a commit does: true writes, false reports failure, null throws. */
    var commit: Boolean? = true
    var setAside: Boolean? = true

    val commits get() = log.count { it == "commit" }

    override fun notebook(): SavedNotebook {
        log += "notebook"
        if (notebookFails) throw IllegalStateException("can't open the preferences")
        return SavedNotebook(exists, stored)
    }

    override fun old(): OldStores {
        log += "old"
        if (oldFails) throw IllegalStateException("can't read the old preferences")
        return OldStores.of(widgetGrid, shortcuts, widgetPages)
    }

    override fun oldDock(): String? {
        log += "dock"
        if (dockFails) throw ClassCastException("items isn't a string")
        return dock
    }

    override fun commit(text: String): Boolean {
        log += "commit"
        val works = commit ?: throw java.io.IOException("disk full")
        if (works) {
            exists = true
            stored = text
        }
        return works
    }

    override fun setAside(stamp: Long): Boolean {
        log += "aside"
        val works = setAside ?: throw java.io.IOException("disk full")
        if (works) aside += stamp to stored
        return works
    }

    /** What the running launcher saves a change with. */
    fun save(text: String) {
        log += "save"
        exists = true
        stored = text
    }

    companion object {
        /** A phone with the pulled preferences in `src/test/resources/migration/<name>`. */
        fun of(
            name: String,
            dock: String? = null,
        ): FakeFiles {
            val dir = File("src/test/resources/migration", name)
            assertTrue("no fixture $dir", File(dir, "widget_grid.xml").exists())
            return FakeFiles(readPrefs(File(dir, "widget_grid.xml")), readPrefs(File(dir, "shortcuts.xml")), readPrefs(File(dir, "widget_pages.xml")), dock)
        }

        fun widgets(name: String) = readWidgets(File("src/test/resources/migration/$name/widgets.txt"))
    }
}

/** The owner's layout as read from his phone on 2026-10-12, and the five widgets he has. */
private const val BOSS = "boss-2026-10-12"

class NotebookStartTest {
    private val chrome = JSONObject().put("key", "app:com.android.chrome/.Main#0").put("label", "Chrome").put("component", "com.android.chrome/com.google.android.apps.chrome.Main")
    private val workMail = JSONObject().put("key", "app:com.mail/.Main#10").put("label", "Mail").put("component", "com.mail/com.mail.Main").put("profile", 10L)
    private val foot =
        JSONObject().put("key", "linux:foot").put("label", "Foot").put("linux", JSONObject().put("name", "Foot").put("exec", "foot").put("terminal", false).put("id", "foot").put("icon", "foot"))
    private val chat = JSONObject().put("key", "oma:chat").put("label", "Oma")

    private fun dock(vararg entries: Any) = Versioned.write(1, JSONArray().also { a -> entries.forEach { a.put(it) } })

    private fun start(
        files: FakeFiles,
        widgets: List<OldWidget>?,
    ): NotebookStart {
        val opening = NotebookOpening(files, { 1760000000000 })
        return opening.saved() ?: opening.migrate(widgets)
    }

    /** The rehearsal on the phone's data, 2026-10-12: two pages of 6 x 9, every tile in its cell, nothing moved, clean. */
    @Test fun theOwnersLayoutMigratesExactlyAsRehearsed() {
        val files = FakeFiles.of(BOSS)
        val start = start(files, FakeFiles.widgets(BOSS))
        assertTrue(start.saved)
        assertNull(start.message)
        val notebook = start.notebook
        assertEquals(9, notebook.rows)
        assertEquals(6, notebook.columns)
        assertEquals(listOf("p1" to "Page 1", "p2" to "Page 2"), notebook.pages.map { it.id to it.name })
        assertEquals(
            mapOf(
                "10-today.owl" to Cell(0, 0, 3, 3),
                "20-fetch.owl" to Cell(3, 0, 3, 3),
                "50-koi.owl" to Cell(0, 3, 6, 3),
                "60-owl.owl" to Cell(0, 6, 3, 3),
            ),
            notebook.pages[0].items.associate { it.key to it.cell },
        )
        assertEquals(mapOf("45-terminal.owl" to Cell(0, 0, 6, 4)), notebook.pages[1].items.associate { it.key to it.cell })
        assertTrue(notebook.pages.flatMap { it.items }.all { it.kind == ItemKind.Widget })
        assertTrue(notebook.dock.isEmpty())
        val report = start.report!!
        assertTrue(report.clean)
        assertEquals(5, report.items)
        assertEquals(emptyList<Moved>(), report.moved)
        assertEquals(0, report.replaced + report.placed + report.pagesAdded + report.overlaps)
        assertEquals(emptyList<String>(), report.orphans)
        assertEquals(mapOf(1 to "p1", 3 to "p2"), report.pageIds)
        assertEquals(mapOf("1248x1972" to 9, "1848x2448" to 9), report.portraitRows)
        // What is on disk is that notebook, whole.
        assertEquals(notebook, NotebookStore.read(files.stored))
        files.oldStoresUntouched()
    }

    @Test fun theFirstStartMigratesAndCommitsOnce() {
        val files = FakeFiles.of("normal")
        val start = start(files, FakeFiles.widgets("normal"))
        assertTrue(start.saved)
        assertEquals(listOf("notebook", "old", "dock", "commit"), files.log)
        assertEquals(1, files.commits)
        assertEquals(start.notebook, NotebookStore.read(files.stored))
        files.oldStoresUntouched()
    }

    @Test fun theSecondStartDoesNotMigrate() {
        val files = FakeFiles.of("normal")
        val first = start(files, FakeFiles.widgets("normal"))
        files.log.clear()
        // No widgets are asked for or needed: the saved notebook is all there is to read.
        val second = NotebookOpening(files).saved()
        assertNotNull(second)
        assertTrue(second!!.saved)
        assertNull(second.report)
        assertNull(second.message)
        assertEquals(first.notebook, second.notebook)
        assertEquals(listOf("notebook"), files.log)
        files.oldStoresUntouched()
    }

    /** UP-2. */
    @Test fun aLayoutThatCantAllBeReadIsShownButNothingIsSaved() {
        for (name in listOf("corrupt-layout", "partly")) {
            val files = FakeFiles.of(name)
            val start = start(files, FakeFiles.widgets(name))
            assertFalse(name, start.saved)
            assertFalse(name, start.report!!.clean)
            assertEquals(NotebookOpening.UNSAVED, start.message)
            assertTrue(start.notebook.pages.isNotEmpty())
            assertEquals(name, 0, files.commits)
            assertFalse(files.exists)
            assertNull(files.stored)
            files.oldStoresUntouched()
            // The next start finds no notebook and tries again, from the same old stores.
            files.log.clear()
            val again = start(files, FakeFiles.widgets(name))
            assertFalse(again.saved)
            assertEquals(start.notebook, again.notebook)
            assertEquals(listOf("notebook", "old", "dock"), files.log)
        }
    }

    @Test fun aFailedCommitPersistsNothing() {
        for (outcome in listOf(false, null)) {
            val files = FakeFiles.of(BOSS)
            files.commit = outcome
            val start = start(files, FakeFiles.widgets(BOSS))
            assertFalse(start.saved)
            assertEquals(NotebookOpening.UNSAVED, start.message)
            // Home still has the whole layout to show.
            assertEquals(5, start.notebook.pages.sumOf { it.items.size })
            assertTrue(start.report!!.clean)
            assertEquals(1, files.commits)
            assertFalse(files.exists)
            assertNull(files.stored)
            files.oldStoresUntouched()
            // Next start, with the disk back: migrated and saved.
            files.commit = true
            val again = start(files, FakeFiles.widgets(BOSS))
            assertTrue(again.saved)
            assertEquals(start.notebook, again.notebook)
        }
    }

    /** UP-3: the commit is the only write there is, so a death before it leaves nothing and one after it a whole notebook. */
    @Test fun theCommitIsTheOnlyWrite() {
        val files = FakeFiles.of(BOSS)
        start(files, FakeFiles.widgets(BOSS))
        assertEquals(listOf("commit"), files.log.filter { it != "notebook" && it != "old" && it != "dock" })
        assertEquals("commit", files.log.last())
    }

    @Test fun anUnsavedStartWritesNothingForTheWholeSession() {
        val files = FakeFiles.of(BOSS)
        files.commit = false
        val start = start(files, FakeFiles.widgets(BOSS))
        val live = LiveNotebook(persist = files::save)
        live.open(start.notebook, kept = start.saved)
        files.log.clear()
        live.setCell("10-today.owl", Cell(3, 6, 3, 3))
        live.save()
        live.move("10-today.owl", "p2")
        val added = live.addPage()!!
        live.move("20-fetch.owl", added)
        live.changeDock(listOf(PinnedApp("app:x", "X", "x/.X")))
        live.forget("50-koi.owl")
        assertEquals(emptyList<String>(), files.log)
        assertFalse(files.exists)
        files.oldStoresUntouched()
    }

    @Test fun afterASavedStartEveryChangeGoesToTheNotebookStoreAlone() {
        val files = FakeFiles.of("normal")
        val start = start(files, FakeFiles.widgets("normal"))
        val live = LiveNotebook(persist = files::save)
        live.open(start.notebook, kept = start.saved)
        assertEquals(start.notebook.copy(pages = start.notebook.pages.map { p -> p.copy(items = p.items.sortedBy { it.key }) }), live.snapshot().let { n -> n.copy(pages = n.pages.map { p -> p.copy(items = p.items.sortedBy { it.key }) }) })
        files.log.clear()
        live.setCell("clock.owl", Cell(0, 6, 3, 2))
        live.save()
        live.move("clock.owl", "p2")
        live.unpin("linux:htop")
        live.changeDock(listOf(PinnedApp("app:x", "X", "x/.X")))
        live.addPage()
        assertTrue(files.log.isNotEmpty())
        // The fake's old stores throw if written, and nothing but saves of the notebook was asked of the files.
        assertEquals(setOf("save"), files.log.toSet())
        files.oldStoresUntouched()
        // And what was saved is what the next start reads, without migrating.
        files.log.clear()
        val next = NotebookOpening(files).saved()!!
        assertEquals(live.snapshot(), next.notebook)
        assertEquals(listOf("notebook"), files.log)
    }

    @Test fun theDocksEntriesArriveInOrder() {
        val files = FakeFiles.of(BOSS, dock = dock(chat, workMail, foot, chrome))
        val start = start(files, FakeFiles.widgets(BOSS))
        assertTrue(start.saved)
        assertEquals(listOf("oma:chat", "app:com.mail/.Main#10", "linux:foot", "app:com.android.chrome/.Main#0"), start.notebook.dock.map { it.key })
        val mail = start.notebook.dock[1]
        assertEquals("Mail", mail.label)
        assertEquals("com.mail/com.mail.Main", mail.component)
        assertEquals(10L, mail.profile)
        assertEquals(LinuxEntry("Foot", "foot", false, "foot", null, "foot"), start.notebook.dock[2].linux)
        assertEquals(start.notebook.dock, NotebookStore.read(files.stored)!!.dock)
        // The pages are what they were without a dock.
        assertEquals(start(FakeFiles.of(BOSS), FakeFiles.widgets(BOSS)).notebook.pages, start.notebook.pages)
        files.oldStoresUntouched()
    }

    @Test fun aDockEntrySavedTwiceArrivesOnce() {
        val start = start(FakeFiles.of(BOSS, dock = dock(chat, chrome, workMail, chrome, chat)), FakeFiles.widgets(BOSS))
        assertTrue(start.saved)
        assertEquals(listOf("oma:chat", "app:com.android.chrome/.Main#0", "app:com.mail/.Main#10"), start.notebook.dock.map { it.key })
    }

    @Test fun noDockFileIsAnEmptyDockAndClean() {
        for (text in listOf(null, "", "  ")) {
            val start = start(FakeFiles.of(BOSS, dock = text), FakeFiles.widgets(BOSS))
            assertTrue(start.saved)
            assertTrue(start.notebook.dock.isEmpty())
        }
        // A dock saved empty is one too.
        assertTrue(start(FakeFiles.of(BOSS, dock = dock()), FakeFiles.widgets(BOSS)).saved)
    }

    @Test fun aDockThatCantBeReadSavesNothing() {
        val bad = listOf("{not json", Versioned.write(1, JSONObject()), dock(chat, "junk"), dock(JSONObject().put("label", "no key")))
        for (text in bad) {
            val files = FakeFiles.of(BOSS, dock = text)
            val start = start(files, FakeFiles.widgets(BOSS))
            assertFalse(text, start.saved)
            assertTrue(text, start.report!!.problems.any { "dock" in it })
            assertEquals(0, files.commits)
            // The pages are still shown.
            assertEquals(5, start.notebook.pages.sumOf { it.items.size })
            files.oldStoresUntouched()
        }
        val files = FakeFiles.of(BOSS).apply { dockFails = true }
        assertFalse(start(files, FakeFiles.widgets(BOSS)).saved)
        assertEquals(0, files.commits)
    }

    @Test fun oldStoresThatCantBeOpenedSaveNothing() {
        val files = FakeFiles.of(BOSS).apply { oldFails = true }
        val start = start(files, FakeFiles.widgets(BOSS))
        assertFalse(start.saved)
        assertEquals(NotebookOpening.UNSAVED, start.message)
        assertEquals(listOf("Page 1"), start.notebook.pages.map { it.name })
        assertEquals(0, files.commits)
        assertFalse(files.exists)
    }

    /** An unreadable notebook is never migrated over: it is kept aside first. */
    @Test fun anUnreadableNotebookIsSetAsideThenMigrated() {
        for (junk in listOf("{not json", "", Versioned.write(1, JSONObject().put("rows", 9)), Versioned.write(1, JSONObject().put("rows", 9).put("pages", JSONArray())), null)) {
            val files = FakeFiles.of(BOSS)
            files.exists = true
            files.stored = junk
            val start = start(files, FakeFiles.widgets(BOSS))
            assertTrue("$junk", start.saved)
            assertEquals(listOf("notebook", "old", "dock", "aside", "commit"), files.log)
            assertEquals(listOf(1760000000000 to junk), files.aside)
            assertTrue(start.message!!.contains("set aside"))
            assertEquals(start.notebook, NotebookStore.read(files.stored))
            assertEquals(5, start.notebook.pages.sumOf { it.items.size })
            files.oldStoresUntouched()
        }
    }

    @Test fun anUnreadableNotebookThatCantBeSetAsideIsLeftAlone() {
        for (outcome in listOf(false, null)) {
            val files = FakeFiles.of(BOSS)
            files.exists = true
            files.stored = "{not json"
            files.setAside = outcome
            val start = start(files, FakeFiles.widgets(BOSS))
            assertFalse(start.saved)
            assertEquals(NotebookOpening.UNSAVED, start.message)
            assertEquals(0, files.commits)
            assertEquals("{not json", files.stored)
            assertEquals(5, start.notebook.pages.sumOf { it.items.size })
        }
    }

    /**
     * A notebook that reads only in part would lose the rest at its first save: the whole of it is set aside before
     * that. Reading it writes nothing (that is on the main thread, at start); [NotebookOpening.finish] does the rest.
     */
    @Test fun aNotebookThatReadsOnlyInPartIsSetAsideBeforeItIsSavedOver() {
        val whole = start(FakeFiles.of("normal"), FakeFiles.widgets("normal")).notebook
        val root = JSONObject(NotebookStore.write(whole))
        root.getJSONObject("data").getJSONArray("pages").getJSONObject(0).getJSONArray("items").getJSONObject(0).put("kind", "hologram")
        val damaged = root.toString()
        val lost = whole.pages[0].items[0].key

        val files = FakeFiles.of("normal")
        files.exists = true
        files.stored = damaged
        val opening = NotebookOpening(files, { 7 })
        val start = opening.saved()!!
        // Shown, but not kept yet, and nothing touched.
        assertFalse(start.saved)
        assertTrue(start.pending)
        assertNull(start.message)
        assertEquals(listOf("notebook"), files.log)
        assertEquals(damaged, files.stored)
        assertFalse(start.notebook.pages.flatMap { it.items }.any { it.key == lost })
        assertEquals(whole.pages[1], start.notebook.pages[1])

        assertEquals(true to NotebookOpening.PARTLY_READ, opening.finish())
        assertEquals(listOf("notebook", "aside", "commit"), files.log)
        assertEquals(listOf(7L to damaged), files.aside)
        assertEquals(start.notebook, NotebookStore.read(files.stored))
        // Done once.
        assertEquals(true to null, opening.finish())
        assertEquals(1, files.commits)
        // What is there now reads whole, so the next start has nothing to set aside.
        files.log.clear()
        val next = NotebookOpening(files).saved()!!
        assertTrue(next.saved)
        assertFalse(next.pending)
        assertEquals(listOf("notebook"), files.log)
        files.oldStoresUntouched()

        // If it can't be set aside, or the clean copy can't be written, the store is left exactly as it was.
        for (failing in listOf("aside", "commit")) {
            for (outcome in listOf(false, null)) {
                val stuck = FakeFiles.of("normal")
                stuck.exists = true
                stuck.stored = damaged
                if (failing == "aside") stuck.setAside = outcome else stuck.commit = outcome
                val unsaved = NotebookOpening(stuck)
                assertEquals(start.notebook, unsaved.saved()!!.notebook)
                assertEquals(false to NotebookOpening.PARTLY_READ_UNSAVED, unsaved.finish())
                assertEquals(damaged, stuck.stored)
                if (failing == "aside") assertEquals(0, stuck.commits)
            }
        }
    }

    /** A store from a newer launcher is not this one's to rewrite: set aside as it is, shown, and nothing written all session. */
    @Test fun aNotebookFromANewerLauncherIsNeverRewritten() {
        val whole = start(FakeFiles.of(BOSS), FakeFiles.widgets(BOSS)).notebook
        val root = JSONObject(NotebookStore.write(whole)).put("v", 2)
        root.getJSONObject("data").put("ink", JSONObject().put("p1", "strokes this launcher knows nothing of"))
        val newer = root.toString()
        assertTrue(NotebookStore.newer(newer))
        assertFalse(NotebookStore.newer(NotebookStore.write(whole)))
        assertFalse(NotebookStore.newer(null))
        assertFalse(NotebookStore.newer("{not json"))
        assertFalse(NotebookStore.newer("[]"))

        val files = FakeFiles.of(BOSS)
        files.exists = true
        files.stored = newer
        val opening = NotebookOpening(files, { 9 })
        val start = opening.saved()!!
        assertFalse(start.saved)
        assertTrue(start.pending)
        // What it can read of it is what home shows.
        assertEquals(whole, start.notebook)
        assertEquals(listOf("notebook"), files.log)
        assertEquals(false to NotebookOpening.NEWER, opening.finish())
        assertEquals(listOf("notebook", "aside"), files.log)
        assertEquals(listOf(9L to newer), files.aside)
        // A whole session of changes writes nothing, and the store is byte for byte what it was.
        val live = LiveNotebook(persist = files::save)
        live.open(start.notebook, kept = start.saved)
        live.move("10-today.owl", "p2")
        live.addPage()
        live.changeDock(listOf(PinnedApp("app:x", "X", "x/.X")))
        live.save()
        assertEquals(listOf("notebook", "aside"), files.log)
        assertEquals(newer, files.stored)
        assertEquals(0, files.commits)
        files.oldStoresUntouched()
        // Even if it can't be set aside, it isn't written.
        val stuck = FakeFiles.of(BOSS)
        stuck.exists = true
        stuck.stored = newer
        stuck.setAside = null
        val again = NotebookOpening(stuck)
        assertFalse(again.saved()!!.saved)
        assertEquals(false to NotebookOpening.NEWER, again.finish())
        assertEquals(newer, stuck.stored)
    }

    @Test fun aNewerNotebookThatDoesntReadIsNotMigratedOverEither() {
        for (newer in listOf("""{"v":2,"data":{"pages":"elsewhere"}}""", """{"v":7,"data":[]}""")) {
            val files = FakeFiles.of(BOSS)
            files.exists = true
            files.stored = newer
            val opening = NotebookOpening(files, { 9 })
            assertNull(opening.saved())
            val start = opening.migrate(FakeFiles.widgets(BOSS))
            // Home has the layout from before to show, and keeps nothing.
            assertFalse(start.saved)
            assertEquals(NotebookOpening.NEWER, start.message)
            assertEquals(5, start.notebook.pages.sumOf { it.items.size })
            assertEquals(0, files.commits)
            assertEquals(newer, files.stored)
            assertEquals(listOf(9L to newer), files.aside)
            files.oldStoresUntouched()
        }
    }

    /** His layout with one more saved cell, and optionally a page choice for it. */
    private fun bossWith(
        key: String,
        cell: Cell?,
        page: Int? = null,
    ): FakeFiles {
        val grid = LinkedHashMap(readPrefs(File("src/test/resources/migration/$BOSS/widget_grid.xml")))
        val root = JSONObject(grid.getValue("layout") as String)
        val data = root.getJSONObject("data")
        cell?.let { data.getJSONObject("cells").put(key, JSONObject().put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)) }
        page?.let { data.getJSONObject("pages").put(key, it) }
        grid["layout"] = root.toString()
        return FakeFiles(grid)
    }

    private fun Notebook.sorted() = pages.map { p -> p.copy(items = p.items.sortedBy { it.key }) }

    private val five by lazy { start(FakeFiles.of(BOSS), FakeFiles.widgets(BOSS)) }

    /**
     * The first listing can miss a file (one being rewritten at that instant). Its cell is the owner's all the
     * same: it stays in the notebook, on the page he had it, and the file lands there when it is seen.
     */
    @Test fun aWidgetMissingFromTheFirstListingKeepsItsPageAndCell() {
        for (gone in listOf("10-today.owl", "20-fetch.owl", "50-koi.owl", "60-owl.owl")) {
            val files = FakeFiles.of(BOSS)
            val four = FakeFiles.widgets(BOSS).filter { it.key != gone }
            val start = start(files, four)
            assertTrue(gone, start.saved)
            assertNull(gone, start.message)
            // Exactly the notebook all five give, and nothing said of it.
            assertEquals(gone, five.notebook.sorted(), start.notebook.sorted())
            assertEquals(gone, 9, start.notebook.rows)
            assertEquals(gone, emptyList<Moved>(), start.report!!.moved)
            assertEquals(gone, 4, start.report!!.items)
            assertEquals(start.notebook, NotebookStore.read(files.stored))

            // Running: the file isn't there, so nothing shows; then it is seen, whatever page it asks for.
            val live = LiveNotebook(persist = files::save)
            live.open(start.notebook, kept = true)
            live.adopt(four.map { it.key to it.page })
            live.adopt(FakeFiles.widgets(BOSS).map { it.key to if (it.key == gone) 2 else it.page })
            assertEquals(gone, "p1", live.pageOf(gone))
            assertEquals(gone, five.notebook.pages[0].items.first { it.key == gone }.cell, live.cell(gone))
            // The next start reads the same (the store lists a page's items in the order they were written).
            assertEquals(live.snapshot().sorted(), NotebookOpening(files).saved()!!.notebook.sorted())
            files.oldStoresUntouched()
        }
    }

    /**
     * The one case of his layout that isn't kept: the terminal is alone on its page, and a held place never makes
     * a page. Without its file there is no Page 2; the file, when seen, starts where it asks, like a new one.
     */
    @Test fun aMissingWidgetAloneOnItsPageDoesNotMakeThePage() {
        val files = FakeFiles.of(BOSS)
        val start = start(files, FakeFiles.widgets(BOSS).filter { it.key != "45-terminal.owl" })
        assertTrue(start.saved)
        assertNull(start.message)
        assertEquals(listOf("p1"), start.notebook.pages.map { it.id })
        assertEquals(five.notebook.pages[0].copy(items = five.notebook.pages[0].items.sortedBy { it.key }), start.notebook.sorted()[0])
        assertEquals(listOf("45-terminal.owl"), start.report!!.orphans)
    }

    /** A cell left from a widget long gone, far down a page: the grid stays 6 x 9 and nothing is said. */
    @Test fun aStaleCellNeverChangesTheRowsOrIsReported() {
        for (cell in listOf(Cell(0, 20, 3, 3), Cell(0, 7, 3, 3), Cell(4, 0, 3, 2), Cell(-1, 0, 2, 2), Cell(0, 0, 0, 2))) {
            val files = bossWith("gone.owl", cell)
            val start = start(files, FakeFiles.widgets(BOSS))
            assertTrue("$cell", start.saved)
            assertNull("$cell", start.message)
            assertEquals("$cell", 9, start.notebook.rows)
            assertEquals("$cell", five.notebook, start.notebook)
            assertEquals(0, start.report!!.replaced + start.report!!.placed + start.report!!.pagesAdded)
            assertEquals(listOf("gone.owl"), start.report!!.orphans)
            assertEquals(5, start.report!!.items)
        }
        // One that fits the rows there are, on a page there is, is held: invisible, and nothing else differs.
        val held = start(bossWith("gone.owl", Cell(3, 6, 3, 3)), FakeFiles.widgets(BOSS))
        assertNull(held.message)
        assertEquals(9, held.notebook.rows)
        assertEquals(five.notebook.pages[0].items + NotebookItem("gone.owl", ItemKind.Widget, Cell(3, 6, 3, 3)), held.notebook.pages[0].items)
        assertEquals(five.notebook.pages[1], held.notebook.pages[1])
        assertEquals(emptyList<Moved>(), held.report!!.moved)
        assertEquals(5, held.report!!.items)
    }

    @Test fun aPageChoiceWithoutACellHoldsNothingAndMakesNoPage() {
        for (page in listOf(1, 3, 7)) {
            val start = start(bossWith("gone.owl", null, page), FakeFiles.widgets(BOSS))
            assertTrue(start.saved)
            assertNull(start.message)
            assertEquals("page $page", five.notebook, start.notebook)
        }
    }

    @Test fun aStaleCellOnAPageNothingIsOnMakesNoPage() {
        for (page in listOf(2, 7)) {
            val start = start(bossWith("gone.owl", Cell(0, 0, 3, 3), page), FakeFiles.widgets(BOSS))
            assertTrue(start.saved)
            assertNull(start.message)
            assertEquals("page $page", five.notebook, start.notebook)
            assertEquals(mapOf(1 to "p1", 3 to "p2"), start.report!!.pageIds)
        }
        // On the page the terminal is on (his old page 3), it is held there.
        val held = start(bossWith("gone.owl", Cell(0, 4, 3, 3), 3), FakeFiles.widgets(BOSS))
        assertEquals(listOf("45-terminal.owl", "gone.owl"), held.notebook.pages[1].items.map { it.key })
        assertEquals(five.notebook.pages[0], held.notebook.pages[0])
    }

    /** Only widget files are held: a cell left from an app that was unpinned, or a folder that is gone, stays behind. */
    @Test fun aStaleCellOfAnAppOrFolderIsNotHeldAsAWidget() {
        for (key in listOf("app:x", "linux:foot", "folder:abc", "notes.txt", "owl")) {
            val start = start(bossWith(key, Cell(3, 6, 1, 1)), FakeFiles.widgets(BOSS))
            assertTrue(key, start.saved)
            assertEquals(key, five.notebook, start.notebook)
            assertEquals(key, listOf(key), start.report!!.orphans)
        }
    }

    /** A tile the migration had to find a cell for is where the first pass put it; a held place never moves it, or lies over it. */
    @Test fun aHeldPlaceNeverLiesOverATileTheMigrationPlaced() {
        // A sixth widget with no saved cell asks for the page the terminal is on (old page 3), and the terminal's file is missing.
        val widgets = FakeFiles.widgets(BOSS).filter { it.key != "45-terminal.owl" } + OldWidget("zz-new.owl", 3, 6 to 4)
        val start = start(FakeFiles.of(BOSS), widgets)
        assertTrue(start.saved)
        assertEquals(mapOf("zz-new.owl" to Cell(0, 0, 6, 4)), start.notebook.pages[1].items.associate { it.key to it.cell })
        // Beside it there is room: held.
        val beside = start(bossWith("gone.owl", Cell(0, 5, 3, 3), 3), widgets)
        assertEquals(mapOf("zz-new.owl" to Cell(0, 0, 6, 4), "gone.owl" to Cell(0, 5, 3, 3)), beside.notebook.pages[1].items.associate { it.key to it.cell })
    }

    /** Running, a held cell is not free: home's own placing goes around it, and takes it without the page to ask about. */
    @Test fun homePlacesNothingOnAHeldCell() {
        val start = start(bossWith("gone.owl", Cell(0, 4, 6, 3), 3), FakeFiles.widgets(BOSS))
        val live = LiveNotebook()
        live.open(start.notebook, kept = false)
        val layout = dev.omalauncher.widgets.WidgetLayout(live)

        fun widget(
            name: String,
            size: Pair<Int, Int>,
        ) = dev.omalauncher.widgets.WidgetItem(dev.omalauncher.widgets.WidgetSpec(File(name), name, 1, 0, null, size = size))

        val terminal = widget("45-terminal.owl", 6 to 4)
        val fresh = widget("fresh.owl", 6 to 2)
        live.expect("fresh.owl", "p2")
        // The terminal has (0,0,6,4) and the held cell (0,4,6,3): the first free rows are the last two.
        assertEquals(emptyList<dev.omalauncher.widgets.HomeItem>(), layout.place(listOf(terminal, fresh), 6, 9, "p2"))
        assertEquals(Cell(0, 7, 6, 2), layout.cell("fresh.owl"))
        // The page is then full: nothing more is claimed on it, held cell included.
        val more = widget("more.owl", 3 to 2)
        assertFalse(layout.claim(more, listOf(terminal, fresh), "p2"))
        assertNull(layout.cell("more.owl"))
        // Asked without the page, nothing is known to be held, and the held cell is taken: the page is what makes the difference.
        assertTrue(layout.claim(more, listOf(terminal, fresh)))
        assertEquals(Cell(0, 4, 3, 2), layout.cell("more.owl"))
    }

    /** Only what reads back as itself is committed. */
    @Test fun aDocumentThatDoesNotReadBackAsTheNotebookIsNotCommitted() {
        val files = FakeFiles.of(BOSS)
        val lossy = NotebookOpening(files, write = { NotebookStore.write(it.copy(pages = it.pages.dropLast(1))) })
        assertNull(lossy.saved())
        val start = lossy.migrate(FakeFiles.widgets(BOSS))
        assertFalse(start.saved)
        assertEquals(NotebookOpening.UNSAVED, start.message)
        assertEquals(0, files.commits)
        assertFalse(files.exists)
        assertEquals(2, start.notebook.pages.size)
    }

    @Test fun aNotebookStoreThatCantBeOpenedIsNotTakenForAbsent() {
        val files = FakeFiles.of(BOSS).apply { notebookFails = true }
        val start = start(files, FakeFiles.widgets(BOSS))
        // Set aside before anything is written where it may be.
        assertEquals(listOf("notebook", "old", "dock", "aside", "commit"), files.log)
        assertTrue(start.saved)
        // And when it can't be set aside, nothing is written.
        val stuck = FakeFiles.of(BOSS).apply {
            notebookFails = true
            setAside = false
        }
        assertFalse(start(stuck, FakeFiles.widgets(BOSS)).saved)
        assertEquals(0, stuck.commits)
    }

    @Test fun anUnreadableNotebookWithAnUnreadableLayoutSavesNothingAndSetsNothingAside() {
        val files = FakeFiles.of("corrupt-layout")
        files.exists = true
        files.stored = "{not json"
        val start = start(files, FakeFiles.widgets("corrupt-layout"))
        assertFalse(start.saved)
        assertEquals(listOf("notebook", "old", "dock"), files.log)
        assertEquals("{not json", files.stored)
    }

    /** Without the widget files a migration would leave every widget's cell behind, so it isn't saved; they are tried again. */
    @Test fun withoutTheWidgetFilesNothingIsSaved() {
        val files = FakeFiles.of(BOSS)
        val opening = NotebookOpening(files)
        assertNull(opening.saved())
        for (widgets in listOf(null, emptyList<OldWidget>())) {
            val start = opening.migrate(widgets)
            assertFalse(start.saved)
            assertTrue(start.widgetsMissing)
            assertEquals(NotebookOpening.UNSAVED, start.message)
            assertEquals(0, files.commits)
            assertFalse(files.exists)
        }
        files.oldStoresUntouched()
        // Once they are listed, the same opening migrates and saves.
        val start = opening.migrate(FakeFiles.widgets(BOSS))
        assertTrue(start.saved)
        assertFalse(start.widgetsMissing)
        assertEquals(1, files.commits)
        assertEquals(5, start.notebook.pages.sumOf { it.items.size })
    }

    @Test fun aLauncherWithNothingSavedAndNoWidgetsStartsAnEmptyNotebook() {
        val files = FakeFiles()
        val start = start(files, emptyList())
        assertTrue(start.saved)
        assertNull(start.message)
        assertEquals(Notebook(9, listOf(NotebookPage("p1", "Page 1"))), start.notebook)
        assertEquals(1, files.commits)
        // But not if the widget files couldn't even be listed.
        assertFalse(start(FakeFiles(), null).saved)
    }

    /** UP-4. */
    @Test fun theOwnerIsToldHowManyTilesWerePlacedAgain() {
        val files = FakeFiles.of("too-tall")
        val start = start(files, FakeFiles.widgets("too-tall"))
        assertTrue(start.saved)
        val replaced = start.report!!.replaced
        assertTrue(replaced > 0)
        assertTrue(start.message, start.message!!.startsWith(if (replaced == 1) "1 tile " else "$replaced tiles "))
        // A layout where nothing moved says nothing.
        assertNull(start(FakeFiles.of("normal"), FakeFiles.widgets("normal")).message)
    }

    /** Every fixture, through a whole first and second start. */
    @Test fun everyFixtureEitherSavesWholeOrSavesNothing() {
        val names = File("src/test/resources/migration").listFiles { f -> File(f, "widget_grid.xml").exists() }.orEmpty().map { it.name }.sorted()
        assertTrue(names.size >= 10)
        for (name in names) {
            val files = FakeFiles.of(name)
            val start = start(files, FakeFiles.widgets(name))
            assertEquals(name, start.report!!.clean, start.saved)
            assertEquals(name, if (start.saved) 1 else 0, files.commits)
            if (start.saved) assertEquals(name, start.notebook, NotebookOpening(files).saved()!!.notebook) else assertNull(name, files.stored)
            files.oldStoresUntouched()
        }
    }

    /** The preferences files a source text opens: for each `getSharedPreferences(` call, the names its argument can stand for. */
    private fun opens(
        text: String,
        constants: Map<String, Set<String>>,
    ): List<Set<String>> {
        val own = constantsOf(text)
        return Regex("""getSharedPreferences\(\s*("[^"]*"|[\w.]+)""").findAll(text).map { call ->
            val arg = call.groupValues[1]
            // A literal is its own name; a constant is what it is set to, in this file first, else wherever it is defined.
            if (arg.startsWith("\"")) setOf(arg.trim('"')) else arg.substringAfterLast('.').let { own[it] ?: constants[it].orEmpty() }
        }.toList()
    }

    private fun constantsOf(text: String): Map<String, Set<String>> =
        Regex("""\bval\s+(\w+)(?:\s*:\s*String)?\s*=\s*"([^"]*)"""").findAll(text).groupBy({ it.groupValues[1] }, { it.groupValues[2] }).mapValues { it.value.toSet() }

    /**
     * Nothing in the launcher but [dev.omalauncher.widgets.NotebookPrefs] opens the notebook's store or the stores from
     * before it, by a name written out or through a constant; and NotebookPrefs opens the old ones only to read. A
     * source check, since no JVM test can open a phone's preferences. Other files open what preferences they like.
     */
    @Test fun onlyTheNotebookPrefsOpenTheLayoutStoresAndTheOldOnesOnlyToRead() {
        val root = File("src/main/kotlin/dev/omalauncher")
        val layout = setOf("widget_grid", "shortcuts", "widget_pages", "dock", "notebook")
        val sources = root.walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }
        val constants = sources.values.flatMap { constantsOf(it).entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.flatten().toSet() }
        val opened = sources.flatMap { (file, text) -> opens(text, constants).map { file to it } }
        assertTrue(opened.size > 5)
        assertEquals("a layout store is opened outside NotebookPrefs.kt", emptyList<Pair<String, Set<String>>>(), opened.filter { (file, names) -> file != "NotebookPrefs.kt" && names.any { it in layout } })
        // The check sees a literal, a constant of the same file, and one defined elsewhere; and lets other names be.
        assertEquals(listOf(setOf("dock")), opens("""ctx.getSharedPreferences("dock", 0)""", emptyMap()))
        assertEquals(listOf(setOf("shortcuts")), opens("""private const val OLD = "shortcuts"\nfun f() = ctx.getSharedPreferences(OLD, 0)""", mapOf("OLD" to setOf("ring"))))
        assertEquals(listOf(setOf("widget_grid")), opens("""ctx.getSharedPreferences(Names.GRID, 0)""", mapOf("GRID" to setOf("widget_grid"))))
        assertEquals(listOf(setOf("ring")), opens("""const val RING_PREFS = "ring"\nfun f() = context.getSharedPreferences(RING_PREFS, Context.MODE_PRIVATE)""", constants))
        assertEquals(listOf(emptySet<String>()), opens("""ctx.getSharedPreferences(name, 0)""", emptyMap()))

        val prefs = sources.getValue("NotebookPrefs.kt")
        for (name in layout - "notebook") assertTrue(name, "old(\"$name\")" in prefs)
        // Its two edits are both of the notebook's own preferences.
        assertEquals(2, Regex("""\.edit\(\)""").findAll(prefs).count())
        assertEquals(2, Regex("""prefs\.edit\(\)\.putString\(KEY, text\)""").findAll(prefs).count())
        assertTrue("private fun old(name: String): Map<String, *> = context.getSharedPreferences(name, Context.MODE_PRIVATE).all" in prefs)
    }
}
