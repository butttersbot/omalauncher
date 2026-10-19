package dev.omalauncher.ui

import dev.omalauncher.assist.prototypeUnit
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.omalauncher.widgets.render.BackdropPicture
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
    val brush = remember(page, density.density) { patternBrush(page, density.density) }
    Canvas(modifier.fillMaxSize()) { drawPagePaper(page, wallpapers, brush) }
}

/** [page]'s pattern as a repeating brush, [density] pixels to the dp; null for a page without one. */
private fun patternBrush(
    page: PackPage,
    density: Float,
): ShaderBrush? = if (page.pattern == PaperPattern.None) null else ShaderBrush(ImageShader(patternTile(page, density), TileMode.Repeated, TileMode.Repeated))

/** What [PagePaper] draws, for the screen this scope covers. */
private fun DrawScope.drawPagePaper(
    page: PackPage,
    wallpapers: PackWallpapers,
    brush: ShaderBrush?,
) {
    drawRect(page.color.toColor())
    val image =
        if (page.kind != PageKind.Wallpaper) {
            null
        } else {
            when (wallpaperSide(size.width / density, size.height / density, wallpapers.cover != null, wallpapers.inner != null)) {
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

/**
 * The look's page and its gutters, [across] pages side by side, as frosted widgets see it behind them; null for a
 * look that leaves the page to the system. Read while composing, so a frosted node makes its blur again when the
 * look, its wallpapers or the pages across change.
 */
fun pageBackdrop(across: Int): BackdropPicture? {
    val page = Pack.look.pack.page
    if (page.kind == PageKind.System) return null
    val wallpapers = ActivePack.wallpapers
    return BackdropPicture(listOf(page, wallpapers.cover, wallpapers.inner, across)) {
        drawPagePaper(page, wallpapers, patternBrush(page, density))
        if (page.gutter && across >= 1) drawPageGutters(across)
    }
}

/** One repeat of [page]'s pattern on a clear ground, [density] pixels to the dp. */
internal fun patternTile(
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

/**
 * The prototype's `.page::before`: a soft shadow down the inner edge of each page, nine of its units wide, in
 * a look whose page has a gutter. With [across] pages side by side there is one at the screen's edge and one
 * at each page's start after it, which on a spread is the fold.
 */
@Composable
fun PageGutters(across: Int) {
    if (!Pack.look.pack.page.gutter || across < 1) return
    Canvas(Modifier.fillMaxSize()) { drawPageGutters(across) }
}

private fun DrawScope.drawPageGutters(across: Int) {
    val u = prototypeUnit(size.width / density, size.height / density)
    val wide = 9f * u * density
    for (page in 0 until across) {
        val x = size.width * page / across
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent), startX = x, endX = x + wide),
            topLeft = Offset(x, 0f),
            size = Size(wide, size.height),
        )
    }
}
