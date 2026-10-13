package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test

class ChatComposerTest {
    private fun out(
        draft: String = "hello", images: Int = 0, missing: Boolean = false, busy: Boolean = false, linux: Boolean = true, agent: String? = "claude", working: Boolean = false,
        lane: Lane = Lane.Build, quickReady: Boolean = true, quickBusy: Boolean = false,
    ) = outgoing(draft, images, missing, busy, linux, agent, working, lane, quickReady, quickBusy)

    @Test fun typedTextGoesToTheBackgroundAgent() {
        assertEquals(Outgoing.Background("hello"), out("  hello \n"))
        assertEquals(Outgoing.Background("hello"), out(agent = "codex", images = 4))
    }
    @Test fun nothingIsSentWhileBusyOrEmpty() {
        assertEquals(Outgoing.Nothing, out(busy = true))
        assertEquals(Outgoing.Nothing, out(draft = "  "))
    }
    @Test fun whileATaskRunsWhatIsSentWaitsAsAFollowUp() {
        assertEquals(Outgoing.Queued("hello"), out("  hello \n", working = true))
        assertEquals(Outgoing.Queued("Describe these images."), out(draft = "", images = 2, agent = "codex", working = true))
        assertEquals(Outgoing.Nothing, out(draft = " ", working = true))
        assertEquals(Outgoing.Nothing, out(busy = true, working = true))
    }
    @Test fun aFollowUpIsRefusedForTheSameReasonsAsAMessage() {
        assertTrue(out(linux = false, working = true) is Outgoing.Refused)
        assertTrue(out(agent = null, working = true) is Outgoing.Refused)
        assertTrue(out(images = 1, missing = true, working = true) is Outgoing.Refused)
    }
    @Test fun noTerminalOpensBesideARunningTask() {
        assertEquals(Outgoing.Refused("Wait for the running task or stop it first."), out(agent = "grok", working = true))
    }
    @Test fun imagesAloneAskForADescription() {
        assertEquals(Outgoing.Background("Describe these images."), out(draft = "", images = 2))
    }
    @Test fun whatCannotBeSentIsRefusedWithAReason() {
        assertTrue(out(linux = false) is Outgoing.Refused)
        assertTrue(out(agent = null) is Outgoing.Refused)
        assertTrue(out(images = 1, missing = true) is Outgoing.Refused)
        assertEquals(Outgoing.Refused("Choose Claude Code or Codex to send images."), out(agent = "grok", images = 1))
    }
    @Test fun otherAgentsStillOpenInATerminal() {
        assertEquals(Outgoing.Terminal("hello"), out(agent = "grok"))
    }
    @Test fun aQuickQuestionGoesAtOnceWhetherOrNotATaskRuns() {
        assertEquals(Outgoing.Quick("hello"), out("  hello \n", lane = Lane.Quick))
        assertEquals(Outgoing.Quick("hello"), out(lane = Lane.Quick, working = true))
        // Whichever agent builds, or none: Claude Code answers quick questions.
        assertEquals(Outgoing.Quick("hello"), out(lane = Lane.Quick, agent = "grok", working = true))
        assertEquals(Outgoing.Quick("hello"), out(lane = Lane.Quick, agent = null))
        assertEquals(Outgoing.Nothing, out(draft = " ", lane = Lane.Quick))
        assertEquals(Outgoing.Nothing, out(busy = true, lane = Lane.Quick, working = true))
    }
    @Test fun aQuickQuestionTakesNoImages() {
        assertEquals(Outgoing.Refused(QUICK_NO_IMAGES), out(images = 1, lane = Lane.Quick))
        assertEquals(Outgoing.Refused(QUICK_NO_IMAGES), out(draft = "", images = 2, lane = Lane.Quick, working = true))
        assertEquals(Outgoing.Refused(QUICK_NO_IMAGES), out(images = 1, missing = true, lane = Lane.Quick))
        assertTrue(QUICK_NO_IMAGES.contains("build"))
    }
    @Test fun aSecondQuickQuestionWaitsForTheFirst() {
        assertEquals(Outgoing.Refused(QUICK_BUSY), out(lane = Lane.Quick, quickBusy = true))
        assertEquals(Outgoing.Refused(QUICK_BUSY), out(lane = Lane.Quick, quickBusy = true, working = true))
        assertEquals(Outgoing.Refused(QUICK_NEEDS_CLAUDE), out(lane = Lane.Quick, agent = "codex", quickReady = false))
        assertTrue(out(lane = Lane.Quick, linux = false) is Outgoing.Refused)
    }
    @Test fun theBuildLaneIsUntouchedByTheQuickOne() {
        // A quick question being answered, or no Claude Code to answer one, changes nothing about a build message.
        for (quickBusy in listOf(false, true)) for (quickReady in listOf(false, true)) {
            assertEquals(Outgoing.Background("hello"), out(quickBusy = quickBusy, quickReady = quickReady))
            assertEquals(Outgoing.Queued("hello"), out(working = true, quickBusy = quickBusy, quickReady = quickReady))
            assertEquals(Outgoing.Background("Describe these images."), out(draft = "", images = 2, quickBusy = quickBusy, quickReady = quickReady))
            assertEquals(Outgoing.Terminal("hello"), out(agent = "grok", quickBusy = quickBusy, quickReady = quickReady))
        }
        assertEquals(outgoing("hello", 0, false, false, true, "claude", true), out(working = true, lane = Lane.Build))
    }
    @Test fun theLaneIsSavedByItsWordAndStartsAsBuild() {
        assertEquals(listOf("quick", "build"), Lane.entries.map { it.id })
        for (lane in Lane.entries) assertEquals(lane, Lane.of(lane.id))
        assertEquals(Lane.Build, Lane.of(null)); assertEquals(Lane.Build, Lane.of("??"))
    }
    @Test fun atMostFourImagesAreAttached() {
        assertEquals(4 to null, imagesToTake(0, 4))
        assertEquals(1 to "You can attach up to 4 images.", imagesToTake(3, 2))
        assertEquals(0 to "You can attach up to 4 images.", imagesToTake(4, 1))
        assertEquals(2 to null, imagesToTake(1, 2))
        assertEquals(0 to null, imagesToTake(4, 0))
    }
}
