package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as Outline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.omalauncher.widgets.annotations.Sticker
import dev.omalauncher.widgets.annotations.StickerKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The built-in stickers, each drawn here from a few lines and curves: no image is bundled for them and
 * nothing is fetched. They are in the pack's accent, with a hard shadow in its surface color so they stand
 * off any page.
 */

/** A point [radius] from the middle of a box [side] across, at [degrees] clockwise from straight up. */
private fun around(
    side: Float,
    radius: Float,
    degrees: Float,
): Offset {
    val angle = (degrees - 90f) * PI.toFloat() / 180f
    return Offset(side / 2f + side * radius * cos(angle), side / 2f + side * radius * sin(angle))
}

private fun polygon(points: List<Offset>) =
    Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }

/** One sticker in a box [side] across at the scope's origin, all of it in the one color it is given. */
private fun DrawScope.shape(
    kind: StickerKind,
    side: Float,
    color: Color,
) {
    val line = Outline(side * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        StickerKind.Star -> drawPath(polygon((0 until 10).map { around(side, if (it % 2 == 0) 0.48f else 0.2f, it * 36f) }), color)
        StickerKind.Heart ->
            drawPath(
                Path().apply {
                    moveTo(side * 0.5f, side * 0.92f)
                    cubicTo(side * -0.08f, side * 0.52f, side * 0.1f, side * 0.02f, side * 0.5f, side * 0.3f)
                    cubicTo(side * 0.9f, side * 0.02f, side * 1.08f, side * 0.52f, side * 0.5f, side * 0.92f)
                    close()
                },
                color,
            )
        StickerKind.Flower -> {
            for (petal in 0 until 5) drawCircle(color, side * 0.19f, around(side, 0.27f, petal * 72f))
            drawCircle(color, side * 0.16f, Offset(side / 2f, side / 2f))
        }
        StickerKind.Check ->
            drawPath(
                Path().apply {
                    moveTo(side * 0.14f, side * 0.55f)
                    lineTo(side * 0.4f, side * 0.8f)
                    lineTo(side * 0.86f, side * 0.2f)
                },
                color,
                style = Outline(side * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        StickerKind.Bolt ->
            drawPath(
                polygon(listOf(0.62f to 0.02f, 0.18f to 0.56f, 0.46f to 0.56f, 0.36f to 0.98f, 0.84f to 0.4f, 0.54f to 0.4f).map { Offset(side * it.first, side * it.second) }),
                color,
            )
        StickerKind.Smile -> {
            drawCircle(color, side * 0.42f, Offset(side / 2f, side / 2f), style = line)
            drawCircle(color, side * 0.065f, Offset(side * 0.36f, side * 0.4f))
            drawCircle(color, side * 0.065f, Offset(side * 0.64f, side * 0.4f))
            drawArc(color, 25f, 130f, false, topLeft = Offset(side * 0.28f, side * 0.28f), size = Size(side * 0.44f, side * 0.44f), style = line)
        }
    }
}

/** [kind] filling the scope: its shadow first, a little down and to the right, then itself. */
private fun DrawScope.drawSticker(kind: StickerKind) {
    val side = minOf(size.width, size.height) * 0.92f
    val shift = side * 0.045f
    translate(shift, shift) { shape(kind, side, Pack.surface) }
    shape(kind, side, Pack.accent)
}

/** A sticker by itself, for where one is chosen. */
@Composable
internal fun StickerGlyph(
    kind: StickerKind,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) { drawSticker(kind) }
}

/**
 * One sticker on its page at [rect], turned as it was left. Only what is drawn turns: it is touched, carried
 * and resized by its upright box, like a tilted scrap. A tap selects it.
 */
@Composable
internal fun StickerScrap(
    sticker: Sticker,
    rect: Rect,
    /** How it eases in or out, if it does: its own layer, after its place and size. */
    motion: Modifier = Modifier,
    /** Null for one that is on its way off the page: it is only drawn. */
    onTap: (() -> Unit)?,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .zIndex(1f)
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .then(motion)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = sticker.turn.toFloat() }) { drawSticker(sticker.kind) }
    }
}

/** The stickers there are, in a row to pick one from. [onPick] is told where in the window the one picked was. */
@Composable
internal fun StickerPicker(onPick: (StickerKind, Offset?) -> Unit) {
    val places = remember { HashMap<StickerKind, Offset>() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (kind in StickerKind.entries) {
            Box(
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { places[kind] = it.boundsInWindow().center }
                    .clickable { onPick(kind, places[kind]) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                StickerGlyph(kind, Modifier.size(36.dp))
            }
        }
    }
}
