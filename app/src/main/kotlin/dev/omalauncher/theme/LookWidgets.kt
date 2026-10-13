package dev.omalauncher.theme

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.Log
import android.widget.RemoteViews
import dev.omalauncher.App
import dev.omalauncher.R
import dev.omalauncher.assist.OmaClockWidget
import dev.omalauncher.assist.OmaLockWidget
import dev.omalauncher.theme.pack.Dressing
import dev.omalauncher.theme.pack.FontGeneric
import dev.omalauncher.theme.pack.PackFont
import dev.omalauncher.theme.pack.ThemePack
import dev.omalauncher.ui.ActivePack
import java.util.Date
import java.util.Locale

/**
 * The launcher's widgets for the lock screen (and Samsung's home), drawn as pictures in the look in use: the
 * look's own typefaces and colors, which a widget's ordinary text can't have. They are drawn again when the
 * look changes, when what they say changes, and the clock at every minute and whenever the screen comes on.
 * The minute comes from Android only while this process lives; the launcher is the home app and stays, and a
 * clock that was drawn before it died is put right as soon as it is back.
 */
object LookWidgets {
    private const val TAG = "look-widgets"
    private const val PACKS = "packs"

    /** A widget's picture is drawn for this many pixels to the dp at most, so it stays within what a widget may send. */
    private const val MAX_SIDE = 1400

    private var watching = false

