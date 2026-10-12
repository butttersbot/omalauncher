package dev.omalauncher.theme.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * A pack that carries a wallpaper for each display (PRD AC-20). No shipped look has image files yet,
 * so the path is walked here with a pack made on the spot: two real PNGs of a few pixels, read back
 * through the same loader, size limit and picking rules the launcher uses. Decoding to a bitmap and
 * drawing are Android's and are not reached from here.
 */
class PackWallpaperTest {
    /** A [width] by [height] PNG, each pixel its own color so a crop can be told from the whole. */
    private fun png(
        file: File,
        width: Int,
        height: Int,
    ) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 40 shl 16) or (y * 40 shl 8) or 0x80)
        file.parentFile?.mkdirs()
        assertTrue(ImageIO.write(image, "png", file))
    }

    private fun withPack(
        manifest: String,
        files: Map<String, Pair<Int, Int>>,
        check: (PackSource, PackLoad) -> Unit,
    ) {
        val dir = Files.createTempDirectory("pack-wallpaper").toFile()
        try {
            File(dir, PackLoader.MANIFEST).writeText(manifest)
            for ((path, size) in files) png(File(dir, path), size.first, size.second)
            val source = DirectoryPackSource(dir)
            check(source, PackLoader.load(source, "walled"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private val both =
        """
        [pack]
        format = 1
        name = "Walled"

        [page]
        kind = "wallpaper"
        color = "#0f4fc9"
        wallpaper_cover = "paper/cover.png"
        wallpaper_inner = "paper/inner.png"
        """.trimIndent()

    @Test fun aPackCarriesOneImageForEachDisplay() {
        withPack(both, mapOf("paper/cover.png" to (3 to 5), "paper/inner.png" to (6 to 5))) { source, load ->
            assertNull(load.error)
            assertEquals(emptyList<PackIssue>(), load.issues.filter { it.kind != PackIssue.Kind.Missing })
            assertFalse(load.broken)
            val page = load.pack.page
            assertEquals(PageKind.Wallpaper, page.kind)
            assertEquals("paper/cover.png", page.wallpaperCover)
            assertEquals("paper/inner.png", page.wallpaperInner)
            // The paper shows until an image is decoded, and under one that can't be.
            assertEquals(PackColor.parse("#0f4fc9"), page.color)

            // Each file is read whole, within an image's limit, and is the picture that was put there.
            val sizes =
                listOf(page.wallpaperCover, page.wallpaperInner).map { path ->
                    val bytes = readCapped(source, path, AssetType.Image.maxBytes)
                    assertNotNull(path, bytes)
                    val image = ImageIO.read(bytes!!.inputStream())
                    assertNotNull(path, image)
                    image.width to image.height
                }
            assertEquals(listOf(3 to 5, 6 to 5), sizes)
            // Held to a limit smaller than the file, it isn't read at all.
            assertNull(readCapped(source, page.wallpaperCover, 8))

            // Folded shows the cover's and unfolded the inner's, whichever way up; the Fold's two displays in dp.
            val has = page.wallpaperCover.isNotEmpty() to page.wallpaperInner.isNotEmpty()
            assertEquals("cover", wallpaperSide(416f, 657f, has.first, has.second))
            assertEquals("cover", wallpaperSide(657f, 416f, has.first, has.second))
            assertEquals("inner", wallpaperSide(816f, 616f, has.first, has.second))
            assertEquals("inner", wallpaperSide(616f, 816f, has.first, has.second))

            // And the one shown covers its display once: a single piece of the image, cut evenly, never repeated or halved.
            for ((image, display) in listOf((3 to 5) to (1248 to 1972), (6 to 5) to (2448 to 1848), (6 to 5) to (1224 to 1848))) {
                val (x, y, w, h) = coverCrop(image.first, image.second, display.first, display.second)!!
                assertTrue("$image on $display", x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= image.first && y + h <= image.second)
                // One side of the image is used in full.
                assertTrue("$image on $display", w == image.first || h == image.second)
            }
            // An inner image on the whole unfolded display is not the half a single page would take.
            assertFalse(coverCrop(6, 5, 2448, 1848) == coverCrop(6, 5, 1224, 1848))
        }
    }

    /** PRD UP-10: a wallpaper the manifest names but the folder lacks falls back alone; the other still shows, on both displays. */
    @Test fun aMissingWallpaperLeavesTheOtherOnBothDisplays() {
        withPack(both, mapOf("paper/inner.png" to (6 to 5))) { source, load ->
            assertNull(load.error)
            assertEquals(mapOf("page.wallpaper_cover" to PackIssue.Kind.FileMissing), load.issues.filter { it.kind != PackIssue.Kind.Missing }.associate { it.token to it.kind })
            val page = load.pack.page
            assertEquals("", page.wallpaperCover)
            assertEquals("paper/inner.png", page.wallpaperInner)
            assertNull(readCapped(source, "paper/cover.png", AssetType.Image.maxBytes))
            assertEquals("inner", wallpaperSide(416f, 657f, hasCover = false, hasInner = true))
            assertEquals("inner", wallpaperSide(816f, 616f, hasCover = false, hasInner = true))
        }
    }

    /** A file that isn't an image by its name is refused, and one outside the pack is never opened. */
    @Test fun onlyAnImageInsideThePackIsAWallpaper() {
        val manifest = both.replace("paper/cover.png", "paper/cover.txt").replace("paper/inner.png", "../inner.png")
        withPack(manifest, emptyMap()) { _, load ->
            assertNull(load.error)
            assertEquals("", load.pack.page.wallpaperCover + load.pack.page.wallpaperInner)
            assertTrue(load.broken)
        }
    }

    /** None of the looks shipped in the app names a wallpaper: there are no image files to name. */
    @Test fun noShippedLookNamesAWallpaperYet() {
        val packs = moduleFile("src/main/assets/packs").listFiles().orEmpty().filter { it.isDirectory }
        assertEquals(9, packs.size)
        for (dir in packs) {
            // Beside the manifest a look carries only its fonts; Console carries nothing.
            val others = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.filter { it != PackLoader.MANIFEST }.toList()
            assertEquals(dir.name, emptyList<String>(), others.filterNot { it.startsWith("fonts/") && it.endsWith(".ttf") })
            if (dir.name == ConsolePack.ID) {
                assertEquals(emptyList<String>(), others)
                continue
            }
            val page = PackLoader.load(DirectoryPackSource(dir), dir.name).pack.page
            assertEquals(dir.name, "", page.wallpaperCover + page.wallpaperInner)
            assertEquals(dir.name, PageKind.Paper, page.kind)
        }
    }
}
