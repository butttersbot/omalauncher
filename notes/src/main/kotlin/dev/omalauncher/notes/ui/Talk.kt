package dev.omalauncher.notes.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Hold to talk: Android's own speech recognition, from the press to a moment after the finger lifts. [heard] gets
 * the words so far while talking, and [done] the last of them (empty when nothing was understood).
 */
class Talk(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var said = ""

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(
        heard: (String) -> Unit,
        done: (String) -> Unit,
    ) {
        stop()
        said = ""
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return done("")
        recognizer = r
        r.setRecognitionListener(
            object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) {
                    words(partialResults)?.let {
                        said = it
                        heard(it)
                    }
                }

                override fun onResults(results: Bundle?) {
                    done(words(results) ?: said)
                    release()
                }

                override fun onError(error: Int) {
                    Log.i("SomaNotes", "talk error $error")
                    done(said)
                    release()
                }

                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {}
            },
        )
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Pauses while thinking aloud don't end it; the finger lifting does.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L),
        )
    }

    /** The finger lifted: what was said is finished off and comes to [start]'s done. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun words(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
