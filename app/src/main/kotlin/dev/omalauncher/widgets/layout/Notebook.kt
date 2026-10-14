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

/** A pinned app as saved: an Android activity ([component], flattened, in [profile]) or a Linux entry. */
data class PinnedApp(
    val key: String,
    val label: String,
    val component: String? = null,
    val profile: Long? = null,
    val linux: LinuxEntry? = null,
)

enum class ItemKind {
    Widget, App, Folder,

    /** A thread's tile, which the launcher draws itself: no file and no app, only its key, which names the thread. */
    Thread,
}

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

/** A page of the notebook. Pages are ordered as listed, and stay when empty. */
data class NotebookPage(
    val id: String,
    val name: String,
    val items: List<NotebookItem> = emptyList(),
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
 * `{"columns": 6, "rows": R, "pages": [{"id", "name", "items": [{"key", "kind", "cell": {x, y, w, h}, "app", "apps"}]}], "dock": [app]}`.
 */
object NotebookStore {
    const val VERSION = 1

    fun write(notebook: Notebook): String {
        val pages = JSONArray()
        for (page in notebook.pages) {
            val items = JSONArray()
            for (item in page.items) {
                val o = JSONObject().put("key", item.key).put("kind", item.kind.name.lowercase())
                item.cell?.let { o.put("cell", JSONObject().put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)) }
                item.app?.let { o.put("app", app(it)) }
                if (item.kind == ItemKind.Folder) o.put("apps", JSONArray().also { a -> item.apps.forEach { a.put(app(it)) } })
                items.put(o)
            }
            pages.put(JSONObject().put("id", page.id).put("name", page.name).put("items", items))
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
                                kind = ItemKind.entries.first { it.name.lowercase() == o.getString("kind") },
                                cell = o.optJSONObject("cell")?.let { Cell(it.getInt("x"), it.getInt("y"), it.getInt("w"), it.getInt("h")) },
                                app = o.optJSONObject("app")?.let(::app),
                                apps = o.optJSONArray("apps")?.all(::app).orEmpty(),
                                // An app without what it opens is no entry at all.
                            ).also { require(it.kind != ItemKind.App || it.app != null) }
                        }
                    NotebookPage(p.getString("id"), p.getString("name"), items)
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
        )
    }
}
