package dev.omalauncher.desktop

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.hostRefusals
import dev.omalauncher.widgets.render.OwlScreen
import dev.omalauncher.widgets.runtime.CommandGate
import dev.omalauncher.widgets.runtime.WidgetInstance
import dev.omalauncher.widgets.runtime.WidgetLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The .owl widgets on the dashboard window (desktop parity slice 5a, Boss 2026-10-28: "the widget screen can be a separate
 * window; I'll set up dashboards"), as the canvas service runs them for the car (Serve.kt): a library file runs only once
 * he has approved its text here (WidgetLibrary asks in Needs you), through the gate, on the window's own thread.
 */
class WindowWidgets(private val home: File, private val gate: CommandGate) {
    val library = WidgetLibrary(File(home, "widgets").apply { mkdirs() }, gate)
    // A widget's runtime is made for the thread that draws it: here, Swing's.
    private val scope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = javax.swing.SwingUtilities.invokeLater(block)
    })
    private val running = HashMap<String, Pair<String, WidgetInstance>>()

    /** The library's widgets, by file name. */
    fun names(): List<String> = library.names()

    /** The running widget for [name] once he has approved that file here; started the first time. Called on the window's thread. */
    fun widget(name: String): WidgetInstance? {
        val text = library.approvedText(name)
        running[name]?.takeIf { it.first == text }?.let { return it.second }
        // A file replaced (in quarantine again) or gone: the old one stops, so nothing of it runs on.
        running.remove(name)?.second?.stop()
        if (text == null) return null
        val program = runCatching { Compiler.compile(text) }.getOrNull() ?: return null
        if (hostRefusals(program, OwlScreen.host).isNotEmpty()) return null
        val inst = WidgetInstance(program, DesktopWidgetServices.gated(gate, name, workDir = File(home, "widget-run"), files = File(home, "widget-files")), onError = {})
        inst.start(scope) { true }
        running[name] = text to inst
        return inst
    }

    /** Why [name] isn't drawn: it waits for his OK, it can't be drawn here, or it isn't there. */
    fun whyNot(name: String): String {
        val text = library.approvedText(name) ?: return if (File(home, "widgets/$name").isFile) "$name waits for your OK in Needs you" else "$name isn't in the library"
        val program = runCatching { Compiler.compile(text) }.getOrNull() ?: return "$name doesn't compile"
        return hostRefusals(program, OwlScreen.host).firstOrNull()?.let { "$name can't draw here: $it" } ?: "$name isn't running"
    }
}
