package dev.omalauncher.widgets.runtime

import android.graphics.Paint
import android.graphics.Picture
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.caverock.androidsvg.SVG
import kotlin.math.ceil

actual class VectorImage(val picture: Picture) {
    actual val width get() = picture.width
    actual val height get() = picture.height
}

actual fun decodeSvg(bytes: ByteArray): VectorImage? =
    runCatching {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        val box = svg.documentViewBox?.takeIf { it.width() > 0 && it.height() > 0 } ?: return null
        val scale = SVG_SIDE / maxOf(box.width(), box.height())
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        VectorImage(svg.renderToPicture(ceil(box.width() * scale).toInt(), ceil(box.height() * scale).toInt()))
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
    val picture = image.picture
    if (picture.width <= 0 || picture.height <= 0 || w <= 0 || h <= 0) return
    drawIntoCanvas {
        val canvas = it.nativeCanvas
        val save =
            if (filter != null || alpha < 1f) {
                canvas.saveLayer(x, y, x + w, y + h, Paint().apply { colorFilter = filter?.asAndroidColorFilter(); this.alpha = (alpha * 255).toInt() })
            } else {
                canvas.save()
            }
        canvas.translate(x, y)
        canvas.scale(w / picture.width, h / picture.height)
        canvas.drawPicture(picture)
        canvas.restoreToCount(save)
    }
}
