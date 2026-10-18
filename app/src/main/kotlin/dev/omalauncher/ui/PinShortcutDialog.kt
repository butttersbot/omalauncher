package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks before a shortcut an app made goes on the home screen: its icon and name, and the app that
 * asked, since that app is what opens it, whatever the shortcut calls itself. Framed as home's menus are.
 */
@Composable
internal fun PinShortcutDialog(
    label: String,
    /** The app that asked; null if the drawer doesn't list it (yet). */
    from: String?,
    /** Blocks; called off the main thread. */
    loadIcon: () -> ImageBitmap?,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val icon by produceState<ImageBitmap?>(null) { value = withContext(Dispatchers.IO) { loadIcon() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .panel(20.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(Pack.heading("add to home screen?"), style = Pack.display(Pack.size.title, Pack.accent))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                icon?.let { AppIcon(it, label, Modifier.size(52.dp), mono = false) }
                    ?: Box(Modifier.iconBadge(52.dp), contentAlignment = Alignment.Center) {
                        BasicText(Pack.placeholderText(label), style = Pack.placeholder(Pack.size.title))
                    }
                Column {
                    BasicText(label, style = Pack.body(Pack.size.large), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    from?.let { BasicText("from $it", style = Pack.body(color = Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BracketAction("cancel", Pack.muted, onDismiss)
                BracketAction("add", Pack.accent, onAdd)
            }
        }
    }
}
