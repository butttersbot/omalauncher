package dev.omalauncher.ui

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.toOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A folder on the home grid: its first four apps in a 2x2 badge. */
@Composable
fun FolderTile(
    folder: Folder,
    /** The first four apps' icons, as [ShortcutTile] gets them. */
    icons: List<ImageBitmap?>,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val bare = Pack.bareIcons
    BoxWithConstraints(
        modifier
            .tileFrame(focused, folder.key)
            .pointerInput(folder.key) { detectTapGestures(onTap = { onTap() }) }
            .padding(4.dp),
    ) {
        val badge = min(maxWidth, maxHeight) * if (bare) 0.95f else 0.85f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.iconBadge(badge, radius = badge / 3),
                contentAlignment = Alignment.Center,
            ) {
                val mini = badge * 0.36f
                Column(verticalArrangement = Arrangement.spacedBy(badge * 0.06f)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(badge * 0.06f)) {
                            for (col in 0 until 2) {
                                val i = row * 2 + col
                                Box(Modifier.size(mini), contentAlignment = Alignment.Center) {
                                    folder.apps.getOrNull(i)?.let { AppGlyph(it.label, icons.getOrNull(i), mini) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A small app icon: its picture (a silhouette in monochrome mode) or its initials. */
@Composable
private fun AppGlyph(
    label: String,
    icon: ImageBitmap?,
    size: Dp,
) {
    if (icon != null) {
        // Inside the folder's badge, which is its frame: the pack's treatment, without the shadow of an icon that stands alone.
        AppIcon(icon, label, Modifier.size(size), Pack.monoIcons, framed = true)
    } else {
        BasicText(Pack.placeholderText(label), style = Pack.placeholder((size.value * 0.45f).sp))
    }
}

/**
 * Where an open folder draws, in the home grid's pixels: a panel in the middle of the
 * grid with the apps at home's cell size, smaller only
 * when that doesn't fit. The grid's gestures and the panel's drawing share it.
 */
internal class FolderGeometry(
    width: Int,
    height: Int,
    cellW: Float,
    cellH: Float,
    gap: Int,
    count: Int,
    density: Density,
) {
    val cols = ceil(sqrt(count.toFloat())).toInt().coerceIn(2, 4)
    private val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
    private val pad = with(density) { 16.dp.roundToPx() }
    private val margin = with(density) { 16.dp.roundToPx() }
    private val scale =
        minOf(
            1f,
            (width - 2 * margin - 2 * pad) / (cols * cellW),
            (height - 2 * margin - 2 * pad) / (rows * cellH),
        )
    private val w = cellW * scale
    private val h = cellH * scale
    private val gap = gap

    val panel: IntRect =
        run {
            val pw = (cols * w).roundToInt() + 2 * pad
            val ph = (rows * h).roundToInt() + 2 * pad
            val left = (width - pw) / 2
            val top = (height - ph) / 2
            IntRect(left, top, left + pw, top + ph)
        }

    /** The [i]th app's tile, gapped like home's. */
    fun app(i: Int): IntRect {
        val left = panel.left + pad + (i % cols) * w
        val top = panel.top + pad + (i / cols) * h
        return IntRect((left + gap / 2f).roundToInt(), (top + gap / 2f).roundToInt(), (left + w - gap / 2f).roundToInt(), (top + h - gap / 2f).roundToInt())
    }
}

/**
 * An open folder over the home grid: a scrim, the panel and its apps drawn by [render].
 * Taps, long-presses and drags are [HomeGrid]'s, like every touch on home, and go by [geometry] as it is
 * at rest. It opens out of its [tile] as [shown] goes from 0 to 1: one layer's scale and opacity, with the
 * panel and the apps laid out where they end up from the start.
 */
@Composable
internal fun FolderPanel(
    folder: Folder,
    geometry: FolderGeometry,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
    /** The app a finger is dragging, if it's one of these: its place stays empty while the others sort around it. */
    carried: String? = null,
    shown: () -> Float = { 1f },
    /** The folder's tile on the grid, in the space the panel is in; without one it opens where it stands. */
    tile: IntRect? = null,
) {
    val density = LocalDensity.current
    FolderFrame(geometry.panel, shown, tile) {
        folder.apps.forEachIndexed { i, app ->
            if (app.key == carried) return@forEachIndexed
            val r = geometry.app(i)
            key(app.key) {
                // Apps making way for a dragged one slide to their new places.
                val at = animateIntOffsetAsState(IntOffset(r.left, r.top), Motion.spatial(IntOffset.VisibilityThreshold))
                render(
                    app,
                    Modifier
                        .offset { at.value }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() }),
                    false,
                )
            }
        }
    }
}

/**
 * What is left of a folder as it closes: where its panel was and where its tile is, and whether it has
 * [gone]. The panel alone goes back into the tile; the apps went as it closed, so nothing left takes a touch.
 */
@Stable
internal class FolderLeft {
    var panel: IntRect? = null
    var tile: IntRect? = null
    var gone by mutableStateOf(true)
}

/** A folder that closed, going back into its [tile] as [shown] goes from where it was to 0: its scrim and its empty panel, only drawn. */
@Composable
internal fun FolderClosing(
    panel: IntRect,
    shown: () -> Float,
    tile: IntRect?,
) = FolderFrame(panel, shown, tile) {}

/** How small a folder's panel starts with no tile to open out of. */
private const val FOLDER_APPEAR_SCALE = 0.8f

/** The least a folder's panel is scaled to as it opens out of its tile: a tile is never so small that the panel starts as a dot. */
private const val FOLDER_LEAST_SCALE = 0.2f

/** How small the panel starts for it to be as wide as the [tile] it opens out of. */
internal fun folderScale(
    panelWidth: Int,
    tileWidth: Int?,
): Float = if (tileWidth == null || panelWidth <= 0) FOLDER_APPEAR_SCALE else (tileWidth.toFloat() / panelWidth).coerceIn(FOLDER_LEAST_SCALE, 1f)

/** The scrim, the panel at [panel] and [content] over it, scaled about [tile] and faded by [shown]. Nothing here takes a touch of its own. */
@Composable
private fun FolderFrame(
    panel: IntRect,
    shown: () -> Float,
    tile: IntRect?,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().zIndex(4f).fades(shown).background(Pack.overlay.copy(alpha = 0.6f))) {
        Box(
            Modifier.fillMaxSize().appears(shown, folderScale(panel.width, tile?.width), fade = false) {
                pivotAt((tile ?: panel).center.toOffset(), Offset.Zero, size)
            },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(panel.left, panel.top) }
                    .size(with(density) { panel.width.toDp() }, with(density) { panel.height.toDp() })
                    .panel(20.dp),
            )
            content()
        }
    }
}
