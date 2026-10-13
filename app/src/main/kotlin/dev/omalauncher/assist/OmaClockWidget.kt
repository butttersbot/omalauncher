package dev.omalauncher.assist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.omalauncher.theme.LookWidgets

/** The date and the time in the look's own typefaces, for the lock screen in place of Samsung's clock; see [LookWidgets]. */
class OmaClockWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) = LookWidgets.refresh(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) = LookWidgets.refresh(context)

    companion object {
        /** A tap goes home: the clock has nothing of its own to open. */
        fun tap(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 1, Intent(context, dev.omalauncher.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
    }
}
