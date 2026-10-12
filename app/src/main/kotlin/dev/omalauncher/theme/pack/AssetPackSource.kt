package dev.omalauncher.theme.pack

import android.content.res.AssetManager
import java.io.InputStream

/** A pack shipped in the app's assets under [dir], like `packs/console`. */
class AssetPackSource(
    private val assets: AssetManager,
    private val dir: String,
) : PackSource {
    override fun stat(path: String): PackEntry {
        if (PackSchema.pathProblem(path) != null) return PackEntry.Outside
        // Assets are compressed, so the only way to a size is to read to the end.
        return try {
            assets.open("$dir/$path").use { input ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                }
                PackEntry.File(size)
            }
        } catch (e: Exception) {
            PackEntry.Missing
        }
    }

    override fun open(path: String): InputStream? {
        if (PackSchema.pathProblem(path) != null) return null
        return try {
            assets.open("$dir/$path")
        } catch (e: Exception) {
            null
        }
    }
}
