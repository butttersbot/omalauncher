package dev.omalauncher.desktop

import org.json.JSONObject
import java.io.File

/**
 * Where Soma desktop keeps what it keeps: `~/.config/soma`, as the phone keeps its in the app's private files. The
 * thread, the tab book, the engine's notes and the settings are files there; the runner keys are not (RunnerKeys).
 */
object SomaHome {
    /** Read each time, so a test that points `soma.home` elsewhere is followed; made when missing. */
    val dir: File get() = File(System.getProperty("soma.home") ?: (System.getProperty("user.home") + "/.config/soma")).apply { mkdirs() }

    val chat get() = File(dir, "chat.json")
    val book get() = File(dir, "tabs.json")
    val notes get() = File(dir, "threads.json")
    val runners get() = File(dir, "runners.json")
    val settings get() = File(dir, "settings.json")

    /** The window's canvas: its verb log (DesktopCanvas). */
    val canvas get() = File(dir, "canvas.json")
    val socket get() = File(dir, "soma.port")
    val lock get() = File(dir, "soma.lock")

    /**
     * The one folder runner keys are taken from: where `oma-runner pair` puts a key for this machine's own Soma, and
     * where he moves one Taildropped from another runner. Never ~/Downloads or ~/Inbox, where any download could land.
     */
    val delivered: List<File> get() = listOf(File(dir, "runners"))

    /** A small JSON file's object, or an empty one. */
    fun read(file: File): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    /** Writes [json] to [file] whole: beside it first, then moved over it, readable by this user alone. */
    @Synchronized fun write(file: File, json: JSONObject) {
        val fresh = File(file.parentFile, "." + file.name + ".new")
        fresh.writeText(json.toString())
        fresh.setReadable(false, false); fresh.setReadable(true, true); fresh.setWritable(false, false); fresh.setWritable(true, true)
        if (!fresh.renameTo(file)) { file.delete(); fresh.renameTo(file) }
    }
}
