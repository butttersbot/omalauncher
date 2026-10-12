package dev.omalauncher.oma.ring

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View

/**
 * Draws a [RingFrame] along the edge of an area. The one drawing routine: home's composable, the assistant
 * window's view and the overlay's view all call [draw] with what [ringFrame] gave them. It draws strokes and
 * nothing else, never text.
 */
class RingPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val turn = Matrix()
    private val edge = RectF()
    private var shader: SweepGradient? = null
    private var shaderFor: List<Number> = emptyList()

    /** [corner] is the radius of the area's corners in pixels, 0 for square ones. */
    fun draw(canvas: Canvas, width: Float, height: Float, corner: Float, frame: RingFrame) {
        if (width <= 0f || height <= 0f) return
        val key = listOf<Number>(width, height, frame.color1, frame.color2)
        val sweep = shader.takeIf { key == shaderFor } ?: run {
            val (colors, positions) = ringStops(frame.color1, frame.color2)
            SweepGradient(width / 2f, height / 2f, colors, positions).also { shader = it; shaderFor = key }
        }
        turn.setRotate(frame.angle, width / 2f, height / 2f)
        sweep.setLocalMatrix(turn)
        paint.shader = sweep
        // Every stroke runs down the middle of the line, so the outer half of the glow falls off the screen.
        val inset = frame.width / 2f
        edge.set(inset, inset, width - inset, height - inset)
        val radius = maxOf(0f, corner - inset)
        for (stroke in ringStrokes(frame)) {
            paint.strokeWidth = stroke.width
            paint.alpha = (stroke.alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            canvas.drawRoundRect(edge, radius, radius, paint)
        }
    }
}

/** The radius of the screen's corner where [view]'s window reaches it, in pixels; 0 when it doesn't or Android can't say. */
fun screenCorner(view: View): Float {
    if (Build.VERSION.SDK_INT < 31) return 0f
    val insets = view.rootWindowInsets ?: return 0f
    return listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
        .minOf { insets.getRoundedCorner(it)?.radius ?: 0 }.toFloat()
}

/**
 * The ring as a plain view, for the windows that aren't Compose: the assistant's and the overlay's. It only
 * draws, and redraws itself each frame while the ring is lit; it takes no touch, no focus and no accessibility focus.
 */
class RingView(context: Context) : View(context) {
    private val painter = RingPainter()
    private val timeline = RingTimeline()
    private var unwatch: (() -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unwatch = OmaRing.watch { postInvalidate() }
    }

    override fun onDetachedFromWindow() {
        unwatch?.invoke()
        unwatch = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val style = ringStyle()
        val now = OmaRing.now()
        val state = ringState(OmaRing.signals, now, doneMillis(style.done))
        ringFrame(style, timeline.at(state, now), resources.displayMetrics.density)?.let {
            painter.draw(canvas, width.toFloat(), height.toFloat(), screenCorner(this), it)
        }
        if (state != RingState.Off) postInvalidateOnAnimation()
    }
}
