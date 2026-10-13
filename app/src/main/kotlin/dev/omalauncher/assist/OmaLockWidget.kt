package dev.omalauncher.assist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import dev.omalauncher.R
import dev.omalauncher.oma.OmaChatActivity

/**
 * A test of whether Samsung's lock screen offers a widget from an app that isn't Samsung's: Oma's state in two
 * lines, opening the Oma chat. It is declared the standard way (home screen and keyguard) and the way Samsung's
 * own lock-screen widgets are. It says only the state, never a request or an answer: a lock screen is read by
 * whoever picks the phone up.
 */
class OmaLockWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        AgentTasks.restore(context)
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        /** The state in a word or two, as the oma tile gives it. */
        internal fun stateLine(
            working: Boolean,
            label: String,
            approvals: Int,
        ): String =
            when {
                approvals > 0 -> "needs your OK"
                working -> "working"
                label == "Done" -> "done"
                label == "Stopped" -> "stopped"
                label == "Ready" -> "ready"
                else -> "needs attention"
            }

        private fun views(context: Context) =
            RemoteViews(context.packageName, R.layout.oma_lock_widget).apply {
                setTextViewText(R.id.oma_lock_widget_state, stateLine(AgentTasks.working, AgentTasks.label, AgentTasks.pending.size))
                setOnClickPendingIntent(R.id.oma_lock_widget, PendingIntent.getActivity(context, 0, OmaChatActivity.intent(context), PendingIntent.FLAG_IMMUTABLE))
            }

        /** The task changed: every placed copy says so. Does nothing when none is placed. */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, OmaLockWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
            }
        }
    }
}
