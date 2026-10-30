package dev.omalauncher.messages.engine

import org.json.JSONArray
import org.json.JSONObject

/** Full attribution already present in /v1; legacy tapback strings remain readable. */
data class Reaction(val kind: String, val sender: String, val fromMe: Boolean, val name: String? = null) {
    fun json() = JSONObject().put("kind", kind).put("sender", sender).put("fromMe", fromMe).put("name", name)
    companion object {
        fun read(a: JSONArray?): List<Reaction> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val kind = o.optString("kind").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Reaction(kind, o.optString("sender"), o.optBoolean("fromMe"), o.optString("name").takeUnless { o.isNull("name") || it.isBlank() })
        }
    }
}

/** Only this process's new sends get an entrance. Restart/history/echo never replay it. */
object SendMotion {
    private val pending = linkedMapOf<String, Long>()
    @Synchronized fun mark(clientId: String, now: Long = System.currentTimeMillis()) {
        pending.entries.removeAll { now - it.value > 5_000 }
        if (pending.size >= 32) pending.remove(pending.keys.first())
        pending["c:$clientId"] = now
    }
    @Synchronized fun take(key: String, now: Long = System.currentTimeMillis()): Boolean =
        pending.remove(key)?.let { now - it in 0..5_000 } ?: false
}
