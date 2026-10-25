package dev.omalauncher.linux

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `oma drop get` and `list` without the phone's adb (Boss, 2026-10-24: Taildrop must reach Linux with Wireless debugging
 * off, as it is off home Wi-Fi; builds and agent mail come that way). Tailscale saves what it receives in shared storage,
 * which Linux cannot see; Oma, with All files access, can, so Linux asks it through the phone bridge (command
 * "taildrop") and Oma copies the files into Linux's own folder. Only a copy as long as the original is reason to remove
 * the original; a name still ending in .partial is a file Tailscale is receiving and is left alone.
 */
object TaildropInbox {
    /** The folders Tailscale saves into: Download/Taildrop, or Taildrop at the storage's top (as on the new Fold). */
    fun folders(storage: File) = listOf(File(storage, "Download/Taildrop"), File(storage, "Taildrop"))

    /** What waits: every whole file of [folders], oldest first. */
    fun waiting(folders: List<File>): List<File> =
        folders.flatMap { dir -> dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".partial") }.orEmpty() }
            .sortedBy { it.lastModified() }

    /** A name in [dir] for [name] that no file has yet: NAME, or "NAME (2)", "NAME (3)"... before the extension. */
    fun freeName(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var file = File(dir, name)
        var number = 2
        while (file.exists()) file = File(dir, "$stem (${number++})$ext")
        return file
    }

    /**
     * Lists what waits ([listOnly]), or copies each file into [dest] (made if missing) and removes the phone's unless
     * [keep]. [guestPath] says each copy's path as Linux sees it. Each file: name, bytes, and state listed, moved,
     * copied (kept, or not removable), partial or failed.
     */
    fun take(folders: List<File>, dest: File?, keep: Boolean, listOnly: Boolean, guestPath: (File) -> String): JSONObject {
        val files = JSONArray()
        if (!listOnly) dest!!.mkdirs()
        for (file in waiting(folders)) {
            val bytes = file.length()
            val entry = JSONObject().put("name", file.name).put("bytes", bytes)
            files.put(entry)
            if (listOnly) { entry.put("state", "listed"); continue }
            val target = freeName(dest!!, file.name)
            val copied = runCatching { file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } } }
            entry.put("path", guestPath(target))
            when {
                copied.isFailure -> { target.delete(); entry.put("state", "failed").put("error", copied.exceptionOrNull()?.message ?: "copy failed") }
                target.length() != bytes -> entry.put("state", "partial").put("got", target.length())
                keep -> entry.put("state", "copied")
                file.delete() -> entry.put("state", "moved")
                else -> entry.put("state", "copied").put("error", "could not remove it from the phone")
            }
        }
        return JSONObject().put("ok", true).put("files", files)
    }
}
