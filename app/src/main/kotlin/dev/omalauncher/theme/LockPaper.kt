package dev.omalauncher.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.omalauncher.theme.pack.Dressing
import dev.omalauncher.theme.pack.PageKind
import dev.omalauncher.theme.pack.PaperPattern
import dev.omalauncher.theme.pack.ThemePack
import dev.omalauncher.theme.pack.coverCrop
import dev.omalauncher.theme.pack.wallpaperSide
import dev.omalauncher.ui.PackWallpapers
import dev.omalauncher.ui.patternTile

/**
 * The phone's wallpapers in the look's paper: what is behind the scraps on home. Samsung draws the lock screen
 * and everything on it; its wallpaper is the one part an app may set, so that is what follows the look. The
 * home wallpaper is set with it, which is what Samsung's color palette takes the system's colors from. A look
 * that leaves the page to the system (Console) has no paper to give.
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

    /** A fold's other display: its size in pixels and the mark Samsung's wallpaper service knows it by. */
    class Other(
        val width: Int,
        val height: Int,
        val mark: Int,
    )

    /** Samsung's marks for a fold's two displays, as its wallpaper service lists them (One UI 9): the open one, and the cover. */
    const val MAIN_DISPLAY = 4
    const val SUB_DISPLAY = 16

    /**
     * The display that isn't the one in use, [width] by [height]: the one other size among those seen, when
     * there is exactly one. The larger of the two is the open display, the smaller the cover.
     */
    fun otherDisplay(
        sizes: Set<String>,
        width: Int,
        height: Int,
    ): Other? {
        val others = sizes.mapNotNull { size -> size.split('x').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } }.filter { it[0] != width || it[1] != height }
        val (w, h) = others.singleOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return Other(w, h, if (w.toLong() * h > width.toLong() * height) MAIN_DISPLAY else SUB_DISPLAY)
    }

    /** Whether a display that has [done] still needs [key]'s paper: the option is on, the look has paper, and it isn't there yet. */
    fun wanted(
        on: Boolean,
        kind: PageKind,
        done: Set<String>,
        key: String,
    ) = on && kind != PageKind.System && key !in done

    /**
     * The look's paper as home draws it, [width] by [height] pixels at [density] pixels to the dp, and on a
     * paper of color and pattern, two empty scraps taped to it in the look's own dress, so the lock screen
     * reads as a page of the same notebook. A look whose paper is a picture is left as the picture. Not on the
     * main thread.
     */
    fun render(
        pack: ThemePack,
        wallpapers: PackWallpapers,
        width: Int,
        height: Int,
        density: Float,
    ): Bitmap {
        val page = pack.page
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
            return bitmap
        }
        if (page.pattern != PaperPattern.None) {
            val tile = patternTile(page, density).asAndroidBitmap()
            canvas.drawPaint(Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        }
        for (scrap in scraps(width, height)) scrap(canvas, pack, scrap, density)
        return bitmap
    }

    /** A scrap on the lock paper: its rectangle in pixels, whether it is the emphasised kind, which way it tilts, and whether tape holds it. */
    class LockScrap(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val alt: Boolean,
        val tiltUp: Boolean,
        val taped: Boolean,
    )

    /**
     * Where the scraps go on a lock screen of [width] by [height]: in its lower half, under Samsung's clock and
     * over its shortcuts, which is the part of the screen nothing of Samsung's is drawn on. Two that overlap a
     * little, as on a page; on a wide screen they stand side by side to the right of the clock instead.
     */
    fun scraps(
        width: Int,
        height: Int,
    ): List<LockScrap> {
        val w = width.toFloat()
        val h = height.toFloat()
        return if (width > height) {
            listOf(LockScrap(w * 0.56f, h * 0.24f, w * 0.80f, h * 0.52f, alt = false, tiltUp = true, taped = true), LockScrap(w * 0.70f, h * 0.44f, w * 0.92f, h * 0.70f, alt = true, tiltUp = false, taped = false))
        } else {
            listOf(LockScrap(w * 0.38f, h * 0.60f, w * 0.90f, h * 0.76f, alt = false, tiltUp = true, taped = false), LockScrap(w * 0.10f, h * 0.50f, w * 0.60f, h * 0.66f, alt = true, tiltUp = false, taped = true))
        }
    }

    private fun scrap(
        canvas: Canvas,
        pack: ThemePack,
        at: LockScrap,
        density: Float,
    ) {
        val surface = if (at.alt) pack.scrap.alt else pack.scrap.surface
        val radius = pack.shape.radius * density
        val box = RectF(at.left, at.top, at.right, at.bottom)
        canvas.save()
        canvas.rotate(if (at.tiltUp) pack.scrap.tiltMax else pack.scrap.tiltMin, box.centerX(), box.centerY())
        val shadow = surface.shadow
        if (shadow.visible && !shadow.inset) {
            canvas.drawRoundRect(RectF(box).apply { offset(shadow.x * density, shadow.y * density) }, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shadow.color.argb })
        }
        canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface.fill.argb })
        if (surface.border > 0f) {
            canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = surface.border * density; color = surface.borderColor.argb })
        }
        val tape = pack.scrap.tape
        if (at.taped && tape.style != Dressing.None) {
            // Home's tapes are sized for tiles; a scrap this big takes one half again as large.
            val tw = tape.width * density * 1.5f
            val th = tape.height * density * 1.5f
            canvas.rotate(tape.angle, box.centerX(), box.top)
            canvas.drawRect(box.centerX() - tw / 2f, box.top - th / 2f, box.centerX() + tw / 2f, box.top + th / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tape.color.withAlpha(tape.opacity).argb })
        }
        canvas.restore()
    }
}
