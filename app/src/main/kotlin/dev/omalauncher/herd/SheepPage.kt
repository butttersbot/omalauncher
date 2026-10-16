package dev.omalauncher.herd

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * The sheep's page (assets/herd/pet.html) in a WebView, wired to the phone: [onOpen] hears it ask to open its
 * panel or close it, [onLoaded] once it is ready for [giveHerd]. One page, used on home (ui/HerdPet.kt) and over
 * other apps (SheepOverlay).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
fun herdPage(
    context: Context,
    onOpen: (Boolean) -> Unit,
    onLoaded: () -> Unit,
): WebView =
    WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = onLoaded()
            }
        val main = Handler(Looper.getMainLooper())
        val page = this
        addJavascriptInterface(
            object {
                // Called on a binder thread; everything it does happens on the main one.
                @JavascriptInterface
                fun post(json: String) {
                    main.post {
                        when (val command = petCommand(json)) {
                            PetCommand.Expand -> onOpen(true)
                            PetCommand.Collapse -> onOpen(false)
                            is PetCommand.Show ->
                                thread(name = "oma-herd-show", isDaemon = true) {
                                    val detail = Herd.session(command.id)
                                    main.post { giveHerd(page, "detail", detail ?: """{"error": "That session didn't answer."}""") }
                                }
                            is PetCommand.Copy -> {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("herd", command.text))
                            }
                            PetCommand.Unknown -> {}
                        }
                    }
                }
            },
            "HerdAndroid",
        )
        loadUrl("file:///android_asset/herd/pet.html")
    }

/** Hands the page [json] through its `herd.<function>`. Main thread. */
fun giveHerd(
    page: WebView,
    function: String,
    json: String,
) = page.evaluateJavascript("window.herd && herd.$function($json)", null)

/**
 * Holds the sheep's page and lets a finger drag it (Boss, 2026-10-15: "I need to be able to drag that thing
 * around"): a touch that travels past the touch slop is a drag, told to [onDrag] in screen pixels and ended with
 * [onDrop]; one that doesn't is a tap, and goes to the page as before.
 */
open class SheepFrame(
    context: Context,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDrop: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val at = IntArray(2)

    // Where the finger is on the screen. Not rawX: home hands its views made-up events whose raw position is not
    // the screen's, and the frame moves under the finger as it is dragged.
    private fun screenX(e: MotionEvent): Float {
        getLocationOnScreen(at)
        return at[0] + e.x
    }

    private fun screenY(e: MotionEvent): Float {
        getLocationOnScreen(at)
        return at[1] + e.y
    }

    /**
     * The page asks for its touches as soon as a finger lands, which would hide every move from
     * [onInterceptTouchEvent]; the ask is passed up to home's own gestures, but this frame still watches for a drag.
     */
    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = screenX(e); downY = screenY(e); lastX = screenX(e); lastY = screenY(e)
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && hypot(screenX(e) - downX, screenY(e) - downY) > slop) {
                dragging = true
                // Home's page swipe doesn't take a drag of the sheep.
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = screenX(e); lastY = screenY(e)
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                onDrag(screenX(e) - lastX, screenY(e) - lastY)
                lastX = screenX(e); lastY = screenY(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onDrop()
            }
        }
        return true
    }
}
