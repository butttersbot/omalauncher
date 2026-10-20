package dev.omalauncher.ui

import androidx.compose.ui.graphics.Color

/**
 * Lays [activity] out behind clear system bars, their clock and icons dark when [light] says what is behind
 * them is light; the status bar's by [status] where that differs, as over a manga page's hero. Set on the window itself: androidx's enableEdgeToEdge keeps the style of its first call and
 * puts it back at every configuration change, so after a fold or a turn the bars were drawn for whatever
 * showed when the activity started, not for the look picked since.
 */
@Suppress("DEPRECATION")
fun applySystemBars(
    activity: androidx.activity.ComponentActivity,
    light: Boolean,
    status: Boolean = light,
) {
    val window = activity.window
    val transparent = android.graphics.Color.TRANSPARENT
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    // Before Android 15 the bars are filled unless told otherwise; from then on they are clear and these two say nothing.
    window.statusBarColor = transparent
    window.navigationBarColor = transparent
    // No scrim of the system's own behind either bar.
    window.isStatusBarContrastEnforced = false
    window.isNavigationBarContrastEnforced = false
    window.attributes.layoutInDisplayCutoutMode =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).run {
        isAppearanceLightStatusBars = status
        isAppearanceLightNavigationBars = light
    }
}
