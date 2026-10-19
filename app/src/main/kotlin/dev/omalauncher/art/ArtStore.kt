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
    private val ownAuthority: String?,
    /** A second look at a copied file: whether the platform can read it as an image. Tests accept the magic alone. */
    private val decodes: (File) -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "art"),
        context.contentResolver,
        context.packageName,
        decodes = ::boundsDecode,
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
        if (ownAuthority != null && uri.authority?.startsWith(ownAuthority) == true) throw Refused("That can't be read.")
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
            if (!decodes(temp)) throw Refused("That picture can't be opened.")
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

        /** Whether Android reads the file's size as an image's, without decoding its pixels. */
        private fun boundsDecode(file: File): Boolean {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            return options.outWidth > 0 && options.outHeight > 0
        }
    }
}
