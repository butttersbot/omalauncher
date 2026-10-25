package dev.omalauncher.messages.engine

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import dev.omalauncher.ui.VoiceClip
import dev.omalauncher.ui.VoiceRecorder
import java.io.File

/**
 * The composer's microphone in Soma Messages: a voice message recorded as audio, never turned into words (Boss,
 * 2026-10-24). Android's own Opus encoder at 24 kHz, mono, in Ogg; [CafOpus.fromOgg] then wraps the same packets as an
 * iPhone's "Audio Message.caf" for sending. [askPermission] is called when the microphone isn't allowed yet; this
 * recording then doesn't start, and the next tap does once he has said yes.
 */
class Mic(
    private val context: Context,
    private val askPermission: () -> Unit,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var player: MediaPlayer? = null
    private var started = 0L

    override fun start(): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermission()
            return false
        }
        discard()
        val out = File(File(context.cacheDir, "voice").apply { mkdirs() }, "rec-${System.currentTimeMillis()}.ogg")
        return runCatching {
            recorder =
                MediaRecorder(context).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setAudioSamplingRate(CafOpus.RATE)
                    setAudioChannels(1)
                    setAudioEncodingBitRate(BITS)
                    setOutputFile(out.path)
                    prepare()
                    start()
                }
            file = out
            started = System.currentTimeMillis()
            true
        }.getOrElse {
            Log.w(TAG, "the microphone wouldn't start", it)
            recorder?.release()
            recorder = null
            out.delete()
            false
        }
    }

    /** MediaRecorder's loudest moment since last asked, eased so speech fills the bars and quiet stays low. */
    override fun level(): Float = (recorder?.runCatching { maxAmplitude }?.getOrNull() ?: 0).let { kotlin.math.sqrt(it / 32_767f).coerceIn(0f, 1f) }

    override fun stop(): VoiceClip? {
        val r = recorder ?: return null
        recorder = null
        val millis = System.currentTimeMillis() - started
        // Stopped too soon after starting, Android keeps nothing and says so by throwing.
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file
        if (!ok || f == null || !f.isFile || f.length() == 0L) {
            f?.delete()
            file = null
            return null
        }
        return VoiceClip(millis, emptyList(), f.path)
    }

    override fun discard() {
        stopPlaying()
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
        file?.delete()
        file = null
    }

    override fun play(clip: VoiceClip) {
        stopPlaying()
        val path = clip.file ?: return
        player =
            runCatching {
                MediaPlayer().apply {
                    setDataSource(path)
                    setOnCompletionListener { stopPlaying() }
                    prepare()
                    start()
                }
            }.getOrNull()
    }

    override fun stopPlaying() {
        player?.release()
        player = null
    }

    /** The recording is going out: it is no longer this microphone's to throw away. */
    fun handOver() {
        stopPlaying()
        file = null
    }

    companion object {
        private const val TAG = "SomaMessages"

        /** Plenty for a voice: iPhone voice messages are about this. */
        private const val BITS = 32_000
    }
}