    /** Starts keeping the widgets current. Once, in the app's process. */
    fun start(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        val changes = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh(app, clockOnly = true)
        }, changes)
    }

    /** Draws every placed copy again; with [clockOnly], only the clocks. Does nothing where none is placed. */
    fun refresh(
        context: Context,
        clockOnly: Boolean = false,
    ) {
        runCatching {
            val app = context.applicationContext
            // The look is the runtime's to load; asking for it here loads it in a process started for a widget alone.
            (app as App).runtime
            val pack = ActivePack.pack
            val manager = AppWidgetManager.getInstance(app)
            for (id in manager.getAppWidgetIds(ComponentName(app, OmaClockWidget::class.java))) {
                val (w, h) = size(app, manager, id, 260, 110)
                manager.updateAppWidget(id, picture(app, clock(app, pack, w, h, Date()), OmaClockWidget.tap(app)))
            }
            if (clockOnly) return
            for (id in manager.getAppWidgetIds(ComponentName(app, OmaLockWidget::class.java))) {
                val (w, h) = size(app, manager, id, 180, 70)
                manager.updateAppWidget(id, picture(app, status(app, pack, w, h, OmaLockWidget.state()), OmaLockWidget.tap(app)))
            }
        }.onFailure { Log.w(TAG, "widgets not drawn", it) }
    }

    private fun picture(
        context: Context,
        bitmap: Bitmap,
        tap: android.app.PendingIntent,
    ) = RemoteViews(context.packageName, R.layout.oma_image_widget).apply {
        setImageViewBitmap(R.id.oma_widget_image, bitmap)
        setOnClickPendingIntent(R.id.oma_widget_image, tap)
    }

    /** The widget [id]'s size in pixels, from what its host says it gives it, or [width] by [height] dp if it says nothing. */
    private fun size(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val options = manager.getAppWidgetOptions(id)
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: width
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: height
        return fit(w, h, context.resources.displayMetrics.density)
    }

    /** [widthDp] by [heightDp] in pixels at [density], made smaller in one piece if either side would pass [MAX_SIDE]. */
    internal fun fit(
        widthDp: Int,
        heightDp: Int,
        density: Float,
    ): Pair<Int, Int> {
        val w = (widthDp * density).coerceAtLeast(1f)
        val h = (heightDp * density).coerceAtLeast(1f)
        val shrink = minOf(1f, MAX_SIDE / maxOf(w, h))
        return (w * shrink).toInt().coerceAtLeast(1) to (h * shrink).toInt().coerceAtLeast(1)
    }

    /** The time as the phone tells it: by the owner's 12 or 24 hour choice, with no am or pm. */
    internal fun timeText(
        now: Date,
        twentyFour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = java.text.SimpleDateFormat(if (twentyFour) "HH:mm" else "h:mm", locale).format(now)

    internal fun dateText(
        now: Date,
        locale: Locale = Locale.getDefault(),
    ): String = java.text.SimpleDateFormat("EEE, MMMM d", locale).format(now)

    /** A role's typeface: the pack's own font file at its weight, or the system family it names. */
    private fun typeface(
        context: Context,
        pack: ThemePack,
        font: PackFont,
    ): Typeface {
        val family =
            when (font.family) {
                FontGeneric.Monospace -> Typeface.MONOSPACE
                FontGeneric.Serif -> Typeface.SERIF
                FontGeneric.Cursive -> Typeface.create("cursive", Typeface.NORMAL)
                FontGeneric.SansSerif -> Typeface.SANS_SERIF
            }
        val file = font.file.takeIf { it.isNotEmpty() && pack.id != "console" }
        val base =
            file?.let {
                runCatching { Typeface.Builder(context.assets, "$PACKS/${pack.id}/$it").setFontVariationSettings("'wght' ${font.weight}").build() }
                    .recoverCatching { _ -> Typeface.createFromAsset(context.assets, "$PACKS/${pack.id}/$it") }
                    .getOrNull()
            } ?: family
        return Typeface.create(base, font.weight.coerceIn(100, 900), font.italic)
    }

    private fun paint(
        context: Context,
        pack: ThemePack,
        font: PackFont,
        size: Float,
        color: Int,
    ) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(context, pack, font)
        textSize = size * font.scale
        letterSpacing = font.tracking
        this.color = color
    }

    private fun spelled(
        text: String,
        font: PackFont,
    ) = if (font.uppercase) text.uppercase() else text

    /** Text of [paint] made no wider than [room], by drawing it smaller. */
    private fun fitted(
        paint: Paint,
        text: String,
        room: Float,
    ) {
        val wide = paint.measureText(text)
        if (wide > room && wide > 0f) paint.textSize *= room / wide
    }

    /**
     * The clock: the date in the look's small capitals over the time in its display face, in the ink the look
     * writes on its page with, on nothing. It stands on the lock screen's wallpaper, which is the look's paper.
     */
    private fun clock(
        context: Context,
        pack: ThemePack,
        width: Int,
        height: Int,
        now: Date,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = pack.palette.onPage.argb
        val date = spelled(dateText(now), pack.type.mono)
        val time = timeText(now, DateFormat.is24HourFormat(context))
        val small = paint(context, pack, pack.type.mono, height * 0.16f, ink)
        val large = paint(context, pack, pack.type.display, height * 0.62f, ink)
        fitted(small, date, width * 0.96f)
        fitted(large, time, width * 0.96f)
        val shadow = pack.page.textShadow
        if (shadow.visible) for (p in listOf(small, large)) p.setShadowLayer(shadow.blur.coerceAtLeast(0.01f), shadow.x, shadow.y, shadow.color.argb)
        val top = height * 0.06f - small.fontMetrics.ascent
        canvas.drawText(date, 0f, top, small)
        canvas.drawText(time, 0f, top + small.fontMetrics.descent + height * 0.04f - large.fontMetrics.ascent * 0.86f, large)
        return bitmap
    }

    /** Oma's state as a scrap of the look: its fill, border, shadow and tape, "OMA" in small capitals over the state in the display face. */
    private fun status(
        context: Context,
        pack: ThemePack,
        width: Int,
        height: Int,
        state: String,
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val surface = pack.scrap.surface
        val shadow = surface.shadow
        val dx = if (shadow.visible && !shadow.inset) shadow.x * density else 0f
        val dy = if (shadow.visible && !shadow.inset) shadow.y * density else 0f
        val tape = pack.scrap.tape
        val taped = tape.style != Dressing.None
        val edge = surface.border * density / 2f + 1f
        // Room is left for the shadow at the side it falls to, and for the tape that sticks out over the top.
        val box = RectF(edge + maxOf(0f, -dx), edge + maxOf(0f, -dy) + (if (taped) tape.height * density / 2f else 0f), width - edge - maxOf(0f, dx), height - edge - maxOf(0f, dy))
        val radius = pack.shape.radius * density
        if (dx != 0f || dy != 0f) canvas.drawRoundRect(RectF(box).apply { offset(dx, dy) }, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shadow.color.argb })
        canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface.fill.argb })
        if (surface.border > 0f) canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = surface.border * density; color = surface.borderColor.argb })
        if (taped) {
            canvas.save()
            canvas.rotate(tape.angle, box.centerX(), box.top)
            canvas.drawRect(box.centerX() - tape.width * density / 2f, box.top - tape.height * density / 2f, box.centerX() + tape.width * density / 2f, box.top + tape.height * density / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tape.color.withAlpha(tape.opacity).argb })
            canvas.restore()
        }
        val pad = box.height() * 0.14f
        val room = box.width() - pad * 2f
        val caption = paint(context, pack, pack.type.mono, box.height() * 0.17f, pack.scrap.muted.argb)
        val line = paint(context, pack, pack.type.display, box.height() * 0.36f, surface.ink.argb)
        val name = spelled("oma", pack.type.mono)
        fitted(caption, name, room)
        fitted(line, state, room)
        val top = box.top + pad - caption.fontMetrics.ascent
        canvas.drawText(name, box.left + pad, top, caption)
        canvas.drawText(state, box.left + pad, top + caption.fontMetrics.descent + box.height() * 0.06f - line.fontMetrics.ascent * 0.9f, line)
        return bitmap
    }
}
