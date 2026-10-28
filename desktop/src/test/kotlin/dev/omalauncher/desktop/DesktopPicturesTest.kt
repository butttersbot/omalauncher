package dev.omalauncher.desktop

import dev.omalauncher.oma.ChatDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files

class DesktopPicturesTest {
    private val dir = Files.createTempDirectory("soma-pictures").toFile()

    private fun png(name: String = "shot.png", w: Int = 400, h: Int = 200) = File(dir, name).also { javax.imageio.ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }

    private fun transfer(flavor: DataFlavor, data: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(flavor)
        override fun isDataFlavorSupported(f: DataFlavor) = f == flavor
        override fun getTransferData(f: DataFlavor) = data
    }

    @Test fun aPictureIsKnownByItsBytesNotItsName() {
        assertEquals("png", pictureKind(png("looks-like.txt")))
        File(dir, "a.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        assertEquals("jpg", pictureKind(File(dir, "a.jpg")))
        File(dir, "a.gif").writeText("GIF89a....")
        assertEquals("gif", pictureKind(File(dir, "a.gif")))
        File(dir, "a.webp").writeBytes("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))
        assertEquals("webp", pictureKind(File(dir, "a.webp")))
        File(dir, "notes.png").writeText("just words")
        assertNull(pictureKind(File(dir, "notes.png")))
    }

    @Test fun anImportIsACopyUnderANameTheDraftsKnow() {
        val source = png()
        val image = importDesktopImage(FileImage(source), File(dir, "attachments"))
        assertTrue("named as ChatDrafts lists them: ${image.file.name}", ChatDrafts.NAME.matches(image.file.name))
        assertEquals(source.readBytes().toList(), image.file.readBytes().toList())
        assertTrue("his own file is left alone", source.isFile)
        assertEquals(image.file.path, image.guestPath)
    }

    @Test fun whatIsNotAPictureOrTooLargeIsRefusedAndAPasteIsCleanedUp() {
        File(dir, "words.txt").writeText("hello")
        val words = runCatching { importDesktopImage(FileImage(File(dir, "words.txt")), File(dir, "attachments")) }
        assertEquals("Choose a JPEG, PNG, WebP or GIF image.", words.exceptionOrNull()?.message)
        val big = png("big.png").apply { appendBytes(ByteArray((MAX_DESKTOP_IMAGE_BYTES).toInt())) }
        assertEquals("Each image must be 20 MB or smaller.", runCatching { importDesktopImage(FileImage(big), File(dir, "attachments")) }.exceptionOrNull()?.message)
        assertFalse("nothing was left in attachments", File(dir, "attachments").listFiles().orEmpty().isNotEmpty())
        val pasted = png("pasted.png")
        importDesktopImage(FileImage(pasted, temporary = true), File(dir, "attachments"))
        assertFalse("a pasted image's temporary file goes once it is copied", pasted.exists())
    }

    @Test fun aThumbnailIsSmallAndNeverLarger() {
        val small = desktopThumbnail(png(w = 400, h = 200), 100)
        assertNotNull(small)
        assertEquals(100, small!!.width)
        assertEquals(50, small.height)
        assertEquals(40, desktopThumbnail(png("tiny.png", 40, 20), 100)!!.width)
        File(dir, "broken.png").writeText("no")
        assertNull(desktopThumbnail(File(dir, "broken.png"), 100))
    }

    @Test fun aPasteOrDropTakesPicturesAndLeavesTextAlone() {
        File(dir, "notes.txt").writeText("hello")
        val files = picturesIn(transfer(DataFlavor.javaFileListFlavor, listOf(png(), File(dir, "notes.txt"))))
        assertEquals(listOf("shot.png"), files.map { it.file.name })
        assertFalse(files.single().temporary)
        val screenshot = picturesIn(transfer(DataFlavor.imageFlavor, BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB))).single()
        assertTrue(screenshot.temporary)
        assertEquals("png", pictureKind(screenshot.file))
        screenshot.file.delete()
        assertTrue(picturesIn(transfer(DataFlavor.stringFlavor, "just text")).isEmpty())
        assertTrue(picturesIn(null).isEmpty())
    }
}
