package dev.omalauncher.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.NOTEBOOK_MIN_ROWS
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.rescale

/** A page as home lists it: what it's called, and the [id] its items go by, which never changes. */
data class PageLabel(
    val id: String,
    val name: String,
)

/** A pinned app in the notebook: on a page by itself, or inside [folder]. */
data class Pinned(
    val app: PinnedApp,
    val folder: String? = null,
)

/**
 * The notebook while the launcher runs: the pages in order, which page each item is on and in which cell, the
 * pinned apps and their folders, and the dock. Everything home shows of its layout is read from here, and
 * [save] writes all of it as one document through [persist]. A page stays when its last item leaves.
 *
 * Until [open] is given the saved (or migrated) notebook it holds one empty page and keeps nothing. Opened as
 * not kept, it works the same and still writes nothing: that is a start whose migration couldn't be saved.
 * Main thread only.
 */
class LiveNotebook(
    private val persist: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The saved notebook has been read, or the old stores migrated. */
    var opened by mutableStateOf(false)
        private set

    /** Whether changes are written. */
    var kept = false
        private set

    /** Rows of every page; the columns are [GRID_COLUMNS]. */
    var rows by mutableIntStateOf(NOTEBOOK_MIN_ROWS)
        private set

    val pages = mutableStateListOf(PageLabel("p1", "Page 1"))

    /** By item key. An item of the grid without one hasn't been placed yet. */
    internal val cells = mutableStateMapOf<String, Cell>()

    /** The page id of everything on the grid, by key: widgets (by file name), apps outside folders, and folders. */
    private val home = mutableStateMapOf<String, String>()

    /** Every pinned app; those of a folder in the folder's order. */
    val apps = mutableStateListOf<Pinned>()

    /** Folder keys, in the order they were made. */
    val folders = mutableStateListOf<String>()

    var dock by mutableStateOf(emptyList<PinnedApp>())
        private set

    /** Replaces everything with [notebook]. Not [kept]: nothing is written from here on. */
    fun open(
        notebook: Notebook,
        kept: Boolean,
    ) {
        pages.clear()
        cells.clear()
        home.clear()
        apps.clear()
        folders.clear()
        rows = notebook.rows.coerceAtLeast(1)
        for (page in notebook.pages) {
            if (pages.any { it.id == page.id }) continue
            pages += PageLabel(page.id, page.name)
            for (item in page.items) {
                if (item.key in home) continue
                when (item.kind) {
                    ItemKind.Widget -> {}
                    ItemKind.App -> if (!load(item.app ?: continue, null)) continue
                    ItemKind.Folder -> {
                        folders += item.key
                        item.apps.forEach { load(it, item.key) }
                    }
                }
                home[item.key] = page.id
                item.cell?.let { cells[item.key] = rescale(it, notebook.columns) }
            }
        }
        if (pages.isEmpty()) pages += PageLabel("p1", "Page 1")
        dock = notebook.dock
        this.kept = kept
        opened = true
    }

    private fun load(
        app: PinnedApp,
        folder: String?,
    ): Boolean {
        if (apps.any { it.app.key == app.key }) return false
        apps += Pinned(app, folder)
        return true
    }

    /** Everything as the document the store keeps. A widget whose file is gone keeps its entry, as its cell always did. */
    fun snapshot(): Notebook {
        val grid = apps.filter { it.folder == null }
        val appKeys = apps.map { it.app.key }.toSet()
        val widgets = home.keys.filter { it !in appKeys && it !in folders }.sorted()
        return Notebook(
            rows = rows,
            pages =
                pages.map { page ->
                    NotebookPage(
                        page.id,
                        page.name,
                        widgets.filter { home[it] == page.id }.map { NotebookItem(it, ItemKind.Widget, cells[it]) } +
                            grid.filter { home[it.app.key] == page.id }.map { NotebookItem(it.app.key, ItemKind.App, cells[it.app.key], app = it.app) } +
                            folders.filter { home[it] == page.id }.map { f -> NotebookItem(f, ItemKind.Folder, cells[f], apps = apps.filter { it.folder == f }.map { it.app }) },
                    )
                },
            dock = dock,
        )
    }

    /** Writes the notebook, unless this start isn't kept. */
    fun save() {
        if (opened && kept) persist(NotebookStore.write(snapshot()))
    }

    val first get() = pages.first().id

    fun name(page: String) = pages.firstOrNull { it.id == page }?.name

    /** A page's place in the notebook, from 0; -1 if there's no such page. */
    fun position(page: String?) = pages.indexOfFirst { it.id == page }

    /** The page [key] is on: an app in a folder is where its folder is. Null for what isn't in the notebook. */
    fun pageOf(key: String): String? = home[key] ?: apps.firstOrNull { it.app.key == key }?.folder?.let { home[it] }

    fun cell(key: String) = cells[key]

    /** Live while dragging; [save] keeps it. An unchanged cell isn't written, so pointer moves within it recompose nothing. */
    fun setCell(
        key: String,
        cell: Cell,
    ) {
        if (cells[key] != cell) cells[key] = cell
    }

    /** A new, empty page after the last one; returns its id. */
    fun addPage(): String {
        val id = "p" + ((pages.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        var number = pages.size + 1
        while (pages.any { it.name == "Page $number" }) number++
        pages += PageLabel(id, "Page $number")
        save()
        return id
    }

    /** Takes back a page that was only offered and never used: an empty one, never the only one. */
    fun withdrawPage(page: String): Boolean {
        if (pages.size < 2 || home.values.any { it == page }) return false
        if (!pages.removeAll { it.id == page }) return false
        save()
        return true
    }

    /**
     * Puts widget files the notebook hasn't seen on the page each asks for, by position (1 is the first page).
     * Those asking for a page past the last get new pages after it, one for each number asked, in order.
     * A widget the notebook knows stays where the owner has it, whatever its file says.
     */
    fun adopt(widgets: List<Pair<String, Int>>) {
        val fresh = widgets.filter { (key) -> key !in home }
        if (fresh.isEmpty()) return
        val count = pages.size
        val added = HashMap<Int, String>()
        for ((key, asks) in fresh.sortedBy { it.second }) {
            home[key] = pages.getOrNull(asks.coerceAtLeast(1) - 1)?.id?.takeIf { asks <= count } ?: added.getOrPut(asks) { addPage() }
        }
        save()
    }

    /** Says where a widget file that isn't written yet will go, so it lands there rather than where its file asks. */
    fun expect(
        key: String,
        page: String,
    ) {
        if (position(page) < 0) return
        home[key] = page
        save()
    }

    /** Puts a widget, an app or a folder on [page]; an app taken out of its folder lands on the grid on its own. */
    fun move(
        key: String,
        page: String,
    ) {
        if (position(page) < 0) return
        val i = apps.indexOfFirst { it.app.key == key }
        if (i >= 0 && apps[i].folder != null) apps[i] = apps[i].copy(folder = null)
        home[key] = page
        tidy()
        save()
    }

    /** Forgets a widget: its cell and its page. */
    fun forget(key: String) {
        val known = cells.remove(key) != null
        if (home.remove(key) != null || known) save()
    }

    /** Pins [app] to [page]; one that's on the home screen already moves there, out of its folder. */
    fun pin(
        app: PinnedApp,
        page: String,
    ) {
        apps.removeAll { it.app.key == app.key }
        apps += Pinned(app)
        home[app.key] = page.takeIf { position(it) >= 0 } ?: first
        tidy()
        save()
    }

    /** Takes an app, or a folder with all its apps, off the home screen. */
    fun unpin(key: String) {
        if (folders.remove(key)) {
            apps.filter { it.folder == key }.forEach { cells.remove(it.app.key) }
            apps.removeAll { it.folder == key }
        }
        apps.removeAll { it.app.key == key }
        cells.remove(key)
        home.remove(key)
        tidy()
        save()
    }

    /**
     * Drops the app [key] on [target]: into it if it's a folder, otherwise both go into a new folder in
     * [target]'s cell. Returns the folder's key, or null if [target] isn't an app or folder on the grid.
     */
    fun group(
        key: String,
        target: String,
    ): String? {
        if (key == target) return null
        val app = apps.firstOrNull { it.app.key == key } ?: return null
        val folder =
            when {
                target in folders -> target
                apps.any { it.app.key == target && it.folder == null } -> {
                    val made = "folder:" + now().toString(36)
                    folders += made
                    home[made] = home.remove(target) ?: first
                    cells.remove(target)?.let { cells[made] = it }
                    into(target, made)
                    made
                }
                else -> return null
            }
        if (folder == app.folder) return folder
        cells.remove(key)
        home.remove(key)
        into(key, folder)
        tidy()
        save()
        return folder
    }

    /** Last in the folder, like stock launchers. */
    private fun into(
        key: String,
        folder: String,
    ) {
        val i = apps.indexOfFirst { it.app.key == key }
        if (i >= 0) apps += apps.removeAt(i).copy(folder = folder)
    }

    /**
     * Moves an app within its folder to just before [before], or after the folder's last app if that's null.
     * A folder's order is its apps' order in [apps].
     */
    fun moveBefore(
        key: String,
        before: String?,
    ) {
        val i = apps.indexOfFirst { it.app.key == key }
        val folder = apps.getOrNull(i)?.folder ?: return
        if (key == before) return
        val app = apps.removeAt(i)
        val at = before?.let { b -> apps.indexOfFirst { it.app.key == b && it.folder == folder } }?.takeIf { it >= 0 } ?: (apps.indexOfLast { it.folder == folder } + 1)
        apps.add(at, app)
        if (at != i) save()
    }

    /**
     * A folder of fewer than two apps goes away, like on stock Android: the app left takes its page, and its
     * cell unless it already has one of its own.
     */
    private fun tidy() {
        for (f in folders.toList()) {
            val inside = apps.filter { it.folder == f }
            if (inside.size >= 2) continue
            val page = home.remove(f)
            val cell = cells.remove(f)
            inside.firstOrNull()?.let { last ->
                val key = last.app.key
                apps[apps.indexOf(last)] = last.copy(folder = null)
                home[key] = page ?: first
                if (cells[key] == null && cell != null) cells[key] = cell
            }
            folders.remove(f)
        }
    }

    fun changeDock(entries: List<PinnedApp>) {
        dock = entries
        save()
    }
}
