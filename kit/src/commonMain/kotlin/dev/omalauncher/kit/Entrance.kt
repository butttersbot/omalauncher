package dev.omalauncher.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.snap
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.moveTo

/**
 * One list item's way in: 1 at once unless [entering], which is asked once per [key], so history, a reconnect or an
 * echo scrolling back into view never plays it again. With animations off it is at rest from the start.
 */
@Composable
fun rememberEntrance(
    key: Any,
    entering: () -> Boolean,
): Animatable<Float, AnimationVector1D> {
    val going = remember(key) { entering() && !Motion.reduced }
    val entrance = remember(key) { Animatable(if (going) 0f else 1f) }
    LaunchedEffect(key) { entrance.moveTo(1f, Feel.entrance) }
    return entrance
}

/**
 * Lays [progress] on what arrives, from [Feel]'s place and size to rest, growing out of its [end] corner at the foot:
 * the sender's side of a conversation. A layer only: the list measures and scrolls it where it will rest.
 */
fun Modifier.entrance(
    end: Boolean,
    progress: () -> Float,
): Modifier =
    graphicsLayer {
        val p = progress()
        transformOrigin = TransformOrigin(if (end) 1f else 0f, 1f)
        scaleX = Feel.ENTRANCE_SCALE + p * (1f - Feel.ENTRANCE_SCALE)
        scaleY = scaleX
        translationY = (1f - p) * Feel.ENTRANCE_RISE.toPx()
        alpha = Feel.ENTRANCE_ALPHA + p * (1f - Feel.ENTRANCE_ALPHA)
    }

/** A list item that moves when its neighbours change, as an inbox reordering does: on the spring, no fades. */
fun Modifier.movesInList(scope: LazyItemScope): Modifier =
    with(scope) { animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (Motion.reduced) snap() else Motion.spatial()) }
