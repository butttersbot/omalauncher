package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.omalauncher.theme.pack.PackPage
import dev.omalauncher.theme.pack.PageKind
import dev.omalauncher.theme.pack.PaperPattern
import dev.omalauncher.theme.pack.coverCrop
import dev.omalauncher.theme.pack.wallpaperSide
import kotlin.math.roundToInt

/**
 * What is behind home in a pack that draws its own page: its paper, a color and a pattern, or its
 * wallpaper. A pack carries one wallpaper for the folded display and one for the unfolded, and the one
 * for the display in use covers all of it as a single image, never tiled and never one half per page
 * (PRD AC-20). Until a wallpaper is decoded, and for a pack that names none, the paper shows.
 *
 * Draws nothing for a pack that leaves the page to the system (Console): the launcher's own wallpaper
 * or Android's shows, as before packs.
 */
@Composable
fun PagePaper(modifier: Modifier = Modifier) {
    val page = Pack.look.pack.page
    if (page.kind == PageKind.System) return
    val wallpapers = ActivePack.wallpapers
    val density = LocalDensity.current
    val pattern = remember(page, density.density) { if (page.pattern == PaperPattern.None) null else patternTile(page, density.density) }
    val brush = remember(pattern) { pattern?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    val color = page.color.toColor()
    Canvas(modifier.fillMaxSize()) {
        drawRect(color)
        val image =
            if (page.kind != PageKind.Wallpaper) {
                null
            } else {
                when (wallpaperSide(size.width / density.density, size.height / density.density, wallpapers.cover != null, wallpapers.inner != null)) {
                    "cover" -> wallpapers.cover
                    "inner" -> wallpapers.inner
                    else -> null
                }
            }
        val crop = image?.let { coverCrop(it.width, it.height, size.width.roundToInt(), size.height.roundToInt()) }
        if (image != null && crop != null) {
            drawImage(
                image,
                srcOffset = IntOffset(crop[0], crop[1]),
                srcSize = IntSize(crop[2], crop[3]),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        } else if (brush != null) {
            drawRect(brush)
        }
    }
}

/** One repeat of [page]'s pattern on a clear ground, [density] pixels to the dp. */
private fun patternTile(
    page: PackPage,
    density: Float,
): ImageBitmap {
    val step = (page.patternSize * density).roundToInt().coerceAtLeast(1)
    val weight = (page.patternWeight * density).coerceAtLeast(1f)
    // A checker repeats every two squares; the rest every step.
    val side = if (page.pattern == PaperPattern.Checker) step * 2 else step
    val tile = ImageBitmap(side, side)
    val canvas = Canvas(tile)
    val paint = Paint().apply { color = page.patternColor.toColor() }
    val s = step.toFloat()
    when (page.pattern) {
        PaperPattern.None -> {}
        PaperPattern.Dots -> canvas.drawCircle(Offset(s / 2f, s / 2f), weight / 2f, paint)
        PaperPattern.Lines -> canvas.drawRect(0f, s - weight, s, s, paint)
        PaperPattern.Grid -> {
            canvas.drawRect(0f, s - weight, s, s, paint)
            canvas.drawRect(s - weight, 0f, s, s, paint)
        }
        PaperPattern.Checker -> {
            canvas.drawRect(0f, 0f, s, s, paint)
            canvas.drawRect(s, s, s * 2f, s * 2f, paint)
        }
    }
    return tile
}
