package dev.omalauncher.assist

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import dev.omalauncher.App
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The guest's `oma status`, which prints the status file: a reader only, so the front agent may run it. */
fun agentsGuestTools(context: Context, rootfs: File): List<Managed> =
    listOf(Managed(File(rootfs, "usr/local/lib/oma/agents"), Policy.Owned, executable = true) { context.assets.open("oma-agents.sh").use { it.readBytes() } })

/**
 * Looks at the phone's builders: from /proc, cheaply, every [LOOK_EVERY_MS] while any works, whenever the front
 * agent is asked something, whenever a phone tab's menu opens and whenever the Mac's threads change ([macChanged]),
 * writing the status file each time. It changes nothing of the build lane except
 * through [restart], which is one builder's own stop and send, and ends no process except leftovers in [clean]:
 * a process either builder's task owns is never one.
 */
object BuilderWatch {
    /** Each builder's CPU track; only touched under this object's lock. */
    private val tracks = HashMap<BuilderId, CpuTrack>()
    private val watching = AtomicBoolean()
    /** The builders whose notification says they stalled; only touched under this object's lock. */
    private val noticed = HashSet<BuilderId>()

    /** A change of the Mac's threads not written yet, and whether a look for such changes runs: a burst of them makes few looks. */
    private val macDirty = AtomicBoolean()
    private val macWriting = AtomicBoolean()

    /** Each builder's facts at the last look, for a tab's menu to show at once what it can mend; empty before the first. */
    @Volatile var last: Map<BuilderId, BuilderFacts> = emptyMap(); private set

    /** A task started, changed or ended: while any works they are looked at until none does, and once more after. */
    fun watch(context: Context) {
        val app = context.applicationContext
        if (!AgentTasks.working) {
            thread(name = "oma-builder-look", isDaemon = true) { look(app) }
            return
        }
        if (!watching.compareAndSet(false, true)) return
        thread(name = "oma-builder-health", isDaemon = true) {
            try {
                while (AgentTasks.working) {
                    look(app)
                    Thread.sleep(LOOK_EVERY_MS)
                }
            } finally {
                watching.set(false)
            }
            // A task that began just as the last one left is looked at all the same.
            watch(app)
        }
    }

    /**
     * The phone's view of the Mac's threads changed: the status file is written again now, off the caller's thread,
     * so `oma status` says what a thread does and not what it did at the builder's last look. One look at a time:
     * a change that comes while one is written is written by another look after it.
     */
    fun macChanged(context: Context) {
        macDirty.set(true)
        if (!macWriting.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "oma-mac-status", isDaemon = true) {
            try {
                while (macDirty.getAndSet(false)) look(app)
            } finally {
                macWriting.set(false)
            }
            // A change that came just as the last look ended is written all the same.
            if (macDirty.get()) macChanged(app)
        }
    }

    /** The launcher reads /proc entries of its own processes only, which are the guest's and its own. */
    internal fun rows(): List<ProcRow> {
        val ticks = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return procRows(File("/proc"), ticks, bootedAt) { dir -> runCatching { Os.readlink(File(dir, "exe").path) }.getOrDefault("").endsWith(LOADER) }
    }

