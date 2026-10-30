package dev.omalauncher.connections

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.omalauncher.canvas.ModuleSize
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ConnectionsViewTest {
    @Test fun restoreDoesNotOpenDetailsAndBodyDoes() = runComposeUiTest {
        var restores = 0
        ConnectionsSource.restore = { restores++ }
        ConnectionsSource.clock = { 100_000 }
        ConnectionsSource.snapshot = ConnectionsSnapshot(listOf(ConnectionReading(Capability.Network, ConnectionState.Failed, "down", 100_000, "network", "Mac Pro", "mac")), 100_000)
        setContent { Box(Modifier.size(235.dp, 180.dp)) { ConnectionsModule(ModuleSize.Card, 100_000) } }
        onNodeWithText("[Restore]").assertIsDisplayed().performClick()
        assertEquals(1, restores)
        onNodeWithContentDescription("Close Connections").assertDoesNotExist()
        onNodeWithText("Connections").performClick()
        onNodeWithContentDescription("Close Connections").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Close Connections").assertDoesNotExist()
        ConnectionsSource.snapshot = ConnectionsSnapshot(); ConnectionsSource.clock = { System.currentTimeMillis() }
    }
    @Test fun noRestoreOnTheCardWithNoIncident() = runComposeUiTest {
        ConnectionsSource.clock = { 100_000 }
        ConnectionsSource.snapshot = ConnectionsSnapshot(Capability.entries.map { ConnectionReading(it, ConnectionState.Ready, "ok", 100_000) }, 100_000)
        try {
            setContent { Box(Modifier.size(235.dp, 180.dp)) { ConnectionsModule(ModuleSize.Card, 100_000) } }
            onNodeWithText("[Restore]").assertDoesNotExist()
        } finally { ConnectionsSource.snapshot = ConnectionsSnapshot(); ConnectionsSource.clock = { System.currentTimeMillis() } }
    }
    @Test fun cardAndSheetCountTheSameIncidentsFromTheSameSnapshot() = runComposeUiTest {
        ConnectionsSource.clock = { 100_000 }
        ConnectionsSource.snapshot = ConnectionsSnapshot(listOf(
            ConnectionReading(Capability.Network, ConnectionState.Failed, "down", 1, "host:a", "A", "a"),
            ConnectionReading(Capability.Voice, ConnectionState.Failed, "down", 1, "service:voice:b", "B", "b")), 1)
        try {
            setContent { Column { Box(Modifier.size(235.dp, 180.dp)) { ConnectionsModule(ModuleSize.Card, 100_000) }; ConnectionsDialog(100_000) {} } }
            // Old, but still failures: both say two.
            onNodeWithText("△ 2").assertExists()
            onNodeWithContentDescription("Connection check: 2 incidents").assertExists()
        } finally { ConnectionsSource.snapshot = ConnectionsSnapshot(); ConnectionsSource.clock = { System.currentTimeMillis() } }
    }
    @Test fun expandedInventoryHasCoreCanvasAndDistinctUnknownState() = runComposeUiTest {
        setContent { ConnectionsDialog(100_000) {} }
        onNodeWithText("All capabilities", ignoreCase = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("12 groups", ignoreCase = true).assertExists()
        // Ruled rows, no disclosure triangles (Canvas v6).
        onAllNodesWithText("▸", substring = true).assertCountEquals(0)
        onNodeWithText("Design Canvas").performScrollTo().assertIsDisplayed().performClick()
        onAllNodesWithText("Not checked yet.").onLast().assertExists()
    }
    @Test fun incidentShowsTheCardsHeadlineStepsAndOnlyBossesControls() = runComposeUiTest {
        val now = 100_000L
        ConnectionsSource.snapshot = ConnectionsSnapshot(Capability.entries.map { c ->
            val hit = c == Capability.Agents || c == Capability.Builds
            ConnectionReading(c, if (hit) ConnectionState.Failed else ConnectionState.Ready, "evidence", now, if (hit) "runner:mac" else null,
                if (hit) "Mac Pro" else "", if (hit) "mac-pro" else "")
        } + ConnectionReading(Capability.Network, ConnectionState.Ready, "route", now, "network", "Mac Pro", "mac-pro"), now)
        var alerts = 0
        ConnectionsSource.toggleAlerts = { alerts++ }
        try {
            setContent { ConnectionsDialog(now) {} }
            // Collapsed until the flag is tapped.
            onNodeWithText("Restart the agent runner").assertDoesNotExist()
            onNodeWithContentDescription("Connection check: 1 incident").assertExists().performClick()
            onNodeWithText("Your Mac Pro is connected.\nIts runner needs a restart.").assertExists()
            onNodeWithText("Restart the agent runner").assertExists()
            onNodeWithText("One repair. Two features back.").assertExists()
            onNodeWithContentDescription("Device connections: Needs repair").assertExists()
            onNodeWithContentDescription("Builds & installs: Affected").assertExists()
            // Boss's widget has his controls only: agents read the incident with `oma connections incident`.
            onNodeWithText("[ Check again ]").performScrollTo().assertIsDisplayed()
            onAllNodesWithText("Details for agents", substring = true).assertCountEquals(0)
            onAllNodesWithText("Pause monitoring", substring = true).assertCountEquals(0)
            onNodeWithText("Notify me about unexpected outages and recovery").performScrollTo().performClick()
            assertEquals(1, alerts)
        } finally { ConnectionsSource.snapshot = ConnectionsSnapshot() }
    }

    private fun readyEverywhere(at: Long) = ConnectionsSnapshot(Capability.entries.filter { it != Capability.Models }.map { c ->
        ConnectionReading(c, ConnectionState.Ready, "evidence", at, if (c == Capability.Network) "network" else null, if (c == Capability.Network) "Mac Pro" else "")
    } + phoneModelReadings(listOf(InstalledAgent("claude", "Claude Code")), emptyList(),
        listOf(dev.omalauncher.oma.ChatTask(1, "Claude Code", "x", status = dev.omalauncher.oma.TaskStatus.Done, result = "fine")), at), at)

    @Test fun aFreshCheckStaysFreshWhenTheHomeTickIsFrozen() = runComposeUiTest {
        // The tick home hands the module moves only when home looks: here it stands 5 minutes behind the checks. Freshness is
        // judged on the clock, so nothing reads Stale (it read the whole sheet Stale when it was judged against the tick).
        val clock = 1_000_000_000L
        ConnectionsSource.clock = { clock }
        ConnectionsSource.snapshot = readyEverywhere(clock - 5_000)
        try {
            setContent { ConnectionsModule(ModuleSize.Card, clock - 300_000) }
            onNodeWithText("Connections").performClick()
            onNodeWithText("All capabilities", ignoreCase = true).performScrollTo().assertIsDisplayed()
            onAllNodesWithText("Stale").assertCountEquals(0)
            onAllNodesWithContentDescription("Device connections: Connected").onLast().assertExists()
            onAllNodesWithContentDescription("Device connections: Stale").assertCountEquals(0)
        } finally { ConnectionsSource.snapshot = ConnectionsSnapshot(); ConnectionsSource.clock = { System.currentTimeMillis() } }
    }

    @Test fun theSheetSaysConnectedForADeviceAndTheFlagOpensAndClosesTheCheck() = runComposeUiTest {
        val clock = 1_000_000_000L
        ConnectionsSource.clock = { clock }
        ConnectionsSource.snapshot = readyEverywhere(clock)
        try {
            setContent { ConnectionsDialog(clock) {} }
            onNodeWithContentDescription("Device connections: Connected").assertExists()
            onNodeWithContentDescription("Models: Healthy").assertExists()
            onNodeWithText("Agent runs", ignoreCase = true).assertDoesNotExist()
            // The flag is the button, collapsed first; its words and place are unchanged.
            onNodeWithText("Everything is ready.").assertDoesNotExist()
            onNodeWithText("✓ ALL READY ▾").assertExists()
            onNodeWithContentDescription("Connection check: All ready").assertHasClickAction().performClick()
            onNodeWithText("Everything is ready.").assertExists()
            onNodeWithText("✓ ALL READY ▴").assertExists()
            onNodeWithContentDescription("Connection check: All ready").performClick()
            onNodeWithText("Everything is ready.").assertDoesNotExist()
        } finally { ConnectionsSource.snapshot = ConnectionsSnapshot(); ConnectionsSource.clock = { System.currentTimeMillis() } }
    }

    @Test fun modelsListsEachAgentWithItsHealthAndTheRealError() = runComposeUiTest {
        val clock = 1_000_000_000L
        ConnectionsSource.clock = { clock }
        val tasks = listOf(dev.omalauncher.oma.ChatTask(1, "Codex", "x", status = dev.omalauncher.oma.TaskStatus.Failed, result = "You've hit your usage limit. Try later."))
        val lines = phoneModelReadings(listOf(InstalledAgent("claude", "Claude Code"), InstalledAgent("codex", "Codex")),
            listOf(AgentNeed("grok", "Grok", listOf("the front agent"))), tasks, clock)
        ConnectionsSource.snapshot = ConnectionsSnapshot(readyEverywhere(clock).readings.filter { it.capability != Capability.Models } + lines, clock)
        try {
            setContent { ConnectionsDialog(clock) {} }
            onNodeWithContentDescription("Claude Code: No recent run").assertExists()
            onNodeWithContentDescription("Codex: Usage limit. You've hit your usage limit. Try later.").assertExists()
            onNodeWithContentDescription("Grok: Missing").assertExists()
            onNodeWithContentDescription("Connection check: 2 incidents").assertExists()
        } finally { ConnectionsSnapshot().let { ConnectionsSource.snapshot = it }; ConnectionsSource.clock = { System.currentTimeMillis() } }
    }
}
