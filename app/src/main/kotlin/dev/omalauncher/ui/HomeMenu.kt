package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.newAppCommand
import dev.omalauncher.widgets.newWidgetCommand
import kotlinx.coroutines.launch

@Composable
internal fun EditBar(content: @Composable () -> Unit) {
    Row(
        Modifier
            .panel()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
internal fun EditAction(
    label: String,
    color: androidx.compose.ui.graphics.Color = Pack.foreground,
    onClick: () -> Unit,
) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicText(label, style = Pack.body(Pack.size.body, color))
    }
}

/**
 * The long-press menu for empty home space. Drawn by the launcher over the whole
 * screen (not inside the home page's system-bar padding) so its scrim covers the
 * status and navigation bars too.
 */
@Composable
fun HomeMenu(
    runtime: Runtime,
    state: LauncherState,
) {
    if (!state.homeMenuOpen) return
    val engine = runtime.widgets
    val agent = runtime.agents.default
    val agentName = agent?.name?.lowercase() ?: "an agent"
    // These need Linux; without it they open its install sheet.
    val linux = runtime.bootstrap.isInstalled
    val scope = rememberCoroutineScope()

    /** Recolors right away, then applies the full theme: its first background, like omarchy-theme-set, and the guest's configs. */
    fun pickTheme(theme: dev.omalauncher.theme.BundledTheme) {
        runtime.themes.pick(theme)
        // Until all themes are downloaded, one that isn't there yet is fetched on its own.
        scope.launch { if (runtime.themes.complete) runtime.themes.applyPicked() else runtime.themes.fetchPicked() }
    }
    // The page the long press was on: what is drawn or stuck from here goes there.
    val page = state.currentPage
    val book = state.marks.book
    Menu(onDismiss = { state.homeMenuOpen = false }) {
        if (book != null) {
            // Not in a session that isn't being saved, nor on a page whose ink couldn't be read: the book says which.
            MenuItem("draw on this page") {
                state.homeMenuOpen = false
                if (book.takes(page)) state.startDrawing()
            }
        }
        MenuItem("new widget with $agentName") {
            state.homeMenuOpen = false
            when {
                !linux -> state.linuxOpen = true
                agent == null -> state.agentsOpen = true
                else -> runtime.terminal(newWidgetCommand(agent.bin), "New widget", agent)
            }
        }
        MenuItem("new app with $agentName") {
            state.homeMenuOpen = false
            when {
                !linux -> state.linuxOpen = true
                agent == null -> state.agentsOpen = true
                else -> runtime.terminal(newAppCommand(agent.bin), "New app", agent)
            }
        }
        MenuItem("widget store") {
            state.homeMenuOpen = false
            state.storeOpen = true
        }
        MenuItem("AUR app store") {
            state.homeMenuOpen = false
            if (linux) state.packagesOpen = true else state.linuxOpen = true
        }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { ThemeStrip(
                runtime.themes,
                gutter = 20.dp,
                onPick = ::pickTheme,
                onBrowse = {
                    state.homeMenuOpen = false
                    state.themeBrowserOpen = true
                },
            ) }
    }
}

@Composable
internal fun Menu(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Pack.overlay)
            .clickable(onClick = onDismiss)
            // A menu with a field in it stays above the keyboard.
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .panel(20.dp)
                .clickable(enabled = false) {}
                .padding(8.dp),
        ) { content() }
    }
}

@Composable
internal fun MenuItem(
    label: String,
    color: androidx.compose.ui.graphics.Color = Pack.foreground,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { BasicText(label, style = Pack.body(Pack.size.item, color)) }
}

/** What a menu is about, over its items. */
@Composable
internal fun MenuTitle(text: String) {
    BasicText(text, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = Pack.body(Pack.size.body, Pack.muted))
}
