package dev.omalauncher

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun onCreate() {
        super.onCreate()
        // The shared chat module draws Nerd Font glyphs with the font this app bundles.
        dev.omalauncher.ui.PlatformFonts.nerdSymbols = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.nerd_symbols))
        // The lock screen's clock is drawn by this process: it hears the minute from here on.
        dev.omalauncher.theme.LookWidgets.start(this)
    }
}
