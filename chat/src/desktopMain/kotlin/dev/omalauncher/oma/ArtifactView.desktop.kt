package dev.omalauncher.oma

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Pack

/** Desktop keeps a readable fallback until its browser renderer is integrated; Android has the live WebView. */
@Composable
internal actual fun ArtifactWeb(url: String?, html: String?, modifier: Modifier, onAnswer: ((ArtifactReplyRequest) -> Unit)?, onApprove: ((ArtifactCanvasRequest) -> Unit)?) {
    Column(modifier) {
        BasicText("Interactive web previews are available on Android. Source is shown here.", Modifier.padding(16.dp), style = Pack.body(Pack.size.small, Pack.muted))
        ArtifactText(html ?: url.orEmpty())
    }
}

@Composable
internal actual fun ArtifactImage(bytes: ByteArray, description: String, modifier: Modifier) {
    val image = remember(bytes) {
        runCatching {
            val codec = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes))
            val info = codec.imageInfo
            codec.close()
            if (info.width.toLong() * info.height > 16_000_000) null
            else org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
    if (image != null) Image(image, description, modifier, contentScale = ContentScale.Fit)
    else BasicText("This image could not be decoded.", modifier.padding(16.dp), style = Pack.body())
}
