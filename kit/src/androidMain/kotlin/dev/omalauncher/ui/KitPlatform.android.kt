package dev.omalauncher.ui

import android.animation.ValueAnimator

actual fun systemAnimationsOn(): Boolean = ValueAnimator.areAnimatorsEnabled()
