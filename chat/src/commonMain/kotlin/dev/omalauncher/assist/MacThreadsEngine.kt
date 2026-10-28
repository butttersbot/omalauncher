package dev.omalauncher.assist

import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.chatKey
import dev.omalauncher.oma.onMac
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The computers' threads as this device sees them (the phone's [MacThreads], and the desktop app), the device's own part given by [platform]. Nothing here runs on the caller's thread except reading what is already
 * known: asking the Mac, sending, following and answering each have a thread of their own. Those threads live
 * as the phone builder's task does, in the process the Linux foreground service keeps alive, so a thread is
 * followed whether or not the chat is open. With no config file there is no Mac: nothing is asked, the board
 * has no Mac on it, and the launcher is as it was before there were threads.
 */
class MacThreadsEngine(private val platform: ThreadsPlatform) {
    @Volatile var view = MacView(); private set

    /** The Mac's requests waiting for Boss, oldest first. One immutable list, as [AgentTasks.pending] is, for the same readers. */
    @Volatile var pending: List<MacPending> = emptyList(); private set

    /** The entries whose words are on their way to the Mac right now. */
    @Volatile var sending: Set<Long> = emptySet(); private set

    private val book = MacApprovalBook()
    /** What the threads' events last said of their artifacts (SomaArtifacts.kt): the panel opens and updates from it. */
    private val artifacts = dev.omalauncher.oma.ArtifactLiveBook()
    @Volatile private var cursors = MacCursors()
    /** The agents each Mac has, by its id, as its `/health` last said; none until it has. */
    @Volatile private var kinds: Map<String, List<String>> = emptyMap()
    // The computers whose runner is the Fold's own (MacHealth.phone): nothing is made there.
    @Volatile private var phones: Set<String> = emptySet()
    @Volatile private var offered: Map<String, Map<String, List<String>>> = emptyMap()
    /** How many threads each computer said it has at its last `/health`: a change (a thread or bot made elsewhere) is listed at once. */
    @Volatile private var counts: Map<String, Int> = emptyMap()
    /** The agents heard to be at their limit lately, kept in prefs under "limits". Only touched under the lock. */
    private val limits = MacLimits()
    /**
     * What each entry was last sent, his words with whatever went ahead of them, so that a turn ending at a limit
     * is sent on exactly as it went. In memory only: after a restart the same is made again from the thread.
     */
    private val sentWords = HashMap<Long, String>()

    /** The pictures sent with the entry filed at a time, on their way to the Mac with its words (and again on `[ try again ]`). */
    private val pictures = HashMap<Long, List<java.io.File>>()
    /** Each project's default agent and model ([macDefaultsFrom]), kept in prefs under [MAC_DEFAULTS_PREF]. Only touched under the lock. */
    private var defaults: Map<String, MacPick> = emptyMap()
    /** The switches under way, by the time of the entry whose thread writes the handoff; kept under [MAC_SWITCHES_PREF]. Under the lock. */
    private var switches: Map<Long, MacPick> = emptyMap()
    /** The new threads' first entries a switch made, by time, with the pick, so `[ try again ]` makes the same again. Kept under "seeds". */
    private var seeds: Map<Long, MacPick> = emptyMap()
    /**
     * The switches between their handoff turn's end and their new thread's being made, by the time of the entry, with
     * the thread they move and the pick: no longer in [switches], not yet in [seeds]. Only in this run. Under the lock.
     */
    private val finishing = HashMap<Long, Pair<String, MacPick>>()
    private val followers = HashMap<String, Follower>()

    /** Whether a thread on the Mac is being followed now: its answer or its request to approve is still to come. */
    val following: Boolean get() = synchronized(this) { followers.isNotEmpty() }

    /** Per entry, when its sending ended by [clock]; [IN_FLIGHT] until then. An entry not in here was sent by an earlier run. */
    private val sentAt = HashMap<Long, Long>()
    private val clock = AtomicLong()
    private val answering = HashSet<String>()
    private val refreshing = AtomicBoolean()
    private val discovering = AtomicBoolean()
    private val discoveryWake = Object()
    private var discoverySignalled = false
    private var restored = false
    @Volatile private var checked = 0L

    /** One thread's follower. [renewed] is set when it is asked for again, so one about to leave stays. */
    private class Follower { var renewed = false }

    private val IN_FLIGHT = Long.MAX_VALUE
    private val STALE_MS = 60_000L
    private val RESTING_LISTEN_MS = 60_000L
    private val NOT_SENT = "Not sent"

    /** Read fresh each time, so a Mac is there the moment Boss puts its file in place and gone when he takes it out. */
    private fun machines(): List<MacMachine> = platform.machines()

    /** The Mac the thread [threadId] is on, as set up now; null when its file is gone. */
    private fun machine(threadId: String): MacMachine? = machines().firstOrNull { it.id == macMachineOf(threadId) }

    /** The runner the thread [threadId] is on. One whose Mac is no longer set up answers nothing ([MacAnswer.Unreachable]). */
    private fun client(threadId: String) = macClient(machine(threadId))

    private fun kindsOf(machine: String) = kinds[machine].orEmpty()

    /** The agents the computer [machine] said it has at its last answer (`/health`'s kinds); none before it answered. */
    fun kindsOn(machine: String): List<String> = kindsOf(machine)

    /** Whether threads can be made on [machine]: every computer's runner but the Fold's own. */
    fun makesThreads(machine: String): Boolean = machine !in phones

    /** The computers a new chat's thread can be made on, as they stand. */
    private fun makers(): List<MacSeen> = macSeen(machines(), view).filter { makesThreads(it.id) }

    /** The models the computer [machine]'s runner listed at its last answer, by agent (`/health`'s models: Venice's); none before. */
    fun modelsOn(machine: String): Map<String, List<String>> = offered[machine].orEmpty()

    /**
     * Project [project]'s handoff top section (Soma Canvas), from the first computer whose runner has it; null when none does.
     * Blocking: call it off the screen's thread.
     */
    fun handoff(project: String): MacHandoff? = handoffs(listOf(project))[project]

    /** The project registry, from the first computer whose runner gives it; null when none does. Blocking. */
    fun projects(): List<RegisteredProject>? {
        for (machine in machines()) {
            val answer = runCatching { macClient(machine).projects() }.getOrNull()
            if (answer is MacAnswer.Ok) return answer.value
        }
        return null
    }

    /** The host the runner set up as [machine] is reached at (its URL's host: a tailnet name or address); empty when unknown. */
    fun hostAddress(machine: String): String =
        machines().firstOrNull { it.id == machine }?.config?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty()

    /**
     * The handoffs of [projects] in one pass: each from the first computer whose runner has it. A computer that doesn't
     * answer at all is not asked again in this pass, so one that is down costs one timeout, not one per project.
     */
    fun handoffs(projects: Collection<String>): Map<String, MacHandoff?> {
        val down = HashSet<String>()
        return projects.associateWith { project ->
            var found: MacHandoff? = null
            for (machine in machines()) {
                if (machine.id in down) continue
                when (val answer = runCatching { macClient(machine).handoff(project) }.getOrNull()) {
                    is MacAnswer.Ok -> { found = answer.value; break }
                    MacAnswer.Unreachable, null -> down += machine.id
                    else -> Unit
                }
            }
            found
        }
    }

    /** Every computer set up on this device, as it last answered; nothing is read from disk, so it can be asked as often as the chat draws. */
    fun hosts(): List<MacSeen> = view.macs.ifEmpty { macSeen(machines(), view) }

    /** The threads on the same Mac as [threadId]: work on one Mac is never placed with, beside or instead of another's. */
    private fun sameMac(threadId: String, threads: List<MacThread> = view.threads) = threads.filter { macMachineOf(it.id) == macMachineOf(threadId) }

    /** Where when each Mac was last seen is kept; the first Mac's under the name it always had. */
    private fun seenKey(machine: String) = if (machine == MAC_FIRST) "seen" else "seen:$machine"

