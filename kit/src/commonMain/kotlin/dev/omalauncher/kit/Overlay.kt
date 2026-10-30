package dev.omalauncher.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.between
import dev.omalauncher.ui.moveTo

/** A window of its own over the app, so accessibility and Back stay on what's in it. [lightBars] is the system bars' ink over it. */
@Composable
expect fun ModalWindow(
    onDismiss: () -> Unit,
    lightBars: Boolean,
    content: @Composable () -> Unit,
)

/**
 * A menu or sheet over the screen, in a real modal window, on the scrim of [ink] (the caller's, from its look; null
 * for none). With [enterMillis] it comes in over that time; without, it is simply there. [dismissOnOutside] closes it on a tap beside
 * what it shows, which has to take its own taps.
 */
@Composable
fun KitOverlay(
    ink: Color?,
    onDismiss: () -> Unit,
    lightBars: Boolean,
    dismissOnOutside: Boolean = true,
    enterMillis: Int = 0,
    content: @Composable BoxScope.() -> Unit,
) {
    // Capture the host's focus before entering the window's separate focus owner. Otherwise
    // Android restores a previously focused composer and reopens an already dismissed IME.
    val hostFocus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { hostFocus.clearFocus(); keyboard?.hide() }
    ModalWindow(onDismiss, lightBars) {
        val entrance = remember { Animatable(if (enterMillis > 0 && !Motion.reduced) 0f else 1f) }
        LaunchedEffect(Unit) { entrance.moveTo(1f, Motion.timed(enterMillis, Motion.easing)) }
        Box(Modifier.fillMaxSize().background(ink?.copy(alpha = Feel.SCRIM_ALPHA * entrance.value) ?: Color.Transparent)
            .then(if (dismissOnOutside) Modifier.clickable(onClick = onDismiss) else Modifier)) {
            Box(Modifier.fillMaxSize().overlayEntrance { entrance.value }, contentAlignment = Alignment.Center, content = content)
        }
    }
}

/** What's in an overlay coming in: faded and a touch small at 0, as it is at 1. A layer only. */
fun Modifier.overlayEntrance(shown: () -> Float): Modifier =
    graphicsLayer {
        val t = shown()
        alpha = t
        val scale = between(Feel.OVERLAY_SCALE, 1f, t)
        scaleX = scale
        scaleY = scale
    }
