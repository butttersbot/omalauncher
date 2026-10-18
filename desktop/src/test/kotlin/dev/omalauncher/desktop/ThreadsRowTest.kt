package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.canvas.ModuleSize
import dev.omalauncher.canvas.ThreadsModule
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** With one Mac no thread names its computer: the Threads card gives that column's room to the name and what it is doing (s12). */
@OptIn(ExperimentalTestApi::class)
class ThreadsRowTest {
    private fun thread(n: Int, name: String, doing: String, machine: String = "") =
        AgentThread("mac:mac/t$n", name, ThreadState.Idle, doing, "idle", machine = machine)

    private val oneMac = listOf(thread(1, "Tailshare for the night", "…plus ~/Projects/tailshare is ready"), thread(2, "Oma runner upkeep", "…with the phone's runner key"))
    private val twoMacs = listOf(thread(1, "Tailshare for the night", "…plus ~/Projects/tailshare is ready", "Mac Pro"), thread(2, "Oma runner upkeep", "…with the phone's runner key", "MacBook Pro"))

    /** The desktop's Threads card [w] wide (Boss's window: 3 columns ≈ 366 dp; a wider card ≈ 490). */
    private fun widths(threads: List<AgentThread>, w: Int = 490): Pair<Float, Float> {
        var out = 0f to 0f
        runComposeUiTest {
            Looks.fonts(); Looks.use("city-pop")
            setContent { Box(Modifier.size(w.dp, 410.dp)) { ThreadsModule(threads, ModuleSize.Card, onOpen = {}) } }
            val name = onNodeWithText(threads[0].name, useUnmergedTree = true).getBoundsInRoot()
            val doing = onNodeWithText(threads[0].doing, useUnmergedTree = true).getBoundsInRoot()
            out = (name.right - name.left).value to (doing.right - doing.left).value
        }
        return out
    }

    @Test fun withOneMacTheEmptyComputerColumnIsGivenToTheOthers() {
        val (name1, doing1) = widths(oneMac)
        val (name2, doing2) = widths(twoMacs)
        assertTrue("name $name1 vs $name2", name1 > name2 + 20)
        assertTrue("doing $doing1 vs $doing2", doing1 > doing2 + 20)
    }

    /** Round-1 review: with two Macs a share of a 3-column card cut the names again; there the computer's column gives way. */
    @Test fun onANarrowCardTheComputerColumnGivesWay() {
        assertEquals(widths(oneMac, 366), widths(twoMacs, 366))
    }

    @Test fun drawsAtTheDesktopSize() {
        Looks.fonts(); Looks.use("city-pop")
        File("build/render").mkdirs()
        for ((label, list) in listOf("one-mac" to oneMac, "two-macs" to twoMacs)) for (w in listOf(366, 490)) {
            val png = ImageComposeScene(width = w * 2, height = 820, density = Density(2f)) {
                Box(Modifier.size(w.dp, 410.dp)) { ThreadsModule(list, ModuleSize.Card, onOpen = {}) }
            }.use { it.render(); it.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes }
            File("build/render/threads-desktop-$label-$w.png").writeBytes(png)
        }
    }
}
