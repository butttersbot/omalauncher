package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A microphone that records nothing and says what was asked of it. */
class FakeRecorder(
    var allow: Boolean = true,
    var clipMillis: Long? = 3_000,
) : VoiceRecorder {
    val calls = mutableListOf<String>()

    override fun start() = allow.also { calls += "start" }

    override fun level() = 0.5f

    override fun stop(): VoiceClip? {
        calls += "stop"
        return clipMillis?.let { VoiceClip(it, emptyList(), "clip.m4a") }
    }

    override fun discard() {
        calls += "discard"
    }

    override fun play(clip: VoiceClip) {
        calls += "play"
    }

    override fun stopPlaying() {
        calls += "stopPlaying"
    }
}

/** The composer's rules and state, without drawing it. */
class ComposerRulesTest {
    private fun action(
        text: String = "",
        attached: Boolean = false,
        voice: Boolean = true,
        own: Boolean = false,
        stage: VoiceStage = VoiceStage.Off,
    ) = ComposerRules.action(stage, text, attached, voice, own)

    @Test fun anEmptyFieldOffersTheMicrophoneAndTextOffersSend() {
        assertEquals(ComposerAction.Record, action())
        assertEquals(ComposerAction.Record, action("   "))
        assertEquals(ComposerAction.Send, action("hi"))
        assertEquals(ComposerAction.Send, action(attached = true))
    }

    @Test fun withoutVoiceTheAppsOwnButtonOrAFaintSend() {
        assertEquals(ComposerAction.Own, action(voice = false, own = true))
        assertEquals(ComposerAction.Idle, action(voice = false))
        assertEquals(ComposerAction.Send, action("hi", voice = false, own = true))
    }

    @Test fun recordingAndReviewTakeTheButtonWhateverTheField() {
        assertEquals(ComposerAction.Stop, action("x", stage = VoiceStage.Recording(0, held = false)))
        assertEquals(ComposerAction.SendVoice, action(stage = VoiceStage.Review(VoiceClip(2_000, emptyList()))))
    }

    @Test fun clocksAndShortRecordings() {
        assertEquals("0:00", ComposerRules.clock(0))
        assertEquals("0:07", ComposerRules.clock(7_200))
        assertEquals("1:30", ComposerRules.clock(90_000))
        assertFalse(ComposerRules.keeps(999))
        assertTrue(ComposerRules.keeps(1_000))
    }

    @Test fun barsKeepTheLoudestMomentOfEachStretch() {
        assertEquals(listOf(0.9f, 0.4f), ComposerRules.bars(listOf(0.1f, 0.9f, 0.4f, 0.2f), 2))
        assertEquals(List(3) { ComposerRules.QUIET }, ComposerRules.bars(emptyList(), 3))
        // More bars than levels repeats them rather than leaving gaps.
        assertEquals(4, ComposerRules.bars(listOf(0.5f, 0.6f), 4).size)
        assertEquals(ComposerRules.QUIET, ComposerRules.bars(listOf(0f), 1).single())
    }

    @Test fun aRecordingIsReviewedThenSentOnce() {
        val mic = FakeRecorder()
        val state = ComposerState()
        assertTrue(state.startVoice(mic, now = 0, held = false))
        state.hear(0.3f)
        state.hear(2f)
        assertEquals(listOf(0.3f, 1f), state.levels.toList())
        assertTrue(state.stopVoice(mic, now = 3_000))
        val review = state.stage as VoiceStage.Review
        assertEquals(3_000L, review.clip.millis)
        assertEquals("clip.m4a", review.clip.file)
        // The recorder kept no levels, so the ones heard are drawn.
        assertEquals(listOf(0.3f, 1f), review.clip.levels)
        assertEquals(review.clip, state.takeVoice(mic))
        assertNull(state.takeVoice(mic))
        assertEquals(VoiceStage.Off, state.stage)
    }

    @Test fun aTooShortRecordingIsThrownAway() {
        val mic = FakeRecorder(clipMillis = 400)
        val state = ComposerState()
        state.startVoice(mic, 0, held = true)
        assertFalse(state.stopVoice(mic, 400))
        assertEquals(VoiceStage.Off, state.stage)
        assertEquals(listOf("start", "stop", "discard"), mic.calls)
    }

    @Test fun nothingRecordsOverADraftOrWithoutTheMicrophone() {
        val state = ComposerState("half a thought")
        assertFalse(state.startVoice(FakeRecorder(), 0, held = false))
        val refused = FakeRecorder(allow = false)
        assertFalse(ComposerState().startVoice(refused, 0, held = false))
        assertEquals(listOf("start"), refused.calls)
    }

    @Test fun cancellingStopsPlaybackAndDiscards() {
        val mic = FakeRecorder()
        val state = ComposerState()
        state.startVoice(mic, 0, held = false)
        state.stopVoice(mic, 2_000)
        state.togglePlayback(mic, 2_500)
        assertEquals(2_500L, state.playingSince)
        state.cancelVoice(mic)
        assertEquals(VoiceStage.Off, state.stage)
        assertNull(state.playingSince)
        assertEquals(listOf("start", "stop", "play", "stopPlaying", "discard"), mic.calls)
    }

    @Test fun takingTextTrimsAndEmpties() {
        val state = ComposerState("  see you at 6 \n")
        assertEquals("see you at 6", state.takeText())
        assertEquals("", state.text)
        assertNull(state.takeText())
    }
}
