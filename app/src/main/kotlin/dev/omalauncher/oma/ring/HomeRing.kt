package dev.omalauncher.oma.ring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import dev.omalauncher.ui.Motion

/**
 * The ring around the launcher. It fills whatever it is put over and only draws there: it has no pointer
 * input, so every touch goes to what is underneath, and as a sibling of the page it changes no layout.
 * Frames run only while the ring is lit, and for the moment it takes to go out.
 */
@Composable
fun HomeRing(modifier: Modifier = Modifier) {
    val view = LocalView.current
    var signals by remember { mutableStateOf(OmaRing.signals) }
    DisposableEffect(Unit) {
        val unwatch = OmaRing.watch { signals = OmaRing.signals }
        signals = OmaRing.signals
        onDispose(unwatch)
    }
    val style = ringStyle()
    val doneFor = doneMillis(style.done)
    // Read only while drawing, so a frame redraws the ring and recomposes nothing.
    var now by remember { mutableLongStateOf(OmaRing.now()) }
    val painter = remember { RingPainter() }
    val timeline = remember { RingTimeline() }
    // Off doesn't cut: the light goes out over the launcher's fade, unless the system has animations off.
    val fadeOut = if (Motion.reduced) 0L else Motion.FADE_MS.toLong()
    LaunchedEffect(signals, doneFor, fadeOut) {
        do {
            withFrameMillis { now = OmaRing.now() }
        } while (!timeline.at(ringState(signals, now, doneFor), now).dark(fadeOut))
    }
    Canvas(modifier.fillMaxSize()) {
        val moment = timeline.at(ringState(signals, now, doneFor), now)
        val frame = ringFrame(style, moment, density, fadeOut) ?: return@Canvas
        drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, screenCorner(view), frame) }
    }
}
