package dev.omalauncher.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerCardTest {
    private val fine = OverlayFacts(allowed = false, connected = true, paused = false, locked = false, awake = true)

    /** The ring's setting has no say over the answer. */
    @Test fun showsOverAnUnlockedAwakePhoneWhateverTheRingSettingSays() {
        assertTrue(answerCardWanted(fine))
        assertTrue(answerCardWanted(fine.copy(allowed = true)))
        // Over the launcher's own screens too: home has no card of its own.
        assertTrue(answerCardWanted(fine.copy(ownScreen = true)))
    }

    @Test fun neverOverTheLockScreenOrASleepingPhone() {
        assertFalse(answerCardWanted(fine.copy(locked = true)))
        assertFalse(answerCardWanted(fine.copy(awake = false)))
        assertFalse(answerCardWanted(OverlayFacts()))
    }

    @Test fun notWithoutPhoneControl() {
        assertFalse(answerCardWanted(fine.copy(connected = false)))
        assertFalse(answerCardWanted(fine.copy(paused = true)))
    }

    /** Neither Android's screenshot for the assistant nor the agent's own may catch an earlier answer. */
    @Test fun outOfTheWayOfScreenshots() {
        assertFalse(answerCardWanted(fine.copy(assistantUp = true)))
        assertFalse(answerCardWanted(fine.copy(capturing = true)))
    }

    @Test fun theCaptionIsOmasNameOrWhatWentWrong() {
        assertEquals("Soma", answerCardCaption("Done"))
        assertEquals("Agent needs attention", answerCardCaption("Agent needs attention"))
    }

    @Test fun anAnswerStaysEightSeconds() = assertEquals(8000L, ANSWER_CARD_MILLIS)
}
