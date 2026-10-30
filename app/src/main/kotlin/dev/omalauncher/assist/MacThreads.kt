package dev.omalauncher.assist

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.omalauncher.App
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatStore
import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TabBook
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/*
 * The phone's view of the project threads on the Mac (docs/MAC-RUNNER-SPEC.md): which there are, what each
 * says while it works, which of its requests wait for Boss, and what becomes of a request handed to one. What a
 * thread's events make of the Oma thread, which approval a tap answers and when a request may be sent again
 * are plain functions in MacThreadsLogic.kt, in the chat module the Mac app shares, unit tested; MacThreads here
 * does what they say over MacRunnerClient.
 * A thread is known by its id everywhere: two threads can share a name and a project.
 */

/**
 * The guest's `oma threads`, which lists the Mac's threads and makes one in a folder Boss names. A command and
 * not a power of the front agent's: the phone's builder runs it under his approval rules, or he does in a
 * terminal, and the runner alone decides which folders it takes. Installed with the other guest tools.
 */
fun macGuestTools(context: Context, rootfs: File): List<Managed> =
    listOf(Managed(File(rootfs, "usr/local/lib/oma/thread"), Policy.Owned, executable = true) { context.assets.open("oma-thread.sh").use { it.readBytes() } })

/**
 * The Mac as this phone sees it: the shared [MacThreadsEngine] (cockpit slice 6), given the phone's part by
 * [AndroidThreadsPlatform]. Every call is the engine's; this object keeps the names the rest of the launcher uses.
 * With no config file there is no Mac: nothing is asked, the board has no Mac on it, and the launcher is as it was
 * before there were threads.
 */
object MacThreads {
    @Volatile private var made: MacThreadsEngine? = null

    private fun engine(context: Context): MacThreadsEngine =
        made ?: synchronized(this) { made ?: MacThreadsEngine(AndroidThreadsPlatform(context.applicationContext)).also { made = it } }

    val view: MacView get() = made?.view ?: MacView()

    /** The Mac's requests waiting for Boss, oldest first. One immutable list, as [AgentTasks.pending] is, for the same readers. */
    val pending: List<MacPending> get() = made?.pending.orEmpty()

    /** The entries whose words are on their way to the Mac right now. */
    val sending: Set<Long> get() = made?.sending.orEmpty()

    /** Whether a thread on the Mac is being followed now: its answer or its request to approve is still to come. */
    val following: Boolean get() = made?.following == true

    fun kindsOn(machine: String): List<String> = made?.kindsOn(machine).orEmpty()

    /** The models the computer [machine]'s runner listed, by agent (Venice's). */
    fun modelsOn(machine: String): Map<String, List<String>> = made?.modelsOn(machine).orEmpty()

    /** What a new chat's thread on [machine] uses when he picks nothing ([MacThreadsEngine.defaultPick]); null before the engine is made. */
    fun defaultPick(machine: String): MacPick? = made?.defaultPick(machine)

    /** Where the runner [machine] is reached ([MacThreadsEngine.hostAddress]); empty before the engine is made. */
    fun hostAddress(machine: String): String = made?.hostAddress(machine).orEmpty()

    fun hosts(context: Context): List<MacSeen> = engine(context).hosts()

    /** Projects' handoffs, each from the first computer that has it ([MacThreadsEngine.handoffs]). Blocking. */
    fun handoffs(context: Context, projects: Collection<String>): Map<String, MacHandoff?> = engine(context).handoffs(projects)

    /** The project registry from the first computer that gives it; null when none does. Blocking. */
    fun projects(context: Context): List<RegisteredProject>? = engine(context).projects()

    fun restore(context: Context) = engine(context).restore()

    fun open(context: Context) = engine(context).open()

    fun board(context: Context, now: Long): MacBoard? = engine(context).board(now)

    fun glance(context: Context, now: Long) = engine(context).glance(now)

    fun label(task: ChatTask): String = made?.label(task) ?: macEntryLabel(task.status, false, false, false, task.fresh)

    fun refresh(context: Context) = engine(context).refresh()

    fun handOver(context: Context, asked: Long, prompt: String, thread: MacThread, away: Boolean, fresh: Boolean = false, newChat: Boolean = false) =
        engine(context).handOver(asked, prompt, thread, away, fresh, newChat)

    fun say(context: Context, prompt: String, threadId: String, name: String, chat: String = "", images: List<java.io.File> = emptyList(), shown: List<String> = emptyList()) = engine(context).say(prompt, threadId, name, chat, images, shown)

