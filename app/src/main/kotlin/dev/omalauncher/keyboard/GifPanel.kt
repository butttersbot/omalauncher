package dev.omalauncher.keyboard

import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import dev.omalauncher.keyboard.KeyboardView.Companion.blend
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The keyboard's GIFs (Boss, 2026-10-21), drawn in the keys' place as the emoji picker is: ABC and a search pill at
 * the top, a row of pills (Trending, Recent, Saved, and moods), and the GIFs in columns that scroll up and down,
 * playing. A heart on each KLIPY one keeps it under Saved, with the GIFs saved on the phone. A tap places the GIF in
 * the app's message box, inline; the app's own send sends it.
 *
 * Searching, the letters come back with a band above them ([drawBand]): the field, and what it finds in columns
 * that scroll up and down, his own first. Nothing of it is held once the panel is put away ([closed]).
 */
class GifPanel(
    private val view: View,
    private val host: Host,
) {
    interface Host {
        fun placeGif(item: GifItem)

        fun startGifSearch()

        fun endGifSearch()

        fun clearGifQuery()

        /** What has been typed into the GIF search. */
        val gifQuery: String

        fun showLetters()

        val haptics: Boolean

        /** The inner screen: more columns. */
        val wide: Boolean

        /** A stable id for this phone, which KLIPY asks for with each search. */
        val customerId: String
    }

    private val context = view.context
    private val density = view.resources.displayMetrics.density
    private val store = GifStore.of(context)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val images = Images()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val clip = Path()

    /** What a feed shows: one of the pills, or a search's words. */
    private sealed class Req {
        object Trending : Req()

        object Recent : Req()

        object Saved : Req()

        data class Words(val q: String) : Req()
    }

    /** A list of GIFs being shown and paged: the panel's, and the search band's. */
    private inner class Feed {
        var items: List<GifItem> = emptyList()
        var next: String? = null
        var loading = false
        var note: String? = null
        var scroll = 0f
        var maxScroll = 0f
        private var gen = 0
        private var req: Req? = null

        fun open(r: Req) {
            if (r == req && items.isNotEmpty()) return
            gen++
            req = r
            items = emptyList()
            next = null
            scroll = 0f
            note = null
            fetch(first = true)
        }

        fun more() {
            if (!loading && next != null) fetch(first = false)
        }

        private fun fetch(first: Boolean) {
            val g = gen
            val r = req ?: return
            val pos = if (first) null else next
            loading = true
            worker.execute {
                val (list, nx, empty) = produce(r, pos, first)
                main.post {
                    if (g != gen) return@post
                    items = if (first) list else items + list.filter { n -> items.none { it.id == n.id } }
                    next = nx
                    loading = false
                    note = if (items.isEmpty()) empty else null
                    view.invalidate()
                }
            }
            view.invalidate()
        }
    }

    private val feed = Feed()
    private val band = Feed()

    /** The pill showing: Trending, Recent, Saved, or a mood. */
    var chip = CHIPS[0]
        private set

    /** What each feed produces: the GIFs, where the next page starts, and what to say if there are none. */
    private fun produce(
        r: Req,
        pos: String?,
        first: Boolean,
    ): Triple<List<GifItem>, String?, String> {
        val key = Klipy.key(context.filesDir)
        val noKey = "Put a KLIPY key in ~/.config/omalauncher/klipy.txt"
        return when (r) {
            Req.Recent -> Triple(store.recent(), null, "GIFs you place show here")
            Req.Saved -> Triple(store.hearted() + GifLocal.all(context), null, "Heart a GIF to keep it here, with the GIFs saved on your phone")
            Req.Trending -> {
                val page = key?.let { Klipy.fetch(it, null, pos, host.customerId) }
                Triple(page?.items.orEmpty(), page?.next, if (key == null) noKey else "KLIPY didn't answer")
            }
            is Req.Words -> {
                // His own first: the ones he hearted whose title has the words, then the phone's, then KLIPY's.
                val mine =
                    if (first) {
                        val w = r.q.lowercase()
                        store.hearted().filter { w in it.title.lowercase() } + GifLocal.search(context, r.q)
                    } else {
                        emptyList()
                    }
                val page = key?.let { Klipy.fetch(it, r.q, pos, host.customerId) }
                val all = (mine + page?.items.orEmpty()).distinctBy { it.id }
                Triple(all, page?.next, if (key == null && mine.isEmpty()) noKey else "No GIFs for “${r.q}”")
            }
        }
    }

    /** As the panel comes up: Trending, from the top. */
    fun opened() {
        chip = CHIPS[0]
        feed.open(Req.Trending)
    }

    /** The full panel showing what [q] finds, as a search ends with Done. */
    fun showResults(q: String) {
        chip = ""
        feed.open(Req.Words(q))
    }

    /** As the panel goes away: the GIFs' pictures let go. */
    fun closed() {
        images.clear()
    }

    /** The search's words changed: what they find, once the typing rests a moment. */
    fun queryChanged(q: String) {
        main.removeCallbacks(searchSoon)
        main.postDelayed(searchSoon, if (q.isBlank()) 0 else SEARCH_PAUSE_MS)
    }

    private val searchSoon = Runnable { val q = host.gifQuery.trim(); band.open(if (q.isEmpty()) Req.Trending else Req.Words(q)) }

    // ---- Drawing ----

    private class Colors(val ink: Int, val muted: Int, val tile: Int, val field: Int, val lit: Int)

    private fun colors(k: KeyboardLook): Colors {
        val ink = if (k.letter != null) k.pageInk else k.ink
        val page = if (k.letter != null) k.page else k.board
        return Colors(ink, blend(ink, page, 0.45f), blend(page, ink, 0.1f), k.key, k.ret)
    }

    private var top = 0f
    private var width = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private val chipBoxes = ArrayList<Pair<String, RectF>>()
    private var cells: List<Pair<GifItem, RectF>> = emptyList()
    private var bandTop = 0f
    private var bandCells: List<Pair<GifItem, RectF>> = emptyList()

    fun draw(
        canvas: Canvas,
        top: Float,
        width: Float,
        height: Float,
        k: KeyboardLook,
    ) {
        val d = density
        val c = colors(k)
        this.top = top
        this.width = width
        text.typeface = k.typeface

        // The search pill, as the emoji search's (Boss, 2026-10-21: no ABC beside it; the GIF button goes back).
        field(canvas, top, width, null, c, k)

        // The pills: as many as fit.
        val chipCy = top + (HEAD_DP + CHIPS_DP / 2) * d
        chipBoxes.clear()
        var x = 10 * d
        text.textSize = 13 * d
        for (name in CHIPS) {
            val w = text.measureText(name) + 22 * d
            if (x + w > width - 10 * d) break
            val r = RectF(x, chipCy - 13 * d, x + w, chipCy + 13 * d)
            val on = name == chip
            if (on) {
                paint.color = c.ink
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            } else {
                pill(canvas, r, c.field, c.ink)
            }
            text.color = if (on) c.field else c.ink
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(name, r.centerX(), chipCy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2, text)
            chipBoxes += name to r
            x += w + 6 * d
        }

        // The GIFs.
        gridTop = top + (HEAD_DP + CHIPS_DP + 4) * d
        gridBottom = top + height - CREDIT_DP * d
        cells = grid(canvas, feed, gridTop, gridBottom, width, if (host.wide) 3 else 2, c)

        // Who they come from, as KLIPY asks.
        text.textSize = 10 * d
        text.color = c.muted
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(if (chip == "Saved") "SAVED · HEARTED AND ON YOUR PHONE" else "YOURS FIRST · POWERED BY KLIPY", width / 2, gridBottom + CREDIT_DP * d * 0.7f, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** The band over the letters while searching: the field, then what it finds. */
    fun drawBand(
        canvas: Canvas,
        top: Float,
        width: Float,
        k: KeyboardLook,
    ) {
        val d = density
        val c = colors(k)
        bandTop = top
        this.width = width
        text.typeface = k.typeface
        field(canvas, top, width, host.gifQuery, c, k)
        bandCells = grid(canvas, band, top + FIELD_DP * d, top + BAND_DP * d - 4 * d, width, if (host.wide) 5 else 3, c)
    }

    /** [f]'s GIFs in [columns] between [y0] and [y1], scrolled; the cells drawn, for taps. */
    private fun grid(
        canvas: Canvas,
        f: Feed,
        y0: Float,
        y1: Float,
        width: Float,
        columns: Int,
        c: Colors,
    ): List<Pair<GifItem, RectF>> {
        val d = density
        val pad = 10 * d
        val gap = 6 * d
        val colW = (width - 2 * pad - (columns - 1) * gap) / columns
        val ratios = f.items.map { images.ratio(it.preview) ?: it.ratio }
        val (laid, total) = Masonry.lay(ratios, columns, colW, gap)
        f.maxScroll = (total - (y1 - y0) + 8 * d).coerceAtLeast(0f)
        if (scroller.computeScrollOffset() && scrolling === f) {
            f.scroll = scroller.currY.toFloat()
            view.postInvalidateOnAnimation()
        }
        f.scroll = f.scroll.coerceIn(0f, f.maxScroll)
        // Near the end: the next page.
        if (f.scroll > f.maxScroll - (y1 - y0)) f.more()
        val out = ArrayList<Pair<GifItem, RectF>>()
        canvas.save()
        canvas.clipRect(0f, y0, width, y1)
        if (f.items.isEmpty()) {
            text.textSize = 13 * d
            text.color = c.muted
            text.textAlign = Paint.Align.CENTER
            val say = if (f.loading) "…" else f.note.orEmpty()
            val line = TextUtils.ellipsize(say, text, width - 2 * pad, TextUtils.TruncateAt.END)
            canvas.drawText(line, 0, line.length, width / 2, (y0 + y1) / 2, text)
            text.textAlign = Paint.Align.LEFT
        }
        val r = 6 * d
        for (cell in laid) {
            val item = f.items[cell.index]
            val x = pad + cell.column * (colW + gap)
            val y = y0 + cell.top - f.scroll
            if (y + cell.height < y0 || y > y1) continue
            val rect = RectF(x, y, x + colW, y + cell.height)
            out += item to rect
            paint.color = c.tile
            canvas.drawRoundRect(rect, r, r, paint)
            images.get(item.preview)?.let { dr ->
                canvas.save()
                clip.reset()
                clip.addRoundRect(rect, r, r, Path.Direction.CW)
                canvas.clipPath(clip)
                dr.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                dr.draw(canvas)
                canvas.restore()
            }
            if (item.local) {
                badge(canvas, rect, c)
            } else {
                heart(canvas, rect, store.isHearted(item.id), c)
            }
        }
        canvas.restore()
        return out
    }

    /**
     * The search pill, drawn as the emoji search's is (EmojiPicker.drawField): filled, no outline, [FIELD_SIDE_DP]
     * from the edges and [FIELD_TOP_DP] down, a drawn magnifying glass. [query] null: the panel's, a tap to search;
     * otherwise the band's field, with its caret and ✕.
     */
    private fun field(
        canvas: Canvas,
        y: Float,
        width: Float,
        query: String?,
        c: Colors,
        k: KeyboardLook,
    ) {
        val d = density
        box.set(FIELD_SIDE_DP * d, y + FIELD_TOP_DP * d, width - FIELD_SIDE_DP * d, y + (FIELD_TOP_DP + FIELD_H_DP) * d)
        paint.color = c.field
        canvas.drawRoundRect(box, FIELD_H_DP / 2 * d, FIELD_H_DP / 2 * d, paint)
        val cy = box.centerY()
        stroke.color = c.muted
        stroke.strokeWidth = 1.8f * d
        val gx = box.left + 16 * d
        canvas.drawCircle(gx, cy - 1.5f * d, 5 * d, stroke)
        canvas.drawLine(gx + 3.6f * d, cy + 2.1f * d, gx + 7.5f * d, cy + 6 * d, stroke)
        text.textAlign = Paint.Align.LEFT
        text.typeface = k.typeface
        text.textSize = 15 * d
        val ty = cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
        val tx = gx + 14 * d
        if (query.isNullOrEmpty()) {
            text.color = c.muted
            canvas.drawText("Search GIFs", tx, ty, text)
        } else {
            text.color = c.ink
            canvas.drawText(query, tx, ty, text)
        }
        if (query != null) {
            if (SystemClock.uptimeMillis() / 500 % 2 == 0L) {
                val cx = tx + (if (query.isEmpty()) 0f else text.measureText(query) + 1.5f * d)
                paint.color = c.lit
                canvas.drawRect(cx, cy - 9 * d, cx + 2 * d, cy + 9 * d, paint)
            }
            view.postInvalidateDelayed(500)
            // ✕: clears the words, or with none, ends the search.
            val xx = box.right - 16 * d
            stroke.color = c.muted
            canvas.drawLine(xx - 5 * d, cy - 5 * d, xx + 5 * d, cy + 5 * d, stroke)
            canvas.drawLine(xx - 5 * d, cy + 5 * d, xx + 5 * d, cy - 5 * d, stroke)
        }
    }

    private fun pill(
        canvas: Canvas,
        r: RectF,
        fill: Int,
        ink: Int,
    ) {
        paint.color = fill
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
        stroke.color = ink
        stroke.strokeWidth = 1.4f * density
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
    }

    /** The heart in a KLIPY GIF's top right corner: filled red once he has hearted it. */
    private fun heart(
        canvas: Canvas,
        rect: RectF,
        on: Boolean,
        c: Colors,
    ) {
        val d = density
        val cx = rect.right - HEART_DP / 2 * d - 3 * d
        val cy = rect.top + HEART_DP / 2 * d + 3 * d
        paint.color = if (on) RED else 0xE6FDFDFB.toInt()
        canvas.drawCircle(cx, cy, HEART_DP / 2 * d, paint)
        text.textSize = 15 * d
        text.color = if (on) 0xFFFFFFFF.toInt() else 0xFF111113.toInt()
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(if (on) "♥" else "♡", cx, cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** "SAVED" on one of the phone's own GIFs. */
    private fun badge(
        canvas: Canvas,
        rect: RectF,
        c: Colors,
    ) {
        val d = density
        text.textSize = 9 * d
        val w = text.measureText("SAVED") + 8 * d
        box.set(rect.left + 4 * d, rect.top + 4 * d, rect.left + 4 * d + w, rect.top + 18 * d)
        paint.color = 0xE6FDFDFB.toInt()
        canvas.drawRect(box, paint)
        text.color = 0xFF111113.toInt()
        canvas.drawText("SAVED", box.left + 4 * d, box.bottom - 4 * d, text)
    }

    // ---- Touch ----

    private val scroller = OverScroller(context)
    private var scrolling: Feed? = null
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    /** A touch on the panel; [e]'s coordinates are the view's, px. */
    fun touch(e: MotionEvent): Boolean = touchIn(e, feed, gridTop, gridBottom, cells, inBand = false)

    /** A touch on the band while searching. */
    fun touchBand(e: MotionEvent): Boolean = touchIn(e, band, bandTop + FIELD_DP * density, bandTop + BAND_DP * density, bandCells, inBand = true)

    private fun touchIn(
        e: MotionEvent,
        f: Feed,
        y0: Float,
        y1: Float,
        shown: List<Pair<GifItem, RectF>>,
        inBand: Boolean,
    ): Boolean {
        val x = e.x
        val y = e.y
        val d = density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x
                downY = y
                lastY = y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!dragging && y >= y0 && abs(y - downY) > SLOP_DP * d) dragging = true
                if (dragging) {
                    f.scroll = (f.scroll - (y - lastY)).coerceIn(0f, f.maxScroll)
                    view.invalidate()
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    scrolling = f
                    scroller.fling(0, f.scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, f.maxScroll.toInt())
                    view.postInvalidateOnAnimation()
                } else if (abs(x - downX) < SLOP_DP * d) {
                    tap(x, y, y0, shown, inBand)
                }
                velocity?.recycle()
                velocity = null
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
                dragging = false
            }
        }
        return true
    }

    private fun tap(
        x: Float,
        y: Float,
        y0: Float,
        shown: List<Pair<GifItem, RectF>>,
        inBand: Boolean,
    ) {
        val d = density
        if (y < y0) {
            if (inBand) {
                // The field: ✕ clears, or with nothing typed, ends the search.
                if (x > width - 50 * d) {
                    tick()
                    if (host.gifQuery.isEmpty()) host.endGifSearch() else host.clearGifQuery()
                }
                return
            }
            if (y < top + HEAD_DP * d) {
                tick()
                host.startGifSearch()
                return
            }
            chipBoxes.firstOrNull { it.second.contains(x, y) }?.let { (name, _) ->
                tick()
                pick(name)
            }
            return
        }
        val (item, rect) = shown.firstOrNull { it.second.contains(x, y) } ?: return
        tick()
        val inHeart = !item.local && x > rect.right - (HEART_DP + 10) * d && y < rect.top + (HEART_DP + 10) * d
        if (inHeart) {
            store.toggleHeart(item)
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            view.invalidate()
        } else {
            store.placed(item)
            host.placeGif(item)
        }
    }

    private fun pick(name: String) {
        chip = name
        feed.open(
            when (name) {
                "Trending" -> Req.Trending
                "Recent" -> Req.Recent
                "Saved" -> Req.Saved
                else -> Req.Words(name)
            },
        )
        view.invalidate()
    }

    private fun tick() {
        if (host.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * The GIFs' small copies, read off the main thread and played as they show: from KLIPY (kept on disk a while) or
     * the phone's own. Only the last [KEPT] stay in memory, and none once the panel is put away.
     */
    private inner class Images {
        private val pool = Executors.newFixedThreadPool(3)
        private val pending = HashSet<String>()
        private val ratios = HashMap<String, Float>()
        private val cache =
            object : LruCache<String, Drawable>(KEPT) {
                override fun entryRemoved(
                    evicted: Boolean,
                    key: String,
                    old: Drawable,
                    new: Drawable?,
                ) {
                    (old as? AnimatedImageDrawable)?.stop()
                    old.callback = null
                }
            }
        private val callback =
            object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = view.postInvalidateOnAnimation()

                override fun scheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                    `when`: Long,
                ) {
                    view.postDelayed(what, `when` - SystemClock.uptimeMillis())
                }

                override fun unscheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                ) {
                    view.removeCallbacks(what)
                }
            }

        fun ratio(src: String) = ratios[src]

        fun get(src: String): Drawable? {
            cache.get(src)?.let { return it }
            if (pending.add(src)) {
                pool.execute {
                    val dr = runCatching { decode(src) }.getOrNull()
                    main.post {
                        pending.remove(src)
                        if (dr != null) {
                            dr.callback = callback
                            (dr as? AnimatedImageDrawable)?.apply {
                                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                start()
                            }
                            if (dr.intrinsicHeight > 0) ratios[src] = dr.intrinsicWidth.toFloat() / dr.intrinsicHeight
                            cache.put(src, dr)
                            view.invalidate()
                        }
                    }
                }
            }
            return null
        }

        fun clear() {
            cache.evictAll()
        }

        private fun decode(src: String): Drawable {
            val bytes = if (src.startsWith("content:")) context.contentResolver.openInputStream(Uri.parse(src))!!.use { it.readBytes() } else fetch(src)
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                // Small: these play in a grid, many at once.
                val w = info.size.width
                if (w > MAX_SIDE) decoder.setTargetSize(MAX_SIDE, (info.size.height * MAX_SIDE.toFloat() / w).toInt().coerceAtLeast(1))
            }
        }

        /** A KLIPY preview: from the disk cache, or fetched and kept there. */
        private fun fetch(url: String): ByteArray {
            val dir = File(context.cacheDir, "gifs/previews").apply { mkdirs() }
            val f = File(dir, Integer.toHexString(url.hashCode()) + ".gif")
            if (f.length() > 0) return f.readBytes()
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4_000
            c.readTimeout = 8_000
            val b = c.inputStream.use { it.readBytes() }
            runCatching { f.writeBytes(b) }
            // A few hundred previews at most on disk: the oldest go.
            dir.listFiles()?.takeIf { it.size > 400 }?.sortedBy { it.lastModified() }?.take(100)?.forEach { it.delete() }
            return b
        }
    }

    companion object {
        val CHIPS = listOf("Trending", "Recent", "Saved", "lol", "yes", "no", "love", "hype", "facepalm", "gn")
        /** The search pill's row, its pill and margins: the emoji search's (EmojiPicker's SEARCH_DP and FIELD_*). */
        const val HEAD_DP = 58f
        const val FIELD_H_DP = 40f
        const val FIELD_TOP_DP = 10f
        const val FIELD_SIDE_DP = 12f
        const val CHIPS_DP = 34f
        const val CREDIT_DP = 18f
        const val FIELD_DP = 58f

        /** The band over the letters while searching: the field and about two rows of GIFs. */
        const val BAND_DP = 232f
        const val HEART_DP = 26f
        const val SLOP_DP = 8f
        const val SEARCH_PAUSE_MS = 300L
        const val KEPT = 40
        const val MAX_SIDE = 260
        const val RED = 0xFFD7262D.toInt()
    }
}
