package dev.omalauncher.messages.look

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.omalauncher.theme.pack.FONT_WEIGHTS
import dev.omalauncher.theme.pack.PackEntry
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PackSchema
import dev.omalauncher.theme.pack.PackSource
import dev.omalauncher.theme.pack.fontHasVariations
import dev.omalauncher.theme.pack.readCapped
import dev.omalauncher.ui.ActivePack
import java.io.File
import java.io.InputStream

/**
 * Draws Soma Messages in a Soma look, from the same pack files the launcher ships (copied in at build time).
 * Manga for now, Boss's look; following the launcher's own pick comes with the launcher link.
 */
object Look {
    private const val TAG = "SomaMessagesLook"
    const val DEFAULT = "manga"
    private const val MAX_FONT = 8L * 1024 * 1024

    fun use(
        context: Context,
        id: String = DEFAULT,
    ) {
        val source = AssetSource(context.assets, "packs/$id")
        val load = PackLoader.load(source, id)
        if (load.error != null) {
            Log.w(TAG, "look $id: ${load.error}")
            return
        }
        ActivePack.choose(PackPick(id, load))
        val type = load.pack.type
        ActivePack.fonts =
            listOf(type.display, type.body, type.hand, type.mono).map { it.file }.filter { it.isNotEmpty() }.distinct()
                .mapNotNull { path -> font(context, source, id, path)?.let { path to it } }
                .toMap()
    }

    /** As the launcher reads a pack's font: copied out to a file, every weight drawn from a variable font's own outlines. */
    private fun font(
        context: Context,
        source: PackSource,
        id: String,
        path: String,
    ): FontFamily? =
        runCatching {
            val bytes = readCapped(source, path, MAX_FONT) ?: return null
            val file = File(File(context.cacheDir, "pack-fonts/$id").apply { mkdirs() }, path.replace('/', '_'))
            file.writeBytes(bytes)
            if (fontHasVariations(bytes)) {
                FontFamily(FONT_WEIGHTS.map { w -> Font(file, FontWeight(w), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(w))) })
            } else {
                FontFamily(Typeface.createFromFile(file))
            }
        }.onFailure { Log.w(TAG, "look $id font $path", it) }.getOrNull()

    /** A pack in this app's assets, as the launcher's AssetPackSource reads its own. */
    private class AssetSource(
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
}
