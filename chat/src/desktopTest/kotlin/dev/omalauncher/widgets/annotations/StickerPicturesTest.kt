package dev.omalauncher.widgets.annotations

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Stickers that are pictures: how a page keeps one, the room it takes, the library it is picked from and the launcher's copy of it. */
class StickerPicturesTest {
    private val dir = Files.createTempDirectory("stickers").toFile()
    private val library = File(dir, "library").apply { mkdirs() }
    private val pages = File(dir, "annotations")
    private val copies = File(pages, "stickers")

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private val moon = Picture(snapshotName("moon".toByteArray(), "webp"), "moon", 2f)

    private fun pictured(
        aspect: Float,
        size: Float = 0.2f,
    ) = Sticker("s1", PICTURE_KIND, 0.5f, 0.5f, size, picture = moon.copy(aspect = aspect))

    /** A book over [pages] whose pictures all measure [wide] by [high], with everything done at once. */
    private fun book(
        wide: Int = 300,
        high: Int = 100,
    ): AnnotationBook {
        var time = 1_000L
        val book =
            AnnotationBook(
                AnnotationFiles(pages),
                background = { it() },
                foreground = { it() },
                defer = { _, save -> save?.invoke() },
                now = { time++ },
                snapshots = StickerSnapshots(copies, measure = { wide to high }),
            )
        book.keep(setOf("p1", "p2"))
        listOf("p1", "p2").forEach(book::open)
        return book
    }

    private fun art(
        name: String,
        content: String = name,
    ) = File(library, name).apply {
        parentFile?.mkdirs()
        writeText(content)
    }

    // The document.

