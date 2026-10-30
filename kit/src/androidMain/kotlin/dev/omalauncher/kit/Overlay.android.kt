package dev.omalauncher.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
actual fun ModalWindow(
    onDismiss: () -> Unit,
    lightBars: Boolean,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view, lightBars) {
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
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
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
        content()
    }
}
