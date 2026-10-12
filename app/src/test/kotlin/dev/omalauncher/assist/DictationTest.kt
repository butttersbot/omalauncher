package dev.omalauncher.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTest {
    @Test fun speakingAgainAddsToWhatIsAlreadyInTheField() {
        assertEquals("book a table for two", appendSpeech("book a table", "for two"))
        assertEquals("book a table for two", appendSpeech("  book a table \n", " for two "))
    }
    @Test fun theFirstWordsFillAnEmptyField() {
        assertEquals("hello", appendSpeech("", "hello"))
        assertEquals("hello", appendSpeech("   ", " hello "))
    }
    @Test fun hearingNothingKeepsWhatWasTyped() {
        assertEquals("typed", appendSpeech("typed", ""))
        assertEquals("typed", appendSpeech("typed", "  "))
    }
    @Test fun aLongerPartialResultReplacesOnlyTheSpokenPart() {
        // The field is rebuilt from what was there before listening began, not from the last partial result.
        val before = "note:"
        assertEquals("note: buy", appendSpeech(before, "buy"))
        assertEquals("note: buy milk", appendSpeech(before, "buy milk"))
    }
}
