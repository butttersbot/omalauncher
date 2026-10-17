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

/**
 * The launcher's main source roots: the app's, and the shared chat module's (common, phone and desktop) (Soma's chat, which the Mac app shares;
 * cockpit/DESIGN.md). A test that reads the sources to pin a rule reads both, or a file moved to the module drops
 * out of its sight without the test failing.
 */
fun mainSourceRoots(): List<File> =
    listOf(moduleFile("src/main/kotlin/dev/omalauncher")) +
        listOf("commonMain", "androidMain", "desktopMain").map { moduleFile("../chat/src/$it/kotlin/dev/omalauncher") }.filter { it.isDirectory }

/** Every Kotlin source under [dirs] of each main root ([mainSourceRoots]), or under the whole roots when none is named. */
fun mainSources(vararg dirs: String): List<File> = mainSourceRoots().flatMap { root ->
    (if (dirs.isEmpty()) listOf(root) else dirs.map { File(root, it) }).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
}

/** The main source at [path] (as "assist/MacThreads.kt"), in whichever root has it. */
fun mainSource(path: String): File = mainSourceRoots().map { File(it, path) }.firstOrNull { it.isFile } ?: File(mainSourceRoots().first(), path)

/** [file]'s path under the main root it is in, as "assist/MacThreads.kt". */
fun mainSourcePath(file: File): String = mainSourceRoots().first { file.canonicalPath.startsWith(it.canonicalPath + File.separator) }.let { file.relativeTo(it).invariantSeparatorsPath }

/** A file of the app module, whichever directory Gradle runs the tests from. */
fun moduleFile(path: String): File = listOf(File(path), File("app", path)).firstOrNull { it.exists() } ?: File(path)

/** Console's manifest with [changes] laid over it and the keys in [without] left out. */
fun consoleManifest(
    changes: Map<String, Any> = emptyMap(),
    without: Set<String> = emptySet(),
) = PackManifest.write((ConsolePack.tokens.manifest() + changes) - without)
