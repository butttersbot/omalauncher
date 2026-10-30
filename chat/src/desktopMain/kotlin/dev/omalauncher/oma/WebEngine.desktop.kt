package dev.omalauncher.oma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import me.friwi.jcefmaven.impl.step.extract.TarGzExtractor
import org.cef.CefApp
import org.cef.CefSettings
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/*
 * Chromium for HTML artifacts on the desktop (docs/CHAT-ARTIFACT-WORKSPACE.md, "Desktop HTML engine"; Boss, 2026-10-29: in
 * the side panel, the same engine on the Macs and Linux). jcefmaven's Chromium is a separate, versioned component: soma-release
 * publishes its natives pinned by sha256 (tools/soma-release/engine.json) and the update pill installs them here, once. This
 * never downloads anything itself, and jcefmaven is never let to either (no mirrors). Until it is installed, or if it fails to
 * start, the panel shows the source and says why.
 */

/** The switches Chromium starts with, in one place: everything here is also in [WebEngineTest]. */
object WebEngineFlags {
    /**
     * [os] as `os.name`; [software] after the GPU failed. No --force-device-scale-factor: JCEF already sizes Chromium's window
     * by Soma's scale, and Chromium scaled it again (MG1 walk: a 941-wide panel was a 1882-wide page, three quarters unseen).
     * Soma's scale is the page's zoom instead ([zoomLevel]).
     */
    fun chromium(os: String, scale: Double, software: Boolean): List<String> = buildList {
        // No first-run window: on Linux it opened behind the app and held Chromium's start on the AWT thread for good (MG1 spike).
        add("--no-first-run"); add("--no-default-browser-check")
        // Nothing of its own reaching out: no component updates, extensions, sync, background fetches or translation.
        add("--disable-component-update"); add("--disable-extensions"); add("--disable-sync")
        add("--disable-background-networking"); add("--disable-default-apps"); add("--disable-features=Translate,MediaRouter,OptimizationHints")
        // Soma's window is an X11 window (XWayland on Omarchy): Chromium must draw through X11 to live inside it.
        if (!os.lowercase().contains("mac")) add("--ozone-platform=x11")
        if (software) { add("--disable-gpu"); add("--disable-gpu-compositing") }
    }

    /**
     * The zoom level that draws pages at Soma's [scale] on Linux, where Chromium sees the X11 window at 1x (CEF's levels: each
     * step is 1.2x, so 2x is ln 2 / ln 1.2). The Mac's Retina scale reaches Chromium on its own: 0 there.
     */
    fun zoomLevel(os: String, scale: Double): Double =
        if (os.lowercase().contains("mac") || scale <= 0 || scale == 1.0) 0.0 else kotlin.math.ln(scale) / kotlin.math.ln(1.2)

    /** The display scale Soma draws at: the uiScale it was started with, else 1. */
    fun scale(): Double = System.getProperty("sun.java2d.uiScale")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
}

/** Where the engine stands, as the panel says it. */
sealed interface WebEngineState {
    /** Not on this computer: the panel offers [ install ] (the update pill installs it). */
    data object NotInstalled : WebEngineState
    data object Starting : WebEngineState
    data class Ready(val app: CefApp) : WebEngineState
    /** It is installed but didn't start; [reason] is said in the panel, which shows the source. */
    data class Failed(val reason: String) : WebEngineState
}

object WebEngine {
    var state by mutableStateOf<WebEngineState>(WebEngineState.NotInstalled)
        private set

    /** The app sets these at start: where engines live (Soma's own folder) and what [ install ] does (opens the update sheet). */
    @Volatile var home: File? = null
    @Volatile var onInstall: (() -> Unit)? = null

    /** The pinned build the app installs: its jcefmaven release tag (build_meta.json says the same once installed). */
    const val VERSION = "152.0.6"

    /** The engine's folder for [VERSION], or null before the app said where engines live. */
    fun dir(): File? = home?.let { File(it, "jcef-$VERSION") }

    /** Whether [dir] holds a whole install: the files jcefmaven checks, and the lock written last. */
    fun installed(dir: File? = dir()): Boolean = dir != null && File(dir, "install.lock").isFile && File(dir, "build_meta.json").isFile

