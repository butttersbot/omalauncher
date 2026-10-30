package dev.omalauncher.oma

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import dev.omalauncher.kit.overlayEntrance
import dev.omalauncher.kit.rememberOverlayEntrance
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How large a picture is decoded for the whole screen, on its longer side. */
private const val VIEWER_LONGEST = 2400

/**
 * A picture from the chat over the whole screen (Boss, 2026-10-21: "i should be able to click on any image thumbnail
 * in the chat app to expand it"): pinch or double-tap to zoom, drag to look round it, a tap or back to close.
 */
@Composable
fun ChatImageViewer(
    host: ChatHost,
    file: File,
    onClose: () -> Unit,
) {
    val picture by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { host.thumbnail(file, VIEWER_LONGEST) }.getOrNull() }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val entrance = rememberOverlayEntrance()
        Box(
            Modifier
                .fillMaxSize()
                .overlayEntrance { entrance.value }
                .background(Color.Black)
                .pointerInput(file) {
                    detectTapGestures(
                        onTap = { onClose() },
                        onDoubleTap = { at ->
                            if (zoom > 1f) {
                                zoom = 1f
                                pan = Offset.Zero
                            } else {
                                zoom = 2.5f
                                // Toward where he tapped.
                                pan = (Offset(size.width / 2f, size.height / 2f) - at) * 1.5f
                            }
                        },
                    )
                }.pointerInput(file) {
                    detectTransformGestures { _, moved, scaled, _ ->
                        zoom = (zoom * scaled).coerceIn(1f, 6f)
                        pan = if (zoom == 1f) Offset.Zero else pan + moved
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = picture
            if (shown != null) {
                Image(
                    shown,
                    file.name,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                    contentScale = ContentScale.Fit,
                )
            } else {
                BasicText("…", style = Pack.body(color = Color.White))
            }
        }
    }
}
