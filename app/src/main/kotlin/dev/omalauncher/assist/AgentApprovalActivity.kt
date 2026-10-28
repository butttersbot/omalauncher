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
 * A request of a thread on the Mac opens here too, named by its own kind of ticket, and is answered through [MacThreads].
 */
class AgentApprovalActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AgentTasks.restore(this)
        MacApprovalTicket.parse(intent?.dataString)?.let { return reviewMac(it) }
        val ticket = ApprovalTicket.parse(intent?.dataString) ?: run { finish(); return }
        fun waiting() = AgentTasks.pending.firstOrNull { it.id == ticket.id && it.nonce == ticket.nonce }
        val entry = waiting() ?: run {
            // Opened from a notification an earlier request left behind.
            if (AgentTasks.pending.none { it.id == ticket.id }) AgentNotices.withdraw(this, ticket.id, ticket.slot)
            AgentNotices.expired(this); finish(); return
        }
        val approval = entry.approval
        // The title names the builder that asks, so he never answers one builder's request thinking it is the other's.
        val builder = AgentTasks.holder(entry) ?: BuilderId.A
        if (approval.tool == "AskUserQuestion") {
            startActivity(dev.omalauncher.oma.OmaChatActivity.intent(this)
                .putExtra(dev.omalauncher.oma.OmaChatActivity.EXTRA_THREAD, builder.thread)
                .putExtra(dev.omalauncher.oma.OmaChatActivity.EXTRA_QUESTION, entry.nonce))
            finish(); return
        }
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
        dialog = AlertDialog.Builder(this).setTitle(builderApprovalTitle(approval.tool, builder))
            .setView(ScrollView(this).apply { addView(detail) })
            .setPositiveButton("Approve once") { _, _ -> decide(true) }
            .setNegativeButton("Deny") { _, _ -> decide(false) }
            .setNeutralButton("Later", null).setOnDismissListener { finish() }.show()
        // Answered elsewhere, withdrawn or stopped while this was open: close without a word.
        handler.post(object : Runnable {
            override fun run() { if (waiting() == null) dialog?.dismiss() else handler.postDelayed(this, 500) }
        })
    }
    /** The same screen for a request of the Mac's: it names the thread, and only a tap here answers. */
    private fun reviewMac(ticket: MacApprovalTicket) {
        MacThreads.restore(this)
        fun waiting() = MacThreads.pending.firstOrNull { it.ticket == ticket.ticket }
        val entry = waiting() ?: run {
            // Opened from a notification an earlier run left behind, or one answered since.
            AgentNotices.macWithdraw(this, ticket.ticket, ticket.slot)
            AgentNotices.expired(this); finish(); return
        }
        val request = entry.request
        val detail = TextView(this).apply {
            text = "${request.explanation}\n\n${macApprovalDetail(request)}\n\nApprove once allows this action only. Future actions may ask again." +
                if (request.canRemember) "\n\nAlways allow saves this exact command, directory and access for this thread. Revoke from the tab menu → Forget saved commands." else ""
            setPadding(40, 24, 40, 24); setTextIsSelectable(true)
        }
        val app = applicationContext
        fun decide(allow: Boolean) = MacThreads.answer(app, entry.ticket, allow) { outcome -> macApprovalNotice(outcome)?.let { AgentNotices.say(app, it) } }
        dialog = AlertDialog.Builder(this).setTitle("Allow ${request.tool.take(60)} on ${entry.threadName.take(40)}?")
            .setView(ScrollView(this).apply { addView(detail) })
            .setPositiveButton("Approve once") { _, _ -> decide(true) }
            .setNegativeButton("Deny") { _, _ -> decide(false) }
            .apply { if (request.canRemember) setNeutralButton("Always allow this command") { _, _ ->
                MacThreads.rememberCommand(app, entry.ticket) { outcome -> macApprovalNotice(outcome)?.let { AgentNotices.say(app, it) } }
            } else setNeutralButton("Later", null) }.setOnDismissListener { finish() }.show()
        // Answered elsewhere, withdrawn or ended while this was open: close without a word.
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
