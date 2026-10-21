package dev.omalauncher.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The keyboard's GIFs: KLIPY's answers read, hearts and recents kept, and the grid's columns. */
class GifSourcesTest {
    private val answer =
        """{"results":[
          {"id":"61","title":"Laughing LOL","media_formats":{
            "gif":{"url":"https://static.klipy.com/a/full.gif","dims":[220,164]},
            "tinygif":{"url":"https://static.klipy.com/a/tiny.gif","dims":[110,82]}}},
          {"id":"62","title":"no pictures"},
          {"id":"63","title":"Tall","media_formats":{"nanogif":{"url":"https://static.klipy.com/b/nano.gif","dims":[50,200]}}}
        ],"next":"Mg=="}"""

    @Test fun klipysAnswerIsReadAsGifs() {
        val page = Klipy.parse(answer)
        assertEquals(listOf("klipy:61", "klipy:63"), page.items.map { it.id })
        val lol = page.items[0]
        assertEquals("https://static.klipy.com/a/tiny.gif", lol.preview)
        assertEquals("https://static.klipy.com/a/full.gif", lol.full)
        assertEquals(110f / 82f, lol.ratio, 0.001f)
        assertFalse(lol.local)
        // With only a small copy, it is placed as that; a very tall one is kept to a sane shape.
        assertEquals("https://static.klipy.com/b/nano.gif", page.items[1].full)
        assertEquals(0.4f, page.items[1].ratio, 0.001f)
        assertEquals("Mg==", page.next)
        assertNull(Klipy.parse("""{"results":[],"next":"0"}""").next)
    }

    @Test fun heartsAndRecentsNewestFirstAndKept() {
        val f = File.createTempFile("gifs", ".json").also { it.delete() }
        val a = GifItem("klipy:1", "lol", "p1", "f1", 1f, false)
        val b = GifItem("klipy:2", "yes", "p2", "f2", 1.3f, false)
        val mine = GifItem("local:content://x/9", "beach.gif", "content://x/9", "content://x/9", 1f, true, link = "")
        GifStore(f).apply {
            assertTrue(toggleHeart(a))
            assertTrue(toggleHeart(b))
            placed(a)
            placed(mine)
            placed(a)
        }
        val back = GifStore(f)
        assertEquals(listOf("klipy:2", "klipy:1"), back.hearted().map { it.id })
        assertEquals(listOf("klipy:1", "local:content://x/9"), back.recent().map { it.id })
        assertTrue(back.recent()[1].local)
        assertFalse(back.toggleHeart(a))
        assertFalse(back.isHearted("klipy:1"))
        repeat(40) { back.placed(GifItem("klipy:r$it", "", "p", "f", 1f, false)) }
        assertEquals(GifStore.RECENT_KEPT, back.recent().size)
        f.delete()
    }

    @Test fun eachTileGoesUnderTheShortestColumn() {
        // Two columns 100 wide: a tall one, then two short ones that both go to the other side.
        val (cells, height) = Masonry.lay(listOf(0.5f, 2f, 2f, 1f), columns = 2, width = 100f, gap = 10f)
        assertEquals(listOf(0, 1, 1, 1), cells.map { it.column })
        assertEquals(listOf(0f, 0f, 60f, 120f), cells.map { it.top })
        assertEquals(230f, height, 0.01f)
    }

    @Test fun tagsAddedRemovedMovedAndKept() {
        val f = File.createTempFile("gifs", ".json").also { it.delete() }
        GifStore(f).apply {
            assertEquals(GifStore.DEFAULT_TAGS, tags())
            assertTrue(addTag(" Bruh "))
            // Once only, and never one of the fixed pills.
            assertFalse(addTag("bruh"))
            assertFalse(addTag("Trending"))
            assertFalse(addTag("  "))
            removeTag("no")
            moveTag(tags().indexOf("bruh"), 0)
            tagsOpen = true
        }
        val back = GifStore(f)
        assertEquals(listOf("bruh", "lol", "yes", "love", "hype", "facepalm", "gn"), back.tags())
        assertTrue(back.tagsOpen)
        back.moveTag(0, 2)
        assertEquals(listOf("lol", "yes", "bruh"), back.tags().take(3))
        f.delete()
    }
}