    /**
     * One look now: each builder's facts, also written to the status file and, for one stalled long, to the task
     * notification. Every working builder's task owns its own process, so neither's is a leftover; the leftovers
     * are told with builder A's facts, the one there always was, so they are counted once.
     */
    fun look(context: Context): Map<BuilderId, BuilderFacts> {
        val app = context.applicationContext as App
        val rows = runCatching { rows() }.getOrDefault(emptyList())
        // A builder only ever runs in the guest, so a look that found none of the guest's processes saw nothing of it.
        val procRead = rows.any { it.guest }
        val now = System.currentTimeMillis()
        // Whether each works is read before when its task began, which it sets first, so a start is never a stale one.
        val working = AgentTasks.builders.associate { it.id to it.working }
        val started = AgentTasks.builders.associate { it.id to it.startedAt }
        val procs = builderProcs(rows, AgentTasks.builders.filter { working.getValue(it.id) }.map { started.getValue(it.id) }, android.os.Process.myPid())
        val tasks = ChatLog.of(app).tasks
        // Read before this object's lock is taken: a builder's queue is under the build lane's lock, which
        // [notice] holds while it takes this one, so the two are never taken the other way round.
        val approval = AgentTasks.builders.associate { it.id to it.pending.isNotEmpty() }
        val queued = AgentTasks.builders.associate { it.id to it.waiting }
        val seen = synchronized(this) {
            AgentTasks.builders.associate { builder ->
                val on = working.getValue(builder.id)
                val start = started.getValue(builder.id)
                val own = if (on) procs.own[start] else null
                val cpuMs = own?.let { sessionCpu(rows, it) }
                val track = (tracks[builder.id] ?: CpuTrack()).after(own?.pid, cpuMs, now).also { tracks[builder.id] = it }
                val request = if (on) tasks.lastOrNull { it.build && it.builderId == builder.id && it.status == TaskStatus.Running }?.prompt.orEmpty() else ""
                builder.id to BuilderFacts(
                    now, on, start.takeIf { on }, builder.heardAt.takeIf { on && it > 0 }, builder.lastStep,
                    builder.lastStepAt.takeIf { on && it > 0 }, builder.traced, own?.pid, track, cpuMs,
                    approval.getValue(builder.id), queued.getValue(builder.id), request, if (builder.id == BuilderId.A) procs.leftovers else emptyList(), procRead,
                )
            }
        }
        last = seen
        write(app, seen, builderBShown(tasks, working[BuilderId.B] == true), builderShown(BuilderId.C, tasks, working[BuilderId.C] == true))
        notice(app, seen)
        return seen
    }

    /** The board's health lines, A's then B's: B's only once [twoShown], and A's then under its letter. */
    fun lines(seen: Map<BuilderId, BuilderFacts>, twoShown: Boolean): Pair<String?, String?> {
        val a = seen[BuilderId.A]?.let { healthLine(it, if (twoShown) "Builder A" else "Builder") }
        val b = if (twoShown) seen[BuilderId.B]?.let { healthLine(it, "Builder B") } else null
        return a to b
    }

    /** [builder]'s health line on the board while it is [shown], under its letter; null otherwise. */
    fun line(seen: Map<BuilderId, BuilderFacts>, builder: BuilderId, shown: Boolean): String? =
        if (shown) seen[builder]?.let { healthLine(it, "Builder ${builder.letter}") } else null

