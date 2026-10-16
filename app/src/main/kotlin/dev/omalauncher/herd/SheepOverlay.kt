package dev.omalauncher.herd

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import kotlin.concurrent.thread

/** How often the sheep asks the herd while it floats, and while its panel is open. */
private const val FLOAT_EVERY_MS = 15_000L
private const val FLOAT_OPEN_EVERY_MS = 4_000L

/**
 * The sheep over everything (Boss, 2026-10-15: "have the pet always on top … even when I'm in an app, outside of
 * Oma"): a small window of the accessibility service's own, which needs no permission, as the ring's does. Unlike
 * the ring's it takes touches, but only on itself: a tap opens its panel, a drag moves it, and a touch outside an
 * open panel closes it. [PhoneService] puts it up and takes it down; main thread only.
 */
class SheepOverlay(private val service: AccessibilityService) {
    private var frame: SheepFrame? = null
    private var page: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var open = false
    private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = service.getSystemService(WindowManager::class.java)
    private val density get() = service.resources.displayMetrics.density

    val shown get() = frame != null

    private val ask =
        object : Runnable {
            override fun run() {
                val p = page ?: return
                if (loaded) {
                    thread(name = "oma-herd-float", isDaemon = true) {
                        val json = Herd.status() ?: herdUnreachable()
                        main.post { if (page === p) giveHerd(p, "update", json) }
                    }
                }
                main.postDelayed(this, if (open) FLOAT_OPEN_EVERY_MS else FLOAT_EVERY_MS)
            }
        }

    private fun sheepPx() = (150 * density).toInt()

    private fun screen() = service.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** The window at the sheep's size where he left it, or the panel's, kept on the screen. */
    private fun place(p: WindowManager.LayoutParams) {
        val (sw, sh) = screen()
        val (w, h) =
            if (open) minOf((400 * density).toInt(), sw) to minOf((680 * density).toInt(), sh * 8 / 10)
            else sheepPx() to sheepPx()
        val (x, y) = HerdSheep.spot(service) ?: ((sw - sheepPx() - (8 * density).toInt()) to (sh - sheepPx() - (120 * density).toInt()))
        p.width = w
        p.height = h
        p.x = x.coerceIn(0, maxOf(0, sw - w))
        p.y = y.coerceIn(0, maxOf(0, sh - h))
    }

    fun show() {
        if (frame != null) return
        val p =
            WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "Oma sheep"
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            }
        open = false
        loaded = false
        place(p)
        lateinit var holder: SheepFrame
        holder =
            object : SheepFrame(service, onDrag = { dx, dy ->
                p.x = (p.x + dx).toInt()
                p.y = (p.y + dy).toInt()
                runCatching { wm.updateViewLayout(holder, p) }
            }, onDrop = {
                // The sheep's own corner is what is kept, open or closed.
                if (!open) HerdSheep.keep(service, p.x, p.y)
            }) {
                override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        if (open) page?.evaluateJavascript("herd.tap()", null)
                        return true
                    }
                    return super.dispatchTouchEvent(e)
                }
            }
        val web =
            herdPage(service, onOpen = { now ->
                open = now
                place(p)
                runCatching { wm.updateViewLayout(holder, p) }
                main.removeCallbacks(ask)
                main.post(ask)
            }, onLoaded = {
                loaded = true
                main.removeCallbacks(ask)
                main.post(ask)
            })
        holder.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (runCatching { wm.addView(holder, p) }.isFailure) {
            web.destroy()
            return
        }
        frame = holder
        page = web
        params = p
        HerdSheep.floating = true
    }

    fun hide() {
        val f = frame ?: return
        main.removeCallbacks(ask)
        runCatching { if (f.isAttachedToWindow) wm.removeViewImmediate(f) }
        page?.destroy()
        frame = null
        page = null
        params = null
        open = false
        HerdSheep.floating = false
    }

    /** Folded, unfolded or turned: put back on the new screen. */
    fun relayout() {
        val f = frame ?: return
        val p = params ?: return
        place(p)
        runCatching { wm.updateViewLayout(f, p) }
    }
}
