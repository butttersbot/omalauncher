package dev.omalauncher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.voice.WhisperModels
import dev.omalauncher.voice.WhisperSession
import java.util.Locale

/**
 * Android speech recognition for the agent button. The guest has no microphone,
 * so speech becomes text here and only text reaches Linux. Prefers the
 * on-device recognizer (offline, fast) and falls back to the regular one when
 * it has no model for the language. Main thread only, like SpeechRecognizer.
 */
class Speech(
    private val context: Context,
) {
    var listening by mutableStateOf(false)
        private set

    /** What's been recognized so far: finished segments plus the current partial result. */
    var text by mutableStateOf("")
        private set

    /** Input loudness, 0..1. */
    var level by mutableFloatStateOf(0f)
        private set

    /** Why recognition failed; the UI clears it once shown. */
    var error by mutableStateOf<String?>(null)

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = true
    private var finished = ""
    private var onDone: ((String) -> Unit)? = null

    /** While held, pauses don't end recognition: a new segment starts until [stop]. */
    private var held = false

    private val onDeviceAvailable get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val available get() = WhisperModels.ready(context) || onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Whisper on the phone, when its model is here (Boss, 2026-10-14: Samsung's recognizer is "just garbage"):
     * the words show as he speaks and the whole is read again, more accurately, when he stops. Android's
     * recognizer stays the fallback until the models have been fetched, which starts on the first use on Wi-Fi.
     */
    private var whisper: WhisperSession? = null

    /** The words of the text that won't change any more; the rest of [text] may, while Whisper listens. */
    var settled by mutableStateOf("")
        private set

    /** Told each time [text] changes while listening, for a caller outside Compose (the keyboard). */
    var onText: ((String) -> Unit)? = null

    /**
     * Starts listening; [onDone] gets the full text (maybe empty) once recognition ends.
     * With [hold], it keeps listening across pauses until [stop] or [letGo].
     */
    fun start(
        hold: Boolean,
        onDone: (String) -> Unit,
    ) {
        cancel()
        error = null
        if (!available) {
            error = "no speech recognizer on this phone"
            return
        }
        held = hold
        this.onDone = onDone
        finished = ""
        text = ""
        settled = ""
        listening = true
        if (WhisperModels.ready(context) && listenWhisper(hold)) return
        WhisperModels.prepare(context)
        listen()
    }

    private fun listenWhisper(hold: Boolean): Boolean {
        lateinit var session: WhisperSession
        session =
            WhisperSession(
                context,
                hold,
                onText = { live ->
                    if (whisper === session) {
                        text = live.text
                        settled = live.settled
                        onText?.invoke(text)
                    }
                },
                onLevel = { if (whisper === session) level = it },
                onDone = { heard, _ ->
                    if (whisper === session) {
                        whisper = null
                        text = heard
                        settled = heard
                        finish()
                    }
                },
            )
        whisper = session
        if (session.start()) return true
        whisper = null
        return false
    }

    /** Ends recognition now; the text so far goes to onDone. */
    fun stop() {
        held = false
        whisper?.let {
            it.stop()
            return
        }
        recognizer?.stopListening() ?: finish()
    }

    /** Keeps listening, but ends at the next pause instead of at [stop]. */
    fun letGo() {
        held = false
        whisper?.letGo()
    }

    /** Ends recognition without calling onDone. */
    fun cancel() {
        held = false
        onDone = null
        whisper?.cancel()
        whisper = null
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
    }

    /**
     * The language to recognize: the phone's, or the closest one the recognizer
     * has a model for. Recognizers only know common language-region pairs, so a
     * phone set to English (Germany) needs en-US or en-GB instead. Null until checked.
     */
    private var language: String? = null

    private fun listen() {
        recognizer?.destroy()
        val r =
            if (onDevice && onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        recognizer = r
        r.setRecognitionListener(Listener(r))
        if (language != null || Build.VERSION.SDK_INT < 33) return r.startListening(intent())
        r.checkRecognitionSupport(
            intent(),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (r !== recognizer) return
                    language = closestLanguage(support)
                    Log.i(TAG, "recognizing ${language ?: "the default language"}")
                    r.startListening(intent())
                }

                override fun onError(error: Int) {
                    if (r === recognizer) r.startListening(intent())
                }
            },
        )
    }

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .apply { language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }

    /**
     * The phone's language-region if supported, else the same language in another
     * region: installed models first, then the language's home region (de-DE),
     * then en-US style defaults, then any.
     */
    @androidx.annotation.RequiresApi(33)
    private fun closestLanguage(support: RecognitionSupport): String? {
        val locale = Locale.getDefault()
        val wanted = Locale(locale.language, locale.country).toLanguageTag()
        val pools =
            if (onDevice) {
                listOf(support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages, support.onlineLanguages)
            } else {
                listOf(support.onlineLanguages, support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages)
            }
        pools.flatten().firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        val home = "${locale.language}-${locale.language}"
        for (pool in pools) {
            val same = pool.filter { it.substringBefore('-').equals(locale.language, ignoreCase = true) }
            same
                .minByOrNull {
                    when {
                        it.equals(home, ignoreCase = true) -> 0
                        it.endsWith("-US", ignoreCase = true) -> 1
                        else -> 2
                    }
                }?.let { return it }
        }
        return null
    }

    private fun finish() {
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
        val done = onDone
        onDone = null
        done?.invoke(text.trim())
    }

    private fun join(
        a: String,
        b: String,
    ) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }

    private fun Bundle.best() = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** Listens to one recognizer; callbacks from one that's been replaced are ignored. */
    private inner class Listener(
        private val r: SpeechRecognizer,
    ) : RecognitionListener {
        private val current get() = r === recognizer

        override fun onPartialResults(partial: Bundle) {
            if (current) {
                text = join(finished, partial.best())
                onText?.invoke(text)
            }
        }

        override fun onResults(results: Bundle) {
            if (!current) return
            finished = join(finished, results.best())
            text = finished
            if (held) listen() else finish()
        }

        override fun onError(code: Int) {
            if (!current) return
            when (code) {
                // Silence or nothing understood: fine while the button is held.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (held) listen() else finish()
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    if (onDevice) {
                        Log.i(TAG, "no on-device model for this language, using the regular recognizer")
                        onDevice = false
                        // The regular recognizer may know other languages than the on-device one.
                        language = null
                        listen()
                    } else {
                        fail("speech isn't available in this language")
                    }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("microphone permission needed")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail("speech recognition needs a network connection")
                else -> fail("speech recognition failed ($code)")
            }
        }

        private fun fail(message: String) {
            Log.w(TAG, message)
            // Keep what was already recognized; the error is only worth showing when there's nothing.
            if (text.isBlank()) error = message
            finish()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (current) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) {}
    }

    companion object {
        private const val TAG = "speech"
    }
}
