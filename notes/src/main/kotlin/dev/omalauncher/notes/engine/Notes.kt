package dev.omalauncher.notes.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The app's notes: the phone's copy, always written first, and the rounds with the Mac that keep it the same as
 * ~/Notes. One thread does the rounds, so they never overlap; a save asks for one a moment later, so typing sends a
 * note once it pauses rather than at every letter.
 */
object Notes {
    private const val TAG = "SomaNotes"

    /** How the Mac was, last time it was asked. */
    data class Reach(val reached: Boolean? = null, val left: Int = 0, val at: Long = 0)

    private lateinit var shelf: Shelf
    private val link: Link = HttpLink()
    private val rounds = Executors.newSingleThreadScheduledExecutor { Thread(it, "notes-sync").apply { isDaemon = true } }
    private var next: ScheduledFuture<*>? = null

    private val _all = MutableStateFlow<List<Note>>(emptyList())
    val all: StateFlow<List<Note>> = _all
    private val _reach = MutableStateFlow(Reach())
    val reach: StateFlow<Reach> = _reach

    /** Bumped when notes came in from the Mac: open pages look again at their text. */
    val came = MutableStateFlow(0)

    @Synchronized
    fun init(context: Context) {
        if (::shelf.isInitialized) return
        shelf = Shelf(File(context.filesDir, "notes"))
        publish()
    }

    fun text(id: String): String = shelf.text(id)

    operator fun get(id: String): Note? = shelf[id]

    fun now(id: String): String = shelf.now(id)

    fun fresh(): String = shelf.fresh()

    /** [text] saved as note [id], here at once and to the Mac shortly. */
    fun save(
        id: String,
        text: String,
    ) {
        if (shelf[id] == null && text.isBlank()) return
        if (shelf[id] != null && shelf.text(id) == text) return
        shelf.write(id, text)
        publish()
        sync(SOON_MS)
    }

    /** A note captured whole (quick capture, shared in): saved, sent now. */
    fun add(text: String): String {
        val id = shelf.fresh()
        shelf.write(id, text)
        publish()
        sync(0)
        return id
    }

    fun pin(
        id: String,
        on: Boolean,
    ) {
        shelf.pin(id, on)
        publish()
    }

    fun delete(id: String) {
        shelf.delete(id)
        publish()
        sync(0)
    }

    /** A round with the Mac, after [delayMs]; one already waiting is moved to then. */
    @Synchronized
    fun sync(delayMs: Long = 0) {
        next?.cancel(false)
        next =
            rounds.schedule({
                val r = runCatching { Sync(shelf, link).run() }.onFailure { Log.w(TAG, "sync", it) }.getOrNull()
                if (r != null) {
                    Log.i(TAG, "sync reached=${r.reached} sent=${r.sent} took=${r.took} left=${r.left}")
                    _reach.value = Reach(r.reached, r.left, System.currentTimeMillis())
                    if (r.took > 0) came.value++
                }
                publish()
            }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun publish() {
        _all.value = shelf.all().filter { !it.gone && !(it.local && it.title.isEmpty() && shelf.text(it.id).isBlank()) }.sortedByDescending { it.modified }
    }

    private const val SOON_MS = 1_500L
}
