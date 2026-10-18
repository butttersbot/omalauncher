package dev.omalauncher.linux

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Brings mise itself up to date. Which backend a tool installs from is built into mise, and an old mise
 * breaks when a tool moves: OpenCode 2 left GitHub releases for npm, and mise 2026.10.8 still looked for
 * it on GitHub (404). Best effort, since the old mise may still manage.
 */
const val MISE_SELF_UPDATE = "{ mise self-update -y || true; }"

/** Adds node to the global config when an installed agent needs it but was installed before it did (OpenCode 1). */
val MISE_NODE_FOR_AGENTS =
    AGENTS.filter { it.node }.joinToString(" ", prefix = "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g ", postfix = ")\" ]; then mise use -g node@lts; fi") {
        shellQuote(it.pkg)
    }

/**
 * Starts [agentBin] with a first prompt; OpenCode takes it as --prompt, Antigravity and Copilot as -i (they
 * refuse a bare argument), the others as an argument.
 * Ori's agent is `ori code`, which stays open only with --interactive. Kimi takes a prompt only in
 * print mode, so the session it ran in is continued after the answer.
 * [readOnly], for reading something untrusted: Claude Code and Antigravity start in plan mode, where they
 * can read but not run commands or change files whatever the user's settings say; otherwise Claude Code
 * starts in auto mode. The others have no flag for that we can rely on inside proot, and only have the prompt.
 */
fun agentInvocation(
    agentBin: String,
    prompt: String,
    readOnly: Boolean = false,
    images: List<String> = emptyList(),
    /**
     * Whether Claude Code may start in auto mode. False for a prompt that isn't Boss's own words, like a widget's
     * ask(...): it starts as Claude Code does by default, asking before it runs commands or changes files.
     */
    auto: Boolean = true,
): String {
    require(images.isEmpty() || agentBin == "codex" || agentBin == "claude") {
        "Image attachments are supported by Claude Code and Codex."
    }
    val withImages = if (agentBin == "claude" && images.isNotEmpty()) {
        prompt + "\n\nRead these attached images before answering:\n" + images.joinToString("\n")
    } else prompt
    // A prompt starting with a dash would be read as an option (claude --settings=... runs hooks); a space in front isn't.
    val quoted = shellQuote(if (withImages.startsWith("-")) " $withImages" else withImages)
    return when {
        agentBin == "codex" && images.isNotEmpty() ->
            // After --, nothing is read as an option: the prompt goes as it is.
            "codex " + images.joinToString(" ") { "--image ${shellQuote(it)}" } + " -- ${shellQuote(withImages)}"
        agentBin == "opencode" -> "$agentBin --prompt $quoted"
        agentBin == "copilot" -> "$agentBin -i $quoted"
        agentBin == "claude" && readOnly -> "$agentBin --permission-mode plan $quoted"
        agentBin == "claude" && auto -> "$agentBin --permission-mode auto $quoted"
        agentBin == "ori" -> "ori code --interactive --prompt $quoted"
        agentBin == "kimi" -> "kimi --prompt $quoted && kimi --continue"
        agentBin == "agy" && readOnly -> "$agentBin --mode plan -i $quoted"
        agentBin == "agy" -> "$agentBin -i $quoted"
        else -> "$agentBin $quoted"
    }
}

/** A new-app session in ~/apps: the agent asks what to build, then adds it to the app drawer. */
fun newAppCommand(agentBin: String): String {
    val prompt =
        "Ask me what app to build, then build it in its own directory here. It runs on Arch Linux ARM (aarch64) inside proot " +
            "on an Android phone, under a Wayland compositor: no systemd, no X11, OpenGL usually in software, a small touch screen. " +
            "Install what it needs with pacman. When it works, add a desktop entry at ~/.local/share/applications/<name>.desktop " +
            "(Terminal=true for terminal apps) so it shows up in my app drawer."
    return "mkdir -p ~/apps && cd ~/apps && ${agentInvocation(agentBin, prompt)}; exec bash"
}

