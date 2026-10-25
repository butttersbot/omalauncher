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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /**
     * With [stop], a live reading under way ends at whisper.cpp's next check, and any started after it at once,
     * until it is set back: for when the dictation ended and its words would not be used.
     */
    external fun stopLive(stop: Boolean)
}

/**
 * The two Whisper models Oma keeps on the phone for when the Mac Pro can't be reached (see [WhisperMac]): a fast
 * one that reads the audio over and over while Boss speaks, and a more accurate one for the whole of it once he
 * stops. Fetched once, on Wi-Fi, from whisper.cpp's own model page, and used only if they are exactly the files
 * pinned here. Loaded only for a dictation that needs them, and freed as soon as no dictation is running.
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

    /**
     * Whether Whisper can listen now: the Mac answered when last asked, or the library loaded and the live model
     * is here (the final one is optional).
     */
    fun ready(context: Context) = WhisperMac.seen || (WhisperNative.loaded && file(context, LIVE).isFile)

    private val fetching = AtomicBoolean()

    /** Fetches the models that aren't here yet, in the background, on an unmetered network only. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        WhisperMac.check(app)
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

    /** A loaded model, kept while a dictation is running. */
    private val loadedModels = HashMap<String, Long>()

    /** Dictations running now; the models are freed when the last one ends. */
    private var sessions = 0

    @Synchronized fun begin() {
        sessions++
    }

    /** One dictation is over, however it ended; with none left, the models go (Boss, 2026-10-17). */
    fun end() =
        synchronized(reading) {
            synchronized(this) {
                sessions--
                if (sessions <= 0) freeAll()
            }
        }

    /** Frees the models when no dictation runs: for a load that finished after its dictation was over. */
    fun releaseIfIdle() = synchronized(reading) { synchronized(this) { if (sessions <= 0) freeAll() } }

    private fun freeAll() {
        sessions = 0
        for (ctx in loadedModels.values) WhisperNative.free(ctx)
        loadedModels.clear()
    }

    @Synchronized fun context(
        context: Context,
        model: Model,
    ): Long {
        loadedModels[model.file]?.let { return it }
        val f = file(context, model)
        if (!f.isFile || !WhisperNative.loaded) return 0
        val ctx = WhisperNative.load(f.path)
        if (ctx != 0L) loadedModels[model.file] = ctx
        return ctx
    }

    /**
     * Held while a model reads or is freed. A whisper context is for one thread at a time, and must not be freed
     * while one reads with it: a session that began while the last was still reading, or a release timed from an
     * earlier session, crashed Soma in ggml (Boss, 2026-10-16 and 17). Taken before this object's own lock.
     * Freeing waits for it: the two together take some 500 MB while loaded.
     */
    private val reading = Any()

    /** What [ctx] hears in [samples], one reading at a time; nothing if the model was freed since it was handed out. */
    fun transcribe(
        ctx: Long,
        samples: FloatArray,
        prompt: String?,
        threads: Int,
        live: Boolean,
    ): String = synchronized(reading) {
        if (synchronized(this) { ctx !in loadedModels.values }) return ""
        WhisperNative.transcribe(ctx, samples, prompt, threads, live)
    }

    const val TAG = "whisper"
}

