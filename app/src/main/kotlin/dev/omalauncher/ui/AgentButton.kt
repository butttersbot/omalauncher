package dev.omalauncher.ui

/** Where a request for the agent goes. */
internal enum class AgentRoute {
    /** Agents run in Linux: without it, the launcher offers to install it instead. */
    InstallLinux,

    /** No agent is installed yet. */
    ChooseAgent,

    /** Claude Code and Codex work in the background, with approvals. */
    Background,

    /** Any other agent opens in a terminal. */
    Terminal,
}

/** The route for the default agent [bin] (null when there is none), given whether Linux is installed. */
internal fun agentRoute(
    linux: Boolean,
    bin: String?,
) = when {
    !linux -> AgentRoute.InstallLinux
    bin == null -> AgentRoute.ChooseAgent
    bin == "claude" || bin == "codex" -> AgentRoute.Background
    else -> AgentRoute.Terminal
}
