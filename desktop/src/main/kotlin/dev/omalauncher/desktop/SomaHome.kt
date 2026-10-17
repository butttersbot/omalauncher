package dev.omalauncher.desktop

import org.json.JSONObject
import java.io.File

/**
 * Where Soma desktop keeps what it keeps: `~/.config/soma`, as the phone keeps its in the app's private files. The
 * thread, the tab book, the engine's notes and the settings are files there; the runner keys are not (RunnerKeys).
 */
object SomaHome {
    /** Read each time, so a test that points `soma.home` (or SOMA_HOME, for a whole process) elsewhere is followed; made when missing. */
    val dir: File get() = File(System.getProperty("soma.home") ?: System.getenv("SOMA_HOME") ?: (System.getProperty("user.home") + "/.config/soma")).apply { mkdirs() }

    val chat get() = File(dir, "chat.json")
    val book get() = File(dir, "tabs.json")
    val notes get() = File(dir, "threads.json")
    val runners get() = File(dir, "runners.json")
    val settings get() = File(dir, "settings.json")

    /** The window's canvas: its verb log (DesktopCanvas). */
    val canvas get() = File(dir, "canvas.json")
    val socket get() = File(dir, "soma.port")

    /** This machine's run-time approvals for widget commands (CommandGate): written only by his tap here or his signed OK. */
    val approved get() = File(dir, "approved.json")

    /** Widget files from elsewhere, each in quarantine until he approves it here (WidgetLibrary). */
    val library get() = File(dir, "widgets")

    /** Where the `soma` the front agent runs is put (CanvasDoor.shim): first on its PATH. */
    val bin get() = File(dir, "bin")
    val lock get() = File(dir, "soma.lock")

    /**
     * The one folder runner keys are taken from: where `oma-runner pair` puts a key for this machine's own Soma, and
     * where he moves one Taildropped from another runner. Never ~/Downloads or ~/Inbox, where any download could land.
     */
    val delivered: List<File> get() = listOf(File(dir, "runners"))

    /**
     * This machine's name, as a signed approval names it and the runner knows it (OMA_RUNNER_SELF when set): the host name,
     * lower case, without the ".local" a network may or may not add, so it doesn't change with the network.
     */
    val machine: String by lazy {
        System.getenv("OMA_RUNNER_SELF")?.ifBlank { null }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("desktop").lowercase().removeSuffix(".local").removeSuffix(".lan")
    }

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
