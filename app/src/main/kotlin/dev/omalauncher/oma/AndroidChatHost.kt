package dev.omalauncher.oma

import dev.omalauncher.assist.MacPick
import dev.omalauncher.assist.macInstalled
import dev.omalauncher.assist.macModels
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.Runtime
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.BuilderWatch
import dev.omalauncher.assist.ChatTitles
import dev.omalauncher.assist.MacApprovalOutcome
import dev.omalauncher.assist.MacEarlierRead
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacThreads
import dev.omalauncher.assist.MacView
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.QuickLane
import dev.omalauncher.assist.RoutingLog
import dev.omalauncher.assist.agentThreadsNow
import dev.omalauncher.assist.gotBy
import dev.omalauncher.assist.handOverContext
import dev.omalauncher.assist.handOverPreface
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.PromptImage
import dev.omalauncher.linux.agentInvocation
import dev.omalauncher.linux.importPromptImage
import dev.omalauncher.phone.HomeDoor
import dev.omalauncher.phone.PhoneBridge
import dev.omalauncher.ui.Speech
import dev.omalauncher.widgets.savedTileStamps
import java.io.File

/**
 * The phone's answers to [ChatHost]: the chat as it was before it was shared, each answer the call the screen made
 * itself. [context] is the chat's activity.
 */
