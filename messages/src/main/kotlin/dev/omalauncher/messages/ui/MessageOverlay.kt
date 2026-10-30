package dev.omalauncher.messages.ui

import androidx.compose.animation.core.Animatable
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.moveTo
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.omalauncher.ui.Pack

/** A real modal window: accessibility and Back stay on the menu, using Soma's existing scrim. */
@Composable internal fun MessageOverlay(onDismiss: () -> Unit, dismissOnOutside: Boolean = true, enterMillis: Int = 0, scrim: Boolean = true, lightBars: Boolean? = null, content: @Composable BoxScope.() -> Unit) {
    // Capture the host's focus before entering the dialog's separate focus owner. Otherwise
    // Android restores a previously focused composer and reopens an already dismissed IME.
    val hostFocus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { hostFocus.clearFocus(); keyboard?.hide() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = lightBars ?: (Pack.paper.luminance() > 0.5f)
        DisposableEffect(view, light) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val apply = Runnable {
                window?.let {
                    it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                    it.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    @Suppress("DEPRECATION")
                    run { it.statusBarColor = android.graphics.Color.TRANSPARENT; it.navigationBarColor = android.graphics.Color.TRANSPARENT }
                    it.isStatusBarContrastEnforced = false; it.isNavigationBarContrastEnforced = false
                    it.setDimAmount(0f)
                    androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                }
            }
            val listener = object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) { v.post(apply) }
                override fun onViewDetachedFromWindow(v: android.view.View) { }
            }
            view.addOnAttachStateChangeListener(listener)
            if (view.isAttachedToWindow) view.post(apply)
            onDispose { view.removeCallbacks(apply); view.removeOnAttachStateChangeListener(listener) }
        }
        // Opt-in for the existing hold/tapback menu only; reply/search/edit overlays keep
        // their own focus and layout behavior. No delayed actions or network gating.
        val entrance = remember { Animatable(if (enterMillis > 0 && !Motion.reduced) 0f else 1f) }
        LaunchedEffect(Unit) { entrance.moveTo(1f, Motion.timed(enterMillis, Motion.easing)) }
        Box(Modifier.fillMaxSize().background(Pack.foreground.copy(alpha = if (scrim) 0.18f * entrance.value else 0f))
            .then(if (dismissOnOutside) Modifier.clickable(onClick = onDismiss) else Modifier)) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = entrance.value
                scaleX = 0.96f + 0.04f * entrance.value
                scaleY = scaleX
            }, contentAlignment = Alignment.Center, content = content)
        }
    }
}
