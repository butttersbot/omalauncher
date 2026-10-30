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
        setContent { Box(Modifier.size(235.dp, 180.dp)) { ConnectionsModule(ModuleSize.Card, 100_000) } }
        onNodeWithText("[Restore]").assertIsDisplayed().performClick()
        assertEquals(1, restores)
        onNodeWithContentDescription("Close Connections").assertDoesNotExist()
        onNodeWithText("Connections").performClick()
        onNodeWithContentDescription("Close Connections").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Close Connections").assertDoesNotExist()
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
    @Test fun incidentShowsTheCardsHeadlineStepsAndKeepsTheAgentTools() = runComposeUiTest {
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
            onNodeWithText("Your Mac Pro is connected.\nIts runner needs a restart.").assertExists()
            onNodeWithText("Restart the agent runner").assertExists()
            onNodeWithText("One repair. Two features back.").assertExists()
            onNodeWithContentDescription("Agent runs: Needs repair").assertExists()
            onNodeWithContentDescription("Builds & installs: Affected").assertExists()
            onNodeWithText("[ Details for agents ]").performScrollTo().assertIsDisplayed()
            onNodeWithText("[ Check again ]").assertExists()
            onNodeWithText("Notify me about unexpected outages and recovery").performScrollTo().performClick()
            assertEquals(1, alerts)
        } finally { ConnectionsSource.snapshot = ConnectionsSnapshot() }
    }
}
