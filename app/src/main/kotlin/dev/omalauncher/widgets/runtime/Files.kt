package dev.omalauncher.widgets.runtime

import android.os.FileObserver
import dev.omalauncher.readUpTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A watched file is read up to this much; widgets show a few values, not whole logs. */
private const val MAX_FILE_BYTES = 1 shl 20

/**
 * One inotify watch per folder for the whole process, shared by every file() source in it. Android's FileObservers
 * on one folder share a watch descriptor, so one source stopping (its page went off screen) silently ended the
 * watch for the rest, which then saw changes only at their 30 s check (Boss, 2026-10-21: Pet home and Steps both
 * read ~/.cache/soma-pet, and Pet home's taps acted on a picture up to half a minute old).
 */
internal object FolderWatch {
    private class Watch(val observer: FileObserver, val listeners: MutableSet<(String?) -> Unit>)

    private val watches = HashMap<String, Watch>()
    private const val MASK = FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE

    /** Calls [listener] with the name of what changed in [dir]; false if [dir] can't be watched now. */
    @Synchronized fun add(
        dir: File,
        listener: (String?) -> Unit,
    ): Boolean {
        if (!dir.isDirectory) return false
        watches[dir.path]?.let {
            it.listeners += listener
            return true
        }
        val listeners = java.util.concurrent.CopyOnWriteArraySet<(String?) -> Unit>()
        listeners += listener
        val observer =
            object : FileObserver(dir, MASK) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    listeners.forEach { it(path) }
                }
            }
        watches[dir.path] = Watch(observer, listeners)
        observer.startWatching()
        return true
    }

    @Synchronized fun remove(
        dir: File,
        listener: (String?) -> Unit,
    ) {
        val w = watches[dir.path] ?: return
        w.listeners -= listener
        if (w.listeners.isEmpty()) {
            w.observer.stopWatching()
            watches.remove(dir.path)
        }
    }
}

/**
 * [file]'s text now and after every change, for `source x = file(...)`.
 * Changes arrive through inotify on its folder ([FolderWatch]), so a file replaced by a rename
 * (how editors and `jq ... > tmp && mv` save) is seen too. A stat every few
 * seconds covers a folder that doesn't exist yet or was replaced. A missing
 * file reads as "".
 */
fun watchFile(file: File): Flow<String> =
    callbackFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val listener: (String?) -> Unit = { path -> if (path == file.name) changed.trySend(Unit) }
        var watched: File? = null

        fun watch() {
            val dir = file.parentFile ?: return
            if (watched == dir && dir.isDirectory) return
            watched?.let { FolderWatch.remove(it, listener) }
            watched = if (FolderWatch.add(dir, listener)) dir else null
        }

        val loop =
            launch {
                var stamp: Pair<Long, Long>? = null
                var force = true
                while (isActive) {
                    watch()
                    val now = file.lastModified() to file.length()
                    if (force || now != stamp) {
                        stamp = now
                        send(read(file))
                    }
                    force = withTimeoutOrNull(if (watched != null) 30_000L else 5_000L) { changed.receive() } != null
                    // A writer may still be at it; read once it's done.
                    if (force) delay(100)
                }
            }
        awaitClose {
            loop.cancel()
            watched?.let { FolderWatch.remove(it, listener) }
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

private fun read(file: File): String =
    runCatching {
        if (!file.isFile) return ""
        String(file.inputStream().use { it.readUpTo(MAX_FILE_BYTES) }, Charsets.UTF_8).trimEnd()
    }.getOrDefault("")
