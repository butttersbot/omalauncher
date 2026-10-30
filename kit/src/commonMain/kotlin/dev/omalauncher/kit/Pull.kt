package dev.omalauncher.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A sideways pull inside a list: vertical scrolling wins until horizontal slop, and a touch left of [edge] is left to
 * the system's Back. [left] is the pull's direction at slop, and it stays that for the whole gesture. Left is always
 * taken; right only with [allowRight].
 */
fun Modifier.sidewaysPull(
    key: Any,
    rootLeft: () -> Float,
    edge: Float,
    allowRight: Boolean = true,
    delta: (left: Boolean, pixels: Float) -> Unit,
    end: (left: Boolean, completed: Boolean) -> Unit,
): Modifier = pointerInput(key, edge, allowRight) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (rootLeft() + down.position.x < edge) return@awaitEachGesture
        var left = false
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over < 0 || allowRight) {
                left = over < 0
                change.consume()
                delta(left, over)
            }
        } ?: return@awaitEachGesture
        val completed = horizontalDrag(start.id) { change ->
            delta(left, change.position.x - change.previousPosition.x)
            change.consume()
        }
        end(left, completed)
    }
}

/** Where a pull lands with the finger [raw] px along and [mark] px to arm: one to one, then stiff past [Feel.PULL_STIFFENS_AT] marks. */
fun pulled(
    raw: Float,
    mark: Float,
): Float {
    val stiff = mark * Feel.PULL_STIFFENS_AT
    return if (raw <= stiff) raw else stiff + (raw - stiff) * Feel.PULL_GIVE
}
