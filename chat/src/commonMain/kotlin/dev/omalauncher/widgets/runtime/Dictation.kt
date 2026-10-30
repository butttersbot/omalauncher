package dev.omalauncher.widgets.runtime

import dev.omalauncher.assist.RelayOutcome
import dev.omalauncher.assist.appendSpeech
import dev.omalauncher.assist.relayWords
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Speech from a home widget's button, typed on another device (Boss, 2026-10-30): tap to record, tap to stop, the words
 * show in the widget, tap Send. `source d = android.dictation`, `dictation("toggle")`. Nothing goes anywhere until he
 * sends it, and a failed send keeps his words and sends them nowhere else. Main thread.
 */

/**
 * The one widget that may use the microphone and read what was said (Boss, 2026-10-30): by the file it is loaded from.
 * The launcher saves store widgets as market-*.owl and shared ones as shared-*.owl, so neither can arrive under this name,
 * whatever the widget calls itself. It is no wall against what can write in the widgets folder: an agent, or Linux.
 */
const val DICTATION_WIDGET_FILE = "dictation-mg1.owl"

internal const val DICTATION_SOURCE = "android.dictation"
internal const val DICTATION_REFUSED = "dictation is only for the widget in $DICTATION_WIDGET_FILE"

/** What the widget reads. [state] is one of [DictationState]'s values. */
data class DictationNow(
    val state: String = DictationState.IDLE,
    val words: String = "",
    val message: String = "",
) {
    fun value(target: String): Value =
        Obj(mapOf("state" to Str(state), "words" to Str(words), "message" to Str(message), "target" to Str(target)))
}

object DictationState {
    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val HEARD = "heard"
    const val SENDING = "sending"
    const val TYPED = "typed"
    const val FAILED = "failed"
}

/** The microphone, as the launcher's recognizer gives it. */
interface DictationMic {
    val allowed: Boolean

    /** Listens across pauses until [stop]. [onText] is told the words so far; [onDone] the whole, and why if it failed. */
    fun start(onText: (String) -> Unit, onDone: (String, String?) -> Unit)

    fun stop()

    /** Ends it without [start]'s onDone. */
    fun cancel()
}

class Dictation(
    private val mic: DictationMic,
    /** The device's name, for what the widget says. */
    val target: String,
    /** Types the words on the device, and says how it went; never on this thread's time. */
    private val deliver: (String, (RelayOutcome) -> Unit) -> Unit,
) {
    private val state = MutableStateFlow(DictationNow())
    val now: StateFlow<DictationNow> = state

    fun control(word: String) {
        val at = state.value
        when (word) {
            "toggle" -> if (at.state == DictationState.LISTENING) stop() else start()
            "start" -> start()
            "stop" -> stop()
            "send" -> send()
            "clear" -> if (at.state != DictationState.SENDING) {
                if (at.state == DictationState.LISTENING) mic.cancel()
                state.value = DictationNow()
            }
        }
    }

    private fun start() {
        val at = state.value
        if (at.state == DictationState.LISTENING || at.state == DictationState.SENDING) return
        if (!mic.allowed) {
            state.value = at.copy(state = DictationState.FAILED, message = "Allow the microphone for Soma")
            return
        }
        // What was sent is gone; words still waiting are added to, as saying more does on the listening screen.
        val before = if (at.state == DictationState.TYPED) "" else at.words
        state.value = DictationNow(DictationState.LISTENING, before)
        mic.start(
            onText = { live ->
                if (state.value.state == DictationState.LISTENING) state.value = DictationNow(DictationState.LISTENING, appendSpeech(before, live))
            },
            onDone = { said, error ->
                if (state.value.state == DictationState.LISTENING) {
                    val all = appendSpeech(before, said)
                    state.value = when {
                        error != null -> DictationNow(DictationState.FAILED, all, error)
                        all.isEmpty() -> DictationNow(message = "Didn't catch that")
                        else -> DictationNow(DictationState.HEARD, all)
                    }
                }
            },
        )
    }

    private fun stop() {
        if (state.value.state == DictationState.LISTENING) mic.stop()
    }

    private fun send() {
        val at = state.value
        if (at.state != DictationState.HEARD && at.state != DictationState.FAILED) return
        if (at.words.isBlank()) return
        state.value = DictationNow(DictationState.SENDING, at.words)
        deliver(at.words) { outcome ->
            val said = relayWords(outcome, target)
            state.value =
                if (outcome is RelayOutcome.Typed) DictationNow(DictationState.TYPED, message = said)
                else DictationNow(DictationState.FAILED, at.words, said)
        }
    }
}
