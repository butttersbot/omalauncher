package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.runtime.CommandGate
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Web images, sounds and fonts on Soma desktop: downloaded and kept as on the phone, and a variable font in its real weights. */
@OptIn(ExperimentalTestApi::class)
class WidgetWebFilesTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/assets").isDirectory }

    /** A variable font with a weight axis, as a widget might name one on the web. */
    private val grotesk = File(repo, "app/src/main/assets/packs/city-pop/fonts/SchibstedGrotesk.ttf").readBytes()

    private val files = Files.createTempDirectory("widget-files").toFile()

    private fun services(download: (String) -> ByteArray?) =
        DesktopWidgetServices.gated(
            CommandGate(Files.createTempDirectory("gate").toFile().resolve("approved.json"), "test"),
            "test.owl",
            get = { _, _, _ -> ShellResult("", "no request", -1) },
            files = files,
            download = download,
        )

    @Test fun aWebFileIsDownloadedOnceAndKept() = runBlocking {
        val asked = AtomicInteger()
        val s = services { asked.incrementAndGet(); Thread.sleep(50); byteArrayOf(1, 2, 3) }
        // Many at once for one URL: one download.
        val got = (1..8).map { async { s.remoteFile("https://img.test/a.png") } }.awaitAll()
        assertEquals(1, asked.get())
        assertTrue(got.all { it != null && it == got[0] })
        assertArrayEquals(byteArrayOf(1, 2, 3), got[0]!!.readBytes())
        assertEquals(got[0]!!.parentFile, files)
        // Asked again later: the kept copy, with no download.
        assertEquals(got[0], s.remoteFile("https://img.test/a.png"))
        assertEquals(1, asked.get())
    }

    @Test fun onlyHttpsIsDownloaded() = runBlocking {
        val asked = AtomicInteger()
        val s = services { asked.incrementAndGet(); byteArrayOf(1) }
        assertNull(s.remoteFile("http://img.test/a.png"))
        assertNull(s.remoteFile("file:///etc/hosts"))
        assertEquals(0, asked.get())
        assertNull(DesktopWidgetServices.downloadFile("http://img.test/a.png"))
    }

    @Test fun aFailedDownloadIsNotAskedAgainAtOnceAndKeepsTheOldCopy() = runBlocking {
        val asked = AtomicInteger()
        var answer: ByteArray? = null
        val s = services { asked.incrementAndGet(); answer }
        assertNull(s.remoteFile("https://img.test/b.png"))
        assertNull(s.remoteFile("https://img.test/b.png"))
        assertEquals(1, asked.get())
        // A copy kept a day and more ago, then the web is gone: the old copy still shows.
        answer = byteArrayOf(7)
        val s2 = services { asked.incrementAndGet(); answer }
        val kept = s2.remoteFile("https://img.test/c.png")!!
        kept.setLastModified(System.currentTimeMillis() - 25 * 3600_000L)
        answer = null
        assertEquals(kept, s2.remoteFile("https://img.test/c.png"))
        assertArrayEquals(byteArrayOf(7), kept.readBytes())
    }

    /** How many of [tag]'s pixels are dark: the ink its text takes. */
    private fun androidx.compose.ui.test.ComposeUiTest.ink(tag: String): Int {
        val map = onNodeWithTag(tag).captureToImage().toPixelMap()
        var n = 0
        for (x in 0 until map.width) for (y in 0 until map.height) if (map[x, y].red < 0.5f) n++
        return n
    }

    @Test fun aWebFontDrawsInItsRealWeights() = runComposeUiTest {
        val asked = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        fun widget(weight: Int) =
            WidgetInstance(
                Compiler.compile(
                    "widget \"w\" {\n size: 2x1\n meta { owl: 10 }\n" +
                        " Text { text: \"Wordmark\"; font: \"https://fonts.test/Grotesk.ttf\"; fontWeight: $weight; fontSize: 28; color: \"#000000\" }\n}",
                ),
                services { asked += it; grotesk },
                onError = { errors += it },
            )
        val light = widget(200)
        val heavy = widget(900)
        // Started before they're first drawn, as home starts its widgets, so the first draw asks for the font.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        light.start(scope) { true }
        heavy.start(scope) { true }
        setContent {
            androidx.compose.foundation.layout.Column(Modifier.background(androidx.compose.ui.graphics.Color.White)) {
                for ((tag, inst) in listOf("light" to light, "heavy" to heavy)) {
                    Box(Modifier.size(300.dp, 60.dp).background(androidx.compose.ui.graphics.Color.White).testTag(tag)) {
                        WidgetContent(inst, Modifier.fillMaxSize(), cells = 2 to 1)
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            Thread.sleep(20)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            // Both widgets keep it in one folder: the first downloads it, the second finds it kept.
            asked.isNotEmpty()
        }
        repeat(20) {
            Thread.sleep(25)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            waitForIdle()
        }
        assertEquals(emptyList<String>(), errors.toList())
        assertEquals(listOf("https://fonts.test/Grotesk.ttf"), asked.toList())
        val l = ink("light")
        val h = ink("heavy")
        // The same word, its heaviest weight against its lightest: far more ink, not one instance named twice.
        scope.cancel()
        assertTrue("light $l, heavy $h", h > l * 1.6)
    }

}
