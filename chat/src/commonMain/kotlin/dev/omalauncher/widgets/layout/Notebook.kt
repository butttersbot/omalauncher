package dev.omalauncher.widgets.layout

import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.GRID_COLUMNS
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A Linux .desktop entry as a pinned app remembers it, for when the entry is gone. */
data class LinuxEntry(
    val name: String,
    val exec: String,
    val terminal: Boolean,
    val id: String,
    val wmClass: String? = null,
    val icon: String? = null,
)

/** A shortcut an Android app made, as a pinned app remembers it: the app's package, and the shortcut's id there. */
data class ShortcutEntry(
    val packageName: String,
    val id: String,
)

/**
 * A pinned app as saved: an Android activity ([component], flattened, in [profile]), a Linux entry, a [shortcut] an
 * Android app made (in [profile] too), or an [owl] app, by its file's name.
 */
data class PinnedApp(
    val key: String,
    val label: String,
    val component: String? = null,
    val profile: Long? = null,
    val linux: LinuxEntry? = null,
    val shortcut: ShortcutEntry? = null,
    val owl: String? = null,
)

enum class ItemKind {
    Widget, App, Folder,

    /** A thread's tile, which the launcher draws itself: no file and no app, only its key, which names the thread. */
    Thread,

    /** A cockpit module, drawn by the launcher itself like a thread's tile: its key alone says which (`module:…`). */
    Module,

    /** A picture or GIF in a panel of the Manga look: its key alone says which (`art:<id>`, [artKey]); its file is the art store's. */
    Art,
}

/** Ahead of an art item's id in its key. */
const val ART_PREFIX = "art:"

private val ART_ID = Regex("[0-9a-f]{16}")

/** The key of the art [id] (16 lowercase hex characters, as the art store makes them). */
fun artKey(id: String): String = ART_PREFIX + id

/** The art id in [key], or null when it is not an art item's key. */
fun artIdOf(key: String): String? = key.removePrefix(ART_PREFIX).takeIf { key.startsWith(ART_PREFIX) && ART_ID.matches(it) }

/**
 * One thing on a notebook page. A widget is its file, named by [key]; an app carries itself in [app];
 * a folder carries its [apps], in order; a thread's tile is its [key] alone. [cell] is null only for an item not placed yet.
 */
data class NotebookItem(
    val key: String,
    val kind: ItemKind,
    val cell: Cell?,
    val app: PinnedApp? = null,
    val apps: List<PinnedApp> = emptyList(),
)

/**
 * A page of the notebook. Pages are ordered as listed, and stay when empty. [panels] is the page's panel layout in the Manga
 * look ([PanelLayouts]), or null for the free grid; in a panel page every item's cell is exactly one panel's cell.
 */
data class NotebookPage(
    val id: String,
    val name: String,
    val items: List<NotebookItem> = emptyList(),
    val panels: String? = null,
)

/** The whole home layout: named pages on one fixed grid of [columns] by [rows], and the dock. */
data class Notebook(
    val rows: Int,
    val pages: List<NotebookPage>,
    val dock: List<PinnedApp> = emptyList(),
    val columns: Int = GRID_COLUMNS,
)

/**
 * The `notebook` store's document, in the [Versioned] wrapper:
 * `{"columns": 6, "rows": R, "pages": [{"id", "name", "panels"?, "items": [{"key", "kind", "cell": {x, y, w, h}, "app", "apps"}]}], "dock": [app]}`.
 */
/** Ahead of a module's key ([dev.omalauncher.canvas.MODULE_PREFIX]): the store knows a module by its key alone. */
private const val MODULE_KEY = "module:"

/** The kind an item is read as: a widget whose key is a module's is that module, as it is saved ([NotebookStore.write]). */
private fun savedKind(key: String, saved: ItemKind): ItemKind = if (saved == ItemKind.Widget && key.startsWith(MODULE_KEY)) ItemKind.Module else saved

object NotebookStore {
    const val VERSION = 1

