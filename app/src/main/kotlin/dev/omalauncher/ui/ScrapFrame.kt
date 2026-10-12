package dev.omalauncher.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.pack.Dressing
import dev.omalauncher.theme.pack.PackShadow
import dev.omalauncher.theme.pack.PackSurface
import dev.omalauncher.theme.pack.PackTape
import dev.omalauncher.theme.pack.PackTitleBar
import dev.omalauncher.theme.pack.scrapTilt

/**
 * ScrapFrame: a tile's chrome from the active pack. Fill, outline, corner shape, up to two shadows,
 * tape, a title bar, the focus color and the tilt all come from the pack's `[scrap]`; every tile on home
 * is dressed by this one modifier.
 *
 * Tilt is only drawn. The scrap is turned on the canvas while it draws; nothing here turns a layer or
 * changes a layout, so it is measured, placed, touched, dragged and resized on its cell's upright
 * rectangle, and the handles and guides around it stay square to the grid (PRD 6.5).
 *
 * [key] picks the scrap's fixed angle, and is the item's key; without one it stands straight. [dressed]
 * adds the pack's tape and title bar, for widget tiles: an app's icon has no room under a title bar.
 */
fun Modifier.scrap(
    focused: Boolean = false,
    key: String? = null,
    dressed: Boolean = false,
): Modifier {
    val scrap = Pack.look.pack.scrap
    val tilt = if (key == null) 0f else scrapTilt(key, scrap.tiltMin, scrap.tiltMax)
    // About half the dressed scraps are taped, always the same ones.
    val tape = scrap.tape.takeIf { dressed && it.style != Dressing.None && key != null && key.hashCode() and 1 == 0 }
    val bar = scrap.titleBar.takeIf { dressed && it.style != Dressing.None && it.height > 0f }
    return surface(scrap.surface, focused, tilt = tilt, tape = tape, bar = bar)
}

/**
 * An outline alone, for a bare icon that is focused or being edited. It is drawn straight on the page, so
 * it is in the page's focus color, which a pack picks to show there; as wide as a focused scrap's.
 */
fun Modifier.scrapFocus(): Modifier {
    val pack = Pack.look.pack
    return framed(focusWidth(pack.scrap.surface).dp, pack.page.focusColor.toColor())
}

/** How wide [surface]'s outline is while focused: the pack's focus width, or the surface's own outline where that is wider. */
internal fun focusWidth(surface: PackSurface) = maxOf(surface.border, surface.focusWidth)

/** The dock's bar, the recent apps strip and the side rail: the pack's `[dock]` surface, outlined in its focus color while [focused]. */
fun Modifier.dockBar(focused: Boolean = false): Modifier = surface(Pack.look.pack.dock.surface, focused)

/** The dock bar's fill without its outline, which would run over icons as tall as the row. */
fun Modifier.dockBarFill(): Modifier = surface(Pack.look.pack.dock.surface, focused = false, outlined = false)

/**
 * One dock icon's own button, in a pack whose dock is a row of buttons; nothing otherwise. Buttons lean
 * by the pack's dock tilt, the [index]th the other way from its neighbours.
 */
fun Modifier.dockButton(index: Int): Modifier {
    val dock = Pack.look.pack.dock
    if (!Pack.dockButtons) return this
    val tilt = if (index % 2 == 0) -dock.tilt else dock.tilt
    return surface(dock.surface, focused = false, tilt = tilt, radius = Pack.look.radius)
}

/**
 * [surface] around the content. With nothing but a fill and an outline, which is all Console has, this
 * is exactly the frame the launcher drew before packs: clipped, filled, then outlined with one shape.
 */
internal fun Modifier.surface(
    surface: PackSurface,
    focused: Boolean,
    tilt: Float = 0f,
    tape: PackTape? = null,
    bar: PackTitleBar? = null,
    outlined: Boolean = true,
    radius: Dp = Pack.look.radius,
): Modifier {
    val shape = Pack.shape(radius)
    val fill = surface.fill.toColor()
    val line =
        when {
            !outlined -> Color.Transparent
            focused -> surface.focusColor.toColor()
            else -> surface.borderColor.toColor()
        }
    // Focus shows even where the pack's scraps have no outline, and at the pack's own weight where it gives one.
    val width = if (focused && outlined) focusWidth(surface) else surface.border
    val plain = !surface.shadow.visible && !surface.shadow2.visible && tilt == 0f && tape == null && bar == null
    if (plain) {
        val filled = clip(shape).background(fill, shape)
        // A border of no width would still draw a hairline.
        return if (width > 0f) filled.border(width.dp, line, shape) else filled
    }
    // The second shadow lies under the first.
    val shadows = listOf(surface.shadow2, surface.shadow).filter { it.visible }
    val drawn =
        drawWithCache {
            // Built when the scrap's size changes, not on every frame.
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val cast = shadows.filter { !it.inset }.map { it to shadowPaint(it, inset = false) }
            val sunk = shadows.filter { it.inset }.map { it to shadowPaint(it, inset = true) }
            onDrawWithContent {
                rotate(tilt) {
                    for ((shadow, paint) in cast) shadow(path, shadow, paint)
                    drawPath(path, fill)
                    clipPath(path) {
                        this@onDrawWithContent.drawContent()
                        for ((shadow, paint) in sunk) shadow(path, shadow, paint)
                        if (bar != null) titleBar(bar, surface.borderColor.toColor())
                        // Half of a stroke twice as wide falls outside the clip, which leaves the outline inside the edge.
                        if (width > 0f) drawPath(path, line, style = Stroke(width.dp.toPx() * 2f))
                    }
                    if (tape != null) tape(tape)
                }
            }
        }
    // Nothing here is laid out: the title bar is drawn over the top of the cell, and what is inside makes room with scrapContent().
    return drawn
}

