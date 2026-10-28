package dev.omalauncher.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.omalauncher.linux.PromptImage
import dev.omalauncher.oma.PickedImage
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID

/*
 * Pictures on the desktop (desktop parity slice 2, Boss 2026-10-28): picked with the + (a file dialog), pasted, or dropped
 * on the chat window, copied into Soma's attachments folder under the names ChatDrafts knows, and sent to a Mac thread
 * the phone's way (the engine puts them on the Mac first). The phone's limits: JPEG, PNG, WebP or GIF, 20 MB each.
 */

/** A picture file on this computer, picked, pasted or dropped. [temporary] is a pasted image written out for the import. */
class FileImage(val file: File, val temporary: Boolean = false) : PickedImage

/** The largest picture taken, as on the phone (PromptImages.kt). */
const val MAX_DESKTOP_IMAGE_BYTES = 20L * 1024 * 1024

/** The kind of picture [file] is, by its first bytes: the extension ChatDrafts' names use, or null for anything else. */
fun pictureKind(file: File): String? {
    val head = runCatching { file.inputStream().use { it.readNBytes(12) } }.getOrNull() ?: return null
    fun at(i: Int) = head.getOrNull(i)?.toInt()?.and(0xff)
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "gif"
        String(head.copyOfRange(0, minOf(4, head.size)), Charsets.ISO_8859_1) == "RIFF" && head.size >= 12 && String(head.copyOfRange(8, 12), Charsets.ISO_8859_1) == "WEBP" -> "webp"
        else -> null
    }
}

/** [picked] copied into [attachments] under a name of its own, for the agent. Throws with why it can't be taken. */
fun importDesktopImage(picked: FileImage, attachments: File): PromptImage {
    try {
        val source = picked.file
        require(source.isFile) { "That picture is gone." }
        val kind = pictureKind(source) ?: error("Choose a JPEG, PNG, WebP or GIF image.")
        require(source.length() > 0) { "The selected image is empty." }
        require(source.length() <= MAX_DESKTOP_IMAGE_BYTES) { "Each image must be 20 MB or smaller." }
        check(attachments.mkdirs() || attachments.isDirectory) { "Could not create the attachments folder." }
        // Generated names: nothing of his file's name reaches a path or a shell.
        val copy = File(attachments, "${UUID.randomUUID()}.$kind")
        try {
            source.copyTo(copy)
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        return PromptImage(copy, copy.path)
    } finally {
        if (picked.temporary) picked.file.delete()
    }
}

/** [file] drawn with its longest side [longest] px (never larger than it is), or null if it can't be read. */
fun desktopThumbnail(file: File, longest: Int): ImageBitmap? = runCatching {
    val image = Image.makeFromEncoded(file.readBytes())
    val ratio = minOf(1f, longest.toFloat() / maxOf(image.width, image.height))
    val w = (image.width * ratio).toInt().coerceAtLeast(1)
    val h = (image.height * ratio).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()

/**
 * The pictures in [transfer] (a paste or a drop): image files, or an image itself (a screenshot copied to the clipboard),
 * written to a temporary PNG. Empty when it holds no picture, so text pastes and drops go on as before.
 */
fun picturesIn(transfer: Transferable?): List<FileImage> {
    if (transfer == null) return emptyList()
    runCatching {
        if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = (transfer.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
            return files.filter { it.isFile && pictureKind(it) != null }.map { FileImage(it) }
        }
    }
    return runCatching {
        if (!transfer.isDataFlavorSupported(DataFlavor.imageFlavor)) return emptyList()
        val image = transfer.getTransferData(DataFlavor.imageFlavor) as java.awt.Image
        val buffered = image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
            it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        }
        val file = File.createTempFile("soma-paste-", ".png")
        javax.imageio.ImageIO.write(buffered, "png", file)
        listOf(FileImage(file, temporary = true))
    }.getOrDefault(emptyList())
}

/** The pictures on the clipboard now; empty when it holds text or nothing. */
fun picturesOnClipboard(): List<FileImage> = picturesIn(runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull())
