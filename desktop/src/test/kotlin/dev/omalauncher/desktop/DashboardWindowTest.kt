package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.canvas.chatHostModules
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.runtime.CommandGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The dashboard window (desktop parity slice 5a, Boss 2026-10-28): the chat lives in its own window, so the dashboard has
 * no chat block; .owl widgets from the library are added from its menu and drawn once he has approved the file here.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardWindowTest {
    private val home = Files.createTempDirectory("soma-dashboard").toFile().also { System.setProperty("soma.home", it.path) }

    private fun sha(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun aNewDashboardHasNoChatAndAnOlderOnesChatGoes() {
        val fresh = DesktopCanvas(File(home, "fresh.json"), "test", chat = false).also { it.load() }
        assertFalse(fresh.has(CHAT_BLOCK))
        assertTrue("its modules fill the page", fresh.items().size == 5)
        // A canvas the chat window used to share: its chat block goes, the rest stays where it was.
        val older = File(home, "older.json")
        DesktopCanvas(older, "test").also { it.load() }
        val was = DesktopCanvas(older, "test").also { it.load() }.items().filter { it.key != CHAT_BLOCK }.map { it.key to it.cell }
        val now = DesktopCanvas(older, "test", chat = false).also { it.load() }
        assertFalse(now.has(CHAT_BLOCK))
        assertEquals(was, now.items().map { it.key to it.cell })
        // The chat window's own canvas keeps its chat as ever.
        assertTrue(DesktopCanvas(File(home, "chat.json"), "test").also { it.load() }.let { it.has(CHAT_BLOCK) && !it.apply(CanvasVerb.Remove(CHAT_BLOCK)).applied })
    }

    @Test fun aLibraryWidgetIsAddedFromTheMenuAndDrawnOnceApproved() = runComposeUiTest {
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        val gate = CommandGate(File(home, "approved.json"), "test")
        val widgets = WindowWidgets(home, gate)
        val text = "widget \"hello\" {\n  size: 3x2\n  Column {\n    fill: true\n    Text { text: \"HELLO DASHBOARD\" }\n  }\n}\n"
        File(home, "widgets").mkdirs()
        File(home, "widgets/hello.owl").writeText(text)
        val canvas = DesktopCanvas(SomaHome.canvas, "test", chat = false).also { it.load() }
        var now by androidx.compose.runtime.mutableStateOf(0L)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(1240.dp, 820.dp)) { CanvasScreen(canvas, host, chatHostModules(host), now, null, onTab = {}, chatElsewhere = {}, widgets = widgets) } }
        mainClock.advanceTimeBy(600)
        onNodeWithContentDescription("Threads module").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("add a widget").assertExists()
        onNodeWithText("hello").performClick()
        mainClock.advanceTimeBy(600)
        assertTrue("placed as a widget", canvas.items().any { it.key == "hello.owl" && it.kind == ItemKind.Widget })
        // Not his OK yet: it says it waits, and runs nothing.
        onNodeWithText("hello.owl waits for your OK in Needs you").assertExists()
        // His OK (here as the gate keeps it): it is drawn.
        gate.approveFile("hello.owl", sha(text))
        now = 1L  // the window's next look
        mainClock.advanceTimeBy(2_000)
        waitUntil(timeoutMillis = 10_000) { mainClock.advanceTimeBy(200); onAllNodes(androidx.compose.ui.test.hasText("HELLO DASHBOARD")).fetchSemanticsNodes().isNotEmpty() }
        File("build/render").mkdirs()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/dashboard-widget.png"))
        // And removed from its own menu.
        onNodeWithText("HELLO DASHBOARD").performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("remove widget · hello").performClick()
        mainClock.advanceTimeBy(300)
        assertFalse(canvas.has("hello.owl"))
    }
}
