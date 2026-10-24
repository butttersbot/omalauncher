package dev.omalauncher.record

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Soma's own screen recorder (Boss, 2026-10-21: "lets build our own screen record button and make it available as a
 * quick control"; Samsung's had been turned off, and stops at the lock screen). A Quick Settings tile starts it:
 * Android asks to capture the screen, as it does for every recording, a short countdown runs in the notification,
 * and the screen is recorded, without sound, into Movies/Soma until the tile or the notification's Stop is tapped.
 */
object ScreenRecord {
    const val CHANNEL = "screen-record"
    const val NOTIFICATION = 0x5ec0

    /** Seconds between his yes and the first frame, so the permission dialog is gone from the video. */
    const val COUNTDOWN = 3

    /** The longer side of the video at most, in pixels: what the phone's encoders take on every screen. */
    const val LONGEST = 1920

    @Volatile var recording = false
        internal set

    @Volatile var counting = false
        internal set

    fun refreshTile(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, ScreenRecordTile::class.java)) }
    }

    /** The video's size for a [width] × [height] screen: its shape kept, no side over [LONGEST], both even. */
    fun videoSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, LONGEST.toDouble() / maxOf(width, height))
        fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
    }
}

/** The tile: off, counting down, or recording; a tap starts or stops. */
class ScreenRecordTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        if (ScreenRecord.recording || ScreenRecord.counting) {
            startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.STOP))
            return
        }
        val ask = Intent(this, ScreenRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, ask, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(ask)
        }
    }

    private fun show() {
        qsTile?.apply {
            label = "Screen record"
            subtitle = when {
                ScreenRecord.recording -> "Recording · tap to stop"
                ScreenRecord.counting -> "Starting…"
                else -> "Tap to record"
            }
            state = if (ScreenRecord.recording || ScreenRecord.counting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}

/** Asks Android for the screen, invisibly, and hands his answer to the service. */
class ScreenRecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pm = getSystemService(MediaProjectionManager::class.java)
        // The whole screen, not one app: Android 14's prompt otherwise opens on "A single app" (Boss, 2026-10-22:
        // "its asking to share an app").
        val ask = if (Build.VERSION.SDK_INT >= 34) pm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else pm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(ask, ASK)
    }

    @Deprecated("The platform's own result call")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ASK && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.START)
                    .putExtra(ScreenRecordService.CODE, resultCode).putExtra(ScreenRecordService.DATA, data)
                    .putExtra(ScreenRecordService.DISPLAY, if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: Display.DEFAULT_DISPLAY else Display.DEFAULT_DISPLAY),
            )
        }
        finish()
    }

    private companion object {
        const val ASK = 7
    }
}

