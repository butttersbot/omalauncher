package dev.omalauncher.oma

import androidx.compose.ui.test.*
import dev.omalauncher.ui.PhoneRunnerPairing
import dev.omalauncher.ui.PhoneRunnerSetting
import org.junit.Assert.*
import org.junit.Test

class PhoneRunnerSettingTapTest : ChatScreenTestBase() {
    @OptIn(ExperimentalTestApi::class)
    @Test fun viewingPairingNeverPairsAndOnlyTheSelectedNativeTapChangesIt() = runComposeUiTest {
        val paired = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val backend = object : PhoneRunnerPairing {
            override fun devices() = listOf("jacks-mac-pro", "macbook-pro")
            override fun paired() = paired.toList()
            override fun pair(device: String) { calls += "pair:$device"; paired += device }
            override fun unpair(device: String) { calls += "unpair:$device"; paired -= device }
        }
        setContent { PhoneRunnerSetting(backend) {} }
        waitUntil(5000) { onAllNodesWithText("[ pair ]").fetchSemanticsNodes().size == 2 }
        assertTrue(calls.isEmpty())
        onAllNodesWithText("[ pair ]").onFirst().performClick()
        waitUntil(5000) { onAllNodesWithText("[ unpair ]").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf("pair:jacks-mac-pro"), calls)
        onNodeWithText("[ unpair ]").performClick()
        waitUntil(5000) { onAllNodesWithText("[ pair ]").fetchSemanticsNodes().size == 2 }
        assertEquals(listOf("pair:jacks-mac-pro", "unpair:jacks-mac-pro"), calls)
        assertTrue(paired.isEmpty())
    }
}
