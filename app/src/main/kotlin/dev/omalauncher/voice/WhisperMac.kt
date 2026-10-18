package dev.omalauncher.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
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

    /** Whether the server takes a connection within a second: no answer means the Mac is asleep, away or off. */
    fun reachable(base: String): Boolean {
        val url = runCatching { URL(base) }.getOrNull() ?: return false
        val port = if (url.port > 0) url.port else url.defaultPort
        seen = runCatching { Socket().use { it.connect(InetSocketAddress(url.host, port), 1_000) } }.isSuccess
        return seen
    }

    /** Asks the Mac in the background, so [seen] is right by the next dictation. */
    fun check(context: Context) {
        val base = base(context)
        thread(name = "oma-whisper-mac", isDaemon = true) { reachable(base) }
    }

    /**
     * What the Mac hears in [samples] (16 kHz mono), as the phone's model gives it: a "t0 t1 text" line per segment,
     * in centiseconds. Null if the Mac didn't answer in time.
     */
    fun transcribe(
        base: String,
        samples: FloatArray,
        prompt: String?,
        timeoutMs: Int,
    ): String? =
        runCatching {
            val boundary = "oma" + System.nanoTime()
            val body = ByteArrayOutputStream(samples.size * 2 + 1024)
            fun field(
                name: String,
                value: String,
            ) = body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            field("response_format", "verbose_json")
            field("language", "en")
            field("temperature", "0")
            field("temperature_inc", "0.2")
            if (!prompt.isNullOrBlank()) field("prompt", prompt)
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"oma.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            body.write(wav(samples))
            body.write("\r\n--$boundary--\r\n".toByteArray())

            val c = URL("$base/inference").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 1_500
                c.readTimeout = timeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.setFixedLengthStreamingMode(body.size())
                c.outputStream.use { body.writeTo(it) }
                check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                val segments = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("segments")
                buildString {
                    for (i in 0 until segments.length()) {
                        val s = segments.getJSONObject(i)
                        val t0 = (s.getDouble("start") * 100).roundToLong()
                        val t1 = (s.getDouble("end") * 100).roundToLong()
                        append("$t0 $t1 ${s.getString("text").replace('\n', ' ')}\n")
                    }
                }
            } finally {
                c.disconnect()
            }
        }.onSuccess { seen = true }
            .onFailure {
                seen = false
                Log.w(WhisperModels.TAG, "the Mac didn't read it: ${it.message}")
            }.getOrNull()

    /** [samples] as a 16-bit 16 kHz mono WAV file. */
    private fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(WhisperSession.RATE).putInt(WhisperSession.RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }
}
