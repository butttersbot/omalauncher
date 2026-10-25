package dev.omalauncher.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/**
 * Whisper on the Mac Pro: whisper.cpp's whisper-server with large-v3-turbo, reached over Tailscale (see
 * docs/WHISPER-MAC.md). Oma sends it the recorded audio and gets the words back, so the phone holds no model
 * while the Mac is there (Boss, 2026-10-17: the two on the phone took some 500 MB).
 */
object WhisperMac {
    const val DEFAULT = "http://100.120.219.46:8178"

    /** The server to use: [DEFAULT], or the address in Linux's ~/.config/omalauncher/whisper-server.txt. */
    fun base(context: Context): String =
        runCatching { File(context.filesDir, "arch/root/.config/omalauncher/whisper-server.txt").readText().trim() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.trimEnd('/')
            ?: DEFAULT

    /** Whether the Mac answered when last asked; Whisper is used without the phone's models then. */
    @Volatile var seen = false
        private set

    /**
     * Whether the server answers within a second: no answer means the Mac is asleep, away or off. Asked over HTTP
     * (whisper-server's /health; any answer will do), so the connection it opened is kept and the first reading of
     * the dictation goes out on it instead of waiting for another (a round trip, 80-140 ms through Tailscale's relay).
     */
    fun reachable(base: String): Boolean {
        val url = runCatching { URL("$base/health") }.getOrNull() ?: return false
        seen =
            runCatching {
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 1_000
                c.readTimeout = 1_000
                try {
                    val code = c.responseCode
                    (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() }
                } catch (e: Exception) {
                    c.disconnect()
                    throw e
                }
            }.isSuccess
        return seen
    }

    /** Asks the Mac in the background, so [seen] is right by the next dictation. */
    fun check(context: Context) {
        val base = base(context)
        thread(name = "oma-whisper-mac", isDaemon = true) { reachable(base) }
    }

    /**
     * Servers that turned FLAC away (an older whisper-server named in whisper-server.txt): they are sent WAV, as
     * before. The Mac Pro's (whisper.cpp 1.8.4, no --convert) decodes FLAC itself.
     */
    private val wavOnly = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * What the Mac hears in [samples] (16 kHz mono), as the phone's model gives it: a "t0 t1 text" line per segment,
     * in centiseconds. Null if the Mac didn't answer in time.
     */
    fun transcribe(
        base: String,
        samples: FloatArray,
        prompt: String?,
        timeoutMs: Int,
    ): String? {
        val pcm = pcm16(samples)
        val flac = base !in wavOnly
        return runCatching { post(base, if (flac) Flac.encode(pcm) else wav(pcm), flac, prompt, timeoutMs) }
            .recoverCatching {
                // Turned away, not timed out: say it again as WAV, and to this server always from now on.
                if (!flac || it !is Rejected) throw it
                wavOnly += base
                post(base, wav(pcm), false, prompt, timeoutMs)
            }.onSuccess { seen = true }
            .onFailure {
                seen = false
                Log.w(WhisperModels.TAG, "the Mac didn't read it: ${it.message}")
            }.getOrNull()
    }

    private class Rejected(
        code: Int,
    ) : Exception("HTTP $code")

    private fun post(
        base: String,
        audio: ByteArray,
        flac: Boolean,
        prompt: String?,
        timeoutMs: Int,
    ): String {
        val boundary = "oma" + System.nanoTime()
        val body = ByteArrayOutputStream(audio.size + 1024)
        fun field(
            name: String,
            value: String,
        ) = body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        field("response_format", "verbose_json")
        field("language", "en")
        field("temperature", "0")
        field("temperature_inc", "0.2")
        if (!prompt.isNullOrBlank()) field("prompt", prompt)
        val (name, type) = if (flac) "oma.flac" to "audio/flac" else "oma.wav" to "audio/wav"
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
        body.write(audio)
        body.write("\r\n--$boundary--\r\n".toByteArray())

        // Not disconnected after: the whole answer is read and the stream closed, so the connection goes back to
        // the pool and the next reading reuses it (whisper-server keeps it 5 s), instead of a new one every time.
        val c = URL("$base/inference").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 1_500
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(body.size())
            c.outputStream.use { body.writeTo(it) }
            val code = c.responseCode
            if (code != 200) {
                c.errorStream?.use { it.readBytes() }
                throw if (code == 400 || code == 415) Rejected(code) else IllegalStateException("HTTP $code")
            }
            val segments = JSONObject(c.inputStream.use { it.bufferedReader().readText() }).getJSONArray("segments")
            return buildString {
                for (i in 0 until segments.length()) {
                    val s = segments.getJSONObject(i)
                    val t0 = (s.getDouble("start") * 100).roundToLong()
                    val t1 = (s.getDouble("end") * 100).roundToLong()
                    append("$t0 $t1 ${s.getString("text").replace('\n', ' ')}\n")
                }
            }
        } catch (e: Exception) {
            // A connection that failed or timed out isn't one to reuse.
            if (e !is Rejected) c.disconnect()
            throw e
        }
    }

    /** [pcm] as a 16-bit 16 kHz mono WAV file. */
    private fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(WhisperSession.RATE).putInt(WhisperSession.RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}
