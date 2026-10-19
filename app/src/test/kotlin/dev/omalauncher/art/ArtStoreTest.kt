package dev.omalauncher.art

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class ArtStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 10L * ArtStore.GRACE_MILLIS
    private val dir get() = File(tmp.root, "art")

    private fun store(bounds: (File, String) -> Pair<Int, Int>? = { _, _ -> 800 to 600 }) = ArtStore(dir, null, { false }, bounds) { clock }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40) { 7 }
    private val jpg = bytes(0xFF, 0xD8, 0xFF, 0xE0) + ByteArray(40) { 1 }
    private val gif = ascii("GIF89a") + ByteArray(40) { 2 }
    private val gif87 = ascii("GIF87a") + ByteArray(40) { 2 }
    private val webp = ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBPVP8X") + ByteArray(30)
    private val heic = bytes(0, 0, 0, 0x18) + ascii("ftypheic") + ByteArray(30)
    private val avif = bytes(0, 0, 0, 0x1C) + ascii("ftypavif") + ByteArray(30)

    private fun ArtStore.take(content: ByteArray) = import { content.inputStream() }

    private fun refused(
        store: ArtStore,
        open: () -> InputStream?,
    ): String {
        try {
            store.import(open)
        } catch (e: ArtStore.Refused) {
            return e.message.orEmpty()
        }
        fail("expected a refusal")
        error("unreachable")
    }

    /** Only finished art files: no temp file is left behind by an import. */
    private fun leftovers() = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun importsEachImageKindUnderItsExtension() {
        val store = store()
        for ((content, ext) in listOf(png to "png", jpg to "jpg", gif to "gif", gif87 to "gif", webp to "webp", heic to "heic", avif to "avif")) {
            val id = store.take(content)
            assertTrue(id, ArtStore.isId(id))
            val file = store.file(id)!!
            assertEquals("$id.$ext", file.name)
            assertEquals(dir, file.parentFile)
            assertArrayEquals(content, file.readBytes())
        }
        assertEquals(7, store.ids().size)
        assertEquals(7, leftovers().size)
    }

    @Test fun idsAreSixteenLowercaseHexAndDistinct() {
        val store = store()
        val ids = (1..50).map { store.take(png) }
        assertEquals(50, ids.toSet().size)
        ids.forEach { assertTrue(it, Regex("[0-9a-f]{16}").matches(it)) }
        assertEquals(ids.toSet(), store.ids())
    }

    @Test fun refusesWhatIsNotAPicture() {
        val store = store()
        assertTrue(refused(store) { "hello, this is text".byteInputStream() }.contains("isn't a picture"))
        assertTrue(refused(store) { ascii("%PDF-1.7\n").inputStream() }.contains("isn't a picture"))
        // A RIFF that isn't WebP (a WAV), and an ISO box that isn't HEIF (an MP4).
        assertTrue(refused(store) { (ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WAVEfmt ")).inputStream() }.contains("isn't a picture"))
        assertTrue(refused(store) { (bytes(0, 0, 0, 0x18) + ascii("ftypisom") + ByteArray(20)).inputStream() }.contains("isn't a picture"))
        // The magic of PNG cut short.
        assertTrue(refused(store) { bytes(0x89, 0x50, 0x4E).inputStream() }.contains("isn't a picture"))
        assertTrue(refused(store) { ByteArray(0).inputStream() }.contains("empty"))
        assertTrue(store.ids().isEmpty())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun refusesWhatThePlatformCannotDecode() {
        val store = store(bounds = { _, _ -> null })
        assertTrue(refused(store) { png.inputStream() }.contains("can't be opened"))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun refusesAnAnimationWhoseFramesWouldNotFitInMemory() {
        // A small file can say it is 20000 pixels square; every frame of an animation is decoded at that size.
        val store = store(bounds = { _, ext -> if (ext == "gif" || ext == "webp") 20000 to 20000 else 9000 to 9000 })
        assertTrue(refused(store) { gif.inputStream() }.contains("too big"))
        assertTrue(refused(store) { webp.inputStream() }.contains("too big"))
        // A big still photo is decoded scaled down, so it is taken.
        assertTrue(ArtStore.isId(store.take(jpg)))
        assertEquals(1, leftovers().size)
    }

    @Test fun fitsBoundsStillsAndFramesSeparately() {
        assertTrue(ArtStore.fits("gif", 4000, 4000))
        assertFalse(ArtStore.fits("gif", 4001, 4000))
        assertFalse(ArtStore.fits("webp", 8000, 8000))
        assertTrue(ArtStore.fits("jpg", 8160, 6120))
        assertTrue(ArtStore.fits("heic", 12000, 9000))
        assertFalse(ArtStore.fits("png", 20000, 20000))
        assertFalse(ArtStore.fits("png", 0, 10))
        assertFalse(ArtStore.fits("png", 10, -1))
    }

    @Test fun refusesOverTwentyMegabytesWithoutReadingItAll() {
        var read = 0L
        val huge =
            object : InputStream() {
                override fun read(): Int = error("read in blocks")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (read == 0L) png.copyInto(b, off)
                    read += len
                    return len
                }
            }
        val store = store()
        assertTrue(refused(store) { huge }.contains("20 MB"))
        assertTrue("stopped near the cap, read $read", read < ArtStore.MAX_BYTES + 1024 * 1024)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun takesExactlyTwentyMegabytes() {
        val content = png + ByteArray((ArtStore.MAX_BYTES - png.size).toInt())
        val id = store().take(content)
        assertEquals(ArtStore.MAX_BYTES, store().file(id)!!.length())
    }

    @Test fun refusesWhatCannotBeOpenedOrRead() {
        val store = store()
        assertTrue(refused(store) { null }.contains("can't be read"))
        assertTrue(refused(store) { throw SecurityException("no grant") }.contains("can't be read"))
        val broken =
            object : InputStream() {
                override fun read(): Int = throw java.io.IOException("gone")
            }
        assertTrue(refused(store) { broken }.contains("can't be read"))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun fileRefusesWhatIsNotAnId() {
        val store = store()
        val id = store.take(png)
        File(dir, "notes.txt").writeText("x")
        assertNull(store.file("../art/$id"))
        assertNull(store.file(id.uppercase()))
        assertNull(store.file(id.dropLast(1)))
        assertNull(store.file("notes"))
        assertNull(store.file("0123456789abcdef"))
        assertEquals(setOf(id), store.ids())
    }

    @Test fun removeDeletesOnlyThatArt() {
        val store = store()
        val a = store.take(png)
        val b = store.take(gif)
        assertTrue(store.remove(a))
        assertFalse(store.remove(a))
        assertNull(store.file(a))
        assertEquals(setOf(b), store.ids())
    }

    @Test fun sweepKeepsReferencedAndFreshArt() {
        val store = store()
        val kept = store.take(png)
        val gone = store.take(jpg)
        val alsoGone = store.take(webp)
        File(dir, "notes.txt").writeText("not art")
        dir.listFiles()!!.forEach { it.setLastModified(clock - ArtStore.GRACE_MILLIS - 1000) }
        val fresh = store.take(gif)
        File(dir, "$fresh.gif").setLastModified(clock)

        assertEquals(setOf(gone, alsoGone), store.sweep(setOf(kept, "ffffffffffffffff")))
        assertEquals(setOf(kept, fresh), store.ids())
        assertTrue(File(dir, "notes.txt").exists())

        // Once it is old enough, unreferenced fresh art goes too.
        clock += ArtStore.GRACE_MILLIS + 1
        assertEquals(setOf(fresh), store.sweep(setOf(kept)))
        assertEquals(setOf(kept), store.ids())
        assertEquals(emptySet<String>(), store.sweep(setOf(kept)))
    }

    @Test fun sweepOfAnEmptyOrMissingStoreDoesNothing() {
        assertEquals(emptySet<String>(), store().sweep(emptySet()))
        assertTrue(store().ids().isEmpty())
    }

    @Test fun clearsTempFilesOfImportsThatDied() {
        dir.mkdirs()
        val stale = File(dir, ".import-0011223344556677").apply { writeText("half") }
        stale.setLastModified(clock - ArtStore.GRACE_MILLIS - 1)
        val live = File(dir, ".import-8899aabbccddeeff").apply { writeText("still coming") }
        live.setLastModified(clock)
        store().sweep(emptySet())
        assertFalse(stale.exists())
        assertTrue(live.exists())
    }

    @Test fun sniffNamesOnlyImages() {
        assertEquals("png", ArtStore.sniff(png))
        assertEquals("jpg", ArtStore.sniff(jpg))
        assertEquals("gif", ArtStore.sniff(gif))
        assertEquals("webp", ArtStore.sniff(webp))
        assertEquals("heic", ArtStore.sniff(heic))
        assertEquals("avif", ArtStore.sniff(avif))
        assertNull(ArtStore.sniff(ByteArray(0)))
        assertNull(ArtStore.sniff(ascii("GIF8")))
        assertNull(ArtStore.sniff(ascii("<svg xmlns=")))
    }
}
