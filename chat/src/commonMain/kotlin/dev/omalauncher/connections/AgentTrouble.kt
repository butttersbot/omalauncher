package dev.omalauncher.connections

import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus

/**
 * What Connections says about an agent comes from its real turns (Boss, 2026-10-30): a turn that fails with a common API
 * error sets the status, naming the agent and the error, and a later good turn clears it. No check of its own, no ping.
 */
val NEUTRAL_RESULTS = setOf("not_installed", "quiet")

data class AgentTrouble(val agent: String, val code: String, val error: String) {
    val words get() = when (code) {
        "sign_in" -> "sign-in refused"
        "usage_limit" -> "usage limit"
        "overloaded" -> "overloaded"
        "no_connection" -> "can't reach its service"
        else -> "API error"
    }
    val sentence get() = "$agent: $words." + if (error.isBlank()) "" else " $error"
}

private fun agentName(agent: String) = agent.replaceFirstChar { it.uppercase() }

/** The trouble this check reports, if it is an agent's. */
fun ConnectionCheck.agentTrouble(): AgentTrouble? = if (!name.startsWith("agent_turn.") || result == "pass") null
    else AgentTrouble(agentName(name.removePrefix("agent_turn.")), fields["code"].orEmpty(), fields["error"].orEmpty())

/** The same list as the runner's (soma_services.AGENT_ERRORS), for this phone's own turns. */
private val AGENT_ERRORS = listOf(
    "sign_in" to Regex("not logged in|/login\\b|failed to authenticate|authentication[ _]failed|invalid api key|\\b401\\b|unauthori[sz]ed|token (has )?expired|codex login", RegexOption.IGNORE_CASE),
    "usage_limit" to Regex("rate.?limit|usage limit|usage credits|weekly limit|hit your .{0,40}limit|\\b429\\b|quota|billing", RegexOption.IGNORE_CASE),
    "overloaded" to Regex("overloaded|\\b529\\b", RegexOption.IGNORE_CASE),
    "no_connection" to Regex("connection (lost|error|refused|reset)|econn\\w+|network error|fetch failed|stream disconnected|could not resolve|timed out", RegexOption.IGNORE_CASE),
    "server_error" to Regex("api error|server[ _]error|\\b50[0-4]\\b|bad gateway|service unavailable", RegexOption.IGNORE_CASE),
)
private val NOT_THE_SERVICE = Regex("safeguards|invalid_request|prompt is too long", RegexOption.IGNORE_CASE)

fun agentError(text: String): String? =
    if (NOT_THE_SERVICE.containsMatchIn(text)) null else AGENT_ERRORS.firstOrNull { it.second.containsMatchIn(text) }?.first

/** This phone's own Claude and Codex, from each one's newest finished turn. An agent with no turns yet has no row. */
fun phoneAgentReadings(tasks: List<ChatTask>, now: Long): List<ConnectionReading> = listOf("claude", "codex").mapNotNull { agent ->
    val last = tasks.lastOrNull { !it.mac && it.answeredBy != "Soma" && it.agent.contains(agent, ignoreCase = true) &&
        it.status in setOf(TaskStatus.Done, TaskStatus.Failed) && (it.status == TaskStatus.Done || agentError(it.result) != null) } ?: return@mapNotNull null
    val root = "agent:phone:$agent"
    if (last.status == TaskStatus.Done) return@mapNotNull ConnectionReading(Capability.Agents, ConnectionState.Ready, "${agentName(agent)} answered its last turn here.", now, root)
    val code = agentError(last.result)!!
    val error = last.result.split(Regex("\\s+")).joinToString(" ").take(160)
    val check = ConnectionCheck("agent_turn.$agent", "phone", result = if (code == "sign_in") "auth" else "fail", fields = mapOf("code" to code, "agent" to agent, "error" to error))
    ConnectionReading(Capability.Agents, if (code == "sign_in") ConnectionState.Auth else ConnectionState.Failed, check.agentTrouble()!!.sentence, now, root, checks = listOf(check))
}
