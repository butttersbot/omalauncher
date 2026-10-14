package dev.omalauncher.oma

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.toColor

/*
 * The thread dots and the arrow that drops the chat's tabs down, drawn as design/oma-threads-mockup.html
 * draws them, number for number (Boss, 2026-10-13: "The pulse just use the HTML code"). The mock-up's
 * pixels are dp here. They are kept in this one file because the house rules let them alone keep the
 * mock-up's own round shape and timing (PackSourceCheckTest, MotionSourceCheckTest); the colours are
 * still the pack's, which in City Pop are the mock-up's.
 */

/** `.dot`: a thread's dot on its tile and in a tab. */
val THREAD_DOT = 9.dp

/** `.dots .dot`: the bigger dots in the chat's header. */
val HEADER_DOT = 11.dp

/** `.dots .dot.here`: the open thread's ring, 2 wide and 2 out from its dot. */
val HERE_RING = 2.dp
val HERE_RING_OFFSET = 2.dp

/** How far past its own box a header dot is drawn: its ring. Whatever holds the dots and clips leaves this much room. */
val HEADER_DOT_REACH = HERE_RING_OFFSET + HERE_RING

/** The 1 px outline of a queued dot (`.queued .dot`) and of the dot on the open tab (`.tab.on .dot`). */
private val DOT_OUTLINE = 1.dp

/** `@keyframes pulse { 50% { transform: scale(1.6); opacity: .45 } }` on `animation: pulse 1.2s ease-in-out infinite`. */
internal const val PULSE_MS = 1200
internal const val PULSE_SCALE = 1.6f
internal const val PULSE_ALPHA = 0.45f

/** CSS's `ease-in-out`, which the pulse runs on from either end to its middle and back. */
private val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** `.drop { transition: transform .15s }`: how long the arrow takes to turn over. */
internal const val DROP_TURN_MS = 150

/**
 * A thread's state as a round dot of [size]: green and pulsing while it works, the highlight outlined in
 * [ink] with requests queued, the scrap's focus colour (the mock-up's pink) when a step needs his OK, red
 * when it needs attention, and [idle] when it rests. [here] rings it in [ink], for the open thread in the
 * header; [outline], for the dot on the open tab, outlines it whatever its state. With the system's
 * animations off the pulse stands still.
 */
@Composable
fun ThreadDot(
    state: ThreadState,
    idle: Color,
    size: Dp = THREAD_DOT,
    ink: Color = Pack.foreground,
    here: Boolean = false,
    outline: Color? = null,
) {
    val fill =
        when (state) {
            ThreadState.Working -> Pack.green
            ThreadState.Queued -> Pack.highlight
            ThreadState.NeedsOk -> Pack.look.pack.scrap.surface.focusColor.toColor()
            ThreadState.Attention -> Pack.red
            ThreadState.Idle -> idle
        }
    val line = outline ?: ink.takeIf { state == ThreadState.Queued }
    val pulse = if (state == ThreadState.Working && !Motion.reduced) pulse() else 0f
    Box(
        Modifier
            .size(size)
            // The whole dot scales and fades, its outline and ring with it, as a CSS transform takes an element's outline along.
            .graphicsLayer {
                val scale = 1f + (PULSE_SCALE - 1f) * pulse
                scaleX = scale
                scaleY = scale
                alpha = 1f - (1f - PULSE_ALPHA) * pulse
            }.drawBehind {
                val radius = this.size.minDimension / 2f
                drawCircle(fill, radius)
                // A CSS outline lies outside the box, so each line is drawn from the dot's edge outwards.
                if (line != null) drawCircle(line, radius + DOT_OUTLINE.toPx() / 2f, style = Stroke(DOT_OUTLINE.toPx()))
                if (here) drawCircle(ink, radius + HERE_RING_OFFSET.toPx() + HERE_RING.toPx() / 2f, style = Stroke(HERE_RING.toPx()))
            },
    )
}

/** How far into a beat the pulse is: 0 at rest, 1 at the beat's middle, where the dot is biggest and faintest. */
@Composable
private fun pulse(): Float {
    val beat by rememberInfiniteTransition("pulse").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(PULSE_MS / 2, easing = CssEaseInOut), RepeatMode.Reverse),
        "pulse",
    )
    return beat
}

/** `.drop`: the arrow that drops the tabs down, turned over while they are [open]. */
@Composable
fun DropArrow(
    open: Boolean,
    modifier: Modifier = Modifier,
) {
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.timed(DROP_TURN_MS), label = "drop")
    BasicText("▾", modifier.rotate(turn).padding(horizontal = 8.dp, vertical = 2.dp), style = Pack.body(Pack.size.title))
}