    @Test fun aPictureStickerReadsBackTheSame() {
        val page = Annotations(stickers = listOf(Sticker("s1", StickerKind.Heart, 0.2f, 0.3f, 0.14f, 15), Sticker("s2", PICTURE_KIND, 0.6f, 0.7f, 0.3f, 345, moon.copy(aspect = 1.333f))))
        val text = AnnotationStore.write(page)
        assertEquals(AnnotationsRead.Read(page), AnnotationStore.read(text))
        assertEquals(text, AnnotationStore.write((AnnotationStore.read(text) as AnnotationsRead.Read).annotations))
        // Still the first version of the document: a built-in sticker is written as it always was, and a picture has no kind.
        val stickers = JSONObject(text).also { assertEquals(1, it.getInt("v")) }.getJSONObject("data").getJSONArray("stickers")
        assertEquals(setOf("id", "k", "x", "y", "s", "r"), stickers.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(setOf("id", "img", "n", "a", "x", "y", "s", "r"), stickers.getJSONObject(1).keys().asSequence().toSet())
        assertEquals(moon.file, stickers.getJSONObject(1).getString("img"))
        assertEquals(1333, stickers.getJSONObject(1).getInt("a"))
    }

    @Test fun aPageSavedBeforeThereWerePicturesStillReads() {
        val old = """{"v":1,"data":{"strokes":[],"notes":[],"stickers":[{"id":"s1","k":"heart","x":5000,"y":2500,"s":1400,"r":15}]}}"""
        assertEquals(AnnotationsRead.Read(Annotations(stickers = listOf(Sticker("s1", StickerKind.Heart, 0.5f, 0.25f, 0.14f, 15)))), AnnotationStore.read(old))
    }

    @Test fun aPictureIsOnlyEverTheNameOfACopy() {
        fun page(sticker: String) = """{"v":1,"data":{"stickers":[{"id":"s1","x":5000,"y":5000,"s":1400,"r":0,$sticker}]}}"""
        assertTrue(AnnotationStore.read(page(""""img":"${moon.file}","n":"moon","a":2000""")) is AnnotationsRead.Read)
        for (bad in listOf(""""img":"../../p1.json","n":"moon","a":2000""", """"img":"moon.webp","n":"moon","a":2000""", """"img":"${moon.file}","a":2000""", """"img":"${moon.file}","n":"moon"""")) {
            assertEquals(bad, AnnotationsRead.Unreadable(AnnotationsRead.Why.Damaged), AnnotationStore.read(page(bad)))
        }
    }

    // The room it takes.

    @Test fun aPictureKeepsItsShapeAndItsSizeIsItsLongerSide() {
        val wide = pictured(2f)
        assertEquals(0.2f, wide.wide, 1e-6f)
        assertEquals(0.1f, wide.high, 1e-6f)
        assertArrayEquals(floatArrayOf(0.4f, 0.45f, 0.6f, 0.55f), wide.box(tall = 1f), 1e-6f)
        val tall = pictured(0.5f)
        assertEquals(0.1f, tall.wide, 1e-6f)
        assertEquals(0.2f, tall.high, 1e-6f)
        assertArrayEquals(floatArrayOf(0.45f, 0.4f, 0.55f, 0.6f), tall.box(tall = 1f), 1e-6f)
        // On the page, which is taller than wide, the same height is a smaller share of it.
        val taken = wide.taken()
        assertEquals(0.2f, taken.w, 1e-6f)
        assertEquals(0.1f / PAGE_TALL, taken.h, 1e-6f)
        assertEquals(0.5f, taken.y + taken.h / 2f, 1e-6f)
        assertEquals(listOf(taken), Annotations(stickers = listOf(wide)).scraps())
        // A built-in sticker is square, as it was.
        val star = Sticker("s", StickerKind.Star, 0.5f, 0.5f, 0.2f)
        assertEquals(star.wide, star.high, 0f)
        assertArrayEquals(floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f), star.box(tall = 1f), 1e-6f)
    }

    @Test fun aFingerBesideAPictureIsNotOnIt() {
        val page = Annotations(stickers = listOf(pictured(2f)))
        assertEquals("s1", page.scrapAt(0.58f, 0.54f, tall = 1f)?.id)
        // Under it, where a square sticker of its size would still be.
        assertNull(page.scrapAt(0.5f, 0.58f, tall = 1f))
        assertEquals("s1", Annotations(stickers = listOf(pictured(1f))).scrapAt(0.5f, 0.58f, tall = 1f)?.id)
    }

    @Test fun aPictureStickerIsKeptWithinBounds() {
        val fitted = Sticker("s1", StickerKind.Heart, 1.4f, -1f, 0.9f, 375, Picture(moon.file, "  full\nmoon ", 1.33333f)).fitted()
        assertEquals(Sticker("s1", PICTURE_KIND, 1f, 0f, AnnotationLimits.MAX_STICKER, 15, Picture(moon.file, "full moon", 1.333f)), fitted)
        assertEquals(fitted, fitted.fitted())
        // A strip is drawn whole inside the longest shape a sticker has; no shape at all is a square.
        assertEquals(PICTURE_SKEW, moon.copy(aspect = 100f).fitted().aspect, 0f)
        assertEquals(1f / PICTURE_SKEW, moon.copy(aspect = 0.001f).fitted().aspect, 0f)
        assertEquals(1f, moon.copy(aspect = Float.NaN).fitted().aspect, 0f)
        assertEquals(PICTURE_NAME, moon.copy(name = "m".repeat(200)).fitted().name.length)
        // Resized, it is as much wider than tall as before.
        val grown = pictured(2f).copy(size = 0.4f).fitted()
        assertEquals(2f, grown.wide / grown.high, 1e-6f)
    }

    @Test fun aPictureStickerCarriedToAnotherPageIsStillThatPicture() {
        val from = Annotations(stickers = listOf(Sticker("s1", PICTURE_KIND, 0.5f, 0.5f, 0.14f, turn = 30, picture = moon)))
        val crossed = carryScrap(from, Annotations(), "s1", 0.25f, 0.75f, now = 5)!!
        assertTrue(crossed.from.stickers.isEmpty())
        assertEquals(Sticker("s1", PICTURE_KIND, 0.25f, 0.75f, 0.14f, turn = 30, picture = moon), crossed.to.stickers.single())
    }

    // The launcher's copies.

    @Test fun aCopyIsNamedFromWhatIsInIt() {
        val name = snapshotName("moon".toByteArray(), "png")
        assertTrue(name, isSnapshotName(name))
        assertEquals(name, snapshotName("moon".toByteArray(), "png"))
        assertNotEquals(name, snapshotName("moom".toByteArray(), "png"))
        assertEquals(name.substringBefore('.') + ".gif", snapshotName("moon".toByteArray(), "gif"))
        // Its ending is the kind of image the file says it is, however that is spelled.
        assertEquals(listOf("png", "webp", "jpg", "jpg", "gif"), listOf("a.png", "a.b.WebP", "a.JPG", "a.jpeg", "a.gif").map(::pictureEnding))
        assertEquals(listOf(null, null, null, null), listOf("a.svg", "png", "a.png.txt", "").map(::pictureEnding))
        for (other in listOf("moon.png", "../$name", "$name.json", name.uppercase(), "p1.json")) assertFalse(other, isSnapshotName(other))
    }

    @Test fun onlyCopiesNothingSpeaksOfAreUnused() {
        val a = snapshotName("a".toByteArray(), "png")
        val b = snapshotName("b".toByteArray(), "jpg")
        assertEquals(setOf(b), unusedSnapshots(listOf(a, b, "notes.txt", ".$a.tmp", "p1.json"), setOf(a)))
        assertEquals(emptySet<String>(), unusedSnapshots(listOf(a, b), setOf(a, b, "gone")))
        assertEquals(setOf(a, b), unusedSnapshots(listOf(a, b), emptySet()))
        // What a page's file speaks of is found in it as text, whatever else is in it.
        val page = AnnotationStore.write(Annotations(stickers = listOf(pictured(2f).copy(picture = moon.copy(file = a)), Sticker("s2", StickerKind.Star, 0.1f, 0.1f, 0.14f))))
        assertEquals(setOf(a), snapshotsNamed(page))
        assertEquals(setOf(a, b), snapshotsNamed("{ cut short \"$a\" and $b"))
        assertEquals(emptySet<String>(), snapshotsNamed(AnnotationStore.write(Annotations())))
    }

    @Test fun stickingAPictureCopiesItSoTheLibraryCanChange() {
        val snapshots = StickerSnapshots(copies, measure = { 200 to 100 })
        val file = art("cove/cat.png", "a cat")
        val cat = snapshots.take(file, "cat")!!
        assertEquals(Picture(snapshotName("a cat".toByteArray(), "png"), "cat", 2f), cat)
        assertEquals("a cat", snapshots.file(cat.file).readText())
        // The art is replaced, then deleted: what was stuck is as it was, and the new art is another copy.
        file.writeText("a better cat")
        val better = snapshots.take(file, "cat")!!
        assertNotEquals(cat.file, better.file)
        file.delete()
        assertEquals("a cat", snapshots.file(cat.file).readText())
        assertNull(snapshots.take(file, "cat"))
        // The same picture from another file is the same copy.
        assertEquals(cat.file, snapshots.take(art("again.png", "a cat"), "again")!!.file)
        assertEquals(setOf(cat.file, better.file), copies.list()!!.toSet())
    }

    @Test fun whatIsNoPictureIsNotCopied() {
        val none = StickerSnapshots(copies, measure = { null })
        assertNull(none.take(art("broken.png"), "broken"))
        val snapshots = StickerSnapshots(copies, measure = { 10 to 10 })
        assertNull(snapshots.take(art("notes.txt"), "notes"))
        assertNull(snapshots.take(art("empty.png", ""), "empty"))
        assertNull(snapshots.take(File(library, "cove").apply { mkdirs() }, "cove"))
        val big = File(library, "big.png").apply { writeBytes(ByteArray(StickerLimits.FILE_BYTES.toInt() + 1)) }
        assertNull(snapshots.take(big, "big"))
        assertNull(StickerSnapshots(copies, measure = { error("not an image") }).take(art("bad.gif"), "bad"))
        assertTrue(copies.list().orEmpty().isEmpty())
    }

    // The library.

    @Test fun theLibraryIsItsPicturesAndThoseOneFolderDown() {
        val files =
            listOf(
                LibraryFile("star.png", 10, 7),
                LibraryFile("cove/moon.webp", 20),
                LibraryFile("cove/Sun.JPG", 30),
                LibraryFile("art/b.jpeg", 1),
                LibraryFile("anim.gif", 9),
                // Not pictures, hidden, too deep, empty or too big.
                LibraryFile("notes.txt", 10),
                LibraryFile("cove/readme", 10),
                LibraryFile(".hidden.png", 10),
                LibraryFile(".git/x.png", 10),
                LibraryFile("cove/deep/x.png", 10),
                LibraryFile("empty.png", 0),
                LibraryFile("big.gif", StickerLimits.FILE_BYTES + 1),
                LibraryFile("cove//x.png", 10),
            )
        val groups = libraryStickers(files)
        assertEquals(listOf("", "art", "cove"), groups.map { it.name })
        assertEquals(listOf("anim.gif", "star.png"), groups[0].stickers.map { it.path })
        assertEquals(listOf("b"), groups[1].stickers.map { it.name })
        assertEquals(listOf("moon" to "cove/moon.webp", "Sun" to "cove/Sun.JPG"), groups[2].stickers.map { it.name to it.path })
        assertTrue(groups[2].stickers.all { it.group == "cove" })
        // A file as big as is taken is taken, and one written again is not the one it was.
        assertEquals(1, libraryStickers(listOf(LibraryFile("edge.png", StickerLimits.FILE_BYTES))).single().stickers.size)
        assertNotEquals(groups[0].stickers[1].stamp, libraryStickers(listOf(LibraryFile("star.png", 10, 8))).single().stickers.single().stamp)
        // However many there are, only so many are offered.
        val many = libraryStickers((1..StickerLimits.LIBRARY + 50).map { LibraryFile("g${it % 3}/s$it.png", 10) })
        assertEquals(StickerLimits.LIBRARY, many.sumOf { it.stickers.size })
        assertEquals(emptyList<StickerGroup>(), libraryStickers(emptyList()))
    }

    @Test fun theLibraryFolderIsListedOneFolderDeep() {
        art("star.png")
        art("cove/moon.webp")
        art("cove/deep/x.png")
        assertEquals(setOf("star.png", "cove/moon.webp"), listLibrary(library).map { it.path }.toSet())
        assertEquals(listOf("", "cove"), libraryStickers(listLibrary(library)).map { it.name })
        assertEquals(emptyList<LibraryFile>(), listLibrary(File(library, "none")))
    }

    // The book.

    @Test fun aPictureIsStuckInItsOwnShapeAndGoesFromPageToPage() {
        val book = book()
        var stuck: String? = null
        book.stick("p1", StickerChoice.Pictured(art("cove/cat.png", "a cat"), "cat"), 0.5f to 0.5f) { stuck = it }
        val sticker = book.on("p1").stickers.single()
        assertEquals(stuck, sticker.id)
        assertEquals(Picture(snapshotName("a cat".toByteArray(), "png"), "cat", 3f), sticker.picture)
        // As wide as any new sticker and a third as tall, with its middle where he pressed.
        assertEquals(STICKER_SIZE, sticker.wide, 1e-6f)
        assertEquals(STICKER_SIZE / 3f, sticker.high, 1e-6f)
        assertEquals(0.5f, sticker.x, 1f / UNITS)
        assertEquals(0.5f, sticker.y, 1f / UNITS)
        // Carried to another page it is the same picture, from the same one copy.
        val there = book.moveScrap("p1", "p2", sticker.id, 0.3f, 0.3f)!!
        assertTrue(book.on("p1").stickers.isEmpty())
        assertEquals(sticker.picture, book.on("p2").stickers.single { it.id == there }.picture)
        assertEquals(listOf(sticker.picture!!.file), copies.list()!!.toList())
        // The copies' folder is beside the pages' files and is none of them.
        assertEquals(setOf("p1", "p2"), AnnotationFiles(pages).pages())
        assertEquals(setOf(sticker.picture!!.file), AnnotationFiles(pages).pictures())
        // A built-in sticker is stuck the same way, at once.
        book.stick("p1", StickerChoice.BuiltIn(StickerKind.Bolt)) { stuck = it }
        assertEquals(StickerKind.Bolt, book.on("p1").stickers.single { it.id == stuck }.kind)
    }

    @Test fun aPictureThatCannotBeReadIsNotStuckAndAFullPageTakesNone() {
        val book = book()
        var answers = 0
        book.stick("p1", StickerChoice.Pictured(File(library, "gone.png"), "gone")) { assertNull(it).also { answers++ } }
        assertEquals(AnnotationBook.NO_PICTURE, book.notice)
        repeat(AnnotationLimits.STICKERS) { book.addSticker("p1", StickerKind.Star) }
        book.stick("p1", StickerChoice.Pictured(art("cat.png"), "cat")) { assertNull(it).also { answers++ } }
        assertEquals(AnnotationBook.NO_ROOM_STICKER, book.notice)
        // Without anywhere to keep copies no picture is stuck, and a session that isn't kept takes none either.
        val bare = AnnotationBook(AnnotationFiles(pages), background = { it() }, foreground = { it() }, defer = { _, save -> save?.invoke() })
        bare.keep(setOf("p2"))
        bare.open("p2")
        bare.stick("p2", StickerChoice.Pictured(art("cat.png"), "cat")) { assertNull(it).also { answers++ } }
        assertEquals(3, answers)
        assertTrue(copies.list().orEmpty().isEmpty())
    }

    @Test fun aCopyGoesOnlyOnceNoPageSpeaksOfIt() {
        val first = book()
        var cat: String? = null
        var dog: String? = null
        first.stick("p1", StickerChoice.Pictured(art("cat.png"), "cat")) { cat = it }
        first.stick("p2", StickerChoice.Pictured(art("dog.png"), "dog")) { dog = it }
        val kept = first.on("p1").stickers.single().picture!!.file
        val dropped = first.on("p2").stickers.single().picture!!.file
        // Taken off its page, its copy stays for the rest of the session and goes at the next start; the other's stays.
        first.remove("p2", dog!!)
        assertEquals(setOf(kept, dropped), copies.list()!!.toSet())
        book()
        assertEquals(listOf(kept), copies.list()!!.toList())
        // A page that was deleted is set aside with its sticker, and so its copy is kept.
        val second = book()
        second.pageGone("p1")
        assertFalse(AnnotationFiles(pages).file("p1").exists())
        book()
        assertEquals(listOf(kept), copies.list()!!.toList())
        assertEquals(cat, (AnnotationStore.read(File(pages, AnnotationFiles.REMOVED).listFiles()!!.single().readText()) as AnnotationsRead.Read).annotations.stickers.single().id)
    }

    @Test fun aPageThatCannotBeReadStillKeepsItsPictures() {
        val first = book()
        first.stick("p1", StickerChoice.Pictured(art("cat.png"), "cat")) {}
        val copy = first.on("p1").stickers.single().picture!!.file
        // Its file is cut short: nothing of it shows, and what it speaks of is still found in it.
        val file = AnnotationFiles(pages).file("p1")
        file.writeText(file.readText().dropLast(4))
        val second = book()
        assertEquals(AnnotationBook.Status.Unreadable, second.status("p1"))
        assertEquals(listOf(copy), copies.list()!!.toList())
        // A file too big to look at says nothing either way, so nothing at all is deleted.
        file.delete()
        File(pages, "p2.json").writeBytes(ByteArray(AnnotationLimits.FILE_BYTES.toInt() + 1))
        assertNull(AnnotationFiles(pages).pictures())
        book()
        assertEquals(listOf(copy), copies.list()!!.toList())
    }
}
