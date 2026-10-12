package dev.omalauncher.widgets.annotations

import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.writeAtomically
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.math.roundToInt

/** What reading a page's annotations came to. */
sealed interface AnnotationsRead {
    /** There is no file, or nothing in it: a page nobody has written on. */
    data object None : AnnotationsRead

    data class Read(
        val annotations: Annotations,
    ) : AnnotationsRead

    /** There is a file and it can't be used. It stays as it is: nothing is ever written over it. */
    data class Unreadable(
        val why: Why,
    ) : AnnotationsRead

    enum class Why {
        /** Not a document this launcher wrote, or one that was cut short. */
        Damaged,

        /** Written by a newer launcher, which may keep in it what this one doesn't know of. */
        Newer,

        /** Bigger than a page's annotations ever are. */
        TooBig,

        /** It couldn't be read from the disk at all. */
        Failed,
    }
}

/**
 * A page's annotations as a document, in the [Versioned] wrapper:
 * `{"strokes": [{"t": tool, "c": ink, "w": width, "p": [x, y, x, y…]}],
 *   "notes": [{"id", "x", "y", "w", "h", "text", "c": surface, "z", "by", "at"}],
 *   "stickers": [{"id", "k": kind, "x", "y", "s": size, "r": degrees}]}`.
 * Every fraction is a whole number of [UNITS]. Reading is strict: a document with anything in it that doesn't
 * parse is unreadable as a whole, and is left alone, rather than read in part and saved back without the rest.
 */
object AnnotationStore {
    const val VERSION = 1

    fun write(annotations: Annotations): String {
        val strokes = JSONArray()
        for (stroke in annotations.strokes) {
            val points = JSONArray()
            for (p in stroke.points) points.put(steps(p))
            strokes.put(JSONObject().put("t", stroke.tool.id).put("c", stroke.role.id).put("w", steps(stroke.width)).put("p", points))
        }
        val notes = JSONArray()
        for (n in annotations.notes) {
            notes.put(
                JSONObject()
                    .put("id", n.id)
                    .put("x", steps(n.x))
                    .put("y", steps(n.y))
                    .put("w", steps(n.w))
                    .put("h", steps(n.h))
                    .put("text", n.text)
                    .put("c", n.role.id)
                    .put("z", n.z)
                    .put("by", n.author.id)
                    .put("at", n.at),
            )
        }
        val stickers = JSONArray()
        for (s in annotations.stickers) {
            stickers.put(JSONObject().put("id", s.id).put("k", s.kind.id).put("x", steps(s.x)).put("y", steps(s.y)).put("s", steps(s.size)).put("r", s.turn))
        }
        return Versioned.write(VERSION, JSONObject().put("strokes", strokes).put("notes", notes).put("stickers", stickers))
    }

    /** Nothing it is given makes it throw, errors included (Android's org.json recurses without a limit). */
    fun read(text: String?): AnnotationsRead {
        if (text.isNullOrBlank()) return AnnotationsRead.None
        return try {
            val root = JSONTokener(text).nextValue() as? JSONObject ?: return AnnotationsRead.Unreadable(AnnotationsRead.Why.Damaged)
            val version = root.getInt("v")
            when {
                version > VERSION -> AnnotationsRead.Unreadable(AnnotationsRead.Why.Newer)
                version < VERSION -> AnnotationsRead.Unreadable(AnnotationsRead.Why.Damaged)
                else -> AnnotationsRead.Read(parse(root.getJSONObject("data")))
            }
        } catch (e: Throwable) {
            AnnotationsRead.Unreadable(AnnotationsRead.Why.Damaged)
        }
    }

