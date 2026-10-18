package dev.omalauncher.canvas

import dev.omalauncher.assist.projectOf
import dev.omalauncher.assist.NO_PROJECT
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PHONE_THREAD_B
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.builderApprovalTitle
import dev.omalauncher.assist.macApprovalDetail
import dev.omalauncher.assist.macApprovalNotice
import dev.omalauncher.assist.macThreadKey
import dev.omalauncher.assist.MacHandoff
import dev.omalauncher.assist.RegisteredProject
import dev.omalauncher.oma.ChatHost
import dev.omalauncher.oma.TabMeta

/*
 * Cockpit modules (Soma Canvas, cockpit/canvas/PRD.md §4): live views over shared state that can sit anywhere on any
 * screen's canvas, each drawn at four sizes that are only cell counts. This file is what every module reads from and
 * how a module's size follows from its cells; the modules themselves are composables beside it.
 */

/** A module's size, from the cells it is given: a mark, one row, a box, or most of the page. */
enum class ModuleSize { Dot, Line, Card, Full }

/**
 * The size [w] by [h] cells is on a grid of [columns]: 1×1 is a dot, one row is a line, and a box at least two thirds of the
 * grid wide and three rows tall (on the Fold's six columns: five wide) is full; anything else is a card.
 */
fun moduleSize(w: Int, h: Int, columns: Int): ModuleSize = when {
    w <= 1 && h <= 1 -> ModuleSize.Dot
    h <= 1 -> ModuleSize.Line
    h >= 3 && w >= if (columns <= 6) 5 else (columns * 2 + 2) / 3 -> ModuleSize.Full
    else -> ModuleSize.Card
}

/**
 * Something that waits for Boss, from any thread: a thread's tool on a computer, or a phone builder's. [title] and [detail]
 * are what the chat's own card says; [threadKey] is the tab the thread has; [ref] is what answering needs, kept opaque.
 */
/**
 * Whether [this] is answered through an approval ticket (a Mac thread's, or the phone builder's, one-time), not a widget
 * file's or command's gate: the only asks the car answers (slice 9b review).
 */
val ModuleAsk.ticketed: Boolean get() = ref is dev.omalauncher.assist.MacPending || ref is PendingApproval

data class ModuleAsk(
    val id: String, val title: String, val detail: String, val threadKey: String, val where: String, internal val ref: Any,
    /**
     * What is approved is [detail] itself (a command a widget would run): approve and deny are offered only where it is
     * shown whole, never beside a line cut short.
     */
    val wholeDetail: Boolean = false,
)

/**
 * What the modules read and the one thing they do: answer an ask, on his tap. Needs you lists approvals, the things he can
 * answer from a module; a thread that needs attention (its last request failed) shows in Threads and opens its chat.
 */
interface ModuleSource {
    fun threads(): List<AgentThread>

    fun asks(): List<ModuleAsk>

    /** His tap on approve or deny; [said] gets what there is to tell him, or null when there is nothing. */
    fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit)

    /** Reads what the slower modules show (herd, the pocket, handoffs) once; blocking, so off the screen's thread. */
    fun refresh(projects: Collection<String> = emptyList()) {}

    /** The computers, as of the last [refresh]. */
    fun computers(): List<ModuleComputer> = emptyList()

    /** Project [project]'s handoff, as of the last [refresh] that named it. */
    fun handoff(project: String): MacHandoff? = null

    /** Desktop sessions (in [project], or every project), as of the last [refresh]. */
    fun sessions(project: String? = null): List<HerdSession> = emptyList()

    fun pocket(): List<PocketItem> = emptyList()

    /** The chat's own entries as a timeline, newest first. */
    fun events(): List<ModuleEvent> = emptyList()

    /** What a new chat would start on; null with no computer set up. */
    fun newThread(): NewThreadPick? = null

    /** Project [name]: its folder, its threads' states and its handoff's next line, as of the last [refresh] that named it. */
    fun project(name: String): ModuleProject = ModuleProject(name, "", emptyList(), "", null)

    /** The projects that have threads on the computers now, by their registry names, sorted: what a project's module can be added for. Empty before the registry is read. */
    fun projects(): List<String> = emptyList()

    /** His tap on nudge: agent mail to that session; [done] says whether it was taken. Blocking work runs off the caller's thread. */
    fun nudge(session: HerdSession, done: (Boolean) -> Unit) = done(false)
}

/** How many reads of the modules pass between asks for the project registry. */
private const val REGISTRY_EVERY = 10

/** The tab a Mac thread is shown as: the new chat it was made for, else its own. */
fun macTabKey(threadId: String, meta: TabMeta): String = meta.chatOnMac(threadId)?.key ?: macThreadKey(threadId)