    fun artifactReply(context: Context, prompt: String, threadId: String, name: String, delivered: (Boolean) -> Unit) =
        engine(context).say(prompt, threadId, name, delivered = delivered)

    fun tryAgain(context: Context, time: Long) = engine(context).tryAgain(time)

    fun startChat(context: Context, asked: Long, prompt: String, away: Boolean, chat: String) = engine(context).startChat(asked, prompt, away, chat)

    fun startChatDirect(context: Context, prompt: String, chat: String, images: List<java.io.File> = emptyList(), shown: List<String> = emptyList()) = engine(context).startChatDirect(prompt, chat, images, shown)

    fun artifact(context: Context, thread: String, path: String) = engine(context).artifact(thread, path)

    /** The artifacts the threads' events spoke of; none before the engine is made. */
    fun liveArtifacts(): List<dev.omalauncher.oma.ArtifactLive> = made?.liveArtifacts().orEmpty()

    fun artifactVersions(context: Context, thread: String, name: String) = engine(context).artifactVersions(thread, name)

    fun earlier(context: Context, threadId: String): MacEarlierRead = engine(context).earlier(threadId)

    fun stop(context: Context, threadId: String, told: (String?) -> Unit) = engine(context).stop(threadId, told)

    fun botFiles(context: Context, threadId: String, told: (MacBotFiles?) -> Unit) = engine(context).botFiles(threadId, told)

    fun freshBot(context: Context, threadId: String, said: (String) -> Unit) = engine(context).freshBot(threadId, said)

    fun switchTo(context: Context, threadId: String, to: MacPick) = engine(context).switchTo(threadId, to)

    fun answerQuestions(context: Context, ticket: String, answers: org.json.JSONObject, told: (MacApprovalOutcome) -> Unit) = engine(context).answerQuestions(ticket, answers, told)
    fun matchQuestions(context: Context, onThread: String, card: org.json.JSONObject, answers: org.json.JSONObject, where: String,
                       answeredThread: String?, answeredApproval: String?) = engine(context).matchQuestions(onThread, card, answers, where, answeredThread, answeredApproval)

    fun answer(context: Context, ticket: String, allow: Boolean, told: (MacApprovalOutcome) -> Unit) = engine(context).answer(ticket, allow, told)
    fun rememberCommand(context: Context, ticket: String, told: (MacApprovalOutcome) -> Unit) = engine(context).rememberCommand(ticket, told)
    fun forgetCommands(context: Context, threadId: String, told: (String) -> Unit) = engine(context).forgetCommands(threadId, told)
}

/** The phone's part of following the threads: the calls MacThreads made itself before the engine was shared. */
class AndroidThreadsPlatform(private val context: Context) : ThreadsPlatform {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    override fun machines(): List<MacMachine> = runCatching { readMacMachines((context as App).runtime.proot.launcherConfig) }.getOrNull().orEmpty()

    override fun chatLog(): ChatStore = ChatLog.of(context)

    override fun book(): TabMeta = TabBook.of(context)

    override fun changeBook(change: (TabMeta) -> TabMeta): TabMeta = TabBook.change(context, change)

    override fun prefString(key: String): String? = prefs.getString(key, null)

    override fun prefLong(key: String): Long = prefs.getLong(key, 0L)

    override fun putPref(key: String, value: String) = prefs.edit().putString(key, value).apply()

    override fun putPref(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    override val stampLock: Any get() = AgentTasks

    override fun clearStaleCards() = AgentNotices.clearStaleMac(context)

    override fun requestCard(entry: MacPending) = AgentNotices.macRequest(context, entry)

    override fun withdrawCard(entry: MacPending) = AgentNotices.macWithdraw(context, entry)

    override fun answeredNotice(label: String, answer: String, front: Boolean) = AgentNotices.answered(context, label, answer, if (front) AgentNotices.FRONT else AgentNotices.MAC)

    override fun foreground(): Boolean = dev.omalauncher.oma.OwnScreens.visible &&
        context.getSystemService(android.os.PowerManager::class.java).isInteractive && !locked()

    override fun locked(): Boolean = AgentNotices.deviceLocked(context)

    override fun linkNote(now: Long): String? = TailscaleHeal.note(now)

    override fun linkAfter(link: MacLink) = TailscaleHeal.after(context, link)

    override fun statusChanged() = BuilderWatch.macChanged(context)

    override fun threadsChanged() = AgentTasks.macChanged(context)

    override fun routingCorrected(request: String, was: String) = RoutingLog.corrected(context, request, was)

    override fun post(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        const val PREFS = "mac-threads"
    }
}
