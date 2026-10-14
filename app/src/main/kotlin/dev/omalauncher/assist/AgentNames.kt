package dev.omalauncher.assist

import dev.omalauncher.linux.AGENTS
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import org.json.JSONObject

/*
 * Who wrote a reply in the Oma chat (Boss, 2026-10-13: "the username can just be the name of the ai"). Every
 * answer carries a short name, the model's family where the agent says which model answered ("Opus", "Haiku")
 * and the agent's own name where it doesn't ("Claude" for a thread on the Mac, "Codex"). The name is kept on
 * the entry ([ChatTask.answeredBy]) as it was when the reply came, so a later change of model does not rename
 * old answers.
 */

/** The model families a name is taken from, as they are shown. Found as a word anywhere in a model id or agent kind. */
private val FAMILIES = listOf("Opus", "Sonnet", "Haiku", "Fable", "Codex", "Grok")

/**
 * What the chat calls whoever [id] is: a model id ("claude-opus-5-5", "claude-3-5-sonnet-20241022", the alias
 * "haiku", "gpt-5-codex", "grok-4") or the kind of an agent ("claude", "codex", "grok", or any other agent the
 * launcher installs, by its command). A Claude model of no family named here is "Claude". Null for an id that
 * says nothing anyone would recognise, Claude Code's own "<synthetic>" messages among them: an entry is better
 * left unnamed than named wrongly.
 */
fun agentName(id: String?): String? {
    val words = id?.lowercase()?.split(Regex("[^a-z]+"))?.filter { it.isNotEmpty() }.orEmpty()
    FAMILIES.firstOrNull { it.lowercase() in words }?.let { return it }
    if ("claude" in words) return "Claude"
    return AGENTS.firstOrNull { it.bin == id?.trim() }?.name
}

/**
 * The name of the model a line of Claude Code's stream-json says is answering: the `model` of its `system`
 * `init` line, or of an `assistant` message. Null for every other line.
 */
fun streamAgentName(event: JSONObject): String? = when (event.optString("type")) {
    "system" -> if (event.optString("subtype") == "init") agentName(event.optString("model")) else null
    "assistant" -> agentName(event.optJSONObject("message")?.optString("model"))
    else -> null
}

/**
 * The name a build task is filed under as it starts, from its agent's command [bin]: none for Claude Code, whose
 * stream says which model answers ([streamAgentName]), and the agent's own name for any other.
 */
fun builderName(bin: String): String = if (bin == "claude") "" else agentName(bin).orEmpty()

/**
 * The name shown over [result], the reply [task] shows now, or null when none is: there is no reply yet, or
 * what stands there is an error or the launcher's own words (a task that failed or was never sent), which no
 * agent wrote. An answer of the front agent's saved before names were kept is its model's; a build or Mac
 * entry from then stays unnamed, since which model answered it is not known.
 */
fun replyName(task: ChatTask, result: String): String? {
    if (result.isBlank() || task.status == TaskStatus.Failed || task.status == TaskStatus.Dropped) return null
    return task.answeredBy.ifEmpty { if (task.quick) agentName(QUICK_MODEL).orEmpty() else "" }.ifEmpty { null }
}