/**
 * The modules' source on a device that has the chat: its threads and its waiting approvals, answered through the very
 * paths the chat's own approval cards use ([ChatHost.answerMac], [ChatHost.answerBuilder]), so the guards, the one-time
 * nonces and the expiry are the same wherever he taps.
 */
fun chatHostModules(
    host: ChatHost,
    feeds: CockpitFeeds? = null,
    handoffs: (Collection<String>) -> Map<String, MacHandoff?> = { emptyMap() },
    registry: () -> List<RegisteredProject>? = { null },
): ModuleSource = object : ModuleSource {
    @Volatile private var herd: HerdSnapshot? = null
    @Volatile private var items: List<PocketItem> = emptyList()
    @Volatile private var read: Map<String, MacHandoff?> = emptyMap()
    @Volatile private var registered: List<RegisteredProject> = emptyList()
    @Volatile private var reads = 0

    override fun threads(): List<AgentThread> = host.threads()

    override fun refresh(projects: Collection<String>) {
        if (feeds != null) {
            // What herd says now, or nothing: an old snapshot is never shown as if it were current.
            herd = feeds.herd()
            items = feeds.pocket()
        }
        read = if (projects.isEmpty()) emptyMap() else runCatching { handoffs(projects) }.getOrDefault(emptyMap())
        // The registry as a computer last gave it: kept when none answers, since it changes rarely and is not live state, and
        // asked again only every few reads, so a computer that is down doesn't cost a timeout on every one.
        if (reads++ % REGISTRY_EVERY == 0 || registered.isEmpty()) runCatching { registry() }.getOrNull()?.let { registered = it }
    }

    override fun events(): List<ModuleEvent> = moduleTimeline(host.tasks(), host.changeBook { it })

    override fun newThread(): NewThreadPick? = moduleNewThread(host.hosts())

    // Only by the registry's names: before a computer has given the registry, a folder's own name could be offered and
    // then stand for nothing once it is read.
    override fun projects(): List<String> = if (registered.isEmpty()) emptyList() else host.macView().threads.map { projectOf(it.project, registered) }.filter { it != NO_PROJECT }.distinct().sorted()

    override fun project(name: String): ModuleProject = moduleProject(name, registered, host.macView().threads, host.threads(), read[name], host.changeBook { it })

    override fun computers(): List<ModuleComputer> = moduleComputers(host.hosts().map { ComputerHost(it.id, it.name, it.reachable, it.address) }, herd?.machines.orEmpty())

    override fun handoff(project: String): MacHandoff? = read[project]

    override fun sessions(project: String?): List<HerdSession> = desktopSessions(herd, project)

    override fun pocket(): List<PocketItem> = items

    override fun nudge(session: HerdSession, done: (Boolean) -> Unit) {
        val sender = feeds ?: return done(false)
        kotlin.concurrent.thread(name = "soma-nudge", isDaemon = true) { done(sender.nudge(session.machine, session.id)) }
    }

    override fun asks(): List<ModuleAsk> {
        val book = host.changeBook { it }
        val mac = host.macPending().map { p ->
            ModuleAsk("mac:${p.ticket}", "Allow ${p.request.tool} on ${p.threadName}?", macApprovalDetail(p.request), macTabKey(p.threadId, book), p.threadName, p)
        }
        val phone = host.builders().flatMap { (builder, state) ->
            state.pending.map { p ->
                ModuleAsk("phone:${p.nonce}", builderApprovalTitle(p.approval.tool, builder), p.approval.detail, if (builder == BuilderId.A) PHONE_THREAD else PHONE_THREAD_B, "Builder ${builder.letter}", p)
            }
        }
        return mac + phone
    }

    override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) {
        when (val ref = ask.ref) {
            is dev.omalauncher.assist.MacPending -> host.answerMac(ref.ticket, allow) { said(macApprovalNotice(it)) }
            is PendingApproval -> said(
                when (host.answerBuilder(ref, allow)) {
                    ApprovalOutcome.Decided -> null
                    ApprovalOutcome.Expired -> "That request has expired"
                    ApprovalOutcome.Locked -> "Unlock your phone to answer that request"
                },
            )
            else -> said("That request can't be answered here")
        }
    }
}

/** The order states are shown in, most urgent first, with the words a heading uses. */
val MODULE_STATES = listOf(
    ThreadState.NeedsOk to "needs your OK",
    ThreadState.Attention to "needs attention",
    ThreadState.Working to "working",
    ThreadState.Queued to "queued",
    ThreadState.Idle to "resting",
)

/** The most urgent state among [threads]; idle with none. */
fun worstState(threads: List<AgentThread>): ThreadState = MODULE_STATES.map { it.first }.firstOrNull { s -> threads.any { it.state == s } } ?: ThreadState.Idle
