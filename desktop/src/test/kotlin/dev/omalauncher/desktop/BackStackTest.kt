package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.ui.BackStack
import dev.omalauncher.ui.LocalBackStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat window and the dashboard each answer Escape from their own stack (round-1 Critic: the chat's Escape ended the dashboard's arranging). */
@OptIn(ExperimentalTestApi::class)
class BackStackTest {
    @Test fun anEscapeInOneWindowNeverReachesTheOther() = runComposeUiTest {
        val chat = BackStack()
        val dashboard = BackStack()
        val took = mutableListOf<String>()
        setContent {
            Column {
                CompositionLocalProvider(LocalBackStack provides chat) { BackHandler(enabled = false) { took += "chat" } }
                CompositionLocalProvider(LocalBackStack provides dashboard) { BackHandler(enabled = true) { took += "dashboard" } }
            }
        }
        runOnIdle {
            // Nothing open in the chat: its Escape is the window's own (it hides), never the dashboard's arranging.
            assertFalse(chat.press())
            assertTrue(took.isEmpty())
            assertTrue(dashboard.press())
            assertEquals(listOf("dashboard"), took)
        }
    }
}
