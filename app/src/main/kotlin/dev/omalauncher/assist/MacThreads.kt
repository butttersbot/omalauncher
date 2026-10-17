package dev.omalauncher.assist

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.omalauncher.App
import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TabBook
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.chatKey
import dev.omalauncher.oma.onMac
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

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
 * The Mac as this phone sees it. Nothing here runs on the caller's thread except reading what is already
 * known: asking the Mac, sending, following and answering each have a thread of their own. Those threads live
 * as the phone builder's task does, in the process the Linux foreground service keeps alive, so a thread is
 * followed whether or not the chat is open. With no config file there is no Mac: nothing is asked, the board
 * has no Mac on it, and the launcher is as it was before there were threads.
 */
object MacThreads {
    @Volatile var view = MacView(); private set

    /** The Mac's requests waiting for Boss, oldest first. One immutable list, as [AgentTasks.pending] is, for the same readers. */
    @Volatile var pending: List<MacPending> = emptyList(); private set

    /** The entries whose words are on their way to the Mac right now. */
    @Volatile var sending: Set<Long> = emptySet(); private set

    private val book = MacApprovalBook()
    @Volatile private var cursors = MacCursors()
    /** The agents each Mac has, by its id, as its `/health` last said; none until it has. */
    @Volatile private var kinds: Map<String, List<String>> = emptyMap()
    /** The agents heard to be at their limit lately, kept in prefs under "limits". Only touched under the lock. */
    private val limits = MacLimits()
    /**
     * What each entry was last sent, his words with whatever went ahead of them, so that a turn ending at a limit
     * is sent on exactly as it went. In memory only: after a restart the same is made again from the thread.
     */
    private val sentWords = HashMap<Long, String>()
    private val followers = HashMap<String, Follower>()

    /** Whether a thread on the Mac is being followed now: its answer or its request to approve is still to come. */
    val following: Boolean get() = synchronized(this) { followers.isNotEmpty() }

    /** Per entry, when its sending ended by [clock]; [IN_FLIGHT] until then. An entry not in here was sent by an earlier run. */
    private val sentAt = HashMap<Long, Long>()
    private val clock = AtomicLong()
    private val answering = HashSet<String>()
    private val refreshing = AtomicBoolean()
    private var restored = false
    @Volatile private var checked = 0L
    private val main = Handler(Looper.getMainLooper())

    /** One thread's follower. [renewed] is set when it is asked for again, so one about to leave stays. */
    private class Follower { var renewed = false }

    private const val PREFS = "mac-threads"
    private const val IN_FLIGHT = Long.MAX_VALUE
    private const val STALE_MS = 60_000L
    private const val RESTING_LISTEN_MS = 60_000L
    private const val NOT_SENT = "Not sent"

    /** Read fresh each time, so a Mac is there the moment Boss puts its file in place and gone when he takes it out. */
    private fun machines(context: Context): List<MacMachine> =
        runCatching { readMacMachines((context.applicationContext as App).runtime.proot.launcherConfig) }.getOrNull().orEmpty()

    /** The Mac the thread [threadId] is on, as set up now; null when its file is gone. */
    private fun machine(context: Context, threadId: String): MacMachine? = machines(context).firstOrNull { it.id == macMachineOf(threadId) }

    /** The runner the thread [threadId] is on. One whose Mac is no longer set up answers nothing ([MacAnswer.Unreachable]). */
    private fun client(context: Context, threadId: String) = macClient(machine(context, threadId))

    private fun kindsOf(machine: String) = kinds[machine].orEmpty()

    /** The threads on the same Mac as [threadId]: work on one Mac is never placed with, beside or instead of another's. */
    private fun sameMac(threadId: String, threads: List<MacThread> = view.threads) = threads.filter { macMachineOf(it.id) == macMachineOf(threadId) }

