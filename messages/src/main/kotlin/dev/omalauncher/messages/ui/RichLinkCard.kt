package dev.omalauncher.messages.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.messages.engine.MacLink
import dev.omalauncher.messages.engine.Parts
import dev.omalauncher.messages.engine.RichLink
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Same metadata card in either direction, the draft, and the unfolded shared table. */
@Composable
internal fun RichLinkCard(link: RichLink, ink: Color, compact: Boolean = false) {
    val context = LocalContext.current
    val picture by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, link.imageAttachmentId) {
        value = withContext(Dispatchers.IO) {
            link.imageAttachmentId?.let { MacLink.file(context, it, picture = true) }?.let { file ->
                runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                    val sample = Integer.highestOneBit(maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1) / 720).coerceAtLeast(1)
                    android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Column(if (compact) Modifier.fillMaxWidth() else Modifier.width(260.dp)) {
        if (link.imageAttachmentId != null) {
            Box(Modifier.fillMaxWidth().height(if (compact) 66.dp else 132.dp).background(Pack.surface), contentAlignment = Alignment.Center) {
                val p = picture
                if (p != null) Image(p, "Link preview image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else BasicText("Link preview", style = Pack.body(Pack.size.small, Pack.muted))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicText(Pack.caption(link.site ?: Parts.host(link.url)), style = Pack.mono(Pack.size.small, ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(link.title ?: Parts.path(link.url).ifEmpty { link.url }, style = Pack.body(Pack.size.item, ink).copy(fontWeight = FontWeight.SemiBold), maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
