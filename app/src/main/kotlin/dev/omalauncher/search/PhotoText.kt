package dev.omalauncher.search

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The phone's own reading of a photo search's words, for when the Mac Pro can't be reached (Boss, 2026-10-20: "when
 * the mac pro is unreachable we can have the fall back be running on the phone... we already do that with whisper").
 * EmbeddingGemma 2's text-only bundle gives the very vectors the Mac's text + vision bundle does (checked on the Mac,
 * bit for bit), so photos read by the Mac are found the same way offline.
 *
 * The model (165 MB) is fetched once on unmetered Wi-Fi by the photo indexer. It runs in the `:indexer` process
 * ([PhotoTextService]), never in the launcher's, is loaded for the first offline photo search and let go 30 seconds
 * after the last one, when that process ends.
 */
object PhotoModel {
    const val URL = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm"

    /** Its size in bytes, as the Mac Pro downloaded it: a download of any other size is not kept. */
    const val SIZE = 164_626_432L

    /** What the Mac Pro's server puts before a search's words; the same here, or the vectors don't match. */
    const val QUERY = "task: search query | text: "

    fun file(context: Context) = File(context.filesDir, "models/embeddinggemma-2-text-270m.litertlm")

    fun ready(context: Context) = file(context).length() == SIZE

    /** Whether the phone's vectors were found to match the Mac's (checked once, after the download). */
    fun agrees(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AGREES, false)

    private const val PREFS = "photo_model"
    private const val AGREES = "agrees"

    /** Fetches the model if it isn't here yet. Called from the indexer, on unmetered Wi-Fi. */
    fun download(
        context: Context,
        stopped: () -> Boolean,
    ) {
        if (ready(context)) return
        val f = file(context)
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        runCatching {
            val c = URL(URL).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            // Picks up where an earlier download stopped.
            if (part.exists()) c.setRequestProperty("Range", "bytes=${part.length()}-")
            val append = c.responseCode == 206
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (!stopped()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (part.length() == SIZE) part.renameTo(f) else if (part.length() > SIZE) part.delete()
    }

    /**
     * Checks once that the phone's vector for a phrase is the Mac's, and remembers the answer: only then is the
     * phone's reading used for searches. Runs in the indexer with the Mac reachable.
     */
    fun check(
        context: Context,
        base: String,
    ) {
        if (!ready(context) || agrees(context)) return
        val phrase = "a cat sitting at a window at night"
        val mac = PhotoMac.text(base, phrase) ?: return
        val phone = runCatching { PhotoEngine.vector(context, phrase) }.getOrNull() ?: return
        val cosine = mac.indices.sumOf { (mac[it] * phone.getOrElse(it) { 0f }).toDouble() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AGREES, cosine > 0.99).putFloat("cosine", cosine.toFloat()).apply()
        PhotoEngine.release()
    }
}

/** The model itself, in the indexer's process: loaded on first use, released when told. */
internal object PhotoEngine {
    private var engine: com.google.ai.edge.litertlm.EmbeddingEngine? = null

    @Synchronized fun vector(
        context: Context,
        text: String,
    ): FloatArray {
        val e = engine ?: open(context, com.google.ai.edge.litertlm.Backend.GPU()) ?: open(context, com.google.ai.edge.litertlm.Backend.CPU(null, null)) ?: error("the photo model didn't load")
        engine = e
        val options = com.google.ai.edge.litertlm.EmbeddingOptions(true, null, PhotoIndex.DIM, null)
        val v = e.computeEmbedding(listOf(com.google.ai.edge.litertlm.InputData.Text(PhotoModel.QUERY + text.trim())), options).embedding
        // As the Mac cuts it: the first numbers, scaled to length 1.
        val cut = FloatArray(PhotoIndex.DIM) { v.getOrElse(it) { 0f } }
        val n = kotlin.math.sqrt(cut.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(cut.size) { cut[it] / n }
    }

    /** The model on [backend], loaded; null if it won't load there (no GPU driver to be had, say). */
    private fun open(
        context: Context,
        backend: com.google.ai.edge.litertlm.Backend,
    ) = runCatching {
        com.google.ai.edge.litertlm.EmbeddingEngine(com.google.ai.edge.litertlm.EmbeddingEngineConfig(PhotoModel.file(context).path, backend, null, null, context.cacheDir.path)).also { it.initialize() }
    }.getOrNull()

    @Synchronized fun release() {
        engine?.close()
        engine = null
    }
}

/**
 * Reads a search's words with the phone's model, for the launcher, from the indexer's process: started for a search
 * and stopped 30 seconds after the last, taking the model's memory with it.
 */
class PhotoTextService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val idle = Runnable { stopSelf() }
    private val messenger =
        Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                val text = msg.data.getString("text").orEmpty()
                val reply = msg.replyTo
                handler.removeCallbacks(idle)
                thread(name = "photo-text") {
                    val v = runCatching { PhotoEngine.vector(applicationContext, text) }.getOrNull()
                    runCatching { reply?.send(Message.obtain().apply { data = Bundle().apply { putFloatArray("vector", v) } }) }
                    handler.postDelayed(idle, IDLE_MS)
                }
                true
            },
        )

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idle)
        PhotoEngine.release()
        super.onDestroy()
    }

    companion object {
        private const val IDLE_MS = 30_000L

        /** The vector of [text] from the phone's model, or null if it isn't fetched, didn't match the Mac's, or failed. */
        fun vector(
            context: Context,
            text: String,
            timeoutMs: Long = 8_000,
        ): FloatArray? {
            if (!PhotoModel.ready(context) || !PhotoModel.agrees(context)) return null
            val answer = java.util.concurrent.ArrayBlockingQueue<Any>(1)
            val replies = android.os.HandlerThread("photo-text-reply").apply { start() }
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        binder: IBinder,
                    ) {
                        val reply = Messenger(Handler(replies.looper) { m -> answer.offer(m.data.getFloatArray("vector") ?: Unit); true })
                        runCatching { Messenger(binder).send(Message.obtain().apply { data = Bundle().apply { putString("text", text) }; replyTo = reply }) }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        answer.offer(Unit)
                    }
                }
            val intent = Intent(context, PhotoTextService::class.java)
            return try {
                runCatching { context.startService(intent) }
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) return null
                answer.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) as? FloatArray
            } finally {
                runCatching { context.unbindService(connection) }
                replies.quitSafely()
            }
        }
    }
}
