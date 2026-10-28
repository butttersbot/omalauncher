package dev.omalauncher.widgets.annotations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/* The phone's picture stickers, decoded by BitmapFactory: halved while read, so never held at their full size. */

private fun sized(bounds: BitmapFactory.Options): Pair<Int, Int>? = stickerSized(bounds.outWidth, bounds.outHeight)

actual fun measurePicture(bytes: ByteArray): Pair<Int, Int>? =
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        sized(bounds)
    } catch (e: Throwable) {
        null
    }

actual fun measurePicture(file: File): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return sized(bounds)
}

actual fun decodePicture(
    file: File,
    side: Int,
): ImageBitmap? {
    return try {
        if (!file.isFile || file.length() !in 1..StickerLimits.FILE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val (width, height) = sized(bounds) ?: return null
        // Halved while it is read, as often as leaves it no smaller than asked, then brought down to it exactly.
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= side) sample *= 2
        val read = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longer = maxOf(read.width, read.height)
        if (longer <= side) return read.asImageBitmap()
        val fitted = Bitmap.createScaledBitmap(read, (read.width * side / longer).coerceAtLeast(1), (read.height * side / longer).coerceAtLeast(1), true)
        if (fitted !== read) read.recycle()
        fitted.asImageBitmap()
    } catch (e: Throwable) {
        null
    }
}
