package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.JobState
import kotlinx.coroutines.launch

/** What an agent's row says about being the default: the one the agent button, the Oma chat and widget work go to. */
internal enum class DefaultMark {
    /** Not installed, so it can't be the default. */
    None,

    /** It is the default. */
    Default,

    /** Installed beside the default: a tap makes it the default. */
    CanChoose,
}

/** The mark for the agent [bin], given the bins [installed] and the default's (null when there is none). */
internal fun defaultMark(
    bin: String,
    installed: List<String>,
    default: String?,
) = when {
    bin !in installed -> DefaultMark.None
    bin == default -> DefaultMark.Default
    else -> DefaultMark.CanChoose
}

/** Install and launch coding agents (Claude Code, OpenCode, Codex, Grok, ...), and choose the default one. */
@Composable
fun AgentsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onOpen: (Agent) -> Unit,
) {
    val store = runtime.agents
    val scope = rememberCoroutineScope()
    OverlayPanel("coding agents", onDismiss, contentWidth = ReadingWidth) {
        BasicText(
            "Installed with mise, like Omarchy. Logins open in your browser; paste keys with the keyboard's clipboard.",
            Modifier.padding(vertical = 8.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AGENTS, key = { it.bin }) { agent ->
                AgentRow(
                    agent = agent,
                    installed = agent in store.installed,
                    state = store.states[agent.bin] ?: JobState.Idle,
                    mark = defaultMark(agent.bin, store.installed.map { it.bin }, store.default?.bin),
                    onChoose = { store.choose(agent) },
                    onInstall = { scope.launch { store.install(agent) } },
                    onRemove = { scope.launch { store.remove(agent) } },
                    onOpen = { onOpen(agent) },
                )
            }
        }
    }
}

@Composable
private fun AgentRow(
    agent: Agent,
    installed: Boolean,
    state: JobState,
    mark: DefaultMark,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(agent.name, style = Mono.copy(fontSize = TextSize.Item))
                BasicText(agent.description, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
            when {
                state is JobState.Working -> BasicText("${state.action}…", style = Mono.copy(color = Palette.accent))
                installed -> {
                    BracketAction("open", Palette.green, onOpen)
                    BracketAction("remove", Palette.red, onRemove)
                }
                else -> BracketAction("install", Palette.accent, onInstall)
            }
        }
        // On a line of its own: three actions beside the name leave a phone no room for the name.
        when (mark) {
            DefaultMark.Default -> BasicText("● default", Modifier.padding(top = 6.dp), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
            DefaultMark.CanChoose -> BracketAction("make default", Palette.accent, onChoose)
            DefaultMark.None -> {}
        }
        JobLog(state)
    }
}