    fun write(notebook: Notebook): String {
        val pages = JSONArray()
        for (page in notebook.pages) {
            val items = JSONArray()
            for (item in page.items) {
                // A module is saved as a widget, which is what a launcher from before modules takes it for: a widget whose file
                // isn't there holds its place and shows nothing, where an unknown kind would be dropped and the pages set
                // aside without it. Its key says it is a module ([savedKind]).
                val kind = if (item.kind == ItemKind.Module) ItemKind.Widget else item.kind
                val o = JSONObject().put("key", item.key).put("kind", kind.name.lowercase())
                item.cell?.let { o.put("cell", JSONObject().put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)) }
                item.app?.let { o.put("app", app(it)) }
                if (item.kind == ItemKind.Folder) o.put("apps", JSONArray().also { a -> item.apps.forEach { a.put(app(it)) } })
                items.put(o)
            }
            // A page's panel layout is written only when it has one, so a free page reads as it always did.
            pages.put(JSONObject().put("id", page.id).put("name", page.name).putOpt("panels", page.panels).put("items", items))
        }
        val data =
            JSONObject()
                .put("columns", notebook.columns)
                .put("rows", notebook.rows)
                .put("pages", pages)
                .put("dock", JSONArray().also { a -> notebook.dock.forEach { a.put(app(it)) } })
        return Versioned.write(VERSION, data)
    }

    /** A notebook as read, and how many of its saved entries (pages, items, apps) were [skipped] because they didn't parse or came twice. */
    class Read(
        val notebook: Notebook,
        val skipped: Int,
    )

    /**
     * The saved notebook, or null if there is none or it can't be read: no pages, or no rows, is not a notebook.
     * An entry that doesn't parse is skipped, not fatal; so is a page whose id, or an item whose key, came before.
     * Nothing it is given makes it throw, errors included (Android's org.json recurses without a limit).
     */
    fun read(text: String?): Notebook? = inspect(text)?.notebook

    /**
     * Whether [text] was written by a launcher newer than this one. Such a document may hold what this one
     * doesn't know of, and writing it back would lose that: it is read, never rewritten.
     */
    fun newer(text: String?): Boolean =
        try {
            if (text.isNullOrBlank()) false else (JSONTokener(text).nextValue() as? JSONObject)?.let { it.has("v") && it.has("data") && it.getInt("v") > VERSION } ?: false
        } catch (e: Throwable) {
            false
        }

    /** As [read], and saying how much was left out: what is saved from a notebook that skipped something no longer has it. */
    fun inspect(text: String?): Read? =
        try {
            (Versioned.read(text, VERSION) as? JSONObject)?.let(::parse)
        } catch (e: Throwable) {
            null
        }

    private fun parse(data: JSONObject): Read? {
        var skipped = 0

        fun <T : Any> JSONArray.all(parse: (JSONObject) -> T?): List<T> = mapObjects(parse).also { skipped += length() - it.size }

        fun <T> List<T>.once(key: (T) -> String): List<T> = distinctBy(key).also { skipped += size - it.size }

        val seen = HashSet<String>()
        val pages =
            data
                .getJSONArray("pages")
                .all { p ->
                    val items =
                        (p.optJSONArray("items") ?: JSONArray()).all { o ->
                            NotebookItem(
                                key = o.getString("key"),
                                kind = savedKind(o.getString("key"), ItemKind.entries.first { it.name.lowercase() == o.getString("kind") }),
                                cell = o.optJSONObject("cell")?.let { Cell(it.getInt("x"), it.getInt("y"), it.getInt("w"), it.getInt("h")) },
                                app = o.optJSONObject("app")?.let(::app),
                                apps = o.optJSONArray("apps")?.all(::app).orEmpty(),
                                // An app without what it opens is no entry at all.
                            ).also { require(it.kind != ItemKind.App || it.app != null) }
                                // Art is its key alone: one that names no art id is no entry.
                                .also { require(it.kind != ItemKind.Art || artIdOf(it.key) != null) }
                        }
                    // A layout this build doesn't know is the free grid: the cells are kept as they are.
                    NotebookPage(p.getString("id"), p.getString("name"), items, p.optString("panels").takeIf(PanelLayouts::known))
                }.once { it.id }
                .map { page -> page.copy(items = page.items.filter { seen.add(it.key) }.also { skipped += page.items.size - it.size }) }
        val rows = data.getInt("rows")
        if (pages.isEmpty() || rows < 1) return null
        val notebook =
            Notebook(
                rows = rows,
                pages = pages,
                dock = data.optJSONArray("dock")?.all(::app).orEmpty(),
                columns = data.optInt("columns", GRID_COLUMNS),
            )
        return Read(notebook, skipped)
    }

    private fun app(a: PinnedApp): JSONObject {
        val o = JSONObject().put("key", a.key).put("label", a.label)
        a.component?.let { o.put("component", it) }
        a.profile?.let { o.put("profile", it) }
        a.linux?.let {
            o.put(
                "linux",
                JSONObject()
                    .put("name", it.name)
                    .put("exec", it.exec)
                    .put("terminal", it.terminal)
                    .put("id", it.id)
                    .putOpt("wmClass", it.wmClass)
                    .putOpt("icon", it.icon),
            )
        }
        a.shortcut?.let { o.put("shortcut", JSONObject().put("package", it.packageName).put("id", it.id)) }
        a.owl?.let { o.put("owl", it) }
        return o
    }

    /** A pinned app as the `shortcuts` store and this one write it; throws on an entry without what an app needs. */
    internal fun app(o: JSONObject): PinnedApp {
        val linux =
            o.optJSONObject("linux")?.let {
                LinuxEntry(
                    name = it.getString("name"),
                    exec = it.getString("exec"),
                    terminal = it.optBoolean("terminal"),
                    id = it.getString("id"),
                    wmClass = it.optString("wmClass").ifEmpty { null },
                    icon = it.optString("icon").ifEmpty { null },
                )
            }
        return PinnedApp(
            key = o.getString("key"),
            label = o.getString("label"),
            component = o.optString("component").ifEmpty { null },
            profile = if (o.has("profile")) o.getLong("profile") else null,
            linux = linux,
            shortcut = o.optJSONObject("shortcut")?.let { ShortcutEntry(it.getString("package"), it.getString("id")) },
            owl = o.optString("owl").ifEmpty { null },
        )
    }
}
