package dev.omalauncher.messages.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import dev.omalauncher.messages.engine.XPost
import dev.omalauncher.messages.engine.XPreviews
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The card for [url]: the Mac's [known] metadata, with an X post's own when the Mac has no picture for it. */
@Composable
internal fun linkCardFor(url: String, known: RichLink?, settleMs: Long = 0): RichLink? {
    if (XPost.statusId(url) == null || (known?.imageAttachmentId != null && known.state != "unavailable")) return known
    val context = LocalContext.current
    val found by XPreviews.found.collectAsState()
    LaunchedEffect(url) {
        delay(settleMs)
        XPreviews.request(context, url)
    }
    return XPost.fill(known, found[url] ?: XPreviews.kept(url))
}

/** Same metadata card in either direction, the draft, and the unfolded shared table. */
@Composable
internal fun RichLinkCard(link: RichLink, ink: Color, compact: Boolean = false, draft: Boolean = false, onRemove: () -> Unit = {}) {
    val context = LocalContext.current
    val picture by produceState(link.imageAttachmentId?.let { PictureCache[it] }, link.imageAttachmentId) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            link.imageAttachmentId?.let { if (XPreviews.owns(it)) XPreviews.image(context, it) else MacLink.file(context, it, picture = true) }?.let { file ->
                runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                    val sample = Integer.highestOneBit(maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1) / 720).coerceAtLeast(1)
                    android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                        ?.also { PictureCache[link.imageAttachmentId] = it }
                }.getOrNull()
            }
        }
    }
    if (draft) {
        // Approved parity-g1-v2 draft anatomy: plain image area, centered Remove, one muted title/site line.
        // Keep the existing shared composer controls and photo tiles unchanged.
        Column(Modifier.fillMaxWidth().background(Pack.surface)) {
            if (link.imageAttachmentId != null) {
                Box(Modifier.fillMaxWidth().height(66.dp), contentAlignment = Alignment.Center) {
                    picture?.let { Image(it, "Link preview image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Remove link preview"; role = Role.Button }
                        .clickable(onClick = onRemove).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        BasicText("× REMOVE", Modifier.background(Pack.surface.copy(alpha = 0.94f)).padding(horizontal = 6.dp, vertical = 3.dp),
                            style = Pack.mono(Pack.size.small, Pack.foreground))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("${link.title ?: link.url} · ${link.site ?: Parts.host(link.url)}", Modifier.weight(1f),
                    style = Pack.body(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (link.imageAttachmentId == null) BasicText("× REMOVE", Modifier.heightIn(min = 44.dp)
                    .semantics { contentDescription = "Remove link preview"; role = Role.Button }.clickable(onClick = onRemove),
                    style = Pack.mono(Pack.size.small, Pack.foreground))
            }
        }
        return
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
