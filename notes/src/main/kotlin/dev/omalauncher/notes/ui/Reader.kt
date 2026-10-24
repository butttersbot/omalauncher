package dev.omalauncher.notes.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.notes.engine.Markdown
import dev.omalauncher.notes.engine.Notes
import dev.omalauncher.notes.engine.ReaderPage

/**
 * A note made to be read: Markdown in the reader's own page (open and sideways, a two-page book), an agent's HTML in a
 * sealed frame (no scripts, nothing fetched from the network), fitted to the screen. "Source" shows the file as it is.
 */
@Composable
fun Reader(
    id: String,
    back: () -> Unit,
    edit: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val came by Notes.came.collectAsState()
    var ticks by remember(id) { mutableStateOf(0) }
    val note = Notes[id]
    val text = remember(id, came, ticks) { Notes.text(id) }
    var source by rememberSaveable(id) { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val book = config.screenWidthDp > config.screenHeightDp && config.screenWidthDp >= 700
    val narrow = config.screenWidthDp < 600
    Column(modifier.fillMaxSize().background(Look.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("‹ Notes", Modifier.clickable(onClick = back).padding(vertical = 6.dp, horizontal = 2.dp), style = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.ink))
            Spacer(Modifier.width(10.dp))
            BasicText(
                listOfNotNull(note?.takeIf { it.agent }?.let(::project), note?.let { whenOf(it.modified, System.currentTimeMillis()) }).joinToString(" · ").uppercase(),
                Modifier.weight(1f),
                maxLines = 1,
                style = mono(Look.muted),
            )
            edit?.let {
                Tool("Edit", it)
                Spacer(Modifier.width(6.dp))
            }
            Tool(if (source) "Read" else "Source") { source = !source }
        }
        if (source) {
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                BasicText(text, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = TextStyle(fontFamily = Look.mono, fontSize = 13.sp, lineHeight = 20.sp, color = Look.ink))
            }
        } else {
            val html = note?.html == true
            val page = remember(text, book, narrow, html) { if (html) text else ReaderPage.of(text, book, narrow) }
            Sheet(page, html, Modifier.weight(1f).fillMaxWidth()) { line ->
                Notes.save(id, Markdown.toggle(Notes.text(id), line))
                ticks++
            }
        }
    }
}

/** What the reader's page may ask of the app: tick a box, copy a code block. */
private class Bridge(
    private val context: Context,
    private val tick: (Int) -> Unit,
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun tick(line: Int) {
        main.post { tick.invoke(line) }
    }

    @JavascriptInterface
    fun copy(text: String) {
        main.post { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Code", text)) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Sheet(
    page: String,
    sealed: Boolean,
    modifier: Modifier,
    tick: (Int) -> Unit,
) {
    // A WebView whose renderer died (Chromium stopped it on a fold, the Mac Pro's walk 2026-10-24) is given up and a
    // new one made, rather than taking the app down with it.
    var renderer by remember { mutableStateOf(0) }
    key(renderer) { Frame(page, sealed, modifier, tick) { renderer++ } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Frame(
    page: String,
    sealed: Boolean,
    modifier: Modifier,
    tick: (Int) -> Unit,
    gone: () -> Unit,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#FDFDFB"))
                settings.apply {
                    // Only our own page runs a script; an agent's HTML is shown, never run.
                    javaScriptEnabled = !sealed
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = sealed
                    builtInZoomControls = sealed
                    displayZoomControls = false
                    loadWithOverviewMode = sealed
                    useWideViewPort = sealed
                }
                if (!sealed) addJavascriptInterface(Bridge(context, tick), "Soma")
                webViewClient =
                    object : WebViewClient() {
                        // Links open in the browser, never in here.
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val url = request.url
                            if (url.scheme == "http" || url.scheme == "https" || url.scheme == "mailto") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            return true
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail,
                        ): Boolean {
                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                            view.destroy()
                            gone()
                            return true
                        }

                        // The book lays itself out once the page is up; asked again here in case the script's own
                        // events came before the WebView had its size.
                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            if (!sealed) view.postDelayed({ runCatching { view.evaluateJavascript("layout()", null) } }, 200)
                        }
                    }
                tag = ""
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                val y = view.scrollY
                view.loadDataWithBaseURL("file:///android_asset/", page, "text/html", "utf-8", null)
                if (!sealed) view.postDelayed({ view.scrollTo(0, y) }, 150)
            }
        },
        modifier = modifier,
    )
}
