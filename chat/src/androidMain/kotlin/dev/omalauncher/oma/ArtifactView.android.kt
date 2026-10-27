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

/**
 * A single declared-recipient reply-preview navigation, no general native bridge, no file/content access and no runner credential in a WebView. A new version of the same generated
 * HTML loads into the WebView already showing, rather than a new one (features/artifact-panel/PLAN.md, slice 2); the
 * page itself starts over, as any reload does.
 */
@Composable
internal actual fun ArtifactWeb(url: String?, html: String?, modifier: Modifier, onAnswer: ArtifactAnswerHandler?, onApprove: ((ArtifactCanvasRequest) -> Unit)?) {
    val answerCallback by rememberUpdatedState(onAnswer)
    val approveCallback by rememberUpdatedState(onApprove)
    val sourceHtml by rememberUpdatedState(html)
    var submitting by remember(url, html) { mutableStateOf(false) }
    val replyToken by rememberUpdatedState(remember(url, html) { java.util.UUID.randomUUID().toString() })
    var error by remember(url, html) { mutableStateOf(false) }
    var retry by remember(url) { mutableIntStateOf(0) }
    // What the WebView showing has loaded, so a recomposition with the same HTML loads nothing again.
    val loaded = remember(url, retry) { arrayOfNulls<String>(1) }
    var owned by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { owned?.apply { stopLoading(); destroy() } } }
    Box(modifier) {
        key(url, html == null, retry) {
            AndroidView(factory = { context ->
                WebView(context).apply {
                    owned?.destroy()
                    owned = this
                    clipToOutline = true
                    outlineProvider = ViewOutlineProvider.BOUNDS
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // A live page (Soma Canvas keeps Boss's sign-in in localStorage, and throws without it) needs its
                    // own storage, as in a browser; generated HTML gets none.
                    settings.domStorageEnabled = url != null
                    settings.setSupportMultipleWindows(false)
                    settings.mediaPlaybackRequiresUserGesture = true
                    // Generated HTML is a self-contained preview, with no ambient network or app access.
                    settings.blockNetworkLoads = url == null
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (false && url == null && approveCallback != null && owned === view) {
                                val prefix = "soma-approve:$replyToken:"
                                val address = request.url.toString()
                                if (address.startsWith(prefix)) ArtifactCanvasApproval.request(sourceHtml.orEmpty(), "soma-approve:" + address.removePrefix(prefix), request.isForMainFrame, request.hasGesture())?.let {
                                    approveCallback?.invoke(it)
                                    return true
                                }
                            }
                            if (url == null && answerCallback != null && owned === view) {
                                val prefix = "soma-answer:$replyToken:"
                                val address = request.url.toString()
                                if (address.startsWith(prefix)) ArtifactReply.request(sourceHtml.orEmpty(), "soma-answer:" + address.removePrefix(prefix), request.isForMainFrame, request.hasGesture())?.let {
                                    if (submitting) return true
                                    submitting = true
                                    val token = replyToken
                                    fun result(value: ArtifactReplyResult) {
                                        view.post {
                                            if (owned !== view || token != replyToken) return@post
                                            val payload = org.json.JSONObject().put("sent", value.sent).put("message", value.message).put("recipient", it.recipient).put("receipt", value.receipt).toString()
                                            view.evaluateJavascript("window.dispatchEvent(new CustomEvent('soma-answer-result',{detail:$payload}));var s=document.getElementById('soma-reply-status');if(s)s.textContent=" + org.json.JSONObject.quote(value.message) + ";", null)
                                        }
                                    }
                                    try { answerCallback?.invoke(it, ::result) }
                                    catch (_: Exception) { result(ArtifactReplyResult(false, "Delivery was not confirmed. Check the agent chat.")) }
                                    return true
                                }
                            }
                            val scheme = request.url.scheme
                            return url == null || scheme !in listOf("https", "http")
                        }
                        override fun onPageFinished(view: WebView, finishedUrl: String?) {
                            if (url == null && owned === view) {
                                val methods = mutableListOf<String>()
                                if (answerCallback != null && ArtifactReply.recipient(sourceHtml.orEmpty()) != null)
                                    methods += "answer:function(text){if(typeof text==='string'&&text.length<=4096)location.href='soma-answer:$replyToken:'+encodeURIComponent(text)}"
                                if (false && approveCallback != null && ArtifactCanvasApproval.declared(sourceHtml.orEmpty()))
                                    methods += "approve:function(value){if(value&&typeof value==='object')location.href='soma-approve:$replyToken:'+encodeURIComponent(JSON.stringify(value))}"
                                if (methods.isNotEmpty()) view.evaluateJavascript("window.soma=Object.freeze({" + methods.joinToString(",") + "});", null)
                            }
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, problem: WebResourceError) {
                            if (request.isForMainFrame) error = true
                        }
                    }
                    if (url != null) loadUrl(url)
                    else loadDataWithBaseURL("https://soma-artifact.invalid/", html.orEmpty(), "text/html", "UTF-8", null).also { loaded[0] = html.orEmpty() }
                }
            }, update = { view ->
                if (url == null && html != null && loaded[0] != html) {
                    loaded[0] = html
                    view.loadDataWithBaseURL("https://soma-artifact.invalid/", html, "text/html", "UTF-8", null)
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
