package dev.omalauncher.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import dev.omalauncher.ui.Motion

/**
 * [value], following the finger exactly while [following] and settling home on the shared spring when it lets go. A
 * finger's speed carries into the spring, and a new target turns it around mid-way. Read the result where it's drawn.
 */
@Composable
fun settling(
    value: Float,
    following: Boolean,
    label: String = "settling",
): State<Float> = animateFloatAsState(value, if (following || Motion.reduced) snap() else Motion.spatial, label = label)
