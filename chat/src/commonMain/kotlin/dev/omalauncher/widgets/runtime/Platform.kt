package dev.omalauncher.widgets.runtime

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

/** An image a widget carries ([bytes], PNG or JPEG), decoded; null when it can't be. */
internal expect fun decodeWidgetImage(bytes: ByteArray): ImageBitmap?

/** An image file, decoded no smaller than [minSide] on each side where the platform can sample; null when it can't be. */
internal expect fun decodeWidgetImageFile(file: File, minSide: Int): ImageBitmap?

/** A widget's runtime problem, for the platform's log. */
internal expect fun widgetLog(message: String)
