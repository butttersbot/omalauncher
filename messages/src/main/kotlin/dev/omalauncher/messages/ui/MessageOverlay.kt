package dev.omalauncher.messages.ui

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
@Composable internal fun MessageOverlay(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    // Capture the host's focus before entering the dialog's separate focus owner. Otherwise
    // Android restores a previously focused composer and reopens an already dismissed IME.
    val hostFocus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { hostFocus.clearFocus(); keyboard?.hide() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = Pack.paper.luminance() > 0.5f
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
        Box(Modifier.fillMaxSize().background(Pack.foreground.copy(alpha = 0.18f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center, content = content)
    }
}
