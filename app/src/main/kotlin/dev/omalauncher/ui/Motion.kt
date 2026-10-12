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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Home's motion, in one place: what grows out of the thing that was touched and goes back into it moves
 * on [spatial], what only appears or goes fades over [FADE_MS] along [easing]. The transitions of home, its
 * menu, folders, the overview, notes, stickers, the dock and the ring take their specs from here and
 * nowhere else, so they feel alike and are tuned here.
 */
object Motion {
    /** How stiff the spring is: a tile-sized thing settles in about a quarter of a second. */
    const val STIFFNESS = 600f

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
