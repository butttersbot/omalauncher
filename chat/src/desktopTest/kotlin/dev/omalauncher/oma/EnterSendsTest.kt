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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** The Mac's way (Boss, 2026-10-29): Return reaches the field as a typed line break, never as a key the field sees. */
    private fun typedNewline(enterSends: Boolean, shift: Boolean = false): Pair<List<String>, String> {
        val sent = mutableListOf<String>()
        var left = ""
        runComposeUiTest {
            val host = object : SampleHost() {
                override val enterSends: Boolean get() = enterSends
                override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) { sent += prompt }
                override fun askFront(prompt: String, chat: String) { sent += prompt }
            }
            mainClock.autoAdvance = false
            setContent { OmaChatScreen(host, null, {}, null, {}) }
            mainClock.advanceTimeBy(600)
            val field = onNode(hasSetTextAction())
            field.performTextInput("hello there")
            mainClock.advanceTimeBy(100)
            if (shift) field.performKeyInput { keyDown(Key.ShiftLeft) }
            field.performTextInput("\n")
            if (shift) field.performKeyInput { keyUp(Key.ShiftLeft) }
            mainClock.advanceTimeBy(600)
            left = field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        }
        return sent to left
    }

    @Test fun aReturnThatArrivesAsTextSendsOnTheDesktopAndIsANewLineOnThePhone() {
        assertEquals(listOf("hello there"), typedNewline(enterSends = true).first)
        val (none, kept) = typedNewline(enterSends = false)
        assertEquals(emptyList<String>(), none)
        assertEquals("hello there\n", kept)
        val (shifted, line) = typedNewline(enterSends = true, shift = true)
        assertEquals(emptyList<String>(), shifted)
        assertEquals("hello there\n", line)
    }

    @Test fun onlyOneTypedLineBreakCountsAsReturn() {
        assertTrue(newlineTyped("hello", "hello\n"))
        assertTrue(newlineTyped("hello", "hel\nlo"))
        assertTrue(newlineTyped("", "\n"))
        assertFalse("a paste of several lines", newlineTyped("hello", "hello\nthere"))
        assertFalse("a letter", newlineTyped("hello", "hellos"))
        assertFalse("the same", newlineTyped("hello", "hello"))
        assertFalse("a deletion", newlineTyped("hello\n", "hello"))
    }

    @Test fun onThePhoneReturnStartsANewLineAndCtrlEnterSends() {
        assertEquals(emptyList<String>(), pressed(false) { pressKey(Key.Enter) })
        assertEquals(listOf("hello there"), pressed(false) { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } })
    }
}
