package dev.omalauncher

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun onCreate() {
        super.onCreate()
        // The shared canvas model reports an unreadable saved document to Android's log, as it always did.
        dev.omalauncher.upgrade.Versioned.log = { message, error -> android.util.Log.w("versioned", message, error) }
        // The shared chat module draws Nerd Font glyphs with the font this app bundles.
        dev.omalauncher.ui.PlatformFonts.nerdSymbols = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.nerd_symbols))
        // The lock screen's clock is drawn by this process: it hears the minute from here on.
        dev.omalauncher.theme.LookWidgets.start(this)
        // An install a phone job scheduled: its sender hears whether it went in (assist/PhoneJobs.kt).
        dev.omalauncher.assist.PhoneJobs.afterInstall(this)
        // Why the process last ended, for `oma status`: the next restart's cause is read, not guessed (assist/AppExits.kt).
        dev.omalauncher.assist.AppExits.record(this)
    }
}
