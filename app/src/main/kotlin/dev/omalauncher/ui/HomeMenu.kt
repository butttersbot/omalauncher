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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.newAppCommand
import dev.omalauncher.widgets.newWidgetCommand

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

    /** Recolors right away, then applies the full theme: its first background, like omarchy-theme-set, and the guest's configs. */
    // The page the long press was on: what is drawn or stuck from here goes there.
    val page = state.currentPage
    val book = state.marks.book
    // "New sticker" turns the menu into the stickers to pick from, until it closes.
    var stickers by remember { mutableStateOf(false) }
    // Where "new sticky note" is in the window: the note comes out of it.
    val noteFrom = remember { arrayOfNulls<Offset>(1) }
    Menu(onDismiss = { state.homeMenuOpen = false }, from = { state.homeMenuFrom }) {
        if (book != null && stickers) {
            MenuTitle("stick on this page")
            StickerPicker { kind, from ->
                state.homeMenuOpen = false
                state.editing = null
                // Selected as it lands, so it can be dragged, resized, turned or taken off again at once.
                book.addSticker(page, kind)?.let {
                    state.arrivals.expect(it, from)
                    state.marks.selected = Chosen(page, it)
                }
            }
            return@Menu
        }
        if (book != null) {
            // Not in a session that isn't being saved, nor on a page whose ink couldn't be read: the book says which.
            MenuItem("draw on this page") {
                state.homeMenuOpen = false
                if (book.takes(page)) state.startDrawing()
            }
            // Lands on the page in the next free place of a cascade, with its text open to type.
            MenuItem("new sticky note", modifier = Modifier.onGloballyPositioned { noteFrom[0] = it.boundsInWindow().center }) {
                state.homeMenuOpen = false
                state.editing = null
                book.addNote(page)?.let {
                    state.arrivals.expect(it, noteFrom[0])
                    state.marks.editing = Chosen(page, it)
                }
            }
            MenuItem("new sticker") { if (book.takes(page)) stickers = true else state.homeMenuOpen = false }
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
        // The looks, where the Omarchy themes' strip was: a tap redraws home behind the menu at once.
        LookStrip(runtime.themes, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

/**
 * A menu over everything, in the middle of the window. With [from], it grows out of that point in the
 * window (the touch that asked for it) as its scrim fades in: its panel is laid out where it ends up and
 * only its layer is scaled. Without one it is simply there.
 */
@Composable
internal fun Menu(
    onDismiss: () -> Unit,
    from: (() -> Offset?)? = null,
    content: @Composable () -> Unit,
) {
    val shown = if (from != null) rememberAppearing() else null
    // Where the panel is in the window, for the point it grows out of. It is known only once the panel has been
    // placed, which is after its layer was first set up: until then the panel is not drawn at all.
    val at = remember { floatArrayOf(0f, 0f) }
    var placed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (shown != null) Modifier.fades { shown.value } else Modifier)
            .background(Pack.overlay)
            .clickable(onClick = onDismiss)
            // A menu with a field in it stays above the keyboard.
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .then(
                    if (shown != null && from != null) {
                        Modifier
                            .onGloballyPositioned {
                                val origin = it.positionInWindow()
                                at[0] = origin.x
                                at[1] = origin.y
                                placed = true
                            }.graphicsLayer { if (!placed && shown.value < 1f) alpha = 0f }
                            .appears({ shown.value }, MENU_APPEAR_SCALE) {
                                from()?.let { touch -> pivotAt(touch, Offset(at[0], at[1]), size) } ?: TransformOrigin.Center
                            }
                    } else {
                        Modifier
                    },
                ).panel(20.dp)
                .clickable(enabled = false) {}
                .padding(8.dp),
        ) { content() }
    }
}

/** How small the home menu starts as it grows out of the touch. */
private const val MENU_APPEAR_SCALE = 0.6f

@Composable
internal fun MenuItem(
    label: String,
    color: androidx.compose.ui.graphics.Color = Pack.foreground,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
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
