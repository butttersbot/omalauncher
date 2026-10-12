package dev.omalauncher.widgets.layout

import dev.omalauncher.upgrade.Versioned
import org.json.JSONArray

/*
 * How the launcher gets its notebook when it starts, with the stores behind [NotebookFiles] so a JVM test can
 * drive it. The rules, which exist so that a failure never costs the owner his home screen:
 *
 *  - a saved notebook that reads is used, and nothing is migrated;
 *  - otherwise the old stores are migrated in memory, and the result is saved only if all of them could be read:
 *    in one commit, after which the old stores are never written again;
 *  - if anything could not be read, or the commit fails, nothing is saved anywhere: home shows what was converted,
 *    the old stores stay as they are, and the next start tries again;
 *  - a saved notebook that doesn't read is never migrated over: it is set aside first, and if that fails nothing is saved;
 *  - a saved notebook that reads only in part is used, and likewise set aside before anything is saved over it.
 *
 * Nothing here writes an old store, whatever happens.
 */

/** What the `notebook` store holds: [text] is null when it [exists] but isn't a string that can be read at all. */
class SavedNotebook(
    val exists: Boolean,
    val text: String?,
)

/** The stores the notebook starts from. Any of these may throw; that is taken as a failure of that step. */
interface NotebookFiles {
    /** The `notebook` store as it is now. */
    fun notebook(): SavedNotebook

    /** The stores from before the notebook: `widget_grid`, `shortcuts` and the legacy `widget_pages`. Read only. */
    fun old(): OldStores

    /** The `dock` store's document (its key `items`), if there is one. Read only. */
    fun oldDock(): String?

    /** Writes the `notebook` store and waits for it to be on disk; false if it isn't. */
    fun commit(text: String): Boolean

    /** Keeps the unreadable `notebook` store somewhere else, named with [stamp]; false if that failed. */
    fun setAside(stamp: Long): Boolean
}

/** The notebook home starts from. Not [saved] means this session's changes can't be kept; [message] is for the owner. */
class NotebookStart(
    val notebook: Notebook,
    val saved: Boolean,
    val message: String? = null,
    /** What the migration did, when there was one. */
    val report: MigrationReport? = null,
    /** Unsaved only because no widget file was seen: worth migrating again once they show up. */
    val widgetsMissing: Boolean = false,
)

