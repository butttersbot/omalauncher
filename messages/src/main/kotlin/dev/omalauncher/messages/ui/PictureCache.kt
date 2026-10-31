package dev.omalauncher.messages.ui

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/** Pictures already decoded, so one scrolling back into view is there at once rather than decoded again. */
internal object PictureCache {
    private val pictures = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    operator fun get(key: String): ImageBitmap? = pictures[key]

    operator fun set(key: String, picture: ImageBitmap) {
        pictures.put(key, picture)
    }
}
