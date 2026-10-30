package dev.omalauncher.kit

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Composable
actual fun PredictiveBack(
    enabled: Boolean,
    progress: suspend (Flow<Float>) -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { events: Flow<BackEventCompat> -> progress(events.map { it.progress }) }
}
