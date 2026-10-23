package dev.omalauncher.messages.engine

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps Soma Messages in touch with the Mac while it isn't on screen, so new messages arrive and notify. Android lets
 * an app stay alive in the background only as a foreground service, which must show a notification: it is the
 * quietest kind ("Connected to your Mac", minimised, no sound or icon in the status bar).
 */
class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Alerts.channels(this)
        val n =
            Notification.Builder(this, Alerts.LINK)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Connected to your Mac")
                .setContentText("New iMessages arrive here")
                .setContentIntent(Alerts.openIntent(this, null))
                .setOngoing(true)
                .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // Android refused (started from the background where it may not be): the app's own start tries again.
            Log.w("SomaMessages", "link service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        Messages.init(applicationContext)
        return START_STICKY
    }

    companion object {
        private const val ID = 7

        /** Starts it when linked to a Mac; nothing otherwise. */
        fun start(context: Context) {
            Messages.init(context.applicationContext)
            if (!Messages.linked) return
            runCatching { context.startForegroundService(Intent(context, LinkService::class.java)) }
                .onFailure { Log.w("SomaMessages", "starting the link service", it) }
        }
    }
}

/** After a restart, the link comes back by itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) LinkService.start(context)
    }
}
