package dev.omalauncher.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.PageTile
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.NOTEBOOK_MIN_ROWS
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.firstFit
import dev.omalauncher.widgets.layout.rescale
import dev.omalauncher.widgets.layout.shown

/** A page as home lists it: what it's called, and the [id] its items go by, which never changes. */
data class PageLabel(
    val id: String,
    val name: String,
)

/** The longest name a page takes. Asking Oma for a page by name matches names up to twice this. */
const val PAGE_NAME_MAX = 40

/**
 * A name as a page takes it: without blank space around it or more than one space in a row, without characters
 * that don't show, and no longer than [PAGE_NAME_MAX]. Null when that leaves nothing: a page is never nameless.
 */
fun pageName(typed: String): String? {
    // Every kind of blank space is a space; then what isn't to be seen goes: control characters, and format
    // characters, which is where the marks that turn the writing direction round are.
    val clean = typed.replace(BLANKS, " ").replace(UNSEEN, "").replace(SPACES, " ").trim()
    return clipped(clean).trim().ifEmpty { null }
}

private val BLANKS = Regex("[\\s\\p{Z}]+")
private val UNSEEN = Regex("[\\p{Cc}\\p{Cf}]")
private val SPACES = Regex(" +")

/** [text] cut to [max] characters, on one line, and not through the middle of a character that takes two. */
fun clipped(
    text: String,
    max: Int = PAGE_NAME_MAX,
): String {
    val line = text.replace('\n', ' ').replace('\r', ' ')
    return if (line.length <= max) line else line.take(if (line[max - 1].isHighSurrogate()) max - 1 else max)
}

/**
 * What became of a deleted page's things: [moved] went to the page [to]; [spilled] found no room there and are
 * on the new page [spill]; [removed] were taken off home with the page (a folder stands for its apps); [dropped]
 * are the entries of widgets whose files were missing, which only held a place on it.
 */
