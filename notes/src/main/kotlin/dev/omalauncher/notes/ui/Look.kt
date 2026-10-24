package dev.omalauncher.notes.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * The Manga look, Boss's (app/src/main/assets/packs/manga/pack.toml): paper and ink, a red for what matters, and its
 * four faces, copied in from the launcher's pack at build time.
 */
object Look {
    val paper = Color(0xFFFDFDFB)
    val ink = Color(0xFF111113)
    val muted = Color(0xFF77777E)
    val red = Color(0xFFD7262D)
    val tone = Color(0xFFE9E9EA)
    val highlight = Color(0xFFFFE680)

    var display: FontFamily = FontFamily.SansSerif
        private set
    var body: FontFamily = FontFamily.SansSerif
        private set
    var hand: FontFamily = FontFamily.Cursive
        private set
    var mono: FontFamily = FontFamily.Monospace
        private set

    fun use(context: Context) {
        fun file(name: String): File? =
            runCatching {
                val out = File(File(context.filesDir, "fonts").apply { mkdirs() }, name)
                if (!out.exists() || out.length() == 0L) context.assets.open("fonts/$name").use { i -> out.outputStream().use { i.copyTo(it) } }
                out
            }.getOrNull()
        file("DelaGothicOne-Regular.ttf")?.let { display = FontFamily(Font(it)) }
        file("PatrickHand-Regular.ttf")?.let { hand = FontFamily(Font(it)) }
        file("SchibstedGrotesk.ttf")?.let { f ->
            body =
                FontFamily(
                    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
                        Font(f, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
                    },
                )
        }
        file("JetBrainsMono.ttf")?.let { f -> mono = FontFamily(Font(f, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400)))) }
    }
}
