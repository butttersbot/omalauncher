package dev.omalauncher.oma

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.callback.CefCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefJSDialogCallback
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.awt.Component

/**
 * HTML artifacts in the side panel, in Chromium (WebEngine), with the Fold's WebView rules: generated HTML is served at one fake
 * origin, reaches nothing (every request but its own address and inline data is cancelled), navigates nowhere, opens no popups,
 * dialogs or context menu, keeps no storage; a web page stays on http(s). The only way out is the declared-recipient reply,
 * which only fills the native Send bar. Until the engine is installed or when it can't start, the source is shown with why.
 */
@Composable
internal actual fun ArtifactWeb(url: String?, html: String?, modifier: Modifier, onAnswer: ArtifactAnswerHandler?, onApprove: ((ArtifactCanvasRequest) -> Unit)?, canvasOrigin: String?) {
    val engine = WebEngine.state
    LaunchedEffect(engine) { if (engine is WebEngineState.NotInstalled) WebEngine.start() }
    if (engine is WebEngineState.Ready) return ChromiumArtifact(engine.app, url, html, modifier, onAnswer)
    Column(modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (engine) {
                WebEngineState.NotInstalled -> {
                    BasicText("HTML engine not installed · ", style = Pack.body(Pack.size.small, Pack.muted))
                    WebEngine.onInstall?.let { install -> BracketAction("install", Pack.accent) { install() } }
                }
                WebEngineState.Starting -> BasicText("Starting the HTML engine…", style = Pack.body(Pack.size.small, Pack.muted))
                is WebEngineState.Failed -> BasicText("The HTML engine didn't start: ${engine.reason}. Source is shown here.", style = Pack.body(Pack.size.small, Pack.muted))
                is WebEngineState.Ready -> Unit
            }
        }
        ArtifactText(html ?: url.orEmpty())
    }
}

@Composable
private fun ChromiumArtifact(app: CefApp, url: String?, html: String?, modifier: Modifier, onAnswer: ArtifactAnswerHandler?) {
    val answerCallback by rememberUpdatedState(onAnswer)
    val sourceHtml by rememberUpdatedState(html)
    var submitting by remember(url, html) { mutableStateOf(false) }
    var pending by remember(url, html) { mutableStateOf<ArtifactReplyRequest?>(null) }
    var deliveryStatus by remember(url, html) { mutableStateOf<String?>(null) }
    val replyToken = remember(url, html) { java.util.UUID.randomUUID().toString() }
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // One browser per address (or per generated page): a new version of the same HTML loads into the one showing.
                key(url, html == null) {
                    val page = remember { ArtifactBrowser(app, url, html) }
                    page.replyToken = replyToken
                    page.answers = if (url == null && answerCallback != null && ArtifactReply.recipient(sourceHtml.orEmpty()) != null) { address, mainFrame ->
                        if (!submitting) {
                            pending = ArtifactReply.pending(sourceHtml.orEmpty(), address, mainFrame)
                            deliveryStatus = null
                        }
                    } else null
                    LaunchedEffect(html) { if (url == null && html != null) page.show(html) }
                    DisposableEffect(page) { onDispose { page.close() } }
                    SwingPanel(factory = { page.component }, modifier = Modifier.fillMaxSize())
                }
            }
            if (url == null && answerCallback != null && ArtifactReply.recipient(html.orEmpty()) != null) {
                val displayed = pending
                Column(Modifier.fillMaxWidth().background(Pack.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BasicText("To: " + (displayed?.recipient ?: ArtifactReply.recipient(html.orEmpty())), style = Pack.mono(Pack.size.small, Pack.muted))
                    if (displayed != null && !submitting) key(displayed) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            BracketAction(ArtifactReply.sendLabel(displayed), Pack.accent) {
                                // A draft changed since this label was drawn cannot be sent under an old label.
                                if (submitting || pending != displayed) return@BracketAction
                                val send = pending ?: return@BracketAction
                                val page = ArtifactBrowser.showing[replyToken] ?: return@BracketAction
                                submitting = true
                                deliveryStatus = "Sending…"
                                page.run("window.dispatchEvent(new Event('soma-answer-sending'));")
                                fun result(value: ArtifactReplyResult) {
                                    if (ArtifactBrowser.showing[replyToken] !== page) return
                                    deliveryStatus = value.message
                                    val payload = org.json.JSONObject().put("sent", value.sent).put("message", value.message).put("recipient", send.recipient).put("receipt", value.receipt).toString()
                                    page.run("window.dispatchEvent(new CustomEvent('soma-answer-result',{detail:$payload}));var s=document.getElementById('soma-reply-status');if(s)s.textContent=" + org.json.JSONObject.quote(value.message) + ";")
                                }
                                try { answerCallback?.invoke(send, ::result) }
                                catch (_: Exception) { result(ArtifactReplyResult(false, "Delivery was not confirmed. Check the agent chat.")) }
                            }
                        }
                    } else if (!submitting) BasicText("[ Send answers ]", style = Pack.body(color = Pack.muted))
                    BasicText(deliveryStatus ?: if (displayed == null) "Choose answers in the mockup." else "Only this native Send button delivers these answers.", style = Pack.body(Pack.size.small, Pack.muted))
                }
            }
        }
    }
}

