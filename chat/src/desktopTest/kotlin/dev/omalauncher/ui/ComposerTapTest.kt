package dev.omalauncher.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer under a finger. The clock is driven by hand: a recording blinks and reads levels for as long as it
 * lasts, so the test never waits for it to go idle.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerTapTest {
    @Test fun typingTurnsTheMicrophoneIntoSend() =
        runComposeUiTest {
            val sent = mutableListOf<String>()
            setContent { SomaComposer(rememberComposerState(), onSend = { sent += it }, voice = FakeRecorder(), onAttach = {}) }
            onNodeWithContentDescription("Record a voice message").assertExists()
            onNodeWithContentDescription("Send").assertDoesNotExist()
            onNodeWithContentDescription("Message").performTextInput("see you at 6")
            onNodeWithContentDescription("Record a voice message").assertDoesNotExist()
            onNodeWithContentDescription("Send").performClick()
            assertEquals(listOf("see you at 6"), sent)
            onNodeWithContentDescription("Record a voice message").assertExists()
        }

    @Test fun tapToRecordStopToReviewThenSend() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val mic = FakeRecorder()
            val voices = mutableListOf<VoiceClip>()
            setContent { SomaComposer(rememberComposerState(), onSend = {}, voice = mic, onSendVoice = { voices += it }, onAttach = {}) }
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Record a voice message").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithContentDescription("Stop recording").assertExists()
            onNodeWithContentDescription("Delete voice message").assertExists()
            onNodeWithContentDescription("Add a photo or file").assertDoesNotExist()
            onNodeWithContentDescription("Stop recording").performClick()
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Play voice message").assertExists()
            onNodeWithText("0:03").assertExists()
            onNodeWithContentDescription("Send voice message").performClick()
            mainClock.advanceTimeBy(100)
            assertEquals(1, voices.size)
            assertEquals(3_000L, voices.single().millis)
            assertTrue("levels were heard while recording", voices.single().levels.isNotEmpty())
            onNodeWithContentDescription("Record a voice message").assertExists()
            onNodeWithContentDescription("Add a photo or file").assertExists()
        }

    @Test fun holdingRecordsUntilTheFingerLifts() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val mic = FakeRecorder()
            setContent { SomaComposer(rememberComposerState(), onSend = {}, voice = mic, onAttach = {}) }
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Record a voice message").performTouchInput { down(center) }
            mainClock.advanceTimeBy(ComposerRules.HOLD_MS + 200)
            onNodeWithContentDescription("Stop recording").assertExists()
            onNodeWithContentDescription("Stop recording").performTouchInput { up() }
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Send voice message").assertExists()
            assertEquals(listOf("start", "stop"), mic.calls)
        }

    @Test fun deletingAReviewedRecordingDiscardsIt() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val mic = FakeRecorder()
            setContent { SomaComposer(rememberComposerState(), onSend = {}, voice = mic) }
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Record a voice message").performClick()
            mainClock.advanceTimeBy(300)
            onNodeWithContentDescription("Stop recording").performClick()
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Delete voice message").performClick()
            mainClock.advanceTimeBy(100)
            assertEquals(listOf("start", "stop", "discard"), mic.calls)
            onNodeWithContentDescription("Record a voice message").assertExists()
        }

    @Test fun aTooShortRecordingGoesBackToTheField() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val mic = FakeRecorder(clipMillis = 300)
            setContent { SomaComposer(rememberComposerState(), onSend = {}, voice = mic) }
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Record a voice message").performClick()
            mainClock.advanceTimeBy(200)
            onNodeWithContentDescription("Stop recording").performClick()
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Record a voice message").assertExists()
            assertEquals(listOf("start", "stop", "discard"), mic.calls)
        }

    @Test fun withoutVoiceTheAppDrawsItsOwnButton() =
        runComposeUiTest {
            setContent { SomaComposer(rememberComposerState(), onSend = {}, emptyAction = { BasicText("Dictate") }) }
            onNodeWithText("Dictate").assertExists()
            onNodeWithContentDescription("Record a voice message").assertDoesNotExist()
            onNodeWithContentDescription("Message").performTextInput("hi")
            onNodeWithText("Dictate").assertDoesNotExist()
            onNodeWithContentDescription("Send").assertExists()
        }
}
