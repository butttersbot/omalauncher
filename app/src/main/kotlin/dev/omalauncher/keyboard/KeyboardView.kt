package dev.omalauncher.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** The keyboard's colours, from the active look (see [OmaKeyboardService.look]). ARGB. */
class KeyboardLook(
    val board: Int,
    val key: Int,
    val mod: Int,
    val ink: Int,
    val muted: Int,
    val mic: Int,
    val onMic: Int,
    val ret: Int,
    val onRet: Int,
    val led: Int,
    val typeface: Typeface,
)

/**
 * Draws the keys of a [KeyLayout] and turns touches into key presses, as the practice page did: a letter
 * lands when the finger lifts or the next finger comes down (rollover); shift and ⌫ act on touch and ⌫
 * repeats while held; a slide along the space bar moves the caret; a swipe down puts the keyboard away.
 * Above the keys is a strip with no touches, where a pressed letter's bubble shows.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val host: Host,
) : View(context) {
    interface Host {
        /** The key at ([x], [y]) dp, with the learned aim if it is on. */
        fun hit(
            x: Float,
            y: Float,
        ): Key?

        /** [key] was pressed by a touch at ([x], [y]) dp. */
        fun press(
            key: Key,
            x: Float,
            y: Float,
        )

        /** Move the caret [steps] characters (negative: left). */
        fun slide(steps: Int)

        fun swipeDown()

        /** 🙂 held: the system's keyboard switcher. */
        fun longPressEmoji()

        val haptics: Boolean

        fun thock(big: Boolean)
    }

    private val density = resources.displayMetrics.density
    var layout: KeyLayout? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    var look: KeyboardLook? = null
        set(value) {
            field = value
            invalidate()
        }
    var mech = true
    var listening = false
        set(value) {
            field = value
            invalidate()
        }

    /** Room above the keys for a pressed key's bubble, dp. Touches there go to the app (see the service's insets). */
    val headroomDp get() = (layout?.rowHeight ?: 54f) * 1.3f
    val headroomPx get() = (headroomDp * density).toInt()

    private class Press(
        val key: Key,
        val x0: Float,
        val y0: Float,
        var done: Boolean = false,
        var moved: Boolean = false,
        var slid: Int = 0,
    )

    private val presses = LinkedHashMap<Int, Press>()
    private val handler = Handler(Looper.getMainLooper())
    private var repeat: Runnable? = null
    private var hold: Runnable? = null

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = ((layout?.height ?: 240f) * density).toInt() + headroomPx
        setMeasuredDimension(w, h)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        // Samsung's edge swipe is back; over the keys it threw Boss out of what he was typing (2026-10-14). The
        // system honours at most 200 dp of each edge, so the letter rows are kept from it, from the top down.
        val l = layout ?: return
        val t = headroomPx
        val b = t + (minOf(200f, l.height) * density).toInt()
        val side = (72 * density).toInt()
        systemGestureExclusionRects = listOf(Rect(0, t, side, b), Rect(width - side, t, width, b))
    }

    // ---- Drawing ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val k = look ?: return
        val d = density
        val top = headroomPx.toFloat()
        // The board.
        if (mech) {
            fill.shader = LinearGradient(0f, top, 0f, height.toFloat(), blend(k.board, BLACK, 0.2f), blend(k.board, BLACK, 0.08f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
            fill.shader = null
            fill.color = blend(k.board, WHITE, 0.4f)
            canvas.drawRect(0f, top, width.toFloat(), top + 3 * d, fill)
            fill.color = k.led
            box.set(6 * d, top + 3 * d, width - 6 * d, top + 6 * d)
            canvas.drawRoundRect(box, 2 * d, 2 * d, fill)
        } else {
            fill.color = k.board
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
        }
        val down = presses.values.map { it.key }.toSet()
        val pulse = if (listening) (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 180.0).toFloat()) else 0f
        for (key in l.keys) drawKey(canvas, key, key in down, k, top, pulse)
        // A pressed letter's bubble, above its cap.
        presses.values.lastOrNull { it.key.kind == KeyKind.LETTER && !it.done }?.let { drawBubble(canvas, it.key, k, top) }
        if (listening) postInvalidateDelayed(40)
    }

    private fun drawKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
        k: KeyboardLook,
        top: Float,
        pulse: Float,
    ) {
        val d = density
        val c = key.cap
        val face =
            when (key.kind) {
                KeyKind.LETTER, KeyKind.SPACE -> k.key
                KeyKind.MOD -> if (key.action == KeyAction.Shift && key.label == "⇪") k.key else k.mod
                KeyKind.MIC -> k.mic
                KeyKind.RETURN -> k.ret
            }
        val ink =
            when (key.kind) {
                KeyKind.MIC -> k.onMic
                KeyKind.RETURN -> k.onRet
                KeyKind.SPACE -> k.muted
                else -> k.ink
            }
        val sink = if (pressed) (if (mech) 2f else 1f) * d else 0f
        box.set(c.x * d, top + c.y * d + sink, (c.x + c.w) * d, top + (c.y + c.h) * d + sink)
        if (key.kind == KeyKind.MIC && pulse > 0f) {
            fill.color = (face and 0x00ffffff) or ((90 * pulse).toInt() shl 24)
            val g = 6 * d * pulse
            canvas.drawRoundRect(RectF(box.left - g, box.top - g, box.right + g, box.bottom + g), 10 * d, 10 * d, fill)
        }
        if (mech) {
            // A NuPhy-like cap: a darker skirt, and a lighter top face set into it.
            val r = 7 * d
            if (!pressed) {
                fill.color = blend(face, BLACK, 0.45f)
                canvas.drawRoundRect(RectF(box.left, box.top + 3 * d, box.right, box.bottom + 3 * d), r, r, fill)
            }
            fill.color = blend(face, BLACK, 0.28f)
            canvas.drawRoundRect(box, r, r, fill)
            val faceBox = RectF(box.left + 3 * d, box.top + 2 * d, box.right - 3 * d, box.bottom - 6 * d)
            fill.shader = LinearGradient(0f, faceBox.top, 0f, faceBox.bottom, blend(face, WHITE, 0.12f), face, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(faceBox, 5 * d, 5 * d, fill)
            fill.shader = null
            drawLabel(canvas, key, faceBox, ink, k)
        } else {
            val r = 5 * d
            fill.color = blend(face, BLACK, 0.25f)
            canvas.drawRoundRect(RectF(box.left, box.top + 1 * d, box.right, box.bottom + 1 * d), r, r, fill)
            fill.color = if (pressed) blend(face, BLACK, 0.1f) else face
            canvas.drawRoundRect(box, r, r, fill)
            drawLabel(canvas, key, box, ink, k)
        }
    }

    private fun labelSize(key: Key): Float {
        val l = layout ?: return 20f
        val base = minOf(l.rowHeight - (if (mech) 14f else 10f), 30f) * 0.55f
        val small = (key.kind == KeyKind.MOD || key.kind == KeyKind.SPACE || key.kind == KeyKind.RETURN) && key.label.length > 1 && key.label != "⇧"
        return (if (small) base * 0.7f else base) * density
    }

    private fun drawLabel(
        canvas: Canvas,
        key: Key,
        b: RectF,
        ink: Int,
        k: KeyboardLook,
    ) {
        text.typeface = k.typeface
        text.textSize = labelSize(key)
        text.color = ink
        val fm = text.fontMetrics
        canvas.drawText(key.label, b.centerX(), b.centerY() - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawBubble(
        canvas: Canvas,
        key: Key,
        k: KeyboardLook,
        top: Float,
    ) {
        val d = density
        val c = key.cap
        val w = (c.w + 14) * d
        val h = c.h * 1.15f * d
        val cx = (c.x + c.w / 2) * d
        val bottom = top + c.y * d - 4 * d
        box.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
        fill.color = 0x40000000
        canvas.drawRoundRect(RectF(box.left, box.top + 2 * d, box.right, box.bottom + 3 * d), 8 * d, 8 * d, fill)
        fill.color = k.key
        canvas.drawRoundRect(box, 8 * d, 8 * d, fill)
        text.typeface = k.typeface
        text.textSize = h * 0.55f
        text.color = k.ink
        val fm = text.fontMetrics
        canvas.drawText(key.label, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2, text)
    }

    // ---- Touch ----
    private fun dpX(px: Float) = px / density

    private fun dpY(px: Float) = (px - headroomPx) / density

    private fun tick() {
        if (host.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), dpX(e.getX(i)), dpY(e.getY(i)))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), dpX(e.getX(i)), dpY(e.getY(i)))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex), commit = true)
            MotionEvent.ACTION_CANCEL -> presses.keys.toList().forEach { up(it, commit = false) }
        }
        return true
    }

    private fun down(
        id: Int,
        x: Float,
        y: Float,
    ) {
        // Rollover: a letter still held lands as soon as the next finger comes down.
        for (p in presses.values) {
            if (!p.done && p.key.action != KeyAction.Space) {
                p.done = true
                host.press(p.key, p.x0, p.y0)
            }
        }
        if (y < 0) return
        val key = host.hit(x, y) ?: return
        val p = Press(key, x, y)
        presses[id] = p
        tick()
        host.thock(key.cell.w > 1.4f * (layout?.unit ?: 40f))
        when (key.action) {
            KeyAction.Shift -> {
                p.done = true
                host.press(key, x, y)
            }
            KeyAction.Back -> {
                p.done = true
                host.press(key, x, y)
                val r =
                    object : Runnable {
                        override fun run() {
                            host.press(key, x, y)
                            tick()
                            handler.postDelayed(this, 60)
                        }
                    }
                repeat = r
                handler.postDelayed(r, 400)
            }
            KeyAction.Emoji -> {
                val r =
                    Runnable {
                        p.done = true
                        host.longPressEmoji()
                    }
                hold = r
                handler.postDelayed(r, 500)
            }
            else -> {}
        }
        invalidate()
    }

    private fun move(
        id: Int,
        x: Float,
        y: Float,
    ) {
        val p = presses[id] ?: return
        val dx = x - p.x0
        val dy = y - p.y0
        val l = layout ?: return
        // A swipe down over the keys puts the keyboard away; a tap never travels this far.
        if (!p.done && p.key.action != KeyAction.Space && dy > 0.9f * l.rowHeight && abs(dx) < dy / 2) {
            p.done = true
            cancelTimers()
            presses.clear()
            invalidate()
            host.swipeDown()
            return
        }
        if (p.key.action == KeyAction.Space) {
            val steps = (dx / 12f).toInt()
            if (steps != 0) p.moved = true
            if (p.moved && steps != p.slid) {
                host.slide(steps - p.slid)
                p.slid = steps
                tick()
            }
        }
    }

    private fun up(
        id: Int,
        commit: Boolean,
    ) {
        val p = presses.remove(id) ?: return
        cancelTimers()
        if (commit && !p.done && !(p.key.action == KeyAction.Space && p.moved)) host.press(p.key, p.x0, p.y0)
        invalidate()
    }

    private fun cancelTimers() {
        repeat?.let(handler::removeCallbacks)
        hold?.let(handler::removeCallbacks)
        repeat = null
        hold = null
    }

    override fun onDetachedFromWindow() {
        cancelTimers()
        presses.clear()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val BLACK = 0xff000000.toInt()
        private const val WHITE = 0xffffffff.toInt()

        fun blend(
            a: Int,
            b: Int,
            t: Float,
        ): Int {
            fun ch(
                s: Int,
                sh: Int,
            ) = (s shr sh) and 0xff

            fun mix(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * t).toInt().coerceIn(0, 255) shl sh
            return mix(24) or mix(16) or mix(8) or mix(0)
        }
    }
}
