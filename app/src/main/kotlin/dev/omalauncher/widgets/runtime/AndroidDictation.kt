package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.omalauncher.assist.RelayTargets
import dev.omalauncher.assist.relayText
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.ui.Speech
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** [Dictation] on the launcher's own recognizer and the relay to MG1, with the ring along the screen while it listens. */
class AndroidDictation(
    private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val watching = AtomicInteger(0)
    private val target = RelayTargets.Mg1

    private val mic =
        object : DictationMic {
            private val speech by lazy { Speech(context.applicationContext) }
            override val allowed get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            override fun start(onText: (String) -> Unit, onDone: (String, String?) -> Unit) {
                OmaRing.hearing(this, true)
                speech.onText = onText
                speech.start(hold = true) { heard ->
                    OmaRing.hearing(this, false)
                    onDone(heard, speech.error)
                }
                // It said why without starting, so nothing will finish it.
                if (!speech.listening) {
                    OmaRing.hearing(this, false)
                    onDone("", speech.error ?: "No speech recognition on this phone")
                }
            }

            override fun stop() = speech.stop()

            override fun cancel() {
                speech.cancel()
                OmaRing.hearing(this, false)
            }
        }

    private val dictation =
        Dictation(mic, target.label) { words, done ->
            thread(name = "oma-dictation-relay", isDaemon = true) {
                val outcome = relayText(target.base!!, words)
                main.post { done(outcome) }
            }
        }

    fun value(): Value = dictation.now.value.value(target.label)

    /** While a widget watches. When the last stops (its page left the screen), the microphone doesn't stay open behind it. */
    fun updates(): Flow<Value> =
        dictation.now
            .map { it.value(target.label) }
            .onStart { watching.incrementAndGet() }
            .onCompletion { if (watching.decrementAndGet() == 0) main.post { dictation.control("stop") } }

    /** Speech is main-thread only. */
    fun control(word: String) {
        main.post { dictation.control(word) }
    }
}
