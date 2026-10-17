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

/** The shared chat screen draws on the desktop, off-screen, from a host with nothing but what is given to it. */
class ChatScreenRenderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A host with a short thread on it and no phone behind it. */
    private open inner class SampleHost : ChatHost {
        val now = System.currentTimeMillis()
        val thread =
            listOf(
                ChatTask(now - 60_000, "oma", "what's left on the cockpit build?", result = "Slice 2 is under way: the chat screens move into the shared module.", status = TaskStatus.Done, answeredBy = "Haiku"),
                ChatTask(now - 5_000, "build", "run the suite on the Mac Pro", steps = listOf("Bash ./gradlew :app:testDebugUnitTest"), status = TaskStatus.Running),
            )

        override fun tasks() = thread
        override fun updateTask(time: Long, save: Boolean, change: (ChatTask) -> ChatTask) {}
        override fun working() = true
        override fun builders() = mapOf(BuilderId.A to BuilderState(working = true, label = "running the tests"))
        override fun continuing() = false
        override fun front() = FrontState()
        override fun macPending() = emptyList<MacPending>()
        override fun macView() = MacView()
        override fun macLabel(task: ChatTask) = ""
        override fun macEarlier(threadId: String): MacEarlierRead = MacEarlierRead.Unread
        override fun hosts() = listOf(HostChoice("mac", "Mac Pro", true, listOf("claude", "codex", "grok")), HostChoice("omarchy-mg1", "MG1", false, listOf("claude", "grok")))
        override fun agentName() = "Claude Code"
        override fun agentBin() = "claude"
        @Composable override fun linuxInstalled() = true
        override fun frontReady() = true
        override fun threads() = listOf(AgentThread(PHONE_THREAD, "Phone", ThreadState.Working, "running the tests", "working", worked = now)) +
            book.chats.map { dev.omalauncher.assist.chatThread(it, NEW_CHAT_NAME, thread, MacView()) }
        override fun liveThreads(all: List<AgentThread>, pinned: Set<String>, archived: Set<String>) = all
        var book = TabMeta()
        override fun changeBook(change: (TabMeta) -> TabMeta) = change(book).also { book = it }
        override fun checkTitles() {}
        override fun builderFacts() = emptyMap<BuilderId, BuilderFacts>()
        override fun lookAtBuilders() {}
        override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) {}
        override fun askFront(prompt: String, chat: String) {}
        override fun startMacChat(prompt: String, chat: String) {}
        override fun sayToMac(prompt: String, thread: String, name: String, chat: String) {}
        override fun stopMac(thread: String, said: (String?) -> Unit) {}
        override fun tryMacAgain(time: Long) {}
        override fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit) {}
        override fun runInTerminal(prompt: String) = false
        override fun handOverToBuild(prompt: String, said: List<ChatTask>) {}
        override fun routingCorrected(request: String) {}
        override fun answerBuilder(entry: PendingApproval, allow: Boolean) = ApprovalOutcome.Expired
        override fun cancelFollowUp(time: Long) {}
        override fun sendNow(time: Long) {}
        override fun stopBuilder(builder: BuilderId?) {}
        override fun cancelFront(time: Long) {}
        override fun stopFront() {}
        override fun askFrontAgain(time: Long) {}
        override fun dropFrontWaiting(chat: String?) {}
        override fun restartBuilder(builder: BuilderId, said: (String) -> Unit) {}
        override fun cleanBuilders(said: (String) -> Unit) {}
        override fun copy(text: String) {}
        override fun drafts() = ChatDrafts(File(tmp.root, "drafts.txt"), File(tmp.root, "attachments"))
        @Composable override fun rememberImagePicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit = {}
        override fun importImage(picked: PickedImage): PromptImage = error("no pictures here")
        override fun thumbnail(file: File, longest: Int): ImageBitmap? = null
        override fun sentImage(path: String) = File(path)

        @Composable
        override fun rememberVoice(): Voice =
            remember {
                object : Voice {
                    override val listening = false
                    override val text = ""
                    override var error: String? by mutableStateOf(null)
                    override fun start(hold: Boolean, onDone: (String) -> Unit) {}
                    override fun stop() {}
                    override fun cancel() {}
                }
            }

        @Composable override fun rememberMicrophone(onGranted: () -> Unit): () -> Unit = onGranted
    }

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

    /** He taps the choices and they are kept on the chat: another computer, then another agent it runs. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun tappingThePicksChangesWhereTheThreadGoes() = androidx.compose.ui.test.runComposeUiTest {
        val host = SampleHost()
        host.book = TabMeta().started("c1", host.now).chose("c1", "omarchy-mg1", "grok")
        // The screen looks again twice a second for ever, so the clock is moved by hand, never left to run to idle.
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        fun settle() = mainClock.advanceTimeBy(1_200)
        settle()
        onNodeWithText("offline").assertExists()
        onNodeWithText("[ MG1 ]").performClick()
        settle()
        onNodeWithText("make its thread on").assertExists()
        onNodeWithText("Mac Pro").performClick()
        settle()
        // Mac Pro runs Grok too, so the agent he picked stays.
        assertTrue(host.book.chat("c1")!!.host == "mac" && host.book.chat("c1")!!.agent == "grok")
        onNodeWithText("[ Mac Pro ]").assertExists()
        onNodeWithText("[ Grok ]").performClick()
        settle()
        onNodeWithText("Codex").performClick()
        settle()
        assertTrue(host.book.chat("c1")!!.agent == "codex")
        onNodeWithText("[ Codex ]").assertExists()
        onNodeWithText("start this chat on Mac Pro…").assertExists()
    }

    /** Before a computer has said which agents it runs, every agent the runner knows is offered, and the menu says why. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aComputerNotHeardFromYetOffersEveryAgent() = androidx.compose.ui.test.runComposeUiTest {
        val state = object : SampleHost() {
            override fun hosts() = listOf(HostChoice("macbook-pro", "Jack's MacBook Pro Max", false, emptyList()))
        }
        state.book = TabMeta().started("c1", state.now)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(state, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        mainClock.advanceTimeBy(1_200)
        // A long name is cut to fit the cover screen.
        onNodeWithText("[ Jack's MacBook Pr… ]").assertExists()
        onNodeWithText("[ Claude ]").performClick()
        mainClock.advanceTimeBy(1_200)
        onNodeWithText("with · Jack's MacBook Pro Max hasn't said which it runs yet").assertExists()
        onNodeWithText("Codex").performClick()
        mainClock.advanceTimeBy(1_200)
        assertTrue(state.book.chat("c1")!!.agent == "codex")
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
