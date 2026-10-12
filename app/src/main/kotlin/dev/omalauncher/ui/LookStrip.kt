package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.PackOption
import dev.omalauncher.theme.pack.ThemePack

/**
 * The looks to pick from, in a row that scrolls sideways: each a small page in its own paper with one of
 * its scraps on it, in its own inks, and its name under it. A tap draws the launcher from that look at once.
 * The one in use has the heavier outline. This stands in the long-press menu where the Omarchy themes'
 * strip was; those still color Console, from the style screen.
 */
@Composable
internal fun LookStrip(
    store: ThemeStore,
    modifier: Modifier = Modifier,
) {
    val options by produceState(emptyList<PackOption>(), store) { value = store.packOptions() }
    if (options.isEmpty()) return
    val picked = ActivePack.pick.id
    Column(modifier) {
        SectionLabel("look", Modifier.padding(bottom = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (option in options) {
                // A look whose manifest can't be read isn't offered here; the style screen says why.
                if (option.load?.error != null) continue
                LookTile(option, option.id == picked) { store.pickPack(option.id) }
            }
        }
    }
}

@Composable
private fun LookTile(
    option: PackOption,
    current: Boolean,
    onPick: () -> Unit,
) {
    val pack: ThemePack = option.load?.pack ?: ConsolePack.pack
    // Console has no page of its own: its swatch is its background.
    val owns = option.id != ConsolePack.ID
    val paper = if (owns) pack.page.color.toColor() else pack.palette.background.toColor()
    val scrap = pack.scrap.surface
    val alt = pack.scrap.alt
    val ring = if (current) Pack.accent else Pack.border
    Column(
        Modifier
            .width(64.dp)
            .clickable(role = Role.RadioButton, onClick = onPick)
            .semantics {
                selected = current
                contentDescription = option.name
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(64.dp, 84.dp)) {
            val r = CornerRadius(6.dp.toPx())
            drawRoundRect(paper, cornerRadius = r)
            // One plain scrap and one emphasised, where a page would have its tiles.
            fun tile(
                at: Offset,
                size: Size,
                fill: Color,
                line: Color,
                width: Float,
            ) {
                val shadow = scrap.shadow
                if (shadow.visible) drawRect(shadow.color.toColor(), Offset(at.x + shadow.x.dp.toPx() / 2, at.y + shadow.y.dp.toPx() / 2), size)
                drawRect(fill, at, size)
                if (width > 0f) drawRect(line, at, size, style = Stroke(width.coerceAtLeast(1f)))
            }
            val w = size.width
            val h = size.height
            tile(Offset(w * 0.14f, h * 0.14f), Size(w * 0.6f, h * 0.22f), scrap.fill.toColor(), scrap.borderColor.toColor(), scrap.border.dp.toPx() / 2)
            tile(Offset(w * 0.24f, h * 0.46f), Size(w * 0.6f, h * 0.3f), alt.fill.toColor(), alt.borderColor.toColor(), alt.border.dp.toPx() / 2)
            val line = (if (current) 3.dp else 1.dp).toPx()
            drawRoundRect(ring, Offset(line / 2, line / 2), Size(w - line, h - line), r, style = Stroke(line))
        }
        Spacer(Modifier.size(4.dp))
        BasicText(
            option.name.lowercase(),
            style = Pack.body(Pack.size.small, if (current) Pack.accent else Pack.foreground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
