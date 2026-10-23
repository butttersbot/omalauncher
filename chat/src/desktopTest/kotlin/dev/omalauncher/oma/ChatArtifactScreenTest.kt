package dev.omalauncher.oma

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.PHONE_THREAD
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ChatArtifactScreenTest : ChatScreenTestBase() {
    @Test fun switchingFromSourceToNotesKeepsMarkdownVisibleOnUprightInnerScreen() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = object : SampleHost() {
            override fun tasks() = listOf(ChatTask(now, "Claude", "Make files", result = "[Page](page.html)\n\n[Notes](notes.md)", status = TaskStatus.Done))
            override fun readArtifact(thread: String, path: String) = ArtifactRead.Ready(
                ArtifactDocument(path, if (path.endsWith(".md")) "text/markdown" else "text/html",
                    (if (path.endsWith(".md")) "# Notes\n\nClose it to return to the same draft." else "<h1>Page</h1>").toByteArray(), "sample"))
        }
        setContent { Box(Modifier.size(704.dp, 933.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ files · 2 ]").performClick()
        mainClock.advanceTimeBy(1200)
        onAllNodesWithText("page.html").onLast().performClick()
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ source ]").performClick()
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ files ]").performClick()
        mainClock.advanceTimeBy(1200)
        onAllNodesWithText("notes.md").onLast().performClick()
        mainClock.advanceTimeBy(1200)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Close it to return to the same draft.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Close it to return to the same draft.").assertIsDisplayed()
    }

    @Test fun filesRemainsAvailableInEmptyPhoneAndMacChatsAndBackReturnsToChat() = runComposeUiTest {
        mainClock.autoAdvance = false
        var tab by mutableStateOf("phone-a")
        setContent {
            Box(Modifier.size(475.dp, 751.dp)) {
                ChatArtifactWorkspace(SampleHost(), tab, emptyList(), {}) {
                    androidx.compose.foundation.text.BasicText("Conversation $tab")
                }
            }
        }
        for (target in listOf("phone-a", "phone-b", "mac/thread")) {
            runOnIdle { tab = target }
            mainClock.advanceTimeBy(1200)
            onNodeWithText("[ files · 0 ]").performClick()
            mainClock.advanceTimeBy(1200)
            onNodeWithText("No files in this conversation yet.").assertExists()
            runOnIdle { assertTrue(dev.omalauncher.ui.DesktopBack.press()) }
            mainClock.advanceTimeBy(1200)
            onNodeWithText("Conversation $target").assertExists()
        }
    }

    @Test fun filesOpensTheConversationListBeforeChoosingAnOutput() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(475.dp, 751.dp)) { OmaChatScreen(outputHost(), null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).performTextInput("keep this draft")
        onNodeWithText("[ files · 1 ]").performClick()
        mainClock.advanceTimeBy(1200)
        onNodeWithText("From this conversation").assertExists()
        onNodeWithText("snippet-1.md").performClick()
        mainClock.advanceTimeBy(1200)
        onNodeWithText("Listening room").assertExists()
        onNodeWithText("[ close ]").performClick()
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).assertTextContains("keep this draft")
    }

    private fun outputHost() = object : SampleHost() {
        override fun working() = false
        override fun builders() = emptyMap<dev.omalauncher.assist.BuilderId, BuilderState>()
        override fun tasks() = listOf(ChatTask(now, "Claude", "Create a plan", result = "Done.\n```markdown\n# Listening room\nKeep the chat open.\n```", status = TaskStatus.Done))
    }

    @Test fun coverClosesBackToTheExistingComposerAndDraft() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = outputHost()
        setContent { Box(Modifier.size(475.dp, 751.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).performTextInput("keep this draft")
        onNodeWithText("snippet-1.md").performClick()
        mainClock.advanceTimeBy(1200)
        onNodeWithText("Listening room").assertExists()
        onNodeWithText("[ close ]").performClick()
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).assertTextContains("keep this draft")
        onNodeWithText("[ send ]").assertExists()
        onNodeWithContentDescription("Dictate").assertExists()
    }

    @Test fun unfoldedKeepsChatEditableBesideTheArtifactAndDiscussDoesNotSend() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = outputHost()
        setContent { Box(Modifier.size(933.dp, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("snippet-1.md").performClick()
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).performTextInput("My draft")
        onNodeWithText("[ discuss ]").performClick()
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).assertTextContains("My draft", substring = true)
        onNode(hasSetTextAction()).assertTextContains("Regarding snippet-1.md", substring = true)
        onNodeWithText("[ source ]").performClick()
        mainClock.advanceTimeBy(1200)
        onAllNodesWithText("# Listening room\nKeep the chat open.").assertCountEquals(2)
        saveCapture("artifact-unfolded-native.png")
    }

    @Test fun aMissingFileShowsRetryAndTheChatStillWorks() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = object : SampleHost() {
            override fun tasks() = listOf(ChatTask(now, "Claude", "Make a page", result = "[Open the page](outputs/page.html)", status = TaskStatus.Done))
            override fun readArtifact(thread: String, path: String): ArtifactRead = ArtifactRead.Failed("The computer is offline.")
        }
        setContent { Box(Modifier.size(933.dp, 704.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("page.html").performClick()
        mainClock.advanceTimeBy(1200)
        // IO completion isn't driven by the animation clock.
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("The computer is offline.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("[ try again ]").assertExists()
        onNode(hasSetTextAction()).performTextInput("Still here")
        mainClock.advanceTimeBy(100)
        onNode(hasSetTextAction()).assertTextContains("Still here")
    }

    @Test fun unfoldingAnOpenArtifactRevealsTheSameDraftAndBackClosesThePane() = runComposeUiTest {
        mainClock.autoAdvance = false
        val host = outputHost()
        var width by mutableStateOf(475.dp)
        setContent { Box(Modifier.size(width, 751.dp)) { OmaChatScreen(host, null, {}, OMA_TAB, {}) } }
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).performTextInput("Across the fold")
        onNodeWithText("snippet-1.md").performClick()
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).assertDoesNotExist()
        runOnIdle { width = 933.dp }
        mainClock.advanceTimeBy(1200)
        onNode(hasSetTextAction()).assertTextContains("Across the fold")
        onNodeWithText("Listening room").assertExists()
        runOnIdle { assertTrue(dev.omalauncher.ui.DesktopBack.press()) }
        mainClock.advanceTimeBy(1200)
        onNodeWithText("[ close ]").assertDoesNotExist()
        onNode(hasSetTextAction()).assertTextContains("Across the fold")
    }

    private fun ComposeUiTest.saveCapture(name: String) {
        val pixels = onRoot().captureToImage().toPixelMap()
        val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].let { c ->
            ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
        })
        val out = File(System.getProperty("render.dir") ?: "build/render").apply { mkdirs() }
        javax.imageio.ImageIO.write(image, "png", File(out, name))
    }
}
