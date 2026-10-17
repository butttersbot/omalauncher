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
 * other apps (SheepOverlay). Where the view can walk and grow (the overlay), [onStroll] and [onRoom] take the
 * page's "stroll" and "room" (CURRENT-STATE.md in ClaudeSync, "Native hooks the page can use"); without them
 * the page walks on the spot and shows no bubble.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
fun herdPage(
    context: Context,
    onOpen: (Boolean) -> Unit,
    onLoaded: () -> Unit,
    onStroll: ((dx: Float, speed: Float) -> Unit)? = null,
    onRoom: ((w: Float?, h: Float?) -> Unit)? = null,
): WebView =
    WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // The pocket's pulls, uploads and share target are the phone's (Pocket.kt): the page may show its tab.
                    // ClipInbox.kt sends the clipboard and keeps "Sent to this phone".
                    giveHerd(view, "caps", """{"pocket": true, "sendclip": true}""")
                    Pocket.attach(view)
                    ClipInbox.show(context, view)
                    onLoaded()
                }
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
                            is PetCommand.Stroll -> onStroll?.invoke(command.dx, command.speed)
                            is PetCommand.Room -> onRoom?.invoke(command.w, command.h)
                            is PetCommand.PocketGet -> Pocket.pull(context, page, command.id, command.name, command.action)
                            PetCommand.PocketPaste -> Pocket.paste(context)
                            is PetCommand.SendClip -> ClipInbox.sendClipboard(context, page, command.device)
                            is PetCommand.InboxGet -> ClipInbox.act(context, page, command.name, command.action)
                            PetCommand.Unknown -> {}
                        }
                    }
                }
            },
            "HerdAndroid",
        )
        loadUrl("file:///android_asset/herd/pet.html")
    }

/** How long a finger rests on the sheep, without moving, before it listens. Shorter than the page's own long press. */
const val TALK_HOLD_MS = 350L

/** Hands the page [json] through its `herd.<function>`. Main thread. */
fun giveHerd(
    page: WebView,
    function: String,
    json: String,
) = page.evaluateJavascript("window.herd && herd.$function && herd.$function($json)", null)

/**
 * Holds the sheep's page and lets a finger drag it (Boss, 2026-10-15: "I need to be able to drag that thing
 * around"): a touch that travels past the touch slop is a drag, told to [onDrag] in screen pixels and ended with
 * [onDrop]; one that doesn't is a tap, and goes to the page as before.
 */
open class SheepFrame(
    context: Context,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDrop: () -> Unit,
    private val raw: Boolean = false,
    private val onHeld: (Boolean) -> Unit = {},
    private val onTalk: ((Boolean) -> Unit)? = null,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var onPet = false

    /**
     * Held still on the sheep for [TALK_HOLD_MS]: [onTalk] hears true, and false when the finger lifts (Boss, 2026-10-17:
     * talk to the agent while playing, without leaving the game). A move before then is still a drag.
     */
    private var talking = false
    private val talkTimer = Handler(Looper.getMainLooper())
    private val startTalk = Runnable {
        if (dragging || !onPet) return@Runnable
        talking = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        // The page had the touch until now: it is told the touch is over, so a hold isn't also its tap or long press.
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { cancel ->
            for (i in 0 until childCount) getChildAt(i).dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        onTalk?.invoke(true)
    }
    private val at = IntArray(2)

    /** The page stands the sheep in the bottom-left corner instead of the bottom-right (its `herd.side("left")`). */
    var sheepLeft = false

    /**
     * Whether ([x], [y]) is on the sheep: the page draws it 120 dp square, 6 dp in from the bottom corner (#pet in
     * pet.html). Only a touch that starts there drags; the panel's lists take the rest and scroll (Boss, 2026-10-16:
     * "I can't scroll in the Look tab … keep moving the pet to just, you know, on the pet itself").
     */
    private fun onSheep(
        x: Float,
        y: Float,
    ): Boolean {
        val d = resources.displayMetrics.density
        val reach = 132 * d
        return y >= height - 128 * d && if (sheepLeft) x <= reach else x >= width - reach
    }

    // Where the finger is on the screen. On home not rawX: home hands its views made-up events whose raw position
    // is not the screen's. In a window of its own ([raw]) rawX it is: there the window moves a beat after each
    // step, so its place on the screen lags the finger and the drag would feed on itself and run off the screen.
    private fun screenX(e: MotionEvent): Float {
        if (raw) return e.rawX
        getLocationOnScreen(at)
        return at[0] + e.x
    }

    private fun screenY(e: MotionEvent): Float {
        if (raw) return e.rawY
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
                talking = false
                onPet = onSheep(e.x, e.y)
                if (onPet && onTalk != null) talkTimer.postDelayed(startTalk, TALK_HOLD_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                talkTimer.removeCallbacks(startTalk)
                // A finger lifted without moving since it began to talk comes here and never reaches onTouchEvent.
                if (talking) {
                    talking = false
                    onTalk?.invoke(false)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (onPet && !dragging && !talking && hypot(screenX(e) - downX, screenY(e) - downY) > slop) {
                talkTimer.removeCallbacks(startTalk)
                dragging = true
                // Home's page swipe doesn't take a drag of the sheep.
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = screenX(e); lastY = screenY(e)
                onHeld(true)
            }
        }
        return dragging || talking
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (talking) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                talking = false
                onTalk?.invoke(false)
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                onDrag(screenX(e) - lastX, screenY(e) - lastY)
                lastX = screenX(e); lastY = screenY(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onHeld(false)
                onDrop()
            }
        }
        return true
    }
}
