package dev.omalauncher.oma

import android.graphics.BitmapFactory
import android.view.ViewOutlineProvider
import androidx.compose.ui.draw.clipToBounds
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack

/** No native bridge, no file/content access and no runner credential in a WebView. */
@Composable
internal actual fun ArtifactWeb(url: String?, html: String?, modifier: Modifier) {
    var error by remember(url, html) { mutableStateOf(false) }
    var retry by remember(url, html) { mutableIntStateOf(0) }
    var owned by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { owned?.apply { stopLoading(); destroy() } } }
    Box(modifier) {
        key(url, html, retry) {
            AndroidView(factory = { context ->
                WebView(context).apply {
                    owned?.destroy()
                    owned = this
                    clipToOutline = true
                    outlineProvider = ViewOutlineProvider.BOUNDS
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.setSupportMultipleWindows(false)
                    settings.mediaPlaybackRequiresUserGesture = true
                    // Generated HTML is a self-contained preview, with no ambient network or app access.
                    settings.blockNetworkLoads = url == null
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme
                            return url == null || scheme !in listOf("https", "http")
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, problem: WebResourceError) {
                            if (request.isForMainFrame) error = true
                        }
                    }
                    if (url != null) loadUrl(url)
                    else loadDataWithBaseURL("https://soma-artifact.invalid/", html.orEmpty(), "text/html", "UTF-8", null)
                }
            }, modifier = Modifier.fillMaxSize().clipToBounds(), onRelease = { it.stopLoading(); it.destroy(); if (owned === it) owned = null })
        }
        if (error) Column(Modifier.fillMaxSize().padding(16.dp)) {
            BasicText("This preview could not be loaded.", style = Pack.body())
            BracketAction("try again", Pack.accent) { error = false; retry++ }
        }
    }
}

@Composable
internal actual fun ArtifactImage(bytes: ByteArray, description: String, modifier: Modifier) {
    val image = remember(bytes) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
    if (image != null) Image(image, description, modifier, contentScale = ContentScale.Fit)
    else BasicText("This image could not be decoded.", modifier.padding(16.dp), style = Pack.body())
}
