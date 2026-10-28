package dev.omalauncher.widgets.annotations

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/*
 * Soma desktop's picture stickers, decoded by Skia (desktop slice 5b, 2026-10-28). The head is read first, so a
 * picture of more pixels than a sticker may have is never decoded at all; one that is decoded is drawn down to the
 * side asked for at once and only that small copy is kept, as the phone keeps only its sampled bitmap.
 */

actual fun measurePicture(bytes: ByteArray): Pair<Int, Int>? =
    try {
        Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { stickerSized(it.width, it.height) } }
    } catch (e: Throwable) {
        null
    }

actual fun measurePicture(file: File): Pair<Int, Int>? =
    try {
        if (!file.isFile || file.length() !in 1..StickerLimits.FILE_BYTES) null else measurePicture(file.readBytes())
    } catch (e: Throwable) {
        null
    }

actual fun decodePicture(
    file: File,
    side: Int,
): ImageBitmap? {
    return try {
        if (!file.isFile || file.length() !in 1..StickerLimits.FILE_BYTES) return null
        val bytes = file.readBytes()
        val (width, height) = measurePicture(bytes) ?: return null
        val longer = maxOf(width, height)
        val w = if (longer <= side) width else (width.toLong() * side / longer).toInt().coerceAtLeast(1)
        val h = if (longer <= side) height else (height.toLong() * side / longer).toInt().coerceAtLeast(1)
        Image.makeFromEncoded(bytes).use { image ->
            Surface.makeRasterN32Premul(w, h).use { surface ->
                surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
                // Its own pixels, copied out of the surface, so nothing it was drawn from is held after.
                Bitmap().apply { allocN32Pixels(w, h) }.also { surface.readPixels(it, 0, 0) }.asComposeImageBitmap()
            }
        }
    } catch (e: Throwable) {
        null
    }
}
