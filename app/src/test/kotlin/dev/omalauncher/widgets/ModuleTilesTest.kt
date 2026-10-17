package dev.omalauncher.widgets

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.ModuleRef
import dev.omalauncher.canvas.isModuleTile
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.canvas.moduleProjects
import dev.omalauncher.canvas.moduleRef
import dev.omalauncher.ui.storeModules
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.applyVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The cockpit modules on the Fold's home (Soma Canvas slice 4): their keys, the store's list, and how they land and stay. */
class ModuleTilesTest {
    @Test fun aModulesKeySaysWhichAndNothingElse() {
        assertEquals("module:needs", moduleKey(ModuleKind.Needs))
        assertEquals("module:handoff:omalauncher", moduleKey(ModuleKind.Handoff, "omalauncher"))
        for (kind in ModuleKind.entries) {
            val key = moduleKey(kind, if (kind.perProject) "pond" else null)!!
            assertEquals(ModuleRef(kind, if (kind.perProject) "pond" else null), moduleRef(key))
            assertTrue(isModuleTile(key))
        }
        // A project's module needs a project, and a plain one takes none; a project is a registry name, never a path.
        assertNull(moduleKey(ModuleKind.Handoff))
        assertNull(moduleKey(ModuleKind.Needs, "pond"))
        for (bad in listOf("../etc", "a/b", "", ".hidden", "x".repeat(81), "has space")) assertNull(bad, moduleKey(ModuleKind.Project, bad))
        // A key this build can't read is no module it shows: it keeps its place and draws nothing.
        for (key in listOf("module:", "module:clock", "module:needs:pond", "module:handoff", "module:handoff:../x", "module:project:a:b", "thread:mac:mac/t1", "clock.owl")) assertNull(key, moduleRef(key))
        assertFalse(isModuleTile("thread:mac:mac/t1"))
        assertEquals(listOf("omalauncher", "pond"), moduleProjects(listOf("module:needs", "module:project:pond", "module:handoff:pond", "module:handoff:omalauncher", "clock.owl")))
    }

    @Test fun theStoreOffersEachModuleOnceAndProjectsForTheirThreads() {
        val offered = storeModules(listOf("omalauncher", "pond", "../bad"))
        assertEquals(ModuleKind.entries.count { !it.perProject && !it.chatScreen } + 4, offered.size)
        // The chat is an app of its own on the phone: never offered for home.
        assertTrue(offered.none { it.kind == ModuleKind.Chat })
        assertEquals(offered.map { it.key }.distinct(), offered.map { it.key })
        assertTrue(offered.any { it.key == "module:project:pond" && it.name == "project · pond" })
        assertTrue(offered.none { "bad" in it.key })
        assertEquals(listOf("Needs you", "Threads", "Computers", "Timeline", "New thread", "Sessions", "Pocket"), storeModules(emptyList()).map { it.name })
    }

