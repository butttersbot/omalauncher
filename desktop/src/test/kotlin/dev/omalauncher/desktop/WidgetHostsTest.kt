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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One renderer on every screen (Soma Canvas slice 7a): the widget a Fold draws is drawn by the same shared code in Soma
 * desktop's window and by the headless service that draws the car's canvas, and a node a screen can't draw is refused
 * before it is placed and never faked.
 */
@OptIn(ExperimentalTestApi::class)
class WidgetHostsTest {
    private val widgets = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/widgets") }.first { it.isDirectory }

    /** Yahoo's answer for the four tickers, as the widget reads it. */
    private val quotes = """{"quoteResponse":{"result":[
        {"symbol":"AAPL","regularMarketPrice":231.5,"regularMarketChangePercent":1.24},
        {"symbol":"NVDA","regularMarketPrice":141.02,"regularMarketChangePercent":-2.5},
        {"symbol":"TSLA","regularMarketPrice":252.1,"regularMarketChangePercent":0.0},
        {"symbol":"BTC-USD","regularMarketPrice":67000.0,"regularMarketChangePercent":3.3}]}}"""

    private val errors = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun watchlist(asked: MutableList<String> = ArrayList()): WidgetInstance {
        val program = Compiler.compile(File(widgets, "examples/86-watchlist.owl").readText())
        val services = testServices { url, _, _ -> asked += url; ShellResult(quotes, "", 200) }
        return WidgetInstance(program, services, onError = { errors += it })
    }

    @Test fun aWebWidgetIsRefusedHereAndOneThatArrivesAnywaySaysSoInItsPlace() = runComposeUiTest {
        val web = Compiler.compile("widget \"chart\" {\n  size: 3x2\n  meta { owl: 6 }\n  Web { src: \"https://s.tradingview.com/widgetembed/\" }\n}")
        assertTrue(hostRefusals(web, OwlHost.Desktop).isNotEmpty())
        assertTrue(hostRefusals(web, OwlHost.Service).isNotEmpty())
        val inst = WidgetInstance(web, testServices { _, _, _ -> error("no request") }, onError = {})
        setContent { Box(Modifier.size(300.dp, 200.dp)) { WidgetContent(inst, Modifier.fillMaxSize()) } }
        onNodeWithText("Web doesn't draw on desktop").assertExists()
    }

    @Test fun noGuestPathReachesThisComputersFiles() {
        val real = java.nio.file.Files.createTempFile("host", ".png").toFile()
        val services = testServices { _, _, _ -> error("no request") }
        // Round-1 review: a rooted path ("//…") once resolved to the host's own file.
        for (path in listOf(real.path, "/" + real.path, "//" + real.path, "~/x.png", "../../" + real.path, "")) assertFalse(path, services.guestFile(path).exists())
    }

        @Test fun everyShippedWidgetThatTheDesktopCanDrawDrawsWithoutAnError() = runComposeUiTest {
        // Animated widgets ask for frames forever: the clock is moved by hand.
        mainClock.autoAdvance = false
        val files = widgets.walk().filter { it.extension == "owl" }.sortedBy { it.name }.toList()
        val drawable = files.map { it to Compiler.compile(it.readText()) }.filter { (_, p) -> hostRefusals(p, OwlHost.Desktop).isEmpty() }
        assertTrue(drawable.size > 15)
        val errors = ArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val insts = drawable.map { (f, p) -> WidgetInstance(p, testServices { _, _, _ -> ShellResult("", "offline in the test", -1) }, onError = { errors += "${f.name}: $it" }).also { it.start(scope) { true } } }
        setContent {
            androidx.compose.foundation.layout.Column {
                for (inst in insts) Box(Modifier.size(240.dp, 120.dp)) { WidgetContent(inst, Modifier.fillMaxSize(), cells = 3 to 2) }
            }
        }
        repeat(20) {
            Thread.sleep(25)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(100)
        }
        scope.cancel()
        // What a widget says when its data can't be had is its own; a crash, or an error from drawing, is not.
        assertEquals(emptyList<String>(), errors.filter { "offline in the test" !in it && "waiting for your OK" !in it && "request failed" !in it })
    }

    /** HTTP from [get]; every command waits for an OK that never comes in these tests, so none runs. */
    private fun testServices(get: (String, Map<String, String>, Int) -> ShellResult) =
        DesktopWidgetServices.gated(dev.omalauncher.widgets.runtime.CommandGate(java.nio.file.Files.createTempDirectory("gate").toFile().resolve("approved.json"), "test"), "test.owl", get = get)
}
