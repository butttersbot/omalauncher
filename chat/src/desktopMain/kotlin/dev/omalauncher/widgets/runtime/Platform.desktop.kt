package dev.omalauncher.widgets.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

internal actual fun decodeWidgetImage(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

internal actual fun decodeWidgetImageFile(file: File, minSide: Int): ImageBitmap? = decodeWidgetImage(file.readBytes())

internal actual fun widgetLog(message: String) {
    System.err.println("widgets: $message")
}
