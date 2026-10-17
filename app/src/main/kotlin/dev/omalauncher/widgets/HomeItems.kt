package dev.omalauncher.widgets

import android.content.ComponentName
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import dev.omalauncher.canvas.moduleRef
import dev.omalauncher.AndroidApp
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.widgets.layout.LinuxEntry
import dev.omalauncher.widgets.layout.PinnedApp

/** Anything on the home grid. [key] identifies it in the notebook: its page and its cell. */
sealed interface HomeItem {
    val key: String
    val size: Pair<Int, Int>?
    val minSize: Pair<Int, Int>

    /** App shortcuts are fixed 1x1 icons; only widgets resize. */
    val resizable: Boolean get() = true
}

/** Equal while its spec is the same object, so pages that didn't change skip recomposition. */
data class WidgetItem(
    val spec: WidgetSpec,
) : HomeItem {
    override val key get() = spec.file.name
    override val size get() = spec.size
    override val minSize get() = spec.minSize
}

/** An app pinned from the drawer: an Android activity or a Linux .desktop entry. In [folder], it has no cell of its own. */
data class Shortcut(
    override val key: String,
    val label: String,
    val component: ComponentName? = null,
    /** [component]'s profile, as in [AndroidApp.profile]. */
    val profile: Long? = null,
    val linux: LinuxApp? = null,
    val folder: String? = null,
) : HomeItem {
    override val size get() = 1 to 1
    override val minSize get() = 1 to 1
    override val resizable get() = false
}

/** Pinned apps grouped under one 1x1 tile, made by dropping an app on another, like stock Android. */
data class Folder(
    override val key: String,
    val apps: List<Shortcut>,
) : HomeItem {
    override val size get() = 1 to 1
    override val minSize get() = 1 to 1
    override val resizable get() = false
}

/**
 * A thread's tile ([threadTileKey]): the phone's builder or a project thread on the Mac, drawn by the launcher
 * from how the thread stands. It has no file and no app; what it shows is looked up by its [key] as it is drawn.
 */
data class ThreadItem(
    override val key: String,
) : HomeItem {
    override val size get() = AGENT_TILE_SIZE
    override val minSize get() = AGENT_TILE_MIN
}

/**
 * A cockpit module ([moduleRef]): drawn by the launcher from shared state, like a thread's tile, at any size from one
 * cell; what it shows changes with its size. It has no file and no app. A key this build doesn't know keeps its place.
 */
data class ModuleItem(
    override val key: String,
) : HomeItem {
    override val size get() = moduleRef(key)?.kind?.size ?: (1 to 1)
    override val minSize get() = 1 to 1
}

/**
 * Puts the module [key] on [page] at its starting size: the first free cells there, else the next page with room, else a
 * new page, as any new tile lands ([WidgetLayout.land]). Returns the page it is on; null, and nothing changed, for a key
 * that is no module's, one already on home, or a page that isn't there. Before home has a grid it waits on [page] with no
 * cells and is placed when that page is laid out.
 */
fun landModule(
    notebook: LiveNotebook,
    layout: WidgetLayout,
    key: String,
    page: String,
    itemsOn: (String) -> List<HomeItem>,
): String? {
    if (moduleRef(key) == null || notebook.pageOf(key) != null || notebook.position(page) < 0) return null
    notebook.move(key, page)
    return layout.land(ModuleItem(key), itemsOn) ?: page
}

/** A desktop entry as the notebook keeps it, and back: a snapshot for when the entry is gone. */
fun LinuxApp.entry() = LinuxEntry(name, exec, terminal, id, wmClass, icon)

fun LinuxEntry.app() = LinuxApp(name, exec, terminal, id, wmClass, icon)

private fun Pinned.shortcut() =
    Shortcut(
        key = app.key,
        label = app.label,
        component = app.component?.let(ComponentName::unflattenFromString),
        profile = app.profile,
        linux = app.linux?.app(),
        folder = folder,
    )

/**
 * Pinned apps and their folders as home shows them. They live in the [notebook], with the pages and
 * cells; this is the view of them with Android's own types.
 */
class Shortcuts(
    private val notebook: LiveNotebook,
) {
    /** Every pinned app, those in folders too. */
    val items: List<Shortcut> by derivedStateOf { notebook.apps.map { it.shortcut() } }

    /** Apps and folders on the grid; apps in a folder show inside it instead. */
    fun onGrid(): List<HomeItem> =
        items.filter { it.folder == null } +
            notebook.folders.map { f -> Folder(f, items.filter { it.folder == f }) }

    /**
     * Drops [app] on [target]: into it if it's a folder, otherwise both go into a new
     * folder in [target]'s cell. Returns the folder's key, or null if [target] isn't an app or folder.
     */
    fun group(
        app: Shortcut,
        target: HomeItem,
    ): String? = if (target is Shortcut || target is Folder) notebook.group(app.key, target.key) else null

    /** Moves an app within its folder to just before [before], or after the folder's last app if that's null. */
    fun moveBefore(
        key: String,
        before: String?,
    ) = notebook.moveBefore(key, before)

    /** Pins to the page with the id [page]. */
    fun addAndroid(
        app: AndroidApp,
        page: String,
    ) = notebook.pin(PinnedApp("app:" + app.key, app.label, component = app.component.flattenToString(), profile = app.profile), page)

    /** A snapshot of the desktop entry, for when it's gone; while it exists, the launcher runs the current one. */
    fun addLinux(
        app: LinuxApp,
        page: String,
    ) = notebook.pin(PinnedApp("linux:" + app.id, app.name, linux = app.entry()), page)

    /** Puts an app or folder on [page]; an app moved out of its folder lands on the grid on its own. */
    fun move(
        key: String,
        page: String,
    ) = notebook.move(key, page)

    /** Takes an app, or a folder with all its apps, off the home screen. */
    fun remove(key: String) = notebook.unpin(key)
}
