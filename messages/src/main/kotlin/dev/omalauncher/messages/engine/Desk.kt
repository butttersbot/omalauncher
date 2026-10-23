package dev.omalauncher.messages.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Where the owner left a conversation: the words not yet sent, the quote waiting with them, and the place in the
 * messages ([index] and [offset] of the top one shown, when [count] messages were there). No place means the latest.
 */
data class Left(
    val draft: String = "",
    val quote: String? = null,
    val index: Int = -1,
    val offset: Int = 0,
    val count: Int = 0,
) {
    val hasPlace get() = index >= 0
    val isEmpty get() = draft.isBlank() && quote == null && !hasPlace

    companion object {
        fun toJson(all: Map<String, Left>): String =
            JSONObject()
                .put("version", 1)
                .put(
                    "left",
                    JSONObject().apply {
                        all.filterValues { !it.isEmpty }.forEach { (id, l) ->
                            put(id, JSONObject().put("draft", l.draft).put("index", l.index).put("offset", l.offset).put("count", l.count).apply { l.quote?.let { put("quote", it) } })
                        }
                    },
                ).toString()

        /** What [toJson] saved; an unreadable file is nothing left anywhere, never a crash. */
        fun fromJson(text: String): Map<String, Left> =
            runCatching {
                val left = JSONObject(text).getJSONObject("left")
                left.keys().asSequence().associateWith { id ->
                    val l = left.getJSONObject(id)
                    Left(l.optString("draft"), l.optString("quote").ifEmpty { null }, l.optInt("index", -1), l.optInt("offset"), l.optInt("count"))
                }
            }.getOrDefault(emptyMap())
    }
}

/** Every conversation's [Left], kept in files/left.json so drafts and places outlast the app. */
object Desk {
    private const val TAG = "SomaMessages"
    private val _left = MutableStateFlow<Map<String, Left>>(emptyMap())
    val left: StateFlow<Map<String, Left>> = _left
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "left.json")
        file = f
        if (f.isFile) _left.value = Left.fromJson(f.readText())
    }

    operator fun get(id: String) = _left.value[id] ?: Left()

    @Synchronized
    fun update(
        id: String,
        change: (Left) -> Left,
    ) {
        val before = get(id)
        val after = change(before)
        if (after == before) return
        val next = if (after.isEmpty) _left.value - id else _left.value + (id to after)
        _left.value = next
        // Example conversations keep their drafts only while the app runs.
        val f = file?.takeIf { !id.startsWith("sample-") } ?: return
        val text = Left.toJson(next.filterKeys { !it.startsWith("sample-") })
        io.execute {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            }.onFailure { Log.w(TAG, "saving drafts", it) }
        }
    }
}
