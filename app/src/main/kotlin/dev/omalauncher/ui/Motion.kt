package dev.omalauncher.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home's motion, in one place: what grows out of the thing that was touched and goes back into it moves
 * on [spatial], what only appears or goes fades over [FADE_MS] along [easing]. The transitions of home, its
 * menu, folders, the overview, notes, stickers, the dock and the ring take their specs from here and
 * nowhere else, so they feel alike and are tuned here.
 */
object Motion {
    /** How stiff the spring is: what it moves is all but there in a fifth of a second. */
    const val STIFFNESS = 1000f

    /** Critically damped: nothing overshoots, so a size or a clip never passes where it is going. */
    const val DAMPING = 1f

    /** How long a fade takes, in milliseconds. */
    const val FADE_MS = 150

    /** Out fast and in gently, as things that come to rest do. */
    val easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For what moves, grows or shrinks. A fling's speed carries into it, and a new target turns it around mid-way. */
    fun <T> spatial(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(DAMPING, STIFFNESS, visibilityThreshold)

    /** [spatial] for a fraction from 0 to 1, at rest within a thousandth of where it is going. */
    val spatial: FiniteAnimationSpec<Float> = spatial(0.001f)

    /** For what appears or goes without moving. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(FADE_MS, easing = easing)

    /** The system's "remove animations" is on: every transition here is an immediate change instead. */
    val reduced: Boolean get() = reducedMotion(if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f)
}

/**
 * Whether motion is left out, from the system's animator duration scale: at zero ("remove animations") it
 * is, and nothing here moves or fades. Any other scale is Compose's to apply, as it does to every animation.
 */
internal fun reducedMotion(durationScale: Float): Boolean = durationScale <= 0f

/** Takes this to [target] along [spec], or at once with the system's animations off. A newer call takes over from this one. */
internal suspend fun <T, V : AnimationVector> Animatable<T, V>.moveTo(
    target: T,
    spec: AnimationSpec<T>,
    reduced: Boolean = Motion.reduced,
) {
    if (reduced) snapTo(target) else animateTo(target, spec)
}

/** [moveTo] along the shared spring. */
internal suspend fun Animatable<Float, *>.moveTo(target: Float) = moveTo(target, Motion.spatial)

/** How far along a fraction [from] to [to] is at [t], which is held to 0..1. */
internal fun between(
    from: Float,
    to: Float,
    t: Float,
): Float = from + (to - from) * t.coerceIn(0f, 1f)

/**
 * The rectangle of something growing out of [tile] to fill [window], at [t] from 0 (the tile's own
 * rectangle) to 1 (the window's): each edge goes straight from one to the other.
 */
internal fun growRect(
    tile: Rect,
    window: Rect,
    t: Float,
): Rect =
    when {
        t <= 0f -> tile
        t >= 1f -> window
        else ->
            Rect(
                between(tile.left, window.left, t),
                between(tile.top, window.top, t),
                between(tile.right, window.right, t),
                between(tile.bottom, window.bottom, t),
            )
    }

/** The least of a tile that has to be showing in the window for something to grow out of it or shrink into it. */
internal const val GROWS_FROM_SHOWING = 0.5f

/**
 * Whether a full-screen thing grows out of [tile] and shrinks back into it, or fades instead: it fades
 * when there is no tile (it was removed, or its page isn't composed), the tile has no size, or less than
 * [GROWS_FROM_SHOWING] of it is inside [window] (the page turned, or the phone was folded onto another page).
 */
internal fun growsFromTile(
    tile: Rect?,
    window: Rect,
): Boolean {
    if (tile == null || tile.width <= 0f || tile.height <= 0f || window.width <= 0f || window.height <= 0f) return false
    val width = minOf(tile.right, window.right) - maxOf(tile.left, window.left)
    val height = minOf(tile.bottom, window.bottom) - maxOf(tile.top, window.top)
    if (width <= 0f || height <= 0f) return false
    return width * height >= tile.width * tile.height * GROWS_FROM_SHOWING
}

/** How much of the way a grow is, by which the growing thing is fully there: it is brought in over its tile, not cut in. */
internal const val GROW_IN_BY = 0.12f

/** How much of the way a grow is, by which the tile's own paper has faded off what grew out of it. */
internal const val GROW_PAPER_OFF_BY = 0.6f

/** The growing thing's opacity at [t]: nothing at the tile, all there by [GROW_IN_BY], and so the same way back. */
internal fun growAlpha(t: Float): Float = (t / GROW_IN_BY).coerceIn(0f, 1f)

/**
 * How much of the tile's own paper lies over the growing thing at [t]: none on the tile itself (the real
 * tile is under it), all of it by [GROW_IN_BY], then easing off until [GROW_PAPER_OFF_BY]. The tile's
 * tilt and outline are on the paper, so they ease out with it.
 */
internal fun growPaper(t: Float): Float =
    when {
        t <= 0f || t >= GROW_PAPER_OFF_BY -> 0f
        t < GROW_IN_BY -> t / GROW_IN_BY
        else -> 1f - (t - GROW_IN_BY) / (GROW_PAPER_OFF_BY - GROW_IN_BY)
    }

/** The part of a tilt, in degrees, that is left at [t] of a grow: all of it at the tile, none at full screen. */
internal fun growTilt(
    tilt: Float,
    t: Float,
): Float = between(tilt, 0f, t)

/** How small something starts that eases in where it stands: a menu, a bar, a page. */
internal const val APPEAR_SCALE = 0.85f

/** How much of the way in something is, by which it is fully opaque; the rest of the way it only grows. */
internal const val APPEAR_IN_BY = 0.5f

/** The opacity of something easing in at [t] of the way, or easing out at [t] left of it. */
internal fun appearAlpha(t: Float): Float = (t / APPEAR_IN_BY).coerceIn(0f, 1f)

/** The longest something takes to ease out, in milliseconds, whatever becomes of its animation: then it is gone. */
internal const val LEAVE_LIMIT_MS = 600L

/**
 * Where [point] is along something that starts at [start] and is [length] long, as the fraction a layer is
 * scaled about: 0 at its start, 1 at its end, and beyond either for a point outside it. The middle for
 * something with no length.
 */
internal fun pivotFraction(
    point: Float,
    start: Float,
    length: Float,
): Float = if (length <= 0f) 0.5f else (point - start) / length

/** The point a layer of [size] at [topLeft] is scaled about for it to grow out of [point], which is in the space [topLeft] is in. */
internal fun pivotAt(
    point: Offset,
    topLeft: Offset,
    size: Size,
): TransformOrigin = TransformOrigin(pivotFraction(point.x, topLeft.x, size.width), pivotFraction(point.y, topLeft.y, size.height))

/**
 * How far in something is that eases in as it first shows: 0 then, 1 at rest, along the shared spring.
 * With the system's animations off it is at rest from the start. A new [key] eases in again.
 */
@Composable
internal fun rememberAppearing(key: Any? = Unit): Animatable<Float, AnimationVector1D> {
    val shown = remember(key) { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(shown) { shown.moveTo(1f) }
    return shown
}

/**
 * How much is left of something that eases out before it goes: 1 at first, 0 as [onGone] is called. That is
 * called within [LEAVE_LIMIT_MS] by the clock, whether or not frames were drawn meanwhile.
 */
@Composable
internal fun rememberLeaving(onGone: () -> Unit): Animatable<Float, AnimationVector1D> {
    val left = remember { Animatable(1f) }
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(left) {
        withTimeoutOrNull(LEAVE_LIMIT_MS) { left.moveTo(0f) }
        gone()
    }
    return left
}

/**
 * Eases in (or out) as [shown] goes from 0 to 1 (or back): scaled from [from] about [pivot], and faded with
 * [fade]. It is one layer's scale and opacity: what it is on is measured and placed as it is at rest, and is
 * touched where it is drawn. At rest nothing is set.
 */
internal fun Modifier.appears(
    shown: () -> Float,
    from: Float = APPEAR_SCALE,
    fade: Boolean = true,
    pivot: GraphicsLayerScope.() -> TransformOrigin = { TransformOrigin.Center },
): Modifier =
    graphicsLayer {
        val t = shown()
        if (t >= 1f) return@graphicsLayer
        if (fade) alpha = appearAlpha(t)
        val scale = between(from, 1f, t)
        scaleX = scale
        scaleY = scale
        transformOrigin = pivot()
    }

/** Fades with [shown], from nothing at 0 to all there by [APPEAR_IN_BY]; at rest nothing is set. */
internal fun Modifier.fades(shown: () -> Float): Modifier =
    graphicsLayer {
        val t = shown()
        if (t < 1f) alpha = appearAlpha(t)
    }

/**
 * A rounded rectangle anywhere inside what it clips, not only around all of it: a layer clipped to this
 * shows, and takes touches, only inside [rect].
 */
internal class RectShape(
    private val rect: Rect,
    private val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = if (radius <= 0f) Outline.Rectangle(rect) else Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}
