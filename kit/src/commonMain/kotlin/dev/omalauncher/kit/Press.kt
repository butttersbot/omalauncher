package dev.omalauncher.kit

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import dev.omalauncher.ui.Motion

/** How small something [longSide] px long goes when pressed at [held] (0 up, 1 down): big things move the same few pixels as small ones. */
fun pressedScale(
    longSide: Float,
    held: Float,
    density: Density,
): Float {
    val most = if (longSide <= 0f) 1f - Feel.PRESS_SCALE else minOf(1f - Feel.PRESS_SCALE, 2f * with(density) { Feel.PRESS_TRAVEL.toPx() } / longSide)
    return 1f - most * held
}

/** How far down a press is, from 0 to 1, on the shared spring, read where it's drawn. It lands at once with animations off. */
@Composable
fun pressAmount(pressed: Boolean): State<Float> =
    if (Motion.reduced) remember { mutableStateOf(0f) } else animateFloatAsState(if (pressed) 1f else 0f, Motion.spatial, label = "press")

/** Goes in by [held]; a layer only, so a press never measures, places or recomposes what is under the finger. */
fun Modifier.pressedLayer(held: () -> Float): Modifier =
    graphicsLayer {
        val t = held()
        if (t > 0f) {
            val scale = pressedScale(maxOf(size.width, size.height), t, this)
            scaleX = scale
            scaleY = scale
        }
    }

/**
 * [combinedClickable] that gives under the finger. The pressed shade stays what the app has ([LocalIndication]); only
 * the give is added, and the look never changes. Put it where `clickable` was.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val held by pressAmount(pressed && enabled)
        pressedLayer { held }.combinedClickable(source, LocalIndication.current, enabled, role = role, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = onClick)
    }