    @Synchronized fun restore() {
        if (restored) return
        restored = true
        cursors = MacCursors(macCursorsFrom(platform.prefString("cursors")))
        val macs = machines().map { MacSeen(it.id, it.name, seen = platform.prefLong(seenKey(it.id)).takeIf { seen -> seen > 0 }) }
        view = macWith(MacView(seen = platform.prefLong("seen").takeIf { it > 0 }), macs)
        limits.restore(platform.prefString("limits"), System.currentTimeMillis())
        defaults = macDefaultsFrom(platform.prefString(MAC_DEFAULTS_PREF))
        // A switch whose handoff ended in an earlier run can't go on any more (only one never taken can be tried again): it is let go.
        val ended = platform.chatLog().tasks.filter { it.status == TaskStatus.Done || it.status == TaskStatus.Failed || it.status == TaskStatus.Stopped }.map { it.time }.toSet()
        switches = macSwitchesFrom(platform.prefString(MAC_SWITCHES_PREF)).filterKeys { it !in ended }
        seeds = macSwitchesFrom(platform.prefString(MAC_SEEDS_PREF))
        // No card of an earlier run can be answered: its ticket died with that process. They are drawn again from the Mac.
        platform.clearStaleCards()
    }

    /** The chat opened, or the launcher started: learn how the Mac stands and pick its working threads up again. */
    fun open() {
        restore()
        startDiscovery()
        wakeDiscovery()
        refresh()
    }

    private fun wakeDiscovery() = synchronized(discoveryWake) {
        discoverySignalled = true
        discoveryWake.notifyAll()
    }

    private fun discoveryForeground(): Boolean = runCatching { platform.foreground() }.getOrDefault(false)

    /** One process-lifetime watcher, independent of UI, owed tasks and per-thread followers. */
    private fun startDiscovery() {
        if (!discovering.compareAndSet(false, true)) return
        thread(name = "oma-mac-discovery", isDaemon = true) {
            var idleRounds = 0
            while (true) {
                val foreground = discoveryForeground()
                val busy = macDiscoveryBusy(view.threads)
                if (foreground || busy) idleRounds = 0
                val delay = macDiscoveryDelay(view.threads, foreground, idleRounds)
                val signalled = synchronized(discoveryWake) {
                    val until = System.nanoTime() + delay * 1_000_000L
                    while (!discoverySignalled) {
                        val remaining = (until - System.nanoTime()) / 1_000_000L
                        if (remaining <= 0) break
                        runCatching { discoveryWake.wait(remaining) }
                    }
                    discoverySignalled.also { discoverySignalled = false }
                }
                // A foreground open already refreshes; a busy transition already has fresh evidence.
                if (!signalled) runCatching { if (machines().isNotEmpty()) refresh() }
                idleRounds = if (signalled || foreground || busy || discoveryForeground() || macDiscoveryBusy(view.threads)) 0
                    else (idleRounds + 1).coerceAtMost(3)
            }
        }
    }

    /**
     * The Mac's part of the front agent's board at [now], from what is already known; null with no Mac set up.
     * It never waits for the Mac: a list that has grown old is asked for again beside this request, for the next one.
     */
    fun board(now: Long): MacBoard? {
        restore()
        val machines = machines()
        if (machines.isEmpty()) return null
        if (now - checked > STALE_MS) refresh()
        val known = view
        val tasks = platform.chatLog().tasks
        return macBoard(known.threads.map { macOwnSummary(it, tasks) }, known.reachable, known.seen, now, platform.linkNote(now), macSeen(machines, known))
    }

    /**
     * Home is showing its thread tiles at [now]: a list grown old is asked for again, as [board] does for the
     * front agent, so a thread that began work at the Mac itself gets its tile. With no Mac set up nothing is asked.
     */
    fun glance(now: Long) {
        restore()
        if (now - checked > STALE_MS && machines().isNotEmpty()) refresh()
    }

    /** What stands beside [task], an entry of the Mac's, while it is not over. */
    fun label(task: ChatTask): String =
        macEntryLabel(task.status, task.time in sending, view.reachableFor(task.thread), pending.any { it.threadId == task.thread }, task.fresh, computerOf(task.thread))

    /** The computer [threadId] runs on, as he named it when it was set up ("MG1"); "the Mac" while that isn't known. */
    fun computerOf(threadId: String): String = machine(threadId)?.name?.takeIf { it.isNotBlank() } ?: "the Mac"

    /** What asking the Mac [machine]'s `/health` came to: whether it answered, and which agents it has when it did. */
    private fun heard(machine: String, health: MacHealthResult) {
        var changed = false
        if (health is MacHealthResult.Up) {
            kinds = kinds + (machine to health.health.kinds)
            offered = offered + (machine to health.health.models)
            phones = if (health.health.phone) phones + machine else phones - machine
            changed = counts[machine].let { it != null && it != health.health.threads }
            counts = counts + (machine to health.health.threads)
        }
        reached(machine, health is MacHealthResult.Up)
        // A session made on another device, or a bot added on its machine, shows here without waiting for the next look.
        if (changed) refresh()
        // Only a Mac that does not answer while none other does says anything about this phone's Tailscale.
        if (health is MacHealthResult.Up || view.macs.none { it.reachable && it.id != machine }) platform.linkAfter(macLink(health))
    }

    /** The agents taken to be at their limit now. */
    private fun limited(): Set<String> = synchronized(this) { limits.at(System.currentTimeMillis()) }

    /** Whether the Mac [machine] answered just now. The others stand as they were: one out of reach hides no other. */
    private fun reached(machine: String, reachable: Boolean) {
        val now = System.currentTimeMillis()
        val machines = machines()
        see() { known ->
            macWith(known, macSeen(machines, known).map { if (it.id != machine) it else if (reachable) it.copy(reachable = true, seen = now) else it.copy(reachable = false) })
        }
        if (reachable) platform.putPref(seenKey(machine), now)
    }

    /**
     * Changes what the phone knows of the Mac. Anything more than when it last answered goes to the status file at
     * once, so `oma status` says what a thread does now and not what it did at the builder's last look.
     */
    private fun see(change: (MacView) -> MacView) {
        val moved = synchronized(this) {
            val before = view
            view = change(before)
            if (!macDiscoveryBusy(before.threads) && macDiscoveryBusy(view.threads))
                wakeDiscovery()
            before.copy(seen = null) != view.copy(seen = null)
        }
        if (!moved) return
        platform.statusChanged()
        told()
    }

    /** One thread as the Mac just described it, put into the list the board is made from. */
    private fun noted(thread: MacThread) = see() { known ->
        known.copy(threads = if (known.threads.any { it.id == thread.id }) known.threads.map { if (it.id == thread.id) thread else it } else known.threads + thread)
    }

    private fun name(threadId: String): String =
        view.threads.firstOrNull { it.id == threadId }?.let { macName(it.name) }
            ?: platform.chatLog().tasks.lastOrNull { it.thread == threadId }?.threadName?.takeIf { it.isNotEmpty() }
            ?: macName("")

    private fun unfinished(threadId: String) = platform.chatLog().tasks.any { macUnfinished(it, threadId) }

    private fun saveCursors() =
        platform.putPref("cursors", macCursorsText(cursors.snapshot()))

    /** Under the lock: republishes the book for readers on other threads, and the tiles, ring and lock widget count its requests. */
    private fun published() {
        pending = book.live
        told()
    }

    /** The Mac's work may look different now: the tiles, the ring and the lock widget are told, off this thread and lock. */
    private fun told() = platform.threadsChanged()