    private fun write(app: App, seen: Map<BuilderId, BuilderFacts>, twoShown: Boolean, threeShown: Boolean = false) = runCatching {
        val first = seen.getValue(BuilderId.A)
        val file = File(app.runtime.proot.home, AGENTS_STATUS_FILE)
        file.parentFile?.mkdirs()
        val written = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(first.now))
        val mac = MacThreads.view.takeIf { it.seen != null || it.threads.isNotEmpty() }?.let { view ->
            val tasks = ChatLog.of(app).tasks
            view.copy(threads = view.threads.map { macOwnSummary(it, tasks) })
        }
        val next = File(file.parentFile, file.name + ".new")
        next.writeText(agentsStatus(first, mac, written, seen[BuilderId.B]?.takeIf { twoShown }, TailscaleHeal.note(first.now), seen[BuilderId.C]?.takeIf { threeShown }).toString(2) + "\n")
        next.renameTo(file)
    }

    /**
     * A builder stalled for [NOTICE_STALL_MS] says so in the task notification, and the notification says what it
     * did before once it moves again. Under the build lane's lock, so a task that has just ended never gets its
     * notification back.
     */
    private fun notice(context: Context, seen: Map<BuilderId, BuilderFacts>) {
        for ((id, facts) in seen) {
            val stalled = builderVerdict(facts) == BuilderVerdict.Stalled && stalledFor(facts) >= NOTICE_STALL_MS
            if (!stalled && synchronized(this) { id !in noticed }) continue
            synchronized(AgentTasks) {
                synchronized(this) { if (stalled) noticed.add(id) else noticed.remove(id) }
                val builder = AgentTasks.of(id)
                if (builder.working) {
                    val others = AgentTasks.builders.count { it.working && it !== builder }
                    if (stalled) AgentNotices.task(context, true, poolLabel(id, "Stalled: no progress for ${span(stalledFor(facts))}", others), builder.answer, AgentTasks.pending.size, id)
                    else AgentNotices.task(context, true, AgentTasks.headline, AgentTasks.answer, AgentTasks.pending.size, AgentTasks.leadId)
                }
            }
        }
    }

    /**
     * Stops [builder]'s task, if it is still [restartable] and no step of it waits for Boss, and sends his request
     * again as a new task in that builder's conversation, once the old one has ended. [told] gets what the chat
     * should say. Nothing waiting for his approval is ever stopped by this, nothing is answered, and the other
     * builder is not touched.
     */
    fun restart(context: Context, builder: BuilderId, told: (String) -> Unit) {
        val app = context.applicationContext
        thread(name = "oma-builder-restart", isDaemon = true) {
            val looked = look(app)
            val seen = looked.getValue(builder)
            if (!restartable(builderVerdict(seen))) return@thread told(if (seen.approval) RESTART_APPROVAL else RESTART_NOTHING)
            val tasks = ChatLog.of(app).tasks
            val task: ChatTask = tasks.lastOrNull { it.build && it.builderId == builder && it.status == TaskStatus.Running } ?: return@thread told(RESTART_NOTHING)
            val one = AgentTasks.of(builder)
            val dropped = synchronized(AgentTasks) {
                if (!one.working) return@thread told(RESTART_NOTHING)
                if (one.pending.isNotEmpty()) return@thread told(RESTART_APPROVAL)
                one.waiting.also { AgentTasks.stop(app, builder) }
            }
            val until = System.currentTimeMillis() + RESTART_WAIT_MS
            while (one.working && System.currentTimeMillis() < until) Thread.sleep(500)
            if (one.working) return@thread told(RESTART_STUCK)
            val alone = !builderBShown(tasks, AgentTasks.of(BuilderId.B).working) && !builderShown(BuilderId.C, tasks, AgentTasks.of(BuilderId.C).working)
            val name = if (builder == BuilderId.A && alone) "the builder" else builderCalled(builder)
            told(runCatching { AgentTasks.followUp(app, task.prompt, task.images, builder = builder); restartedText(seen, dropped, name) }.getOrElse { it.message ?: RESTART_STUCK })
        }
    }

    /**
     * Ends the builder processes no task of either builder owns: each is checked again just before, by its pid and
     * start, so a pid taken since by something else is never touched. Asked to end first, then made to. [told]
     * gets what the chat should say.
     */
    fun clean(context: Context, told: (String) -> Unit) {
        val app = context.applicationContext
        thread(name = "oma-builder-clean", isDaemon = true) {
            fun leftovers() = look(app).values.flatMap { it.leftovers }
            val found = leftovers()
            fun still() = leftovers().filter { left -> found.any { it.pid == left.pid && it.startedAt == left.startedAt } }
            still().forEach { runCatching { Os.kill(it.pid, OsConstants.SIGTERM) } }
            Thread.sleep(CLEAN_WAIT_MS)
            still().forEach { runCatching { Os.kill(it.pid, OsConstants.SIGKILL) } }
            Thread.sleep(500)
            told(cleanedText(found.size, found.size - still().size))
        }
    }

    /** The executable every guest process runs under, as proot starts it. */
    private const val LOADER = "libproot-loader.so"
    private const val RESTART_WAIT_MS = 30_000L
    private const val CLEAN_WAIT_MS = 3_000L
}
