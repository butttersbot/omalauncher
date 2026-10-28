package dev.omalauncher.upgrade

import java.io.File

/**
 * Replaces [file] in one rename, so a process reading or exec'ing it (a
 * running guest) never sees it half written.
 */
fun writeAtomically(
    file: File,
    bytes: ByteArray,
    executable: Boolean = false,
) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    // Synced before the rename: after a power cut the file is the old one or the new one, never empty.
    tmp.outputStream().use {
        it.write(bytes)
        it.fd.sync()
    }
    if (executable) tmp.setExecutable(true, false)
    if (!tmp.renameTo(file)) {
        tmp.delete()
        error("can't replace $file")
    }
}
