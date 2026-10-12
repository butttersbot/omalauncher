package dev.omalauncher.oma.ring

import dev.omalauncher.theme.pack.PackRing
import dev.omalauncher.theme.pack.PackRingState
import kotlin.math.PI
import kotlin.math.cos

/** One breath of a light that pulses: bright, down to [BREATH_LOW], bright again. */
const val BREATH_MILLIS = 1600L

/** How dim a breath gets, as a share of the state's opacity. */
const val BREATH_LOW = 0.4f

/** How long the light takes to come up from dark, and to go out after its last breath. */
const val FADE_MILLIS = 600L

/** How many widening strokes stand in for the blur of the glow. */
const val GLOW_STEPS = 4

/** The pack's look for [state]; null when the ring is off. */
fun PackRing.lookOf(state: RingState): PackRingState? = when (state) {
    RingState.Off -> null
    RingState.Listening -> listening
    RingState.Working -> working
    RingState.NeedsYou -> needsYou
    RingState.Done -> done
}

/** How long done shows: its breaths, or [DONE_MILLIS] for a pack whose done light is steady. */
fun doneMillis(done: PackRingState): Long = if (done.pulses > 0) done.pulses * BREATH_MILLIS else DONE_MILLIS

/** How far around the light has run, in degrees clockwise, [sinceMillis] into a state. */
fun ringAngle(periodMs: Int, sinceMillis: Long): Float =
    if (periodMs <= 0) 0f else (sinceMillis.coerceAtLeast(0) % periodMs) * 360f / periodMs

/**
 * The share of its opacity a light shows [sinceMillis] into its state: steady for a light that doesn't
 * pulse, otherwise [pulses] breaths, going out over the end of the last one and dark after it.
 */
fun breath(pulses: Int, sinceMillis: Long): Float {
    if (pulses <= 0) return 1f
    val t = sinceMillis.coerceAtLeast(0)
    val end = pulses * BREATH_MILLIS
    if (t >= end) return 0f
    val phase = (t % BREATH_MILLIS) / BREATH_MILLIS.toFloat()
    val wave = (1f + BREATH_LOW) / 2f + (1f - BREATH_LOW) / 2f * cos(2.0 * PI * phase).toFloat()
    return wave * ((end - t) / FADE_MILLIS.toFloat()).coerceIn(0f, 1f)
}

/** How far the light has come up, [sinceLitMillis] after the ring left off. */
fun fadeIn(sinceLitMillis: Long): Float = (sinceLitMillis / FADE_MILLIS.toFloat()).coerceIn(0f, 1f)

/** How long the ring has been in its state and how long it has been lit at all. */
data class RingMoment(val state: RingState, val sinceState: Long, val sinceLit: Long)

/** Remembers when the ring last changed, for one host: each draws from its own, so a host that starts late still fades in. */
class RingTimeline {
    private var state = RingState.Off
    private var stateAt = 0L
    private var litAt = 0L

    /** The moment for [next] at [now]; the clock only has to be the same one from call to call. */
    fun at(next: RingState, now: Long): RingMoment {
        if (next != state) {
            if (state == RingState.Off) litAt = now
            state = next
            stateAt = now
        }
        return RingMoment(state, now - stateAt, now - litAt)
    }
}

/** What to draw for one moment, in pixels and ARGB. */
data class RingFrame(
    /** The line's width. */
    val width: Float,
    /** How far the glow reaches past the line on each side; 0 for a bare line. */
    val glow: Float,
    /** The glow's opacity where it is strongest, as a share of [alpha]. */
    val glowAlpha: Float,
    val color1: Int,
    val color2: Int,
    /** The whole ring's opacity, 0 to 1. */
    val alpha: Float,
    /** Degrees clockwise the light has run. */
    val angle: Float,
)

/**
 * The ring for [moment] in the pack's style, on a screen of [density] pixels to the dp; null when there is
 * nothing to draw. The only place a state, a time and a style become a picture: every host draws what this returns.
 */
fun ringFrame(ring: PackRing, moment: RingMoment, density: Float): RingFrame? {
    val look = ring.lookOf(moment.state) ?: return null
    val alpha = (look.opacity * fadeIn(moment.sinceLit) * breath(look.pulses, moment.sinceState)).coerceIn(0f, 1f)
    if (alpha <= 0f || ring.width <= 0f || density <= 0f) return null
    return RingFrame(
        width = ring.width * density,
        glow = maxOf(0f, ring.glow) * density,
        glowAlpha = ring.glowOpacity.coerceIn(0f, 1f),
        color1 = look.color1.argb,
        color2 = look.color2.argb,
        alpha = alpha,
        angle = ringAngle(look.periodMs, moment.sinceState),
    )
}

/** One stroke along the screen's edge. */
data class RingStroke(val width: Float, val alpha: Float)

/**
 * The strokes that make [frame], widest first and the line itself last. The glow is [GLOW_STEPS] wider
 * strokes of equal faintness lying on one another, so it is strongest at the line and thins outward.
 */
fun ringStrokes(frame: RingFrame): List<RingStroke> {
    val glow = if (frame.glow <= 0f || frame.glowAlpha <= 0f) emptyList() else (GLOW_STEPS downTo 1).map { step ->
        RingStroke(frame.width + 2f * frame.glow * step / GLOW_STEPS, frame.alpha * frame.glowAlpha / GLOW_STEPS)
    }
    return glow + RingStroke(frame.width, frame.alpha)
}

/**
 * The light as it runs around the edge, as the colors and positions of a sweep gradient: two comets a half
 * turn apart, each growing from nothing to [color1] and ending in [color2] at its head. The gaps are the
 * colors themselves at no opacity, so nothing darkens where they fade.
 */
fun ringStops(color1: Int, color2: Int): Pair<IntArray, FloatArray> {
    val clear1 = color1 and 0x00FFFFFF
    val clear2 = color2 and 0x00FFFFFF
    return intArrayOf(clear1, clear1, color1, color2, clear2, clear2, color2, color1) to
        floatArrayOf(0f, 0.22f, 0.46f, 0.5f, 0.502f, 0.72f, 0.96f, 1f)
}
