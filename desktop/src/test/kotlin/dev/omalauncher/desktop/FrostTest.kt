package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.render.Backdrop
import dev.omalauncher.widgets.render.BackdropPicture
import dev.omalauncher.widgets.render.LocalBackdrop
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.runtime.CommandGate
import dev.omalauncher.widgets.runtime.WidgetInstance
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** `frost` blurs what the launcher draws behind a widget itself, as Soma's looks draw their own page. */
@OptIn(ExperimentalTestApi::class)
class FrostTest {
    @Test fun aFrostedNodeShowsThePageBehindIt() = runComposeUiTest {
        val inst =
            WidgetInstance(
                Compiler.compile("widget \"w\" {\n size: 2x1\n meta { owl: 10 }\n Column { frost: 8; fill: width; weight: 1; Text { text: \"\" } }\n}"),
                DesktopWidgetServices.gated(CommandGate(Files.createTempDirectory("gate").toFile().resolve("a.json"), "t"), "w.owl", get = { _, _, _ -> ShellResult("", "", -1) }),
                onError = {},
            )
        // A page red on its left 150 pixels and blue past them.
        val page = BackdropPicture("red-blue") {
            drawRect(Color.Blue)
            drawRect(Color.Red, Offset.Zero, Size(150f, size.height))
        }
        setContent {
            CompositionLocalProvider(LocalBackdrop provides Backdrop(picture = { page })) {
                Box(Modifier.size(400.dp, 100.dp).testTag("w")) {
                    LaunchedEffect(inst) { inst.start(this) { true } }
                    WidgetContent(inst, Modifier.fillMaxSize(), cells = 2 to 1)
                }
            }
        }
        fun row(): List<Color> {
            val map = onNodeWithTag("w").captureToImage().toPixelMap()
            return (0 until map.width).map { map[it, map.height / 2] }
        }
        waitUntil(timeoutMillis = 5_000) {
            Thread.sleep(20)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            row().let { it[it.size / 10] }.alpha > 0.9f
        }
        // The frosted node, without the widget's own inset around it.
        val r = row().dropWhile { it.alpha < 0.9f }.dropLastWhile { it.alpha < 0.9f }
        val inset = row().indexOfFirst { it.alpha >= 0.9f }
        val left = r[10]
        val right = r[r.size - 10]
        assertTrue("left $left", left.red > 0.8f && left.blue < 0.2f)
        assertTrue("right $right", right.blue > 0.8f && right.red < 0.2f)
        // Where red turns to blue, at the page's 150th pixel, blurred: some of each, not a sharp edge.
        val turn = inset + r.indexOfFirst { it.blue > it.red }
        assertTrue("turns at $turn", turn in 130..170)
        val mixed = r.count { it.red in 0.15f..0.85f }
        assertTrue("mixed $mixed", mixed >= 6)
    }
}
