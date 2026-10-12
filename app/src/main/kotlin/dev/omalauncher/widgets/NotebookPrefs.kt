package dev.omalauncher.widgets

import android.content.Context
import dev.omalauncher.widgets.layout.NotebookFiles
import dev.omalauncher.widgets.layout.OldStores
import dev.omalauncher.widgets.layout.SavedNotebook
import java.io.File

/**
 * The notebook's stores on the phone. The notebook is one string in the `notebook` preferences. What the
 * launcher saved before it (`widget_grid`, `shortcuts`, `dock`, the legacy `widget_pages`) is only ever read
 * here: those files are the backup of the layout as it was, and nothing in the launcher writes them any more.
 */
internal class NotebookPrefs(
    private val context: Context,
) : NotebookFiles {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    /** The preferences file itself, which Android reads as empty when it is damaged. */
    private val file get() = File(context.dataDir, "shared_prefs/$FILE.xml")

    override fun notebook(): SavedNotebook {
        if (prefs.contains(KEY)) return SavedNotebook(exists = true, text = prefs.all[KEY] as? String)
        return SavedNotebook(exists = damaged(), text = null)
    }

    /** The file names a notebook that the preferences don't have: it didn't parse, and the next write would replace it. */
    private fun damaged() = runCatching { file.isFile && file.readText().contains("\"$KEY\"") }.getOrDefault(false)

    override fun old() =
        OldStores.of(
            widgetGrid = old("widget_grid"),
            shortcuts = old("shortcuts"),
            widgetPages = old("widget_pages"),
        )

    override fun oldDock() = old("dock")["items"] as? String

    private fun old(name: String): Map<String, *> = context.getSharedPreferences(name, Context.MODE_PRIVATE).all

    /** Synchronous, unlike [save]: the migration is only done once this is on disk. */
    override fun commit(text: String) = prefs.edit().putString(KEY, text).commit()

    /** A copy beside the app's files: the unreadable value, or the whole file if that is what's damaged. */
    override fun setAside(stamp: Long): Boolean {
        val copy = File(context.filesDir, "notebook-unreadable-$stamp")
        if (prefs.contains(KEY)) copy.writeText(prefs.all[KEY].toString()) else file.copyTo(copy, overwrite = true)
        return copy.isFile
    }

    /** Keeps a change made on home. */
    fun save(text: String) = prefs.edit().putString(KEY, text).apply()

    private companion object {
        const val FILE = "notebook"
        const val KEY = "notebook"
    }
}
