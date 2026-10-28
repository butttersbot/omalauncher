package dev.omalauncher.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/* The phone's answers to Platform.kt. */

actual fun systemAnimationsOn(): Boolean = ValueAnimator.areAnimatorsEnabled()

actual fun blurPaint(paint: Paint, radius: Float) {
    paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
}

actual fun DrawScope.drawTextRightAligned(text: String, x: Float, y: Float, size: Float, color: Color) {
    val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = size
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
actual fun rememberWebOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            // A phone with no browser has nowhere to open it, and the address is still there to read and copy.
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

actual fun androidx.compose.ui.Modifier.onSecondaryClick(key: Any?, action: () -> Unit): androidx.compose.ui.Modifier = this

