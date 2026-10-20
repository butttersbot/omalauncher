package dev.omalauncher.oma

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.MacApprovalOutcome
import dev.omalauncher.assist.MacEarlierRead
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacView
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.linux.PromptImage
import java.io.File

/*
 * Everything Soma's chat screen ([OmaChatScreen]) asks of the device it runs on (cockpit/DESIGN.md §1). The screen
 * itself is the same on the phone and on the desktop; the phone app answers with its builders, the front agent in
 * Linux, the Mac's threads, Android's speech and picker, and the desktop app with its runners. A device without a
 * thing (the desktop has no phone builders) answers with nothing there: no state, and an action that does nothing.
 */

/** One builder as the chat reads it twice a second: whether it works, what waits for his OK, its label and its live answer. */
data class BuilderState(val working: Boolean = false, val pending: List<PendingApproval> = emptyList(), val label: String = "", val answer: String = "")

/** The front agent as the chat reads it twice a second: whether it answers, which request it took (by its time), what it is doing and its answer so far. */
data class FrontState(val working: Boolean = false, val time: Long = 0L, val label: String = "", val answer: String = "")

/** Where a request goes when it goes straight to the build lane. */
enum class BuildWay {
    /** From the Soma tab, with what the front agent said since. */
    Direct,

    /** Behind the task that runs. */
    FollowUp,

    /** As new work, now. */
    Send,
}

/** A computer a new chat's thread can be made on: its id ([dev.omalauncher.assist.MacMachine.id]), what he calls it, whether it answers, and the agents it runs. */
data class HostChoice(
    val id: String,
    val name: String,
    val reachable: Boolean,
    val agents: List<String>,
    /** The models each agent's threads can be made with, by agent, the agent's own default ("") first ([dev.omalauncher.assist.macModels]). */
    val models: Map<String, List<String>> = emptyMap(),
    /** What a new chat's thread here uses when he picks nothing: the default new chats remember ([dev.omalauncher.assist.MacThreadsEngine.defaultPick]). */
    val default: dev.omalauncher.assist.MacPick? = null,
    /** The host its runner is reached at (a tailnet name or address), which is how herd's machines are matched to it. */
    val address: String = "",
)

/** A picture he picked, still on the device's side; [ChatHost.importImage] makes it a [PromptImage]. */
interface PickedImage

/** Speaking into the field: push-to-talk only, started and stopped by his tap. Its fields are Compose state. */
interface Voice {
    val listening: Boolean

    /** What has been heard so far in this listening. */
    val text: String

    /** Why the last listening failed, until he dismisses it. */
    var error: String?

    /** Starts listening; [onDone] gets the full text once it ends. With [hold] it keeps on across pauses. */
    fun start(hold: Boolean, onDone: (String) -> Unit)

    fun stop()

    fun cancel()
}

interface ChatHost {
    /** Whether pictures can be attached here: the field's + shows only where they can go somewhere. */
    val attaches: Boolean get() = true

    /** Whether the field's microphone works here. Where it doesn't (the desktop, whose own dictation types into any field), it isn't shown. */
    val dictates: Boolean get() = true

    /**
     * Whether there is a Soma tab, where the front agent answers or hands his words on. The phone's ([OMA_TAB]). The
     * desktop has none (Boss, 2026-10-19: the front agent only makes sense on the phone): it opens to a new chat, whose
     * picker says the computer, agent and model, and every request is a chat of its own.
     */
    val somaTab: Boolean get() = true

    // What the screen reads, each time it looks (twice a second).

    /** The Soma thread: every entry, oldest first. */
    fun tasks(): List<ChatTask>

    /** Changes the entry sent at [time], and saves the thread when [save]. */
    fun updateTask(time: Long, save: Boolean, change: (ChatTask) -> ChatTask)

    /** Whether any phone builder works. */
    fun working(): Boolean

    /** Each phone builder's own state. */
    fun builders(): Map<BuilderId, BuilderState>

    /** Whether the next message to the builder follows up on the task it just did. */
    fun continuing(): Boolean

    fun front(): FrontState

    /** The Mac threads' requests waiting for his OK, oldest first. */
    fun macPending(): List<MacPending>

    /** What is last known of the Mac threads. */
    fun macView(): MacView

    /** The label a Mac thread's running entry shows. */
    fun macLabel(task: ChatTask): String

    /** The record of what was done in the Mac thread [threadId] before this device came in. Blocks: call off the main thread. */
    fun macEarlier(threadId: String): MacEarlierRead

    /** The computers set up for threads, in their order, each as it last answered. */
    fun hosts(): List<HostChoice>

    /** The default coding agent's name ("Claude Code"), or null with none. */
    fun agentName(): String?

