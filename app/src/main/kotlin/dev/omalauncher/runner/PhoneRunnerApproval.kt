package dev.omalauncher.runner

import dev.omalauncher.assist.ClaudeProtocol
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.questionSkipAnswers
import org.json.JSONObject

data class RunnerApprovalDecision(val allow: Boolean, val answers: JSONObject?)
/** Pure validation before the existing ledger claims anything. Denying a question means Skip, not stopping. */
fun phoneRunnerDecision(pending: PendingApproval, nonce: String, decision: String, answers: JSONObject?): RunnerApprovalDecision {
    if (nonce != pending.nonce) throw RunnerFault(403, "wrong nonce")
    if (decision !in setOf("allow", "deny")) throw RunnerFault(400, "invalid decision")
    if (decision == "deny" && answers != null) throw RunnerFault(400, "deny cannot include answers")
    val skip = pending.approval.tool == "AskUserQuestion" && decision == "deny"
    val payload = if (skip) questionSkipAnswers(pending.approval.input.toString()) else answers
    val allow = decision == "allow" || skip
    try { ClaudeProtocol.decision(pending.approval, allow, payload) }
    catch (_: Exception) { throw RunnerFault(400, "invalid question form") }
    return RunnerApprovalDecision(allow, payload)
}
