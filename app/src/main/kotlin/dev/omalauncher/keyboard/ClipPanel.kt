package dev.omalauncher.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.omalauncher.keyboard.KeyboardView.Companion.blend
import kotlin.math.abs

/**
 * The keyboard's clipboard: what was copied lately ([ClipHistory]), newest first, drawn in [KeyboardView]'s place
 * while it is up, as the emoji picker is. A tap on a clip types it into the app; a long press takes it out of the
 * history. At the top ABC (back to the letters), and Clear. The list scrolls.
 */
class ClipPanel(
    private val view: View,
    private val host: Host,
) {
    interface Host {
        /** What there is to show, newest first. */
        fun clips(): List<ClipHistory.Clip>

        fun pasteClip(text: String)

        fun forgetClip(text: String)

        fun clearClips()

        fun showLetters()

        val haptics: Boolean
    }

    private val density = view.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private var top = 0f
    private var bottom = 0f
    private var width = 0f
    private var scrollY = 0f
    private var shown: List<ClipHistory.Clip> = emptyList()
    private var pressed = -1
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPress: Runnable? = null

    /** Called as the panel comes up: from the top of the list. */
    fun opened() {
        scrollY = 0f
        pressed = -1
    }

    fun draw(
        canvas: Canvas,
        top: Float,
        width: Float,
        height: Float,
        k: KeyboardLook,
    ) {
        val d = density
        this.top = top
        this.width = width
        bottom = top + height
        shown = host.clips()
        val ink = if (k.letter != null) k.pageInk else k.ink
        val muted = blend(ink, if (k.letter != null) k.page else k.board, 0.45f)
        text.typeface = k.typeface

        // The head: ABC, the title, Clear.
        val headH = HEAD_DP * d
        val cy = top + headH / 2
        text.textSize = 15 * d
        text.color = ink
        text.textAlign = Paint.Align.LEFT
        val lift = (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
        canvas.drawText("ABC", 16 * d, cy - lift, text)
        text.textAlign = Paint.Align.CENTER
        text.color = muted
        canvas.drawText("Clipboard", width / 2, cy - lift, text)
        if (shown.isNotEmpty()) {
            text.textAlign = Paint.Align.RIGHT
            text.color = ink
            canvas.drawText("Clear", width - 16 * d, cy - lift, text)
        }
        paint.color = blend(ink, k.board, 0.8f)
        canvas.drawRect(0f, top + headH - d, width, top + headH, paint)

        // The clips.
        val listTop = top + headH
        val rowH = ROW_DP * d
        val maxScroll = maxOf(0f, shown.size * rowH - (bottom - listTop))
        scrollY = scrollY.coerceIn(0f, maxScroll)
        canvas.save()
        canvas.clipRect(0f, listTop, width, bottom)
        if (shown.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.color = muted
            text.textSize = 14 * d
            canvas.drawText("Copy something and it shows here, for a week.", width / 2, listTop + (bottom - listTop) / 2, text)
        }
        val now = System.currentTimeMillis()
        val pad = 12 * d
        for ((i, c) in shown.withIndex()) {
            val y0 = listTop + i * rowH - scrollY
            if (y0 + rowH < listTop || y0 > bottom) continue
            box.set(pad, y0 + 4 * d, width - pad, y0 + rowH - 4 * d)
            paint.color = if (i == pressed) blend(k.key, ink, 0.15f) else k.key
            canvas.drawRoundRect(box, k.radius * d, k.radius * d, paint)
            text.textAlign = Paint.Align.LEFT
            text.textSize = 12 * d
            text.color = muted
            val age = age(now - c.at)
            val ageW = text.measureText(age)
            canvas.drawText(age, box.right - 12 * d - ageW, box.centerY() - lift * 0.8f, text)
            text.textSize = 15 * d
            text.color = k.ink
            val line = TextUtils.ellipsize(ClipHistory.oneLine(c.text), text, box.width() - 36 * d - ageW, TextUtils.TruncateAt.END)
            canvas.drawText(line, 0, line.length, box.left + 12 * d, box.centerY() - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2, text)
        }
        canvas.restore()
    }

    fun touch(e: MotionEvent): Boolean {
        val d = density
        val y = e.y
        val x = e.x
        val listTop = top + HEAD_DP * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = y
                lastY = y
                dragging = false
                if (y < listTop) {
                    when {
                        x < 90 * d -> {
                            tick()
                            host.showLetters()
                        }
                        x > width - 90 * d && shown.isNotEmpty() -> {
                            tick()
                            host.clearClips()
                            view.invalidate()
                        }
                    }
                    pressed = -1
                    return true
                }
                pressed = ((y - listTop + scrollY) / (ROW_DP * d)).toInt().takeIf { it in shown.indices } ?: -1
                if (pressed >= 0) {
                    val which = pressed
                    val r =
                        Runnable {
                            if (pressed == which) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                host.forgetClip(shown[which].text)
                                pressed = -1
                                view.invalidate()
                            }
                        }
                    longPress = r
                    view.postDelayed(r, LONG_PRESS_MS)
                }
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(y - downY) > SLOP_DP * d) {
                    dragging = true
                    pressed = -1
                    longPress?.let(view::removeCallbacks)
                }
                if (dragging) {
                    scrollY -= y - lastY
                    view.invalidate()
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                longPress?.let(view::removeCallbacks)
                val p = pressed
                pressed = -1
                if (!dragging && p in shown.indices) {
                    tick()
                    host.pasteClip(shown[p].text)
                }
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                longPress?.let(view::removeCallbacks)
                pressed = -1
                view.invalidate()
            }
        }
        return true
    }

    private fun tick() {
        if (host.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        const val HEAD_DP = 44f
        const val ROW_DP = 52f
        const val SLOP_DP = 8f
        const val LONG_PRESS_MS = 500L

        /** "now", "5m", "3h", "2d": how long ago, short. */
        fun age(ms: Long): String =
            when {
                ms < 60_000 -> "now"
                ms < 3_600_000 -> "${ms / 60_000}m"
                ms < 86_400_000 -> "${ms / 3_600_000}h"
                else -> "${ms / 86_400_000}d"
            }
    }
}
