package dev.omalauncher.messages.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Vertical scrolling wins until horizontal slop. Edge gestures remain available to navigation. */
internal fun Modifier.messageDrag(
    key: Any,
    rootLeft: () -> Float,
    edge: Float,
    allowReply: Boolean = true,
    delta: (times: Boolean, pixels: Float) -> Unit,
    end: (times: Boolean, completed: Boolean) -> Unit,
): Modifier = pointerInput(key, edge, allowReply) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (rootLeft() + down.position.x < edge) return@awaitEachGesture
        var times = false
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over < 0 || allowReply) {
                times = over < 0
                change.consume()
                delta(times, over)
            }
        } ?: return@awaitEachGesture
        val completed = horizontalDrag(start.id) { change ->
            delta(times, change.position.x - change.previousPosition.x)
            change.consume()
        }
        end(times, completed)
    }
}