class AgentStore(
    private val proot: Proot,
    /** Called when the set of installed agents changed, so their desktop entries follow. */
    private val onChanged: () -> Unit,
) {
    private val mise get() = File(proot.home, ".local/bin/mise")
    private val shims get() = File(proot.home, ".local/share/mise/shims")

    val states = mutableStateMapOf<String, JobState>()

    /** Shims are symlinks to a guest path (/root/.local/bin/mise), so don't follow them from the host. */
    fun isInstalled(agent: Agent) = Files.exists(File(shims, agent.bin).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** The agent picked in the prompt bar, kept in the guest home where scripts can read it too. */
    private val preferredFile get() = File(proot.launcherConfig, "agent")
    private var preferred by mutableStateOf<String?>(null)

    /** Reads what's on disk; call off the main thread before the UI needs [default]. */
    fun load() {
        preferred = runCatching { preferredFile.readText().trim() }.getOrNull()
        refresh()
    }

    /** Installed agents in Omarchy's order; re-read from disk after installs and removals. */
    var installed by mutableStateOf<List<Agent>>(emptyList())
        private set

    private fun refresh() {
        installed = AGENTS.filter { isInstalled(it) }
    }

    /** The agent the launcher opens for prompts and widget work: the picked one, else the first installed in Omarchy's order. */
    val default: Agent? get() = installed.firstOrNull { it.bin == preferred } ?: installed.firstOrNull()

    fun choose(agent: Agent) {
        preferred = agent.bin
        runCatching {
            preferredFile.parentFile?.mkdirs()
            preferredFile.writeText(agent.bin + "\n")
        }.onFailure { Log.w("agents", "cannot save the default agent", it) }
    }

    suspend fun install(agent: Agent) =
        run(agent, "installing") { installScript(agent) }.also { ok -> if (ok) onInstalled(agent) }

    /** Installs [agent] with mise, bootstrapping mise itself if needed. */
    fun installScript(agent: Agent) =
        buildString {
            append(if (mise.exists()) "$MISE_SELF_UPDATE && " else "curl -fsSL https://mise.run | sh && ")
            if (agent.node) append("mise use -g node@lts && ")
            append("mise use -g ${shellQuote(agent.pkg)}")
            // Downloads are only needed during install; storage on phones is tight.
            append(" && mise cache clear && rm -rf ~/.local/share/mise/downloads")
        }

    /** Call after [installScript] succeeded: makes the agent show up in the drawer. */
    fun onInstalled(agent: Agent) {
        refresh()
        onChanged()
    }

    suspend fun remove(agent: Agent) =
        run(agent, "removing") { "mise unuse -g ${shellQuote(agent.pkg)} && mise prune -y" }
            .also {
                refresh()
                onChanged()
            }

    /** Drawer entries for the installed agents, with their logos (see [bundledIcons]); each opens in a terminal in the home directory. */
    fun managed(): List<Managed> =
        installed.map { agent ->
            Managed.text(File(proot.applications, "agent-${agent.bin}.desktop"), Policy.Owned) {
                "[Desktop Entry]\nType=Application\nName=${agent.name}\nTerminal=true\nExec=${agent.command}\nIcon=${bundledIcon("agent-${agent.bin}")}\n"
            }
        } + codexConfig()

    /**
     * Codex starts a shared background server and reads its start time with `ps`; when that fails, Codex
     * refuses to start ("failed to read start time for pid-managed app server", rerun with --no-daemon).
     * Proot kills that server with its window anyway, so don't start it. A system layer, so the user's
     * ~/.codex/config.toml stays theirs and can turn it back on.
     */
    private fun codexConfig() =
        if (installed.none { it.bin == "codex" }) {
            emptyList()
        } else {
            listOf(
                Managed.text(File(proot.rootfs, "etc/codex/config.toml"), Policy.Owned) {
                    "# Written by the launcher.\n[features]\ndaemon_auto_start = false\n"
                },
            )
        }

    private suspend fun run(
        agent: Agent,
        action: String,
        script: () -> String,
    ): Boolean {
        val (ok, _) = proot.trackedJob(action, withContext(Dispatchers.IO) { script() }) { states[agent.bin] = it }
        Log.i("agents", "$action ${agent.bin}: ${if (ok) "ok" else "failed"}")
        return ok
    }
}
