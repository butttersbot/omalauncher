package dev.omalauncher.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** whisper.cpp, built into the app (cpp/omawhisper.cpp). */
object WhisperNative {
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("omawhisper") }.isSuccess }

    external fun load(path: String): Long

    external fun free(ctx: Long)

    external fun transcribe(
        ctx: Long,
        samples: FloatArray,
        prompt: String?,
        threads: Int,
        live: Boolean,
    ): String
}

/**
 * The two Whisper models Oma uses, kept in the app's files: a fast one that reads the audio over and over while
 * Boss speaks, and a more accurate one for the whole of it once he stops. Fetched once, on Wi-Fi, from
 * whisper.cpp's own model page, and used only if they are exactly the files pinned here.
 */
object WhisperModels {
    class Model(
        val file: String,
        val sha256: String,
    ) {
        val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
    }

    val LIVE = Model("ggml-base.en-q5_1.bin", "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f")
    val FINAL = Model("ggml-small.en-q5_1.bin", "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30")

    fun dir(context: Context) = File(context.filesDir, "whisper")

    fun file(
        context: Context,
        model: Model,
    ) = File(dir(context), model.file)

    /** Whether Whisper can listen now: the library loaded and the live model is here (the final one is optional). */
    fun ready(context: Context) = WhisperNative.loaded && file(context, LIVE).isFile

    private val fetching = AtomicBoolean()

    /** Fetches the models that aren't here yet, in the background, on an unmetered network only. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        if (listOf(LIVE, FINAL).all { file(app, it).isFile }) return
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        if (!fetching.compareAndSet(false, true)) return
        thread(name = "oma-whisper-fetch", isDaemon = true) {
            try {
                for (m in listOf(LIVE, FINAL)) if (!file(app, m).isFile) fetch(app, m)
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun fetch(
        context: Context,
        model: Model,
    ) {
        val dir = dir(context).apply { mkdirs() }
        val part = File(dir, model.file + ".part")
        runCatching {
            val c = URL(model.url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            val digest = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            check(got == model.sha256) { "${model.file}: unexpected checksum $got" }
            check(part.renameTo(File(dir, model.file))) { "could not keep ${model.file}" }
        }.onFailure {
            Log.w(TAG, "fetching ${model.file} failed: ${it.message}")
            part.delete()
        }
    }

    /** A loaded model, kept while it is used and a while after. */
    private val loadedModels = HashMap<String, Long>()
    private val lastUse = HashMap<String, Long>()

    @Synchronized fun context(
        context: Context,
        model: Model,
    ): Long {
        lastUse[model.file] = System.currentTimeMillis()
        loadedModels[model.file]?.let { return it }
        val f = file(context, model)
        if (!f.isFile || !WhisperNative.loaded) return 0
        val ctx = WhisperNative.load(f.path)
        if (ctx != 0L) loadedModels[model.file] = ctx
        return ctx
    }

    /** Frees the models not used for [idleMs]: the accurate one takes some 300 MB while loaded. */
    @Synchronized fun release(idleMs: Long) {
        val now = System.currentTimeMillis()
        for ((name, ctx) in loadedModels.entries.toList()) {
            if (now - (lastUse[name] ?: 0) >= idleMs) {
                WhisperNative.free(ctx)
                loadedModels.remove(name)
            }
        }
    }

    const val TAG = "whisper"
}

/**
 * One time Boss speaks, heard by Whisper on the phone. The microphone is read in steps of [STEP_MS]; while he
 * speaks, the fast model reads the audio after the settled words again and again, and [onText] gets the words
 * so far (the settled ones never change again). It ends at [stop], or with [hold] false at the first long pause;
 * then the accurate model reads the whole of it once, and [onDone] gets that text. Callbacks come on the main thread.
 */
