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
        for (summon in Summon.entries) for (launcher in listOf(true, false)) for (locked in listOf(true, false)) for (screenshot in listOf(true, false, null))
            if (screenContext(summon, launcher, locked, screenshot) == ScreenContext.Attached) attached++
        assertEquals(1, attached)
    }

    private fun gesture() = ScreenOffer(Summon.AssistGesture, launcherInFront = false, locked = false)

    @Test fun untilAndroidAnswersTheChipIsWaitingAndNothingIsAttached() {
        assertEquals(ScreenContext.Waiting, screenContext(Summon.AssistGesture, launcherInFront = false, locked = false, screenshot = null))
        assertEquals(ScreenContext.Waiting, gesture().context)
        // Where nothing could attach there is nothing to wait for either.
        for (launcher in listOf(true, false)) for (locked in listOf(true, false)) assertEquals(ScreenContext.None, screenContext(Summon.Other, launcher, locked, null))
        assertEquals(ScreenContext.None, screenContext(Summon.AssistGesture, launcherInFront = true, locked = false, screenshot = null))
        assertEquals(ScreenContext.None, screenContext(Summon.AssistGesture, launcherInFront = false, locked = true, screenshot = null))
    }
    @Test fun aScreenshotAttachesOnlyOnceItsFileIsWritten() {
        val saving = gesture().arrived(screenshot = true)
        assertEquals(Shot.Saving, saving.shot); assertEquals(ScreenContext.Waiting, saving.context)
        assertEquals(ScreenContext.Attached, saving.saved(written = true).context)
        assertEquals(ScreenContext.Unavailable, saving.saved(written = false).context)
        // A file can't be reported for a screenshot that never arrived.
        assertEquals(ScreenContext.Waiting, gesture().saved(written = true).context)
    }
    @Test fun unavailableOnlyAfterAndroidSaysSoOrStaysSilent() {
        assertEquals(ScreenContext.Unavailable, gesture().arrived(screenshot = false).context)
        assertEquals(ScreenContext.Unavailable, gesture().timedOut().context)
        // The timeout doesn't cut off a screenshot that is being written, or take one away.
        assertEquals(ScreenContext.Waiting, gesture().arrived(true).timedOut().context)
        assertEquals(ScreenContext.Attached, gesture().arrived(true).saved(true).timedOut().context)
        // One that comes after the chip said there is none is not attached behind the owner's back.
        assertEquals(ScreenContext.Unavailable, gesture().timedOut().arrived(true).saved(true).context)
    }
    @Test fun afterSendOrRemovalALateScreenshotIsDropped() {
        // Send tapped while still waiting: the request went without the screen.
        val sent = gesture().close()
        assertEquals(ScreenContext.None, sent.context)
        assertEquals(sent, sent.arrived(screenshot = true))
        assertNotEquals(Shot.Saving, sent.arrived(true).shot)
        assertEquals(ScreenContext.None, sent.arrived(true).saved(true).timedOut().context)
        // Closed while its file was being written.
        assertEquals(ScreenContext.None, gesture().arrived(true).close().saved(true).context)
        // Removed after it attached.
        assertEquals(ScreenContext.None, gesture().arrived(true).saved(true).close().context)
    }
    @Test fun whatCouldNeverAttachIsNotEvenSaved() {
        for (offer in listOf(ScreenOffer(Summon.Other, false, false), ScreenOffer(Summon.AssistGesture, true, false), ScreenOffer(Summon.AssistGesture, false, true))) {
            assertEquals(Shot.Awaited, offer.arrived(screenshot = true).shot)
            assertEquals(ScreenContext.None, offer.arrived(true).saved(true).context)
            assertEquals(ScreenContext.None, offer.timedOut().context)
        }
    }
    @Test fun learningThatOmaItselfIsUnderneathDetaches() {
        assertEquals(ScreenContext.None, gesture().arrived(true).saved(true).overOwnScreen().context)
        assertEquals(ScreenContext.None, gesture().overOwnScreen().arrived(true).saved(true).context)
    }
    @Test fun theChipSaysWhatHappensToTheScreen() {
        assertEquals("", screenChipText(ScreenContext.None, true))
        assertEquals("Getting this screen…", screenChipText(ScreenContext.Waiting, true))
        val claude = screenChipText(ScreenContext.Attached, keptInConversation = true)
        assertTrue(claude.contains("goes with this request and stays in this conversation until New thread"))
        assertFalse(screenChipText(ScreenContext.Attached, keptInConversation = false).contains("stays in this conversation"))
        for (kept in listOf(true, false)) {
            assertTrue(screenChipText(ScreenContext.Unavailable, kept).contains("isn't attached"))
            // Only the attached chip may read as if something is sent.
            for (state in listOf(ScreenContext.Waiting, ScreenContext.Unavailable, ScreenContext.None)) assertFalse(screenChipText(state, kept).contains("goes with"))
        }
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
