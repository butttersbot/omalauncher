package dev.omalauncher

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun onCreate() {
        super.onCreate()
        // The lock screen's clock is drawn by this process: it hears the minute from here on.
        dev.omalauncher.theme.LookWidgets.start(this)
    }
}
