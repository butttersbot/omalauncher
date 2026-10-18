package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test

class ChatComposerTest {
    private fun out(
        draft: String = "hello", images: Int = 0, missing: Boolean = false, busy: Boolean = false, linux: Boolean = true, agent: String? = "claude", working: Boolean = false,
        frontReady: Boolean = false, to: SendTo = SendTo.Oma,
    ) = outgoing(draft, images, missing, busy, linux, agent, working, frontReady, to)

    /** A thread's own tab (Boss, 2026-10-13): the field talks straight to the thread, and the front agent is never asked. */
    @Test fun onTheBuildersTabWordsGoStraightToTheBuildLane() {
        assertEquals(Outgoing.Background("hello"), out(frontReady = true, to = SendTo.Phone))
        assertEquals(Outgoing.Queued("hello"), out(frontReady = true, working = true, to = SendTo.Phone))
        assertEquals(Outgoing.Background("hello"), out(images = 1, frontReady = true, to = SendTo.Phone))
        // Refused for the reasons a message to the build lane always was.
        assertTrue(out(linux = false, frontReady = true, to = SendTo.Phone) is Outgoing.Refused)
        assertTrue(out(agent = null, to = SendTo.Phone) is Outgoing.Refused)
        assertEquals(Outgoing.Nothing, out(draft = " ", to = SendTo.Phone))
    }
    @Test fun onAMacThreadsTabWordsGoStraightToThatThread() {
        val pond = SendTo.Mac("t-pond", "Pond")
        assertEquals(Outgoing.Mac("hello", "t-pond", "Pond"), out("  hello \n", frontReady = true, to = pond))
        // Whatever the phone's builder is doing, and with no Linux or agent on the phone: the Mac does this work.
        assertEquals(Outgoing.Mac("hello", "t-pond", "Pond"), out(working = true, to = pond))
        assertEquals(Outgoing.Mac("hello", "t-pond", "Pond"), out(linux = false, agent = null, to = pond))
        assertEquals(Outgoing.Mac("hello", "t-pond", "Pond"), out(agent = "grok", working = true, to = pond))
        assertEquals(Outgoing.Nothing, out(draft = " ", to = pond)); assertEquals(Outgoing.Nothing, out(busy = true, to = pond))
        // The runner takes no images: nothing is sent, he is told why, and they never go to the builder instead.
        assertEquals(Outgoing.Refused(noImagesTo("Pond")), out(images = 1, to = pond))
        assertEquals(Outgoing.Refused(noImagesTo("Pond")), out(draft = "", images = 2, working = true, to = pond))
        assertTrue(noImagesTo("Pond").contains("Pond"))
    }
    @Test fun theOmaTabSendsAsItAlwaysDid() {
        for (working in listOf(true, false)) for (ready in listOf(true, false)) for (images in listOf(0, 1)) {
            assertEquals(outgoing("hello", images, false, false, true, "claude", working, ready), out(images = images, working = working, frontReady = ready, to = SendTo.Oma))
        }
    }

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
        assertEquals(Outgoing.Refused("Wait for the running task or stop it first."), out(agent = "gemini", working = true))
    }
    @Test fun imagesAloneAskForADescription() {
        assertEquals(Outgoing.Background("Describe these images."), out(draft = "", images = 2))
    }
    @Test fun whatCannotBeSentIsRefusedWithAReason() {
        assertTrue(out(linux = false) is Outgoing.Refused)
        assertTrue(out(agent = null) is Outgoing.Refused)
        assertTrue(out(images = 1, missing = true) is Outgoing.Refused)
        assertEquals(Outgoing.Refused("Choose Claude Code, Codex or Grok to send images."), out(agent = "gemini", images = 1))
    }
    @Test fun otherAgentsStillOpenInATerminal() {
        assertEquals(Outgoing.Terminal("hello"), out(agent = "gemini"))
    }
    /** The lane choice is gone on purpose (Boss, 2026-10-13): these replace the tests of the quick and build lanes. */
    @Test fun wordsAloneAreHeardByTheFrontAgentFirstWhetherOrNotATaskRuns() {
        assertEquals(Outgoing.Front("hello"), out("  hello \n", frontReady = true))
        assertEquals(Outgoing.Front("hello"), out(frontReady = true, working = true))
        // Whichever background agent builds: Claude Code is the front agent.
        assertEquals(Outgoing.Front("hello"), out(agent = "codex", frontReady = true, working = true))
        assertEquals(Outgoing.Nothing, out(draft = " ", frontReady = true))
        assertEquals(Outgoing.Nothing, out(busy = true, frontReady = true, working = true))
    }
    @Test fun imagesGoStraightToTheBuildLane() {
        assertEquals(Outgoing.Background("hello"), out(images = 1, frontReady = true))
        assertEquals(Outgoing.Queued("Describe these images."), out(draft = "", images = 2, frontReady = true, working = true))
        assertTrue(out(images = 1, missing = true, frontReady = true) is Outgoing.Refused)
    }
    /**
     * Changed on purpose (Boss, 2026-10-13: "I should be able to send as many messages as I want"). This used to
     * pin that words sent while the front agent was still on a request went past it, straight to the builder.
     * Send no longer knows whether the front agent is busy at all: his words are for it to hear, now or next.
     */
    @Test fun whatIsSentWhileTheFrontAgentIsBusyStillGoesToItAndIsNeverRefused() {
        assertEquals(Outgoing.Front("hello"), out(frontReady = true))
        assertEquals(Outgoing.Front("hello"), out(frontReady = true, working = true))
        // Nothing a busy builder or a busy front agent changes: the only things in the way are his own draft and setup.
        for (working in listOf(false, true)) {
            assertTrue(out(frontReady = true, working = working) !is Outgoing.Refused)
            assertTrue(out(images = 1, frontReady = true, working = working) !is Outgoing.Refused)
            assertTrue(out(frontReady = false, working = working) !is Outgoing.Refused)
        }
    }
    @Test fun withNoFrontAgentEverythingGoesAsItAlwaysDid() {
        assertEquals(Outgoing.Background("hello"), out())
        assertEquals(Outgoing.Queued("hello"), out(working = true))
        assertEquals(Outgoing.Background("Describe these images."), out(draft = "", images = 2))
        assertEquals(outgoing("hello", 0, false, false, true, "claude", true), out(working = true))
    }
    @Test fun aTerminalAgentIsNeverRoutedThroughTheFrontAgent() {
        for (frontReady in listOf(false, true)) {
            assertEquals(Outgoing.Terminal("hello"), out(agent = "gemini", frontReady = frontReady))
            assertEquals(Outgoing.Refused("Wait for the running task or stop it first."), out(agent = "gemini", working = true, frontReady = frontReady))
        }
    }
    @Test fun theFrontAgentDoesNotGetWhatCouldNotBeSentAnyway() {
        assertTrue(out(linux = false, frontReady = true) is Outgoing.Refused)
        assertTrue(out(agent = null, frontReady = true) is Outgoing.Refused)
    }
    @Test fun aRequestLongerThanTheThreadKeepsGoesStraightToTheBuildLane() {
        val whole = "a".repeat(ChatHistory.MAX_PROMPT)
        assertEquals(Outgoing.Front(whole), out(whole, frontReady = true))
        assertEquals(Outgoing.Background(whole + "b"), out(whole + "b", frontReady = true))
        assertEquals(Outgoing.Queued(whole + "b"), out(whole + "b", frontReady = true, working = true))
    }
    @Test fun atMostFourImagesAreAttached() {
        assertEquals(4 to null, imagesToTake(0, 4))
        assertEquals(1 to "You can attach up to 4 images.", imagesToTake(3, 2))
        assertEquals(0 to "You can attach up to 4 images.", imagesToTake(4, 1))
        assertEquals(2 to null, imagesToTake(1, 2))
        assertEquals(0 to null, imagesToTake(4, 0))
    }

    /**
     * Boss 2026-10-13 ("The plus button should always open up a Mac session"): a new chat is heard by Oma first and
     * never goes to the phone's builders. With no front agent its words go straight to the Mac (OPEN-BUGS #13); with
     * images nothing is sent from it.
     */
    @Test fun aNewChatGoesToOmaFirstAndNeverToTheBuilders() {
        val chat = SendTo.Chat("c1", "New chat")
        assertEquals(Outgoing.Front("hello"), out(frontReady = true, to = chat))
        assertEquals(Outgoing.Front("hello"), out(frontReady = true, working = true, to = chat))
        assertEquals(Outgoing.Refused(NO_IMAGES_IN_CHAT), out(images = 1, frontReady = true, to = chat))
        assertEquals(Outgoing.Refused(NO_IMAGES_IN_CHAT), out(images = 1, linux = false, to = chat))
        assertEquals(Outgoing.Refused(CHAT_MAKING), out(frontReady = true, to = SendTo.ChatStarting("c1", "N")))
        assertEquals(Outgoing.Mac("hello", "t-new", "Menu"), out(frontReady = true, to = SendTo.Mac("t-new", "Menu", "c1")))
    }

    /** OPEN-BUGS #13: a new chat's work is the Mac's, so the phone's Linux and agents are not needed to start one. */
    @Test fun aNewChatWithNoFrontAgentGoesStraightToTheMac() {
        val chat = SendTo.Chat("c1", "New chat")
        val straight = Outgoing.ChatMac("hello", "c1")
        assertEquals(straight, out("  hello \n", linux = false, frontReady = true, to = chat))
        assertEquals(straight, out(linux = false, agent = null, to = chat))
        assertEquals(straight, out(agent = null, to = chat))
        assertEquals(straight, out(agent = "gemini", frontReady = true, to = chat))
        assertEquals(straight, out(frontReady = false, to = chat))
        assertEquals(straight, out(frontReady = false, working = true, to = chat))
        // More than the front agent takes goes as it is too, rather than being refused.
        val long = "x".repeat(dev.omalauncher.assist.FRONT_MAX_REQUEST + 1)
        assertEquals(Outgoing.ChatMac(long, "c1"), out(long, frontReady = true, to = chat))
        // Nothing in the field, or something still in the way, sends nothing; a chat whose thread is being made waits.
        assertEquals(Outgoing.Nothing, out(draft = " ", linux = false, to = chat))
        assertEquals(Outgoing.Nothing, out(busy = true, linux = false, to = chat))
        assertEquals(Outgoing.Refused(CHAT_MAKING), out(linux = false, to = SendTo.ChatStarting("c1", "N")))
        // Once it has its thread, as before: straight to it.
        assertEquals(Outgoing.Mac("hello", "t-new", "Menu"), out(linux = false, agent = null, to = SendTo.Mac("t-new", "Menu", "c1")))
        // The Oma tab and the builders' still need the phone.
        assertTrue(out(linux = false, frontReady = true) is Outgoing.Refused)
        assertTrue(out(linux = false, to = SendTo.Phone) is Outgoing.Refused)
    }
}
