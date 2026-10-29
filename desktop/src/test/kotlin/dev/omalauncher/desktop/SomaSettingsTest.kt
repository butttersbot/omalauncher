package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.ActivePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Desktop Soma's settings (Boss, 2026-10-29: "there's actually no settings button on the desktop apps"): the phone's rows
 * where they apply, Cmd+, or Ctrl+, from any window, and a launcher entry for Omarchy's Walker.
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class SomaSettingsTest {
    private val home = Files.createTempDirectory("soma-settings").toFile().also { System.setProperty("soma.home", it.path) }

    private fun key(key: Key, ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false) =
        KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = 0, isCtrlPressed = ctrl, isMetaPressed = meta, isShiftPressed = shift)

    @Test fun cmdOrCtrlCommaOpensSettingsAndNothingElseDoes() {
        var opened = 0
        assertTrue(settingsKey(key(Key.Comma, meta = true)) { opened++ })
        assertTrue(settingsKey(key(Key.Comma, ctrl = true)) { opened++ })
        assertFalse(settingsKey(key(Key.Comma)) { opened++ })
        assertFalse(settingsKey(key(Key.Comma, ctrl = true, shift = true)) { opened++ })
        assertFalse(settingsKey(key(Key.Period, meta = true)) { opened++ })
        assertEquals(2, opened)
    }

    @Test fun aSecondSomaCanHandTheRunningOneSettings() {
        // `soma --settings` while Soma runs hands it "settings" (a Hyprland keybind, Walker's entry); it was dropped at first.
        assertTrue(handOffWord("settings"))
        assertTrue(handOffWord("show"))
        assertTrue(handOffWord("toggle"))
        assertFalse(handOffWord("settings please"))
        assertFalse(handOffWord("quit"))
    }

    @Test fun omarchysLauncherGetsASomaSettingsEntry() {
        val entry = Login.settingsEntry(listOf("/usr/bin/java", "-jar", "/home/neetzy/Soma/builds/Soma.jar"))
        assertTrue(entry, entry.contains("Name=Soma Settings"))
        assertTrue(entry, entry.contains("Exec=\"/usr/bin/java\" \"-jar\" \"/home/neetzy/Soma/builds/Soma.jar\" --settings"))
    }

    @Test fun theRowsAreThePhonesAndEachDoesWhatItSays() = runComposeUiTest {
        Looks.fonts()
        Looks.use("city-pop")
        var closed = false
        setContent { Box(Modifier.size(640.dp, 720.dp)) { SomaSettings(onClose = { closed = true }) } }
        waitForIdle()
        for (row in listOf("look", "start at login", "computers", "enter sends")) onNodeWithContentDescription("setting $row").assertExists()
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/settings-rows.png"))
        // Enter sends: on by default, a click turns it off and it is kept.
        assertTrue(DesktopSettings.enterSends)
        onNodeWithContentDescription("setting enter sends").performClick()
        waitForIdle()
        assertFalse(DesktopSettings.enterSends)
        assertFalse(SomaHome.read(SomaHome.settings).optBoolean("enterSends", true))
        onNodeWithText("off · Enter starts a new line, Ctrl+Enter sends").assertExists()
        onNodeWithContentDescription("setting enter sends").performClick()
        waitForIdle()
        assertTrue(DesktopSettings.enterSends)
        // Look: every look, and a click uses it.
        onNodeWithContentDescription("setting look").performClick()
        waitForIdle()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/settings-looks.png"))
        val other = Looks.swatches.first { it.id != ActivePack.pick.id }
        onNodeWithContentDescription("look ${other.name}").performClick()
        waitForIdle()
        assertEquals(other.id, ActivePack.pick.id)
        onNodeWithText("[ back ]").performClick()
        waitForIdle()
        // Computers: none paired here, and how to pair a computer or the phone.
        onNodeWithContentDescription("setting computers").performClick()
        waitForIdle()
        onNodeWithText("No computer is paired with this Soma yet.").assertExists()
        onNodeWithText("The phone's sessions: on the phone, Settings › phone sessions › pair", substring = true).assertExists()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/settings-computers.png"))
        onNodeWithText("[ back ]").performClick()
        waitForIdle()
        onNodeWithText("[ close ]").performClick()
        assertTrue(closed)
        Looks.use("city-pop")
    }
}
