package dev.omalauncher.oma

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.omalauncher.assist.BuilderId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Enter on a hardware keyboard (Boss, 2026-10-29, from MG1): on the desktop it sends and Shift+Enter starts a new line; on
 * the phone Return starts a new line (Boss, 2026-10-25) and the send button sends. Ctrl+Enter sends on both.
 */
@OptIn(ExperimentalTestApi::class)
class EnterSendsTest : ChatScreenTestBase() {
    private fun pressed(enterSends: Boolean, keys: androidx.compose.ui.test.KeyInjectionScope.() -> Unit): List<String> {
        val sent = mutableListOf<String>()
        runComposeUiTest {
            val host = object : SampleHost() {
                override val enterSends: Boolean get() = enterSends
                override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) { sent += prompt }
                override fun askFront(prompt: String, chat: String) { sent += prompt }
            }
            // A builder at work keeps the screen moving, so time is advanced by hand rather than waited out.
            mainClock.autoAdvance = false
            setContent { OmaChatScreen(host, null, {}, null, {}) }
            mainClock.advanceTimeBy(600)
            onNode(hasSetTextAction()).performTextInput("hello there")
            mainClock.advanceTimeBy(100)
            onNode(hasSetTextAction()).performKeyInput(keys)
            mainClock.advanceTimeBy(600)
        }
        return sent
    }

    @Test fun onTheDesktopEnterSendsAndShiftEnterStartsANewLine() {
        assertEquals(listOf("hello there"), pressed(true) { pressKey(Key.Enter) })
        assertEquals(emptyList<String>(), pressed(true) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } })
        assertEquals(listOf("hello there"), pressed(true) { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } })
    }

    @Test fun onThePhoneReturnStartsANewLineAndCtrlEnterSends() {
        assertEquals(emptyList<String>(), pressed(false) { pressKey(Key.Enter) })
        assertEquals(listOf("hello there"), pressed(false) { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } })
    }
}
