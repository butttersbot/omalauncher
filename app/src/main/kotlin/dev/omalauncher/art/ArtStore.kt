package dev.omalauncher.art

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/**
 * The pictures and GIFs that hang in manga panels (features/manga-look/CONTRACT.md, piece C): one file each in
 * `filesDir/art/<id>.<ext>`, where the id is 16 lowercase hex characters and the notebook keeps it as `art:<id>`.
 * Only real images come in (PNG, JPEG, GIF, WebP still or animated, HEIF and AVIF from the camera), at most [MAX_BYTES].
 * Nothing here knows about pages: a file stays until [remove] or a [sweep] that no longer finds it referenced.
 */
class ArtStore internal constructor(
    private val dir: File,
    private val resolver: ContentResolver?,
    /** Whether a content uri is served by Soma itself, which a sharing app must not get it to read. */
    private val ours: (Uri) -> Boolean,
    /**
     * A second look at a copied file of type [String] (its extension): the platform's width and height of it, or null
     * if it can't read it as an image. Tests have no platform: they give a size or none.
     */
    private val bounds: (File, String) -> Pair<Int, Int>? = { _, _ -> 1 to 1 },
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "art"),
        context.contentResolver,
        ours = { uri -> isOurs(context, uri) },
        bounds = { file, _ -> boundsDecode(file) },
    )

    /** Why something wasn't taken in, said so Boss can read it. */
    class Refused(
        message: String,
    ) : Exception(message)

    /**
     * Copies the image at [uri] in and returns its new id. Reads only `content:` uris of other apps (never Soma's own
     * files, which a sharing app could otherwise point at). Throws [Refused] for anything that isn't an image,
     * is over [MAX_BYTES] or can't be read. Blocks: call it off the main thread.
     */
    fun import(uri: Uri): String {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw Refused("That can't be read.")
        if (ours(uri)) throw Refused("That can't be read.")
        val r = resolver ?: throw Refused("That can't be read.")
        return import { r.openInputStream(uri) }
    }

    /** As [import] with a uri, from whatever [open] opens; null or a failure to open is unreadable. */
    fun import(open: () -> InputStream?): String {
        dir.mkdirs()
        clearStaleTemps()
        val temp = File(dir, "$TEMP_PREFIX${hex(8)}")
        try {
            val stream =
                try {
                    open()
                } catch (e: Exception) {
                    null
                } ?: throw Refused("That can't be read.")
            val size =
                try {
                    stream.use { input -> temp.outputStream().use { out -> copyCapped(input, out) } }
                } catch (e: Refused) {
                    throw e
                } catch (e: Exception) {
                    throw Refused("That can't be read.")
                }
            if (size == 0L) throw Refused("That's empty.")
            val ext = sniff(head(temp)) ?: throw Refused("That isn't a picture or a GIF.")
            val (w, h) = bounds(temp, ext) ?: throw Refused("That picture can't be opened.")
            if (!fits(ext, w, h)) throw Refused("That picture is too big to hang.")
            while (true) {
                val id = hex(ID_LENGTH / 2)
                if (file(id) != null) continue
                val target = File(dir, "$id.$ext")
                if (temp.renameTo(target)) return id
                throw Refused("It couldn't be saved.")
            }
        } finally {
            temp.delete()
        }
    }

    /** The file of art [id], or null if there is none or the id isn't one. */
    fun file(id: String): File? {
        if (!isId(id)) return null
        return EXTENSIONS.asSequence().map { File(dir, "$id.$it") }.firstOrNull { it.isFile }
    }

    /** Deletes art [id]; false if there was none. */
    fun remove(id: String): Boolean = file(id)?.delete() == true

    /** Every art id in the store. */
    fun ids(): Set<String> = artFiles().map { it.nameWithoutExtension }.toSet()

    /**
     * Deletes the art no one refers to: every file whose id is not in [referenced], except files younger than
     * [GRACE_MILLIS], which were just imported and are about to be placed. Returns the ids it deleted.
     * Leaves everything that isn't an art file alone.
     */
    fun sweep(referenced: Set<String>): Set<String> {
        val cutoff = now() - GRACE_MILLIS
        clearStaleTemps()
        return artFiles()
            .filter { it.nameWithoutExtension !in referenced && it.lastModified() < cutoff && it.delete() }
            .map { it.nameWithoutExtension }
            .toSet()
    }

    private fun artFiles(): List<File> = dir.listFiles().orEmpty().filter { it.isFile && FILE_NAME.matches(it.name) }

    /** Copies at most [MAX_BYTES], refusing anything longer; returns how much was copied. */
    private fun copyCapped(
        input: InputStream,
        out: java.io.OutputStream,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > MAX_BYTES) throw Refused("That's over 20 MB.")
            out.write(buffer, 0, n)
        }
    }

    /** The first [HEAD] bytes of [file], or fewer if it is shorter. */
    private fun head(file: File): ByteArray =
        file.inputStream().use { input ->
            val bytes = ByteArray(HEAD)
            var got = 0
            while (got < HEAD) {
                val n = input.read(bytes, got, HEAD - got)
                if (n < 0) break
                got += n
            }
            bytes.copyOf(got)
        }

    /** Leftovers of imports that died halfway (an hour old: one in progress is never that slow). */
    private fun clearStaleTemps() {
        val cutoff = now() - GRACE_MILLIS
        dir.listFiles().orEmpty().filter { it.name.startsWith(TEMP_PREFIX) && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val ID_LENGTH = 16

        /** How long a freshly imported file is safe from [sweep]. */
        const val GRACE_MILLIS = 60L * 60 * 1000

        val EXTENSIONS = listOf("png", "jpg", "gif", "webp", "heic", "avif")
        private const val TEMP_PREFIX = ".import-"
        private const val HEAD = 32
        private val ID = Regex("[0-9a-f]{$ID_LENGTH}")
        private val FILE_NAME = Regex("[0-9a-f]{$ID_LENGTH}\\.(${EXTENSIONS.joinToString("|")})")
        private val random = SecureRandom()

        fun isId(id: String): Boolean = ID.matches(id)

        /** The extension the first bytes of a file say it is, or null if they aren't an image this store takes. */
        fun sniff(head: ByteArray): String? {
            fun at(
                offset: Int,
                vararg bytes: Int,
            ) = head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }

            fun ascii(
                offset: Int,
                text: String,
            ) = at(offset, *text.map { it.code }.toIntArray())
            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
                at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
                ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "gif"
                ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
                ascii(4, "ftyp") && head.size >= 12 -> {
                    when (String(head, 8, 4, Charsets.US_ASCII)) {
                        "avif", "avis" -> "avif"
                        "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" -> "heic"
                        else -> null
                    }
                }
                else -> null
            }
        }

        private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /** The most pixels a still may have: it is decoded scaled down, so only absurd sizes are refused. */
        const val MAX_STILL_PIXELS = 200L * 1000 * 1000

        /** The most pixels a frame of a GIF or WebP may have: an animation's frames are decoded at full size. */
        const val MAX_FRAME_PIXELS = 16L * 1000 * 1000

        /** Whether a [w]×[h] picture of type [ext] can be drawn without running the launcher out of memory. */
        fun fits(
            ext: String,
            w: Int,
            h: Int,
        ): Boolean {
            if (w <= 0 || h <= 0) return false
            val pixels = w.toLong() * h
            return pixels <= if (ext == "gif" || ext == "webp") MAX_FRAME_PIXELS else MAX_STILL_PIXELS
        }

        /** Android's width and height of the file as an image, without decoding its pixels; null if it can't read it. */
        private fun boundsDecode(file: File): Pair<Int, Int>? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
        }

        /**
         * Whether [uri] is served by this app: its own authorities (after a `user@` prefix, which Android ignores), or
         * any authority Android says belongs to this package.
         */
        @Suppress("DEPRECATION")
        private fun isOurs(
            context: Context,
            uri: Uri,
        ): Boolean {
            val authority = uri.authority?.substringAfterLast('@') ?: return true
            val own = context.packageName
            if (authority.startsWith(own)) return true
            return runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName == own }.getOrDefault(true)
        }
    }
}
