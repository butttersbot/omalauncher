package dev.omalauncher.messages.engine

import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Each picture's shape (height / width), known before its bytes: from the Mac's message, then from the picture itself,
 * which also knows which way up it is. A photo takes its full height from the start, so nothing under it moves when it
 * loads (Boss: "the scrolling will shift and then it'll stutter"). Kept on the phone so a second look starts right too.
 */
object PictureShapes {
    private const val MAX_KEPT = 4096
    private val shapes = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>) = size > MAX_KEPT
    }
    private val disk = Executors.newSingleThreadExecutor()
    @Volatile private var file: File? = null
    @Volatile private var saving = false

    @Synchronized fun of(id: String): Float? = shapes[id]

    /** What the Mac says, for a picture not looked at yet. */
    @Synchronized fun fromTheMac(id: String, width: Int, height: Int) {
        if (width > 0 && height > 0 && id !in shapes) shapes[id] = height.toFloat() / width
    }

    /** What the picture turned out to be. */
    fun learned(id: String, shape: Float) {
        synchronized(this) {
            if (shapes[id] == shape) return
            shapes[id] = shape
        }
        save()
    }

    fun load(into: File) {
        file = into
        disk.execute {
            val saved = runCatching { JSONObject(into.readText()) }.getOrNull() ?: return@execute
            synchronized(this) {
                saved.keys().forEach { id -> if (id !in shapes) shapes[id] = saved.optDouble(id).toFloat() }
            }
        }
    }

    private fun save() {
        val to = file ?: return
        if (saving) return
        saving = true
        disk.execute {
            Thread.sleep(2_000)
            saving = false
            val text = synchronized(this) { JSONObject(shapes.mapValues { it.value.toDouble() }).toString() }
            runCatching { to.writeText(text) }
        }
    }
}