    /** The default coding agent's command ("claude"), or null with none. */
    fun agentBin(): String?

    /** Whether the device's Linux is installed, as Compose state. */
    @Composable
    fun linuxInstalled(): Boolean

    /** Whether the front agent can be asked now. */
    fun frontReady(): Boolean

    /** Every thread as it stands this moment. */
    fun threads(): List<AgentThread>

    /** The threads with a tile or a dot now ([dev.omalauncher.widgets.liveThreads]), given the tabs he pinned and archived. */
    fun liveThreads(all: List<AgentThread>, pinned: Set<String>, archived: Set<String>): List<AgentThread>

    /** The tab book, after [change], saved when it changed. */
    fun changeBook(change: (TabMeta) -> TabMeta): TabMeta

    /** Names new chats in the background, once each. */
    fun checkTitles()

    /** Each phone builder's facts at the last look. */
    fun builderFacts(): Map<BuilderId, BuilderFacts>

    /** Looks at the phone builders again. Blocks: call off the main thread. */
    fun lookAtBuilders()

    // What he does.

    /** [prompt] with [images] (their paths for the agent) straight to the build lane, to [builder] or whichever is free. Throws with why it refused. */
    fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?)

    /** [prompt] to the front agent, in the new chat [chat] or the Soma stream (""). Throws [dev.omalauncher.assist.FrontQueueFull] when too many wait, or why it can't be asked. */
    fun askFront(prompt: String, chat: String)

    /** A new chat's first words, as they are, to a new thread on the Mac. */
    fun startMacChat(prompt: String, chat: String)

    /** [prompt] straight to the Mac thread [thread] ([name]), from the new chat [chat] or a thread's own tab (""). */
    fun sayToMac(prompt: String, thread: String, name: String, chat: String)

    /** Stops the Mac thread [thread]; [said] gets what there is to tell him. */
    fun stopMac(thread: String, said: (String?) -> Unit)

    /** Moves the Mac thread [thread] to another agent or model, with a handoff (cockpit/DESIGN.md §4). */
    fun switchMac(thread: String, to: dev.omalauncher.assist.MacPick)

    /** Sends the entry at [time] that the Mac never got again. */
    fun tryMacAgain(time: Long)

    /** His answer to a Mac thread's request; [done] gets how it went. */
    fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit)

    /** Opens a terminal with the default agent started on [prompt]. False with no agent. */
    fun runInTerminal(prompt: String): Boolean

    /** The front agent's answer's request, [prompt], handed to the build lane as new work, with [said] (the Soma stream) as context. Throws with why. */
    fun handOverToBuild(prompt: String, said: List<ChatTask>)

    /** Notes that [request] went somewhere else than it first did, for the routing log. */
    fun routingCorrected(request: String)

    /** His answer to a phone builder's request. */
    fun answerBuilder(entry: PendingApproval, allow: Boolean): ApprovalOutcome

    fun cancelFollowUp(time: Long)

    fun sendNow(time: Long)

    /** Stops [builder], or every builder with null. */
    fun stopBuilder(builder: BuilderId?)

    fun cancelFront(time: Long)

    fun stopFront()

    fun askFrontAgain(time: Long)

    /** Drops the requests waiting for the front agent, in the new chat [chat] or all of them (null). */
    fun dropFrontWaiting(chat: String?)

    /** Restarts a stuck [builder]; [said] gets what was done. */
    fun restartBuilder(builder: BuilderId, said: (String) -> Unit)

    /** Cleans up leftover builder processes; [said] gets what was done. */
    fun cleanBuilders(said: (String) -> Unit)

    /** Puts [text] on the clipboard and says so. */
    fun copy(text: String)

    // Pictures and words.

    /** The list of copied-in images, which a killed process's are swept from at the next start. */
    fun drafts(): ChatDrafts

    /** A picker for up to [max] pictures; picking hands them to [onPicked]. Returns what opens it. */
    @Composable
    fun rememberImagePicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit

    /** [picked] copied in for the agent. Blocks; throws with why it couldn't. */
    fun importImage(picked: PickedImage): PromptImage

    /** [file] made small, its longest side [longest] px, or null if it can't be read. Blocks. */
    fun thumbnail(file: File, longest: Int): ImageBitmap?

    /** Where the device keeps the picture a sent request names by [path]. */
    fun sentImage(path: String): File

    /** Speech into the field. */
    @Composable
    fun rememberVoice(): Voice

    /** Asks to use the microphone if it must, then calls [onGranted]. Returns what starts that. */
    @Composable
    fun rememberMicrophone(onGranted: () -> Unit): () -> Unit
}