/** Holds the capture while it records: a foreground service of the kind Android requires for one. */
class ScreenRecordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtual: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var out: Uri? = null
    private var file: ParcelFileDescriptor? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> begin(intent)
            STOP -> finish(save = true)
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (ScreenRecord.recording || ScreenRecord.counting) return
        ScreenRecord.channel(this)
        // A capture's service must be in the foreground, as a capture, before the capture is taken.
        val first = note("Screen recording starts in ${ScreenRecord.COUNTDOWN}…", recording = false)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ScreenRecord.NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(ScreenRecord.NOTIFICATION, first)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(DATA)
        val code = intent.getIntExtra(CODE, Activity.RESULT_CANCELED)
        val p = data?.let { runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }.getOrNull() }
        if (p == null) return finish(save = false)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            // Ended from outside: the system's own stop, or another app taking the screen.
            override fun onStop() {
                main.post { finish(save = true) }
            }
        }, main)
        ScreenRecord.counting = true
        ScreenRecord.refreshTile(this)
        val display = intent.getIntExtra(DISPLAY, Display.DEFAULT_DISPLAY)
        for (left in ScreenRecord.COUNTDOWN downTo 1) {
            main.postDelayed({
                if (ScreenRecord.counting) {
                    notify(note("Screen recording starts in $left…", recording = false))
                    RecordPill.countdown(left)
                }
            }, (ScreenRecord.COUNTDOWN - left) * 1000L)
        }
        main.postDelayed({ if (ScreenRecord.counting) record(display) }, ScreenRecord.COUNTDOWN * 1000L)
    }

    private fun record(displayId: Int) {
        ScreenRecord.counting = false
        // The countdown off the screen before the first frame is taken.
        RecordPill.hide()
        val p = projection ?: return finish(save = false)
        val shown = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val ctx = createDisplayContext(shown)
        val (sw, sh) = if (Build.VERSION.SDK_INT >= 30) {
            ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            android.util.DisplayMetrics().also { @Suppress("DEPRECATION") shown.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        }
        val (w, h) = ScreenRecord.videoSize(sw, sh)
        val dpi = ctx.resources.displayMetrics.densityDpi
        val ok = runCatching {
            val name = "Soma " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Soma")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("No place for the video")
            out = uri
            val fd = contentResolver.openFileDescriptor(uri, "w") ?: error("The video can't be written")
            file = fd
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h)
            r.setVideoFrameRate(60)
            r.setVideoEncodingBitRate(maxOf(6_000_000, w * h * 6))
            r.setOutputFile(fd.fileDescriptor)
            r.prepare()
            recorder = r
            virtual = p.createVirtualDisplay("Soma screen record", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            r.start()
            startedAt = System.currentTimeMillis()
        }.isSuccess
        if (!ok) return finish(save = false)
        ScreenRecord.recording = true
        ScreenRecord.refreshTile(this)
        // Nothing of ours over the screen while it records (Boss, 2026-10-22: the stop only at the end, where it can be
        // cropped): a short buzz says it has started, and the notification in the shade holds the time and Stop.
        notify(note("Recording · tap to stop", recording = true))
        buzz()
    }

    private fun finish(save: Boolean) {
        main.removeCallbacksAndMessages(null)
        RecordPill.hide()
        val was = ScreenRecord.recording
        ScreenRecord.counting = false
        ScreenRecord.recording = false
        val stopped = runCatching { recorder?.stop() }.isSuccess && was
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtual?.release() }
        virtual = null
        val p = projection
        projection = null
        runCatching { p?.stop() }
        runCatching { file?.close() }
        file = null
        val uri = out
        out = null
        if (uri != null) {
            if (save && stopped) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
            }
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        if (uri != null && save && stopped) saved(uri)
        ScreenRecord.refreshTile(this)
        stopSelf()
    }

    /** "Saved", with a tap that opens the video. */
    private fun saved(uri: Uri) {
        val secs = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = Notification.Builder(this, ScreenRecord.CHANNEL)
            .setSmallIcon(dev.omalauncher.R.drawable.ic_screen_record_tile)
            .setContentTitle("Screen recording saved")
            .setContentText("${secs / 60}:${"%02d".format(secs % 60)} · Movies/Soma")
            .setContentIntent(PendingIntent.getActivity(this, 1, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ScreenRecord.NOTIFICATION + 1, n)
    }

    private fun note(text: String, recording: Boolean): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, ScreenRecordService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, ScreenRecord.CHANNEL)
            .setSmallIcon(dev.omalauncher.R.drawable.ic_screen_record_tile)
            .setContentTitle(text)
            .setOngoing(true)
            // Public: while the screen is being recorded Android hides private notifications' content, and this one's
            // time and Stop with it (the 137 walk on Android 17). Nothing in it is private.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setUsesChronometer(recording)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, if (recording) "Stop" else "Cancel", stop).build())
            .build()
    }

    private fun buzz() {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") getSystemService(android.os.Vibrator::class.java)
            v.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun notify(n: Notification) = getSystemService(NotificationManager::class.java).notify(ScreenRecord.NOTIFICATION, n)

    override fun onDestroy() {
        if (ScreenRecord.recording || ScreenRecord.counting) finish(save = true)
        super.onDestroy()
    }

    companion object {
        const val START = "dev.omalauncher.record.START"
        const val STOP = "dev.omalauncher.record.STOP"
        const val CODE = "code"
        const val DATA = "data"
        const val DISPLAY = "display"
    }
}
