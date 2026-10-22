package dev.omalauncher

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun onCreate() {
        super.onCreate()
        // The search's file indexer runs in a process of its own (search/FileIndex.kt): none of the launcher starts there.
        if (Application.getProcessName().endsWith(":indexer")) return
        // The shared canvas model reports an unreadable saved document to Android's log, as it always did.
        dev.omalauncher.upgrade.Versioned.log = { message, error -> android.util.Log.w("versioned", message, error) }
        // The shared chat module draws Nerd Font glyphs with the font this app bundles.
        // A widget's Terminal node draws with this app's Linux terminal; the shared renderer has none of its own (Soma Canvas 7a).
        dev.omalauncher.widgets.render.OwlScreen.terminal = { term, modifier -> dev.omalauncher.ui.TerminalView(term as dev.omalauncher.ui.EmbeddedTerminal, modifier) }
        dev.omalauncher.ui.PlatformFonts.nerdSymbols = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.nerd_symbols))
        // The lock screen's clock is drawn by this process: it hears the minute from here on.
        dev.omalauncher.theme.LookWidgets.start(this)
        // An install a phone job scheduled: its sender hears whether it went in (assist/PhoneJobs.kt).
        dev.omalauncher.assist.PhoneJobs.afterInstall(this)
        // Why the process last ended, for `oma status`: the next restart's cause is read, not guessed (assist/AppExits.kt).
        dev.omalauncher.assist.AppExits.record(this)
        // Home's layout with the canvas service's copy of it, when a Mac says it runs one (Soma Canvas slice 8b).
        dev.omalauncher.widgets.FoldSyncJob.start(this)
        // Home's saved layouts for the Layouts module, and how art is printed in the Manga look (piece D).
        dev.omalauncher.widgets.FoldLayouts.install(this)
        // Art from the share sheet and the panel picker hangs in home's panels (piece B).
        dev.omalauncher.ui.MangaHome.install(this)
        // Soma News on the hour, for the reader and the home widgets, once FreshRSS's settings are there.
        if (dev.omalauncher.news.NewsStore.config(this) != null) dev.omalauncher.news.NewsJob.schedule(this)
        // The Pet home and Steps widgets: whether the pet is out and what it wears, and today's steps once allowed.
        dev.omalauncher.herd.HerdSheep.restore(this)
        dev.omalauncher.herd.PetWidget.write(this)
        dev.omalauncher.steps.Steps.start(this)
    }
}
