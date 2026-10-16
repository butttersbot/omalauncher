package dev.omalauncher.assist

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.json.JSONObject

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
    /** The Oma app. A tile attaches no screen, whatever app is underneath. */
    open fun destination(): Intent = dev.omalauncher.oma.OmaChatActivity.intent(this)
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
        val intent = destination().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
    override fun active() = AgentTasks.working || AgentTasks.mac.working != null
    override fun subtitle() = activityLine(AgentTasks.working, AgentTasks.pending.size, AgentTasks.headline, AgentTasks.mac)
}
class OmaApprovalsTile : OmaTile() {
    override val title = "Oma approvals"
    private val count get() = AgentTasks.pending.size + MacThreads.pending.size
    override fun active() = count > 0
    override fun subtitle() = if (active()) "$count to review" else "No approvals"
    /**
     * The oldest approval, the phone's before the Mac's, chosen when the tile is tapped; the screen then answers
     * only that one. A Mac request with no notification slot is answered from the chat, which the tile then opens.
     */
    override fun destination(): Intent =
        AgentTasks.pending.firstOrNull()?.let { AgentNotices.reviewIntent(this, it) }
            ?: MacThreads.pending.firstNotNullOfOrNull { mac -> mac.slot?.let { AgentNotices.macReviewIntent(this, mac.ticket, it) } }
            ?: super.destination()
}

/**
 * The activity tile's line: the builders' [headline] while one works or waits for his OK (of which there are
 * [approvals]) and no Mac thread waits for him, otherwise the Mac's work by name, as the oma tile says it.
 */
internal fun activityLine(working: Boolean, approvals: Int, headline: String, mac: MacGlance): String {
    val status = omaStatus(JSONObject().put("working", working).put("approvals", approvals), mac)
    return status.getString("mac").ifEmpty { headline }
}

/**
 * The herd sheep's switch in the quick settings (Boss, 2026-10-15: "we need a button to toggle it on and off"): a tap
 * shows or hides it, over other apps and on home alike; nothing opens.
 */
class HerdSheepTile : android.service.quicksettings.TileService() {
    override fun onStartListening() {
        super.onStartListening()
        dev.omalauncher.herd.HerdSheep.restore(this)
        show()
    }

    override fun onClick() {
        super.onClick()
        dev.omalauncher.herd.HerdSheep.set(this, !dev.omalauncher.herd.HerdSheep.shown)
        show()
    }

    private fun show() {
        qsTile?.apply {
            label = "Herd sheep"
            subtitle = if (dev.omalauncher.herd.HerdSheep.shown) "On" else "Off"
            state = if (dev.omalauncher.herd.HerdSheep.shown) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
