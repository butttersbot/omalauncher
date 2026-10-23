package dev.omalauncher.record

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.omalauncher.phone.PhoneService

/**
 * What the screen recording shows on the screen itself (Boss, 2026-10-22: "I couldn't see any timer and I couldn't see
 * when it was recording"): the countdown, large in the middle, gone before the first frame. A window of the
 * phone-control service's own, so it stays over every app. [recording]'s pill (a red dot, the time, Stop) is kept but
 * not shown: it is in the video, as Android keeps no app's window out of a capture, and Boss wants the stop only at
 * the end, from the shade. Main thread only.
 */
object RecordPill {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var time: TextView? = null
    private var startedAt = 0L
    private val tick =
        object : Runnable {
            override fun run() {
                val t = time ?: return
                t.text = clock((System.currentTimeMillis() - startedAt) / 1000)
                main.postDelayed(this, 500)
            }
        }

    /** "0:07", "12:30". */
    fun clock(seconds: Long): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"

    /** The countdown's [left] seconds, large in the middle of the screen. */
    fun countdown(left: Int) {
        val s = PhoneService.instance ?: return
        hide()
        val d = s.resources.displayMetrics.density
        val n = TextView(s).apply {
            text = left.toString()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val size = (128 * d).toInt()
            width = size
            height = size
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            contentDescription = "Recording starts in $left"
        }
        add(n, Gravity.CENTER, 0, touchable = false)
    }

    /** Recording since [since]: the pill at the top, its time running, [onStop] when Stop is tapped. */
    fun recording(since: Long, onStop: () -> Unit) {
        val s = PhoneService.instance ?: return
        hide()
        startedAt = since
        val d = s.resources.displayMetrics.density
        val dot = View(s).apply {
            val size = (10 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * d).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE5383B.toInt())
            }
        }
        val t = TextView(s).apply {
            text = clock(0)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
        }
        val stop = TextView(s).apply {
            text = "■  Stop"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val pad = (12 * d).toInt()
            setPadding(pad, (6 * d).toInt(), pad, (6 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(0xFFE5383B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * d).toInt() }
            contentDescription = "Stop recording"
            setOnClickListener {
                hide()
                onStop()
            }
        }
        val pill = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (8 * d).toInt()
            setPadding((14 * d).toInt(), pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(0xE6000000.toInt())
            }
            elevation = 6 * d
            addView(dot)
            addView(t)
            addView(stop)
        }
        time = t
        // Below the status bar, where it is out of the way and easy to reach.
        add(pill, Gravity.TOP or Gravity.CENTER_HORIZONTAL, (44 * d).toInt(), touchable = true)
        main.post(tick)
    }

    fun hide() {
        main.removeCallbacks(tick)
        time = null
        val v = view ?: return
        view = null
        val s = PhoneService.instance ?: return
        runCatching { if (v.isAttachedToWindow) s.getSystemService(WindowManager::class.java).removeViewImmediate(v) }
    }

    private fun add(
        v: View,
        gravity: Int,
        y: Int,
        touchable: Boolean,
    ) {
        val s = PhoneService.instance ?: return
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            title = "Soma screen record"
        }
        if (runCatching { s.getSystemService(WindowManager::class.java).addView(v, p) }.isSuccess) view = v
    }
}
