package dev.omalauncher.desktop

import dev.omalauncher.assist.MacPick
import dev.omalauncher.assist.macInstalled
import dev.omalauncher.assist.macModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.BuilderNow
import dev.omalauncher.assist.MacApprovalOutcome
import dev.omalauncher.assist.MacEarlierRead
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.assist.MacView
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.ThreadAsk
import dev.omalauncher.assist.agentThreads
import dev.omalauncher.assist.askTarget
import dev.omalauncher.linux.PromptImage
import dev.omalauncher.oma.BuildWay
import dev.omalauncher.oma.BuilderState
import dev.omalauncher.oma.ChatDrafts
import dev.omalauncher.oma.ChatHost
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.FrontState
import dev.omalauncher.oma.HostChoice
import dev.omalauncher.oma.PickedImage
import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.Voice
import dev.omalauncher.widgets.TileLife
import dev.omalauncher.widgets.threadLife
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * The desktop's answers to [ChatHost] (cockpit slice 6). There are no phone builders here: the Soma tab's words go to
 * the desktop front agent, which hands work to the computers' threads, and work for no thread starts a new one. Threads
 * on the computers are the shared engine's ([MacThreadsEngine]), exactly as on the phone. No pictures and no microphone:
 * the desktop's own dictation types into the field.
 */
