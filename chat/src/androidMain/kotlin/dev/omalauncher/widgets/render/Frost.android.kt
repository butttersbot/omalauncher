package dev.omalauncher.widgets.render

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun imageFromPixels(
    px: IntArray,
    w: Int,
    h: Int,
): ImageBitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
