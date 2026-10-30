package dev.omalauncher.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A menu over everything, in the middle of the window. With [from], it grows out of that point in the
 * window (the touch that asked for it) as its scrim fades in: its panel is laid out where it ends up and
 * only its layer is scaled. Without one it is simply there.
 */
@Composable
fun Menu(
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
            // A click or touch on the scrim closes it, never a key: a clickable also answers Space and Enter, which a field
            // in the menu passes on, so typing a space in a tab's name closed the menu (Boss, 2026-10-29, Soma on Linux).
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .semantics { onClick { onDismiss(); true } }
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
                // A menu longer than the window (a computer's Venice models) scrolls inside its panel, rather than its
                // rows past the window's edge being out of reach.
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) { content() }
    }
}

/** How small the home menu starts as it grows out of the touch. */
private const val MENU_APPEAR_SCALE = 0.6f

@Composable
fun MenuItem(
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
fun MenuTitle(text: String) {
    BasicText(text, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = Pack.body(Pack.size.body, Pack.muted))
}
