package dev.omalauncher.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.omalauncher.herd.PocketShotActivity
import dev.omalauncher.ui.Pack

/** Samsung's screenshot tool, and the bar of buttons it shows after each screenshot. */
const val SMART_CAPTURE = "com.samsung.android.app.smartcapture"
const val SMART_CAPTURE_BAR = "$SMART_CAPTURE:id/toolbar_btn_container"

/** Where the button goes for a toolbar at [bar]: centred over it, [gap] above, never off the top. */
fun shotButtonSpot(
    barLeft: Int,
    barTop: Int,
    barRight: Int,
    width: Int,
    height: Int,
    gap: Int,
): Pair<Int, Int> = ((barLeft + barRight) / 2 - width / 2).coerceAtLeast(0) to (barTop - gap - height).coerceAtLeast(0)

/**
 * "Add to pocket" over Samsung's screenshot toolbar (Boss, 2026-10-16: "put our own button in that little toolbar …
 * after every screenshot", into the pocket). Android lets no app into that toolbar, so this is a window of the
 * accessibility service's own, just above it, that comes and goes with it. A tap opens PocketShotActivity, which puts
 * the newest screenshot in the pocket. Main thread only.
 */
class ShotButton(private val service: AccessibilityService) {
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val wm get() = service.getSystemService(WindowManager::class.java)
    private val density get() = service.resources.displayMetrics.density

    val shown get() = view != null

    /** The toolbar's bounds now, or null when it isn't up. */
    fun toolbar(windows: List<android.view.accessibility.AccessibilityWindowInfo>): Rect? {
        for (window in windows) {
            val root: AccessibilityNodeInfo = window.root ?: continue
            if (root.packageName?.toString() != SMART_CAPTURE) continue
            val bar = root.findAccessibilityNodeInfosByViewId(SMART_CAPTURE_BAR).firstOrNull() ?: continue
            return Rect().also { bar.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
        }
        return null
    }

    /** Up over [bar], or moved there. */
    fun show(bar: Rect) {
        val gap = (10 * density).toInt()
        val existing = view
        val label = existing ?: TextView(service).apply {
            text = "＋ Add to pocket"
            contentDescription = "Add this screenshot to the pocket"
            val scrap = Pack.look.pack.scrap.surface
            setTextColor(scrap.ink.argb or 0xff000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(scrap.fill.argb or 0xff000000.toInt())
                setStroke((1.5f * density).toInt().coerceAtLeast(1), scrap.ink.argb or 0xff000000.toInt())
            }
            elevation = 6 * density
            setOnClickListener {
                hide()
                runCatching {
                    service.startActivity(Intent(service, PocketShotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }
        }
        label.measure(0, 0)
        val (x, y) = shotButtonSpot(bar.left, bar.top, bar.right, label.measuredWidth, label.measuredHeight, gap)
        val p = params ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Oma add to pocket"
        }
        if (p.x == x && p.y == y && existing != null) return
        p.x = x
        p.y = y
        if (existing == null) {
            if (runCatching { wm.addView(label, p) }.isFailure) return
            view = label
            params = p
        } else {
            runCatching { wm.updateViewLayout(existing, p) }
        }
    }

    fun hide() {
        val v = view ?: return
        runCatching { if (v.isAttachedToWindow) wm.removeViewImmediate(v) }
        view = null
        params = null
    }
}
