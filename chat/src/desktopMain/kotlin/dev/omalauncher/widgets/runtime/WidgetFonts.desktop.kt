package dev.omalauncher.widgets.runtime

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import org.jetbrains.skia.FontMgr
import java.io.File

internal actual fun fontFamilyOf(
    file: File,
    weights: List<Int>?,
): FontFamily {
    checkNotNull(FontMgr.default.makeFromFile(file.path)) { "isn't a font" }
    if (weights == null) return FontFamily(Font(file))
    // A variable font: one instance per weight, each set on its wght axis, so fontWeight draws the weight it names.
    return FontFamily(weights.map { w -> Font(file, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) })
}
