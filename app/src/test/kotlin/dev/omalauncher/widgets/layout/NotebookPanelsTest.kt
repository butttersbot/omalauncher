package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notebook's Manga fields: a page's panel layout and art items, read and written alongside everything else. */
class NotebookPanelsTest {
    private val art = artKey("0123456789abcdef")
    private val manga = Notebook(
        rows = 10,
        pages = listOf(
            NotebookPage(
                "p1", "Today",
                listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 6, 4)), NotebookItem(art, ItemKind.Art, Cell(0, 4, 3, 2))),
                panels = "opening",
            ),
            NotebookPage("p2", "Later", listOf(NotebookItem("module:needs", ItemKind.Module, Cell(0, 0, 3, 2)))),
        ),
    )

    private fun data(text: String) = JSONObject(text).getJSONObject("data")

    @Test fun panelsAndArtRoundTrip() {
        assertEquals(manga, NotebookStore.read(NotebookStore.write(manga)))
        val pages = data(NotebookStore.write(manga)).getJSONArray("pages")
        assertEquals("opening", pages.getJSONObject(0).getString("panels"))
        assertEquals("art", pages.getJSONObject(0).getJSONArray("items").getJSONObject(1).getString("kind"))
        assertEquals(art, pages.getJSONObject(0).getJSONArray("items").getJSONObject(1).getString("key"))
    }

    @Test fun aFreePageWritesNoPanelsAndReadsAsBefore() {
        val pages = data(NotebookStore.write(manga)).getJSONArray("pages")
        assertFalse(pages.getJSONObject(1).has("panels"))
        val plain = manga.copy(pages = manga.pages.map { it.copy(panels = null, items = it.items.filter { i -> i.kind != ItemKind.Art }) })
        assertFalse(NotebookStore.write(plain).contains("panels"))
        assertEquals(plain, NotebookStore.read(NotebookStore.write(plain)))
    }

    @Test fun aDocumentFromBeforeTheMangaLookStillLoads() {
        val old = """{"v":1,"data":{"columns":6,"rows":9,"pages":[{"id":"p1","name":"Page 1","items":[
            {"key":"clock.owl","kind":"widget","cell":{"x":0,"y":0,"w":3,"h":2}},
            {"key":"module:needs","kind":"widget","cell":{"x":3,"y":0,"w":3,"h":2}}]}],"dock":[]}}"""
        val read = NotebookStore.inspect(old)!!
        assertEquals(0, read.skipped)
        assertNull(read.notebook.pages.single().panels)
        assertEquals(listOf(ItemKind.Widget, ItemKind.Module), read.notebook.pages.single().items.map { it.kind })
    }

    @Test fun anUnknownLayoutIsTheFreeGridWithItsCellsKept() {
        val text = NotebookStore.write(manga).replace("\"opening\"", "\"collage\"")
        val read = NotebookStore.inspect(text)!!
        assertEquals(0, read.skipped)
        assertNull(read.notebook.pages[0].panels)
        assertEquals(manga.pages[0].items, read.notebook.pages[0].items)
    }

    @Test fun artWithoutAnArtIdIsNoEntry() {
        for (bad in listOf("art:", "art:0123", "art:0123456789ABCDEF", "art:0123456789abcdeg", "clock.owl")) {
            val text = NotebookStore.write(manga).replace(art, bad)
            val read = NotebookStore.inspect(text)!!
            assertEquals(bad, 1, read.skipped)
            assertTrue(bad, read.notebook.pages[0].items.none { it.kind == ItemKind.Art })
        }
    }

    @Test fun artKeysAndIds() {
        assertEquals("art:0123456789abcdef", art)
        assertTrue(art.startsWith(ART_PREFIX))
        assertEquals("0123456789abcdef", artIdOf(art))
        assertNull(artIdOf("0123456789abcdef"))
        assertNull(artIdOf("art:xyz"))
        assertNull(artIdOf("module:art:0123456789abcdef"))
    }
}
