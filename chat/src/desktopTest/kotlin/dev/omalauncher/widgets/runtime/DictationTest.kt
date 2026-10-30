package dev.omalauncher.widgets.runtime

import dev.omalauncher.assist.RelayOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget's flow (Boss, 2026-10-30): tap to record, tap to stop, words show, Send types them on MG1. */
class DictationTest {
    private class FakeMic(override val allowed: Boolean = true) : DictationMic {
        var started = 0
        var cancelled = 0
        var text: ((String) -> Unit)? = null
        var done: ((String, String?) -> Unit)? = null

        override fun start(onText: (String) -> Unit, onDone: (String, String?) -> Unit) {
            started++
            text = onText
            done = onDone
        }

        override fun stop() {
            done?.invoke("turn the lights down", null)
        }

        override fun cancel() {
            cancelled++
        }
    }

    private val sent = mutableListOf<String>()
    private var answer: RelayOutcome = RelayOutcome.Typed("Brave")

    private fun dictation(mic: DictationMic = FakeMic()) = Dictation(mic, "MG1") { words, done ->
        sent += words
        done(answer)
    }

    @Test fun tapRecordTapStopWordsWaitThenSendTypesThem() {
        val mic = FakeMic()
        val d = dictation(mic)
        assertEquals(DictationState.IDLE, d.now.value.state)
        d.control("toggle")
        assertEquals(DictationState.LISTENING, d.now.value.state)
        mic.text!!("turn the")
        assertEquals("turn the", d.now.value.words)
        assertTrue("nothing goes anywhere while listening", sent.isEmpty())
        d.control("toggle")
        assertEquals(DictationNow(DictationState.HEARD, "turn the lights down"), d.now.value)
        assertTrue("nothing goes anywhere until Send", sent.isEmpty())
        d.control("send")
        assertEquals(listOf("turn the lights down"), sent)
        assertEquals(DictationNow(DictationState.TYPED, "", "Typed into Brave on MG1"), d.now.value)
    }

    @Test fun aFailedSendKeepsTheWordsAndTryingAgainSendsThem() {
        val d = dictation()
        d.control("start")
        d.control("stop")
        answer = RelayOutcome.Locked
        d.control("send")
        assertEquals(DictationNow(DictationState.FAILED, "turn the lights down", "MG1 is locked. Not typed."), d.now.value)
        answer = RelayOutcome.Offline
        d.control("send")
        assertEquals("MG1 is offline. Not typed.", d.now.value.message)
        assertEquals("turn the lights down", d.now.value.words)
        answer = RelayOutcome.Typed("Brave")
        d.control("send")
        assertEquals(DictationState.TYPED, d.now.value.state)
        assertEquals(3, sent.size)
    }

    @Test fun whileSendingNothingSendsTwiceOrDropsTheWords() {
        var finish: ((RelayOutcome) -> Unit)? = null
        val d = Dictation(FakeMic(), "MG1") { words, done ->
            sent += words
            finish = done
        }
        d.control("start")
        d.control("stop")
        d.control("send")
        assertEquals(DictationState.SENDING, d.now.value.state)
        d.control("send")
        d.control("clear")
        d.control("toggle")
        assertEquals(1, sent.size)
        assertEquals("turn the lights down", d.now.value.words)
        finish!!(RelayOutcome.NoAnswer)
        assertEquals("No answer from MG1. It may have typed this.", d.now.value.message)
    }

    @Test fun recordingAgainAddsToWaitingWordsAndClearDropsThem() {
        val mic = FakeMic()
        val d = dictation(mic)
        d.control("toggle")
        d.control("toggle")
        d.control("toggle")
        mic.text!!("and the fan")
        assertEquals("turn the lights down and the fan", d.now.value.words)
        d.control("clear")
        assertEquals(1, mic.cancelled)
        assertEquals(DictationNow(), d.now.value)
        // A send's words are gone: the next recording starts empty.
        d.control("start")
        d.control("stop")
        d.control("send")
        d.control("start")
        assertEquals("", d.now.value.words)
    }

    @Test fun noWordsNoMicAndARecognizerErrorSaySoAndSendNothing() {
        val silent = FakeMic()
        val d = dictation(silent)
        d.control("start")
        silent.done!!("", null)
        assertEquals(DictationNow(message = "Didn't catch that"), d.now.value)
        d.control("send")
        assertTrue(sent.isEmpty())
        d.control("start")
        silent.done!!("half a thought", "speech isn't available in this language")
        assertEquals(DictationNow(DictationState.FAILED, "half a thought", "speech isn't available in this language"), d.now.value)
        val off = dictation(FakeMic(allowed = false))
        off.control("toggle")
        assertEquals(DictationState.FAILED, off.now.value.state)
        assertEquals("Allow the microphone for Soma", off.now.value.message)
        off.control("send")
        assertTrue(sent.isEmpty())
    }

    @Test fun theWidgetReadsStateWordsMessageAndTarget() {
        val v = DictationNow(DictationState.HEARD, "hi").value("MG1").toString()
        for (part in listOf("heard", "hi", "MG1")) assertTrue(v, v.contains(part))
    }
}
