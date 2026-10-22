package dev.omalauncher.assist

import org.json.JSONObject

/**
 * What a line of `codex exec --json` says, for a builder running Codex (Boss, 2026-10-21: Phone B "immediately just
 * regurgitated what I sent to him"). Codex's plain output starts with its header and his whole prompt; its events
 * say only what it did and what it answered, as Claude's stream does.
 */
object CodexProtocol {
    sealed interface Said {
        /** The conversation it runs in, which a follow-up resumes. */
        data class Started(val thread: String) : Said

        /** Words of its answer. */
        data class Text(val text: String) : Said

        /** A tool it began to use, as a step line like Claude's ("Bash: ls"), with the command when it ran one. */
        data class Step(val step: String, val tool: String, val command: String) : Said

        /** Why its turn failed. */
        data class Failed(val why: String) : Said
    }

    /** What [line] says; null for anything that isn't one of its events, or says nothing he needs to see. */
    fun read(line: String): Said? {
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (event.optString("type")) {
            "thread.started" -> event.optString("thread_id").takeIf { THREAD.matches(it) }?.let { Said.Started(it) }
            "item.started" -> step(event.optJSONObject("item"))
            "item.completed" -> event.optJSONObject("item")?.takeIf { it.optString("type") == "agent_message" }
                ?.optString("text")?.takeIf { it.isNotBlank() }?.let { Said.Text(it.trimEnd()) }
            "turn.failed" -> Said.Failed(event.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Codex's turn failed." })
            "error" -> Said.Failed(event.optString("message").ifBlank { "Codex reported an error." })
            else -> null
        }
    }

    private fun step(item: JSONObject?): Said.Step? {
        item ?: return null
        return when (item.optString("type")) {
            "command_execution" -> {
                val command = shellBody(item.optString("command"))
                Said.Step("Bash: " + command.lines().first().take(120), "Bash", command)
            }
            "file_change" -> {
                val changes = item.optJSONArray("changes")
                val paths = (0 until (changes?.length() ?: 0)).mapNotNull { changes!!.optJSONObject(it)?.optString("path")?.takeIf(String::isNotBlank) }
                Said.Step(if (paths.isEmpty()) "Edit" else "Edit: " + paths.joinToString(", ").take(120), "Edit", "")
            }
            "web_search" -> Said.Step("WebSearch", "WebSearch", "")
            "mcp_tool_call" -> Said.Step("Tool: " + listOf(item.optString("server"), item.optString("tool")).filter(String::isNotBlank).joinToString("."), "Tool", "")
            else -> null
        }
    }

    private val THREAD = Regex("[0-9a-fA-F-]{36}")

    /** How a builder's Codex conversation is kept, so one of Claude's is never handed to Codex or the other way round. */
    const val THREAD_PREFIX = "codex:"

    /** What a builder running Codex is told, as nobody can answer it while it runs: it asks at the end, as Claude does. */
    const val NO_QUESTIONS = "Nobody can answer a question while you run, and request_user_input reaches no one: never use it or wait " +
        "for an answer. When you need Boss (a setting only he can change, a choice, a port number), stop there and ask in your " +
        "final message. His reply comes back to this same conversation."

    /** `bash -lc '…'` as Codex wraps every command, unwrapped to what it ran. */
    fun shellBody(command: String): String {
        val wrapped = Regex("""^\S*(?:ba|z)?sh\s+-l?c\s+'(.*)'$""", RegexOption.DOT_MATCHES_ALL).find(command.trim()) ?: return command.trim()
        return wrapped.groupValues[1].replace("'\\''", "'")
    }
}
