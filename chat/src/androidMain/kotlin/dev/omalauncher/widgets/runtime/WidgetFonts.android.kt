package dev.omalauncher.widgets.runtime

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

internal actual fun fontFamilyOf(
    file: File,
    weights: List<Int>?,
): FontFamily {
    android.graphics.fonts.Font.Builder(file).build()
    if (weights == null) return FontFamily(Font(file))
    return FontFamily(weights.map { w -> Font(file, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) })
}
