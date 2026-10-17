package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.canvas.ModuleAsk
import dev.omalauncher.canvas.ModuleSize
import dev.omalauncher.canvas.NeedsYouModule
import dev.omalauncher.canvas.ThreadsModule
import dev.omalauncher.canvas.moduleSize
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Threads and Needs you modules at every size, in City Pop, the pack Boss uses (Soma Canvas slice 3a). */
class ModulesRenderTest {
    private val threads = listOf(
        AgentThread("mac:mac/t1", "cockpit canvas", ThreadState.Working, "Building the modules", "working", machine = "MacBook"),
        AgentThread("mac:mac/t2", "notch trading", ThreadState.NeedsOk, "Wants to run swift build", "needs your OK", machine = "Mac Pro"),
        AgentThread("mac:mg1/t3", "MG1 runner", ThreadState.Attention, "Which port should it use?", "needs attention", machine = "MG1"),
        AgentThread("mac:mac/t4", "card pass", ThreadState.Idle, "Seeded 13 forms", "done", machine = "Mac Pro"),
    )
    private val asks = listOf(
        ModuleAsk("mac:1", "Allow Bash on notch trading?", "swift build -c release", "mac:mac/t2", "notch trading", Unit),
        ModuleAsk("phone:2", "Allow Bash for Builder A?", "oma-install --status", "phone", "Builder A", Unit),
    )

    /** Console's wider type at 2×2: approve and deny stay whole or give way to "open"; never a clipped button. */
    @Test fun aTightCardInConsoleNeverClipsAnAnswer() {
        Looks.fonts()
        Looks.use("console")
        val png = ImageComposeScene(width = 2 * 76 * 2, height = 2 * 76 * 2, density = Density(2f)) {
            Box(Modifier.size(152.dp, 152.dp)) { NeedsYouModule(asks, ModuleSize.Card, onAnswer = { _, _, _ -> }, onOpen = {}) }
        }.use { it.render(); it.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes }
        File("build/render").mkdirs()
        File("build/render/module-needs-card-2x2-console.png").writeBytes(png)
        Looks.use("city-pop")
    }

    @Test fun eachSizeDrawsInCityPop() {
        Looks.fonts()
        Looks.use("city-pop")
        val out = File("build/render").apply { mkdirs() }
        // Cells at the Fold's real size (a 468 dp page over six columns: about 76 dp each, GridGeometry), every size, and the
        // narrow one-row shapes that must give way rather than wrap.
        val cell = 76
        for ((name, w, h) in listOf(Triple("dot", 1, 1), Triple("line", 6, 1), Triple("line", 2, 1), Triple("line", 4, 1), Triple("card", 3, 2), Triple("card", 2, 2), Triple("full", 6, 3), Triple("full", 6, 4))) {
            val size = moduleSize(w, h, 6)
            assertEquals("$w×$h", name, size.name.lowercase())
            for (which in listOf("threads", "needs")) {
                val png = ImageComposeScene(width = w * cell * 2, height = h * cell * 2, density = Density(2f)) {
                    Box(Modifier.size((w * cell).dp, (h * cell).dp)) {
                        if (which == "threads") ThreadsModule(threads, size, onOpen = {}) else NeedsYouModule(asks, size, onAnswer = { _, _, _ -> }, onOpen = {})
                    }
                }.use { it.render(); it.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes }
                File(out, "module-$which-$name-${w}x$h.png").writeBytes(png)
                assertTrue(png.size > 1000)
            }
        }
    }
}
