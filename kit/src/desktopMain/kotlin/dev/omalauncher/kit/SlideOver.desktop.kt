package dev.omalauncher.kit

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

@Composable
actual fun PredictiveBack(
    enabled: Boolean,
    progress: suspend (Flow<Float>) -> Unit,
) {}