/**
 * Room for a dressed scrap's title bar above its content, in a pack that has one; nothing otherwise.
 * It goes after the tile's pointer input, on the content alone: the whole cell stays the tile's to tap,
 * bar included, whatever the pack.
 */
fun Modifier.scrapContent(): Modifier {
    val bar = Pack.look.pack.scrap.titleBar
    return if (bar.style != Dressing.None && bar.height > 0f) padding(top = bar.height.dp) else this
}

/**
 * What [shadow] is painted with. A cast shadow fills the scrap's outline again; one that falls inward,
 * like a recessed display's, is a blurred line along the outline, drawn inside the scrap's clip.
 */
private fun Density.shadowPaint(
    shadow: PackShadow,
    inset: Boolean,
) = Paint().apply {
    color = shadow.color.toColor()
    val blur = shadow.blur.dp.toPx()
    if (blur > 0f) asFrameworkPaint().maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    if (inset) {
        style = PaintingStyle.Stroke
        strokeWidth = blur + 2f * maxOf(kotlin.math.abs(shadow.x.dp.toPx()), kotlin.math.abs(shadow.y.dp.toPx()))
    }
}

/** [path] again with the shadow's paint, moved by its offset. */
private fun DrawScope.shadow(
    path: Path,
    shadow: PackShadow,
    paint: Paint,
) {
    translate(shadow.x.dp.toPx(), shadow.y.dp.toPx()) { drawIntoCanvas { it.drawPath(path, paint) } }
}

/** The bar across the scrap's top: plain, or ruled like an old window's, with the pack's mark at its end. */
private fun DrawScope.titleBar(
    bar: PackTitleBar,
    edge: Color,
) {
    val height = bar.height.dp.toPx()
    val fill = bar.fill.toColor()
    val ink = bar.ink.toColor()
    if (bar.style == Dressing.Stripes) {
        drawRect(ink, size = Size(size.width, height))
        val rule = 1.dp.toPx()
        var y = 0f
        while (y < height) {
            drawRect(fill, Offset(0f, y), Size(size.width, rule))
            y += rule * 3f
        }
    } else {
        drawRect(fill, size = Size(size.width, height))
    }
    if (bar.glyph.isNotEmpty()) {
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = ink.toArgb()
                textSize = height * 0.7f
                textAlign = android.graphics.Paint.Align.RIGHT
            }
        drawIntoCanvas { it.nativeCanvas.drawText(bar.glyph, size.width - height * 0.35f, height * 0.75f, paint) }
    }
    if (bar.border > 0f) drawRect(edge, Offset(0f, height - bar.border.dp.toPx()), Size(size.width, bar.border.dp.toPx()))
}

/** The strip of tape over the middle of the scrap's top edge, half on it and half on the page. */
private fun DrawScope.tape(tape: PackTape) {
    val w = tape.width.dp.toPx()
    val h = tape.height.dp.toPx()
    val left = (size.width - w) / 2f
    val color = tape.color.toColor().let { it.copy(alpha = it.alpha * tape.opacity) }
    rotate(tape.angle, pivot = Offset(size.width / 2f, 0f)) {
        drawRect(color, Offset(left, -h / 2f), Size(w, h))
        if (tape.style == Dressing.Stripes) {
            val second = tape.color2.toColor().let { it.copy(alpha = it.alpha * tape.opacity) }
            val band = 6.dp.toPx()
            clipRect(left, -h / 2f, left + w, h / 2f) {
                var x = left + band
                while (x < left + w) {
                    drawRect(second, Offset(x, -h / 2f), Size(band, h))
                    x += band * 2f
                }
            }
        }
    }
}