data class PageGone(
    val to: String?,
    val moved: List<String>,
    val spill: String?,
    val spilled: List<String>,
    val removed: List<String>,
    val dropped: List<String>,
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
 * Until [open] is given the saved (or migrated) notebook it holds one empty page and takes no change at all:
 * whatever was pinned, moved or added then would be thrown away when the real pages arrive, so it is refused
 * instead. Opened as not kept, it works and still writes nothing: that is a start whose migration couldn't be
 * saved. Main thread only.
 */
class LiveNotebook(
    private val persist: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The saved notebook has been read, or the old stores migrated. */
    var opened by mutableStateOf(false)
        private set

    /** Whether changes are written. */
    var kept by mutableStateOf(false)
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

    /**
     * Told the id of a page that has just gone, by [deletePage] or [withdrawPage]: what is kept by page id
     * outside the notebook (the page's ink and notes) goes with it, so a later page given that id starts clean.
     */
    var onPageGone: ((String) -> Unit)? = null

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

    /** From here on changes are written, and what was changed meanwhile with them: the start's disk work came out well. */
    fun keep() {
        if (!opened || kept) return
        kept = true
        save()
    }

    /**
     * The cells on [page] of what isn't among [except]: in practice widgets whose files aren't there now. They
     * show nothing, but the place is still theirs, so nothing else is put there by itself.
     */
    fun cellsOn(
        page: String,
        except: Set<String>,
    ): List<Cell> = home.entries.filter { it.value == page && it.key !in except }.mapNotNull { cells[it.key] }

    /**
     * [page] as what is placed on it by itself sees it: its grid, and every tile with a cell on it as it
     * shows. A widget goes by its file name, there or not (a missing file's place is still its), an app by
     * its label, and a folder by the labels of the apps in it; apps and folders take one cell.
     */
    fun board(page: String): PageBoard {
        val tiles =
            home.entries.filter { it.value == page }.sortedBy { it.key }.mapNotNull { (key, _) ->
                val cell = cells[key] ?: return@mapNotNull null
                val app = apps.firstOrNull { it.folder == null && it.app.key == key }
                when {
                    app != null -> PageTile(app.app.label, cell.shown(resizable = false), "app")
                    key in folders -> PageTile(apps.filter { it.folder == key }.joinToString(", ") { it.app.label }, cell.shown(resizable = false), "folder")
                    else -> PageTile(key, cell, "widget")
                }
            }
        return PageBoard(rows, tiles.sortedWith(compareBy({ it.cell.y }, { it.cell.x })))
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
        if (opened && cells[key] != cell) cells[key] = cell
    }

    /** A new, empty page after the last one; returns its id, or null before the notebook is opened. */
    fun addPage(): String? = if (opened) newPage() else null

    private fun newPage(): String {
        val page = PageLabel(freeId(), freeName())
        pages += page
        save()
        return page.id
    }

    /** An id no page has. */
    private fun freeId() = "p" + ((pages.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    /** "Page N" for the place after the last page, or the next number no page is called by. */
    private fun freeName(): String {
        var number = pages.size + 1
        while (pages.any { it.name == "Page $number" }) number++
        return "Page $number"
    }

    /**
     * Whether a page other than [except] goes by [name], whatever its capitals. Two pages may share a name, but
     * "open my trip page" then can't choose between them, so whoever names one is told.
     */
    fun nameTaken(
        name: String,
        except: String? = null,
    ) = pages.any { it.id != except && it.name.equals(name, ignoreCase = true) }

    /**
     * Calls [page] by [name], tidied as [pageName] does; false, and nothing changed, for a name with nothing in it.
     * Its id stays, and so does everything on it.
     */
    fun renamePage(
        page: String,
        name: String,
    ): Boolean {
        val at = position(page)
        val clean = pageName(name)
        if (!opened || at < 0 || clean == null) return false
        if (pages[at].name != clean) {
            pages[at] = pages[at].copy(name = clean)
            save()
        }
        return true
    }

    /**
     * Moves [page] [by] places among the pages: -1 is one earlier, 1 one later. False at either end, where there is
     * nowhere to go. Pages side by side are paired by position, so the pairs follow the new order.
     */
    fun movePage(
        page: String,
        by: Int,
    ): Boolean {
        val from = position(page)
        val to = from + by
        if (!opened || from < 0 || by == 0 || to !in pages.indices) return false
        pages.add(to, pages.removeAt(from))
        save()
        return true
    }

    /** Whether [page] can be deleted: it is there, and it isn't the only one. */
    fun canDelete(page: String) = opened && pages.size > 1 && position(page) >= 0

    /**
     * Deletes [page]; null, and nothing changed, if it [canDelete] not. With [move], what is on it goes to the
     * page before it (the one after, for the first page): each thing to the first free cells at its size, in
     * reading order, and what finds none stays together, in its own cells, on a new page where the deleted one
     * was. Without [move] it is all taken off home: apps and folders unpinned, widgets forgotten, whose files
     * the caller moves aside; refused while the notebook isn't [kept].
     *
     * [widgets] are the widget files there are now. A widget's entry whose file is missing shows nothing and
     * was only holding its place: it goes with the page either way.
     */
    fun deletePage(
        page: String,
        widgets: Set<String>,
        move: Boolean,
    ): PageGone? {
        // Removing moves widget files aside for good; in a session that isn't written the store would still list them.
        if (!canDelete(page) || (!move && !kept)) return null
        val at = position(page)
        val here = home.filterValues { it == page }.keys.sorted()
        val (shown, held) = here.partition { it in widgets || !isWidget(it) }
        held.forEach {
            home.remove(it)
            cells.remove(it)
        }
        val moved = ArrayList<String>()
        val spilled = ArrayList<String>()
        var to: String? = null
        var spill: PageLabel? = null
        if (move) {
            val target = pages[if (at == 0) 1 else at - 1].id
            to = target
            val taken = home.filterValues { it == target }.keys.mapNotNullTo(ArrayList()) { shownCell(it) }
            for (key in shown.sortedWith(compareBy({ cells[it]?.y ?: Int.MAX_VALUE }, { cells[it]?.x ?: Int.MAX_VALUE }))) {
                val cell = shownCell(key)
                // Not placed yet: home finds it cells when it lays the page out, as for any newcomer.
                val spot = if (cell == null) null else firstFit(cell.w, cell.h, taken, GRID_COLUMNS, rows)
                if (cell != null && spot == null) {
                    spilled += key
                    continue
                }
                if (spot != null) {
                    cells[key] = spot
                    taken += spot
                }
                home[key] = target
                moved += key
            }
            // Before the page goes, so its id isn't handed to the page that takes its place.
            if (spilled.isNotEmpty()) spill = PageLabel(freeId(), "")
        } else {
            shown.forEach(::take)
        }
        pages.removeAt(at)
        spill?.let { made ->
            pages.add(at, made.copy(name = freeName()))
            spilled.forEach { home[it] = made.id }
        }
        save()
        onPageGone?.invoke(page)
        return PageGone(to, moved, spill?.id, spilled, if (move) emptyList() else shown, held)
    }

    /** Whether [key] is a widget's, rather than an app's or a folder's. */
    private fun isWidget(key: String) = key !in folders && apps.none { it.app.key == key }

    /** The cells [key] takes up: an app or a folder shows as 1x1, whatever an old saved cell says. */
    private fun shownCell(key: String) = cells[key]?.shown(isWidget(key))

    /** Takes back a page that was only offered and never used: an empty one, never the only one. */
    fun withdrawPage(page: String): Boolean {
        if (!opened || pages.size < 2 || home.values.any { it == page }) return false
        if (!pages.removeAll { it.id == page }) return false
        save()
        onPageGone?.invoke(page)
        return true
    }

    /**
     * Puts widget files the notebook hasn't seen on the page each asks for, by position (1 is the first page).
     * Those asking for a page past the last get new pages after it, one for each number asked, in order.
     * A widget the notebook knows stays where the owner has it, whatever its file says.
     *
     * [prefer] names, for a new file, the id of a page to put it on instead of the one it asks for: the page the
     * owner was on when he asked an agent for it. Returns the files that went to such a page.
     */
    fun adopt(
        widgets: List<Pair<String, Int>>,
        prefer: (String) -> String? = { null },
    ): List<String> {
        val fresh = widgets.filter { (key) -> key !in home }
        if (!opened || fresh.isEmpty()) return emptyList()
        val count = pages.size
        val added = HashMap<Int, String>()
        val preferred = ArrayList<String>()
        for ((key, asks) in fresh.sortedBy { it.second }) {
            val wanted = prefer(key)?.takeIf { position(it) >= 0 }
            if (wanted != null) preferred += key
            home[key] = wanted ?: pages.getOrNull(asks.coerceAtLeast(1) - 1)?.id?.takeIf { asks <= count } ?: added.getOrPut(asks) { newPage() }
        }
        save()
        return preferred
    }

    /** Says where a widget file that isn't written yet will go, so it lands there rather than where its file asks. */
    fun expect(
        key: String,
        page: String,
    ) {
        if (!opened || position(page) < 0) return
        home[key] = page
        save()
    }

    /** Puts a widget, an app or a folder on [page]; an app taken out of its folder lands on the grid on its own. */
    fun move(
        key: String,
        page: String,
    ) {
        if (!opened || position(page) < 0) return
        val i = apps.indexOfFirst { it.app.key == key }
        if (i >= 0 && apps[i].folder != null) apps[i] = apps[i].copy(folder = null)
        home[key] = page
        tidy()
        save()
    }

    /** Forgets a widget: its cell and its page. */
    fun forget(key: String) {
        if (!opened) return
        val known = cells.remove(key) != null
        if (home.remove(key) != null || known) save()
    }

    /** Pins [app] to [page]; one that's on the home screen already moves there, out of its folder. */
    fun pin(
        app: PinnedApp,
        page: String,
    ) {
        if (!opened) return
        apps.removeAll { it.app.key == app.key }
        apps += Pinned(app)
        home[app.key] = page.takeIf { position(it) >= 0 } ?: first
        tidy()
        save()
    }

    /** Takes an app, or a folder with all its apps, off the home screen. */
    fun unpin(key: String) {
        if (!opened) return
        take(key)
        tidy()
        save()
    }

    /** Takes [key] off the grid with its cell and its page; a folder with all its apps. */
    private fun take(key: String) {
        if (folders.remove(key)) {
            apps.filter { it.folder == key }.forEach { cells.remove(it.app.key) }
            apps.removeAll { it.folder == key }
        }
        apps.removeAll { it.app.key == key }
        cells.remove(key)
        home.remove(key)
    }

    /**
     * Drops the app [key] on [target]: into it if it's a folder, otherwise both go into a new folder in
     * [target]'s cell. Returns the folder's key, or null if [target] isn't an app or folder on the grid.
     */
    fun group(
        key: String,
        target: String,
    ): String? {
        if (!opened || key == target) return null
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
        if (!opened || key == before) return
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
        if (!opened) return
        dock = entries
        save()
    }
}
