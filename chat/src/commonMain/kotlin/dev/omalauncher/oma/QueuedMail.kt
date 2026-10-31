package dev.omalauncher.oma

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Agent mail that waits for a busy builder, kept across a restart. The queue itself lives in memory, so an install or an
 * app update while a builder worked used to drop the mail the sender had been told was sent. Only mail is kept: his own
 * follow-ups still end with the app (FollowUpQueue.RESTARTED). One file per builder, written whenever its waiting mail
 * changes and read once, when that builder is restored.
 */
object QueuedMail {
    fun encode(mail: List<FollowUp>): String = JSONArray(mail.filter { it.mailSender.isNotEmpty() }.map {
        JSONObject().put("time", it.time).put("prompt", it.prompt).put("earlier", it.earlier).put("sender", it.mailSender).put("id", it.mailId)
            .put("task", it.mailTask).put("supersedes", it.supersedes)
    }).toString()

    fun decode(text: String): List<FollowUp> = runCatching {
        val rows = JSONArray(text)
        (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            FollowUp(it.getLong("time"), it.getString("prompt"), earlier = it.optString("earlier"), mailSender = it.getString("sender"),
                mailId = it.optString("id"), mailTask = it.optString("task"), supersedes = it.optString("supersedes"))
        }.filter { it.mailSender.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun save(file: File, mail: List<FollowUp>) {
        val kept = mail.filter { it.mailSender.isNotEmpty() }
        if (kept.isEmpty()) { file.delete(); return }
        val fresh = File(file.parentFile, file.name + ".new")
        fresh.writeText(encode(kept))
        check(fresh.renameTo(file)) { "Could not save the waiting mail" }
    }

    fun read(file: File): List<FollowUp> = runCatching { if (file.isFile && file.length() < 4_000_000) decode(file.readText()) else emptyList() }.getOrDefault(emptyList())

    /** The task times kept for every builder, for the thread to leave them waiting rather than drop them. */
    fun times(folder: File, names: List<String>): Set<Long> = names.flatMap { read(File(folder, it)).map { m -> m.time } }.toSet()
}
