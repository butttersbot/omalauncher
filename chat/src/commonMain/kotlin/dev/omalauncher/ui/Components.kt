package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * A full-screen framed panel with a title, optional [actions] and a close button: themes, agents, the store.
 * With a [contentWidth], a wide screen centers the content at that width; the frame and title still span the panel.
 */
@Composable
fun OverlayPanel(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    contentWidth: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Pack.overlay)
            .clickable(onClick = onDismiss)
            .safeDrawingPadding()
            .padding(PageGap),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .panel(20.dp)
                // Taps inside the panel don't reach the scrim behind it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(PanelPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(Pack.heading(title), style = Pack.display(Pack.size.title, Pack.accent))
                Spacer(Modifier.weight(1f))
                actions()
                Symbol(Glyph.Close, Pack.red, Modifier.clickable(onClick = onDismiss).padding(8.dp), Pack.size.title)
            }
            Spacer(Modifier.height(8.dp))
            if (contentWidth == Dp.Unspecified) {
                content()
            } else {
                Column(Modifier.weight(1f).widthIn(max = contentWidth).fillMaxWidth().align(Alignment.CenterHorizontally), content = content)
            }
        }
    }
}

/** [OverlayPanel]'s padding inside its frame; rows that scroll sideways [bleed] through it to the frame. */
val PanelPadding = 12.dp

/** Widens the element by [gutter] on both sides, drawing over its parent's padding. */
fun Modifier.bleed(gutter: Dp) =
    layout { measurable, constraints ->
        val extra = gutter.roundToPx() * 2
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
    }

/** A muted heading over a group of rows: settings sections, the app drawer's letters. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    /** On a panel by default; a heading straight on the page takes the page's own muted ink. */
    color: Color = Pack.muted,
) = BasicText(Pack.caption(text), modifier.padding(top = 12.dp, bottom = 4.dp), style = Pack.mono(color = color))

/** A [Glyph] from the launcher's Nerd Font subset, sized like text. */
@Composable
fun Symbol(
    glyph: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = Pack.size.body,
) = BasicText(glyph, modifier, style = Pack.symbol(fontSize, color))

/** A terminal-style `[ label ]` button. */
@Composable
fun BracketAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
        BasicText("[ $label ]", style = Pack.body(color = color))
    }
}

/** The items of a popup menu; picking one closes the menu first. */
@Composable
fun MenuItems(
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    minWidth: Dp,
    itemPadding: Dp,
    fontSize: TextUnit,
    /** Above the items, inside the frame: an Android app's shortcuts. */
    top: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = minWidth)
            .framed(Pack.hair, Pack.border, Pack.surface)
            .padding(vertical = 6.dp),
    ) {
        top()
        for ((label, action) in items) {
            BasicText(
                label,
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        action()
                    }.padding(horizontal = 18.dp, vertical = itemPadding),
                style = Pack.body(fontSize),
            )
        }
    }
}
