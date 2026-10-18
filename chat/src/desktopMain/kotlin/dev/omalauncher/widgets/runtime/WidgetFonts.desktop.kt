package dev.omalauncher.widgets.runtime

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import org.jetbrains.skia.FontMgr
import java.io.File

internal actual fun fontFamilyOf(
    file: File,
    weights: List<Int>?,
): FontFamily {
    checkNotNull(FontMgr.default.makeFromFile(file.path)) { "isn't a font" }
    // Skia picks a variable font's default instance; the weights are named so fontWeight finds the nearest.
    if (weights == null) return FontFamily(Font(file))
    return FontFamily(weights.map { w -> Font(file, FontWeight(w)) })
}