/**
 * One Chromium browser for the panel, with its handlers. [url] null: generated HTML ([first], then whatever [show] gives), served
 * at [ARTIFACT_ORIGIN]. The browser is made at that address with the page already in hand: a load asked for before Chromium's
 * window exists is dropped (MG1 walk, 2026-10-29: a blank panel).
 */
private class ArtifactBrowser(app: CefApp, private val url: String?, first: String?) {
    private val html get() = url == null
    @Volatile private var page: String = first.orEmpty()
    @Volatile var replyToken: String = ""
        set(value) { showing.remove(field); field = value; showing[value] = this }
    /** Takes a reply navigation (address, main frame) into the native draft; null when the page declares no recipient. */
    @Volatile var answers: ((String, Boolean) -> Unit)? = null

    private val client = app.createClient()
    private val zoom = WebEngineFlags.zoomLevel(System.getProperty("os.name"), WebEngineFlags.scale())
    private val browser: CefBrowser
    val component: Component

    init {
        val resources = object : CefResourceRequestHandlerAdapter() {
            // true cancels: generated HTML reaches only itself and inline data.
            override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): Boolean = !ArtifactPolicy.request(html, request.url)
            override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler? =
                if (html && request.url == ARTIFACT_ORIGIN) PageResource(isolatedArtifactHtml(page).toByteArray()) else null
        }
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame, request: CefRequest, user_gesture: Boolean, is_redirect: Boolean): Boolean {
                val address = request.url
                return when (ArtifactPolicy.navigation(html, address, frame.isMain)) {
                    ArtifactPolicy.Go.Allow -> false
                    ArtifactPolicy.Go.Block -> true
                    ArtifactPolicy.Go.Answer -> {
                        val prefix = "soma-answer:$replyToken:"
                        // Page data can only update the native draft, never deliver or sign.
                        if (address.startsWith(prefix)) answers?.invoke("soma-answer:" + address.removePrefix(prefix), frame.isMain)
                        true
                    }
                }
            }
            override fun getResourceRequestHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?, disableDefaultHandling: BoolRef?): CefResourceRequestHandler = resources
        })
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, target_url: String?, target_frame_name: String?): Boolean = true
            // Soma's scale as the page's zoom, from the first paint (WebEngineFlags.zoomLevel).
            override fun onAfterCreated(browser: CefBrowser) { if (zoom != 0.0) browser.zoomLevel = zoom }
        })
        client.addJSDialogHandler(object : CefJSDialogHandlerAdapter() {
            override fun onJSDialog(browser: CefBrowser?, origin_url: String?, dialog_type: CefJSDialogHandler.JSDialogType?, message_text: String?, default_prompt_text: String?, callback: CefJSDialogCallback?, suppress_message: BoolRef): Boolean {
                suppress_message.set(true)
                return false
            }
        })
        client.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(browser: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel) { model.clear() }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                WebEngine.drew()
                if (zoom != 0.0 && browser.zoomLevel != zoom) browser.zoomLevel = zoom
                // The same reply bridge as the Fold: a navigation the request handler turns into a native draft, nothing more.
                if (html && answers != null)
                    run("window.soma=Object.freeze({answer:function(value){var text=value===null?'':typeof value==='string'?value:'soma-json:'+JSON.stringify(value);location.href='soma-answer:$replyToken:'+encodeURIComponent(text.length<=4096?text:'')}});window.dispatchEvent(new Event('soma-ready'));")
            }
        })
        // Generated HTML gets a context of its own in memory: no storage, cookies or cache shared with anything.
        val context = if (html) CefRequestContext.createContext(null) else CefRequestContext.getGlobalContext()
        browser = client.createBrowser(url ?: ARTIFACT_ORIGIN, false, false, context)
        component = browser.uiComponent
    }

    /** A new version of the page, loaded into the browser already showing (the first one it was made with). */
    fun show(html: String) {
        if (html == page) return
        page = html
        browser.loadURL(ARTIFACT_ORIGIN)
    }

    fun run(script: String) = browser.executeJavaScript(script, ARTIFACT_ORIGIN, 0)

    fun close() {
        showing.remove(replyToken)
        browser.close(true)
        client.dispose()
    }

    companion object {
        /** The browser drawing each reply token's page, so a late delivery result reaches only the page that asked. */
        val showing = java.util.concurrent.ConcurrentHashMap<String, ArtifactBrowser>()
    }
}

/** The generated page, as Chromium reads it for [ARTIFACT_ORIGIN]. */
private class PageResource(private val bytes: ByteArray) : CefResourceHandlerAdapter() {
    private var offset = 0
    override fun processRequest(request: CefRequest?, callback: CefCallback): Boolean { callback.Continue(); return true }
    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        response.setMimeType("text/html")
        response.setStatus(200)
        response.setHeaderByName("Content-Security-Policy", "frame-src 'none'; object-src 'none'", true)
        response.setHeaderByName("Cache-Control", "no-store", true)
        responseLength.set(bytes.size)
    }
    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback?): Boolean {
        if (offset >= bytes.size) { bytesRead.set(0); return false }
        val n = minOf(bytesToRead, bytes.size - offset)
        System.arraycopy(bytes, offset, dataOut, 0, n)
        offset += n
        bytesRead.set(n)
        return true
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
