package dev.omalauncher.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.herd.Herd
import dev.omalauncher.herd.PetCommand
import dev.omalauncher.herd.herdUnreachable
import dev.omalauncher.herd.petCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** How often the sheep asks while it sits on home, and while its panel is open. */
private const val HERD_EVERY_MS = 15_000L
private const val HERD_OPEN_EVERY_MS = 4_000L

/**
 * The herd's sheep in home's corner: the same page the Mac's desktop pet draws (assets/herd/pet.html, a copy of
 * ~/ClaudeSync/projects/herd/pet/web/pet.html), fed what the reporters say. A tap opens its panel of every agent on
 * every machine; back closes it. It asks only while home has the window.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun HerdPet(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val page = remember { arrayOfNulls<WebView>(1) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val screen = LocalConfiguration.current

    fun give(function: String, json: String) {
        page[0]?.evaluateJavascript("window.herd && herd.$function($json)", null)
    }

    BackHandler(enabled = open) { page[0]?.evaluateJavascript("herd.tap()", null) }

    LaunchedEffect(focused, loaded, open) {
        while (focused && loaded) {
            val json = withContext(Dispatchers.IO) { Herd.status() }
            give("update", json ?: herdUnreachable())
            delay(if (open) HERD_OPEN_EVERY_MS else HERD_EVERY_MS)
        }
    }

    // Closed it is just the sheep; open it is the panel above it, as wide as a phone's screen allows.
    val width = if (open) minOf(400, screen.screenWidthDp).dp else 150.dp
    val height = if (open) minOf(680, screen.screenHeightDp * 8 / 10).dp else 150.dp
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            loaded = true
                        }
                    }
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        // Called on a binder thread; everything it does happens on the main one.
                        @JavascriptInterface
                        fun post(json: String) {
                            main.post {
                                when (val command = petCommand(json)) {
                                    PetCommand.Expand -> open = true
                                    PetCommand.Collapse -> open = false
                                    is PetCommand.Show ->
                                        thread(name = "oma-herd-show", isDaemon = true) {
                                            val detail = Herd.session(command.id)
                                            main.post { give("detail", detail ?: """{"error": "That session didn't answer."}""") }
                                        }
                                    is PetCommand.Copy -> copy(context, command.text)
                                    PetCommand.Unknown -> {}
                                }
                            }
                        }
                    },
                    "HerdAndroid",
                )
                loadUrl("file:///android_asset/herd/pet.html")
                page[0] = this
            }
        },
        onRelease = {
            if (page[0] === it) page[0] = null
            it.destroy()
        },
        modifier = modifier.size(width, height),
    )
}

private fun copy(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("herd", text))
}
