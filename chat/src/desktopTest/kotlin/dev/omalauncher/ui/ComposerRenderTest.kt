package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.readCapped
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The composer drawn off-screen in a real look, one row per stage, kept in build/render for a person to look at.
 * Off-screen scenes and UI tests trip over each other in one JVM, so the taps are in [ComposerTapTest].
 */
class ComposerRenderTest {
    /** Draws from the launcher's own copy of [id], with its typefaces, as the desktop app does. */
    private fun use(id: String) {
        val dir = File("../app/src/main/assets/packs/$id")
        val load = PackLoader.load(DirectoryPackSource(dir), id)
        assertNull(load.error)
        ActivePack.choose(PackPick(id, load))
        val type = load.pack.type
        val source = DirectoryPackSource(dir)
        ActivePack.fonts =
            listOf(type.display, type.body, type.hand, type.mono).map { it.file }.filter { it.isNotEmpty() }.distinct()
                .mapNotNull { path -> readCapped(source, path, 8L * 1024 * 1024)?.let { path to FontFamily(Font("$id/$path", it)) } }
                .toMap()
    }

    private fun render(
        id: String,
        name: String,
    ): ByteArray {
        use(id)
        val mic = FakeRecorder()
        val typed = ComposerState("Yes! 7:30 at Kiriko. I got us the counter")
        val recording = ComposerState().apply {
            startVoice(mic, now = 0, held = false)
            listOf(0.2f, 0.5f, 0.9f, 0.4f, 0.7f, 0.3f, 0.8f, 0.6f, 0.25f, 0.55f).forEach(::hear)
        }
        val review = ComposerState().apply {
            startVoice(mic, now = 0, held = false)
            repeat(40) { hear(((it * 37) % 10) / 10f) }
            stopVoice(mic, now = 7_000)
        }
        val png =
            ImageComposeScene(width = 950, height = 520, density = Density(2f)) {
                Column(Modifier.background(Pack.background).width(475.dp)) {
                    SomaComposer(ComposerState(), onSend = {}, placeholder = "iMessage", sendColor = Pack.foreground, voice = mic, onAttach = {})
                    SomaComposer(typed, onSend = {}, placeholder = "iMessage", sendColor = Pack.foreground, voice = mic, onAttach = {})
                    SomaComposer(recording, onSend = {}, voice = mic, onAttach = {}, now = { 7_000 })
                    SomaComposer(review, onSend = {}, sendColor = Pack.foreground, voice = mic, onAttach = {})
                }
            }.use { scene ->
                scene.render()
                scene.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            }
        val out = File(System.getProperty("render.dir") ?: "build/render").apply { mkdirs() }
        File(out, name).writeBytes(png)
        return png
    }

    private fun colours(png: ByteArray): Int {
        val image = org.jetbrains.skia.Image.makeFromEncoded(png)
        val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val seen = HashSet<Int>()
        for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) seen += pixels.getColor(x, y)
        return seen.size
    }

    @Test fun theComposerDrawsInTheMangaLook() {
        val png = render("manga", "composer-manga.png")
        // A blank or crashed scene is one colour; drawn composers have ink, paper, the red recording and antialiasing.
        assertTrue(colours(png) > 8)
    }

    @Test fun theComposerDrawsInCityPop() {
        assertTrue(colours(render("city-pop", "composer-city-pop.png")) > 8)
    }
}
