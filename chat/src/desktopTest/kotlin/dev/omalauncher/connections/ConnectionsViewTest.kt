package dev.omalauncher.connections

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.omalauncher.canvas.ModuleSize
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ConnectionsViewTest {
    @Test fun restoreDoesNotOpenDetailsAndBodyDoes() = runComposeUiTest {
        var restores = 0
        ConnectionsSource.restore = { restores++ }
        setContent { Box(Modifier.size(235.dp, 180.dp)) { ConnectionsModule(ModuleSize.Card, 100_000) } }
        onNodeWithText("[ Restore ]").assertIsDisplayed().performClick()
        assertEquals(1, restores)
        onNodeWithContentDescription("Close Connections").assertDoesNotExist()
        onNodeWithText("Connections").performClick()
        onNodeWithContentDescription("Close Connections").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Close Connections").assertDoesNotExist()
    }
    @Test fun expandedInventoryHasCoreCanvasAndDistinctUnknownState() = runComposeUiTest {
        setContent { ConnectionsDialog(100_000) {} }
        onNodeWithText("All capabilities · 12 groups").performScrollTo().assertIsDisplayed()
        onNodeWithText("▸ Design Canvas").performScrollTo().assertIsDisplayed().performClick()
        onAllNodesWithText("Not checked yet.").onLast().assertExists()
    }
}
