package dev.omalauncher.herd

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
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

    // The sheep's own square, pixels from the screen's top left; the window is laid around it.
    private var sx = 0f
    private var sy = 0f

    // Room the page asked for around the sheep for its speech bubble (pixels), or none.
    private var room: Pair<Int, Int>? = null
    private var side = "right"
    private var walk: Runnable? = null

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

    /**
     * The status bar's and the gesture or navigation bar's heights, which the open panel keeps clear of: from the
     * window's own insets where Android gives them, else from the system's dimensions for the two bars.
     */
    private fun bars(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                return insets.top to insets.bottom
            }
        }
        fun dimen(name: String) = service.resources.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(service.resources::getDimensionPixelSize) ?: 0
        return dimen("status_bar_height") to dimen("navigation_bar_height")
    }

    /** The sheep kept on the screen. */
    private fun hold() {
        val (sw, sh) = screen()
        sx = sx.coerceIn(0f, maxOf(0, sw - sheepPx()).toFloat())
        sy = sy.coerceIn(0f, maxOf(0, sh - sheepPx()).toFloat())
    }

    /** The window: the panel's size at the sheep's corner when open, else the sheep and any room it asked for. */
    private fun place(p: WindowManager.LayoutParams) {
        val (sw, sh) = screen()
        hold()
        if (open) {
            val (top, bottom) = bars()
            val win = sheepOpenWindow(side, (400 * density).toInt(), minOf((680 * density).toInt(), sh * 8 / 10), sw, sh, top, bottom, (8 * density).toInt())
            p.x = win.x
            p.y = win.y
            p.width = win.w
            p.height = win.h
            return
        }
        val (w, h) = room ?: (0 to 0)
        val win = sheepWindow(sx.toInt(), sy.toInt(), sheepPx(), w, h, sw, sh)
        p.x = win.x
        p.y = win.y
        p.width = win.w
        p.height = win.h
        if (win.side != side) {
            side = win.side
            frame?.sheepLeft = side == "left"
            page?.let { giveHerd(it, "side", "\"$side\"") }
        }
    }

    private fun update() {
        val f = frame ?: return
        val p = params ?: return
        place(p)
        runCatching { wm.updateViewLayout(f, p) }
    }

    /** Walks the sheep [dx] dp along the screen at [speed] dp a second, then tells the page how far it got. */
    private fun stroll(
        dx: Float,
        speed: Float,
    ) {
        halt()
        if (open) return
        val (sw, _) = screen()
        val start = sx
        val target = (sx + dx * density).coerceIn(0f, maxOf(0, sw - sheepPx()).toFloat())
        val step = speed * density / 60f * (if (target < sx) -1 else 1)
        val tick =
            object : Runnable {
                override fun run() {
                    sx = if (kotlin.math.abs(target - sx) <= kotlin.math.abs(step)) target else sx + step
                    update()
                    if (sx == target) {
                        walk = null
                        HerdSheep.keep(service, sx.toInt(), sy.toInt())
                        page?.let { giveHerd(it, "arrived", """{"moved": ${(sx - start) / density}}""") }
                    } else {
                        main.postDelayed(this, 16)
                    }
                }
            }
        walk = tick
        main.post(tick)
    }

    /** Stops a walk in progress, telling the page. */
    private fun halt() {
        val w = walk ?: return
        main.removeCallbacks(w)
        walk = null
        HerdSheep.keep(service, sx.toInt(), sy.toInt())
        page?.let { giveHerd(it, "arrived", """{"moved": 0}""") }
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
        room = null
        side = "right"
        val (sw, sh) = screen()
        val spot = HerdSheep.spot(service) ?: ((sw - sheepPx() - (8 * density).toInt()) to (sh - sheepPx() - (120 * density).toInt()))
        sx = spot.first.toFloat()
        sy = spot.second.toFloat()
        place(p)
        lateinit var holder: SheepFrame
        holder =
            object : SheepFrame(service, onDrag = { dx, dy ->
                sx += dx
                sy += dy
                update()
            }, onDrop = {
                HerdSheep.keep(service, sx.toInt(), sy.toInt())
            }, raw = true, onHeld = { held ->
                if (held) halt()
                page?.let { giveHerd(it, "held", if (held) "true" else "false") }
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
                if (now) halt()
                open = now
                room = null
                update()
                main.removeCallbacks(ask)
                main.post(ask)
            }, onLoaded = {
                loaded = true
                page?.let {
                    giveHerd(it, "caps", """{"stroll": true, "room": true}""")
                    giveHerd(it, "side", "\"$side\"")
                }
                main.removeCallbacks(ask)
                main.post(ask)
            }, onStroll = { dx, speed -> stroll(dx, speed) }, onRoom = { w, h ->
                if (!open) {
                    room = if (w != null && h != null) (w * density).toInt() to (h * density).toInt() else null
                    update()
                }
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
        walk?.let { main.removeCallbacks(it) }
        walk = null
        runCatching { if (f.isAttachedToWindow) wm.removeViewImmediate(f) }
        page?.destroy()
        frame = null
        page = null
        params = null
        open = false
        room = null
        HerdSheep.floating = false
    }

    /** Folded, unfolded or turned: put back on the new screen. */
    fun relayout() = update()
}
