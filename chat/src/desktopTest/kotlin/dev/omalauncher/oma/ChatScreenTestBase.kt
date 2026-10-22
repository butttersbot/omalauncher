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

/** What the chat screen's desktop tests share: a host with nothing but what is given to it. */
abstract class ChatScreenTestBase {
    @get:Rule val tmp = TemporaryFolder()

    /** A host with a short thread on it and no phone behind it. */
    protected open inner class SampleHost : ChatHost {
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
        override fun sayToMac(prompt: String, thread: String, name: String, chat: String, images: List<java.io.File>) {}
        override fun stopMac(thread: String, said: (String?) -> Unit) {}
        var switched: Pair<String, dev.omalauncher.assist.MacPick>? = null
        override fun switchMac(thread: String, to: dev.omalauncher.assist.MacPick) { switched = thread to to }
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
}
