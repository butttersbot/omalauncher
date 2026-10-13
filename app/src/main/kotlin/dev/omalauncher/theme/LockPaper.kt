package dev.omalauncher.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.omalauncher.theme.pack.PackPage
import dev.omalauncher.theme.pack.PageKind
import dev.omalauncher.theme.pack.PaperPattern
import dev.omalauncher.theme.pack.coverCrop
import dev.omalauncher.theme.pack.wallpaperSide
import dev.omalauncher.ui.PackWallpapers
import dev.omalauncher.ui.patternTile

/**
 * The lock screen's wallpaper in the look's paper: what is behind the scraps on home, with nothing on it.
 * Samsung draws the lock screen and everything on it; its wallpaper is the one part an app may set, so that
 * is what follows the look. A look that leaves the page to the system (Console) has no paper to give.
 */
object LockPaper {
    /**
     * What says a display already has the look's paper: the look and the display's size. A fold has two
     * displays with a lock wallpaper each, and each is given its paper the first time home shows on it.
     */
    fun key(
        look: String,
        width: Int,
        height: Int,
    ) = "$look|${width}x$height"

    /** Whether a display that has [done] still needs [key]'s paper: the option is on, the look has paper, and it isn't there yet. */
    fun wanted(
        on: Boolean,
        kind: PageKind,
        done: Set<String>,
        key: String,
    ) = on && kind != PageKind.System && key !in done

    /** [page] as home draws it, [width] by [height] pixels at [density] pixels to the dp. Not on the main thread. */
    fun render(
        page: PackPage,
        wallpapers: PackWallpapers,
        width: Int,
        height: Int,
        density: Float,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(page.color.argb)
        val image =
            if (page.kind != PageKind.Wallpaper) {
                null
            } else {
                when (wallpaperSide(width / density, height / density, wallpapers.cover != null, wallpapers.inner != null)) {
                    "cover" -> wallpapers.cover
                    "inner" -> wallpapers.inner
                    else -> null
                }
            }?.asAndroidBitmap()
        val crop = image?.let { coverCrop(it.width, it.height, width, height) }
        if (image != null && crop != null) {
            canvas.drawBitmap(image, Rect(crop[0], crop[1], crop[0] + crop[2], crop[1] + crop[3]), Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        } else if (page.pattern != PaperPattern.None) {
            val tile = patternTile(page, density).asAndroidBitmap()
            canvas.drawPaint(Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        }
        return bitmap
    }
}
