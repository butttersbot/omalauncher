package dev.omalauncher.linux

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.UUID

const val MAX_PROMPT_IMAGE_BYTES = 20L * 1024 * 1024

/** Copies only the selected images into the guest, never giving agents access to the photo library. */
fun importPromptImage(resolver: ContentResolver, uri: Uri, proot: Proot): PromptImage {
    val extension = when (resolver.getType(uri)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> error("Choose a JPEG, PNG, WebP or GIF image.")
    }
    val dir = proot.promptAttachments
    check(dir.mkdirs() || dir.isDirectory) { "Could not create the attachments folder." }
    // Generated names avoid path traversal and shell syntax from user-supplied display names.
    val file = File(dir, "${UUID.randomUUID()}.$extension")
    try {
        resolver.openInputStream(uri)?.use { copyPromptImage(it, file) }
            ?: error("Could not read the selected image.")
        return PromptImage(file, requireNotNull(proot.guestPath(file)))
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}

/** Enforce the size limit while streaming, even when a provider doesn't report a size. */
internal fun copyPromptImage(input: InputStream, file: File, limit: Long = MAX_PROMPT_IMAGE_BYTES) {
    try {
        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Each image must be 20 MB or smaller." }
                output.write(buffer, 0, count)
            }
            require(total > 0) { "The selected image is empty." }
        }
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}
