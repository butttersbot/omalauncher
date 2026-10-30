package dev.omalauncher.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.omalauncher.ui.Motion

/** A bar or strip that opens in the column it's in, pushing what's below on the shared spring, as it would by popping in at rest. */
@Composable
fun ColumnScope.Reveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = if (Motion.reduced) EnterTransition.None else expandVertically(Motion.spatial()) + fadeIn(Motion.fade()),
        exit = if (Motion.reduced) ExitTransition.None else shrinkVertically(Motion.spatial()) + fadeOut(Motion.fade()),
    ) { content() }
}
