package dev.omalauncher.widgets.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

internal actual fun imageFromPixels(
    px: IntArray,
    w: Int,
    h: Int,
): ImageBitmap = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, w, h, px, 0, w) }.toComposeImageBitmap()
