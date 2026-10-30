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

/** The agent this check says is missing from the phone, if it is that check. */
fun ConnectionCheck.agentMissing(): String? = if (name.startsWith("agent_missing.")) agentName(name.removePrefix("agent_missing.")) else null

/** An agent installed on this phone, as the Agents list knows it. */
data class InstalledAgent(val bin: String, val name: String)

/** An agent that something on this phone needs, and who needs it ("Phone B", "the front agent"). */
data class AgentNeed(val bin: String, val name: String, val by: List<String>)

/** "gpt-6.1-terra" as it is said: "GPT-6.1 Terra". */
fun prettyModel(id: String): String = id.split('-').let { parts ->
    if (parts.size < 2) id else "${parts.first().uppercase()}-${parts[1]}" + parts.drop(2).joinToString("") { " " + it.replaceFirstChar { c -> c.uppercase() } }
}

/** The word a Models line ends with: "Healthy", "No recent run", "Missing", or what its last real run hit. */
fun modelWord(reading: ConnectionReading): String =
    if (reading.checks.any { it.agentMissing() != null }) "Missing"
    else reading.checks.firstNotNullOfOrNull { it.agentTrouble() }?.words?.replaceFirstChar { it.uppercase() }
        ?: when (reading.state) { ConnectionState.Ready -> "Healthy"; ConnectionState.Stale -> "Stale"; else -> "No recent run" }

/** When that line's last real run was, in ms, or 0. */
fun modelRanAt(reading: ConnectionReading): Long =
    reading.checks.firstNotNullOfOrNull { c -> c.fields["at"]?.toLongOrNull() }?: 0L

/** The real error a Models line hit, as it was printed, or blank. */
fun modelError(reading: ConnectionReading): String = reading.checks.firstNotNullOfOrNull { it.agentTrouble() }?.error.orEmpty()

/**
 * One line for each agent installed on this phone, from its own newest finished real turn (no sign-in check, no test
 * call): Healthy when it finished, the error as printed when it hit one, "No recent run" when there is none. An agent
 * a builder or the front agent needs that isn't installed gets a line too, Missing, and it is an incident.
 * [modelOf] names the model a turn ran where Soma knows it; the line shows the agent alone where it doesn't.
 */
fun phoneModelReadings(installed: List<InstalledAgent>, needs: List<AgentNeed>, tasks: List<ChatTask>, now: Long,
    modelOf: (ChatTask) -> String? = { null }): List<ConnectionReading> {
    val lines = installed.map { agent ->
        val bin = agent.bin
        val name = agentName(bin)
        val root = "agent:phone:$bin"
        val last = tasks.lastOrNull { !it.mac && it.answeredBy != "Soma" && it.agent.contains(bin, ignoreCase = true) &&
            it.status in setOf(TaskStatus.Done, TaskStatus.Failed) && (it.status == TaskStatus.Done || agentError(it.result) != null) }
        val label = listOfNotNull(agent.name, last?.let(modelOf)).joinToString(" · ")
        when {
            last == null -> ConnectionReading(Capability.Models, ConnectionState.Unknown, "${agent.name} has no recent run here.", now, root, label = label)
            // Healthy is only that the last real run finished, and the line says when that was: it is no fresh check.
            last.status == TaskStatus.Done -> ConnectionReading(Capability.Models, ConnectionState.Ready, "${agent.name} answered its last real run here, ${ageWords(now - last.time)} ago.", now, root,
                checks = listOf(ConnectionCheck("agent_last_run.$bin", "phone", result = "pass", fields = mapOf("at" to last.time.toString()))), label = label)
            else -> {
                val code = agentError(last.result)!!
                val error = last.result.split(Regex("\\s+")).joinToString(" ").take(160)
                val check = ConnectionCheck("agent_turn.$bin", "phone", result = if (code == "sign_in") "auth" else "fail", fields = mapOf("code" to code, "agent" to bin, "error" to error, "at" to last.time.toString()))
                ConnectionReading(Capability.Models, if (code == "sign_in") ConnectionState.Auth else ConnectionState.Failed, check.agentTrouble()!!.sentence, now, root, checks = listOf(check), label = label)
            }
        }
    }
    val missing = needs.filter { need -> installed.none { it.bin == need.bin } }.map { need ->
        val who = need.by.joinToString(" and ")
        val check = ConnectionCheck("agent_missing.${need.bin}", "phone", result = "fail", fields = mapOf("agent" to need.bin, "needs" to who))
        ConnectionReading(Capability.Models, ConnectionState.Failed, "${need.name} isn't installed on this phone. $who ${if (need.by.size > 1) "need" else "needs"} it.",
            now, "agent:phone:${need.bin}:missing", checks = listOf(check), label = need.name)
    }
    return lines + missing
}