class WhisperSession(
    private val context: Context,
    hold: Boolean,
    private val onText: (LiveText) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onDone: (String, String?) -> Unit,
) {
    @Volatile private var held = hold
    @Volatile private var ending = false
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())
    private var samples = FloatArray(16_000 * 30)
    @Volatile private var count = 0
    private val levels = ArrayList<Float>()
    private val prompt = voicePrompt(extraWords())
    private val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    /** Where the audio after the settled words begins, in samples. */
    private var anchor = 0
    private var live = LiveText()

    private fun extraWords(): List<String> =
        runCatching { File(context.filesDir, "arch/root/.config/omalauncher/voice-words.txt").readLines() }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec =
            runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, RATE / 2 * 4))
            }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        rec.startRecording()
        thread(name = "oma-whisper-mic", isDaemon = true) { record(rec) }
        thread(name = "oma-whisper-live", isDaemon = true) { readLive() }
        return true
    }

    /** Ends now; the text so far goes to onDone after the accurate reading. */
    fun stop() {
        held = false
        ending = true
    }

    /** Keeps listening, but ends at the next pause. */
    fun letGo() {
        held = false
    }

    fun cancel() {
        cancelled = true
        ending = true
    }

    private fun record(rec: AudioRecord) {
        val step = RATE * STEP_MS / 1000
        val buf = FloatArray(step)
        try {
            while (!ending) {
                val n = rec.read(buf, 0, step, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                synchronized(this) {
                    if (count + n > samples.size) samples = samples.copyOf(samples.size * 2)
                    System.arraycopy(buf, 0, samples, count, n)
                    count += n
                }
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i]
                val level = (sqrt(sum / n).toFloat() * 8f).coerceIn(0f, 1f)
                levels += level
                main.post { if (!cancelled) onLevel(level) }
                if (!held && pauseEnded(levels, STEP_MS)) ending = true
                // Nothing heard at all for a long while ends it too.
                if (!held && levels.size * STEP_MS > 8_000 && levels.none { it > SILENCE * 2 }) ending = true
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }

    private fun audio(
        from: Int,
        to: Int,
    ): FloatArray = synchronized(this) { samples.copyOfRange(from, to) }

    private fun readLive() {
        val ctx = WhisperModels.context(context, WhisperModels.LIVE)
        // Load the accurate model while he speaks, so the end comes sooner.
        thread(name = "oma-whisper-warm", isDaemon = true) { WhisperModels.context(context, WhisperModels.FINAL) }
        var readAt = 0
        while (!ending) {
            val end = count
            if (ctx == 0L || end - readAt < RATE * READ_EVERY_MS / 1000 || end - anchor < RATE / 2) {
                Thread.sleep(50)
                continue
            }
            readAt = end
            val heard = parseHeard(WhisperNative.transcribe(ctx, audio(anchor, end), prompt, threads, true))
            if (cancelled) break
            val now = heard.joinToString(" ") { it.text }
            live = live.read(now)
            // A long stretch is settled up to its last segment, and reading starts again after it.
            if (end - anchor > RATE * SETTLE_AFTER_S && heard.size > 1) {
                val keep = heard.dropLast(1)
                live = live.keep(keep.joinToString(" ") { it.text })
                anchor += (keep.last().end * RATE / 100).toInt().coerceAtMost(end - anchor)
            }
            val shown = live
            main.post { if (!cancelled) onText(shown) }
        }
        if (cancelled) return
        // The whole of it, read by the accurate model; the fast one's reading if that one isn't here.
        val all = audio(0, count)
        val finalCtx = WhisperModels.context(context, WhisperModels.FINAL)
        val text =
            when {
                all.size < RATE / 4 -> ""
                finalCtx != 0L -> parseHeard(WhisperNative.transcribe(finalCtx, all, prompt, threads, false)).joinToString(" ") { it.text }
                ctx != 0L -> joinHeard(live.kept, parseHeard(WhisperNative.transcribe(ctx, audio(anchor, all.size), prompt, threads, true)).joinToString(" ") { it.text })
                else -> live.text
            }
        main.post { if (!cancelled) onDone(text.trim(), null) }
        main.postDelayed({ thread(isDaemon = true) { WhisperModels.release(RELEASE_AFTER_MS) } }, RELEASE_AFTER_MS + 1_000)
    }

    companion object {
        const val RATE = 16_000
        const val STEP_MS = 100
        const val READ_EVERY_MS = 500
        const val SETTLE_AFTER_S = 15
        const val RELEASE_AFTER_MS = 90_000L
    }
}