    /**
     * The natives [jar] (already checked against its pinned sha256 by the caller) unpacked into [dir]: into a folder beside it
     * first, then moved into place, the lock written last, so a cut-off install is never taken for a whole one.
     */
    fun install(jar: File, dir: File = dir() ?: error("the engine's folder isn't set")): String? = runCatching {
        val staging = File(dir.parentFile, ".${dir.name}.new").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(jar.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return "the engine file has no Chromium archive in it"
                if (!entry.isDirectory && entry.name.endsWith(".tar.gz")) break
            }
            TarGzExtractor.extractTarGZ(staging, zip)
        }
        if (!File(staging, "build_meta.json").isFile) return "the engine file unpacked without its build_meta.json"
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) return "couldn't move the engine into ${dir.path}"
        File(dir, "install.lock").createNewFile()
        null
    }.getOrElse { "${it.message ?: it.javaClass.simpleName} while unpacking the engine" }

    /** Left while Chromium starts with the GPU, removed once a page has drawn. Still there at the next start: the GPU failed. */
    private fun gpuUnconfirmed(dir: File) = File(dir.parentFile, "gpu-unconfirmed")

    /** The GPU failed here once: this engine starts in software from then on (delete the file to try the GPU again). */
    private fun gpuFailed(dir: File) = File(dir.parentFile, "gpu-failed")

    /** Starts Chromium once, off the UI thread, if it is installed; the panel follows [state]. */
    @Synchronized
    fun start() {
        if (state is WebEngineState.Ready || state is WebEngineState.Starting) return
        val dir = dir()
        if (dir == null || !installed(dir)) { state = WebEngineState.NotInstalled; return }
        if (gpuUnconfirmed(dir).exists()) runCatching { gpuUnconfirmed(dir).renameTo(gpuFailed(dir)) }
        val software = gpuFailed(dir).exists() || System.getenv("SOMA_WEB_SOFTWARE") == "1"
        state = WebEngineState.Starting
        thread(name = "soma-web-engine", isDaemon = true) {
            if (!software) runCatching { gpuUnconfirmed(dir).writeText("started with the GPU; removed once a page draws\n") }
            val started = runCatching {
                CefAppBuilder().apply {
                    setInstallDir(dir)
                    // Never fetched at run time: what isn't installed stays not installed.
                    setMirrors(emptyList())
                    cefSettings.windowless_rendering_enabled = false
                    cefSettings.root_cache_path = File(dir.parentFile, "profile").absolutePath
                    cefSettings.remote_debugging_port = 0
                    cefSettings.persist_session_cookies = false
                    cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_ERROR
                    cefSettings.log_file = File(dir.parentFile, "chromium.log").absolutePath
                    addJcefArgs(*WebEngineFlags.chromium(System.getProperty("os.name"), WebEngineFlags.scale(), software).toTypedArray())
                    setAppHandler(object : MavenCefAppHandlerAdapter() {})
                }.build()
            }
            state = started.fold({ WebEngineState.Ready(it) }, { WebEngineState.Failed("${it.message ?: it.javaClass.simpleName}${if (software) "" else " (it starts without the GPU next time)"}") })
        }
        // A start that never returns (as the first-run window once did) is said, not waited on forever.
        thread(name = "soma-web-engine-watch", isDaemon = true) {
            Thread.sleep(30_000)
            if (state is WebEngineState.Starting) state = WebEngineState.Failed("Chromium didn't start in 30 seconds" + if (software) "" else " (it starts without the GPU next time)")
        }
    }

    /** A page drew: the GPU path works on this computer. */
    fun drew() {
        dir()?.let { gpuUnconfirmed(it).delete() }
    }

    /** At quit: Chromium's own processes end with Soma. */
    fun dispose() {
        (state as? WebEngineState.Ready)?.app?.dispose()
    }
}

/** What a page may do in the artifact panel: the same rules as the Fold's WebView, as decisions a test can read. */
object ArtifactPolicy {
    enum class Go { Allow, Block, Answer }

    /** A navigation to [url]: generated HTML ([html]) loads its own address once and goes nowhere else; a web page stays on http(s). */
    fun navigation(html: Boolean, url: String, mainFrame: Boolean): Go = when {
        html && url.startsWith("soma-answer:") -> if (mainFrame) Go.Answer else Go.Block
        html -> if ((url == ARTIFACT_ORIGIN || url == "about:blank") && mainFrame) Go.Allow else Go.Block
        else -> if (url.startsWith("https://") || url.startsWith("http://")) Go.Allow else Go.Block
    }

    /** A request a page makes for [url]: generated HTML reaches only itself and inline data; a web page, the web. */
    fun request(html: Boolean, url: String): Boolean = when {
        html -> url == ARTIFACT_ORIGIN || url.startsWith("data:") || url.startsWith("blob:")
        else -> url.startsWith("https://") || url.startsWith("http://") || url.startsWith("data:") || url.startsWith("blob:")
    }
}
