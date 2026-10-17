package dev.omalauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope

/*
 * What the shared screens ask of the platform they draw on: the phone app (Android) and the desktop app (Mac and
 * Omarchy). Each has one small file of answers beside this one.
 */

/** Whether the system runs animations at all; Android's "remove animations" turns them off. Read by [Motion.reduced]. */
expect fun systemAnimationsOn(): Boolean

/** [paint] blurred by [radius] px, as a pack's soft shadow is drawn. */
expect fun blurPaint(paint: Paint, radius: Float)

/** [text] in the system face at [size] px, its right edge at [x] and its baseline at [y]. */
expect fun DrawScope.drawTextRightAligned(text: String, x: Float, y: Float, size: Float, color: Color)

/** While [enabled], Back (the gesture, the key, Escape) does [onBack] instead of leaving. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Opens a web address where the platform opens web pages; with nowhere to open it, nothing happens. */
@Composable
expect fun rememberWebOpener(): (String) -> Unit
