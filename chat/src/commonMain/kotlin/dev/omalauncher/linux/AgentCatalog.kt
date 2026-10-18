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
    /** What starts it without a prompt, when that's more than [bin]. */
    val command: String = bin,
    /** Whether mise needs node to install it: npm packages run their postinstall scripts with it. */
    val node: Boolean = pkg.startsWith("npm:"),
) {
    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/** Omarchy's agent list (omarchy-default-agent), limited to ones mise can install on arm64. */
val AGENTS =
    listOf(
        Agent("claude", "Claude Code", "Anthropic's coding agent", "claude"),
        // mise installs OpenCode 2 from npm (@opencode/cli).
        Agent("opencode", "OpenCode", "Open source agent, any model provider", "opencode", node = true),
        Agent("codex", "Codex", "OpenAI's coding agent", "codex"),
        Agent("grok", "Grok", "xAI's coding agent", "npm:@xai-official/grok"),
        Agent("copilot", "GitHub Copilot", "GitHub's coding agent", "copilot"),
        Agent("crush", "Crush", "Charm's agent for the terminal", "crush"),
        Agent("cursor-agent", "Cursor CLI", "Cursor's agent for the terminal", "cursor-agent"),
        Agent("agy", "Antigravity", "Google's Gemini coding agent", "antigravity-cli"),
        Agent("pi", "Pi", "Minimal, extensible coding agent", "pi"),
        Agent("omp", "Oh My Pi", "Pi with tools and plugins built in", "github:can1357/oh-my-pi"),
        Agent("ori", "Ori", "OpenRouter's coding agent", "github:OpenRouterLabs/ori-releases", command = "ori code"),
        Agent(
            "muse",
            "Muse Code",
            "Meta's coding agent",
            // Meta's launcher fetches and updates the native binary for this platform.
            "http:muse[url=https://api.meta.ai/muse-launcher.sh,bin=muse,version_list_url=https://api.meta.ai/muse-code/channels/muse-stable,version_json_path=.version]",
        ),
        Agent("kimi", "Kimi", "Moonshot AI's coding agent", "npm:@moonshot-ai/kimi-code"),
    )
