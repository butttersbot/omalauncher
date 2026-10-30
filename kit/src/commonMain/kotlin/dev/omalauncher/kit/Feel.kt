package dev.omalauncher.kit

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Motion

/**
 * The numbers behind Soma's feel, taken from Soma Messages 0.8.7 (Boss, 2026-10-30: "really smooth"). The spring and
 * the fade are [Motion]'s; these are the rest. Timings began from the MacBook's iPhone reference and are starting
 * points, not measurements of input latency. Tune here, never on a screen. See docs/SOMA-UI-KIT.md.
 */
object Feel {
    /** A screen sliding over another, when started by a tap. A finger's slide lands on [Motion.spatial] instead. */
    const val OPEN_MS = 270

    /** The same screen leaving, by its back button. */
    const val CLOSE_MS = 200

    /** Something just sent arriving in a list. */
    const val ENTRANCE_MS = 370

    /** A menu or a sheet over the screen. */
    const val OVERLAY_MS = 180

    val open: FiniteAnimationSpec<Float> get() = Motion.timed(OPEN_MS, Motion.easing)
    val close: FiniteAnimationSpec<Float> get() = Motion.timed(CLOSE_MS, Motion.easing)
    val entrance: FiniteAnimationSpec<Float> get() = Motion.timed(ENTRANCE_MS, Motion.easing)
    val overlay: FiniteAnimationSpec<Float> get() = Motion.timed(OVERLAY_MS, Motion.easing)

    /** Where an arriving list item starts: a little small, a little low and not yet solid. */
    const val ENTRANCE_SCALE = 0.92f
    const val ENTRANCE_ALPHA = 0.6f
    val ENTRANCE_RISE = 24.dp

    /** How an overlay starts, and how dark the screen goes under it at rest (of the ink the caller hands in). */
    const val OVERLAY_SCALE = 0.96f
    const val SCRIM_ALPHA = 0.18f

    /** Touches this close to the screen's edge belong to the system's Back and never start a drag. */
    val EDGE_GUARD = 24.dp

    /** A screen slid this much of its width goes when let go; less, and it comes back. */
    const val SLIDE_AWAY_AT = 0.35f

    /** A flick this fast, in dp a second, sends it from wherever it is. */
    const val SLIDE_AWAY_FLING = 500

    /** How far something pulled sideways goes to arm what it does (a reply), with a tick of the haptics. */
    val PULL_MARK = 64.dp

    /** Past this many marks a pull stiffens, moving [PULL_GIVE] of what the finger does. */
    const val PULL_STIFFENS_AT = 1.3f
    const val PULL_GIVE = 0.3f

    /**
     * How small a thing goes under the finger, and the most its long side moves. Messages has no press motion of its
     * own (its rows take Compose's instant pressed shade), so this one is new with the kit and not from its reference.
     */
    const val PRESS_SCALE = 0.97f
    val PRESS_TRAVEL = 6.dp
}
