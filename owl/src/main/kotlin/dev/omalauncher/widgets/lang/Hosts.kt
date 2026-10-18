package dev.omalauncher.widgets.lang

/*
 * Which screens can draw which nodes (Soma Canvas slice 7a, PRD §3: "one renderer, honest about hosts"). The renderer is
 * shared Compose on every screen, but a few nodes need what only Android has: a web view, the phone's Linux terminal, an
 * AGSL shader. A widget that uses one is refused for a screen that can't draw it, before it is placed there, and the
 * renderer never draws a stand-in for one.
 */

/** A kind of screen a widget runs on: the Fold, Soma desktop, or the Mac Pro's headless service that draws the car's canvas. */
enum class OwlHost(val id: String, val cannot: Set<String>) {
    Fold("fold", emptySet()),
    Desktop("desktop", setOf("Web", "Terminal", "Shader")),
    Service("service", setOf("Web", "Terminal", "Shader")),
    ;

    /** Whether this host draws nodes of [type]. */
    fun draws(type: String) = type !in cannot

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/** Why [program] can't run on [host], one line per kind of node it can't draw there; empty when it can. */
fun hostRefusals(program: Program, host: OwlHost): List<String> =
    program.nodeTypes.filter { !host.draws(it) }.sorted().map { "$it nodes don't draw on ${host.id}: ${WHY[it] ?: "this screen has nothing to draw them with"}" }

private val WHY = mapOf(
    "Web" to "there is no web view there",
    "Terminal" to "there is no Linux terminal there",
    "Shader" to "AGSL shaders draw only on Android",
)
