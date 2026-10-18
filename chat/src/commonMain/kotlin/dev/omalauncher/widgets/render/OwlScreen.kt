package dev.omalauncher.widgets.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.OwlHost
import dev.omalauncher.widgets.runtime.WidgetTerminal

/*
 * What the screen a widget is drawn on can draw besides the shared nodes (Soma Canvas slice 7a, PRD §3): the renderer is
 * the same Compose everywhere, and the few nodes that need Android (a web view, an AGSL shader) or the phone's Linux (a
 * terminal) are this screen's to draw or not. `owl check --host` refuses a widget for a screen that can't draw it before
 * it is placed; one that arrives anyway says so in its place, and nothing is drawn in its stead.
 */
object OwlScreen {
    /** Which screen this is; `soma --serve` (slice 8a) sets [OwlHost.Service] as it starts headless. */
    @Volatile var host: OwlHost = platformHost

    /** Draws a Terminal node; the phone's launcher sets it, with its own Linux terminal. */
    @Volatile var terminal: (@Composable (WidgetTerminal, Modifier) -> Unit)? = null
}

/** The screen this build is: the Fold on Android, Soma desktop on a computer. */
internal expect val platformHost: OwlHost

/** Draws a Shader node here, or null when this screen can't. */
internal expect val shaderNode: (@Composable (Props, Modifier) -> Unit)?

/** Draws a Web node here, or null when this screen can't. */
internal expect val webNode: (@Composable (Props, Modifier) -> Unit)?

/** In the place of a node this screen can't draw: what it is, and that it isn't drawn here. Never a stand-in for it. */
@Composable
internal fun NotOnThisScreen(type: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        BasicText("$type doesn't draw on ${OwlScreen.host.id}", style = androidx.compose.ui.text.TextStyle(color = Palette.muted, fontSize = 11.sp))
    }
}
