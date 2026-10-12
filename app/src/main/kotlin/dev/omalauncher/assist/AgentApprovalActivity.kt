package dev.omalauncher.assist

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView

/**
 * The approval screen: where an approval opens when notifications are blocked, and from the tile, the prompt
 * and a tap on the notification's text. It shows the one approval its intent names and answers only that one.
 */
class AgentApprovalActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AgentTasks.restore(this)
        val ticket = ApprovalTicket.parse(intent?.dataString) ?: run { finish(); return }
        fun waiting() = AgentTasks.pending.firstOrNull { it.id == ticket.id && it.nonce == ticket.nonce }
        val entry = waiting() ?: run {
            // Opened from a notification an earlier request left behind.
            if (AgentTasks.pending.none { it.id == ticket.id }) AgentNotices.withdraw(this, ticket.id, ticket.slot)
            AgentNotices.expired(this); finish(); return
        }
        val approval = entry.approval
        val detail = TextView(this).apply {
            text = "${approval.explanation}\n\n${approval.detail}\n\nApprove once allows this action only. Future actions may ask again."
            setPadding(40, 24, 40, 24); setTextIsSelectable(true)
        }
        fun decide(allow: Boolean) {
            when (AgentTasks.answerApproval(this, entry.id, entry.nonce, allow)) {
                ApprovalOutcome.Decided -> Unit
                ApprovalOutcome.Expired -> AgentNotices.expired(this)
                // Nothing was answered and the request is still waiting.
                ApprovalOutcome.Locked -> AgentNotices.locked(this)
            }
        }
        dialog = AlertDialog.Builder(this).setTitle("Allow ${approval.tool.take(60)}?")
            .setView(ScrollView(this).apply { addView(detail) })
            .setPositiveButton("Approve once") { _, _ -> decide(true) }
            .setNegativeButton("Deny") { _, _ -> decide(false) }
            .setNeutralButton("Later", null).setOnDismissListener { finish() }.show()
        // Answered elsewhere, withdrawn or stopped while this was open: close without a word.
        handler.post(object : Runnable {
            override fun run() { if (waiting() == null) dialog?.dismiss() else handler.postDelayed(this, 500) }
        })
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dialog?.apply { setOnDismissListener(null); dismiss() }
        super.onDestroy()
    }
}
