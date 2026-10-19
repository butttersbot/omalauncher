package dev.omalauncher.widgets

import android.content.Context
import dev.omalauncher.App
import dev.omalauncher.canvas.LayoutPad
import dev.omalauncher.canvas.LayoutShelf
import dev.omalauncher.canvas.Layouts
import dev.omalauncher.canvas.LayoutsSource
import dev.omalauncher.canvas.MangaPrefs
import dev.omalauncher.canvas.withLayouts

/*
 * The Fold's saved layouts (Manga look, piece D): a preference file of their own beside the notebook's, like the restore
 * points. They stay on the Fold. Sync with the Mac Pro carries home only as verbs, never documents, and every screen keeps
 * its own layout (Boss, 2026-10-16): a saved layout holds this phone's apps and its own pages, which mean nothing on another
 * screen, and a copy of it on the service could only ever be loaded back over a newer home. A page goes to another screen
 * as a template.
 */
object FoldLayouts {
    private const val FILE = "layouts"
    private const val SHELF = "shelf"
    private const val ART_INK = "art-ink"

    @Volatile private var made: LayoutShelf? = null

    /** The Fold's shelf, saving with each layout the look picked when it is saved. */
    fun shelf(context: Context): LayoutShelf = made ?: synchronized(this) {
        made ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            LayoutShelf({ prefs.getString(SHELF, null) }, { prefs.edit().putString(SHELF, it).apply() }, look = { (app as App).runtime.themes.pickedPack })
        }.also { made = it }
    }

    /**
     * Home's canvas as the agents' words and the Layouts module reach it, changed through the notebook as the overview and a
     * drag change it, with the saved layouts. One pad for both, so a load from the tile is undone as an agent's change is.
     */
    fun pad(context: Context): LayoutPad = dev.omalauncher.phone.homeCanvasPad(context).withLayouts(shelf(context))

    /** Home kept as [name] by home itself (B: "Before Manga" when the look changes to Manga); false while home isn't open. */
    fun autoSave(context: Context, name: String): Boolean {
        val pad = pad(context)
        if (!(context.applicationContext as App).runtime.widgets.notebook.opened) return false
        pad.layouts.autoSave(name, pad.notebook())
        return true
    }

    /** At the app's start: the art preference read back, and the Layouts module given the shelf and home's pad. */
    fun install(app: App) {
        val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        MangaPrefs.open(if (prefs.contains(ART_INK)) prefs.getBoolean(ART_INK, true) else null) { prefs.edit().putBoolean(ART_INK, it).apply() }
        Layouts.here = LayoutsSource(shelf(app), pad = { if (app.runtime.widgets.notebook.opened) pad(app) else null })
    }
}