    @Test fun aModuleIsKeptInTheSavedNotebookAsAModule() {
        val notebook = Notebook(rows = 8, pages = listOf(NotebookPage("p1", "Soma", listOf(
            NotebookItem("module:needs", ItemKind.Module, Cell(0, 0, 3, 2)),
            NotebookItem("module:handoff:pond", ItemKind.Module, Cell(3, 0, 3, 3)),
            NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 2, 3, 2)),
        ))))
        val text = NotebookStore.write(notebook)
        // Saved as a widget, so a launcher from before modules holds its place (a widget with no file) rather than
        // dropping it and setting his pages aside; read back here as the module its key says.
        val items = org.json.JSONObject(text).getJSONObject("data").getJSONArray("pages").getJSONObject(0).getJSONArray("items")
        assertEquals(listOf("widget", "widget", "widget"), (0 until items.length()).map { items.getJSONObject(it).getString("kind") })
        // One saved by a build that wrote the kind "module" reads the same.
        val asModule = org.json.JSONObject(text).apply { getJSONObject("data").getJSONArray("pages").getJSONObject(0).getJSONArray("items").getJSONObject(0).put("kind", "module") }.toString()
        assertEquals(notebook, NotebookStore.read(asModule))
        val read = NotebookStore.inspect(text)!!
        assertEquals(0, read.skipped)
        assertEquals(notebook, read.notebook)
        // Opened and saved again by the launcher, it is the same document; the board tells it as a module.
        val saved = ArrayList<String>()
        val live = LiveNotebook(persist = { saved += it }).also { it.open(read.notebook, kept = true) }
        assertEquals(notebook.pages.single().items.toSet(), live.snapshot().pages.single().items.toSet())
        assertEquals(mapOf("module:needs" to "p1", "module:handoff:pond" to "p1"), live.modules())
        assertTrue(live.board("p1").tiles.filter { it.name.startsWith("module:") }.all { it.kind == "module" })
        // An agent's exact verb may place one, as it may a widget.
        assertTrue(applyVerb(notebook, CanvasVerb.Place("module:timeline", ItemKind.Module, "p1", Cell(0, 4, 6, 2))).applied)
    }

    private fun home(vararg pages: NotebookPage): Triple<LiveNotebook, WidgetLayout, (String) -> List<HomeItem>> {
        val live = LiveNotebook(persist = {}).also { it.open(Notebook(rows = 4, pages = pages.toList()), kept = true) }
        val layout = WidgetLayout(live)
        val itemsOn: (String) -> List<HomeItem> = { page ->
            live.snapshot().pages.first { it.id == page }.items.map { if (isModuleTile(it.key)) ModuleItem(it.key) else WidgetItem(WidgetSpec(File(it.key), it.key, startPage = 1, order = 0, program = null, size = it.cell?.let { c -> c.w to c.h })) }
        }
        return Triple(live, layout, itemsOn)
    }

    @Test fun aModuleLandsAsAnyNewTileDoes() {
        val (live, layout, itemsOn) = home(
            NotebookPage("p1", "Today", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)))),
            NotebookPage("p2", "Full", listOf(NotebookItem("wall.owl", ItemKind.Widget, Cell(0, 0, 6, 4)))),
            NotebookPage("p3", "Room", listOf(NotebookItem("mail.owl", ItemKind.Widget, Cell(0, 0, 6, 2)))),
        )
        // No grid yet: it waits on its page, with no cells, and is placed when the page is laid out.
        assertEquals("p1", landModule(live, layout, "module:needs", "p1", itemsOn))
        assertNull(live.cell("module:needs"))
        layout.place(itemsOn("p1"), GRID_COLUMNS, live.rows, "p1")
        assertEquals(Cell(3, 0, 3, 2), live.cell("module:needs"))
        // Once there, it is there once: a second add changes nothing.
        assertNull(landModule(live, layout, "module:needs", "p3", itemsOn))
        assertEquals("p1", live.pageOf("module:needs"))
        // A full page sends it to the next with room, at its own size.
        assertEquals("p3", landModule(live, layout, "module:timeline", "p2", itemsOn))
        assertEquals(Cell(0, 2, 6, 2), live.cell("module:timeline"))
        // With no room anywhere, a new page.
        val made = landModule(live, layout, "module:handoff:pond", "p2", itemsOn)!!
        assertTrue(made !in listOf("p1", "p2", "p3"))
        // On a new page its cells come when the page is first laid out, as a widget's do.
        layout.place(itemsOn(made), GRID_COLUMNS, live.rows, made)
        assertEquals(Cell(0, 0, 3, 3), live.cell("module:handoff:pond"))
        // No module, no page: nothing.
        assertNull(landModule(live, layout, "module:clock", "p1", itemsOn))
        assertNull(landModule(live, layout, "module:chat", "p1", itemsOn))
        assertNull(landModule(live, layout, "module:pocket", "nope", itemsOn))
        assertEquals(ModuleKind.Timeline.size, ModuleItem("module:timeline").size)
        assertEquals(1 to 1, ModuleItem("module:timeline").minSize)
        assertEquals(1 to 1, ModuleItem("module:unknown").size)
    }

    @Test fun aModuleGoesWithItsPageOrMovesWithIt() {
        val pages = arrayOf(
            NotebookPage("p1", "One", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 2, 2)))),
            NotebookPage("p2", "Two", listOf(NotebookItem("module:needs", ItemKind.Module, Cell(0, 0, 3, 2)))),
        )
        val (moved, _, _) = home(*pages)
        val gone = moved.deletePage("p2", widgets = setOf("clock.owl"), move = true)!!
        assertEquals("p1", moved.pageOf("module:needs"))
        assertTrue("module:needs" in gone.moved)
        val (dropped, _, _) = home(*pages)
        dropped.deletePage("p2", widgets = setOf("clock.owl"), move = false)!!
        assertNull(dropped.pageOf("module:needs"))
    }

    /** The launcher's own tile keeping never moves or removes a module, even one he put on the Agents page. */
    @Test fun theThreadTileKeeperLeavesModulesAlone() {
        val start = Notebook(rows = 8, pages = listOf(
            NotebookPage("p1", "Home", listOf(NotebookItem("module:threads", ItemKind.Module, Cell(0, 0, 3, 2)))),
            NotebookPage("a", AGENTS_PAGE, listOf(NotebookItem("module:needs", ItemKind.Module, Cell(0, 0, 3, 2)))),
        ))
        val live = LiveNotebook(persist = {}).also { it.open(start, kept = true) }
        val keeper = AgentTileKeeper(live, null, {})
        var now = 1_800_000_000_000L
        val busy = AgentThread("mac:mac/t1", "cockpit", ThreadState.Working, "", "working", worked = now)
        repeat(5) {
            now += 2_000
            keeper.keep(listOf(busy), true, now) { emptyList() }
        }
        keeper.keep(emptyList(), true, now + 13 * 3_600_000L) { emptyList() }
        assertEquals(mapOf("module:threads" to "p1", "module:needs" to "a"), live.modules())
        assertEquals(Cell(0, 0, 3, 2), live.cell("module:needs"))
    }
}
