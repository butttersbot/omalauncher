package dev.omalauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.TextLine

/* The desktop's answers to Platform.kt (Mac and Omarchy). */

// The desktop has no system-wide switch for animations that Compose reads; they always run.
actual fun systemAnimationsOn(): Boolean = true

actual fun blurPaint(paint: Paint, radius: Float) {
    // Android's BlurMaskFilter turns its radius into Skia's sigma this way.
    paint.skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius * 0.57735f + 0.5f)
}

actual fun DrawScope.drawTextRightAligned(text: String, x: Float, y: Float, size: Float, color: Color) {
    val line = TextLine.make(text, Font(null, size))
    val paint = org.jetbrains.skia.Paint().apply { this.color = color.toArgb() }
    drawIntoCanvas { it.nativeCanvas.drawTextLine(line, x - line.width, y, paint) }
}

/**
 * The desktop's Back is Escape. A window hands each Escape to its stack's [press], which gives it to the newest handler
 * still on screen and enabled, as Android gives Back to the innermost one. Each window has a stack of its own
 * ([LocalBackStack]), so an Escape in one never closes something in another (the chat and dashboard windows, 2026-10-28).
 */
open class BackStack {
    private val handlers = ArrayList<() -> Boolean>()

    fun add(handler: () -> Boolean) = synchronized(handlers) { handlers.add(handler) }

    fun remove(handler: () -> Boolean) = synchronized(handlers) { handlers.remove(handler) }

    /** Gives one Escape to the newest enabled handler; false when none took it. */
    fun press(): Boolean = synchronized(handlers) { handlers.toList() }.asReversed().any { it() }
}

/** The stack where no window gave one of its own (a test's single screen). */
object DesktopBack : BackStack()

/** The stack of the window being drawn. */
val LocalBackStack = staticCompositionLocalOf<BackStack> { DesktopBack }

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val on by rememberUpdatedState(enabled)
    val stack = LocalBackStack.current
    DisposableEffect(stack) {
        val handler = { if (on) { current(); true } else false }
        stack.add(handler)
        onDispose { stack.remove(handler) }
    }
}

@Composable
actual fun rememberWebOpener(): (String) -> Unit =
    remember {
        { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }
    }

actual fun androidx.compose.ui.Modifier.onSecondaryClick(key: Any?, action: () -> Unit): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    action()
                }
            }
        }
    })

