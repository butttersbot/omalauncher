package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.canvas.chatHostModules
import dev.omalauncher.ui.DesktopBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The canvas never takes what is the chat's (round-1 Critic, slice 5), and keeps working when it can't be kept. */
@OptIn(ExperimentalTestApi::class)
class CanvasScreenTest {
    private val home = Files.createTempDirectory("soma-canvas-ui").toFile().also { System.setProperty("soma.home", it.path) }

    @Test fun aRightClickInTheChatIsTheChatsAndEscapeClosesTheMenuFirst() = runComposeUiTest {
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        val canvas = DesktopCanvas(SomaHome.canvas, "test").also { it.load() }
        var width by mutableStateOf(1000.dp)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(width, 760.dp)) { CanvasScreen(canvas, host, chatHostModules(host), 0L, null, onTab = {}) } }
        mainClock.advanceTimeBy(600)
        // Over a module the right-click is the canvas's; Escape closes its menu and is taken, so the window isn't hidden.
        onNodeWithContentDescription("Threads module").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("arrange").assertExists()
        assertTrue(DesktopBack.press())
        mainClock.advanceTimeBy(300)
        onNodeWithText("arrange").assertDoesNotExist()
        // In the chat's field it is the chat's: no canvas menu over it.
        onNode(hasSetTextAction()).performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("arrange").assertDoesNotExist()
        onNodeWithText("remove threads").assertDoesNotExist()
        // Nor on its header or its margins, where the chat itself takes no pointer (round-2 Critic: those fell through to the page).
        for (at in listOf(Offset(3f, 3f), Offset(3f, 400f), Offset(200f, 27f))) {
            onNodeWithContentDescription("Chat block").performMouseInput { rightClick(at) }
            mainClock.advanceTimeBy(300)
            onNodeWithText("arrange").assertDoesNotExist()
        }
        // What he was typing stays when the window narrows to the chat alone and widens again.
        onNode(hasSetTextAction()).performTextInput("a draft he was typing")
        mainClock.advanceTimeBy(300)
        width = 600.dp
        mainClock.advanceTimeBy(300)
        onNodeWithContentDescription("Threads module").assertDoesNotExist()
        onNode(hasText("a draft he was typing", substring = true)).assertExists()
        width = 1000.dp
        mainClock.advanceTimeBy(300)
        onNode(hasText("a draft he was typing", substring = true)).assertExists()
        onNodeWithContentDescription("Threads module").assertExists()
    }

    @Test fun theArrangingBarCoversNeitherTheChatsSendNorAGrip() = runComposeUiTest {
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        val canvas = DesktopCanvas(SomaHome.canvas, "test").also { it.load() }
        mainClock.autoAdvance = false
        // The narrowest canvas there is, where round 2 found the bar over [ send ].
        setContent { Box(Modifier.size(800.dp, 600.dp)) { CanvasScreen(canvas, host, chatHostModules(host), 0L, null, onTab = {}) } }
        mainClock.advanceTimeBy(600)
        onNodeWithContentDescription("Threads module").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("arrange").performClick()
        mainClock.advanceTimeBy(600)
        val bar = onNodeWithText("[ done ]", substring = true).getBoundsInRoot()
        val send = onNodeWithText("[ send ]", substring = true).getBoundsInRoot()
        assertTrue("send $send, bar $bar", send.bottom <= bar.top)
        for (grip in onAllNodes(hasContentDescription("resize ", substring = true)).fetchSemanticsNodes()) {
            assertTrue("grip ${grip.boundsInRoot}, bar $bar", grip.boundsInRoot.bottom <= bar.top.value * density.density)
        }
    }

    @Test fun aDraggedBlockFollowsThePointerExactly() = runComposeUiTest {
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        val canvas = DesktopCanvas(SomaHome.canvas, "test").also { it.load() }
        // Room below Needs you to land in.
        canvas.apply(dev.omalauncher.widgets.layout.CanvasVerb.Remove("module:timeline"))
        canvas.apply(dev.omalauncher.widgets.layout.CanvasVerb.Remove("module:computers"))
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(1000.dp, 760.dp)) { CanvasScreen(canvas, host, chatHostModules(host), 0L, null, onTab = {}) } }
        mainClock.advanceTimeBy(600)
        onNodeWithContentDescription("Threads module").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("arrange").performClick()
        mainClock.advanceTimeBy(600)
        val block = onNodeWithContentDescription("arrange module:needs")
        val before = block.getBoundsInRoot()
        mainClock.autoAdvance = true
        // Slowly, as a finger drags (the test's mouse doesn't drag here): 50 small moves, 150 dp down in all. The block is where the pointer is at every step.
        block.performTouchInput {
            down(center)
            repeat(50) { moveBy(Offset(0f, 3f), delayMillis = 16) }
        }
        mainClock.advanceTimeBy(100)
        val during = block.getBoundsInRoot()
        block.performTouchInput { up() }
        mainClock.advanceTimeBy(300)
        assertEquals(150f, (during.top - before.top).value, 3f)
        // Let go, it lands on the whole cells nearest where it was dragged: 150 dp at this grid's pitch.
        val pitch = (760f - 36f - 8f * (DESKTOP_ROWS + 1)) / DESKTOP_ROWS + 8f
        assertEquals(Math.round(150f / pitch), canvas.items().first { it.key == "module:needs" }.cell!!.y)
    }

    @Test fun aCanvasThatCantBeKeptStillShowsItsChatAndSaysSo() {
        val dir = Files.createTempDirectory("soma-canvas-ro").toFile()
        // Where the log should be is a folder: nothing can be written there.
        val file = dir.resolve("canvas.json").apply { mkdirs(); resolve("kept.txt").writeText("x") }
        val canvas = DesktopCanvas(file, "test").also { it.load() }
        assertTrue(canvas.has(CHAT_BLOCK))
        assertEquals(6, canvas.items().size)
        assertNotNull(canvas.unkept)
        assertTrue(file.isDirectory)
    }

    @Test fun aLogThatCantBeReadOrSetAsideIsNeverWrittenOver() {
        val dir = Files.createTempDirectory("soma-canvas-aside").toFile()
        val file = dir.resolve("canvas.json").apply { writeText("{not json") }
        dir.setWritable(false)
        try {
            val canvas = DesktopCanvas(file, "test").also { it.load() }
            assertTrue(canvas.has(CHAT_BLOCK))
            assertNotNull(canvas.unkept)
            canvas.add("module:pocket")
            assertEquals("{not json", file.readText())
            // Writable again, it still isn't written: what keeps it is that it couldn't be set aside, not the disk.
            dir.setWritable(true)
            // A change that applies (round-3 Critic: adding to a full page changed nothing, so nothing was tried).
            assertTrue(canvas.apply(dev.omalauncher.widgets.layout.CanvasVerb.Remove("module:computers")).applied)
            assertEquals("{not json", file.readText())
            assertNotNull(canvas.unkept)
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun everyChangeIsARequestOfItsOwn() {
        val canvas = DesktopCanvas(Files.createTempDirectory("soma-canvas-req").toFile().resolve("canvas.json"), "test").also { it.load() }
        canvas.apply(dev.omalauncher.widgets.layout.CanvasVerb.Remove("module:computers"))
        canvas.apply(dev.omalauncher.widgets.layout.CanvasVerb.Remove("module:newthread"))
        val last = canvas.log.entries.takeLast(2).map { it.request }
        assertFalse(last[0] == last[1])
    }
}
