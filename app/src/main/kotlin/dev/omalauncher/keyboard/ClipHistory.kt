package dev.omalauncher.keyboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What was copied lately, kept by the Soma keyboard (Boss's banked search list, 2026-10-20: "clipboard history via
 * the Soma keyboard"): Android lets only the keyboard in use and the app in front read the clipboard, so the keyboard
 * notes each copy as it happens. Pasted from the keyboard's clipboard panel, found by search. Text only; a copy
 * marked sensitive (a password manager's) or made while a password field has the keyboard is never kept. The last
 * [KEPT], for [DAYS] days, in the app's own files.
 */
class ClipHistory(
    private val file: File?,
) {
    data class Clip(
        val text: String,
        val at: Long,
    )

    private val clips = ArrayList<Clip>()

    /**
     * Copies made at or before this (ms) aren't taken in again: Clear and a removed clip stay gone, though the phone's
     * clipboard may still hold the last copy and the keyboard reads it again each time it comes up (Boss, 2026-10-21:
     * "the clear button on the clipboard doesn't actually clear it").
     */
    private var clearedAt = 0L
    private val removed = HashMap<String, Long>()

    init {
        runCatching {
            val text = file!!.readText()
            if (text.trimStart().startsWith("[")) {
                read(JSONArray(text))
            } else {
                val o = JSONObject(text)
                read(o.getJSONArray("clips"))
                clearedAt = o.optLong("clearedAt")
                o.optJSONObject("removed")?.let { r -> for (k in r.keys()) removed[k] = r.getLong(k) }
            }
        }
    }

    private fun read(a: JSONArray) {
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            clips += Clip(o.getString("text"), o.getLong("at"))
        }
    }

    /** Newest first, those within [DAYS] days of [now]. */
    @Synchronized fun all(now: Long): List<Clip> = clips.filter { now - it.at <= DAYS * 86_400_000L }

    /** [text] was copied at [now]: it goes first (once), trimmed to [MAX_CHARS]. False if there was nothing to keep. */
    @Synchronized fun add(
        text: String,
        now: Long,
    ): Boolean {
        val t = text.take(MAX_CHARS)
        if (t.isBlank()) return false
        if (now <= clearedAt || (removed[t] ?: -1L) >= now) return false
        if (clips.firstOrNull()?.text == t) return false
        clips.removeAll { it.text == t }
        clips.add(0, Clip(t, now))
        while (clips.size > KEPT) clips.removeAt(clips.lastIndex)
        save()
        return true
    }

    /** Takes [text] out, and keeps it out unless it is copied again after [now]. */
    @Synchronized fun remove(
        text: String,
        now: Long = System.currentTimeMillis(),
    ) {
        clips.removeAll { it.text == text }
        removed[text] = now
        while (removed.size > KEPT) removed.remove(removed.minByOrNull { it.value }!!.key)
        save()
    }

    /** Empties it, and keeps out everything copied up to [now]. */
    @Synchronized fun clear(now: Long = System.currentTimeMillis()) {
        clips.clear()
        removed.clear()
        clearedAt = now
        save()
    }

    /** Clips with every word of [query] in them, newest first. */
    fun find(
        query: String,
        now: Long,
        limit: Int = 3,
    ): List<Clip> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.sumOf { it.length } < 2) return emptyList()
        return all(now).filter { c -> val l = c.text.lowercase(); words.all { it in l } }.take(limit)
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val list = JSONArray()
            for (c in clips) list.put(JSONObject().put("text", c.text).put("at", c.at))
            val a = JSONObject().put("clips", list).put("clearedAt", clearedAt).put("removed", JSONObject(removed.mapValues { it.value } as Map<*, *>))
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(f)
        }
    }

    companion object {
        const val KEPT = 40
        const val DAYS = 7
        const val MAX_CHARS = 5_000

        /** How long after a copy the keyboard offers it to paste with one tap. */
        const val FRESH_MS = 2 * 60_000L

        @Volatile private var shared: ClipHistory? = null

        /** The app's one history, read once. */
        fun of(context: android.content.Context): ClipHistory =
            shared ?: synchronized(this) { shared ?: ClipHistory(File(context.filesDir, "clips/history.json")).also { shared = it } }

        /** One line of [text] for a row: its whitespace run together. */
        fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim()
    }
}
