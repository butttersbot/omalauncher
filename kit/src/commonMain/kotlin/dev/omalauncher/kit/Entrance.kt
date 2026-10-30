package dev.omalauncher.kit

import androidx.compose.animation.core.Animatable
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
 * One list item's way in, from [Feel]'s place and size to rest, growing out of its [end] corner at the foot: the sender's
 * side of a conversation. [entering] is asked once per [key], so history, a reconnect or an echo scrolling back into view
 * never plays it again. An item that isn't arriving costs nothing while scrolling: no animation, no effect and no layer.
 */
@Composable
fun Modifier.arriving(
    key: Any,
    end: Boolean,
    entering: () -> Boolean,
): Modifier {
    val going = remember(key) { entering() && !Motion.reduced }
    if (!going) return this
    val entrance = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { entrance.moveTo(1f, Feel.entrance) }
    return entrance(end) { entrance.value }
}

/**
 * Which items are new, for a list that has no marker of its own as Messages' sends have: what's newer than the newest
 * [seen] when the list opened, each once, so scrolling back to one or reopening the list doesn't play it again.
 */
class NewerThan(seen: Long) {
    private var newest = seen

    fun take(time: Long): Boolean = (time > newest).also { if (it) newest = time }
}

/** Lays [progress] on what arrives. A layer only: the list measures and scrolls it where it will rest. */
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
