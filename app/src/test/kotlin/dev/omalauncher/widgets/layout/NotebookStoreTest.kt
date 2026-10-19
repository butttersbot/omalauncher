package dev.omalauncher.widgets.layout

import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.widgets.Cell
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotebookStoreTest {
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val work = PinnedApp("app:chrome#10", "Chrome", "com.android.chrome/.Main", 10)
    private val firefox = PinnedApp("linux:firefox", "Firefox", linux = LinuxEntry("Firefox", "firefox %u", false, "firefox", "firefox", "firefox"))
    private val htop = PinnedApp("linux:htop", "htop", linux = LinuxEntry("htop", "htop", true, "htop"))
    private val notebook =
        Notebook(
            rows = 11,
            pages =
                listOf(
                    NotebookPage(
                        "p1",
                        "Today",
                        listOf(
                            NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)),
                            NotebookItem(chrome.key, ItemKind.App, Cell(0, 2, 1, 1), app = chrome),
                            NotebookItem(firefox.key, ItemKind.App, Cell(1, 2, 1, 1), app = firefox),
                            NotebookItem("folder:a", ItemKind.Folder, Cell(2, 2, 1, 1), apps = listOf(work, htop)),
                            NotebookItem("folder:none", ItemKind.Folder, Cell(3, 2, 1, 1)),
                            NotebookItem("unplaced.owl", ItemKind.Widget, null),
                        ),
                    ),
                    // A page stays when it's empty, and names are free text.
                    NotebookPage("p2", "Trip \"to\" 東京 \n"),
                ),
            dock = listOf(chrome, firefox),
        )

    @Test fun roundTrips() {
        assertEquals(notebook, NotebookStore.read(NotebookStore.write(notebook)))
        val empty = Notebook(9, listOf(NotebookPage("p1", "Page 1")))
        assertEquals(empty, NotebookStore.read(NotebookStore.write(empty)))
    }

    @Test fun roundTripsPinnedShortcutsAndOwlApps() {
        val site = PinnedApp("shortcut:org.chromium/#10#site-1", "Docs", profile = 10, shortcut = ShortcutEntry("org.chromium", "site-1"))
        val notes = PinnedApp("owl:notes.owl", "Notes", owl = "notes.owl")
        val withThem =
            Notebook(
                9,
                listOf(
                    NotebookPage(
                        "p1",
                        "Page 1",
                        listOf(
                            NotebookItem(site.key, ItemKind.App, Cell(0, 0, 1, 1), app = site),
                            NotebookItem("folder:a", ItemKind.Folder, Cell(1, 0, 1, 1), apps = listOf(notes, chrome)),
                        ),
                    ),
                ),
                dock = listOf(notes, site),
            )
        val written = NotebookStore.write(withThem)
        assertEquals(withThem, NotebookStore.read(written))
        val saved = JSONObject(written).getJSONObject("data").getJSONArray("dock")
        assertEquals("notes.owl", saved.getJSONObject(0).getString("owl"))
        assertEquals("site-1", saved.getJSONObject(1).getJSONObject("shortcut").getString("id"))
        assertEquals("org.chromium", saved.getJSONObject(1).getJSONObject("shortcut").getString("package"))
    }

    @Test fun isAVersionedDocument() {
        val root = JSONObject(NotebookStore.write(notebook))
        assertEquals(1, root.getInt("v"))
        val data = root.getJSONObject("data")
        assertEquals(setOf("columns", "rows", "pages", "dock"), data.keys().asSequence().toSet())
        assertEquals(6, data.getInt("columns"))
        assertEquals(11, data.getInt("rows"))
        val item = data.getJSONArray("pages").getJSONObject(0).getJSONArray("items").getJSONObject(0)
        assertEquals("clock.owl", item.getString("key"))
        assertEquals("widget", item.getString("kind"))
        assertEquals(setOf("x", "y", "w", "h"), item.getJSONObject("cell").keys().asSequence().toSet())
    }

    @Test fun unreadableIsNull() {
        assertNull(NotebookStore.read(null))
        assertNull(NotebookStore.read(""))
        assertNull(NotebookStore.read("{not json"))
        assertNull(NotebookStore.read("[]"))
        assertNull(NotebookStore.read(Versioned.write(1, JSONObject())))
        assertNull(NotebookStore.read(Versioned.write(1, JSONObject().put("rows", 9))))
        assertNull(NotebookStore.read(Versioned.write(1, JSONObject().put("pages", "x").put("rows", 9))))
    }

    @Test fun aBadEntryIsSkippedNotFatal() {
        val root = JSONObject(NotebookStore.write(notebook))
        val items = root.getJSONObject("data").getJSONArray("pages").getJSONObject(0).getJSONArray("items")
        items.getJSONObject(0).put("kind", "hologram")
        items.put("junk")
        val read = NotebookStore.read(root.toString())!!
        assertEquals(notebook.pages[0].items.drop(1), read.pages[0].items)
        assertEquals(notebook.pages[1], read.pages[1])
    }

    @Test fun sayingHowMuchWasLeftOut() {
        assertEquals(0, NotebookStore.inspect(NotebookStore.write(notebook))!!.skipped)
        assertNull(NotebookStore.inspect("{not json"))
        val root = JSONObject(NotebookStore.write(notebook))
        val data = root.getJSONObject("data")
        val pages = data.getJSONArray("pages")
        val items = pages.getJSONObject(0).getJSONArray("items")
        // An unknown kind, a stray string, an app without its app, an item saved twice, a folder's app without a key,
        // a page saved twice, a page without a name, and a dock entry without a label: eight entries that can't be kept.
        items.getJSONObject(0).put("kind", "hologram")
        items.put("junk")
        items.getJSONObject(1).remove("app")
        items.put(JSONObject(items.getJSONObject(2).toString()))
        items.getJSONObject(3).getJSONArray("apps").getJSONObject(0).remove("key")
        pages.put(JSONObject(pages.getJSONObject(1).toString()))
        pages.put(JSONObject().put("id", "p9"))
        data.getJSONArray("dock").getJSONObject(0).remove("label")
        val read = NotebookStore.inspect(root.toString())!!
        assertEquals(8, read.skipped)
        assertEquals(listOf("p1", "p2"), read.notebook.pages.map { it.id })
        assertEquals(listOf(firefox.key, "folder:a", "folder:none", "unplaced.owl"), read.notebook.pages[0].items.map { it.key })
        assertEquals(listOf(htop), read.notebook.pages[0].items[1].apps)
        assertEquals(listOf(firefox), read.notebook.dock)
        // What is written from it reads whole.
        assertEquals(0, NotebookStore.inspect(NotebookStore.write(read.notebook))!!.skipped)
    }

    /** The layout core is plain Kotlin: nothing from Android or Compose, so JVM tests can run all of it. */
    @Test fun theCoreImportsNothingFromAndroid() {
        // In the shared module since Soma Canvas slice 1, where nothing may import Android at all.
        val files = File(dev.omalauncher.theme.pack.mainSourceRoots().first { File(it, "widgets/layout/Placement.kt").isFile }, "widgets/layout").listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(files.size >= 4)
        for (file in files) {
            val imports = file.readLines().filter { it.startsWith("import ") }
            assertFalse("${file.name}: ${imports.filter { "android" in it }}", imports.any { it.startsWith("import android") })
            // Reading and writing JSON is only for the stores, not for placing or geometry.
            if (file.name in setOf("Placement.kt", "GridGeometry.kt")) assertTrue(file.name, imports.none { "org.json" in it || "upgrade" in it })
        }
    }

    /** The shared module's common code runs on the desktop and the car's renderer too: none of it may import Android. */
    @Test fun sharedCommonCodeImportsNothingFromAndroid() {
        val common = dev.omalauncher.theme.pack.moduleFile("../chat/src/commonMain/kotlin")
        val files = common.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("the shared common code was found at $common", files.size > 20)
        // androidx.compose is Compose Multiplatform and fine; android.* and the Android-only androidx libraries are not.
        val androidOnly = Regex("""^import (android\.|androidx\.(activity|core|appcompat|lifecycle\.Process|work)\b)""")
        val bad = files.filter { f -> f.readLines().any { androidOnly.containsMatchIn(it) } }.map { it.name }
        assertEquals(emptyList<String>(), bad)
    }

    /** A thread's tile (Boss, 2026-10-13) is kept by its key, page and cell, under a kind of its own. */
    @Test fun aThreadTileIsWrittenAndReadBackAsItsOwnKind() {
        val notebook = Notebook(9, listOf(NotebookPage("p1", "Agents", listOf(NotebookItem("thread:mac:t1", ItemKind.Thread, Cell(0, 0, 3, 3)), NotebookItem("thread:phone", ItemKind.Thread, Cell(3, 0, 3, 3))))))
        val text = NotebookStore.write(notebook)
        assertTrue(text.contains("\"kind\":\"thread\""))
        val read = NotebookStore.inspect(text)!!
        assertEquals(notebook, read.notebook)
        assertEquals(0, read.skipped)
    }
}
