package dev.omalauncher.widgets.annotations

import dev.omalauncher.upgrade.writeAtomically
import java.io.File
import java.security.MessageDigest

/*
 * Stickers that are pictures. The owner keeps image files in a folder, the library, and picks from them; what
 * is stuck on a page is never that file but a copy of its bytes the launcher makes at that moment and keeps
 * beside the pages' own files. So art that is replaced or deleted later never repaints or breaks a sticker
 * already placed, which is the rule his other notebook, Cove, keeps for its stickers too.
 *
 * Everything here is plain files and names, so it is tested without a phone; decoding is in StickerBitmaps.kt.
 */

/** The bounds on a picture taken as a sticker. */
object StickerLimits {
    /** A file bigger than this is not offered: stickers are small, and one this big is something else. */
    const val FILE_BYTES = 5L * 1024 * 1024

    /** How many of the library's pictures are offered. */
    const val LIBRARY = 300

    /** The longest side a picture is ever decoded to, in pixels. */
    const val SIDE = 512

    /** A picture of more pixels than this is not decoded at all, however small its file. */
    const val PIXELS = 64_000_000L
}

/** The kinds of image a sticker can be, by the ending of the file's name, each with the ending its copy gets. */
private val ENDINGS = mapOf("png" to "png", "webp" to "webp", "jpg" to "jpg", "jpeg" to "jpg", "gif" to "gif")

/** The ending the copy of the image file called [fileName] gets; null for a file that is no image a sticker can be. */
fun pictureEnding(fileName: String): String? = ENDINGS[fileName.substringAfterLast('.', "").lowercase()]

private val SNAPSHOT = Regex("[0-9a-f]{32}\\.(?:png|webp|jpg|gif)")

/** Whether [name] is one the launcher gives its copy of a picture: only such a name is ever read from a page or deleted. */
fun isSnapshotName(name: String) = SNAPSHOT.matches(name)

/** Every copy's name that stands anywhere in [text], which is a page's file read as it is. */
fun snapshotsNamed(text: String): Set<String> = SNAPSHOT.findAll(text).map { it.value }.toSet()

/**
 * What the launcher calls its copy of the image [bytes]: from what is in it, so the same picture stuck twice,
 * from whatever file, is one copy, and a picture changed by a single byte is another.
 */
fun snapshotName(
    bytes: ByteArray,
    ending: String,
): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(32) + "." + ending

/** Those of the copies [present] that nothing in [used] speaks of: what may be deleted. Never a file that isn't a copy's. */
fun unusedSnapshots(
    present: Collection<String>,
    used: Set<String>,
): Set<String> = present.filter { isSnapshotName(it) && it !in used }.toSet()

/**
 * A file in the library: where it is from the library's folder, with `/` between a folder and what is in it,
 * how big, and when it was last written.
 */
data class LibraryFile(
    val path: String,
    val bytes: Long,
    val modified: Long = 0,
)

/**
 * A picture the owner can pick: its file's [path] in the library, what it is called, and the folder it is
 * grouped by, or none. [stamp] changes whenever the file is written again, so what was decoded of the old one
 * is not shown for the new.
 */
data class LibrarySticker(
    val path: String,
    val name: String,
    val group: String,
    val stamp: String = "",
)

data class StickerGroup(
    val name: String,
    val stickers: List<LibrarySticker>,
)

/**
 * The pictures to pick from among [files]: every PNG, WebP, JPEG or GIF straight in the library, and those in a
 * folder one level down, which is the group they are shown under. Hidden files and folders, anything deeper,
 * empty files and files over [StickerLimits.FILE_BYTES] are left out. Those in no folder come first, then the
 * groups by name, each by its pictures' names; no more than [StickerLimits.LIBRARY] in all.
 */
fun libraryStickers(files: List<LibraryFile>): List<StickerGroup> =
    files
        .mapNotNull { file ->
            val parts = file.path.split('/')
            when {
                parts.size > 2 || parts.any { it.isEmpty() || it.startsWith(".") } -> null
                pictureEnding(parts.last()) == null || file.bytes !in 1..StickerLimits.FILE_BYTES -> null
                else -> LibrarySticker(file.path, stickerName(parts.last()), if (parts.size == 2) parts[0] else "", "${file.bytes}:${file.modified}")
            }
        }.sortedWith(compareBy({ it.group.lowercase() }, { it.group }, { it.name.lowercase() }, { it.path }))
        .take(StickerLimits.LIBRARY)
        .groupBy { it.group }
        .map { StickerGroup(it.key, it.value) }

/** What a picture from the file called [fileName] is called: the name without its ending, as a page keeps it. */
fun stickerName(fileName: String): String = Picture("", fileName.substringBeforeLast('.')).fitted().name.ifEmpty { "sticker" }

/** What is in the library folder [dir], one folder deep. Not on the main thread. */
fun listLibrary(dir: File): List<LibraryFile> =
    dir.listFiles().orEmpty().flatMap { entry ->
        when {
            entry.isFile -> listOf(LibraryFile(entry.name, entry.length(), entry.lastModified()))
            entry.isDirectory -> entry.listFiles().orEmpty().filter { it.isFile }.map { LibraryFile("${entry.name}/${it.name}", it.length(), it.lastModified()) }
            else -> emptyList()
        }
    }

/** What the owner picked to stick on a page. */
sealed interface StickerChoice {
    data class BuiltIn(
        val kind: StickerKind,
    ) : StickerChoice

    /** The image [file] in the library, called [name]. */
    data class Pictured(
        val file: File,
        val name: String,
    ) : StickerChoice
}

/**
 * The launcher's copies of the pictures stuck on pages, in [dir]. [measure] gives an image's width and height
 * from its bytes, or null for what is no image. Nothing here runs on the main thread, and what makes or
 * deletes a copy runs one at a time ([AnnotationBook] sees to both).
 */
class StickerSnapshots(
    private val dir: File,
    private val measure: (ByteArray) -> Pair<Int, Int>?,
) {
    /** Where the copy called [name] is, whether or not it is still there. */
    fun file(name: String): File {
        require(isSnapshotName(name)) { "not a picture's copy: $name" }
        return File(dir, name)
    }

    /**
     * Copies the image file [from] here, unless a copy of the very same bytes is here already, and gives the
     * picture a sticker called [name] is of it. Null, and nothing copied, for a file that is gone, too big or
     * not an image. It never throws.
     */
    fun take(
        from: File,
        name: String,
    ): Picture? {
        return try {
            val ending = pictureEnding(from.name) ?: return null
            if (!from.isFile || from.length() !in 1..StickerLimits.FILE_BYTES) return null
            val bytes = from.readBytes()
            // It may have grown since it was looked at.
            if (bytes.isEmpty() || bytes.size > StickerLimits.FILE_BYTES) return null
            val (width, height) = measure(bytes) ?: return null
            if (width <= 0 || height <= 0) return null
            val copy = File(dir, snapshotName(bytes, ending))
            if (!copy.isFile || copy.length() != bytes.size.toLong()) writeAtomically(copy, bytes)
            Picture(copy.name, name, width / height.toFloat()).fitted()
        } catch (e: Throwable) {
            null
        }
    }

    /** Deletes the copies nothing in [used] speaks of, and says how many went. */
    fun sweep(used: Set<String>): Int = unusedSnapshots(dir.list().orEmpty().asList(), used).count { File(dir, it).delete() }
}
