package dev.omalauncher.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
actual fun ModalWindow(
    onDismiss: () -> Unit,
    lightBars: Boolean,
    content: @Composable () -> Unit,
) {
    // The kit draws its own scrim, coming in with what it shows; the platform's is left off.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, scrimColor = Color.Transparent), content = content)
}