    /**
     * Asks the Mac for its threads, off the caller's thread and one asking at a time. The threads [macFollowed]
     * names are then followed: those at work, those owed a request of his, and those of his that may still say
     * something between his messages or said something nobody heard.
     */
    fun refresh() {
        restore()
        if (!refreshing.compareAndSet(false, true)) return
        thread(name = "oma-mac-refresh", isDaemon = true) {
            try {
                val machines = machines()
                if (machines.isEmpty()) noMac()
                else {
                    checked = System.currentTimeMillis()
                    gone(machines.map { it.id }.toSet())
                    // Each Mac on its own: one that does not answer leaves the others' threads as they are heard.
                    val links = mutableListOf<MacLink>()
                    val threads = mutableListOf<MacThread>()
                    for (machine in machines) {
                        val client = macClient(machine)
                        val health = client.health()
                        links += macLink(health)
                        if (health is MacHealthResult.Up) {
                            kinds = kinds + (machine.id to health.health.kinds)
                            offered = offered + (machine.id to health.health.models)
                            phones = if (health.health.phone) phones + machine.id else phones - machine.id
                        }
                        val listed = if (health is MacHealthResult.Up) client.threads() else MacAnswer.Unreachable
                        if (listed is MacAnswer.Ok) {
                            threads += listed.value
                            see() { known ->
                                macWith(known.copy(threads = known.threads.filter { macMachineOf(it.id) != machine.id } + listed.value),
                                    macSeen(machines, known).map { if (it.id == machine.id) it.copy(listed = true) else it })
                            }
                        }
                        reached(machine.id, listed is MacAnswer.Ok)
                    }
                    // Also how the launcher's start turns Tailscale back on after a reboot: Runtime.start opens this.
                    platform.linkAfter(links.firstOrNull { it == MacLink.Answered } ?: links.first())
                    val tasks = platform.chatLog().tasks
                    val owed = tasks.filter { it.mac && (it.status == TaskStatus.Running || it.status == TaskStatus.Queued) }.map { it.thread }
                    val his = tasks.filter { it.mac && !it.fresh }.map { it.thread }.toSet()
                    for (threadId in macFollowed(threads, owed, his, cursors::after, System.currentTimeMillis())) follow(threadId)
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
    private fun gone(kept: Set<String>) {
        val lost = view.threads.map { macMachineOf(it.id) }.filter { it !in kept }.toSet() +
            platform.chatLog().tasks.filter { it.mac && it.thread != MAC_NEW_THREAD && macUnfinished(it, it.thread) }.map { macMachineOf(it.thread) }.filter { it !in kept }
        if (lost.isEmpty()) return
        platform.chatLog().revise { tasks -> tasks.map { it.thread }.distinct().filter { macMachineOf(it) in lost && it != MAC_NEW_THREAD }.fold(tasks) { all, id -> macAbandon(all, id, MAC_NOT_SET_UP) } }
        val dropped = synchronized(this) { book.live.filter { macMachineOf(it.threadId) in lost }.flatMap { book.drop(it.threadId) }.also { published() } }
        dropped.forEach { platform.withdrawCard(it) }
        see() { known -> known.copy(threads = known.threads.filter { macMachineOf(it.id) !in lost }) }
    }

    /** The config is gone: there is no Mac to follow or answer, and what was unfinished there says so. */
    private fun noMac() {
        platform.chatLog().revise { macAbandon(it, null, MAC_NOT_SET_UP) }
        val gone = synchronized(this) { book.clear().also { published() } }
        see() { MacView() }
        gone.forEach { platform.withdrawCard(it) }
    }

    /**
     * Hands [prompt], his words exactly, to [thread]. The entry sent at [asked] becomes that thread's before
     * anything is sent; the Mac is then asked how it is and sent the words on a thread of its own. [away] is a
     * request spoken on the listening screen: if it cannot be sent he is told over whatever is in front.
     * [fresh] is separate work on the project [thread] is busy with, which goes where [place] says; [newChat] is
     * the first words of a new chat, which always get a new thread on that project. Either way, while the agent
     * it would go to is known to be at its limit, another takes it ([deliver]).
     */
    fun handOver(asked: Long, prompt: String, thread: MacThread, away: Boolean, fresh: Boolean = false, newChat: Boolean = false) {
        restore()
        if (fresh || newChat) return place(asked, prompt, thread, away, newChat)
        deliver(asked, prompt, thread, away)
    }

    /**
     * Gives the request filed at [time] to [thread], with [step] on its entry. While the agent [thread] runs is
     * known to be at its limit and another the Mac has is not ([macInstead]), it goes to that one instead, on the
     * same project: to a thread of that agent there with nothing to do, or to a new one made for it, and the entry
     * says why in place of [step].
     */
    private fun deliver(time: Long, prompt: String, thread: MacThread, away: Boolean, step: String? = null) {
        val log = platform.chatLog()
        val machine = macMachineOf(thread.id)
        val instead = macInstead(thread.kind, kindsOf(machine), limited())
        if (instead == null) {
            log.update(time, save = true) { macHandOver(it, thread).let { entry -> if (step == null) entry else ChatHistory.withStep(entry, step) } }
            return send(time, sentText(time, prompt, thread.id), thread.id, away)
        }
        val why = macLimitedStep(thread.kind, instead)
        val free = macKindThread(thread.project, instead, sameMac(thread.id), macOwed(log.tasks, except = time))
        if (free != null) {
            log.update(time, save = true) { ChatHistory.withStep(macHandOver(it, free), why) }
            return send(time, sentText(time, prompt, free.id), free.id, away)
        }
        val name = macKindThreadName(thread.name, instead, sameMac(thread.id).map { it.name })
        log.update(time, save = true) { ChatHistory.withStep(macFreshHandOver(it, thread, name), why) }
        make(time, MacFresh.Make(name, thread.project), away, instead, machine) { sentText(time, prompt, it) }
    }

    /**
     * Separate work for the project of [busy], filed at [time]: [macFresh] decides from the threads as last heard,
     * with [busy] as the Mac last described it. A thread that is free takes it as a hand-over; past the limit it
     * waits on a working one and the entry says why; otherwise a new thread is made and the words go to that. A
     * new thread runs the first agent the Mac has that is not known to be at its limit, and is named after it
     * when that is not Claude.
     */
    private fun place(time: Long, prompt: String, busy: MacThread, away: Boolean, newChat: Boolean = false) {
        val log = platform.chatLog()
        val threads = sameMac(busy.id).let { known -> if (known.any { it.id == busy.id }) known else known + busy }
        val named = threads.first { it.id == busy.id }
        val machine = macMachineOf(busy.id)
        when (val place = macFresh(named, threads, macOwed(log.tasks, except = time), newChat)) {
            is MacFresh.Same -> deliver(time, prompt, place.thread, away)
            is MacFresh.Queue -> deliver(time, prompt, place.thread, away, macCappedStep(place.working, macFolder(named.project)))
            is MacFresh.Make -> {
                val limited = limited()
                // A new chat's own pick of agent and model still holds beside a named thread; the computer is that thread's.
                // With none, the project's default while the computer can run it (DESIGN §4), else the first agent as ever.
                val chosen = if (!newChat) null else platform.book().chat(log.tasks.firstOrNull { it.time == time }?.chat.orEmpty())
                val picked = chosen?.agent.orEmpty()
                val fallback = if (picked.isNotBlank()) null else defaultFor(named.project, machine, limited)
                val kind = fallback?.kind ?: macChatKind(picked, kindsOf(machine), limited) ?: macInstalled(kindsOf(machine)).first()
                val model = if (picked.isNotBlank()) chosen?.model.orEmpty() else fallback?.model.orEmpty()
                val name = if (kind == MAC_CLAUDE) place.name else macKindThreadName(named.name, kind, threads.map { it.name })
                // Said only when a limit is why: a thread of another agent's is otherwise simply made as Claude's.
                val why = macLimitedStep(named.kind, kind).takeIf { picked.isBlank() && fallback == null && kind != macKindOf(named.kind) && macKindOf(named.kind) in limited }
                log.update(time, save = true) { macFreshHandOver(it, named, name).let { entry -> if (why == null) entry else ChatHistory.withStep(entry, why) } }
                make(time, place.copy(name = name), away, kind, machine, model) { sentText(time, prompt, it) }
            }
        }
    }

    /**
     * Makes the thread [place] names, on the Mac [machine], running the agent [kind] on [model], off the caller's thread, and sends it what [words]
     * makes for its id as any hand-over is sent. The entry counts as on its way the whole time, so nothing the
     * thread it waited under says can end it. A Mac that does not answer, turns the key down or refuses the
     * folder leaves the entry saying so with `[ try again ]`.
     */
    private fun make(time: Long, place: MacFresh.Make, away: Boolean, kind: String, machine: String, model: String = "", made: (MacThread) -> Unit = {}, failed: (String) -> Unit = {}, words: (String) -> String) {
        synchronized(this) {
            sentAt[time] = IN_FLIGHT
            sending = sending + time
        }
        thread(name = "oma-mac-make", isDaemon = true) {
            val log = platform.chatLog()
            var madeThread: MacThread? = null
            var problem = MAC_UNREACHABLE
            var sendOn = false
            try {
                val mac = machines().firstOrNull { it.id == machine }
                val client = macClient(mac)
                val health = client.health()
                if (mac != null) heard(machine, health)
                if (health !is MacHealthResult.Up) problem = macUnreachableReason(health)
                else {
                    val answer = client.makeThread(place.name, place.project, kind, model)
                    madeThread = (answer as? MacAnswer.Ok)?.value
                    problem = macMadeReason(answer) ?: problem
                }
                madeThread?.let { thread ->
                    noted(thread)
                    made(thread)
                    log.update(time, save = true) { macMade(it, thread) }
                    // Made for a new chat's words, its first or ones another agent's limit sent on: the chat lives here from now on.
                    log.tasks.firstOrNull { it.time == time }?.chat?.takeIf { it.isNotEmpty() }?.let { chat -> platform.changeBook { it.onMac(chat, thread.id) } }
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
            val thread = madeThread
            when {
                thread != null && sendOn -> send(time, words(thread.id), thread.id, away)
                thread == null -> {
                    log.update(time, save = true) { macUnsent(it, problem) }
                    failed(problem)
                    if (away) platform.answeredNotice(NOT_SENT, problem, front = true)
                    refresh()
                }
                else -> refresh()
            }
        }
    }

    /**
     * His words typed on a thread's own tab in the chat: they go to the thread [threadId], called [name], as
     * they are. The front agent is not asked and nothing is put ahead of them. The entry is filed first, under
     * the build lane's lock as every entry's time is taken, and the words then go the way a hand-over's do:
     * the Mac is asked how it is, and one that does not answer leaves the entry saying so with `[ try again ]`.
     */
    fun say(prompt: String, threadId: String, name: String, chat: String = "", images: List<java.io.File> = emptyList(), shown: List<String> = emptyList(), delivered: ((Boolean) -> Unit)? = null) {
        restore()
        // "switch to codex" typed on a thread's tab, the whole message, is a switch (DESIGN §4), never words for the agent.
        if (delivered == null) macSwitchAsked(prompt)?.let { return switchTo(threadId, it, prompt, name, chat) }
        val log = platform.chatLog()
        val time = synchronized(platform.stampLock) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { log.begin(macDirect(it, prompt, threadId, name).copy(chat = chat, images = shown)) }
        }
        if (images.isNotEmpty()) synchronized(this) { pictures[time] = images }
        send(time, prompt, threadId, away = false, delivered = delivered)
    }

    /**
     * What the thread is sent for the request filed at [asked]: his words, behind what he and the front agent
     * said since that thread last got anything, and before that [MAC_SHARED_NOTE] while another thread works in
     * the same folder ([macShares]). The entry and the routing log are given [prompt] alone. Words he sent
     * straight to the thread go as they are, however often they are sent again.
     */
    private fun sentText(asked: Long, prompt: String, threadId: String): String {
        val tasks = platform.chatLog().tasks
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
    fun tryAgain(time: Long) {
        restore()
        val log = platform.chatLog()
        // Under the lock, so a switch of its thread starting now either sees it on its way again or calls it off first ([switchTo]).
        val task = synchronized(this) {
            log.tasks.firstOrNull { it.time == time }?.takeIf { macRetried(it) != null }?.also { log.update(time, save = true) { macRetried(it) ?: it } }
        } ?: return
        platform.routingCorrected(task.prompt, MAC_TRIED_AGAIN)
        // A switch's first words to its new thread, never made: the same thread of the same agent is made again.
        synchronized(this) { seeds[time] }?.let { pick -> if (task.fresh) return seed(time, task, pick) }
        // A switch whose handoff never reached the thread asks for it again, never sends his "switch to …" as words.
        synchronized(this) { switches[time] }?.let { pick -> return send(time, macHandoffAsk(pick), task.thread, away = false) }
        val busy = view.threads.firstOrNull { it.id == task.thread }
        // Sent again as it was, unless its agent is known to be at its limit, as when every agent was.
        if (!task.fresh) return if (busy != null) deliver(time, task.prompt, busy, away = false) else send(time, sentText(time, task.prompt, task.thread), task.thread, away = false)
        // A new chat's first words whose thread in the default folder was never made: it is made again.
        if (task.thread == MAC_NEW_THREAD) return makeForChat(time, task.prompt, away = false, task.chat)
        // A new chat's first words that never got their thread are given a new one again, never the busy one.
        val newChat = task.chat.isNotEmpty() && platform.book().chat(task.chat)?.bound == false
        if (busy != null) return place(time, task.prompt, busy, away = false, newChat)
        // Until the Mac has listed its threads in this run, a thread missing from the list is only not heard of yet.
        log.update(time, save = true) { macUnsent(it, if (view.listedFor(task.thread)) MAC_THREAD_GONE else MAC_UNREACHABLE) }
        refresh()
    }

    /**
     * Asks the Mac how it is, inside the two-second limit, and sends [text] if it answers. A Mac that does not
     * leaves the entry saying so with his words kept; nothing is given to the phone's builder instead. When the
     * words were sent and no answer came back, it is not known whether the Mac has them: the entry waits, and
     * the thread's follower finds out from the Mac itself.
     */
    private fun send(time: Long, text: String, threadId: String, away: Boolean, delivered: ((Boolean) -> Unit)? = null) {
        val kept = platform.chatLog().tasks.map { it.time }.toSet()
        // A thread never followed from here is followed from where it stood: its past would otherwise be read as this turn's.
        view.threads.firstOrNull { it.id == threadId }?.let { cursors.start(threadId, it.lastEvent) }
        synchronized(this) {
            sentAt[time] = IN_FLIGHT
            sending = sending + time
            // Only the entries the chat still keeps can be sent on.
            sentWords.keys.retainAll(kept)
            pictures.keys.retainAll(kept)
            sentWords[time] = text
        }
        thread(name = "oma-mac-send", isDaemon = true) {
            val log = platform.chatLog()
            var problem: String? = MAC_UNREACHABLE
            try {
                val mac = machine(threadId)
                val client = macClient(mac)
                val health = client.health()
                if (mac != null) heard(mac.id, health)
                problem = if (mac == null) MAC_NOT_SET_UP else when (macRoute(ThreadHome.Mac, health, startedOnMac = false, phoneHasClone = false)) {
                    MacRoute.RunOnMac -> {
                        // Pictures sent with his words go up first; the thread is told where, as the phone's builders are.
                        val attached = synchronized(this) { pictures[time] }.orEmpty()
                        val kept = attached.map { client.upload(it) }
                        if (kept.any { it == null }) MAC_IMAGES_NOT_SENT
                        else {
                            val taken = client.sendMessage(threadId, withPictures(text, kept.filterNotNull()))
                            if (taken is MacAnswer.Ok && taken.value == MacTaken.Now) log.update(time, save = true, change = ::macTaken)
                            macUnsentReason(taken)
                        }
                    }
                    else -> macUnreachableReason(health)
                }
            } catch (_: Exception) {
                problem = MAC_UNREACHABLE
            } finally {
                synchronized(this) {
                    sentAt[time] = clock.incrementAndGet()
                    sending = sending - time
                }
            }
            val why = problem
            if (why == null) follow(threadId)
            else {
                log.update(time, save = true) { macUnsent(it, why) }
                if (away) platform.answeredNotice(NOT_SENT, why, front = true)
            }
            platform.post { delivered?.invoke(why == null) }
            refresh()
        }
    }

    /** Starts following [threadId], or tells the follower it has to stay. */
    private fun follow(threadId: String) {
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
                following(threadId, mine)
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
    private fun following(threadId: String, mine: Follower) {
        val log = platform.chatLog()
        var misses = 0
        val machine = macMachineOf(threadId)
        while (true) {
            val mac = machine(threadId)
            if (mac == null) {
                if (machines().isEmpty()) noMac() else abandon(threadId, MAC_NOT_SET_UP)
                return
            }
            val client = macClient(mac)
            val asked = clock.incrementAndGet()
            when (val known = client.thread(threadId)) {
                is MacAnswer.Ok -> {
                    val seen = known.value
                    reached(machine, true)
                    platform.linkAfter(MacLink.Answered)
                    noted(seen)
                    if (seen.lastEvent < cursors.after(threadId)) {
                        cursors.forget(threadId); artifacts.forget(threadId)
                        saveCursors()
                    }
                    synced(seen)
                    if (cursors.after(threadId) < seen.lastEvent) {
                        val page = client.events(threadId, cursors.after(threadId))
                        val fresh = if (page is MacAnswer.Ok) cursors.catchUp(threadId, page.value.events) else emptyList()
                        if (fresh.isEmpty()) pause(macRetryDelay(++misses))
                        else {
                            misses = 0
                            fresh.forEach { shown(threadId, it) }
                        }
                        continue
                    }
                    val resting = macResting(seen.status)
                    if (resting) {
                        val late = synchronized(this) { sentAt.filterValues { it >= asked }.keys.toSet() }
                        ending(threadId) { tasks -> macSettle(tasks, threadId, seen.status, seen.waitingTurns) { it.time !in late } }
                        if (unfinished(threadId)) {
                            pause(macRetryDelay(++misses))
                            continue
                        }
                        // Nothing of his is left there, but the thread may still report on its own between his messages: it
                        // is listened to until it lists no work left running and its last turn is a while ago.
                        if (!macAwake(seen, System.currentTimeMillis()) || log.tasks.none { it.thread == threadId && !it.fresh }) {
                            if (leave(mine, threadId)) {
                                refresh()
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
                            shown(threadId, event)
                            if (macTurnsOver(event)) turned.set(true)
                        }
                    }
                    when (end.why) {
                        MacStreamWhy.Refused -> {
                            abandon(threadId, macRefusedText(end.code))
                            return
                        }
                        MacStreamWhy.Dropped -> if (heard) misses = 0 else pause(macRetryDelay(++misses))
                        MacStreamWhy.Gap, MacStreamWhy.Stopped -> misses = 0
                    }
                }
                MacAnswer.NotFound -> {
                    abandon(threadId, MAC_THREAD_GONE)
                    return
                }
                MacAnswer.Unauthorized -> {
                    abandon(threadId, macRefusedText(401))
                    return
                }
                else -> {
                    reached(machine, false)
                    // A thread waiting out a dark link is often where a reboot's Tailscale shows: it is healed from here too,
                    // unless another Mac answers, which says this phone's link is up.
                    if (known is MacAnswer.Unreachable && view.macs.none { it.reachable }) platform.linkAfter(MacLink.NoAnswer)
                    // Out of reach with nothing of his there and no card of its to keep: nothing to wait for.
                    if (!unfinished(threadId) && pending.none { it.threadId == threadId } && leave(mine, threadId)) return
                    pause(macRetryDelay(++misses))
                }
            }
        }
    }

    /** The thread cannot be followed: its entries say why, and its cards go, since they could not be answered. */
    private fun abandon(threadId: String, reason: String) {
        ending(threadId) { macAbandon(it, threadId, reason) }
        val gone = synchronized(this) { book.drop(threadId).also { published() } }
        gone.forEach { platform.withdrawCard(it) }
    }

    /**
     * Ends what [change] ends of [threadId]'s entries with no event of the thread's to say so: the Mac found it resting
     * with a turn still running (the runner restarted under it, or let it go), or it can't be followed at all. A switch's
     * handoff ended this way goes on to [finishSwitch] as one ended by an event does, from the thread's record, and is
     * held as [finishing] from before it ends, so no second switch of the thread gets in between.
     */
    private fun ending(threadId: String, change: (List<ChatTask>) -> List<ChatTask>) {
        val log = platform.chatLog()
        val handing = handingOff(threadId)
        try {
            log.revise(change)
            val ended = handing?.let { at -> log.tasks.firstOrNull { it.time == at } }?.takeIf(::macHandoffEnded) ?: return
            val switching = synchronized(this) { switches[ended.time] } ?: return
            finishSwitch(ended.time, switching)
        } finally {
            if (handing != null) synchronized(this) { finishing.remove(handing) }
        }
    }

    /** Makes the cards match the thread's own list of what waits, and the entries with them. */
    private fun synced(seen: MacThread) {
        val change = synchronized(this) { book.sync(seen).also { published() } }
        val log = platform.chatLog()
        for (gone in change.removed) {
            platform.withdrawCard(gone)
            log.revise { macAnswered(it, seen.id, gone.request.approval, ApprovalAnswer.Unanswered) }
        }
        for (added in change.added) {
            log.revise { macAsked(it, seen.id, added.request) }
            platform.requestCard(added)
        }
    }

    /**
     * One new event of a thread, in order: into the Oma thread and onto disk first, then the place it was read
     * to, so a restart between the two shows an event twice rather than never. An approval becomes a card for
     * Boss and nothing else; nothing here answers one.
     */
    private fun shown(threadId: String, event: MacEvent) {
        val log = platform.chatLog()
        val before = log.tasks
        // A turn that ended at its agent's limit goes on elsewhere before anything else is made of its end.
        // A handoff asked for is never sent on to another agent: the switch goes on from the thread's record instead.
        if (macLimited(event) && !askingHandoff(threadId)) fallBack(threadId)
        // Its limit is still remembered, so the switch's new thread doesn't start on an agent at one.
        else if (macLimited(event)) hitLimit(view.threads.firstOrNull { it.id == threadId }?.kind.orEmpty())
        // A handoff turn about to end keeps its switch on its way until [finishSwitch] has the new thread being made.
        val handing = if (event is MacEvent.Result || event is MacEvent.Error || event is MacEvent.Stopped) handingOff(threadId) else null
        try {
            shownOn(threadId, event, before)
        } finally {
            if (handing != null) synchronized(this) { finishing.remove(handing) }
        }
    }

    private fun shownOn(threadId: String, event: MacEvent, before: List<ChatTask>) {
        val log = platform.chatLog()
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
                val threadName = name(threadId)
                val added = synchronized(this) { book.offer(threadId, threadName, event.id, event.request).also { published() } }
                if (added != null) platform.requestCard(added)
            }
            is MacEvent.ApprovalResult -> {
                val gone = synchronized(this) { book.resolve(threadId, event.id, event.approval).also { published() } }
                if (gone != null) platform.withdrawCard(gone)
            }
            is MacEvent.Result, is MacEvent.Error, is MacEvent.Stopped -> {
                val gone = synchronized(this) { book.ended(threadId, event.id).also { published() } }
                gone.forEach { platform.withdrawCard(it) }
                // A request of his that just ended comes to him as the phone builder's does; one he stopped says nothing more.
                // Dropped here when every agent was at its limit or the turn ended empty, which needs him as a failure does.
                val ended = log.tasks.firstOrNull { now ->
                    now.thread == threadId && now.status in ENDINGS && before.any { it.time == now.time && it.status == TaskStatus.Running }
                }
                // A handoff he stopped calls the switch off: the thread stays where it is.
                log.tasks.filter { it.thread == threadId && it.status == TaskStatus.Stopped }.forEach { stopped ->
                    val off = synchronized(this) { (stopped.time in switches).also { if (it) { switches = switches - stopped.time; saveSwitches() } } }
                    if (off) log.update(stopped.time, save = true) { ChatHistory.withStep(it, MAC_SWITCH_OFF) }
                }
                val switching = ended?.let { synchronized(this) { switches[it.time] } }
                if (ended != null && switching != null) finishSwitch(ended.time, switching)
                else if (ended != null) platform.answeredNotice(if (ended.status == TaskStatus.Done) ended.threadName else "${ended.threadName} needs attention", ended.result, front = false)
            }
            is MacEvent.Status -> see() { known -> known.copy(threads = known.threads.map { if (it.id == threadId) it.copy(status = event.status) else it }) }
            is MacEvent.Artifact -> artifacts.heard(threadId, event.name, event.version, event.state, event.settled, event.title, event.note,
                dev.omalauncher.oma.artifactAgent(kind), machine(threadId)?.name.orEmpty(), event.time, event.id)
            is MacEvent.Message, is MacEvent.Text, is MacEvent.Step, is MacEvent.Note, is MacEvent.Other -> Unit
        }
        saveCursors()
    }

    private val ENDINGS = setOf(TaskStatus.Done, TaskStatus.Failed, TaskStatus.Dropped)

    /**
     * The turn [threadId] was on ended at its agent's limit. The limit is remembered ([MAC_LIMIT_MEMORY_MS]), and
     * the request of his that turn was on goes, the same words with whatever went ahead of them the first time,
     * to a new thread of the next agent the Mac has that is not at its limit, on the same project; the entry
     * says so. With none left it needs his attention and can be tried again. A turn that was not his, or of a
     * thread whose project is not known, is left to end as any error does.
     */
    private fun fallBack(threadId: String) {
        val log = platform.chatLog()
        val task = log.tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running } ?: return
        val thread = view.threads.firstOrNull { it.id == threadId }?.takeIf { it.project.isNotBlank() } ?: return
        val machine = macMachineOf(threadId)
        val from = macKindOf(thread.kind)
        val now = System.currentTimeMillis()
        val (next, words) = synchronized(this) {
            limits.hit(from, now)
            platform.putPref("limits", limits.text(now))
            macKind(kindsOf(machine), limits.at(now)) to sentWords[task.time]
        }
        if (next == null) return log.update(task.time, save = true) { macAllLimitedEntry(it, kindsOf(machine)) }
        val name = macKindThreadName(thread.name, next, sameMac(threadId).map { it.name })
        val text = words ?: sentText(task.time, task.prompt, threadId)
        log.update(task.time, save = true) { macLimitHandOver(it, name, macLimitStep(from, next)) }
        make(task.time, MacFresh.Make(name, thread.project), away = false, next, machine) { text }
    }

    /** The default for a new thread in [project] on [machine] that nothing else picked ([macDefaultFor]); null leaves it as ever. */
    private fun defaultFor(project: String, machine: String, limited: Set<String>): MacPick? =
        synchronized(this) { macDefaultFor(defaults, project, kindsOf(machine), limited) }

    /** The default a new chat's picks show before he picks: the one new chats' threads would use now, or null for the first agent. */
    fun defaultPick(machine: String): MacPick? {
        restore()
        return defaultFor(MAC_DEFAULT_PROJECT, machine, limited())
    }

    /** [pick] is [project]'s default from now on: the last one he picked for it, by a new chat or a switch. */
    private fun remember(project: String, pick: MacPick) = synchronized(this) {
        defaults = macDefaultsWith(defaults, project, pick)
        platform.putPref(MAC_DEFAULTS_PREF, macDefaultsText(defaults))
    }

    private fun hitLimit(kind: String) = synchronized(this) {
        val now = System.currentTimeMillis()
        limits.hit(macKindOf(kind), now)
        platform.putPref("limits", limits.text(now))
    }

    private fun saveSwitches() {
        platform.putPref(MAC_SWITCHES_PREF, macSwitchesText(switches))
        platform.putPref(MAC_SEEDS_PREF, macSwitchesText(seeds))
    }

    /** The time of the handoff a switch asked for that [threadId]'s turn is on, now counted as [finishing]; null when it is on none. */
    private fun handingOff(threadId: String): Long? {
        val running = platform.chatLog().tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running } ?: return null
        return synchronized(this) { switches[running.time]?.let { pick -> finishing[running.time] = threadId to pick; running.time } }
    }

    /** Whether the turn [threadId] is on is a handoff a switch asked for. */
    private fun askingHandoff(threadId: String): Boolean {
        val running = platform.chatLog().tasks.firstOrNull { it.thread == threadId && it.status == TaskStatus.Running } ?: return false
        return synchronized(this) { running.time in switches }
    }

    /**
     * Moves the thread [threadId] to [to] (DESIGN §4, "switch to X"): his request is filed on its tab as [words], and
     * the thread is asked for a handoff when it can write one ([macCanWriteHandoff]); once that turn ends, however it
     * ends, [finishSwitch] makes the new thread. When it can't, the new thread is made at once from the thread's record.
     * The pick becomes the project's default. Agents can't resume each other's sessions; this is the honest version.
     */
    fun switchTo(threadId: String, to: MacPick, words: String = "Switch to ${macPickName(to)}", name: String = "", chat: String = "") {
        restore()
        val pick = macPickClean(to)
        // Filed in the chat that lives on the thread, where he asked, as his words there are.
        val chat = chat.ifEmpty { platform.book().chatOnMac(threadId)?.id.orEmpty() }
        val log = platform.chatLog()
        val thread = view.threads.firstOrNull { it.id == threadId }?.takeIf { it.project.isNotBlank() }
        val shownName = thread?.name ?: name.ifEmpty { name(threadId) }
        val time = synchronized(platform.stampLock) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { log.begin(ChatHistory.withStep(macDirect(it, words, threadId, shownName).copy(chat = chat), macSwitchingStep(pick))) }
        }
        if (thread == null) {
            log.update(time, save = true) { it.copy(status = TaskStatus.Failed, result = MAC_SWITCH_UNKNOWN, answeredBy = OMA_NAME) }
            return
        }
        // One switch of a thread at a time: a second while the first's handoff or new thread is on its way would make two,
        // as would one between the two, when the handoff has ended and the new thread is not yet being made ([finishing]).
        // A seed whose thread couldn't be made is no longer on its way, and never holds the thread.
        val under = synchronized(this) {
            val tasks = log.tasks
            (switches.entries.firstOrNull { (at, _) -> at != time && tasks.any { it.time == at && it.thread == threadId && !macHandoffEnded(it) } }?.value
                ?: finishing.entries.firstOrNull { (at, on) -> at != time && on.first == threadId }?.value?.second
                // Or one tried again whose making hasn't begun yet: it waits as Queued until [make] has it on its way.
                ?: seeds.entries.firstOrNull { (at, _) -> tasks.any { it.time == at && it.thread == threadId && it.fresh && (at in sending || it.status == TaskStatus.Queued) } }?.value)
                .also { if (it == null) calledOff(threadId, time) }
        }
        if (under != null) {
            log.update(time, save = true) { it.copy(status = TaskStatus.Failed, result = macSwitchBusy(under), answeredBy = OMA_NAME) }
            return
        }
        val busy = macBusy(thread, thread.id in macOwed(log.tasks, except = time))
        val writes = macCanWriteHandoff(thread, view.reachableFor(threadId), limited(), busy)
        synchronized(this) {
            switches = switches + (time to pick)
            saveSwitches()
        }
        if (writes) send(time, macHandoffAsk(pick), threadId, away = false)
        else finishSwitch(time, pick)
    }

    /**
     * Under the lock, as the switch filed at [except] starts on [threadId]: an earlier switch of the thread that never got
     * there (its handoff or its new thread's first words left with `[ try again ]`) is called off, so trying it again
     * can't make a second thread beside this one's.
     */
    private fun calledOff(threadId: String, except: Long) {
        val log = platform.chatLog()
        fun waiting(at: Long, fresh: Boolean) = at != except && log.tasks.any { it.time == at && it.thread == threadId && it.fresh == fresh && it.status == TaskStatus.Dropped }
        val off = switches.keys.filter { waiting(it, fresh = false) } + seeds.keys.filter { waiting(it, fresh = true) }
        if (off.isEmpty()) return
        switches = switches - off.toSet()
        seeds = seeds - off.toSet()
        saveSwitches()
        off.forEach { at -> log.update(at, save = true) { ChatHistory.withStep(it.copy(status = TaskStatus.Stopped), MAC_SWITCH_REPLACED) } }
    }

    /**
     * The switch asked for at [time] goes on: the entry's answer is the handoff when the thread wrote one, else the end of
     * its record ([macHandoffFromRecord]). A new thread of [pick] is made on the same computer and project and sent it
     * ([seed]); the entry says where the thread moved, and a chat bound to the old thread lives on the new one.
     */
    private fun finishSwitch(time: Long, pick: MacPick) {
        val log = platform.chatLog()
        val asked = log.tasks.firstOrNull { it.time == time }
        // Out of the switches under way and into [finishing] at once, so it is on its way throughout.
        synchronized(this) {
            switches = switches - time
            asked?.let { finishing[time] = it.thread to pick }
            saveSwitches()
        }
        asked ?: return
        try {
            finishOn(time, pick, asked)
        } finally {
            synchronized(this) { finishing.remove(time) }
        }
    }

    private fun finishOn(time: Long, pick: MacPick, asked: ChatTask) {
        val log = platform.chatLog()
        val old = view.threads.firstOrNull { it.id == asked.thread }
        // A handoff that already ended (as when the thread was lost) keeps its own reason, and says it couldn't move.
        if (old == null) return log.update(time, save = true) {
            if (macHandoffEnded(asked)) ChatHistory.withStep(it, macSwitchFailedStep("that thread isn't known here any more")) else it.copy(status = TaskStatus.Failed, result = MAC_SWITCH_UNKNOWN)
        }
        // A request still waiting is ended: it was only ever the handoff, and the new thread takes over from here.
        if (!macHandoffEnded(asked)) log.update(time, save = true) { it.copy(status = TaskStatus.Done, result = "Moved without a handoff: the thread couldn't write one.", answeredBy = OMA_NAME) }
        val written = log.tasks.firstOrNull { it.time == time }?.let(::macHandoffWritten)
        val handoff = written ?: macHandoffFromRecord(log.tasks.filter { it.time != time }, old.id)
        val name = macKindThreadName(old.name, pick.kind, sameMac(old.id).map { it.name })
        val chat = platform.book().chatOnMac(old.id)?.id ?: asked.chat
        val words = macHandoffSeed(macReplyName(old.kind, old.model) ?: macKindName(old.kind), handoff, written != null)
        val first = synchronized(platform.stampLock) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { log.begin(macFreshHandOver(ChatTask(it, "", words, direct = true, chat = chat), old, name)) }
        }
        seed(first, log.tasks.first { it.time == first }, pick, asked = time)
    }

    /**
     * Makes the new thread a switch asked for, of [pick], for the entry [task] filed at [time], and sends it the entry's
     * words. Only once it is made does the request's entry ([asked]) say where the thread moved, and the pick become the
     * project's default; a thread that can't be made leaves the entry saying why, and the old thread where it was.
     */
    private fun seed(time: Long, task: ChatTask, pick: MacPick, asked: Long? = null) {
        val old = view.threads.firstOrNull { it.id == task.thread }
        val machine = macMachineOf(task.thread)
        val project = old?.project ?: return platform.chatLog().update(time, save = true) { macUnsent(it, MAC_SWITCH_UNKNOWN) }
        synchronized(this) {
            seeds = seeds.filterKeys { key -> platform.chatLog().tasks.any { it.time == key && it.fresh } } + (time to pick)
            saveSwitches()
        }
        val request = asked ?: platform.chatLog().tasks.lastOrNull { it.thread == task.thread && it.steps.any { step -> step.startsWith("handing off to ") } && it.time < time }?.time
        make(
            time, MacFresh.Make(task.threadName, project), away = false, pick.kind, machine, pick.model,
            made = { thread ->
                remember(project, pick)
                request?.let { at -> platform.chatLog().update(at, save = true) { entry -> ChatHistory.withStep(entry, macSwitchedStep(thread.name)) } }
            },
            failed = { why -> request?.let { at -> platform.chatLog().update(at, save = true) { entry -> ChatHistory.withStep(entry, macSwitchFailedStep(why)) } } },
        ) { task.prompt }
    }

    /**
     * The first words of the new chat [chat], filed at [asked], with no thread named for them: they go to a new
     * thread in [MAC_DEFAULT_PROJECT], called by the chat's name, and nothing asks him which project (Boss,
     * 2026-10-14: "i shouldn't be asked which project"). With no Mac set up it says so; with the Mac out of reach
     * his words are kept, unsent, with `[ try again ]` under them ([chatPlace]).
     */
    fun startChat(asked: Long, prompt: String, away: Boolean, chat: String) {
        restore()
        val log = platform.chatLog()
        val macs = makers()
        val on = macChatMachine(platform.book().chat(chat)?.host, macs)
        when (chatPlace(on != null, macs.any { it.id == on && it.reachable })) {
            ChatPlace.NotSetUp -> log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = MAC_NOT_SET_UP, status = TaskStatus.Failed) }
            ChatPlace.Unreachable -> {
                val name = macs.firstOrNull { it.id == on }?.name.orEmpty().takeIf { macs.size > 1 }.orEmpty()
                log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = macChatUnreachable(name), status = TaskStatus.Dropped, answeredBy = OMA_NAME) }
                refresh()
            }
            ChatPlace.Start -> makeForChat(asked, prompt, away, chat, machine = on ?: MAC_FIRST)
        }
    }

    /**
     * The first words of the new chat [chat] when no front agent can read them ([dev.omalauncher.oma.Outgoing.ChatMac]):
     * filed as his, then sent as they are to a new thread in [MAC_DEFAULT_PROJECT] called by the chat's name or his
     * first words ([chatDirectName]). Nothing on the phone is needed: no Linux and no agent. With no Mac set up it
     * says so; a Mac out of reach leaves his words unsent with `[ try again ]`, which makes the thread again.
     */
    /** [shown] are the pictures as the chat names them, for the entry's thumbnails; [images] the files that go to the Mac. */
    fun startChatDirect(prompt: String, chat: String, images: List<java.io.File> = emptyList(), shown: List<String> = emptyList()) {
        restore()
        val log = platform.chatLog()
        val name = chatDirectName(platform.book().names[chatKey(chat)], prompt)
        val setUp = machines().isNotEmpty()
        val time = synchronized(platform.stampLock) {
            ChatHistory.stamp(log.tasks, System.currentTimeMillis()).also { time ->
                log.begin((if (setUp) macChatDirect(time, prompt, chat, name) else ChatTask(time, QUICK_AGENT, prompt, result = MAC_NOT_SET_UP, status = TaskStatus.Failed, direct = true, chat = chat)).copy(images = shown))
            }
        }
        // Pictures go with the first words once the thread is made: send() puts them on the Mac first.
        if (setUp && images.isNotEmpty()) synchronized(this) { pictures[time] = images }
        if (setUp) makeForChat(time, prompt, away = false, chat, name)
    }

    /**
     * Makes the thread for the new chat [chat] in [MAC_DEFAULT_PROJECT] and sends it the words filed at [time]. It
     * runs the first agent the Mac has that is not known to be at its limit, named after it when that is not Claude.
     * It is called [given], or else by the chat's name as it stands.
     */
    private fun makeForChat(time: Long, prompt: String, away: Boolean, chat: String, given: String? = null, machine: String? = null) {
        val name = given ?: chatThreadName(platform.book().names[chatKey(chat)])
        val picked = platform.book().chat(chat)
        val on = machine ?: macChatMachine(picked?.host, makers()) ?: MAC_FIRST
        val agent = picked?.agent.orEmpty()
        // His pick is used for this chat alone (Boss, 2026-10-28: a pick no longer becomes the default for later chats);
        // with none, the default a switch of agent left, while it can be used.
        val fallback = if (agent.isNotBlank()) null else defaultFor(MAC_DEFAULT_PROJECT, on, limited())
        val kind = fallback?.kind ?: macChatKind(agent, kindsOf(on), limited()) ?: macInstalled(kindsOf(on)).first()
        val model = if (agent.isNotBlank()) picked?.model.orEmpty() else fallback?.model.orEmpty()
        val named = if (kind == MAC_CLAUDE) name else macKindThreadName(name, kind, view.threads.filter { macMachineOf(it.id) == on }.map { it.name })
        platform.chatLog().update(time, save = true) { macChatStart(it, named) }
        make(time, MacFresh.Make(named, MAC_DEFAULT_PROJECT), away, kind, on, model) { sentText(time, prompt, it) }
    }

    /**
     * The record of [threadId] for its tab's earlier work ([macEarlier]): its newest [MAC_EARLIER_EVENTS] events,
     * asked for on the caller's thread, which must not be the main one. Only read: nothing is applied to the chat, no
     * place is moved and no card is made from it. [MacEarlierRead.Unread] with no Mac set up or none answering.
     */
    fun earlier(threadId: String): MacEarlierRead {
        restore()
        val mac = machine(threadId) ?: return MacEarlierRead.Unread
        val client = macClient(mac)
        val known = client.thread(threadId) as? MacAnswer.Ok ?: return MacEarlierRead.Unread
        val after = macEarlierAfter(known.value.lastEvent)
        val events = macEarlierEvents(after, known.value.lastEvent) { from -> (client.events(threadId, from) as? MacAnswer.Ok)?.value?.events }
            ?: return MacEarlierRead.Unread
        return MacEarlierRead.Read(events, cut = after > 0)
    }

    /** Every artifact the threads' events have spoken of since the app started, as they last said. */
    fun liveArtifacts(): List<dev.omalauncher.oma.ArtifactLive> = artifacts.snapshot()

    /** One artifact's versions as its computer keeps them; null when it can't say. Blocks off the UI thread. */
    fun artifactVersions(threadId: String, name: String): dev.omalauncher.oma.ArtifactVersions? =
        client(threadId).artifacts(threadId)?.firstOrNull { it.name == name }

    /** One explicitly opened output on the conversation's computer. Blocks off the UI thread. */
    fun artifact(threadId: String, path: String): dev.omalauncher.oma.ArtifactRead = client(threadId).artifact(threadId, path)

    /**
     * `[ stop ]` on an entry of the Mac's: stops that thread's task, which also drops what waited behind it
     * there. The entries end when the thread says it stopped. [told] gets what there is to tell him, null when
     * the Mac simply did it.
     */
    /** A bot's instructions and notes, read from the computer it lives on; null when they can't be read now. */
    fun botFiles(threadId: String, told: (MacBotFiles?) -> Unit) {
        restore()
        val botId = view.threads.firstOrNull { it.id == threadId }?.bot?.id ?: return platform.post { told(null) }
        thread(name = "oma-mac-bot", isDaemon = true) {
            val read = (client(threadId).bot(botId) as? MacAnswer.Ok)?.value
            platform.post { told(read) }
        }
    }

    /** Start fresh on a bot's tab: [told] gets what to tell him. */
    fun freshBot(threadId: String, told: (String) -> Unit) {
        restore()
        thread(name = "oma-mac-fresh", isDaemon = true) {
            val said = when (val done = client(threadId).fresh(threadId)) {
                is MacAnswer.Ok -> "Started fresh. Its instructions and notes are kept."
                is MacAnswer.Failed -> done.error.ifBlank { "It didn't start fresh." }.replaceFirstChar { it.uppercaseChar() }
                MacAnswer.Unreachable -> "The computer it lives on is unreachable."
                else -> "It didn't start fresh."
            }
            refresh()
            platform.post { told(said) }
        }
    }

    fun stop(threadId: String, told: (String?) -> Unit) {
        restore()
        thread(name = "oma-mac-stop", isDaemon = true) {
            val said = when (val stopped = client(threadId).stop(threadId)) {
                is MacAnswer.Ok -> if (stopped.value) null else "Nothing was running on the Mac for this."
                MacAnswer.Unreachable -> "Mac unreachable. It is still working there."
                else -> "The Mac did not stop it."
            }
            follow(threadId)
            platform.post { told(said) }
        }
    }

    /**
     * His tap on one waiting request: the card in the chat, the notification's buttons and the approval screen
     * all come through here, and nothing else calls the Mac with an answer. Nothing is answered from a locked
     * phone. The answer goes with that request's own nonce; the card goes only when the Mac says it took the
     * answer or that nothing waits any more, and otherwise stays for him. [told] is called on the main thread.
     */
    fun answer(ticket: String, allow: Boolean, told: (MacApprovalOutcome) -> Unit) = decide(ticket, allow, false, told)

    fun answerQuestions(ticket: String, answers: org.json.JSONObject, told: (MacApprovalOutcome) -> Unit) = decide(ticket, true, false, told, answers)

    fun rememberCommand(ticket: String, told: (MacApprovalOutcome) -> Unit) = decide(ticket, true, true, told)

    fun forgetCommands(threadId: String, told: (String) -> Unit) {
        if (platform.locked()) return told("Unlock your phone to forget saved commands")
        thread(name = "oma-forget-commands", isDaemon = true) {
            val result = runCatching { client(threadId).clearCommands(threadId) }.getOrNull()
            platform.post { told(if (result is MacAnswer.Ok && result.value) "Saved command approvals forgotten" else "Could not forget saved commands. Check the runner connection and version.") }
        }
    }

    private fun decide(ticket: String, allow: Boolean, remember: Boolean, told: (MacApprovalOutcome) -> Unit, answers: org.json.JSONObject? = null) {
        restore()
        if (platform.locked()) return told(MacApprovalOutcome.Locked)
        val entry = synchronized(this) {
            val waiting = book.find(ticket) ?: return told(MacApprovalOutcome.Expired)
            if (remember && !waiting.request.canRemember) return told(MacApprovalOutcome.Failed)
            if (!answering.add(ticket)) return told(MacApprovalOutcome.Answering)
            waiting
        }
        thread(name = "oma-mac-answer", isDaemon = true) {
            var outcome = MacApprovalOutcome.Unreachable
            try {
                outcome = macApprovalOutcome(client(entry.threadId).answerApproval(entry.threadId, entry.request.approval, allow, entry.request.nonce, remember, answers))
                if (outcome.over) {
                    val gone = synchronized(this) { book.remove(entry.threadId, entry.request.approval).also { published() } }
                    if (gone != null) platform.withdrawCard(gone)
                    val answered = if (outcome != MacApprovalOutcome.Decided) ApprovalAnswer.Unanswered else if (allow && questionAnswersSkipped(answers)) ApprovalAnswer.Skipped else if (allow) ApprovalAnswer.Approved else ApprovalAnswer.Denied
                    platform.chatLog().revise { macAnswered(it, entry.threadId, entry.request.approval, answered) }
                }
            } finally {
                synchronized(this) { answering.remove(ticket) }
            }
            val result = outcome
            platform.post { told(result) }
        }
    }
}

/** Why a thread's words weren't sent: the pictures with them didn't reach the Mac. */
const val MAC_IMAGES_NOT_SENT = "The pictures didn't reach the Mac, so nothing was sent. Try again."

/** What comes between his words and where the Mac keeps the pictures sent with them. */
const val PICTURES_NOTE = "\n\nRead these attached images before answering:\n"

/** [text] with where the Mac keeps the pictures sent with it, for the thread's agent to read, as the phone's builders are told. */
fun withPictures(
    text: String,
    paths: List<String>,
): String = if (paths.isEmpty()) text else text + PICTURES_NOTE + paths.joinToString("\n")
