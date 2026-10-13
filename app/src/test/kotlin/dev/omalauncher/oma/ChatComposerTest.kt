package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test

class ChatComposerTest {
    private fun out(draft: String = "hello", images: Int = 0, missing: Boolean = false, busy: Boolean = false, linux: Boolean = true, agent: String? = "claude", working: Boolean = false) =
        outgoing(draft, images, missing, busy, linux, agent, working)

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
    @Test fun atMostFourImagesAreAttached() {
        assertEquals(4 to null, imagesToTake(0, 4))
        assertEquals(1 to "You can attach up to 4 images.", imagesToTake(3, 2))
        assertEquals(0 to "You can attach up to 4 images.", imagesToTake(4, 1))
        assertEquals(2 to null, imagesToTake(1, 2))
        assertEquals(0 to null, imagesToTake(4, 0))
    }
}
