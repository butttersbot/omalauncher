package dev.omalauncher.widgets.runtime

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asSkiaColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.ceil

actual class VectorImage(
    val dom: SVGDOM,
    actual val width: Int,
    actual val height: Int,
)

actual fun decodeSvg(bytes: ByteArray): VectorImage? =
    runCatching {
        val dom = SVGDOM(Data.makeFromBytes(bytes))
        val root = dom.root ?: return null
        val box = root.viewBox ?: run {
            val w = root.width.value
            val h = root.height.value
            if (w > 0 && h > 0) Rect.makeWH(w, h).also { root.viewBox = it } else null
        } ?: return null
        if (box.width <= 0 || box.height <= 0) return null
        val scale = SVG_SIDE / maxOf(box.width, box.height)
        VectorImage(dom, ceil(box.width * scale).toInt(), ceil(box.height * scale).toInt())
    }.getOrNull()

actual fun DrawScope.drawVector(
    image: VectorImage,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    filter: ColorFilter?,
    alpha: Float,
) {
    if (image.width <= 0 || image.height <= 0 || w <= 0 || h <= 0) return
    drawIntoCanvas {
        val canvas = it.nativeCanvas
        val save =
            if (filter != null || alpha < 1f) {
                canvas.saveLayer(Rect.makeXYWH(x, y, w, h), Paint().apply { colorFilter = filter?.asSkiaColorFilter(); this.alpha = (alpha * 255).toInt() })
            } else {
                canvas.save()
            }
        canvas.translate(x, y)
        canvas.scale(w / image.width, h / image.height)
        image.dom.setContainerSize(image.width.toFloat(), image.height.toFloat())
        image.dom.render(canvas)
        canvas.restoreToCount(save)
    }
}
