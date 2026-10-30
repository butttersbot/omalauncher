package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.oma.WebEngine
import java.io.File
import kotlin.concurrent.thread

/*
 * The HTML engine's line in the update sheet (docs/CHAT-ARTIFACT-WORKSPACE.md, "Desktop HTML engine"): Chromium for HTML
 * artifacts, a component of its own. The release offers it (tools/soma-release/engine.json); the first time, the pill says
 * "install html engine" and nothing downloads until he taps it. It comes over the runners like the app, is checked against its
 * pinned sha256, and is unpacked into Soma's own folder (WebEngine). App updates don't download it again; only a new pinned
 * version, which comes with an app built against it, offers it again.
 */

/** The steps an engine install goes through, as its card lists them. */
val ENGINE_STEPS = listOf("fetch the engine", "check its sha256", "unpack it", "start it")

sealed interface EngineState {
    data object None : EngineState
    data class Offer(val release: Release, val file: ReleaseFile) : EngineState
    data class Installing(val release: Release, val step: Int) : EngineState
    data object Installed : EngineState
    /** [details] is for an agent, copied whole. */
    data class Failed(val reason: String, val details: String, val release: Release?, val file: ReleaseFile?) : EngineState
}

class EngineInstaller(
    private val hands: UpdateHands,
    private val dir: File,
    private val platform: String = platformKey(),
    /** The engine this build runs (its jcefmaven): only that version's natives fit it. */
    private val version: String = WebEngine.VERSION,
    private val installed: () -> Boolean = { WebEngine.installed() },
    private val unpack: (File) -> String? = { WebEngine.install(it) },
    private val started: () -> Unit = { WebEngine.start() },
) {
    var state by mutableStateOf<EngineState>(EngineState.None)
        private set

    @Volatile private var busy = false

    /** The engine file [release] offers this computer, or null: none for it, another version than this build's, or already in. */
    fun offered(release: Release?): ReleaseFile? =
        release?.engine?.takeIf { it.version == version }?.natives?.get(platform)?.takeIf { !installed() }

    /** From the record the last check read: the line shows when there is an engine to install. Leaves an install alone. */
    fun check(release: Release?) {
        if (busy) return
        val now = state
        if (now is EngineState.Installing || now is EngineState.Installed) return
        val file = offered(release)
        if (now is EngineState.Failed) { if (file != null && now.release == null) state = now.copy(release = release, file = file); return }
        state = if (file != null) EngineState.Offer(release!!, file) else EngineState.None
    }

    /** "install html engine" (or "try again"): on a thread of its own. */
    fun start(release: Release, file: ReleaseFile) {
        if (busy) return
        busy = true
        thread(name = "soma-engine-install", isDaemon = true) {
            try { install(release, file) } catch (e: Exception) { state = failed("something unexpected: ${e.message ?: e.javaClass.simpleName}", "unknown", release, file) } finally { busy = false }
        }
    }

    /** The install itself; runs on the caller's thread (tests call it directly). */
    internal fun install(release: Release, file: ReleaseFile) {
        val into = File(dir, "download").apply { mkdirs() }
        state = EngineState.Installing(release, 0)
        hands.fetchEngine(release, file, into)?.let { return run { state = failed("it couldn't be fetched from ${release.runner}: $it", "fetch", release, file) } }
        state = EngineState.Installing(release, 1)
        val jar = File(into, file.file)
        if (!matches(jar, file)) { jar.delete(); return run { state = failed("the download's sha256 didn't match the record (${file.file})", "check", release, file) } }
        state = EngineState.Installing(release, 2)
        unpack(jar)?.let { return run { state = failed("it couldn't be unpacked: $it", "unpack", release, file) } }
        jar.delete()
        state = EngineState.Installing(release, 3)
        started()
        state = EngineState.Installed
    }

    /** The sheet closed: "installed" has been read, so the line goes; "didn't install" stays until he tries again. */
    fun close() {
        if (state is EngineState.Installed) state = EngineState.None
    }

    private fun failed(reason: String, step: String, release: Release?, file: ReleaseFile?): EngineState.Failed {
        val details = listOf(
            "Soma's HTML engine didn't install on ${SomaHome.machine} ($platform).",
            "engine: $version" + (file?.let { "  file: ${it.file} (${it.size} bytes, sha256 ${it.sha256})" } ?: "") + (release?.let { "  from the runner on ${it.runner}" } ?: ""),
            "step: $step",
            "why: $reason",
            "folder: ${dir.path}",
            "the record: GET /release on the Mac Pro's runner, its \"engine\" (tools/soma-release/engine.json); try again from the pill once fixed.",
        ).joinToString("\n")
        runCatching { File(dir, "last-error.txt").apply { parentFile.mkdirs(); writeText(details + "\n") } }
        return EngineState.Failed(reason, details, release, file)
    }
}
