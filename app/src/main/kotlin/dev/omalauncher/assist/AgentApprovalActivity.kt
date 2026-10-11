package dev.omalauncher.assist

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** A real activity token allows the same approval modal from an Android voice session or home rail. */
class AgentApprovalActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val approval = AgentTasks.approvals.firstOrNull() ?: run { finish(); return }
        val detail = TextView(this).apply {
            text = "${approval.explanation}\n\n${approval.detail}\n\nApprove once allows this action only. Future actions may ask again."
            setPadding(40, 24, 40, 24); setTextIsSelectable(true)
        }
        fun decide(allow: Boolean) {
            try { AgentTasks.decide(this, approval.id, allow) }
            catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
        }
        AlertDialog.Builder(this).setTitle("Allow ${approval.tool}?")
            .setView(ScrollView(this).apply { addView(detail) })
            .setPositiveButton("Approve once") { _, _ -> decide(true) }
            .setNegativeButton("Deny") { _, _ -> decide(false) }
            .setNeutralButton("Later", null).setOnDismissListener { finish() }.show()
    }
}
