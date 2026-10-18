package dev.omalauncher.widgets.render

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Str
import kotlinx.coroutines.delay

/** A page that has not finished in this long has failed: the widget falls back rather than waiting on it. */
private const val LOAD_TIMEOUT_MS = 10_000L

/**
 * A `Web` node: a page from one of [Compiler.WEB_HOSTS], shown as a picture. It takes no touches and follows
 * no links, so nothing on it can lead away from what the widget asked for. It only exists on the widget's own
 * screen: the web view (a process of its own) is made when the screen opens and destroyed when it closes.
 * In a tile, in a preview picture, or for a page from anywhere else, the node is empty space.
 *
 * `onLoad` runs when the page has finished loading and `onError` when it could not be loaded or took longer
 * than [LOAD_TIMEOUT_MS]. Finished is what the web view reports: a page that loads and then says its content
 * is missing still counts as loaded.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal fun WebNode(
    p: Props,
    modifier: Modifier,
) {
    val url = (p.observe("src") as? Str)?.v
    if (!LocalWidgetScreen.current || LocalWidgetPreview.current || url == null || !Compiler.webAllowed(url)) {
        Spacer(modifier)
        return
    }
    val client =
        remember(p) {
            PageClient { ok -> p.el.handlers[if (ok) "onLoad" else "onError"]?.let { it.run(p.env, emptyList()) } }
        }
    LaunchedEffect(client, url) {
        delay(LOAD_TIMEOUT_MS)
        client.giveUp(url)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // The widget's own background shows through where the page allows it.
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                setOnTouchListener { _, _ -> true }
                webViewClient = client
            }
        },
        update = { view ->
            // A different page is a new load, even when only its query changed.
            if (view.tag != url) {
                view.tag = url
                client.begin(url)
                view.loadUrl(url)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

/** Reports each page once, as loaded or failed, and keeps the view on the page it was given. */
private class PageClient(
    private val done: (Boolean) -> Unit,
) : WebViewClient() {
    private var page: String? = null
    private var settled = true

    fun begin(url: String) {
        page = url
        settled = false
    }

    /** The page [url] is still loading after the timeout. */
    fun giveUp(url: String) {
        if (page == url) settle(false)
    }

    private fun settle(ok: Boolean) {
        if (settled) return
        settled = true
        done(ok)
    }

    // Links, and redirects to anywhere but an allowed host, go nowhere. Frames inside the page are the page's own.
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = request.isForMainFrame && !(request.isRedirect && Compiler.webAllowed(request.url.toString()))

    override fun onPageFinished(
        view: WebView,
        url: String,
    ) = settle(true)

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) settle(false)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame) settle(false)
    }
}