/** Reads the saved notebook, or migrates into one; see the rules above. */
class NotebookOpening(
    private val files: NotebookFiles,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The `notebook` store is there but can't be read: it has to be set aside before anything is written over it. */
    private var unreadable = false

    /** The saved notebook, if there is one that reads; null means there is none to use, and [migrate] is next. */
    fun saved(): NotebookStart? {
        val saved =
            try {
                files.notebook()
            } catch (e: Throwable) {
                // Not known to be absent, so not written over as if it were.
                SavedNotebook(exists = true, text = null)
            }
        if (!saved.exists) return null
        val read = NotebookStore.inspect(saved.text)
        unreadable = read == null
        if (read == null) return null
        if (read.skipped == 0) return NotebookStart(read.notebook, saved = true)
        // Part of it didn't read, and the first save would drop that part for good. So the store as it is goes aside
        // first, and what did read is written in its place; if either fails, this start keeps nothing.
        val kept = attempt { files.setAside(now()) } && attempt { files.commit(NotebookStore.write(read.notebook)) }
        return NotebookStart(read.notebook, saved = kept, message = if (kept) PARTLY_READ else PARTLY_READ_UNSAVED)
    }

    /**
     * The old stores as a notebook, saved if that is safe. [widgets] are the widget files that exist (their name,
     * the page each file asks for and its size), or null if they couldn't be listed.
     */
    fun migrate(widgets: List<OldWidget>?): NotebookStart {
        val old =
            try {
                files.old()
            } catch (e: Throwable) {
                return unsaved(Notebook(NOTEBOOK_MIN_ROWS, listOf(NotebookPage("p1", "Page 1"))), null)
            }
        val migration = migrate(old, widgets.orEmpty())
        val problems = ArrayList(migration.report.problems)
        // A layout that names widgets, and not one widget to be seen: their files are what's missing, not their places.
        val missing = widgets.isNullOrEmpty() && migration.report.orphans.any { it.endsWith(WIDGET_FILE) }
        if (widgets == null) problems += "the widget files couldn't be listed" else if (missing) problems += "none of the widgets the saved layout names were found"
        val dock = oldDock(problems)
        val notebook = migration.notebook.copy(dock = dock)
        val report = migration.report.copy(problems = problems)
        if (!report.clean) return unsaved(notebook, report, widgetsMissing = widgets == null || missing)

        val text =
            try {
                NotebookStore.write(notebook)
            } catch (e: Throwable) {
                return unsaved(notebook, report)
            }
        // Only what reads back as itself is worth more than the old stores.
        if (NotebookStore.read(text) != notebook) return unsaved(notebook, report)
        if (unreadable && !attempt { files.setAside(now()) }) return unsaved(notebook, report)
        if (!attempt { files.commit(text) }) return unsaved(notebook, report)
        val wasUnreadable = unreadable
        unreadable = false
        return NotebookStart(notebook, saved = true, message = savedMessage(report, wasUnreadable), report = report)
    }

    /** The dock's entries as the `dock` store has them; no store is an empty dock, one that can't be read a problem. */
    private fun oldDock(problems: MutableList<String>): List<PinnedApp> {
        val text =
            try {
                files.oldDock()
            } catch (e: Throwable) {
                problems += "the dock (dock: items) can't be read"
                return emptyList()
            }
        if (text.isNullOrBlank()) return emptyList()
        val array =
            try {
                Versioned.read(text, 1) as? JSONArray
            } catch (e: Throwable) {
                null
            }
        if (array == null) {
            problems += "the dock (dock: items) can't be read"
            return emptyList()
        }
        val entries = (0 until array.length()).mapNotNull { i -> runCatching { array.optJSONObject(i)?.let(NotebookStore::app) }.getOrNull() }
        if (entries.size < array.length()) problems += "${array.length() - entries.size} entries of the dock (dock: items) can't be read"
        return entries.distinctBy { it.key }
    }

    private fun attempt(step: () -> Boolean) =
        try {
            step()
        } catch (e: Throwable) {
            false
        }

    private fun unsaved(
        notebook: Notebook,
        report: MigrationReport?,
        widgetsMissing: Boolean = false,
    ) = NotebookStart(notebook, saved = false, message = UNSAVED, report = report, widgetsMissing = widgetsMissing)

    private fun savedMessage(
        report: MigrationReport,
        wasUnreadable: Boolean,
    ): String? {
        val moved =
            when (report.replaced) {
                0 -> null
                1 -> "1 tile didn't fit where it was and was placed again."
                else -> "${report.replaced} tiles didn't fit where they were and were placed again."
            }
        val aside = if (wasUnreadable) "Your saved pages couldn't be read, so they were set aside and your home screen was brought back from the layout before them." else null
        return listOfNotNull(aside, moved).joinToString(" ").ifEmpty { null }
    }

    companion object {
        /** The one sentence for a start that couldn't be saved. */
        const val UNSAVED =
            "Your home layout couldn't be moved to the new pages, so it is shown as far as it could be read: " +
                "changes you make now won't be kept, and it will be tried again the next time the launcher starts."

        /** For a saved notebook that read only in part. */
        const val PARTLY_READ = "Part of your saved pages couldn't be read and is left out; the pages as they were saved were set aside."
        const val PARTLY_READ_UNSAVED =
            "Part of your saved pages couldn't be read and is left out: changes you make now won't be kept, " +
                "and it will be tried again the next time the launcher starts."

        private const val WIDGET_FILE = ".owl"
    }
}
