package dev.omalauncher.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.moveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Android's Back gesture while [enabled]: [progress] gets its 0..1 and ends when he lets go, cancelled if he took it back. None on the desktop. */
@Composable
expect fun PredictiveBack(
    enabled: Boolean,
    progress: suspend (Flow<Float>) -> Unit,
)

/** Whether a slide [slid] of the way, moving at [speed] px a second, goes on letting go rather than comes back. */
fun slidesAway(
    slid: Float,
    speed: Float,
    flingSpeed: Float,
): Boolean = slid > Feel.SLIDE_AWAY_AT || speed > flingSpeed

/**
 * A screen over another, as Messages' conversation is over its inbox: it slides in from the right, and Android's back
 * gesture or a drag to the right anywhere on it moves it with the finger. Letting go far enough, or flicking, finishes
 * the slide; letting go early puts it back. [background] paints the screen, so what's under it doesn't show through.
 * What's inside sees the drag first, so a sideways pull there can take it.
 */
@Composable
fun SlideOver(
    widthPx: Float,
    onGone: () -> Unit,
    background: Modifier = Modifier,
    content: @Composable (back: () -> Unit, modalChanged: (Boolean) -> Unit) -> Unit,
) {
    var modal by remember { mutableStateOf(false) }
    val modalChanged: (Boolean) -> Unit = remember { { value -> modal = value } }
    val x = remember { Animatable(if (Motion.reduced) 0f else widthPx) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { x.moveTo(0f, Feel.open) }
    // Gone, it is back in place, in the same frame: if something else stays in this screen, that shows instead of nothing.
    fun gone() {
        onGone()
        scope.launch { x.snapTo(0f) }
    }
    val back: () -> Unit = {
        scope.launch {
            x.moveTo(widthPx, Feel.close)
            gone()
        }
    }
    PredictiveBack(enabled = !modal) { progress ->
        try {
            progress.collect { p -> x.snapTo(p * widthPx) }
            x.moveTo(widthPx, Motion.spatial)
            gone()
        } catch (e: CancellationException) {
            // The finger let go early: the screen goes back where it was, outside the cancelled gesture.
            scope.launch { x.moveTo(0f, Motion.spatial) }
            throw e
        }
    }
    val fling = with(LocalDensity.current) { Feel.SLIDE_AWAY_FLING.dp.toPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(widthPx, modal) {
            if (modal) return@pointerInput
            val velocity = VelocityTracker()
            var pulling = false
            var decided = false
            detectHorizontalDragGestures(
                onDragStart = {
                    pulling = false
                    decided = false
                    velocity.resetTracking()
                },
                onDragEnd = {
                    if (pulling) {
                        val v = velocity.calculateVelocity().x
                        scope.launch {
                            if (slidesAway(x.value / widthPx, v, fling)) {
                                x.moveTo(widthPx, Motion.spatial)
                                gone()
                            } else {
                                x.moveTo(0f, Motion.spatial)
                            }
                        }
                    }
                    pulling = false
                },
                onDragCancel = {
                    if (pulling) scope.launch { x.moveTo(0f, Motion.spatial) }
                    pulling = false
                },
            ) { change, dx ->
                // A drag that starts leftward isn't a way back, even if it turns.
                if (!decided) {
                    decided = true
                    pulling = dx > 0f
                }
                if (!pulling) return@detectHorizontalDragGestures
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                scope.launch { x.snapTo((x.value + dx).coerceIn(0f, widthPx)) }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().offset { IntOffset(x.value.roundToInt(), 0) }.then(background)) { content(back, modalChanged) }
    }
}
