package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.ThreadState

/**
 * A thread's state as a dot, the same on its tile and in the chat's header and tabs: green and gently pulsing
 * while it works, the pack's highlight with requests queued, the accent when a step needs his OK, red when it
 * needs attention, and [idle] when it rests. The one whose tab is [here] is ringed. The pulse is the dot's
 * opacity alone, on the shared timing, and it stands still with the system's animations off.
 */
@Composable
fun ThreadDot(
    state: ThreadState,
    idle: Color,
    here: Boolean = false,
) {
    val color =
        when (state) {
            ThreadState.Working -> Pack.green
            ThreadState.Queued -> Pack.highlight
            ThreadState.NeedsOk -> Pack.accent
            ThreadState.Attention -> Pack.red
            ThreadState.Idle -> idle
        }
    val working = state == ThreadState.Working
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(working) {
        // Asked at every beat, so turning the system's animations off stops it where a snap would otherwise spin.
        while (working && !Motion.reduced) {
            pulse.moveTo(THREAD_DOT_DIM, Motion.timed(THREAD_PULSE_MS))
            pulse.moveTo(1f, Motion.timed(THREAD_PULSE_MS))
        }
        pulse.snapTo(1f)
    }
    Box(
        Modifier.size(17.dp).then(if (here) Modifier.framed(Pack.mark, Pack.foreground, radius = 9.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer { alpha = pulse.value }
                // The highlight is a marker's colour and can be lost on a light scrap: it gets the ink's hairline, as in the mock-up.
                .then(if (state == ThreadState.Queued) Modifier.framed(Pack.hair, Pack.foreground, color, radius = 5.dp) else Modifier.background(color, Pack.round(5.dp))),
        )
    }
}

/** How far a working thread's dot dims at each beat, and how long half a beat takes: the mock-up's 1.2 seconds a beat. */
const val THREAD_DOT_DIM = 0.45f
const val THREAD_PULSE_MS = 600