    /** Where when each Mac was last seen is kept; the first Mac's under the name it always had. */
    private fun seenKey(machine: String) = if (machine == MAC_FIRST) "seen" else "seen:$machine"

    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cursors = MacCursors(macCursorsFrom(saved.getString("cursors", null)))
        val macs = machines(context).map { MacSeen(it.id, it.name, seen = saved.getLong(seenKey(it.id), 0L).takeIf { seen -> seen > 0 }) }
        view = macWith(MacView(seen = saved.getLong("seen", 0L).takeIf { it > 0 }), macs)
        limits.restore(saved.getString("limits", null), System.currentTimeMillis())
        // No card of an earlier run can be answered: its ticket died with that process. They are drawn again from the Mac.
        AgentNotices.clearStaleMac(context)
    }

    /** The chat opened, or the launcher started: learn how the Mac stands and pick its working threads up again. */
    fun open(context: Context) {
        restore(context)
        refresh(context)
    }

    /**
     * The Mac's part of the front agent's board at [now], from what is already known; null with no Mac set up.
     * It never waits for the Mac: a list that has grown old is asked for again beside this request, for the next one.
     */
    fun board(context: Context, now: Long): MacBoard? {
        restore(context)
        val machines = machines(context)
        if (machines.isEmpty()) return null
        if (now - checked > STALE_MS) refresh(context)
        val known = view
        val tasks = ChatLog.of(context).tasks
        return macBoard(known.threads.map { macOwnSummary(it, tasks) }, known.reachable, known.seen, now, TailscaleHeal.note(now), macSeen(machines, known))
    }

    /**
     * Home is showing its thread tiles at [now]: a list grown old is asked for again, as [board] does for the
     * front agent, so a thread that began work at the Mac itself gets its tile. With no Mac set up nothing is asked.
     */
    fun glance(context: Context, now: Long) {
        restore(context)
        if (now - checked > STALE_MS && machines(context).isNotEmpty()) refresh(context)
    }

    /** What stands beside [task], an entry of the Mac's, while it is not over. */
    fun label(task: ChatTask): String = macEntryLabel(task.status, task.time in sending, view.reachableFor(task.thread), pending.any { it.threadId == task.thread }, task.fresh)

    /** What asking the Mac [machine]'s `/health` came to: whether it answered, and which agents it has when it did. */
    private fun heard(context: Context, machine: String, health: MacHealthResult) {
        if (health is MacHealthResult.Up) kinds = kinds + (machine to health.health.kinds)
        reached(context, machine, health is MacHealthResult.Up)
        // Only a Mac that does not answer while none other does says anything about this phone's Tailscale.
        if (health is MacHealthResult.Up || view.macs.none { it.reachable && it.id != machine }) TailscaleHeal.after(context, macLink(health))
    }

    /** The agents taken to be at their limit now. */
    private fun limited(): Set<String> = synchronized(this) { limits.at(System.currentTimeMillis()) }

    /** Whether the Mac [machine] answered just now. The others stand as they were: one out of reach hides no other. */
    private fun reached(context: Context, machine: String, reachable: Boolean) {
        val now = System.currentTimeMillis()
        val machines = machines(context)
        see(context) { known ->
            macWith(known, macSeen(machines, known).map { if (it.id != machine) it else if (reachable) it.copy(reachable = true, seen = now) else it.copy(reachable = false) })
        }
        if (reachable) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(seenKey(machine), now).apply()
    }

    /**
     * Changes what the phone knows of the Mac. Anything more than when it last answered goes to the status file at
     * once, so `oma status` says what a thread does now and not what it did at the builder's last look.
     */
    private fun see(context: Context, change: (MacView) -> MacView) {
        val moved = synchronized(this) {
            val before = view
            view = change(before)
            before.copy(seen = null) != view.copy(seen = null)
        }
        if (!moved) return
        BuilderWatch.macChanged(context)
        told(context)
    }

    /** One thread as the Mac just described it, put into the list the board is made from. */
    private fun noted(context: Context, thread: MacThread) = see(context) { known ->
        known.copy(threads = if (known.threads.any { it.id == thread.id }) known.threads.map { if (it.id == thread.id) thread else it } else known.threads + thread)
    }

    private fun name(context: Context, threadId: String): String =
        view.threads.firstOrNull { it.id == threadId }?.let { macName(it.name) }
            ?: ChatLog.of(context).tasks.lastOrNull { it.thread == threadId }?.threadName?.takeIf { it.isNotEmpty() }
            ?: macName("")

    private fun unfinished(context: Context, threadId: String) = ChatLog.of(context).tasks.any { macUnfinished(it, threadId) }

    private fun saveCursors(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cursors", macCursorsText(cursors.snapshot())).apply()

    /** Under the lock: republishes the book for readers on other threads, and the tiles, ring and lock widget count its requests. */
    private fun published(context: Context) {
        pending = book.live
        told(context)
    }

    /** The Mac's work may look different now: the tiles, the ring and the lock widget are told, off this thread and lock. */
    private fun told(context: Context) = AgentTasks.macChanged(context)

    /**
     * Asks the Mac for its threads, off the caller's thread and one asking at a time. The threads [macFollowed]
     * names are then followed: those at work, those owed a request of his, and those of his that may still say
     * something between his messages or said something nobody heard.
     */
    fun refresh(context: Context) {
        restore(context)
        val app = context.applicationContext
        if (!refreshing.compareAndSet(false, true)) return
        thread(name = "oma-mac-refresh", isDaemon = true) {
            try {
                val machines = machines(app)
                if (machines.isEmpty()) noMac(app)
                else {
                    checked = System.currentTimeMillis()
                    gone(app, machines.map { it.id }.toSet())
                    // Each Mac on its own: one that does not answer leaves the others' threads as they are heard.
                    val links = mutableListOf<MacLink>()
                    val threads = mutableListOf<MacThread>()
                    for (machine in machines) {
                        val client = macClient(machine)
                        val health = client.health()
                        links += macLink(health)
                        if (health is MacHealthResult.Up) kinds = kinds + (machine.id to health.health.kinds)
                        val listed = if (health is MacHealthResult.Up) client.threads() else MacAnswer.Unreachable
                        if (listed is MacAnswer.Ok) {
                            threads += listed.value
                            see(app) { known ->
                                macWith(known.copy(threads = known.threads.filter { macMachineOf(it.id) != machine.id } + listed.value),
                                    macSeen(machines, known).map { if (it.id == machine.id) it.copy(listed = true) else it })
                            }
                        }
                        reached(app, machine.id, listed is MacAnswer.Ok)
                    }
                    // Also how the launcher's start turns Tailscale back on after a reboot: Runtime.start opens this.
                    TailscaleHeal.after(app, links.firstOrNull { it == MacLink.Answered } ?: links.first())
                    val tasks = ChatLog.of(app).tasks
                    val owed = tasks.filter { it.mac && (it.status == TaskStatus.Running || it.status == TaskStatus.Queued) }.map { it.thread }
                    val his = tasks.filter { it.mac && !it.fresh }.map { it.thread }.toSet()
                    for (threadId in macFollowed(threads, owed, his, cursors::after, System.currentTimeMillis())) follow(app, threadId)
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * Macs whose files are gone while others stay, [kept] being the ids of those set up: their threads leave the
     * list, and what was unfinished on them says so, as [noMac] says for all of them.
     */
    private fun gone(context: Context, kept: Set<String>) {
        val lost = view.threads.map { macMachineOf(it.id) }.filter { it !in kept }.toSet() +
            ChatLog.of(context).tasks.filter { it.mac && it.thread != MAC_NEW_THREAD && macUnfinished(it, it.thread) }.map { macMachineOf(it.thread) }.filter { it !in kept }
        if (lost.isEmpty()) return
        ChatLog.of(context).revise { tasks -> tasks.map { it.thread }.distinct().filter { macMachineOf(it) in lost && it != MAC_NEW_THREAD }.fold(tasks) { all, id -> macAbandon(all, id, MAC_NOT_SET_UP) } }
        val dropped = synchronized(this) { book.live.filter { macMachineOf(it.threadId) in lost }.flatMap { book.drop(it.threadId) }.also { published(context) } }
        dropped.forEach { AgentNotices.macWithdraw(context, it) }
        see(context) { known -> known.copy(threads = known.threads.filter { macMachineOf(it.id) !in lost }) }
    }

    /** The config is gone: there is no Mac to follow or answer, and what was unfinished there says so. */
    private fun noMac(context: Context) {
        ChatLog.of(context).revise { macAbandon(it, null, MAC_NOT_SET_UP) }
        val gone = synchronized(this) { book.clear().also { published(context) } }
        see(context) { MacView() }
        gone.forEach { AgentNotices.macWithdraw(context, it) }
    }

    /**
     * Hands [prompt], his words exactly, to [thread]. The entry sent at [asked] becomes that thread's before
     * anything is sent; the Mac is then asked how it is and sent the words on a thread of its own. [away] is a
     * request spoken on the listening screen: if it cannot be sent he is told over whatever is in front.
     * [fresh] is separate work on the project [thread] is busy with, which goes where [place] says; [newChat] is
     * the first words of a new chat, which always get a new thread on that project. Either way, while the agent
     * it would go to is known to be at its limit, another takes it ([deliver]).
     */
    fun handOver(context: Context, asked: Long, prompt: String, thread: MacThread, away: Boolean, fresh: Boolean = false, newChat: Boolean = false) {
        restore(context)
        val app = context.applicationContext
        if (fresh || newChat) return place(app, asked, prompt, thread, away, newChat)
        deliver(app, asked, prompt, thread, away)
    }

    /**
     * Gives the request filed at [time] to [thread], with [step] on its entry. While the agent [thread] runs is
     * known to be at its limit and another the Mac has is not ([macInstead]), it goes to that one instead, on the
     * same project: to a thread of that agent there with nothing to do, or to a new one made for it, and the entry
     * says why in place of [step].
     */
    private fun deliver(context: Context, time: Long, prompt: String, thread: MacThread, away: Boolean, step: String? = null) {
        val log = ChatLog.of(context)
        val machine = macMachineOf(thread.id)
        val instead = macInstead(thread.kind, kindsOf(machine), limited())
        if (instead == null) {
            log.update(time, save = true) { macHandOver(it, thread).let { entry -> if (step == null) entry else ChatHistory.withStep(entry, step) } }
            return send(context, time, sentText(context, time, prompt, thread.id), thread.id, away)
        }
        val why = macLimitedStep(thread.kind, instead)
        val free = macKindThread(thread.project, instead, sameMac(thread.id), macOwed(log.tasks, except = time))
        if (free != null) {
            log.update(time, save = true) { ChatHistory.withStep(macHandOver(it, free), why) }
            return send(context, time, sentText(context, time, prompt, free.id), free.id, away)
        }
        val name = macKindThreadName(thread.name, instead, sameMac(thread.id).map { it.name })
        log.update(time, save = true) { ChatHistory.withStep(macFreshHandOver(it, thread, name), why) }
        make(context, time, MacFresh.Make(name, thread.project), away, instead, machine) { sentText(context, time, prompt, it) }
    }

    /**
     * Separate work for the project of [busy], filed at [time]: [macFresh] decides from the threads as last heard,
     * with [busy] as the Mac last described it. A thread that is free takes it as a hand-over; past the limit it
     * waits on a working one and the entry says why; otherwise a new thread is made and the words go to that. A
     * new thread runs the first agent the Mac has that is not known to be at its limit, and is named after it
     * when that is not Claude.
     */
    private fun place(context: Context, time: Long, prompt: String, busy: MacThread, away: Boolean, newChat: Boolean = false) {
        val log = ChatLog.of(context)
        val threads = sameMac(busy.id).let { known -> if (known.any { it.id == busy.id }) known else known + busy }
        val named = threads.first { it.id == busy.id }
        val machine = macMachineOf(busy.id)
        when (val place = macFresh(named, threads, macOwed(log.tasks, except = time), newChat)) {
            is MacFresh.Same -> deliver(context, time, prompt, place.thread, away)
            is MacFresh.Queue -> deliver(context, time, prompt, place.thread, away, macCappedStep(place.working, macFolder(named.project)))
            is MacFresh.Make -> {
                val limited = limited()
                val kind = macKind(kindsOf(machine), limited) ?: macInstalled(kindsOf(machine)).first()
                val name = if (kind == MAC_CLAUDE) place.name else macKindThreadName(named.name, kind, threads.map { it.name })
                // Said only when a limit is why: a thread of another agent's is otherwise simply made as Claude's.
                val why = macLimitedStep(named.kind, kind).takeIf { kind != macKindOf(named.kind) && macKindOf(named.kind) in limited }
                log.update(time, save = true) { macFreshHandOver(it, named, name).let { entry -> if (why == null) entry else ChatHistory.withStep(entry, why) } }
                make(context, time, place.copy(name = name), away, kind, machine) { sentText(context, time, prompt, it) }
            }
        }
    }

    /**
     * Makes the thread [place] names, on the Mac [machine], running the agent [kind], off the caller's thread, and sends it what [words]
     * makes for its id as any hand-over is sent. The entry counts as on its way the whole time, so nothing the
     * thread it waited under says can end it. A Mac that does not answer, turns the key down or refuses the
     * folder leaves the entry saying so with `[ try again ]`.
     */
    private fun make(context: Context, time: Long, place: MacFresh.Make, away: Boolean, kind: String, machine: String, words: (String) -> String) {
        synchronized(this) {
            sentAt[time] = IN_FLIGHT
            sending = sending + time
        }
        thread(name = "oma-mac-make", isDaemon = true) {
            val log = ChatLog.of(context)
            var made: MacThread? = null
            var problem = MAC_UNREACHABLE
            var sendOn = false
            try {
                val mac = machines(context).firstOrNull { it.id == machine }
                val client = macClient(mac)
                val health = client.health()
                if (mac != null) heard(context, machine, health)
                if (health !is MacHealthResult.Up) problem = macUnreachableReason(health)
                else {
                    val answer = client.makeThread(place.name, place.project, kind)
                    made = (answer as? MacAnswer.Ok)?.value
                    problem = macMadeReason(answer) ?: problem
                }
                made?.let { thread ->
                    noted(context, thread)
                    log.update(time, save = true) { macMade(it, thread) }
                    // Made for a new chat's words, its first or ones another agent's limit sent on: the chat lives here from now on.
                    log.tasks.firstOrNull { it.time == time }?.chat?.takeIf { it.isNotEmpty() }?.let { chat -> TabBook.change(context) { it.onMac(chat, thread.id) } }
                    // Ended meanwhile, as when the busy thread could no longer be followed: then nothing is sent.
                    sendOn = log.tasks.any { it.time == time && it.thread == thread.id && it.status == TaskStatus.Queued }
                }
            } finally {
                // One sent on stays on its way until [send] is done with it; for any other its time on the way ends here.
                if (!sendOn) synchronized(this) {
                    sentAt[time] = clock.incrementAndGet()
                    sending = sending - time
                }
            }
            val thread = made
            when {
                thread != null && sendOn -> send(context, time, words(thread.id), thread.id, away)
                thread == null -> {
                    log.update(time, save = true) { macUnsent(it, problem) }
                    if (away) AgentNotices.answered(context, NOT_SENT, problem, AgentNotices.FRONT)
                    refresh(context)
                }
                else -> refresh(context)
            }
        }
    }

    /**
     * His words typed on a thread's own tab in the chat: they go to the thread [threadId], called [name], as
     * they are. The front agent is not asked and nothing is put ahead of them. The entry is filed first, under
     * the build lane's lock as every entry's time is taken, and the words then go the way a hand-over's do:
     * the Mac is asked how it is, and one that does not answer leaves the entry saying so with `[ try again ]`.
     */
    fun say(context: Context, prompt: String, threadId: String, name: String, chat: String = "") {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val time = synchronized(AgentTasks) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { log.begin(macDirect(it, prompt, threadId, name).copy(chat = chat)) }
        }
        send(app, time, prompt, threadId, away = false)
    }

    /**
     * What the thread is sent for the request filed at [asked]: his words, behind what he and the front agent
     * said since that thread last got anything, and before that [MAC_SHARED_NOTE] while another thread works in
     * the same folder ([macShares]). The entry and the routing log are given [prompt] alone. Words he sent
     * straight to the thread go as they are, however often they are sent again.
     */
    private fun sentText(context: Context, asked: Long, prompt: String, threadId: String): String {
        val tasks = ChatLog.of(context).tasks
        if (tasks.any { it.time == asked && it.direct }) return prompt
        val shared = macShares(threadId, sameMac(threadId), macOwed(tasks, except = asked))
        // Only what was said where he asked: in his new chat, or in the Oma stream.
        val chat = tasks.firstOrNull { it.time == asked }?.chat.orEmpty()
        val said = tasks.filter { it.chat == chat }
        return handOverText(sharedPreface(shared, MAC_SHARED_NOTE, handOverPreface(handOverContext(said, asked) { threadGot(it, threadId) })), prompt)
    }

    /**
     * `[ try again ]` on a request the Mac never got or ended with nothing: the same step once more, and a line in the routing log.
     * One whose new thread was never made is placed again from how the threads stand now.
     */
    fun tryAgain(context: Context, time: Long) {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val task = log.tasks.firstOrNull { it.time == time }?.takeIf { macRetried(it) != null } ?: return
        log.update(time, save = true) { macRetried(it) ?: it }
        RoutingLog.corrected(app, task.prompt, MAC_TRIED_AGAIN)
        val busy = view.threads.firstOrNull { it.id == task.thread }
        // Sent again as it was, unless its agent is known to be at its limit, as when every agent was.
        if (!task.fresh) return if (busy != null) deliver(app, time, task.prompt, busy, away = false) else send(app, time, sentText(app, time, task.prompt, task.thread), task.thread, away = false)
        // A new chat's first words whose thread in the default folder was never made: it is made again.
        if (task.thread == MAC_NEW_THREAD) return makeForChat(app, time, task.prompt, away = false, task.chat)
        // A new chat's first words that never got their thread are given a new one again, never the busy one.
        val newChat = task.chat.isNotEmpty() && TabBook.of(app).chat(task.chat)?.bound == false
        if (busy != null) return place(app, time, task.prompt, busy, away = false, newChat)
        // Until the Mac has listed its threads in this run, a thread missing from the list is only not heard of yet.
        log.update(time, save = true) { macUnsent(it, if (view.listedFor(task.thread)) MAC_THREAD_GONE else MAC_UNREACHABLE) }
        refresh(app)
    }

    /**
     * Asks the Mac how it is, inside the two-second limit, and sends [text] if it answers. A Mac that does not
     * leaves the entry saying so with his words kept; nothing is given to the phone's builder instead. When the
     * words were sent and no answer came back, it is not known whether the Mac has them: the entry waits, and
     * the thread's follower finds out from the Mac itself.
     */
    private fun send(context: Context, time: Long, text: String, threadId: String, away: Boolean) {
        val kept = ChatLog.of(context).tasks.map { it.time }.toSet()
        // A thread never followed from here is followed from where it stood: its past would otherwise be read as this turn's.
        view.threads.firstOrNull { it.id == threadId }?.let { cursors.start(threadId, it.lastEvent) }
        synchronized(this) {
            sentAt[time] = IN_FLIGHT
            sending = sending + time
            // Only the entries the chat still keeps can be sent on.
            sentWords.keys.retainAll(kept)
            sentWords[time] = text
        }
        thread(name = "oma-mac-send", isDaemon = true) {
            val log = ChatLog.of(context)
            var problem: String? = MAC_UNREACHABLE
            try {
                val mac = machine(context, threadId)
                val client = macClient(mac)
                val health = client.health()
                if (mac != null) heard(context, mac.id, health)
                problem = if (mac == null) MAC_NOT_SET_UP else when (macRoute(ThreadHome.Mac, health, startedOnMac = false, phoneHasClone = false)) {
                    MacRoute.RunOnMac -> {
                        val taken = client.sendMessage(threadId, text)
                        if (taken is MacAnswer.Ok && taken.value == MacTaken.Now) log.update(time, save = true, change = ::macTaken)
                        macUnsentReason(taken)
                    }
                    else -> macUnreachableReason(health)
                }
            } finally {
                synchronized(this) {
                    sentAt[time] = clock.incrementAndGet()
                    sending = sending - time
                }
            }
            val why = problem
            if (why == null) follow(context, threadId)
            else {
                log.update(time, save = true) { macUnsent(it, why) }
                if (away) AgentNotices.answered(context, NOT_SENT, why, AgentNotices.FRONT)
            }
            refresh(context)
        }
    }

    /** Starts following [threadId], or tells the follower it has to stay. */
    private fun follow(context: Context, threadId: String) {
        val mine = synchronized(this) {
            val running = followers[threadId]
            if (running != null) {
                running.renewed = true
                return
            }
            Follower().also { followers[threadId] = it }
        }
        thread(name = "oma-mac-follow", isDaemon = true) {
            try {
                following(context, threadId, mine)
            } finally {
                synchronized(this) { if (followers[threadId] === mine) followers.remove(threadId) }
            }
        }
    }

    /** True when the follower may go; false when it was asked for again meanwhile and has to look once more. */
    private fun leave(mine: Follower, threadId: String): Boolean = synchronized(this) {
        if (mine.renewed) {
            mine.renewed = false
            false
        } else {
            followers.remove(threadId)
            true
        }
    }

    private fun pause(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }

    /**
     * Follows one thread until it rests with nothing of his unfinished in it and nothing more to say between his
     * messages ([macAwake]). Each round first asks the Mac how the thread stands, with no stream open, so what it
     * says of its last event, its waiting requests and its waiting turns is one moment's truth: a record that was
     * cleared resets the place, the cards are made to match, events missed are fetched, and a resting thread is
     * settled. A thread with a task is then listened to until its turn ends or the link drops; a resting one that
     * may still report, for a minute at a time. Nothing in here sends his words anywhere.
     */
    private fun following(context: Context, threadId: String, mine: Follower) {
        val log = ChatLog.of(context)
        var misses = 0
        val machine = macMachineOf(threadId)
        while (true) {
            val mac = machine(context, threadId)
            if (mac == null) {
                if (machines(context).isEmpty()) noMac(context) else abandon(context, threadId, MAC_NOT_SET_UP)
                return
            }
            val client = macClient(mac)
            val asked = clock.incrementAndGet()
            when (val known = client.thread(threadId)) {
                is MacAnswer.Ok -> {
                    val seen = known.value
                    reached(context, machine, true)
                    TailscaleHeal.after(context, MacLink.Answered)
                    noted(context, seen)
                    if (seen.lastEvent < cursors.after(threadId)) {
                        cursors.forget(threadId)
                        saveCursors(context)
                    }
                    synced(context, seen)
                    if (cursors.after(threadId) < seen.lastEvent) {
                        val page = client.events(threadId, cursors.after(threadId))
                        val fresh = if (page is MacAnswer.Ok) cursors.catchUp(threadId, page.value.events) else emptyList()
                        if (fresh.isEmpty()) pause(macRetryDelay(++misses))
                        else {
                            misses = 0
                            fresh.forEach { shown(context, threadId, it) }
                        }
                        continue
                    }
                    val resting = macResting(seen.status)
                    if (resting) {
                        val late = synchronized(this) { sentAt.filterValues { it >= asked }.keys.toSet() }
                        log.revise { tasks -> macSettle(tasks, threadId, seen.status, seen.waitingTurns) { it.time !in late } }
                        if (unfinished(context, threadId)) {
                            pause(macRetryDelay(++misses))
                            continue
                        }
                        // Nothing of his is left there, but the thread may still report on its own between his messages: it
                        // is listened to until it lists no work left running and its last turn is a while ago.
                        if (!macAwake(seen, System.currentTimeMillis()) || log.tasks.none { it.thread == threadId && !it.fresh }) {
                            if (leave(mine, threadId)) {
                                refresh(context)
                                return
                            }
                            continue
                        }
                    }
                    var heard = false
                    val turned = AtomicBoolean()
                    // A resting thread is listened to a while at a time, then asked again how it stands.
                    val until = System.currentTimeMillis() + RESTING_LISTEN_MS
                    val end = client.stream(threadId, cursors.after(threadId), { turned.get() || (resting && System.currentTimeMillis() > until) }) { event ->
                        if (cursors.offer(threadId, event) == MacOffer.Show) {
                            heard = true
                            shown(context, threadId, event)
                            if (macTurnsOver(event)) turned.set(true)
                        }
                    }
                    when (end.why) {
                        MacStreamWhy.Refused -> {
                            abandon(context, threadId, macRefusedText(end.code))
                            return
                        }
                        MacStreamWhy.Dropped -> if (heard) misses = 0 else pause(macRetryDelay(++misses))
                        MacStreamWhy.Gap, MacStreamWhy.Stopped -> misses = 0
                    }
                }
                MacAnswer.NotFound -> {
                    abandon(context, threadId, MAC_THREAD_GONE)
                    return
                }
                MacAnswer.Unauthorized -> {
                    abandon(context, threadId, macRefusedText(401))
                    return
                }
                else -> {
                    reached(context, machine, false)
                    // A thread waiting out a dark link is often where a reboot's Tailscale shows: it is healed from here too,
                    // unless another Mac answers, which says this phone's link is up.
                    if (known is MacAnswer.Unreachable && view.macs.none { it.reachable }) TailscaleHeal.after(context, MacLink.NoAnswer)
                    // Out of reach with nothing of his there and no card of its to keep: nothing to wait for.
                    if (!unfinished(context, threadId) && pending.none { it.threadId == threadId } && leave(mine, threadId)) return
                    pause(macRetryDelay(++misses))
                }
            }
        }
    }

    /** The thread cannot be followed: its entries say why, and its cards go, since they could not be answered. */
    private fun abandon(context: Context, threadId: String, reason: String) {
        ChatLog.of(context).revise { macAbandon(it, threadId, reason) }
        val gone = synchronized(this) { book.drop(threadId).also { published(context) } }
        gone.forEach { AgentNotices.macWithdraw(context, it) }
    }

    /** Makes the cards match the thread's own list of what waits, and the entries with them. */
    private fun synced(context: Context, seen: MacThread) {
        val change = synchronized(this) { book.sync(seen).also { published(context) } }
        val log = ChatLog.of(context)
        for (gone in change.removed) {
            AgentNotices.macWithdraw(context, gone)
            log.revise { macAnswered(it, seen.id, gone.request.approval, ApprovalAnswer.Unanswered) }
        }
        for (added in change.added) {
            log.revise { macAsked(it, seen.id, added.request) }
            AgentNotices.macRequest(context, added)
        }
    }

    /**
     * One new event of a thread, in order: into the Oma thread and onto disk first, then the place it was read
     * to, so a restart between the two shows an event twice rather than never. An approval becomes a card for
     * Boss and nothing else; nothing here answers one.
     */
    private fun shown(context: Context, threadId: String, event: MacEvent) {
        val log = ChatLog.of(context)
        val before = log.tasks
        // A turn that ended at its agent's limit goes on elsewhere before anything else is made of its end.
        if (macLimited(event)) fallBack(context, threadId)
        val flying = synchronized(this) { sentAt.filterValues { it == IN_FLIGHT }.keys.toSet() }
        // The reply is named after the agent and model the event says answered, the thread's agent when it says neither.
        val kind = view.threads.firstOrNull { it.id == threadId }?.kind.orEmpty()
        val by = when (event) {
            is MacEvent.Text -> macReplyName(event.kind.ifEmpty { kind }, event.model)
            is MacEvent.Result -> macReplyName(event.kind.ifEmpty { kind }, event.model)
            else -> null
        }.orEmpty()
        log.revise { macApply(it, threadId, event, flying, by) }
        when (event) {
            is MacEvent.Approval -> {
                val threadName = name(context, threadId)
                val added = synchronized(this) { book.offer(threadId, threadName, event.id, event.request).also { published(context) } }
                if (added != null) AgentNotices.macRequest(context, added)
            }
            is MacEvent.ApprovalResult -> {
                val gone = synchronized(this) { book.resolve(threadId, event.id, event.approval).also { published(context) } }
                if (gone != null) AgentNotices.macWithdraw(context, gone)
            }
            is MacEvent.Result, is MacEvent.Error, is MacEvent.Stopped -> {
                val gone = synchronized(this) { book.ended(threadId, event.id).also { published(context) } }
                gone.forEach { AgentNotices.macWithdraw(context, it) }
                // A request of his that just ended comes to him as the phone builder's does; one he stopped says nothing more.
                // Dropped here when every agent was at its limit or the turn ended empty, which needs him as a failure does.
                val ended = log.tasks.firstOrNull { now ->
                    now.thread == threadId && now.status in ENDINGS && before.any { it.time == now.time && it.status == TaskStatus.Running }
                }
                if (ended != null) AgentNotices.answered(context, if (ended.status == TaskStatus.Done) ended.threadName else "${ended.threadName} needs attention", ended.result, AgentNotices.MAC)
            }
            is MacEvent.Status -> see(context) { known -> known.copy(threads = known.threads.map { if (it.id == threadId) it.copy(status = event.status) else it }) }
            is MacEvent.Message, is MacEvent.Text, is MacEvent.Step, is MacEvent.Note, is MacEvent.Other -> Unit
        }
        saveCursors(context)
    }

    private val ENDINGS = setOf(TaskStatus.Done, TaskStatus.Failed, TaskStatus.Dropped)

    /**
     * The turn [threadId] was on ended at its agent's limit. The limit is remembered ([MAC_LIMIT_MEMORY_MS]), and
     * the request of his that turn was on goes, the same words with whatever went ahead of them the first time,
     * to a new thread of the next agent the Mac has that is not at its limit, on the same project; the entry
     * says so. With none left it needs his attention and can be tried again. A turn that was not his, or of a
     * thread whose project is not known, is left to end as any error does.
     */
    private fun fallBack(context: Context, threadId: String) {
        val log = ChatLog.of(context)
        val task = log.tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running } ?: return
        val thread = view.threads.firstOrNull { it.id == threadId }?.takeIf { it.project.isNotBlank() } ?: return
        val machine = macMachineOf(threadId)
        val from = macKindOf(thread.kind)
        val now = System.currentTimeMillis()
        val (next, words) = synchronized(this) {
            limits.hit(from, now)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("limits", limits.text(now)).apply()
            macKind(kindsOf(machine), limits.at(now)) to sentWords[task.time]
        }
        if (next == null) return log.update(task.time, save = true) { macAllLimitedEntry(it, kindsOf(machine)) }
        val name = macKindThreadName(thread.name, next, sameMac(threadId).map { it.name })
        val text = words ?: sentText(context, task.time, task.prompt, threadId)
        log.update(task.time, save = true) { macLimitHandOver(it, name, macLimitStep(from, next)) }
        make(context, task.time, MacFresh.Make(name, thread.project), away = false, next, machine) { text }
    }

    /**
     * The first words of the new chat [chat], filed at [asked], with no thread named for them: they go to a new
     * thread in [MAC_DEFAULT_PROJECT], called by the chat's name, and nothing asks him which project (Boss,
     * 2026-10-14: "i shouldn't be asked which project"). With no Mac set up it says so; with the Mac out of reach
     * his words are kept, unsent, with `[ try again ]` under them ([chatPlace]).
     */
    fun startChat(context: Context, asked: Long, prompt: String, away: Boolean, chat: String) {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val macs = macSeen(machines(app), view)
        val on = macDefaultMachine(macs)
        when (chatPlace(on != null, macs.any { it.id == on && it.reachable })) {
            ChatPlace.NotSetUp -> log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = MAC_NOT_SET_UP, status = TaskStatus.Failed) }
            ChatPlace.Unreachable -> {
                log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = MAC_CHAT_UNREACHABLE, status = TaskStatus.Dropped, answeredBy = OMA_NAME) }
                refresh(app)
            }
            ChatPlace.Start -> makeForChat(app, asked, prompt, away, chat, machine = on ?: MAC_FIRST)
        }
    }

    /**
     * The first words of the new chat [chat] when no front agent can read them ([dev.omalauncher.oma.Outgoing.ChatMac]):
     * filed as his, then sent as they are to a new thread in [MAC_DEFAULT_PROJECT] called by the chat's name or his
     * first words ([chatDirectName]). Nothing on the phone is needed: no Linux and no agent. With no Mac set up it
     * says so; a Mac out of reach leaves his words unsent with `[ try again ]`, which makes the thread again.
     */
    fun startChatDirect(context: Context, prompt: String, chat: String) {
        restore(context)
        val app = context.applicationContext
        val log = ChatLog.of(app)
        val name = chatDirectName(TabBook.of(app).names[chatKey(chat)], prompt)
        val setUp = machines(app).isNotEmpty()
        val time = synchronized(AgentTasks) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { time ->
                log.begin(if (setUp) macChatDirect(time, prompt, chat, name) else ChatTask(time, QUICK_AGENT, prompt, result = MAC_NOT_SET_UP, status = TaskStatus.Failed, direct = true, chat = chat))
            }
        }
        if (setUp) makeForChat(app, time, prompt, away = false, chat, name)
    }

    /**
     * Makes the thread for the new chat [chat] in [MAC_DEFAULT_PROJECT] and sends it the words filed at [time]. It
     * runs the first agent the Mac has that is not known to be at its limit, named after it when that is not Claude.
     * It is called [given], or else by the chat's name as it stands.
     */
    private fun makeForChat(context: Context, time: Long, prompt: String, away: Boolean, chat: String, given: String? = null, machine: String? = null) {
        val name = given ?: chatThreadName(TabBook.of(context).names[chatKey(chat)])
        val on = machine ?: macDefaultMachine(macSeen(machines(context), view)) ?: MAC_FIRST
        val kind = macKind(kindsOf(on), limited()) ?: macInstalled(kindsOf(on)).first()
        val named = if (kind == MAC_CLAUDE) name else macKindThreadName(name, kind, view.threads.filter { macMachineOf(it.id) == on }.map { it.name })
        ChatLog.of(context).update(time, save = true) { macChatStart(it, named) }
        make(context, time, MacFresh.Make(named, MAC_DEFAULT_PROJECT), away, kind, on) { sentText(context, time, prompt, it) }
    }

    /**
     * The record of [threadId] for its tab's earlier work ([macEarlier]): its newest [MAC_EARLIER_EVENTS] events,
     * asked for on the caller's thread, which must not be the main one. Only read: nothing is applied to the chat, no
     * place is moved and no card is made from it. [MacEarlierRead.Unread] with no Mac set up or none answering.
     */
    fun earlier(context: Context, threadId: String): MacEarlierRead {
        restore(context)
        val mac = machine(context.applicationContext, threadId) ?: return MacEarlierRead.Unread
        val client = macClient(mac)
        val known = client.thread(threadId) as? MacAnswer.Ok ?: return MacEarlierRead.Unread
        val after = macEarlierAfter(known.value.lastEvent)
        val events = macEarlierEvents(after, known.value.lastEvent) { from -> (client.events(threadId, from) as? MacAnswer.Ok)?.value?.events }
            ?: return MacEarlierRead.Unread
        return MacEarlierRead.Read(events, cut = after > 0)
    }

    /**
     * `[ stop ]` on an entry of the Mac's: stops that thread's task, which also drops what waited behind it
     * there. The entries end when the thread says it stopped. [told] gets what there is to tell him, null when
     * the Mac simply did it.
     */
    fun stop(context: Context, threadId: String, told: (String?) -> Unit) {
        restore(context)
        val app = context.applicationContext
        thread(name = "oma-mac-stop", isDaemon = true) {
            val said = when (val stopped = client(app, threadId).stop(threadId)) {
                is MacAnswer.Ok -> if (stopped.value) null else "Nothing was running on the Mac for this."
                MacAnswer.Unreachable -> "Mac unreachable. It is still working there."
                else -> "The Mac did not stop it."
            }
            follow(app, threadId)
            main.post { told(said) }
        }
    }

    /**
     * His tap on one waiting request: the card in the chat, the notification's buttons and the approval screen
     * all come through here, and nothing else calls the Mac with an answer. Nothing is answered from a locked
     * phone. The answer goes with that request's own nonce; the card goes only when the Mac says it took the
     * answer or that nothing waits any more, and otherwise stays for him. [told] is called on the main thread.
     */
    fun answer(context: Context, ticket: String, allow: Boolean, told: (MacApprovalOutcome) -> Unit) {
        restore(context)
        val app = context.applicationContext
        if (AgentNotices.deviceLocked(app)) return told(MacApprovalOutcome.Locked)
        val entry = synchronized(this) {
            val waiting = book.find(ticket) ?: return told(MacApprovalOutcome.Expired)
            if (!answering.add(ticket)) return told(MacApprovalOutcome.Answering)
            waiting
        }
        thread(name = "oma-mac-answer", isDaemon = true) {
            var outcome = MacApprovalOutcome.Unreachable
            try {
                outcome = macApprovalOutcome(client(app, entry.threadId).answerApproval(entry.threadId, entry.request.approval, allow, entry.request.nonce))
                if (outcome.over) {
                    val gone = synchronized(this) { book.remove(entry.threadId, entry.request.approval).also { published(context) } }
                    if (gone != null) AgentNotices.macWithdraw(app, gone)
                    val answered = if (outcome != MacApprovalOutcome.Decided) ApprovalAnswer.Unanswered else if (allow) ApprovalAnswer.Approved else ApprovalAnswer.Denied
                    ChatLog.of(app).revise { macAnswered(it, entry.threadId, entry.request.approval, answered) }
                }
            } finally {
                synchronized(this) { answering.remove(ticket) }
            }
            val result = outcome
            main.post { told(result) }
        }
    }
}
