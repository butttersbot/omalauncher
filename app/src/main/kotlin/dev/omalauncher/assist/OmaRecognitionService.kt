package dev.omalauncher.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*

/** Uses the phone's real recognizer, excluding this forwarding service to avoid recursion. */
fun assistantRecognizer(context: Context): SpeechRecognizer {
    if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    }
    val candidates = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .filter { it.serviceInfo.packageName != context.packageName }
    val service = candidates.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
        ?: candidates.firstOrNull() ?: error("No speech recognizer is installed. You can type instead.")
    return SpeechRecognizer.createSpeechRecognizer(context, ComponentName(service.serviceInfo.packageName, service.serviceInfo.name))
}

class OmaRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    override fun onStartListening(intent: Intent, listener: Callback) {
        try {
            recognizer?.destroy()
            recognizer = assistantRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listener.readyForSpeech(params ?: Bundle()) }
                    override fun onBeginningOfSpeech() { listener.beginningOfSpeech() }
                    override fun onRmsChanged(rmsdB: Float) { listener.rmsChanged(rmsdB) }
                    override fun onBufferReceived(buffer: ByteArray?) { buffer?.let(listener::bufferReceived) }
                    override fun onEndOfSpeech() { listener.endOfSpeech() }
                    override fun onError(error: Int) { listener.error(error) }
                    override fun onResults(results: Bundle?) { listener.results(results ?: Bundle()) }
                    override fun onPartialResults(results: Bundle?) { listener.partialResults(results ?: Bundle()) }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                startListening(intent)
            }
        } catch (e: Exception) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onStopListening(listener: Callback) { recognizer?.stopListening() }
    override fun onCancel(listener: Callback) { recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }
}
