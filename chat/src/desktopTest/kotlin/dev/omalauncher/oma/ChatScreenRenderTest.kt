package dev.omalauncher.oma

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.use
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.MacApprovalOutcome
import dev.omalauncher.assist.MacEarlierRead
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacView
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.linux.PromptImage
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The shared chat screen drawn off-screen on the desktop. Only off-screen scenes live in this class: a scene and a UI test in
 * one JVM trip over each other, and each test class gets a JVM of its own (chat/build.gradle.kts), so the tap tests are in
 * ChatScreenTapTest.
 */
class ChatScreenRenderTest : ChatScreenTestBase() {
    /** The chat drawn off-screen at [wanted]'s tab, kept as [name] in build/render. */
    private fun render(host: ChatHost, wanted: String?, name: String, width: Int = 900): ByteArray {
        val png =
            ImageComposeScene(width = width, height = 1400, density = Density(2f)) {
                OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = {})
            }.use { scene ->
                scene.render()
                // Twice: the screen's first look settles the tab book and the thread before it draws them.
                scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            }
        val out = File(System.getProperty("render.dir") ?: "build/render").apply { mkdirs() }
        File(out, name).writeBytes(png)
        return png
    }

    /** The phone's cover screen (344 dp across): the picks still sit whole on their two lines with a long name, out of reach. */
    @Test fun thePicksFitTheCoverScreen() {
        val host = object : SampleHost() {
            override fun hosts() = listOf(HostChoice("macbook-pro", "Jack's MacBook Pro Max", false, listOf("claude", "codex")))
        }
        host.book = TabMeta().started("c1", host.now).chose("c1", "macbook-pro", "codex")
        render(host, chatKey("c1"), "chat-newchat-cover.png", width = 688)
    }

    @Test fun aNewChatShowsWhereItsThreadWillBeMadeAndWithWhom() {
        val host = SampleHost()
        host.book = TabMeta().started("c1", host.now).chose("c1", "omarchy-mg1", "grok")
        render(host, chatKey("c1"), "chat-newchat.png")
        // A pick is kept on the chat until its thread is made, and a bound chat takes no other.
        val chat = host.book.chat("c1")!!
        assertTrue(chat.host == "omarchy-mg1" && chat.agent == "grok")
        assertTrue(host.book.onMac("c1", "t1").chose("c1", "mac", "claude").chat("c1")!!.host == "omarchy-mg1")
        assertTrue(tabMetaFrom(tabMetaText(host.book)).chat("c1") == chat)
    }

    @Test fun theChatDrawsOnTheDesktop() {
        val host = SampleHost()
        val png = render(host, null, "chat-desktop.png")
        val image = org.jetbrains.skia.Image.makeFromEncoded(png)
        val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val seen = HashSet<Int>()
        for (y in 0 until image.height step 7) for (x in 0 until image.width step 7) seen += pixels.getColor(x, y)
        // A blank or crashed screen is one colour; a drawn chat has its text, frames and dots.
        assertTrue("the chat drew only ${seen.size} colours", seen.size > 8)
    }
}
