package dev.omalauncher.linux

/*
 * The coding agents the launcher knows by name, shared with the Mac app; installing them is in Agents.kt.
 */

/** A coding agent CLI, installed the way Omarchy's omarchy-default-agent does it: via mise. */
data class Agent(
    val bin: String,
    val name: String,
    val description: String,
    /** mise package spec. */
    val pkg: String,
) {
    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/** Omarchy's agent list (omarchy-default-agent), limited to ones mise can install on arm64. */
val AGENTS =
    listOf(
        Agent("claude", "Claude Code", "Anthropic's coding agent", "claude"),
        Agent("opencode", "OpenCode", "Open source agent, any model provider", "opencode"),
        Agent("codex", "Codex", "OpenAI's coding agent", "codex"),
        Agent("grok", "Grok", "xAI's coding agent", "npm:@xai-official/grok"),
        Agent("copilot", "GitHub Copilot", "GitHub's coding agent", "copilot"),
        Agent("crush", "Crush", "Charm's agent for the terminal", "crush"),
        Agent("cursor-agent", "Cursor CLI", "Cursor's agent for the terminal", "cursor-agent"),
        Agent("agy", "Antigravity", "Google's Gemini coding agent", "antigravity-cli"),
        Agent("pi", "Pi", "Minimal, extensible coding agent", "pi"),
    )
