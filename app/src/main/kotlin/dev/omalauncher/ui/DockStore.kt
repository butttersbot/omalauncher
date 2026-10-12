package dev.omalauncher.ui

import androidx.compose.runtime.mutableStateListOf
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.widgets.LiveNotebook
import dev.omalauncher.widgets.app
import dev.omalauncher.widgets.entry
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import org.json.JSONArray
import org.json.JSONObject

/** Key of the dock's built-in entry: the Oma chat, which is not an installed app. */
const val OMA_CHAT_KEY = "oma:chat"

/**
 * One thing pinned to the dock. An app is keyed and saved the way a home shortcut is
 * ([dev.omalauncher.widgets.Shortcuts]): `app:` and the Android app's key, or `linux:` and the desktop id.
 * The built-in Oma chat has [OMA_CHAT_KEY] and neither a [component] nor a [linux] entry; it is always
 * in the dock, since nothing could pin it back.
 */
data class DockEntry(
    val key: String,
    val label: String,
    /** An Android activity, flattened as ComponentName writes it. */
    val component: String? = null,
    /** [component]'s profile, as in [dev.omalauncher.widgets.Shortcut.profile]. */
    val profile: Long? = null,
    /** A snapshot of the desktop entry, as home shortcuts keep one. */
    val linux: LinuxApp? = null,
) {
    /** The Oma chat rather than an app. */
    val builtIn get() = key == OMA_CHAT_KEY
}

/** The dock of a launcher that never saved one: the Oma chat alone. */
internal val DefaultDock = listOf(DockEntry(OMA_CHAT_KEY, "Oma"))

/** Pins [entry] after the others; one that's already pinned keeps its place and takes the new label. */
internal fun List<DockEntry>.pinned(entry: DockEntry): List<DockEntry> =
    if (any { it.key == entry.key }) map { if (it.key == entry.key) entry else it } else this + entry

/** Takes the app [key] out; the Oma chat stays. */
internal fun List<DockEntry>.unpinned(key: String): List<DockEntry> = filter { it.key != key || it.builtIn }

/** Moves the entry [key] by [step] places (negative is earlier), stopping at either end. */
internal fun List<DockEntry>.moved(
    key: String,
    step: Int,
): List<DockEntry> {
    val from = indexOfFirst { it.key == key }
    if (from < 0) return this
    val to = (from + step).coerceIn(0, lastIndex)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** What the long-press menu of a dock entry offers. */
internal enum class DockAction(
    val label: String,
) {
    Earlier("move earlier"),
    Later("move later"),
    Unpin("unpin from dock"),
}

/** The actions for the entry [key]: the ends can't move further out, and the Oma chat stays pinned. */
internal fun List<DockEntry>.actions(key: String): List<DockAction> {
    val i = indexOfFirst { it.key == key }
    if (i < 0) return emptyList()
    return listOfNotNull(
        DockAction.Earlier.takeIf { i > 0 },
        DockAction.Later.takeIf { i < lastIndex },
        DockAction.Unpin.takeIf { !this[i].builtIn },
    )
}

/** The list after [action] on the entry [key]. */
internal fun List<DockEntry>.after(
    action: DockAction,
    key: String,
): List<DockEntry> =
    when (action) {
        DockAction.Earlier -> moved(key, -1)
        DockAction.Later -> moved(key, 1)
        DockAction.Unpin -> unpinned(key)
    }

/** How many [slot]-sized icons fit in [room] with at least [gap] between them; all sizes in the same unit. */
internal fun dockFits(
    room: Int,
    slot: Int,
    gap: Int,
): Int = if (slot <= 0 || room < slot) 0 else (room + gap) / (slot + gap)

private const val DOCK_VERSION = 1

/** The dock as saved: a [Versioned] array whose entries carry a home shortcut's fields under the same names. */
internal fun writeDock(entries: List<DockEntry>): String {
    val array = JSONArray()
    for (e in entries) {
        val o = JSONObject().put("key", e.key).put("label", e.label)
        e.component?.let { o.put("component", it) }
        e.profile?.let { o.put("profile", it) }
        e.linux?.let {
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
        array.put(o)
    }
    return Versioned.write(DOCK_VERSION, array)
}

/**
 * The saved dock, or [DefaultDock] when there is none or it can't be read. Entries that are neither an app
 * nor the Oma chat are skipped, and a key keeps its first entry only. A dock saved without the Oma chat
 * can only be damaged, as no menu unpins it: it gets the chat back, first.
 */
internal fun readDock(text: String?): List<DockEntry> {
    val array = Versioned.read(text, DOCK_VERSION) as? JSONArray ?: return DefaultDock
    val saved = readEntries(array)
    return if (saved.any { it.builtIn }) saved else DefaultDock + saved
}

private fun readEntries(array: JSONArray): List<DockEntry> =
    array
        .mapObjects { o ->
            val linux =
                o.optJSONObject("linux")?.let {
                    LinuxApp(
                        name = it.getString("name"),
                        exec = it.getString("exec"),
                        terminal = it.optBoolean("terminal"),
                        id = it.getString("id"),
                        wmClass = it.optString("wmClass").ifEmpty { null },
                        icon = it.optString("icon").ifEmpty { null },
                    )
                }
            DockEntry(
                key = o.getString("key"),
                label = o.getString("label"),
                component = o.optString("component").ifEmpty { null },
                profile = if (o.has("profile")) o.getLong("profile") else null,
                linux = linux,
            ).takeIf { it.builtIn || it.component != null || it.linux != null }
        }.distinctBy { it.key }

/** A dock entry as the notebook keeps it, and back. */
internal fun DockEntry.pinned() = PinnedApp(key, label, component, profile, linux?.entry())

internal fun PinnedApp.dockEntry() = DockEntry(key, label, component, profile, linux?.app())

/**
 * The dock's pinned apps in order. They are saved with the notebook, the one store of the home layout;
 * the `dock` preferences file they had before it is left as it was, as the backup of that time.
 * [load] gives the saved document, if any, and [save] keeps a new one.
 */
class DockStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
) {
    /** The dock of [notebook], read and written as the document the dock always was. */
    constructor(notebook: LiveNotebook) : this(
        load = { writeDock(notebook.dock.map { it.dockEntry() }) },
        save = { notebook.changeDock(readDock(it).map { entry -> entry.pinned() }) },
    )

    val items = mutableStateListOf<DockEntry>()

    init {
        items += readDock(load())
    }

    /** Reads the saved dock again, after what [load] reads from was replaced. */
    fun reload() {
        val saved = readDock(load())
        if (saved == items.toList()) return
        items.clear()
        items += saved
    }

    fun isPinned(key: String) = items.any { it.key == key }

    fun pin(entry: DockEntry) = replace(items.pinned(entry))

    fun unpin(key: String) = replace(items.unpinned(key))

    internal fun act(
        action: DockAction,
        key: String,
    ) = replace(items.after(action, key))

    private fun replace(changed: List<DockEntry>) {
        // A copy first: a change that changes nothing hands back [items] itself, which clearing would empty.
        val next = changed.toList()
        if (next == items.toList()) return
        items.clear()
        items += next
        save(writeDock(next))
    }
}
