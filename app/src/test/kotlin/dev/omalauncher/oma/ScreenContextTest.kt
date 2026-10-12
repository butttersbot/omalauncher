package dev.omalauncher.oma

import dev.omalauncher.assist.ClaudeProtocol
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ScreenContextTest {
    @Test fun theAssistGestureOverAnotherAppAttachesAndroidsScreenshot() {
        assertEquals(ScreenContext.Attached, screenContext(Summon.AssistGesture, launcherInFront = false, locked = false, screenshot = true))
    }
    @Test fun aSecureScreenAttachesNothingAndSaysSo() {
        assertEquals(ScreenContext.Unavailable, screenContext(Summon.AssistGesture, launcherInFront = false, locked = false, screenshot = false))
    }
    @Test fun nothingIsAttachedOverTheLauncher() {
        for (screenshot in listOf(true, false))
            assertEquals(ScreenContext.None, screenContext(Summon.AssistGesture, launcherInFront = true, locked = false, screenshot = screenshot))
    }
    @Test fun nothingIsAttachedOnTheLockScreen() {
        for (screenshot in listOf(true, false)) for (launcher in listOf(true, false))
            assertEquals(ScreenContext.None, screenContext(Summon.AssistGesture, launcher, locked = true, screenshot = screenshot))
    }
    @Test fun noOtherWayInAttachesTheScreenEvenIfAScreenshotExists() {
        for (launcher in listOf(true, false)) for (locked in listOf(true, false)) for (screenshot in listOf(true, false))
            assertEquals(ScreenContext.None, screenContext(Summon.Other, launcher, locked, screenshot))
    }
    @Test fun onlyOneCombinationAttaches() {
        var attached = 0
        for (summon in Summon.entries) for (launcher in listOf(true, false)) for (locked in listOf(true, false)) for (screenshot in listOf(true, false))
            if (screenContext(summon, launcher, locked, screenshot) == ScreenContext.Attached) attached++
        assertEquals(1, attached)
    }

    @Test fun screenshotsAreScaledDownAndNeverUp() {
        assertEquals(1600 to 1207, screenshotSize(2448, 1848))
        assertEquals(1012 to 1600, screenshotSize(1248, 1972))
        assertEquals(800 to 600, screenshotSize(800, 600))
        assertEquals(1600 to 1, screenshotSize(100_000, 10))
        assertThrows(IllegalArgumentException::class.java) { screenshotSize(0, 10) }
    }

    @Test fun codexGetsACopyAndTheChipsFileIsGone() {
        val dir = Files.createTempDirectory("oma-screen-test").toFile()
        try {
            val chip = java.io.File(dir, "private/screen-1.jpg").apply { parentFile!!.mkdirs(); writeText("pixels") }
            val attachments = java.io.File(dir, "attachments")
            val copy = ScreenShots.handOver(chip, attachments)
            assertFalse(chip.exists())
            assertEquals("pixels", copy.readText())
            assertEquals(attachments, copy.parentFile)
            java.io.File(attachments, "picked-by-the-owner.jpg").writeText("keep")
            // What a killed process left is cleared at the next start, and nothing else.
            ScreenShots.discardHandedOver(attachments)
            assertEquals(listOf("picked-by-the-owner.jpg"), attachments.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun claudeGetsTheScreenshotInsideTheMessage() {
        val plain = ClaudeProtocol.user("hello").getJSONObject("message")
        assertEquals("hello", plain.getString("content"))
        val content = ClaudeProtocol.user("what is this?", "QUJD").getJSONObject("message").getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("what is this?", content.getJSONObject(0).getString("text"))
        val source = content.getJSONObject(1).getJSONObject("source")
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals("base64", source.getString("type")); assertEquals("image/jpeg", source.getString("media_type")); assertEquals("QUJD", source.getString("data"))
    }
}
