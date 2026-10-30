package dev.omalauncher.messages.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.moveTo
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The picture open over the app. Kept at its root, so the conversation under it is never touched, scrolled or reloaded. */
internal object PhotoViewer {
    data class Open(val file: File, val moving: Boolean)

    val open = mutableStateOf<Open?>(null)

    fun show(file: File, moving: Boolean = false) {
        open.value = Open(file, moving)
    }
}

@Composable internal fun PhotoViewerHost() {
    PhotoViewer.open.value?.let { photo -> FullScreenPhoto(photo) { PhotoViewer.open.value = null } }
}

/** A swipe this far or this fast sends the picture away; less, and it settles back. */
private val SWIPE_AWAY = 96.dp
private val SWIPE_AWAY_SPEED = 900.dp
private const val MAX_ZOOM = 5f
private const val LONGEST_SIDE = 2160

/**
 * Full screen in Messages' own window, over the chat (Boss, 0.8.7: it opened the gallery, and back landed on another
 * gallery screen). A swipe up or down or Back returns to the same place; a pinch zooms, as the gallery he had did.
 */
@Composable private fun FullScreenPhoto(photo: PhotoViewer.Open, onGone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    val swipe = remember { Animatable(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var closing by remember { mutableStateOf(false) }
    val picture by produceState<ImageBitmap?>(null, photo) {
        if (!photo.moving) value = withContext(Dispatchers.IO) { runCatching { decode(photo.file, LONGEST_SIDE) }.getOrNull() }
    }
    val moving by produceState<android.graphics.drawable.Drawable?>(null, photo) {
        if (photo.moving) value = withContext(Dispatchers.IO) { runCatching { moving(context, null, photo.file) }.getOrNull() }
    }
    LaunchedEffect(photo) { shown.moveTo(1f, MessageMotion.open) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            shown.moveTo(0f, MessageMotion.close)
            onGone()
        }
    }
    MessageOverlay(::close, dismissOnOutside = false, scrim = false, lightBars = false) {
        Box(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "Photo, full screen"; dismiss { close(); true } }
                .graphicsLayer { alpha = shown.value * (1f - abs(swipe.value) / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f) }
                .background(Color.Black),
        )
        Box(
            Modifier.fillMaxSize().pointerInput(photo) {
                val awayAt = SWIPE_AWAY.toPx()
                val awaySpeed = SWIPE_AWAY_SPEED.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val speed = VelocityTracker()
                    var travel = 0f
                    var swiping = false
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (!swiping && (fingers > 1 || zoom > 1f)) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val room = Offset((zoom - 1f) * size.width / 2, (zoom - 1f) * size.height / 2)
                            val moved = pan + event.calculatePan()
                            pan = Offset(moved.x.coerceIn(-room.x, room.x), moved.y.coerceIn(-room.y, room.y))
                        } else if (fingers == 1) {
                            travel += event.calculatePan().y
                            if (abs(travel) > viewConfiguration.touchSlop) swiping = true
                            if (swiping) {
                                val first = event.changes.first()
                                speed.addPosition(first.uptimeMillis, first.position)
                                scope.launch { swipe.snapTo(travel) }
                            }
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (swiping) {
                        if (abs(travel) > awayAt || abs(speed.calculateVelocity().y) > awaySpeed) {
                            val sign = if (travel < 0f) -1f else 1f
                            scope.launch { swipe.moveTo(sign * size.height, MessageMotion.close) }
                            close()
                        } else scope.launch { swipe.moveTo(0f, Motion.spatial) }
                    }
                }
            }.graphicsLayer {
                alpha = shown.value
                val entering = 0.96f + 0.04f * shown.value
                scaleX = entering * zoom
                scaleY = entering * zoom
                translationX = pan.x
                translationY = pan.y + swipe.value
            },
        ) {
            val m = moving
            val p = picture
            if (m != null) {
                AndroidView(
                    { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
                    Modifier.fillMaxSize(),
                    update = { v ->
                        if (v.drawable !== m) {
                            v.setImageDrawable(m)
                            (m as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                        }
                    },
                    onRelease = { (m as? android.graphics.drawable.AnimatedImageDrawable)?.stop() },
                )
            } else if (p != null) {
                Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}