class DesktopChatHost(
    private val platform: DesktopThreadsPlatform,
    private val engine: MacThreadsEngine,
    private val front: DesktopFrontAgent,
    private val notices: Notices,
) : ChatHost {
    override val attaches: Boolean get() = true
    override val dictates: Boolean get() = false
    override val enterSends: Boolean get() = true

    // No Soma tab and no front agent here (Boss, 2026-10-19): it opens to a new chat, and he starts each one himself.
    override val somaTab: Boolean get() = false

    override fun tasks(): List<ChatTask> = platform.chatLog().tasks

    override fun updateTask(time: Long, save: Boolean, change: (ChatTask) -> ChatTask) = platform.chatLog().update(time, save, change)

    override fun working(): Boolean = false

    override fun builders(): Map<BuilderId, BuilderState> = emptyMap()

    override fun continuing(): Boolean = false

    override fun front(): FrontState = FrontState(front.working, front.time, front.label, front.answer)

    override fun macPending(): List<MacPending> = engine.pending

    override fun macView(): MacView = engine.view

    override fun macLabel(task: ChatTask): String = engine.label(task)

    override fun readArtifact(thread: String, path: String) = engine.artifact(thread, path)

    override fun liveArtifacts() = engine.liveArtifacts()

    override fun artifactVersions(thread: String, name: String) = engine.artifactVersions(thread, name)

    override fun macEarlier(threadId: String): MacEarlierRead = engine.earlier(threadId)

    override fun hosts(): List<HostChoice> = engine.hosts().map { seen ->
        val agents = engine.kindsOn(seen.id)
        HostChoice(seen.id, seen.name, seen.reachable, agents, macInstalled(agents).associateWith { macModels(it, engine.view.threads, engine.modelsOn(seen.id)[it].orEmpty()) }, engine.defaultPick(seen.id), engine.hostAddress(seen.id), makes = engine.makesThreads(seen.id))
    }

    override fun agentName(): String? = null

    // Always a background agent, so the send rules never refuse (ChatComposer.kt): a new chat's words start its thread
    // directly, on the computer, agent and model picked (startMacChat → startChatDirect), never through a front agent.
    override fun agentBin(): String? = "claude"

    @Composable
    override fun linuxInstalled(): Boolean = true

    override fun frontReady(): Boolean = false

    override fun threads(): List<AgentThread> {
        // The list is asked for again once it is a minute old, as home's tiles do on the phone: the desktop has no tiles.
        engine.glance(System.currentTimeMillis())
        val book = platform.book()
        val asks = engine.pending.groupBy({ it.threadId }) { ThreadAsk(it.request.tool, askTarget(it.request.input), it.request.requestedAt) }
        // The phone's own threads (its builders) are the phone's: not here.
        return agentThreads(tasks(), BuilderNow(), engine.view, asks, BuilderNow(), book.chats, book.names).filter { BuilderId.ofThread(it.key) == null }
    }

    override fun liveThreads(all: List<AgentThread>, pinned: Set<String>, archived: Set<String>): List<AgentThread> {
        val now = System.currentTimeMillis()
        return all.filter { threadLife(it.state, it.worked, now, it.key in pinned, it.key in archived) != TileLife.Gone }
            .sortedBy { if (it.key in pinned) pinned.indexOf(it.key) else Int.MAX_VALUE }
            .sortedWith(dev.omalauncher.assist.agentThreadOrder)
    }

    override fun changeBook(change: (TabMeta) -> TabMeta): TabMeta = platform.changeBook(change)

    private val titles = DesktopChatTitles(platform)

    override fun checkTitles() = titles.check()

    override fun builderFacts(): Map<BuilderId, BuilderFacts> = emptyMap()

    override fun lookAtBuilders() {}

    /** No builders here: work sent straight to "build" goes to a new thread, as a new chat's first words do. */
    override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) = engine.startChatDirect(prompt, "")

    override fun askFront(prompt: String, chat: String) = front.ask(prompt, chat)

    override fun startMacChat(prompt: String, chat: String, images: List<java.io.File>) = engine.startChatDirect(prompt, chat, images, images.map { it.path })

    override fun sayToMac(prompt: String, thread: String, name: String, chat: String, images: List<java.io.File>) = engine.say(prompt, thread, name, chat, images, images.map { it.path })

    override fun stopMac(thread: String, said: (String?) -> Unit) = engine.stop(thread, said)

    override fun botFiles(thread: String, told: (dev.omalauncher.assist.MacBotFiles?) -> Unit) = engine.botFiles(thread, told)

    override fun freshBot(thread: String, said: (String) -> Unit) = engine.freshBot(thread, said)

    override fun switchMac(thread: String, to: MacPick) = engine.switchTo(thread, to)

    override fun tryMacAgain(time: Long) = engine.tryAgain(time)

    override fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit) = engine.answer(ticket, allow, done)
    // A question form's answers (AskUserQuestion, Codex's questions): without this the form always said the Mac could not take them.
    override fun answerMacQuestions(ticket: String, answers: org.json.JSONObject, done: (MacApprovalOutcome) -> Unit) = engine.answerQuestions(ticket, answers, done)
    override fun rememberMacCommand(ticket: String, done: (MacApprovalOutcome) -> Unit) = engine.rememberCommand(ticket, done)
    override fun forgetMacCommands(threadId: String, done: (String) -> Unit) = engine.forgetCommands(threadId, done)

    override fun runInTerminal(prompt: String): Boolean = false

    override fun handOverToBuild(prompt: String, said: List<ChatTask>) = engine.startChatDirect(prompt, "")

    override fun routingCorrected(request: String) {}

    override fun answerBuilder(entry: PendingApproval, allow: Boolean): ApprovalOutcome = ApprovalOutcome.Expired

    override fun cancelFollowUp(time: Long) {}

    override fun sendNow(time: Long) {}

    override fun stopBuilder(builder: BuilderId?) {}

    override fun cancelFront(time: Long) = front.cancel(time)

    override fun stopFront() = front.stop()

    override fun askFrontAgain(time: Long) = front.again(time)

    override fun dropFrontWaiting(chat: String?) = front.dropWaiting(chat)

    override fun restartBuilder(builder: BuilderId, said: (String) -> Unit) {}

    override fun cleanBuilders(said: (String) -> Unit) {}

    override fun copy(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        notices.toast("Copied")
    }

    override fun drafts(): ChatDrafts = ChatDrafts.of(SomaHome.dir)

    /** A file dialog, several pictures at once; what was chosen goes to [onPicked] (nothing when he cancels). */
    @Composable
    override fun rememberImagePicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit {
        val current by androidx.compose.runtime.rememberUpdatedState(onPicked)
        return remember {
            {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Add pictures", java.awt.FileDialog.LOAD).apply {
                    isMultipleMode = true
                    setFilenameFilter { dir, name -> pictureKind(File(dir, name)) != null }
                }
                dialog.isVisible = true
                current(dialog.files.orEmpty().filter { it.isFile }.map { FileImage(it) })
            }
        }
    }

    /** Where a paste or a drop on the chat window goes: the chat on screen's own taking of pictures, while it is there. */
    @Volatile private var arrivals: ((List<PickedImage>) -> Unit)? = null

    @Composable
    override fun rememberImageArrivals(onPicked: (List<PickedImage>) -> Unit) {
        val current by androidx.compose.runtime.rememberUpdatedState(onPicked)
        androidx.compose.runtime.DisposableEffect(Unit) {
            val take: (List<PickedImage>) -> Unit = { current(it) }
            arrivals = take
            onDispose { if (arrivals === take) arrivals = null }
        }
    }

    /** Pictures pasted or dropped on the chat window; false when there were none or no chat to take them (text goes on as before). */
    fun arrived(pictures: List<FileImage>): Boolean {
        val take = arrivals
        if (pictures.isEmpty() || take == null) {
            pictures.filter { it.temporary }.forEach { it.file.delete() }
            return false
        }
        take(pictures)
        return true
    }

    // A pasted picture's temporary file, when it wasn't taken (round-1 Critic: /tmp/soma-paste-* grew).
    override fun releaseImage(picked: PickedImage) {
        (picked as? FileImage)?.takeIf { it.temporary }?.file?.delete()
    }

    override fun importImage(picked: PickedImage): PromptImage = importDesktopImage(picked as FileImage, File(SomaHome.dir, "attachments"))

    override fun thumbnail(file: File, longest: Int): ImageBitmap? = desktopThumbnail(file, longest)

    override fun sentImage(path: String): File = File(path)

    @Composable
    override fun rememberVoice(): Voice = remember { Silent() }

    @Composable
    override fun rememberMicrophone(onGranted: () -> Unit): () -> Unit = {}

    /** The microphone that isn't shown ([dictates] is false): it never listens. */
    private class Silent : Voice {
        override val listening = false
        override val text = ""
        override var error: String? by mutableStateOf(null)
        override fun start(hold: Boolean, onDone: (String) -> Unit) {}
        override fun stop() {}
        override fun cancel() {}
    }
}
