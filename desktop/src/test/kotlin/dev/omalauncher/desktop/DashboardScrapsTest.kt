package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.canvas.chatHostModules
import dev.omalauncher.ui.DesktopBack
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.Box as Room
import dev.omalauncher.widgets.annotations.box
import dev.omalauncher.widgets.annotations.taken
import dev.omalauncher.widgets.layout.CanvasVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * The dashboard's notes, stickers and ink (desktop slice 5b, Boss 2026-10-28: the dashboard is to be like the phone's home):
 * added from the page's right-click menu, drawn by the phone's own code, kept in Soma's folder, on their own page, and
 * never placed over a block.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardScrapsTest {
    private val home = Files.createTempDirectory("soma-scraps").toFile().also { System.setProperty("soma.home", it.path) }

    /** An empty spot of the starting dashboard, in the window: the lower right is left free for what he adds. */
    private val free = Offset(880f, 560f)

    private class Shown(val canvas: DesktopCanvas, val scraps: DesktopScraps)

    private fun ComposeUiTest.dashboard(): Shown {
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        val canvas = DesktopCanvas(SomaHome.canvas, "test", chat = false).also { it.load() }
        val scraps = DesktopScraps(canvas, SomaHome.dir)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(1024.dp, 700.dp)) { CanvasScreen(canvas, host, chatHostModules(host), 0L, null, onTab = {}, chatElsewhere = {}, scraps = scraps) } }
        settle { scraps.book.status(canvas.showing) == AnnotationBook.Status.Ready && scraps.book.writable }
        return Shown(canvas, scraps)
    }

    /** Lets the clock, the window's thread and the files' thread go on a moment, or until [done] when it is given. */
    private fun ComposeUiTest.settle(done: (() -> Boolean)? = null) {
        repeat(if (done == null) 8 else 150) {
            mainClock.advanceTimeBy(100)
            if (!SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeAndWait {}
            waitForIdle()
            if (done?.invoke() == true) return
            Thread.sleep(20)
        }
        if (done != null) assertTrue("it settled", done())
    }

    private fun ComposeUiTest.rightClickPage(at: Offset = free) {
        onRoot().performMouseInput { rightClick(at) }
        settle()
    }

    private fun ComposeUiTest.render(name: String) {
        File("build/render").mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/render/$name.png"))
    }

    /** What the dashboard keeps of [page], read again from its files by a window opened afresh. */
    private fun reread(page: String): Annotations {
        val canvas = DesktopCanvas(SomaHome.canvas, "test", chat = false).also { it.load() }
        val book = DesktopScraps(canvas, SomaHome.dir) { task -> task() }.book
        book.open(page)
        repeat(200) { if (book.status(page) == AnnotationBook.Status.Ready) return book.on(page); Thread.sleep(20) }
        error("$page wasn't read")
    }

    /** Has every change written now, and waits for the files' thread to have done it. */
    private fun ComposeUiTest.keep(scraps: DesktopScraps) {
        runOnIdle { scraps.book.flush() }
        settle()
    }

    /** The blocks on [page] as fractions of it, as the dashboard lays them out. */
    private fun blocks(shown: Shown, page: String): List<Room> = shown.scraps.board(page).let { board -> board.tiles.map { board.box(it.cell) } }

    private fun clear(room: Room, of: List<Room>) = of.none { it.overlap(room) > 0f }

    @Test fun aNoteFromThePageMenuShowsItsTextIsKeptAndItIsOnNoBlock() = runComposeUiTest {
        val shown = dashboard()
        rightClickPage()
        onNodeWithText("add a note").performClick()
        settle()
        // Its text, typed in the phone's editor over the window.
        onNode(hasSetTextAction()).performTextInput("call the plumber")
        settle()
        onNodeWithText("done").performClick()
        settle()
        val note = shown.scraps.book.on(DESKTOP_PAGE).notes.single()
        assertEquals("call the plumber", note.text)
        onNodeWithText("call the plumber").assertExists()
        assertTrue("on no block", clear(note.box(), blocks(shown, DESKTOP_PAGE)))
        // Near where the menu was asked for: the lower right.
        assertTrue(note.x > 0.5f && note.y > 0.4f)
        render("dashboard-note")
        // Its text edited: a click opens it again.
        onNodeWithText("call the plumber").performClick()
        settle()
        onNode(hasSetTextAction()).performTextInput(" at 9")
        settle()
        assertTrue(DesktopBack.press())
        settle()
        assertEquals("call the plumber at 9", shown.scraps.book.on(DESKTOP_PAGE).notes.single().text)
        keep(shown.scraps)
        assertEquals("call the plumber at 9", reread(DESKTOP_PAGE).notes.single().text)
    }

    @Test fun aStickerFromTheLibraryIsPlacedShownAndOnNoBlock() = runComposeUiTest {
        // A picture in the library he fills: a red disc.
        val library = File(SomaHome.dir, "stickers").apply { mkdirs() }
        val disc = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
        disc.createGraphics().apply { color = Color(220, 40, 40); fillOval(10, 10, 280, 280); dispose() }
        ImageIO.write(disc, "png", File(library, "disc.png"))
        val shown = dashboard()
        rightClickPage()
        onNodeWithText("add a sticker").performClick()
        settle { onAllNodes(hasContentDescription("disc")).fetchSemanticsNodes().isNotEmpty() }
        render("dashboard-sticker-picker")
        onAllNodes(hasContentDescription("disc"))[0].performClick()
        settle { shown.scraps.book.on(DESKTOP_PAGE).stickers.isNotEmpty() }
        val sticker = shown.scraps.book.on(DESKTOP_PAGE).stickers.single()
        assertNotNull("a picture's copy", sticker.picture)
        assertTrue("on no block", clear(sticker.taken(shown.scraps.pageHeight / shown.scraps.pageWidth), blocks(shown, DESKTOP_PAGE)))
        // Its picture is drawn on the page, from the copy, once it is decoded.
        settle { onAllNodes(hasContentDescription("disc")).fetchSemanticsNodes().isNotEmpty() }
        // Once it has eased in from where it was picked, it fills its selected frame.
        settle()
        render("dashboard-sticker")
        // Selected as it arrives, as on the phone: its bar deletes it.
        onNodeWithText("delete").assertExists()
        keep(shown.scraps)
        assertEquals(sticker.picture?.file, reread(DESKTOP_PAGE).stickers.single().picture?.file)
    }

    @Test fun inkDrawnWithTheMouseIsKeptAndEscapeEndsDrawing() = runComposeUiTest {
        val shown = dashboard()
        rightClickPage()
        onNodeWithText("draw").performClick()
        settle()
        assertTrue(shown.scraps.drawing)
        // Over a block too: drawing takes the pointer from what is under it.
        onRoot().performMouseInput {
            moveTo(Offset(300f, 300f))
            press()
            for (i in 1..40) moveTo(Offset(300f + i * 15f, 300f + (i % 8) * 12f))
            release()
        }
        settle()
        assertEquals(1, shown.scraps.book.on(DESKTOP_PAGE).strokes.size)
        render("dashboard-ink")
        assertTrue(DesktopBack.press())
        settle()
        assertFalse(shown.scraps.drawing)
        onNodeWithText("call the plumber").assertDoesNotExist()
        keep(shown.scraps)
        assertEquals(1, reread(DESKTOP_PAGE).strokes.size)
    }

    @Test fun whatIsOnPageTwoIsNotOnPageOne() = runComposeUiTest {
        val shown = dashboard()
        runOnIdle {
            shown.canvas.apply(CanvasVerb.PageMake("page2", "Two"))
            shown.canvas.shown = "page2"
        }
        settle { shown.scraps.book.status("page2") == AnnotationBook.Status.Ready }
        rightClickPage(Offset(600f, 300f))
        onNodeWithText("add a note").performClick()
        settle()
        onNode(hasSetTextAction()).performTextInput("only on two")
        settle()
        onNodeWithText("done").performClick()
        settle()
        onNodeWithText("only on two").assertExists()
        render("dashboard-page-two")
        runOnIdle { shown.canvas.shown = DESKTOP_PAGE }
        settle()
        onNodeWithText("only on two").assertDoesNotExist()
        assertTrue(shown.scraps.book.on(DESKTOP_PAGE).notes.isEmpty())
        assertEquals("only on two", shown.scraps.book.on("page2").notes.single().text)
    }

    @Test fun aNoteIsMovedByDraggingAndSelectedByRightClick() = runComposeUiTest {
        val shown = dashboard()
        val id = runOnIdle { shown.scraps.book.addNote(DESKTOP_PAGE, 0.8f to 0.7f).also { shown.scraps.book.changeNote(DESKTOP_PAGE, it!!) { n -> n.copy(text = "move me") } } }
        settle()
        val before = shown.scraps.book.on(DESKTOP_PAGE).notes.single()
        val at = onNodeWithText("move me").fetchSemanticsNode().boundsInRoot.center
        onRoot().performMouseInput {
            moveTo(at)
            press()
            for (i in 1..10) moveTo(at + Offset(-i * 8f, -i * 3f))
            release()
        }
        settle()
        val after = shown.scraps.book.on(DESKTOP_PAGE).notes.single { it.id == id }
        assertTrue("moved left", after.x < before.x - 0.03f)
        // The drag didn't open it.
        onNode(hasSetTextAction()).assertDoesNotExist()
        // A right-click selects it, not the page's menu: its bar shows.
        onRoot().performMouseInput { rightClick(onNodeWithText("move me").fetchSemanticsNode().boundsInRoot.center) }
        settle()
        onNodeWithText("add a note").assertDoesNotExist()
        onNodeWithText("delete").assertExists()
        render("dashboard-note-selected")
        onNodeWithText("delete").performClick()
        settle()
        assertTrue(shown.scraps.book.on(DESKTOP_PAGE).notes.isEmpty())
        assertTrue(hasText("move me").let { m -> onAllNodes(m).fetchSemanticsNodes().isEmpty() })
    }
}
