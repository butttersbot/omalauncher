package dev.omalauncher.theme.pack

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** A pack held in memory: each path's bytes, or just a size for a file too big to make. */
class MapPackSource(
    private val files: Map<String, ByteArray> = emptyMap(),
    private val sizes: Map<String, Long> = emptyMap(),
) : PackSource {
    /** Every path asked for, to show the loader never reaches for one outside the pack. */
    val asked = ArrayList<String>()

    override fun stat(path: String): PackEntry {
        asked += path
        return sizes[path]?.let { PackEntry.File(it) } ?: files[path]?.let { PackEntry.File(it.size.toLong()) } ?: PackEntry.Missing
    }

    override fun open(path: String): InputStream? {
        asked += path
        return files[path]?.let(::ByteArrayInputStream)
    }
}

/** A pack of just [manifest], with [files] beside it. */
fun packOf(
    manifest: String,
    vararg files: String,
) = MapPackSource(mapOf(PackLoader.MANIFEST to manifest.toByteArray()) + files.associateWith { "x".toByteArray() })

fun loadPack(
    manifest: String,
    vararg files: String,
) = PackLoader.load(packOf(manifest, *files), "test")

/** A file of the app module, whichever directory Gradle runs the tests from. */
fun moduleFile(path: String): File = listOf(File(path), File("app", path)).firstOrNull { it.exists() } ?: File(path)

/** Console's manifest with [changes] laid over it and the keys in [without] left out. */
fun consoleManifest(
    changes: Map<String, Any> = emptyMap(),
    without: Set<String> = emptySet(),
) = PackManifest.write((ConsolePack.tokens.manifest() + changes) - without)
