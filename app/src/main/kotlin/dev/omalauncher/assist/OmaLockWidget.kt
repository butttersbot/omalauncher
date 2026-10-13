package dev.omalauncher.assist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import dev.omalauncher.oma.OmaChatActivity
import dev.omalauncher.theme.LookWidgets

/**
 * Oma's state in a word or two, as a scrap of the look in use, opening the Oma chat; drawn by [LookWidgets].
 * LockStar's widget picker offers it for the lock screen. It says only the state, never a request or an answer:
 * a lock screen is read by whoever picks the phone up.
 */
class OmaLockWidget : AppWidgetProvider() {
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

        /** The state now. */
        fun state(): String = stateLine(AgentTasks.working, AgentTasks.label, AgentTasks.pending.size)

        fun tap(context: Context): PendingIntent = PendingIntent.getActivity(context, 0, OmaChatActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)

        /** The task changed: every placed copy says so. */
        fun refresh(context: Context) {
            AgentTasks.restore(context)
            LookWidgets.refresh(context)
        }
    }
}
