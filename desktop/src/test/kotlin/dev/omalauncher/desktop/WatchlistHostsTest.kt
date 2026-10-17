package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.OwlHost
import dev.omalauncher.widgets.lang.hostRefusals
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One renderer on every screen (Soma Canvas slice 7a): the widget a Fold draws is drawn by the same shared code in Soma
 * desktop's window and by the headless service that draws the car's canvas, and a node a screen can't draw is refused
 * before it is placed and never faked.
 */
/** watchlist.owl in Soma desktop's window and on the headless service, pixel for pixel (a class of its own: one Compose scene per JVM). */
@OptIn(ExperimentalTestApi::class)
class WatchlistHostsTest {
    private val widgets = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/widgets") }.first { it.isDirectory }

    /** Yahoo's chart answer for one ticker, as the widget reads it: the price now and at the last close. */
    private fun chart(symbol: String, price: Double, close: Double) =
        """{"chart":{"result":[{"meta":{"symbol":"$symbol","regularMarketPrice":$price,"chartPreviousClose":$close}}],"error":null}}"""

    private val quotes = mapOf(
        "AAPL" to chart("AAPL", 231.5, 228.66),
        "NVDA" to chart("NVDA", 141.02, 144.64),
        "TSLA" to chart("TSLA", 252.1, 252.1),
        "BTC-USD" to chart("BTC-USD", 67000.0, 64859.63),
    )

    private val errors = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun watchlist(asked: MutableList<String> = ArrayList()): WidgetInstance {
        val program = Compiler.compile(File(widgets, "examples/86-watchlist.owl").readText())
        val services = testServices { url, _, _ -> asked += url; ShellResult(quotes.getValue(url.substringAfter("/chart/").substringBefore("?")), "", 200) }
        return WidgetInstance(program, services, onError = { errors += it })
    }

    @Test fun watchlistDrawsTheSameInTheWindowAndOnTheHeadlessService() = runComposeUiTest {
        Looks.use("city-pop")
        val asked = ArrayList<String>()
        val inst = watchlist(asked)
        // Soma desktop's window: the widget in a 300x200 block.
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(300.dp, 200.dp).testTag("tile")) {
                    // Run where it is drawn, as the phone runs widgets on its main thread.
                    androidx.compose.runtime.LaunchedEffect(inst) { inst.start(this) { true } }
                    WidgetContent(inst, Modifier.fillMaxSize(), cells = 3 to 2)
                }
            }
        }
        // The source answers off the main thread; the frame after it shows what it said.
        val until = System.currentTimeMillis() + 5_000
        while (onAllNodesWithText("+3.30%").fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            // Written off the main thread, as on the phone: told to the composition here, as the app's frame loop does there.
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(100)
        }
        assertEquals(emptyList<String>(), errors)
        onNodeWithText("-2.50%").assertExists()
        onNodeWithText("+1.24%").assertExists()
        // Every column has room: the price is as wide as its digits.
        assertTrue(onNodeWithText("141.02").fetchSemanticsNode().boundsInRoot.width > 30f)
        val window = onNodeWithTag("tile").captureToImage()
        // The service: the same widget drawn off-screen with nobody's window, as the car's frames are.
        val scene = ImageComposeScene(300, 200, Density(1f)) { WidgetContent(inst, Modifier.fillMaxSize(), cells = 3 to 2) }
        scene.render(0)
        val service = scene.render(1_000_000_000L).toComposeImageBitmap()
        scene.close()
        assertEquals(listOf("AAPL", "NVDA", "TSLA", "BTC-USD").map { "https://query1.finance.yahoo.com/v8/finance/chart/$it?range=1d&interval=1d" }.toSet(), asked.toSet())
        assertSamePixels(window, service)
        // And it is City Pop's: its green (a rise) and its red (a fall) are in the picture, not a default look's.
        for (c in listOf(dev.omalauncher.ui.Palette.green, dev.omalauncher.ui.Palette.red)) assertTrue("no ${Integer.toHexString(c.toArgb())} in the picture", hasColor(service, c))
        File("build/reports/widget-hosts").apply { mkdirs() }.let { dir ->
            javax.imageio.ImageIO.write(toAwt(service), "png", File(dir, "watchlist-service.png"))
            javax.imageio.ImageIO.write(toAwt(window), "png", File(dir, "watchlist-window.png"))
        }
    }

    private fun hasColor(img: ImageBitmap, c: androidx.compose.ui.graphics.Color): Boolean {
        val px = img.toPixelMap()
        fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.06f
        for (y in 0 until img.height) for (x in 0 until img.width) px[x, y].let { if (near(it.red, c.red) && near(it.green, c.green) && near(it.blue, c.blue)) return true }
        return false
    }

    @Test fun theLiveSourceAnswersWithoutAKey() {
        org.junit.Assume.assumeTrue("set SOMA_LIVE to fetch the real feed once", System.getenv("SOMA_LIVE") != null)
        val r = DesktopWidgetServices.httpGet("https://query1.finance.yahoo.com/v8/finance/chart/AAPL?range=1d&interval=1d", emptyMap(), 8)
        assertEquals(r.err, 200, r.code)
        assertTrue(r.out.contains("\"regularMarketPrice\""))
    }

    private fun assertSamePixels(a: ImageBitmap, b: ImageBitmap) {
        assertEquals(a.width to a.height, b.width to b.height)
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var differ = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (pa[x, y] != pb[x, y]) differ++
        assertEquals("pixels that differ between the window and the service", 0L, differ.toLong())
    }

    private fun toAwt(img: ImageBitmap): java.awt.image.BufferedImage {
        val px = img.toPixelMap()
        return java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until img.height) for (x in 0 until img.width) setRGB(x, y, px[x, y].toArgb())
        }
    }

    /** HTTP from [get]; every command waits for an OK that never comes in these tests, so none runs. */
    private fun testServices(get: (String, Map<String, String>, Int) -> ShellResult) =
        DesktopWidgetServices.gated(dev.omalauncher.widgets.runtime.CommandGate(java.nio.file.Files.createTempDirectory("gate").toFile().resolve("approved.json"), "test"), "test.owl", get = get)
}
