package dev.omalauncher.assist

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Native Samsung/Android tiles; the system owns authentication and the notification shade. */
object OmaTiles {
    fun refresh(context: Context) {
        for (type in listOf(OmaPromptTile::class.java, OmaActivityTile::class.java, OmaApprovalsTile::class.java))
            runCatching { TileService.requestListeningState(context, ComponentName(context, type)) }
    }
}
abstract class OmaTile : TileService() {
    abstract val title: String
    open fun active() = false
    open fun subtitle() = "Tap to open"
    open fun destination(): Class<*> = AssistantPromptActivity::class.java
    override fun onStartListening() {
        super.onStartListening()
        AgentTasks.restore(this)
        qsTile?.apply {
            label = title
            subtitle = if (isLocked) "Unlock to view" else this@OmaTile.subtitle()
            state = if (!isLocked && active()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openPanel() } else openPanel()
    }
    private fun openPanel() {
        val intent = Intent(this, destination()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
class OmaPromptTile : OmaTile() { override val title = "Oma prompt"; override fun subtitle() = "Speak or type" }
class OmaActivityTile : OmaTile() {
    override val title = "Agent activity"
    override fun active() = AgentTasks.working
    override fun subtitle() = AgentTasks.label
}
class OmaApprovalsTile : OmaTile() {
    override val title = "Oma approvals"
    override fun active() = AgentTasks.approvals.isNotEmpty()
    override fun subtitle() = if (active()) "${AgentTasks.approvals.size} to review" else "No approvals"
    override fun destination(): Class<*> = if (active()) AgentApprovalActivity::class.java else AssistantPromptActivity::class.java
}
