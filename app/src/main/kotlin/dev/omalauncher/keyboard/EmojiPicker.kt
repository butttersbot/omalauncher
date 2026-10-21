package dev.omalauncher.keyboard

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import dev.omalauncher.keyboard.KeyboardView.Companion.blend
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The keyboard's emoji picker, drawn in [KeyboardView]'s place while it is up, as Apple's is (Boss, 2026-10-20):
 * a "Search Emoji" pill at the top; the emoji big, in four rows that scroll sideways, section after section with no
 * titles between (Boss, 2026-10-20, from his iPhone's: "remove the headers"); Frequently Used first; under them ABC, the sections' icons (a tap
 * goes to that section, and the one showing is lit) and ⌫, which repeats while held. A long press on an emoji with
 * skin tones offers them above it: slide to one and let go, or let go and tap one; the tone picked is what that
 * emoji shows from then on.
 *
 * While searching, the letter keys come back with a band above them ([drawBand]): the field with what is typed, and
 * a row of what it finds, which a tap types into the app.
 */
class EmojiPicker(
    private val view: View,
    private val host: Host,
) {
    interface Host {
        /** Every emoji, once read; null until then. */
        val catalog: EmojiCatalog?
        val usage: EmojiUsage

        /** Types [emoji] into the app and counts it as used. */
        fun typeEmoji(emoji: String)

        /** ⌫ in the app's field. */
        fun deleteBack()

        /** ABC: back to the letters. */
        fun showLetters()

        fun startSearch()

        /** What has been typed into the search field. */
        val query: String

        fun clearQuery()

        fun endSearch()

        /** A form of [item] was picked from its tones. */
        fun pickedTone(
            item: EmojiItem,
            form: String,
        )

        val haptics: Boolean
    }

    private val density = view.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    // ---- What the grid holds, laid out in columns of [ROWS] ----

    /** One section as laid out: where it begins in the strip of columns (px) and how wide it is. */
    private class Part(
        val title: String,
        val icon: String,
        val items: List<EmojiItem>,
        val start: Float,
        val width: Float,
    )

    private var parts: List<Part> = emptyList()
    private var partsFor: Any? = null
    private var cell = 40f
    private var contentWidth = 0f
    private var scrollX = 0f
    private val scroller = OverScroller(view.context)
    private var velocity: VelocityTracker? = null

    /** The frequently used items as the grid shows them, read when the picker comes up (and once the list is read). */
    private var frequent: List<EmojiItem> = emptyList()
    private var frequentFor: Any? = Unit

    /** Called each time the picker comes up: Frequently Used is read again, and the grid starts where Apple's does. */
    fun opened() {
        frequentFor = Unit
        partsFor = null
        scroller.forceFinished(true)
        scrollX = 0f
        tones = null
        pressed = null
        bandScroll = 0f
        resultsFor = null
    }

    private fun readFrequent() {
        val c = host.catalog
        if (frequentFor === c) return
        frequentFor = c
        // As typed, tone and all; a long press there offers nothing, as the tones are in the sections.
        frequent = if (c == null) emptyList() else host.usage.frequent().mapNotNull { e -> c.item(e)?.let { EmojiItem(e, emptyList(), it.words) } }
    }

    private fun lay(
        width: Float,
        gridH: Float,
    ) {
        readFrequent()
        val c = host.catalog
        val key = Triple(c, width, gridH)
        if (partsFor == key) return
        partsFor = key
        cell = gridH / ROWS
        val pad = SIDE_DP * density
        var x = pad
        val out = ArrayList<Part>()

        fun add(
            title: String,
            icon: String,
            items: List<EmojiItem>,
        ) {
            if (items.isEmpty()) return
            val w = ceil(items.size / ROWS.toFloat()) * cell
            out += Part(title, icon, items, x, w)
            x += w + GAP_CELLS * cell
        }
        add("Frequently Used", ICONS[0], frequent)
        c?.sections?.forEachIndexed { i, s -> add(s.title, ICONS.getOrElse(i + 1) { "•" }, s.items) }
        parts = out
        contentWidth = x - GAP_CELLS * cell + pad
    }

    private var viewWidth = 0f
    private val maxScroll get() = (contentWidth - viewWidth).coerceAtLeast(0f)

    /** The section showing: the one at the left edge, or the last once the grid is at its end. */
    private fun current(): Int {
        if (parts.isEmpty()) return -1
        if (scrollX >= maxScroll - 1f && maxScroll > 0f) return parts.lastIndex
        val at = scrollX + cell
        return parts.indexOfLast { it.start <= at }.coerceAtLeast(0)
    }

    // ---- Drawing ----
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        alpha = 150
    }
    private val box = RectF()

    /** Where things are, px, from the last draw: touches are read against them. */
    private var top = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private var barTop = 0f
    private var bottom = 0f

    private class Colors(val bg: Int, val ink: Int, val muted: Int, val field: Int, val lit: Int)

    private fun colors(k: KeyboardLook): Colors {
        val bg = if (k.letter != null) k.page else k.board
        val ink = if (k.letter != null) k.pageInk else k.ink
        val field = k.letter?.fill ?: k.key
        return Colors(bg, ink, blend(ink, bg, 0.45f), field, blend(bg, ink, 0.13f))
    }

    /** Draws the picker in the board's place: from [top] down [height] px, [width] px wide, on the board's colour. */
    fun draw(
        canvas: Canvas,
        top: Float,
        width: Float,
        height: Float,
        k: KeyboardLook,
    ) {
        val d = density
        val col = colors(k)
        this.top = top
        viewWidth = width
        bottom = top + height
        barTop = bottom - BAR_DP * d
        gridTop = top + SEARCH_DP * d
        gridBottom = barTop
        lay(width, gridBottom - gridTop)
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScroll)
            view.postInvalidateOnAnimation()
        }
        scrollX = scrollX.coerceIn(0f, maxScroll)

        // The search field.
        drawField(canvas, top, width, col, k, null)

        // The grid: only the columns that show are drawn.
        canvas.save()
        canvas.clipRect(0f, top + SEARCH_DP * d, width, gridBottom)
        text.textSize = cell * GLYPH
        text.typeface = null
        text.color = col.ink or 0xff000000.toInt()
        val fm = text.fontMetrics
        val lift = (fm.ascent + fm.descent) / 2
        for (p in parts) {
            if (p.start + p.width < scrollX || p.start > scrollX + width) {
                continue
            }
            val first = ((scrollX - p.start) / cell).toInt().coerceAtLeast(0)
            val last = ((scrollX + width - p.start) / cell).toInt()
            for (c in first..last) {
                for (r in 0 until ROWS) {
                    val i = c * ROWS + r
                    if (i >= p.items.size) break
                    val item = p.items[i]
                    val cx = p.start + c * cell + cell / 2 - scrollX
                    val cy = gridTop + r * cell + cell / 2
                    if (pressed?.let { it.part === p && it.index == i } == true && tones == null) {
                        paint.color = col.lit
                        box.set(cx - cell / 2 + 2 * d, cy - cell / 2 + 2 * d, cx + cell / 2 - 2 * d, cy + cell / 2 - 2 * d)
                        canvas.drawRoundRect(box, 8 * d, 8 * d, paint)
                    }
                    canvas.drawText(shown(p, item), cx, cy - lift, text)
                }
            }
        }
        if (parts.isEmpty() || (host.catalog == null && frequent.isEmpty())) {
            text.textSize = 14 * d
            text.color = col.muted
            text.typeface = k.typeface
            canvas.drawText("…", width / 2, (gridTop + gridBottom) / 2, text)
        }
        canvas.restore()

        // The bar: ABC, the sections' icons, ⌫.
        drawBar(canvas, width, col, k)

        tones?.let { drawTones(canvas, it, col, width) }
    }

    /** What a cell shows: in Frequently Used the form that was typed, elsewhere the tone last picked. */
    private fun shown(
        p: Part,
        item: EmojiItem,
    ) = if (p.items === frequent) item.emoji else host.usage.shown(item)

    /** The search field from [y] down, [SEARCH_DP] tall; [query] null for the picker's own, which only opens a search. */
    private fun drawField(
        canvas: Canvas,
        y: Float,
        width: Float,
        col: Colors,
        k: KeyboardLook,
        query: String?,
    ) {
        val d = density
        paint.color = col.field
        // A pill, as Apple's, clear of the board's edges and of the emoji below.
        box.set(FIELD_SIDE_DP * d, y + FIELD_TOP_DP * d, width - FIELD_SIDE_DP * d, y + (FIELD_TOP_DP + FIELD_DP) * d)
        canvas.drawRoundRect(box, FIELD_DP / 2 * d, FIELD_DP / 2 * d, paint)
        val cy = box.centerY()
        // A magnifying glass.
        stroke.color = col.muted
        stroke.strokeWidth = 1.8f * d
        val gx = box.left + 16 * d
        canvas.drawCircle(gx, cy - 1.5f * d, 5 * d, stroke)
        canvas.drawLine(gx + 3.6f * d, cy + 2.1f * d, gx + 7.5f * d, cy + 6 * d, stroke)
        text.textAlign = Paint.Align.LEFT
        text.typeface = k.typeface
        text.textSize = 15 * d
        val fm = text.fontMetrics
        val ty = cy - (fm.ascent + fm.descent) / 2
        val tx = gx + 14 * d
        if (query.isNullOrEmpty()) {
            text.color = col.muted
            canvas.drawText("Search Emoji", tx, ty, text)
        } else {
            text.color = col.ink
            canvas.drawText(query, tx, ty, text)
        }
        if (query != null) {
            // The caret, and ✕ to clear the field or, empty, to leave the search.
            val cx = tx + (if (query.isEmpty()) 0f else text.measureText(query) + 1.5f * d)
            paint.color = k.ret
            canvas.drawRect(cx, cy - 9 * d, cx + 2 * d, cy + 9 * d, paint)
            val xx = box.right - 16 * d
            paint.color = col.muted
            canvas.drawCircle(xx, cy, 8 * d, paint)
            stroke.color = col.field
            stroke.strokeWidth = 1.8f * d
            canvas.drawLine(xx - 3 * d, cy - 3 * d, xx + 3 * d, cy + 3 * d, stroke)
            canvas.drawLine(xx - 3 * d, cy + 3 * d, xx + 3 * d, cy - 3 * d, stroke)
        }
        text.textAlign = Paint.Align.CENTER
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** The bar's touch spots, px: ABC at the left, ⌫ at the right, the icons evenly between. */
    private fun sideW() = SIDE_BUTTON_DP * density

    private fun drawBar(
        canvas: Canvas,
        width: Float,
        col: Colors,
        k: KeyboardLook,
    ) {
        val d = density
        val cy = (barTop + bottom) / 2
        text.typeface = Typeface.create(k.typeface, Typeface.BOLD)
        text.textSize = 14 * d
        text.color = col.ink
        var fm = text.fontMetrics
        canvas.drawText("ABC", sideW() / 2, cy - (fm.ascent + fm.descent) / 2, text)
        // ⌫ as a drawn key cap, as Apple's.
        val bx = width - sideW() / 2
        stroke.color = col.ink
        stroke.strokeWidth = 1.7f * d
        val path = android.graphics.Path().apply {
            moveTo(bx - 12 * d, cy)
            lineTo(bx - 6 * d, cy - 7 * d)
            lineTo(bx + 11 * d, cy - 7 * d)
            lineTo(bx + 11 * d, cy + 7 * d)
            lineTo(bx - 6 * d, cy + 7 * d)
            close()
        }
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, stroke)
        canvas.drawLine(bx - 1 * d, cy - 3 * d, bx + 5 * d, cy + 3 * d, stroke)
        canvas.drawLine(bx - 1 * d, cy + 3 * d, bx + 5 * d, cy - 3 * d, stroke)
        // The icons: the section showing in colour on a lit disc, the others grey.
        val n = parts.size
        if (n == 0) return
        val now = current()
        val slot = (width - 2 * sideW()) / n
        // Small and grey, as Apple's line icons; the section showing on a lit disc.
        val size = minOf(slot * 0.55f, ICON_DP * d)
        text.typeface = null
        text.textSize = size
        grey.textSize = size
        fm = text.fontMetrics
        for ((i, p) in parts.withIndex()) {
            val cx = sideW() + slot * (i + 0.5f)
            if (i == now) {
                paint.color = col.lit
                canvas.drawCircle(cx, cy, size * 0.82f, paint)
                canvas.drawText(p.icon, cx, cy - (fm.ascent + fm.descent) / 2, grey)
            } else {
                canvas.drawText(p.icon, cx, cy - (fm.ascent + fm.descent) / 2, grey)
            }
        }
    }

    // ---- Skin tones ----
    private class Tones(
        val item: EmojiItem,
        val cx: Float,
        val cellTop: Float,
        var at: Int,
        /** The finger has moved since they came up: letting go picks. */
        var slid: Boolean = false,
        /** They stay up after the finger lifted without sliding, for a tap. */
        var held: Boolean = true,
    ) {
        val box = RectF()
    }

    private var tones: Tones? = null

    private fun drawTones(
        canvas: Canvas,
        t: Tones,
        col: Colors,
        width: Float,
    ) {
        val d = density
        val forms = t.item.forms
        val w = cell * 1.05f
        val total = w * forms.size + 8 * d
        val left = (t.cx - total / 2).coerceIn(4 * d, width - total - 4 * d)
        val b = t.cellTop - 4 * d
        val h = w + 8 * d
        t.box.set(left, (b - h).coerceAtLeast(top + 2 * d), left + total, (b - h).coerceAtLeast(top + 2 * d) + h)
        paint.color = 0x50000000
        canvas.drawRoundRect(RectF(t.box.left, t.box.top + 3 * d, t.box.right, t.box.bottom + 4 * d), 14 * d, 14 * d, paint)
        paint.color = col.field
        canvas.drawRoundRect(t.box, 14 * d, 14 * d, paint)
        text.typeface = null
        text.textSize = w * GLYPH
        val fm = text.fontMetrics
        for ((i, f) in forms.withIndex()) {
            val cx = t.box.left + 4 * d + w * (i + 0.5f)
            if (i == t.at) {
                paint.color = blend(col.field, col.ink, 0.16f)
                canvas.drawRoundRect(RectF(cx - w / 2 + 2 * d, t.box.top + 6 * d, cx + w / 2 - 2 * d, t.box.bottom - 6 * d), 9 * d, 9 * d, paint)
            }
            canvas.drawText(f, cx, t.box.centerY() - (fm.ascent + fm.descent) / 2, text)
        }
    }

    private fun toneAt(
        t: Tones,
        x: Float,
    ): Int {
        val w = cell * 1.05f
        return ((x - t.box.left - 4 * density) / w).toInt().coerceIn(0, t.item.forms.lastIndex)
    }

    // ---- Touch ----
    private class Hit(
        val part: Part,
        val index: Int,
        val cellTop: Float,
        val cx: Float,
    )

    private var pressed: Hit? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var longPress: Runnable? = null
    private var repeat: Runnable? = null
    private var inBar = false

    private fun tick() {
        if (host.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun hitCell(
        x: Float,
        y: Float,
    ): Hit? {
        if (y < gridTop || y >= gridBottom) return null
        val sx = x + scrollX
        val p = parts.firstOrNull { sx >= it.start && sx < it.start + it.width } ?: return null
        val c = ((sx - p.start) / cell).toInt()
        val r = ((y - gridTop) / cell).toInt().coerceIn(0, ROWS - 1)
        val i = c * ROWS + r
        if (i >= p.items.size) return null
        return Hit(p, i, gridTop + r * cell, p.start + c * cell + cell / 2 - scrollX)
    }

    /** A touch on the picker; [e]'s coordinates are the view's, px. */
    fun touch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopTimers()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x
                downY = y
                lastX = x
                dragging = false
                inBar = false
                val t = tones
                if (t != null) {
                    // Tones left up for a tap: a tap on one picks it; anywhere else puts them away.
                    if (t.box.contains(x, y)) {
                        t.at = toneAt(t, x)
                        t.slid = true
                    } else {
                        tones = null
                        pressed = null
                    }
                    view.invalidate()
                    return true
                }
                if (!scroller.isFinished) scroller.forceFinished(true)
                if (y < top + SEARCH_DP * density) {
                    tick()
                    host.startSearch()
                    return true
                }
                if (y >= barTop) {
                    inBar = true
                    bar(x)
                    return true
                }
                pressed = hitCell(x, y)?.also { h ->
                    tick()
                    val item = h.part.items[h.index]
                    if (item.tones.isNotEmpty()) {
                        val r =
                            Runnable {
                                val form = shown(h.part, item)
                                tones = Tones(item, h.cx, h.cellTop, item.forms.indexOf(form).coerceAtLeast(0))
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                view.invalidate()
                            }
                        longPress = r
                        handler.postDelayed(r, LONG_PRESS_MS)
                    }
                }
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val t = tones
                if (t != null) {
                    val at = toneAt(t, x)
                    if (abs(x - downX) > SLOP_DP * density || at != t.at) t.slid = true
                    if (at != t.at) {
                        t.at = at
                        tick()
                    }
                    view.invalidate()
                    return true
                }
                if (inBar) return true
                if (!dragging && abs(x - downX) > SLOP_DP * density) {
                    dragging = true
                    stopTimers()
                    pressed = null
                }
                if (dragging) {
                    scrollX = (scrollX - (x - lastX)).coerceIn(0f, maxScroll)
                    view.invalidate()
                }
                lastX = x
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                stopTimers()
                val t = tones
                if (t != null) {
                    if (t.slid) {
                        val form = t.item.forms[t.at]
                        tones = null
                        pressed = null
                        host.pickedTone(t.item, form)
                        host.typeEmoji(form)
                    }
                    view.invalidate()
                    return true
                }
                if (dragging) {
                    velocity?.let { v ->
                        v.computeCurrentVelocity(1000)
                        scroller.fling(scrollX.toInt(), 0, -v.xVelocity.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
                    }
                } else {
                    pressed?.let { h -> host.typeEmoji(shown(h.part, h.part.items[h.index])) }
                }
                pressed = null
                dragging = false
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                stopTimers()
                pressed = null
                dragging = false
                view.invalidate()
            }
        }
        return true
    }

    /** A touch coming down on the bar at [x]. */
    private fun bar(x: Float) {
        tick()
        val w = viewWidth
        when {
            x < sideW() -> host.showLetters()
            x > w - sideW() -> {
                host.deleteBack()
                val r =
                    object : Runnable {
                        override fun run() {
                            host.deleteBack()
                            tick()
                            handler.postDelayed(this, 60)
                        }
                    }
                repeat = r
                handler.postDelayed(r, 400)
            }
            parts.isNotEmpty() -> {
                val slot = (w - 2 * sideW()) / parts.size
                val i = ((x - sideW()) / slot).toInt().coerceIn(0, parts.lastIndex)
                val to = (parts[i].start - SIDE_DP * density).coerceIn(0f, maxScroll)
                scroller.forceFinished(true)
                scroller.startScroll(scrollX.toInt(), 0, (to - scrollX).toInt(), 0, 260)
                view.invalidate()
            }
        }
    }

    private fun stopTimers() {
        longPress?.let(handler::removeCallbacks)
        repeat?.let(handler::removeCallbacks)
        longPress = null
        repeat = null
    }

    fun detached() {
        stopTimers()
        tones = null
        pressed = null
    }

    // ---- The search band, over the letter keys while searching ----
    private var bandTop = 0f
    private var bandScroll = 0f
    private var results: List<EmojiItem> = emptyList()
    private var resultsFor: String? = null
    private var bandDownX = 0f
    private var bandLastX = 0f
    private var bandDragging = false

    private fun results(): List<EmojiItem> {
        readFrequent()
        val q = host.query
        if (q != resultsFor) {
            resultsFor = q
            bandScroll = 0f
            results = if (q.isBlank()) frequent else host.catalog?.search(q).orEmpty()
        }
        return results
    }

    /** The band: the field with what is typed, then a row of what it finds, from [top] down [BAND_DP]. */
    fun drawBand(
        canvas: Canvas,
        top: Float,
        width: Float,
        k: KeyboardLook,
    ) {
        val d = density
        val col = colors(k)
        bandTop = top
        viewWidth = width
        drawField(canvas, top, width, col, k, host.query)
        val r = results()
        val rowTop = top + SEARCH_DP * d
        val size = (BAND_DP - SEARCH_DP) * d
        val maxBand = (r.size * size + 2 * SIDE_DP * d - width).coerceAtLeast(0f)
        bandScroll = bandScroll.coerceIn(0f, maxBand)
        if (r.isEmpty()) {
            text.typeface = k.typeface
            text.textSize = 14 * d
            text.color = col.muted
            val fm = text.fontMetrics
            canvas.drawText(if (host.query.isBlank()) "" else "No emoji found", width / 2, rowTop + size / 2 - (fm.ascent + fm.descent) / 2, text)
            return
        }
        text.typeface = null
        text.textSize = size * GLYPH * 0.92f
        val fm = text.fontMetrics
        canvas.save()
        canvas.clipRect(0f, rowTop, width, rowTop + size)
        for ((i, item) in r.withIndex()) {
            val cx = SIDE_DP * d + size * (i + 0.5f) - bandScroll
            if (cx < -size || cx > width + size) continue
            canvas.drawText(if (r === frequent) item.emoji else host.usage.shown(item), cx, rowTop + size / 2 - (fm.ascent + fm.descent) / 2, text)
        }
        canvas.restore()
    }

    /** A touch on the band; [e]'s coordinates are the view's, px. */
    fun touchBand(e: MotionEvent): Boolean {
        val d = density
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bandDownX = x
                bandLastX = x
                bandDragging = false
                if (y < bandTop + SEARCH_DP * d && x > viewWidth - (SIDE_DP + 44) * d) {
                    tick()
                    if (host.query.isEmpty()) host.endSearch() else host.clearQuery()
                    view.invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!bandDragging && abs(x - bandDownX) > SLOP_DP * d) bandDragging = true
                if (bandDragging) {
                    bandScroll -= x - bandLastX
                    view.invalidate()
                }
                bandLastX = x
            }
            MotionEvent.ACTION_UP -> {
                if (!bandDragging && y >= bandTop + SEARCH_DP * d) {
                    val size = (BAND_DP - SEARCH_DP) * d
                    val i = ((x + bandScroll - SIDE_DP * d) / size).toInt()
                    results().getOrNull(i)?.let { item ->
                        tick()
                        host.typeEmoji(if (results === frequent) item.emoji else host.usage.shown(item))
                    }
                }
                bandDragging = false
            }
        }
        return true
    }

    companion object {
        /** Apple's four rows of big emoji (Boss, 2026-10-20, with his iPhone's for reference). */
        const val ROWS = 4

        /** The search field's height, the section titles' and the bar's, dp. */
        const val SEARCH_DP = 58f

        /** The search pill: its height, and its margins from the board's top and sides. */
        const val FIELD_DP = 40f
        const val FIELD_TOP_DP = 10f
        const val FIELD_SIDE_DP = 12f

        /** The bar's section icons. */
        const val ICON_DP = 24f
        const val BAR_DP = 44f

        /** The search band over the letters: the field and a row of results, dp. */
        const val BAND_DP = 112f

        /** Side margin, and the touch width of ABC and ⌫ in the bar, dp. */
        const val SIDE_DP = 6f
        const val SIDE_BUTTON_DP = 56f

        /** How much of a cell an emoji fills, and the gap between sections, in cells. */
        const val GLYPH = 0.72f
        const val GAP_CELLS = 0.15f
        const val SLOP_DP = 8f
        const val LONG_PRESS_MS = 380L

        /** The sections' icons: Frequently Used, then Apple's eight. */
        val ICONS = listOf("🕘", "😀", "🐻", "🍔", "⚽", "🚗", "💡", "🔣", "🏳️")
    }
}
