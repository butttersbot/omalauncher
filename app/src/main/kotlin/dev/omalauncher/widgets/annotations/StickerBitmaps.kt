package dev.omalauncher.widgets.annotations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.omalauncher.App
import java.io.File

/*
 * Picture stickers as pixels: read off the main thread, no bigger than they are drawn, and kept for the next
 * time in a cache that has a bound. A file that is no picture, or one too big to be a sticker, comes to
 * nothing here instead of an error, and what shows it then says so in its place.
 */

private fun sized(bounds: BitmapFactory.Options): Pair<Int, Int>? {
    val width = bounds.outWidth
    val height = bounds.outHeight
    return if (width > 0 && height > 0 && width.toLong() * height <= StickerLimits.PIXELS) width to height else null
}

/** The width and height of the image in [bytes], read from its head alone; null for what is no image, or has too many pixels to be a sticker. */
fun measurePicture(bytes: ByteArray): Pair<Int, Int>? =
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        sized(bounds)
    } catch (e: Throwable) {
        null
    }

/**
 * The image in [file] with its longer side no more than [side] pixels, a moving one's first picture; null for a
 * file that is gone, too big, or can't be decoded. It never throws, running out of memory included, and never
 * holds the picture at its full size.
 */
fun decodePicture(
    file: File,
    side: Int,
): Bitmap? {
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
        if (longer <= side) return read
        val fitted = Bitmap.createScaledBitmap(read, (read.width * side / longer).coerceAtLeast(1), (read.height * side / longer).coerceAtLeast(1), true)
        if (fitted !== read) read.recycle()
        fitted
    } catch (e: Throwable) {
        null
    }
}

/** The side a picture drawn [pixels] across is decoded to: one of a few, so that resizing a sticker doesn't decode it at every step. */
fun pictureSide(pixels: Int): Int = PICTURE_SIDES.firstOrNull { it >= pixels } ?: StickerLimits.SIDE

private val PICTURE_SIDES = listOf(128, 256, StickerLimits.SIDE)

/**
 * The decoded pictures, by a key that names one image for good: a copy's name, or a library file's path with
 * its size and date. Kept up to [CACHE_BYTES] of pixels, the least lately used going first. A picture that
 * couldn't be decoded is not kept, so it is tried again the next time something shows it.
 */
class StickerBitmaps {
    private class Held(
        val bitmap: Bitmap,
        val side: Int,
    )

    private val held =
        object : LruCache<String, Held>(CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Held,
            ) = value.bitmap.byteCount
        }

    /** The picture kept for [key], if it was decoded for at least [side] pixels. */
    fun kept(
        key: String,
        side: Int,
    ): Bitmap? = held.get(key)?.takeIf { it.side >= side }?.bitmap

    /** The picture for [key], decoded from [file] for [side] pixels unless it is kept; null if it can't be. Not on the main thread. */
    fun load(
        key: String,
        file: File,
        side: Int,
    ): Bitmap? = kept(key, side) ?: decodePicture(file, side)?.also { held.put(key, Held(it, side)) }

    companion object {
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}

/**
 * Where the launcher's stickers are: the library the owner fills, the copies of what he stuck, and their pixels.
 * The library is the launcher's own folder [dev.omalauncher.LauncherData.stickersDir], which the Linux guest sees
 * as `~/.config/omalauncher/stickers`, beside the widgets there, so he and his agents put pictures in it as they
 * write widgets. It is kept outside the Linux root: reinstalling Linux leaves it, and it is there without Linux.
 */
class StickerShelf(
    private val folder: File,
    val snapshots: StickerSnapshots,
    val bitmaps: StickerBitmaps = StickerBitmaps(),
) {
    /** The library's folder, made if it isn't there; null if it can't be. Not on the main thread. */
    fun library(): File? = folder.also { it.mkdirs() }.takeIf { it.isDirectory }

    /**
     * The pictures to pick from, in their groups; those whose file is no image are left out. Null while there
     * is no library. Not on the main thread.
     */
    fun look(): StickerLibrary? {
        val dir = library() ?: return null
        val groups =
            try {
                libraryStickers(listLibrary(dir))
                    .map { group -> group.copy(stickers = group.stickers.filter { isPicture(File(dir, it.path)) }) }
                    .filter { it.stickers.isNotEmpty() }
            } catch (e: Throwable) {
                emptyList()
            }
        return StickerLibrary(dir, groups)
    }

    private fun isPicture(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return sized(bounds) != null
    }
}

/** The library as it was when looked at: its folder [dir] and the pictures in it. */
class StickerLibrary(
    val dir: File,
    val groups: List<StickerGroup>,
) {
    fun file(sticker: LibrarySticker) = File(dir, sticker.path)

    /** What [sticker]'s decoded picture is kept by: its file, as it was when the library was looked at. */
    fun key(sticker: LibrarySticker) = "library:${sticker.path}:${sticker.stamp}"
}

/** The one [StickerShelf] of the running launcher. */
object HomeStickers {
    private var shelf: StickerShelf? = null

    /** The shelf, made on first use: the copies are `annotations/stickers/` in the app's private files, beside the pages' files. */
    fun of(context: Context): StickerShelf {
        val app = context.applicationContext
        return shelf ?: StickerShelf(dev.omalauncher.LauncherData.stickersDir(app), StickerSnapshots(File(app.filesDir, "annotations/stickers"), ::measurePicture)).also { shelf = it }
    }
}
