package dev.omalauncher.widgets.annotations

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

/*
 * Picture stickers as pixels: read off the main thread, no bigger than they are drawn, and kept for the next
 * time in a cache that has a bound. A file that is no picture, or one too big to be a sticker, comes to
 * nothing here instead of an error, and what shows it then says so in its place.
 *
 * The decoding itself is each platform's (StickerDecoding.android.kt: BitmapFactory, sampled as it reads;
 * StickerDecoding.desktop.kt: Skia), so the phone and Soma desktop draw the same stickers from the same shelf
 * (desktop slice 5b, 2026-10-28).
 */

/** The width and height of the image in [bytes], read from its head alone; null for what is no image, or has too many pixels to be a sticker. */
expect fun measurePicture(bytes: ByteArray): Pair<Int, Int>?

/** The width and height of the image in [file], read from its head alone; null for a file that is gone, no image, or has too many pixels to be a sticker. */
expect fun measurePicture(file: File): Pair<Int, Int>?

/**
 * The image in [file] with its longer side no more than [side] pixels, a moving one's first picture; null for a
 * file that is gone, too big, or can't be decoded. It never throws, running out of memory included, and never
 * holds the picture at its full size.
 */
expect fun decodePicture(
    file: File,
    side: Int,
): ImageBitmap?

/** Whether [width] by [height] is a picture a sticker can be: something at all, and no more than [StickerLimits.PIXELS]. */
fun stickerSized(
    width: Int,
    height: Int,
): Pair<Int, Int>? = if (width > 0 && height > 0 && width.toLong() * height <= StickerLimits.PIXELS) width to height else null

/** The side a picture drawn [pixels] across is decoded to: one of a few, so that resizing a sticker doesn't decode it at every step. */
fun pictureSide(pixels: Int): Int = PICTURE_SIDES.firstOrNull { it >= pixels } ?: StickerLimits.SIDE

private val PICTURE_SIDES = listOf(128, 256, StickerLimits.SIDE)

/**
 * The decoded pictures, by a key that names one image for good: a copy's name, or a library file's path with
 * its size and date. Kept up to [CACHE_BYTES] of pixels, the least lately used going first. A picture that
 * couldn't be decoded is not kept, so it is tried again the next time something shows it. Plain Kotlin, where
 * it was Android's LruCache, so the desktop keeps its pictures the same way; safe from any thread, as that was.
 */
class StickerBitmaps(
    private val most: Long = CACHE_BYTES.toLong(),
) {
    private class Held(
        val bitmap: ImageBitmap,
        val side: Int,
    ) {
        /** Four bytes a pixel, as the phone's bitmaps are. */
        val bytes = bitmap.width.toLong() * bitmap.height * 4
    }

    /** In order of use, the least lately used first. */
    private val held = LinkedHashMap<String, Held>(16, 0.75f, true)
    private var bytes = 0L

    /** The picture kept for [key], if it was decoded for at least [side] pixels. */
    fun kept(
        key: String,
        side: Int,
    ): ImageBitmap? = synchronized(held) { held[key] }?.takeIf { it.side >= side }?.bitmap

    /** The picture for [key], decoded from [file] for [side] pixels unless it is kept; null if it can't be. Not on the main thread. */
    fun load(
        key: String,
        file: File,
        side: Int,
    ): ImageBitmap? = kept(key, side) ?: decodePicture(file, side)?.also { put(key, Held(it, side)) }

    private fun put(
        key: String,
        value: Held,
    ) = synchronized(held) {
        held.put(key, value)?.let { bytes -= it.bytes }
        bytes += value.bytes
        val oldest = held.entries.iterator()
        while (bytes > most && oldest.hasNext()) {
            val gone = oldest.next()
            bytes -= gone.value.bytes
            oldest.remove()
        }
    }

    companion object {
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}

/**
 * Where the stickers are: the library the owner fills, the copies of what he stuck, and their pixels. On the
 * phone the library is the launcher's own folder [dev.omalauncher.LauncherData.stickersDir], which the Linux
 * guest sees as `~/.config/omalauncher/stickers`, beside the widgets there, so he and his agents put pictures
 * in it as they write widgets. It is kept outside the Linux root: reinstalling Linux leaves it, and it is there
 * without Linux. Soma desktop's is `stickers` in its own folder. [shownAs] is where the picker says to put them.
 */
class StickerShelf(
    private val folder: File,
    val snapshots: StickerSnapshots,
    val bitmaps: StickerBitmaps = StickerBitmaps(),
    val shownAs: String = "~/.config/omalauncher/stickers",
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
                    .map { group -> group.copy(stickers = group.stickers.filter { measurePicture(File(dir, it.path)) != null }) }
                    .filter { it.stickers.isNotEmpty() }
            } catch (e: Throwable) {
                emptyList()
            }
        return StickerLibrary(dir, groups)
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
