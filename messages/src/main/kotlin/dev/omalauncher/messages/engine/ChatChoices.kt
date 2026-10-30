package dev.omalauncher.messages.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Owner choices on this phone only. Never sent to Apple or inferred from incoming messages. */
data class LocalChatChoices(val pinned: List<String> = emptyList(), val muted: Set<String> = emptySet()) {
    fun pin(id: String, on: Boolean) = if (on && (id in pinned || pinned.size >= 9)) this else copy(pinned = pinned.filterNot { it == id } + if (on) listOf(id) else emptyList())
    fun mute(id: String, on: Boolean) = copy(muted = if (on) muted + id else muted - id)

    /** A pinned chat keeps its chosen position when another chat receives a new message. */
    fun order(chats: List<Conversation>): List<Conversation> {
        val byID = chats.associateBy { it.id }
        return pinned.mapNotNull(byID::get) + chats.filterNot { it.id in pinned }.sortedByDescending { it.lastTime }
    }

    fun json() = JSONObject().put("version", 1).put("scope", "phone")
        .put("pinned", JSONArray(pinned)).put("muted", JSONArray(muted.toList())).toString()

    companion object {
        fun read(text: String): LocalChatChoices = runCatching {
            val o = JSONObject(text)
            fun ids(key: String): List<String> = o.optJSONArray(key)?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).takeIf { id -> id.isNotBlank() && id.length <= 1024 } }.distinct()
            }.orEmpty()
            LocalChatChoices(ids("pinned"), ids("muted").toSet())
        }.getOrDefault(LocalChatChoices())
    }
}

/** Kept separately from Mac history, so history refresh cannot overwrite phone-local pin/mute choices. */
object ChatChoices {
    private val current = MutableStateFlow(LocalChatChoices())
    val state: StateFlow<LocalChatChoices> = current
    private val disk = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private var app: Context? = null

    @Synchronized fun init(context: Context) {
        if (file != null) return
        app = context.applicationContext
        file = File(context.filesDir, "chat-choices.json").also { f ->
            if (f.isFile) current.value = runCatching { LocalChatChoices.read(f.readText()) }.getOrDefault(LocalChatChoices())
        }
    }

    @Synchronized fun update(change: (LocalChatChoices) -> LocalChatChoices) {
        val next = change(current.value)
        if (next == current.value) return
        val newlyMuted = next.muted - current.value.muted
        current.value = next
        app?.let { context -> newlyMuted.forEach { Alerts.clear(context, it) } }
        val f = file ?: return
        // Example conversations can be tried without becoming real phone preferences.
        val kept = next.copy(pinned = next.pinned.filterNot { it.startsWith("sample-") }, muted = next.muted.filterNot { it.startsWith("sample-") }.toSet())
        disk.execute {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(kept.json())
                check(tmp.renameTo(f)) { "Could not save conversation choices" }
            }.onFailure { Log.w("SomaMessages", "saving conversation choices", it) }
        }
    }
}