/**
 * One time Boss speaks, heard by Whisper. The phone only records: the readings are made on the Mac Pro
 * ([WhisperMac]), and by the phone's own models only when the Mac can't be reached. The microphone is read in
 * steps of [STEP_MS]; while he speaks, the audio after the settled words is read again and again, and [onText]
 * gets the words so far (the settled ones never change again). It ends at [stop], or with [hold] false at the
 * first long pause. Then the whole of it is read once more, by the accurate model, and [onDone] gets that text;
 * unless the Mac read it live and its last reading already was of the whole of it ([finishPlan]). Callbacks come
 * on the main thread.
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

    /**
     * Where his voice was first and last heard, in samples; -1 before it was. Whisper is never given the quiet
     * before or after it, from which it makes up words ("thank you for watching").
     */
    @Volatile private var voiceFrom = -1
    @Volatile private var voiceTo = 0
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
        WhisperModels.begin()
        if (WhisperNative.loaded) WhisperNative.stopLive(false)
        thread(name = "oma-whisper-mic", isDaemon = true) { record(rec) }
        thread(name = "oma-whisper-live", isDaemon = true) {
            // However it ends, cancelled too: a cancelled dictation used to leave both models loaded for good.
            try {
                readLive()
            } finally {
                WhisperModels.end()
            }
        }
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
                if (level > SILENCE * 2) {
                    if (voiceFrom < 0) voiceFrom = maxOf(0, count - n - RATE * PAD_MS / 1000)
                    voiceTo = count
                }
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

    /** The Mac's address while it answers in this dictation; null once it hasn't, and the phone reads instead. */
    private var mac: String? = null

    /** The phone's fast model, loaded on first need; the accurate one is loaded alongside it. */
    @Volatile private var liveCtx = 0L

    private fun stopPhoneReading() {
        if (WhisperNative.loaded) WhisperNative.stopLive(true)
    }

    private fun localLive(): Long {
        if (liveCtx == 0L) {
            liveCtx = WhisperModels.context(context, WhisperModels.LIVE)
            // Load the accurate model while he speaks, so the end comes sooner.
            thread(name = "oma-whisper-warm", isDaemon = true) {
                WhisperModels.context(context, WhisperModels.FINAL)
                WhisperModels.releaseIfIdle()
            }
        }
        return liveCtx
    }

    /** [clip] read on the Mac, or on the phone once the Mac hasn't answered; null with neither. */
    private fun read(
        clip: FloatArray,
        final: Boolean,
    ): String? {
        mac?.let { base ->
            val timeout = if (final) 15_000 + clip.size / RATE * 1_000 else LIVE_TIMEOUT_MS
            WhisperMac.transcribe(base, clip, prompt, timeout)?.let { return it }
            mac = null
        }
        if (final) {
            val ctx = WhisperModels.context(context, WhisperModels.FINAL)
            if (ctx != 0L) return WhisperModels.transcribe(ctx, clip, prompt, threads, false)
        }
        val ctx = localLive()
        return if (ctx == 0L) null else WhisperModels.transcribe(ctx, clip, prompt, threads, !final)
    }

    /**
     * A live reading, made on a thread of its own so that a stop needn't wait for it when its words would be
     * replaced anyway. [onMac]: by the Mac, whose address it was given; else by the phone's model [ctx], loaded
     * beforehand on the session's own thread (never here, where it could come after the dictation freed them).
     */
    private inner class Reading(
        val from: Int,
        val to: Int,
        private val mac: String?,
        private val ctx: Long,
    ) {
        val onMac = mac != null
        @Volatile var raw: String? = null
        private val done = CountDownLatch(1)

        init {
            val clip = audio(from, to)
            thread(name = "oma-whisper-read", isDaemon = true) {
                try {
                    raw =
                        if (mac != null) {
                            WhisperMac.transcribe(mac, clip, prompt, LIVE_TIMEOUT_MS)
                        } else {
                            if (ctx == 0L) null else WhisperModels.transcribe(ctx, clip, prompt, threads, true)
                        }
                } finally {
                    done.countDown()
                }
            }
        }

        /** Waits for it to finish; false if [give] came true first. */
        fun await(give: () -> Boolean): Boolean {
            while (!done.await(20, TimeUnit.MILLISECONDS)) if (give()) return false
            return true
        }
    }

    private fun readLive() {
        mac = WhisperMac.base(context).takeIf { WhisperMac.reachable(it) }
        if (mac == null) localLive()
        var readAt = 0
        // The last live reading after the settled words: where it began and ended, and what it heard.
        var lastFrom = -1
        var lastTo = -1
        var lastText: String? = null
        // A reading still under way when the dictation ended.
        var pending: Reading? = null
        while (!ending) {
            // Only up to just after his last word: from the quiet after it Whisper makes up words, or loops on
            // one ("and, and, and…", Boss 2026-10-15).
            val end = minOf(count, voiceTo + RATE * PAD_MS / 1000)
            if (voiceFrom < 0) {
                Thread.sleep(50)
                continue
            }
            if (anchor < voiceFrom) anchor = voiceFrom
            // In a pause (all up to just after his last word is recorded), what came since the last reading is
            // read however little it is: then the last reading is of all of it, and the end needs no other.
            val paused = end > readAt && count >= voiceTo + RATE * PAD_MS / 1000
            if ((end - readAt < RATE * READ_EVERY_MS / 1000 && !paused) || end - anchor < RATE / 2) {
                Thread.sleep(50)
                continue
            }
            readAt = end
            val r = Reading(anchor, end, mac, if (mac == null) localLive() else 0L)
            if (!r.await { ending }) {
                pending = r
                break
            }
            if (cancelled) break
            val raw = r.raw
            if (raw == null) {
                // The Mac didn't answer in time: the phone reads the rest of this dictation, this audio first.
                if (r.onMac) {
                    mac = null
                    readAt = 0
                }
                continue
            }
            val heard = parseHeard(raw)
            val now = heard.joinToString(" ") { it.text }
            live = live.read(now)
            lastFrom = anchor
            lastTo = end
            lastText = now
            // A long stretch is settled up to its last segment, and reading starts again after it.
            if (end - anchor > RATE * SETTLE_AFTER_S && heard.size > 1) {
                val keep = heard.dropLast(1)
                live = live.keep(keep.joinToString(" ") { it.text })
                anchor += (keep.last().end * RATE / 100).toInt().coerceAtMost(end - anchor)
            }
            val shown = live
            main.post { if (!cancelled) onText(shown) }
        }
        if (cancelled) {
            if (pending?.onMac == false) stopPhoneReading()
            return
        }
        val end = if (voiceFrom < 0) 0 else minOf(count, voiceTo + RATE * PAD_MS / 1000)
        // Ended before any reading: none of the quiet before his first word either.
        if (anchor < voiceFrom) anchor = voiceFrom
        pending?.let { r ->
            if (r.onMac && r.from == voiceFrom && anchor == voiceFrom && r.to >= end) {
                // Under way on the Mac, and of all of it: that one is the end of it, so wait for it.
                if (r.await { false }) {
                    r.raw?.let {
                        lastFrom = r.from
                        lastTo = r.to
                        lastText = parseHeard(it).joinToString(" ") { h -> h.text }
                    } ?: run { mac = null }
                }
            } else if (!r.onMac) {
                // The phone's: the whole of it is read again just below, so this one stops now.
                stopPhoneReading()
            }
            // A Mac reading that would be read again anyway is let run out on its own; its words aren't used.
        }
        val text =
            when {
                end - maxOf(voiceFrom, 0) < RATE / 4 -> ""
                else ->
                    when (val plan = finishPlan(mac != null, voiceFrom, anchor, end, lastFrom, lastTo, lastText)) {
                        is Finish.Reuse -> plan.text
                        Finish.Whole -> whole()
                    }
            }
        main.post { if (!cancelled) onDone(text.trim(), null) }
    }

    /**
     * The whole of it, read once more by the accurate model (the Mac's, or the phone's); the phone's fast one's
     * reading after the settled words if the accurate one isn't here. From his first word to just after his
     * last: none of the quiet around it.
     */
    private fun whole(): String {
        val all = audio(voiceFrom, minOf(count, voiceTo + RATE * PAD_MS / 1000))
        return read(all, final = true)?.let { raw -> parseHeard(raw).joinToString(" ") { it.text } } ?: live.text
    }

    companion object {
        const val RATE = 16_000
        const val STEP_MS = 100
        const val READ_EVERY_MS = 500
        /**
         * A reading of more than this many seconds settles its words but the last segment's. Kept at 15 s, not
         * shortened: a dictation read live that settled nothing ends without being read again ([finishPlan]),
         * so a shorter one would make every dictation of 8-15 s wait for a whole reading again when he stops.
         */
        const val SETTLE_AFTER_S = 15

        /**
         * How long a live reading may take on the Mac before the phone reads instead (it was 6 s). The Mac takes
         * some 0.4 s whatever the length; the rest is the trip, under half a second through Tailscale's relay.
         */
        const val LIVE_TIMEOUT_MS = 2_000

        /** Kept before his first word and after his last, so their edges aren't cut. */
        const val PAD_MS = 300
    }
}
