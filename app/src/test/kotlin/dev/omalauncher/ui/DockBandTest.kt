package dev.omalauncher.ui

import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers home lays its grid out by, which the dock must leave as the prompt bar had them. What the band
 * really measures on a screen can't be checked here: these only pin the constants and the sum.
 */
class DockBandTest {
    @Test fun theRowIsAsTallAsTheBarWas() {
        assertEquals(48.dp, PromptBarHeight)
        // An icon fills the row and no more, so pinned apps can't make the band taller.
        assertEquals(PromptBarHeight, DockSlot)
        assertEquals(6.dp, DockBandPadding)
        assertEquals(6.dp, HomeGap)
    }

    @Test fun theRecentStripKeepsItsHeight() {
        assertEquals(56.dp, RecentStripHeight)
    }

    @Test fun homeIsToldTheMeasuredFrameAndAGapAboveAndBelow() {
        assertEquals(0 + 2 * 16, bandHeight(0, 16))
        assertEquals(273 + 2 * 16, bandHeight(273, 16))
        assertEquals(312, bandHeight(300, 6))
    }

    @Test fun iconsKeepAtLeastEightBetweenThem() {
        assertTrue(DockGap >= 8.dp)
    }

    @Test fun onlyAPhysicalKeyboardPutsTheCursorInTheSearchFieldOnOpening() {
        assertFalse(focusesSearchOnOpen(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES))
        assertFalse(focusesSearchOnOpen(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_NO))
        assertFalse(focusesSearchOnOpen(Configuration.KEYBOARD_UNDEFINED, Configuration.HARDKEYBOARDHIDDEN_UNDEFINED))
        assertFalse(focusesSearchOnOpen(Configuration.KEYBOARD_12KEY, Configuration.HARDKEYBOARDHIDDEN_NO))
        // Attached but folded away.
        assertFalse(focusesSearchOnOpen(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES))
        assertTrue(focusesSearchOnOpen(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
    }

    @Test fun requestsGoWhereTheAgentIs() {
        assertEquals(AgentRoute.InstallLinux, agentRoute(linux = false, bin = "claude"))
        assertEquals(AgentRoute.InstallLinux, agentRoute(linux = false, bin = null))
        assertEquals(AgentRoute.ChooseAgent, agentRoute(linux = true, bin = null))
        assertEquals(AgentRoute.Background, agentRoute(linux = true, bin = "claude"))
        assertEquals(AgentRoute.Background, agentRoute(linux = true, bin = "codex"))
        assertEquals(AgentRoute.Terminal, agentRoute(linux = true, bin = "gemini"))
    }

    @Test fun theChatIsOpenedByTheAgreedAction() {
        assertEquals("dev.omalauncher.action.OMA_CHAT", OMA_CHAT_ACTION)
    }
}
