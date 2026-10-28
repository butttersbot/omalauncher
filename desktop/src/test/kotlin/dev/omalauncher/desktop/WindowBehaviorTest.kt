package dev.omalauncher.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.omalauncher.ui.BackStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.desktop.AppReopenedEvent
import javax.swing.SwingUtilities

/**
 * Soma's window never goes away by itself (Boss, 2026-10-28: "soma window on mac pro disappeared again"; the MacBook's
 * fix of 2026-10-21, 0b159117, lost in desktop parity slice 1): Escape closes panels and never the window, and a Dock click
 * shows the same window, hidden, minimized or already up.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class WindowBehaviorTest {
    private fun key(key: Key, type: KeyEventType = KeyEventType.KeyDown) = KeyEvent(key = key, type = type, codePoint = 0)

    @Test fun escapeClosesTheInnermostPanelAndThenLeavesTheWindowAlone() {
        val back = BackStack()
        var outer = true
        var inner = true
        back.add { if (outer) { outer = false; true } else false }
        back.add { if (inner) { inner = false; true } else false }
        assertFalse(somaEscape(key(Key.Enter), back))
        assertFalse(somaEscape(key(Key.Escape, KeyEventType.KeyUp), back))
        assertTrue(inner)
        assertTrue(somaEscape(key(Key.Escape), back))
        assertFalse(inner)
        assertTrue(outer)
        assertTrue(somaEscape(key(Key.Escape), back))
        assertFalse(outer)
        // Nothing open: Escape is not taken, and there is no "hide the window" left behind it.
        assertFalse(somaEscape(key(Key.Escape), back))
        assertFalse(somaEscape(key(Key.Escape), back))
    }

    @Test fun aDockClickShowsTheSameWindowHiddenMinimizedOrAlreadyUp() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        lateinit var window: Frame
        var shows = 0
        SwingUtilities.invokeAndWait { window = Frame("Soma window test").apply { setSize(320, 240) } }
        val listener = somaReopenListener {
            assertTrue("on the window's thread", SwingUtilities.isEventDispatchThread())
            shows++
            toPointerScreen(window)
        }
        fun reopen() {
            listener.appReopened(AppReopenedEvent())
            SwingUtilities.invokeAndWait {
                assertTrue(window.isVisible)
                assertEquals(0, window.extendedState and Frame.ICONIFIED)
            }
        }
        try {
            reopen()
            SwingUtilities.invokeAndWait { window.extendedState = Frame.ICONIFIED }
            reopen()
            reopen()
            SwingUtilities.invokeAndWait { window.isVisible = false }
            reopen()
            assertEquals(4, shows)
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
