package dev.omalauncher.theme.pack

import java.io.File
import java.io.InputStream

/**
 * Where a pack's files are: a folder on disk, the app's assets, or a map in a test. Paths are
 * relative to the pack with `/` between names. Neither function throws.
 */
interface PackSource {
    /** What is at [path], without reading it. */
    fun stat(path: String): PackEntry

    /** The file at [path] for the caller to close, or null when [stat] wouldn't call it a file. */
    fun open(path: String): InputStream?
}

sealed interface PackEntry {
    data object Missing : PackEntry

    /** The path leads out of the pack, by its spelling or through a link. */
    data object Outside : PackEntry

    data class File(
        val size: Long,
    ) : PackEntry
}

/** A pack unpacked into [root]. Nothing outside [root] is ever read, whatever a path or a symlink says. */
class DirectoryPackSource(
    private val root: File,
) : PackSource {
    override fun stat(path: String): PackEntry {
        if (PackSchema.pathProblem(path) != null) return PackEntry.Outside
        return try {
            val base = root.canonicalFile
            val file = File(base, path).canonicalFile
            when {
                !file.path.startsWith(base.path + File.separator) -> PackEntry.Outside
                !file.isFile -> PackEntry.Missing
                else -> PackEntry.File(file.length())
            }
        } catch (e: Exception) {
            PackEntry.Missing
        }
    }

    override fun open(path: String): InputStream? {
        if (stat(path) !is PackEntry.File) return null
        return try {
            File(root.canonicalFile, path).canonicalFile.inputStream()
        } catch (e: Exception) {
            null
        }
    }
}
