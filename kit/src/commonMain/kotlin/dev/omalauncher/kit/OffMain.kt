package dev.omalauncher.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What [load] gives, worked out off the main thread: null until it is ready. Disk, decoding and thumbnails go here, so
 * no frame waits on them. A new [key] loads again and keeps showing the last until then.
 */
@Composable
fun <T : Any> offMain(
    key: Any?,
    load: suspend () -> T?,
): State<T?> = produceState<T?>(null, key) { value = withContext(Dispatchers.IO) { load() } }