    private fun parse(data: JSONObject): Annotations {
        val strokes =
            data.list("strokes") { o ->
                val p = o.getJSONArray("p")
                require(p.length() % 2 == 0 && p.length() >= 2)
                Stroke(
                    tool = InkTool.entries.first { it.id == o.getString("t") },
                    role = InkRole.entries.first { it.id == o.getString("c") },
                    width = fraction(o.getInt("w")),
                    points = FloatArray(p.length()) { fraction(p.getInt(it)) },
                ).fitted()
            }
        val notes =
            data.list("notes") { o ->
                StickyNote(
                    id = o.getString("id").also { require(it.isNotEmpty()) },
                    x = fraction(o.getInt("x")),
                    y = fraction(o.getInt("y")),
                    w = fraction(o.getInt("w")),
                    h = fraction(o.getInt("h")),
                    text = o.getString("text"),
                    role = NoteRole.entries.first { it.id == o.getString("c") },
                    z = o.getInt("z"),
                    author = Author.entries.first { it.id == o.getString("by") },
                    at = o.getLong("at"),
                ).fitted()
            }
        val stickers =
            data.list("stickers") { o ->
                Sticker(
                    id = o.getString("id").also { require(it.isNotEmpty()) },
                    kind = StickerKind.entries.first { it.id == o.getString("k") },
                    x = fraction(o.getInt("x")),
                    y = fraction(o.getInt("y")),
                    size = fraction(o.getInt("s")),
                    turn = o.getInt("r"),
                ).fitted()
            }
        // Two things with one id couldn't be told apart when one of them is moved or deleted.
        val ids = notes.map { it.id } + stickers.map { it.id }
        require(ids.size == ids.toSet().size)
        return Annotations(strokes, notes, stickers)
    }

    /** Every entry of the array at [key], which may be missing; one that isn't an object, or doesn't parse, throws. */
    private fun <T> JSONObject.list(
        key: String,
        parse: (JSONObject) -> T,
    ): List<T> {
        if (!has(key)) return emptyList()
        val array = getJSONArray(key)
        return (0 until array.length()).map { parse(array.getJSONObject(it)) }
    }

    private fun steps(fraction: Float) = (fraction * UNITS).roundToInt()

    private fun fraction(steps: Int) = unit(steps / UNITS.toFloat())
}

/**
 * The per-page annotations files: `<page id>.json` under [dir], in the app's private files. A page's file is
 * replaced in one rename, so it is the old one or the new one and never half of either. Nothing here runs on
 * the main thread; [AnnotationBook] sees to that.
 */
class AnnotationFiles(
    private val dir: File,
) {
    /** Whether [page] is an id a file can go by: the notebook's are `p1`, `p2`…; anything with a path in it is not. */
    fun valid(page: String) = ID.matches(page)

    fun file(page: String): File {
        require(valid(page)) { "not a page id: $page" }
        return File(dir, "$page.json")
    }

    fun read(page: String): AnnotationsRead {
        val file = file(page)
        return try {
            when {
                !file.exists() -> AnnotationsRead.None
                !file.isFile -> AnnotationsRead.Unreadable(AnnotationsRead.Why.Failed)
                file.length() > AnnotationLimits.FILE_BYTES -> AnnotationsRead.Unreadable(AnnotationsRead.Why.TooBig)
                else -> AnnotationStore.read(file.readText())
            }
        } catch (e: Throwable) {
            // Not known to be absent or empty, so never written over as if it were.
            AnnotationsRead.Unreadable(AnnotationsRead.Why.Failed)
        }
    }

    /** Replaces [page]'s file; throws if it couldn't, and then the file is as it was. */
    fun write(
        page: String,
        annotations: Annotations,
    ) = writeAtomically(file(page), AnnotationStore.write(annotations).toByteArray())

    /** The ids of the pages that have a file. */
    fun pages(): Set<String> = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") && valid(it.name.removeSuffix(".json")) }.map { it.name.removeSuffix(".json") }.toSet()

    /**
     * Takes [page]'s file away, for a page that was deleted: moved into `.removed/` under a name with [stamp]
     * in it, like a removed widget's file, not destroyed. True if no file is left under the page's id, so a
     * later page given the same id starts with nothing on it.
     */
    fun remove(
        page: String,
        stamp: Long,
    ): Boolean {
        val file = file(page)
        if (!file.exists()) return true
        val aside = File(dir, REMOVED).apply { mkdirs() }
        var to = File(aside, "$page-$stamp.json")
        var n = 2
        while (to.exists()) to = File(aside, "$page-$stamp-${n++}.json")
        return file.renameTo(to) || file.delete() || !file.exists()
    }

    companion object {
        const val REMOVED = ".removed"
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
