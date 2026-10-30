package dev.omalauncher.messages.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import dev.omalauncher.ui.Motion

/**
 * Messages-only starting points from the MacBook iOS 26.5 reference (2026-10-30).
 * Its VFR visible-change spans are approximate, not input latency or complete settling.
 * Keep Android IME motion native and unobserved interactive gestures on the shared spring.
 */
internal object MessageMotion {
    val open: FiniteAnimationSpec<Float> get() = Motion.timed(270, Motion.easing)
    val close: FiniteAnimationSpec<Float> get() = Motion.timed(200, Motion.easing)
    val send: FiniteAnimationSpec<Float> get() = Motion.timed(370, Motion.easing)
    const val MENU_ENTER_MS = 180
}
