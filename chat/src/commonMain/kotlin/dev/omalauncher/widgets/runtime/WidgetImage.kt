package dev.omalauncher.widgets.runtime

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/** What an `Image` src or `image(...)` decodes to. */
sealed interface WidgetImage {
    /** Pixels: a PNG, JPEG or WebP, an app icon or album art. */
    data class Raster(val bitmap: ImageBitmap) : WidgetImage

    /** An SVG, kept as drawing commands: sharp at any size, and a few KB however large it's drawn. */
    class Vector(val vector: VectorImage) : WidgetImage
}

/** An SVG ready to draw at any size, with the viewBox's proportions in [width] by [height]. */
expect class VectorImage {
    val width: Int
    val height: Int
}

/**
 * An SVG with the viewBox's proportions, scaled so its longer side is [SVG_SIDE]; it's drawn scaled to
 * wherever it goes. A viewBox comes from width/height when the file has none; with neither, null.
 */
expect fun decodeSvg(bytes: ByteArray): VectorImage?

/** Draws [image] stretched into the rectangle at [x], [y], [w] by [h], colored through [filter] if there is one. */
expect fun DrawScope.drawVector(
    image: VectorImage,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    filter: ColorFilter? = null,
    alpha: Float = 1f,
)

/** Whether [bytes] are an SVG: XML starts with '<' (after a BOM or blank space), which no PNG, JPEG or WebP does. */
fun isSvg(bytes: ByteArray): Boolean {
    var i = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
    return i < bytes.size && bytes[i] == '<'.code.toByte()
}

/** A [WidgetImage.Vector] for Compose's Image, which scales it by `contentScale` like a bitmap. */
class VectorPainter(private val image: VectorImage) : Painter() {
    private var filter: ColorFilter? = null
    private var alpha = 1f

    override val intrinsicSize = Size(image.width.toFloat(), image.height.toFloat())

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        filter = colorFilter
        return true
    }

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun DrawScope.onDraw() {
        drawVector(image, 0f, 0f, size.width, size.height, filter, alpha)
    }
}

/** The longer side of a decoded SVG; only proportions matter, as it's drawn scaled. */
internal const val SVG_SIDE = 1000f
