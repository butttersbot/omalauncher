package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.newAppCommand
import dev.omalauncher.widgets.newWidgetCommand

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
            StickerPicker { choice, from ->
                state.homeMenuOpen = false
                state.editing = null
                // Selected as it lands, so it can be dragged, resized, turned or taken off again at once. A picture
                // lands a moment after the menu has gone, once the launcher has made its own copy of it.
                book.stick(page, choice, state.homeMenuAt) { stuck ->
                    if (stuck != null) {
                        state.arrivals.expect(stuck, from)
                        state.marks.selected = Chosen(page, stuck)
                    }
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
            // Lands where he pressed, or as near it as is clear of the tiles and of what is stuck there, with its text open to type.
            MenuItem("new sticky note", modifier = Modifier.onGloballyPositioned { noteFrom[0] = it.boundsInWindow().center }) {
                state.homeMenuOpen = false
                state.editing = null
                book.addNote(page, state.homeMenuAt)?.let {
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
