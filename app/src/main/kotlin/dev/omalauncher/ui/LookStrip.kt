package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.prototypeUnit
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.PackOption
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.ThemePack
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The looks to pick from, in a row that scrolls sideways: each a small page in its own paper with one of
 * its scraps on it, in its own inks, and its name under it. A tap draws the launcher from that look at once.
 * The one in use has the heavier outline. This stands in the long-press menu where the Omarchy themes'
 * strip was; those still color Console, from the look screen.
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
                // A look whose manifest can't be read isn't offered here; the look screen says why.
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
    Column(
        Modifier
            .width(SWATCH_WIDTH.dp)
            .clickable(role = Role.RadioButton, onClick = onPick)
            .semantics {
                selected = current
                contentDescription = option.name
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LookSwatch(option, current, Modifier.size(SWATCH_WIDTH.dp, SWATCH_HEIGHT.dp))
        Spacer(Modifier.size(4.dp))
        BasicText(
            option.name.lowercase(),
            style = Pack.body(Pack.size.small, if (current) Pack.accent else Pack.foreground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The strip's swatch in dp, which every other size of it is drawn in proportion to. */
private const val SWATCH_WIDTH = 64f
private const val SWATCH_HEIGHT = 84f

/**
 * A look as a small page: its paper, with one plain scrap and one emphasised where a page would have its
 * tiles, in the look's own fills, outlines and shadows. The one in use has the heavier outline, in the accent.
 * Everything is in proportion to the swatch's width, so the strip's small one and the picker's larger one match.
 */
@Composable
private fun LookSwatch(
    option: PackOption,
    current: Boolean,
    modifier: Modifier,
) {
    val pack: ThemePack = option.load?.pack ?: ConsolePack.pack
    // Console has no page of its own: its swatch is its background.
    val owns = option.id != ConsolePack.ID
    val paper = if (owns) pack.page.color.toColor() else pack.palette.background.toColor()
    val scrap = pack.scrap.surface
    val alt = pack.scrap.alt
    val ring = if (current) Pack.accent else Pack.border
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // One dp of the strip's swatch, at this one's size.
        val unit = w / SWATCH_WIDTH
        val r = CornerRadius(6f * unit)
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
            if (shadow.visible) drawRect(shadow.color.toColor(), Offset(at.x + shadow.x * unit / 2, at.y + shadow.y * unit / 2), size)
            drawRect(fill, at, size)
            if (width > 0f) drawRect(line, at, size, style = Stroke(width.coerceAtLeast(1f)))
        }
        tile(Offset(w * 0.14f, h * 0.14f), Size(w * 0.6f, h * 0.22f), scrap.fill.toColor(), scrap.borderColor.toColor(), scrap.border * unit / 2)
        tile(Offset(w * 0.24f, h * 0.46f), Size(w * 0.6f, h * 0.3f), alt.fill.toColor(), alt.borderColor.toColor(), alt.border * unit / 2)
        val line = (if (current) 3f else 1f) * unit
        drawRoundRect(ring, Offset(line / 2, line / 2), Size(w - line, h - line), r, style = Stroke(line))
    }
}

/**
 * The looks as the look screen offers them: every one a page with its name handwritten under it, in rows
 * that wrap, sized by the prototype's unit so the cover display has three across and the open one five. A tap
 * draws the launcher from that look at once. The one in use has the heavier outline and says so; a look whose
 * manifest has something wrong says what under its own page, and nowhere else.
 */
@Composable
internal fun LookPicker(
    store: ThemeStore,
    modifier: Modifier = Modifier,
) {
    val options by produceState(emptyList<PackOption>(), store) { value = store.packOptions() }
    if (options.isEmpty()) return
    val picked = ActivePack.pick
    val u = LocalConfiguration.current.run { prototypeUnit(screenWidthDp.toFloat(), screenHeightDp.toFloat()) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val grid = lookGrid(options.size, maxWidth.value, u)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(grid.gap.dp),
            verticalArrangement = Arrangement.spacedBy(grid.gap.dp),
            maxItemsInEachRow = grid.columns,
        ) {
            for (option in options) {
                LookPage(option, option.id == picked.id, grid.tile, lookProblem(option, picked)) { store.pickPack(option.id) }
            }
        }
    }
}

@Composable
private fun LookPage(
    option: PackOption,
    current: Boolean,
    /** The page's width in dp. */
    width: Float,
    problem: String?,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .width(width.dp)
            .clickable(role = Role.RadioButton, onClick = onPick)
            .semantics {
                selected = current
                contentDescription = option.name
            },
    ) {
        LookSwatch(option, current, Modifier.size(width.dp, (width * SWATCH_HEIGHT / SWATCH_WIDTH).dp))
        BasicText(
            option.name.lowercase(),
            Modifier.padding(top = 6.dp),
            style = Pack.hand(Pack.size.item, if (current) Pack.accent else Pack.foreground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            problem != null -> BasicText(problem, style = Pack.body(Pack.size.small, Pack.red))
            current -> BasicText(Pack.caption("in use"), style = Pack.mono(Pack.size.small, Pack.accent))
        }
    }
}

/** How the picker's pages are laid out: how many across, and each page's width and the gap between them in dp. */
internal data class LookGrid(
    val columns: Int,
    val tile: Float,
    val gap: Float,
)

/** A page in the picker is never narrower than [LOOK_MIN] of the prototype's units or wider than [LOOK_MAX]; [LOOK_GAP] lies between two. */
private const val LOOK_MIN = 72f
private const val LOOK_MAX = 88f
private const val LOOK_GAP = 12f

/**
 * The grid for [count] looks in [width] dp, at the prototype's [unit]. As few rows as the narrowest pages
 * allow, then the looks spread evenly over those rows, so nine are three rows of three on the cover display
 * and five and four on the open one, never a full row with one look left over under it. The pages then grow
 * to fill the row, up to their widest.
 */
internal fun lookGrid(
    count: Int,
    width: Float,
    unit: Float,
): LookGrid {
    val gap = LOOK_GAP * unit
    val looks = count.coerceAtLeast(1)
    val fits = ((width + gap) / (LOOK_MIN * unit + gap)).toInt().coerceIn(1, looks)
    val rows = ceil(looks / fits.toFloat()).toInt()
    val columns = ceil(looks / rows.toFloat()).toInt()
    // Whole dp, so a row's pages and gaps never add up to a hair more than the row.
    val tile = floor(minOf((width - gap * (columns - 1)) / columns, LOOK_MAX * unit))
    return LookGrid(columns, tile, gap)
}

/**
 * What the picker says under [option]'s page when its manifest isn't whole; null when it is. The look that
 * is [picked] but couldn't be used at all also says what is drawn in its place.
 */
internal fun lookProblem(
    option: PackOption,
    picked: PackPick,
): String? = picked.error?.takeIf { option.id == picked.id }?.let { "$it Console is shown instead." } ?: option.problem
