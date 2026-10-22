package dev.omalauncher.news

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Soma News, the screen (Boss, 2026-10-21, mock-up approved): the stream set as a magazine, its own layout on the
 * cover and on the open Fold, and the reader: a page that scrolls on the cover and the open Fold upright, and pages
 * that turn, laid out a line at a time by Pretext round the photos, on the open Fold held sideways.
 *
 * The page is the approved mock-up's, from assets/news/, served from a private https origin so its modules load; the
 * stories come from [NewsStore]'s file through [Bridge]. Pictures load from the web as the stream shows them.
 */
class NewsActivity : Activity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.setBackgroundColor(0xFFFBFAF6.toInt())
        web.addJavascriptInterface(Bridge(), "SomaNews")
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = asset(request.url)

                // Links in a story open in the browser, never in the reader.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (request.url.host == HOST) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return true
                }
            }
        setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.loadUrl("https://$HOST/assets/news/index.html")
        NewsJob.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Back after an hour or more: what came in since.
        if (System.currentTimeMillis() / 1000 - updated() > 3600) refresh()
    }

    @Deprecated("Back closes the reader before the screen")
    override fun onBackPressed() {
        web.evaluateJavascript("window.somaBack ? window.somaBack() : false") { handled ->
            if (handled != "true") finish()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun updated(): Long = runCatching { JSONObject(NewsStore.cached(this)).optLong("updated") }.getOrDefault(0L)

    private fun refresh() {
        thread(name = "news-refresh") {
            val r = NewsStore.refresh(applicationContext)
            val msg = r.exceptionOrNull()?.message
            main.post {
                if (isDestroyed) return@post
                web.evaluateJavascript("window.somaRefreshed && window.somaRefreshed(${JSONObject.quote(msg ?: "")})", null)
            }
        }
    }

    /** The page's own files, from the app's assets, at https://[HOST]/assets/…; anything else goes to the web. */
    private fun asset(url: Uri): WebResourceResponse? {
        if (url.host != HOST) return null
        val path = url.path?.removePrefix("/assets/") ?: return null
        if (path.contains("..")) return null
        return runCatching { WebResourceResponse(mime(path), if (path.endsWith(".ttf") || path.endsWith(".jpg")) null else "utf-8", assets.open(path)) }.getOrNull()
    }

    private fun mime(path: String) =
        when (path.substringAfterLast('.')) {
            "html" -> "text/html"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "ttf" -> "font/ttf"
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

    /** What the page asks of the app. */
    inner class Bridge {
        /** The stream as the file holds it. */
        @JavascriptInterface fun stream(): String = NewsStore.cached(this@NewsActivity)

        /** Fetches what's new; the page hears back through window.somaRefreshed(error). */
        @JavascriptInterface fun refresh() = main.post { this@NewsActivity.refresh() }

        /** The story's own page, in the browser. */
        @JavascriptInterface fun original(url: String) =
            main.post { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

        /** Shares the story's link (to Notes, Tailshare, anything that takes a link). */
        @JavascriptInterface fun share(
            title: String,
            url: String,
        ) = main.post {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, "$title\n$url"), title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        /**
         * The story's whole page, fetched here (the page itself can't read other sites); it hears back through
         * window.somaPage(link, html), html "" when the page couldn't be had.
         */
        @JavascriptInterface fun page(link: String) {
            thread(name = "news-page", isDaemon = true) {
                val html = NewsStore.page(link).orEmpty()
                main.post { if (!isDestroyed) web.evaluateJavascript("window.somaPage && window.somaPage(${JSONObject.quote(link)}, ${JSONObject.quote(html)})", null) }
            }
        }

        /** The whole article taken from [link]'s page before, or "". */
        @JavascriptInterface fun full(link: String): String = NewsStore.full(this@NewsActivity, link)

        /** Keeps the whole article taken from [link]'s page, so it opens at once, and offline, next time. */
        @JavascriptInterface fun keep(
            link: String,
            html: String,
        ) {
            thread(name = "news-keep", isDaemon = true) { NewsStore.keepFull(this@NewsActivity, link, html) }
        }

        @JavascriptInterface fun toast(text: String) = main.post { Toast.makeText(this@NewsActivity, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /** The page's private origin: nothing is served from the network under it. */
        const val HOST = "news.soma.local"

        fun open(context: Context) = context.startActivity(Intent(context, NewsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