class AndroidChatHost(
    private val context: Context,
    private val runtime: Runtime,
) : ChatHost {
    override fun tasks(): List<ChatTask> = ChatLog.of(context).tasks.filter { it.builder != "D" }

    override fun updateTask(time: Long, save: Boolean, change: (ChatTask) -> ChatTask) {
        ChatLog.of(context).update(time, save = save, change)
    }

    override fun working(): Boolean = AgentTasks.working

    override fun builders(): Map<BuilderId, BuilderState> = AgentTasks.builders.associate { it.id to BuilderState(it.working, it.pending, it.label, it.answer) }

    override fun continuing(): Boolean = AgentTasks.continuing

    override fun front(): FrontState = FrontState(QuickLane.working, QuickLane.time, QuickLane.label, QuickLane.answer)

    override fun macPending(): List<MacPending> = MacThreads.pending

    override fun macView(): MacView = MacThreads.view

    override fun macLabel(task: ChatTask): String = MacThreads.label(task)

    // A phone builder's conversation reads its own store in the guest (an entry of its from before keeps no thread: "").
    override fun readArtifact(thread: String, path: String) =
        if (thread.isEmpty() || phoneArtifactThread(thread)) PhoneArtifacts.read(context, thread, path) else MacThreads.artifact(context, thread, path)

    override fun liveArtifacts(): List<ArtifactLive> = MacThreads.liveArtifacts() + PhoneArtifacts.live(context)

    override fun artifactVersions(thread: String, name: String) =
        if (phoneArtifactThread(thread)) PhoneArtifacts.versions(context, thread, name) else MacThreads.artifactVersions(context, thread, name)

    override fun macEarlier(threadId: String): MacEarlierRead = MacThreads.earlier(context, threadId)

    override fun hosts(): List<HostChoice> = MacThreads.hosts(context).map { seen ->
        val agents = MacThreads.kindsOn(seen.id)
        HostChoice(seen.id, seen.name, seen.reachable, agents, macInstalled(agents).associateWith { macModels(it, MacThreads.view.threads, MacThreads.modelsOn(seen.id)[it].orEmpty()) }, MacThreads.defaultPick(seen.id), MacThreads.hostAddress(seen.id))
    }

    override fun agentName(): String? = runtime.agents.default?.name

    override fun agentBin(): String? = runtime.agents.default?.bin

    @Composable
    override fun linuxInstalled(): Boolean {
        val state by runtime.bootstrap.state.collectAsState()
        return state == LinuxState.Installed
    }

    override fun frontReady(): Boolean = QuickLane.ready(context)

    override fun threads(): List<AgentThread> = agentThreadsNow(context)

    override fun liveThreads(all: List<AgentThread>, pinned: Set<String>, archived: Set<String>): List<AgentThread> =
        dev.omalauncher.widgets.liveThreads(all, savedTileStamps(context), System.currentTimeMillis(), MacThreads.view.listed, pinned, archived)

    override fun changeBook(change: (TabMeta) -> TabMeta): TabMeta = TabBook.change(context, change)

    override fun checkTitles() = ChatTitles.check(context)

    override fun builderFacts(): Map<BuilderId, BuilderFacts> = BuilderWatch.last

    override fun lookAtBuilders() {
        BuilderWatch.look(context)
    }

    override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) {
        when (way) {
            BuildWay.Direct -> AgentTasks.direct(context, prompt, images, builder = builder)
            BuildWay.FollowUp -> AgentTasks.followUp(context, prompt, images, builder = builder)
            BuildWay.Send -> AgentTasks.send(context, prompt, images, builder = builder)
        }
    }

    override fun askFront(prompt: String, chat: String) = QuickLane.ask(context, prompt, chat = chat)

    // The entry keeps the pictures as guest paths, as a builder's do, so its thumbnails show (Boss, 2026-10-21).
    override fun startMacChat(prompt: String, chat: String, images: List<File>) = MacThreads.startChatDirect(context, prompt, chat, images, images.mapNotNull(runtime.proot::guestPath))

    override fun sayToMac(prompt: String, thread: String, name: String, chat: String, images: List<File>) = MacThreads.say(context, prompt, thread, name, chat, images, images.mapNotNull(runtime.proot::guestPath))

    override fun canvasOrigin(): String? = runCatching {
        val file = File(runtime.proot.home, ".config/soma-canvas/env")
        val value = file.readLines().firstOrNull { it.trim().removePrefix("export ").startsWith("CANVAS_URL=") }
            ?.trim()?.removePrefix("export ")?.substringAfter('=')?.trim()?.trim { it.code == 34 || it.code == 39 }
        CanvasReceipt.origin(value)
    }.getOrNull()

    override fun submitArtifactReply(source: ArtifactReplySource, request: ArtifactReplyRequest, done: (ArtifactReplyResult) -> Unit) =
        ArtifactReplyDelivery.submit(context, this, source, request, done)

    override fun stopMac(thread: String, said: (String?) -> Unit) = MacThreads.stop(context, thread, said)

    override fun botFiles(thread: String, told: (dev.omalauncher.assist.MacBotFiles?) -> Unit) = MacThreads.botFiles(context, thread, told)

    override fun freshBot(thread: String, said: (String) -> Unit) = MacThreads.freshBot(context, thread, said)

    override fun switchMac(thread: String, to: MacPick) = MacThreads.switchTo(context, thread, to)

    override fun tryMacAgain(time: Long) = MacThreads.tryAgain(context, time)

    override fun answerMacQuestions(ticket: String, answers: org.json.JSONObject, done: (MacApprovalOutcome) -> Unit) = MacThreads.answerQuestions(context, ticket, answers, done)
    override fun answerBuilderQuestions(entry: PendingApproval, answers: org.json.JSONObject): ApprovalOutcome = AgentTasks.answerApproval(context, entry.id, entry.nonce, true, answers)

    override fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit) = MacThreads.answer(context, ticket, allow, done)
    override fun rememberMacCommand(ticket: String, done: (MacApprovalOutcome) -> Unit) = MacThreads.rememberCommand(context, ticket, done)
    override fun forgetMacCommands(threadId: String, done: (String) -> Unit) = MacThreads.forgetCommands(context, threadId, done)

    override fun runInTerminal(prompt: String): Boolean {
        val agent = runtime.agents.default ?: return false
        runtime.terminal("${agentInvocation(agent.bin, prompt + "\n\n" + PhoneBridge.INSTRUCTIONS)}; exec bash", agent.name, agent)
        return true
    }

    override fun handOverToBuild(prompt: String, said: List<ChatTask>) {
        AgentTasks.handOver(context, prompt, HomeDoor.home?.showing, null) { to -> handOverPreface(handOverContext(said, Long.MAX_VALUE, gotBy(to))) }
    }

    override fun routingCorrected(request: String) = RoutingLog.corrected(context, request)

    override fun answerBuilder(entry: PendingApproval, allow: Boolean): ApprovalOutcome = AgentTasks.answerApproval(context, entry.id, entry.nonce, allow)

    override fun cancelFollowUp(time: Long) = AgentTasks.cancelFollowUp(context, time)

    override fun sendNow(time: Long) {
        AgentTasks.sendNow(context, time)
    }

    override fun stopBuilder(builder: BuilderId?) = AgentTasks.stop(context, builder)

    override fun cancelFront(time: Long) = QuickLane.cancel(context, time)

    override fun stopFront() = QuickLane.stop()

    override fun askFrontAgain(time: Long) = QuickLane.again(context, time)

    override fun dropFrontWaiting(chat: String?) = QuickLane.dropWaiting(context, chat)

    override fun restartBuilder(builder: BuilderId, said: (String) -> Unit) = BuilderWatch.restart(context, builder, said)

    override fun cleanBuilders(said: (String) -> Unit) = BuilderWatch.clean(context, said)

    override fun copy(text: String) = copied(context, text)

    override fun drafts(): ChatDrafts = ChatDrafts.of(context)

    @Composable
    override fun rememberImagePicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(max)) { uris -> onPicked(uris.map(::UriImage)) }
        return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    override fun importImage(picked: PickedImage): PromptImage = importPromptImage(context.contentResolver, (picked as UriImage).uri, runtime.proot)

    override fun thumbnail(file: File, longest: Int): ImageBitmap? =
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val ratio = longest.toFloat() / maxOf(info.size.width, info.size.height).coerceAtLeast(longest)
                decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            }.asImageBitmap()
        }.getOrNull()

    override fun sentImage(path: String): File = runtime.proot.hostFile(path)

    @Composable
    override fun rememberVoice(): Voice = remember { Speech(context.applicationContext) }

    @Composable
    override fun rememberMicrophone(onGranted: () -> Unit): () -> Unit {
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onGranted() }
        return {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onGranted() else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** A picture picked with Android's photo picker. */
private class UriImage(val uri: Uri) : PickedImage

/** Puts [text] on the clipboard and says so. */
private fun copied(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Soma", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
