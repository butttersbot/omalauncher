package dev.omalauncher.notes.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** A note as the phone keeps it. */
data class Note(
    /** Its path under ~/Notes; a new note's is "~/" and a number until it is first sent. */
    val id: String,
    val title: String,
    val kind: String,
    val ext: String,
    val modified: Long,
    /** The Mac's version this copy came from; null until first sent. */
    val sha: String?,
    /** Changed here since, still to send. */
    val dirty: Boolean = false,
    /** Deleted here, still to tell the Mac. */
    val gone: Boolean = false,
    val preview: String = "",
    /** Kept at the top of the stream (a swipe right). The phone's own, for now: the Mac doesn't keep pins. */
    val pinned: Boolean = false,
) {
    val agent get() = kind == Wire.AGENT
    val html get() = ext == "html"
    val local get() = id.startsWith(LOCAL)

    companion object {
        const val LOCAL = "~/"
    }
}

/**
 * The phone's own copy of every note, in a folder: an index, each note's text, and the text it last agreed on with the
 * Mac (for merging both sides' edits). Plain files and org.json, so the JVM tests run it on a temporary folder.
 */
class Shelf(private val dir: File) {
    private val index = File(dir, "index.json")
    private val texts = File(dir, "text")
    private val bases = File(dir, "base")
    private val notes = LinkedHashMap<String, Note>()

    /** New notes' first ids, to the file names they were sent as: an open page keeps writing to the same note. */
    private val moved = HashMap<String, String>()

    init {
        texts.mkdirs()
        bases.mkdirs()
        runCatching {
            val a = JSONArray(index.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val n =
                    Note(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        kind = o.optString("kind", Wire.MINE),
                        ext = o.optString("ext", "md"),
                        modified = o.optLong("modified"),
                        sha = o.optString("sha").ifEmpty { null },
                        dirty = o.optBoolean("dirty"),
                        gone = o.optBoolean("gone"),
                        preview = o.optString("preview"),
                        pinned = o.optBoolean("pinned"),
                    )
                notes[n.id] = n
            }
        }
    }

    /** What note [id] is called now. */
    @Synchronized
    fun now(id: String): String = moved[id] ?: id

    @Synchronized
    fun all(): List<Note> = notes.values.toList()

    @Synchronized
    operator fun get(id: String): Note? = notes[now(id)]

    @Synchronized
    fun text(id: String): String = runCatching { file(texts, now(id)).readText() }.getOrDefault("")

    @Synchronized
    fun base(id: String): String = runCatching { file(bases, id).readText() }.getOrDefault("")

    /** [text] for note [note], written here first; to send. */
    @Synchronized
    fun write(
        note: String,
        text: String,
        at: Long = System.currentTimeMillis(),
    ): Note {
        val id = now(note)
        val was = notes[id]
        val n =
            (was ?: Note(id, "", Wire.MINE, "md", at, null)).copy(
                title = Wire.title(text, if (id.startsWith(Note.LOCAL)) "" else id),
                modified = at,
                dirty = true,
                gone = false,
                preview = if (id.endsWith(".html")) "" else Wire.preview(text),
            )
        file(texts, id).writeText(text)
        notes[id] = n
        save()
        return n
    }

    /** A new, empty page's id. */
    @Synchronized
    fun fresh(at: Long = System.currentTimeMillis()): String {
        var n = at
        while ("${Note.LOCAL}$n" in notes || "${Note.LOCAL}$n" in moved) n++
        return "${Note.LOCAL}$n"
    }

    /** The Mac's [text] at [remote]'s sha, agreed on by both sides. */
    @Synchronized
    fun took(
        remote: Remote,
        text: String,
    ) {
        file(texts, remote.id).writeText(text)
        file(bases, remote.id).writeText(text)
        notes[remote.id] =
            Note(
                id = remote.id,
                title = if (remote.ext == "html") remote.title else Wire.title(text, remote.id),
                kind = remote.kind,
                ext = remote.ext,
                modified = remote.modified,
                sha = remote.sha,
                preview = if (remote.ext == "html") "" else Wire.preview(text),
                pinned = notes[remote.id]?.pinned == true,
            )
        save()
    }

    /** Note [note] pinned to the top of the stream, or let go. */
    @Synchronized
    fun pin(
        note: String,
        on: Boolean,
    ) {
        val id = now(note)
        val n = notes[id] ?: return
        notes[id] = n.copy(pinned = on)
        save()
    }

    /**
     * Note [id] sent: the Mac has [text] at [sha] now, under [to] (a new note's file name). [read] is what the phone had
     * when it went out ([text] differs when it was merged with the Mac's).
     */
    @Synchronized
    fun sent(
        id: String,
        to: String,
        read: String,
        text: String,
        sha: String,
    ) {
        val was = notes.remove(id) ?: return
        val still = file(texts, id).takeIf { it.exists() }?.readText() ?: read
        file(texts, id).delete()
        file(bases, id).delete()
        // Typed into again while it was on its way: that's still to send, on top of what went.
        val changed = still != read
        val here = if (changed) Merge.merge(read, still, text) else text
        file(texts, to).writeText(here)
        file(bases, to).writeText(text)
        notes[to] =
            was.copy(
                id = to,
                sha = sha,
                dirty = changed,
                title = Wire.title(here, to),
                preview = if (to.endsWith(".html")) "" else Wire.preview(here),
            )
        if (to != id) moved[id] = to
        save()
    }

    /** Deleted here: kept, out of sight, until the Mac is told. */
    @Synchronized
    fun delete(note: String) {
        val id = now(note)
        val n = notes[id] ?: return
        if (n.sha == null) {
            forget(id)
        } else {
            notes[id] = n.copy(gone = true)
            save()
        }
    }

    @Synchronized
    fun forget(id: String) {
        notes.remove(id)
        file(texts, id).delete()
        file(bases, id).delete()
        save()
    }

    private fun save() {
        val a = JSONArray()
        for (n in notes.values) {
            a.put(
                JSONObject()
                    .put("id", n.id).put("title", n.title).put("kind", n.kind).put("ext", n.ext).put("modified", n.modified)
                    .put("sha", n.sha ?: "").put("dirty", n.dirty).put("gone", n.gone).put("preview", n.preview).put("pinned", n.pinned),
            )
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(index)
    }

    private fun file(
        under: File,
        id: String,
    ) = File(under, URLEncoder.encode(id, "UTF-8"))
}
