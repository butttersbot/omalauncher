package dev.omalauncher.assist

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// How the phone's builder is doing, for the front agent to see and Boss to ask about (Boss, 2026-10-13: the
// builder sat on one task for 45 minutes printing nothing, and the front agent could only say it was busy).
// Everything that decides is pure and at the top, made from plain facts so a pool of builders can use it as is;
// BuilderWatch at the end reads /proc and the build lane, writes the status file and carries out a repair, which
// Boss asks for from a phone tab's long-press menu (Boss, 2026-10-14), never the front agent.

/** No line from the builder for this long and it is no longer simply working: it is quiet. */
const val QUIET_MS = 3 * 60_000L

/**
 * No line and no CPU for this long and it is stalled. Long enough for a slow model turn, which prints nothing
 * until it is whole and takes little CPU while it waits for the model, short of the 45 minutes Boss waited.
 */
const val STALL_MS = 10 * 60_000L

/** Stalled this long and the task's notification says so. */
const val NOTICE_STALL_MS = 15 * 60_000L

/** Working this long with no builder process to be found and the process is taken for gone, not still starting. */
const val DEAD_GRACE_MS = 60_000L

/**
 * A task this young that has no builder process yet is neither alive nor dead but not known: on the first status
 * after an update the builder's process was still starting and read "alive: false" (OPEN-BUGS #6).
 */
const val ALIVE_GRACE_MS = 30_000L

/**
 * CPU used per minute of looking, in milliseconds, that counts as computing: about 2% of one core. An agent
 * that only waits uses far less; a build, a test run or a sub-agent working uses far more.
 */
const val CPU_BUSY_MS_PER_MIN = 1_000L

/** How often the build lane is looked at while it works. */
const val LOOK_EVERY_MS = 60_000L

/** Looks closer together than this are too short to tell computing from waiting: the earlier one stands. */
const val MIN_LOOK_MS = 10_000L

/** How far back the CPU growth shown is counted. */
const val CPU_WINDOW_MS = 10 * 60_000L

/** CPU seen rising this recently and the builder counts as computing. Two looks, so one quiet minute does not flip it. */
const val CPU_RECENT_MS = 2 * 60_000L

/** A builder process started this long before its task's own start is still that task's: clocks read a little apart. */
const val START_SLACK_MS = 5_000L

/** How much of his request, a step or a thread's words the status carries. */
const val HEALTH_TEXT_CHARS = 120

/**
 * One process as /proc shows it: who started it ([ppid]) and who traces it ([tracer], proot for the guest's
 * processes, 0 for none), when it started by the wall clock, the CPU it and its reaped children have used, its
 * arguments, and whether it runs in the guest under proot's loader.
 */
data class ProcRow(val pid: Int, val ppid: Int, val tracer: Int, val startedAt: Long, val cpuMs: Long, val argv: List<String>, val guest: Boolean)

/**
 * Reads one process from the text of its `stat`, `status` and `cmdline`. [ticks] is the kernel's clock ticks
 * per second and [bootedAt] when the phone started by the wall clock. Null for anything unreadable. The name in
 * `stat` may hold spaces and brackets, so the fields are counted from its last closing bracket.
 */
fun procRow(pid: Int, stat: String, status: String, cmdline: ByteArray, guest: Boolean, ticks: Long, bootedAt: Long): ProcRow? {
    if (ticks <= 0) return null
    val fields = stat.substringAfterLast(") ", "").trim().split(' ')
    if (fields.size < 20) return null
    fun field(n: Int) = fields[n - 3].toLongOrNull()
    val ppid = field(4) ?: return null
    val cpu = listOf(14, 15, 16, 17).sumOf { field(it) ?: return null }
    val started = field(22) ?: return null
    val tracer = status.lineSequence().firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
    val argv = cmdline.decodeToString().split('\u0000').dropLastWhile { it.isEmpty() }
    return ProcRow(pid, ppid.toInt(), tracer, bootedAt + started * 1000 / ticks, cpu * 1000 / ticks, argv, guest)
}

/**
 * Every process under [proc] (the real /proc, or a stand-in laid out like it) that can be read; [guest] says
 * whether a process's directory is one of the guest's. A process that ends while it is read is left out.
 */
fun procRows(proc: File, ticks: Long, bootedAt: Long, guest: (File) -> Boolean): List<ProcRow> =
    proc.listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        runCatching { procRow(pid, File(dir, "stat").readText(), File(dir, "status").readText(), File(dir, "cmdline").readBytes(), guest(dir), ticks, bootedAt) }.getOrNull()
    }

/**
 * Whether [argv] is a builder's Claude Code: `claude -p` reading stream-json and asking for approvals over stdio,
 * as only [claudeStreamCommand] starts it. The front agent's, oma-door's and any terminal's Claude are not.
 */
fun builderCommand(argv: List<String>): Boolean {
    val claude = argv.indexOfFirst { it.substringAfterLast('/') == "claude" }
    if (claude < 0) return false
    val args = argv.drop(claude + 1)
    fun pair(flag: String, value: String) = args.indices.any { args[it] == flag && args.getOrNull(it + 1) == value }
    return "-p" in args && pair("--input-format", "stream-json") && pair("--permission-prompt-tool", "stdio")
}

/** The builder processes found: each running task's own, keyed by when that task started, and every other one. */
data class BuilderProcs(val own: Map<Long, ProcRow>, val leftovers: List<ProcRow>)

/**
 * Sorts the builder processes in [rows]. Each task started at one of [starts] (one per builder at work) owns the
 * earliest builder process started since then that no other task has taken; a task starts its process only after
 * it has begun. Two tasks that began in the same millisecond each still take one, so neither builder's process is
 * ever a leftover, though [BuilderProcs.own] can key only the first. Every other builder process in the guest
 * belongs to no task: a leftover, never one in a task's own tree, and never [self], the launcher.
 */
fun builderProcs(rows: List<ProcRow>, starts: List<Long>, self: Int): BuilderProcs {
    val builders = rows.filter { it.guest && it.pid != self && builderCommand(it.argv) }.sortedBy { it.startedAt }
    val own = LinkedHashMap<Long, ProcRow>()
    val taken = mutableListOf<ProcRow>()
    for (start in starts.sorted()) builders.firstOrNull { it.startedAt >= start - START_SLACK_MS && it !in taken }?.let { taken += it; own.putIfAbsent(start, it) }
    val owned = taken.flatMap { session(rows, it) }.map { it.pid }.toSet()
    return BuilderProcs(own, builders.filter { it.pid !in owned })
}

/**
 * [own] and everything that works for it: its descendants, the proot that traces it and whatever else that proot
 * traces, such as a build daemon that has left its parent. The task's proot runs this task and nothing else.
 */
fun session(rows: List<ProcRow>, own: ProcRow): List<ProcRow> {
    val tree = mutableSetOf(own.pid)
    while (true) {
        val children = rows.filter { it.ppid in tree && it.pid !in tree }
        if (children.isEmpty()) break
        children.forEach { tree.add(it.pid) }
    }
    return rows.filter { it.pid in tree || (own.tracer > 0 && (it.tracer == own.tracer || it.pid == own.tracer)) }
}

/** The CPU [own]'s whole session has used so far, in milliseconds. */
fun sessionCpu(rows: List<ProcRow>, own: ProcRow): Long = session(rows, own).sumOf { it.cpuMs }

/** One look at a process's CPU: [at] by the wall clock, [cpuMs] used by then. */
data class CpuSample(val at: Long, val cpuMs: Long)

/**
 * The CPU of one builder process over the looks at it. [since] is the first look, [movedAt] the last look that
 * found it computing; [samples] are the looks within [CPU_WINDOW_MS] of the newest.
 */
data class CpuTrack(val pid: Int? = null, val since: Long? = null, val samples: List<CpuSample> = emptyList(), val movedAt: Long? = null) {
    /** The track after a look at [now] found [pid] at [cpuMs]; a different process starts a track of its own. */
    fun after(pid: Int?, cpuMs: Long?, now: Long): CpuTrack {
        if (pid == null || cpuMs == null) return CpuTrack()
        val last = samples.lastOrNull()
        if (pid != this.pid || last == null) return CpuTrack(pid, now, listOf(CpuSample(now, cpuMs)))
        if (now - last.at < MIN_LOOK_MS) return this
        val moved = (cpuMs - last.cpuMs) * 60_000 >= CPU_BUSY_MS_PER_MIN * (now - last.at)
        return copy(samples = (samples + CpuSample(now, cpuMs)).filter { now - it.at <= CPU_WINDOW_MS }, movedAt = if (moved) now else movedAt)
    }

    /** CPU used between the oldest and newest look kept, and over how long. */
    val growthMs get() = ((samples.lastOrNull()?.cpuMs ?: 0L) - (samples.firstOrNull()?.cpuMs ?: 0L)).coerceAtLeast(0)
    val windowMs get() = (samples.lastOrNull()?.at ?: 0L) - (samples.firstOrNull()?.at ?: 0L)

    /** Since when its CPU has not been seen rising: the last rise, or the first look if there has been none. */
    val stillSince get() = movedAt ?: since
}

/**
 * What is known of the build lane at [now]: whether it is [working], when its task [startedAt], when its agent
 * last printed a line ([heardAt]), its last tool step and when, whether its agent is Claude Code ([traced]: only
 * that one's process can be found), the process [pid] (null if none was found), its CPU, whether a step waits for
 * Boss's [approval], how many follow-ups are [queued] behind it, the start of his [request], and [leftovers].
 * [procRead] says whether the look at [now] could read the guest's processes at all; one that could not finds no
 * process whether or not there is one. Nothing of an approval but that one waits.
 */
data class BuilderFacts(
    val now: Long,
    val working: Boolean,
    val startedAt: Long? = null,
    val heardAt: Long? = null,
    val lastStep: String = "",
    val lastStepAt: Long? = null,
    val traced: Boolean = false,
    val pid: Int? = null,
    val cpu: CpuTrack = CpuTrack(),
    val cpuMs: Long? = null,
    val approval: Boolean = false,
    val queued: Int = 0,
    val request: String = "",
    val leftovers: List<ProcRow> = emptyList(),
    val procRead: Boolean = true,
) {
    /** How long nothing has come from it: since its last line, or since it began if none has. */
    val quietFor get() = (now - (heardAt ?: startedAt ?: now)).coerceAtLeast(0)

    /** How long its CPU has been still; null when it cannot be read. */
    val stillFor get() = cpu.stillSince?.let { (now - it).coerceAtLeast(0) }

    val computing get() = cpu.movedAt?.let { now - it < CPU_RECENT_MS } == true

    /**
     * Whether its process lives: null when that cannot be told, which is for a task not working or not traced,
     * one younger than [ALIVE_GRACE_MS] with no process found yet, and any look that could not read the guest.
     */
    val alive: Boolean? get() = when {
        !working || !traced -> null
        pid != null -> true
        !procRead || startedAt == null || now - startedAt < ALIVE_GRACE_MS -> null
        else -> false
    }

    val dead get() = alive == false && startedAt != null && now - startedAt >= DEAD_GRACE_MS
}

enum class BuilderVerdict(val word: String) {
    Idle("idle"),
    /** A line in the last [QUIET_MS]. */
    Working("working"),
    /** No line for [QUIET_MS] or more, but not stalled: computing, or not quiet for long enough to say. */
    Quiet("quiet"),
    /** No line and no CPU for [STALL_MS]. */
    Stalled("stalled"),
    /** A step waits for Boss: it is his turn, and nothing is wrong. */
    WaitingForBoss("waiting for Boss"),
    /** Its task is marked working, but its process is gone. */
    Dead("dead"),
    /** Idle, with builder processes of earlier tasks still running. */
    Leftovers("leftovers"),
}

fun builderVerdict(facts: BuilderFacts): BuilderVerdict = when {
    !facts.working -> if (facts.leftovers.isNotEmpty()) BuilderVerdict.Leftovers else BuilderVerdict.Idle
    facts.approval -> BuilderVerdict.WaitingForBoss
    facts.dead -> BuilderVerdict.Dead
    facts.quietFor < QUIET_MS -> BuilderVerdict.Working
    facts.computing -> BuilderVerdict.Quiet
    facts.quietFor >= STALL_MS && (facts.stillFor ?: 0L) >= STALL_MS -> BuilderVerdict.Stalled
    else -> BuilderVerdict.Quiet
}

/** Only these may be restarted: a builder that waits for Boss, works or is merely quiet is never stopped for it. */
fun restartable(verdict: BuilderVerdict) = verdict == BuilderVerdict.Stalled || verdict == BuilderVerdict.Dead

fun span(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 120 -> "$minutes min"
        else -> "${minutes / 60} hours"
    }
}

private fun seconds(ms: Long) = "${ms / 1000} s"

private fun clip(text: String) = text.trim().replace(Regex("\\s+"), " ").let { if (it.length > HEALTH_TEXT_CHARS) it.take(HEALTH_TEXT_CHARS) + "..." else it }

/** The last step as it may be told: only the tool's name while a step waits for Boss, so nothing of what he is asked to approve. */
private fun shownStep(facts: BuilderFacts): String =
    if (facts.lastStepAt == null) "" else clip(if (facts.approval) facts.lastStep.substringBefore(':') else facts.lastStep)

private fun step(facts: BuilderFacts): String? {
    val at = facts.lastStepAt ?: return null
    return shownStep(facts).ifBlank { null }?.let { "last step ${span(facts.now - at)} ago: $it" }
}

private fun processes(n: Int) = if (n == 1) "1 leftover builder process" else "$n leftover builder processes"

/** How long the stall has lasted: as long as both the output and the CPU have been still. */
fun stalledFor(facts: BuilderFacts) = minOf(facts.quietFor, facts.stillFor ?: facts.quietFor)

/** What the verdict means, in a few words. */
fun healthSummary(facts: BuilderFacts, verdict: BuilderVerdict = builderVerdict(facts)): String = when (verdict) {
    BuilderVerdict.Idle -> "idle"
    BuilderVerdict.Leftovers -> "idle, but ${processes(facts.leftovers.size)} from earlier tasks still running"
    BuilderVerdict.WaitingForBoss -> "waiting for Boss to approve a step; nothing is wrong"
    BuilderVerdict.Dead -> "dead: its process is gone but its task, started ${span(facts.now - (facts.startedAt ?: facts.now))} ago, is still marked working"
    BuilderVerdict.Working -> "working, last output ${span(facts.quietFor)} ago"
    BuilderVerdict.Stalled -> "stalled: no output and no CPU for ${span(stalledFor(facts))}"
    BuilderVerdict.Quiet -> "quiet ${span(facts.quietFor)}" + when {
        facts.stillFor == null -> "; its CPU cannot be read"
        facts.computing -> " but computing (CPU +${seconds(facts.cpu.growthMs)} in the last ${span(facts.cpu.windowMs)})"
        else -> ", CPU barely moving (+${seconds(facts.cpu.growthMs)} in the last ${span(facts.cpu.windowMs)}); stalled if it stays so for ${span(STALL_MS)}"
    }
}

/**
 * The board's one line about the builder's health, or null when it is idle and nothing is wrong. The front
 * agent answers "is it frozen?" from this, and says where Restart is when it is stalled or dead. [name] is
 * "Builder" while the phone shows one builder, and "Builder A" or "Builder B" once B has worked.
 */
fun healthLine(facts: BuilderFacts, name: String = "Builder"): String? {
    val verdict = builderVerdict(facts)
    if (verdict == BuilderVerdict.Idle) return null
    val parts = listOfNotNull(
        healthSummary(facts, verdict),
        if (facts.working) step(facts) else null,
        if (facts.working && facts.leftovers.isNotEmpty()) "also ${processes(facts.leftovers.size)} from earlier tasks" else null,
        if (facts.working && facts.queued > 0) (if (facts.queued == 1) "1 message waits" else "${facts.queued} messages wait") + " behind it" else null,
    )
    return "$name health: " + parts.joinToString("; ") + "."
}

/**
 * What the chat entry says once a restart was carried out. [dropped] follow-ups waited behind the task and went
 * with the stop; [name] is "the builder" while there is one to speak of, and "builder B" or "builder A" after.
 */
fun restartedText(facts: BuilderFacts, dropped: Int, name: String = "the builder"): String {
    val why = if (builderVerdict(facts) == BuilderVerdict.Dead) "its process had gone" else "it had been stalled ${span(stalledFor(facts))}"
    val lost = when (dropped) {
        0 -> ""
        1 -> " The message that waited behind it was not sent; say it again."
        else -> " The $dropped messages that waited behind it were not sent; say them again."
    }
    return "Restarted $name: $why.$lost"
}

const val RESTART_NOTHING = "The builder had already finished, so nothing was restarted."
const val RESTART_APPROVAL = "The builder is waiting for your approval, so it was not restarted."
const val RESTART_STUCK = "Stopped the builder, but its task has not let go yet, so it was not sent again. Try Restart again in a minute."

/** What the chat entry says once leftovers were looked for: [found] of them, [ended] of those now gone. */
fun cleanedText(found: Int, ended: Int): String = when {
    found == 0 -> "No leftover builder processes were found."
    ended == found -> if (ended == 1) "Ended 1 leftover builder process." else "Ended $ended leftover builder processes."
    else -> "Ended $ended of $found leftover builder processes; the rest would not end."
}

/** Where the launcher writes how its agents stand, in the Linux home, for `oma status`. */
const val AGENTS_STATUS_FILE = ".cache/oma-agents/status.json"

private fun minutesAgo(now: Long, at: Long?) = at?.let { (now - it).coerceAtLeast(0) / 60_000 }

/**
 * The status file: the builder's facts and verdict, and the Mac's threads as the phone last heard of them.
 * Times are minutes before [BuilderFacts.now], which [written] states. It never holds a key, a nonce or anything
 * of an approval but how many wait, and of a request or a thread's words only the first [HEALTH_TEXT_CHARS].
 * [tailscale] is why an unreachable Mac is out of reach when Tailscale was found off ([healNote]); it is left
 * out while the Mac answers, since then it no longer explains anything.
 */
fun agentsStatus(facts: BuilderFacts, mac: MacView?, written: String, second: BuilderFacts? = null, tailscale: String? = null): JSONObject {
    val now = facts.now
    val root = JSONObject().put("written", written).put("builder", builderStatus(facts))
    // Builder B, once it has worked; "builder" is then builder A, the one there always was.
    if (second != null) root.put("builderB", builderStatus(second))
    if (mac != null) root.put("mac", JSONObject()
        .put("reachable", mac.reachable).put("lastSeenMinAgo", minutesAgo(now, mac.seen))
        .put("tailscale", tailscale?.takeIf { !mac.reachable })
        // Each Mac set up, once there is more than one: one out of reach is said here and its threads stay listed.
        .apply { if (mac.macs.size >= 2) put("macs", JSONArray(mac.macs.map { seen ->
            JSONObject().put("name", seen.name).put("reachable", seen.reachable).put("lastSeenMinAgo", minutesAgo(now, seen.seen))
        })) }
        .put("threads", JSONArray(mac.threads.map { thread ->
            JSONObject().put("name", clip(thread.name)).put("id", thread.id).put("status", thread.status.word.ifEmpty { "unknown" })
                .apply { mac.machineFor(thread.id).takeIf { it.isNotEmpty() }?.let { put("mac", it) } }
                .put("lastWorkedMinAgo", thread.lastWorked?.let { minutesAgo(now, it * 1000) })
                .put("asked", clip(thread.summary)).put("lastSaid", thread.tail.trim().replace(Regex("\\s+"), " ").takeLast(HEALTH_TEXT_CHARS))
                .put("approvalsWaiting", thread.pending.size)
                .put("background", JSONArray(thread.background.map(::clip)))
        })))
    return root
}

/** One builder's part of the status file. */
private fun builderStatus(facts: BuilderFacts): JSONObject {
    val now = facts.now
    val verdict = builderVerdict(facts)
    return JSONObject()
        .put("verdict", verdict.word).put("health", healthSummary(facts, verdict)).put("working", facts.working)
        .put("request", if (facts.working) clip(facts.request) else "")
        .put("startedMinAgo", if (facts.working) minutesAgo(now, facts.startedAt) else null)
        .put("lastOutputMinAgo", if (facts.working) minutesAgo(now, facts.heardAt) else null)
        .put("lastStep", if (facts.working) shownStep(facts) else "")
        .put("lastStepMinAgo", if (facts.working) minutesAgo(now, facts.lastStepAt) else null)
        .put("pid", facts.pid).put("alive", facts.alive)
        .put("cpuSeconds", facts.cpuMs?.div(1000))
        .put("cpuSecondsInWindow", facts.cpu.growthMs / 1000).put("cpuWindowMin", facts.cpu.windowMs / 60_000)
        .put("cpuLastRoseMinAgo", minutesAgo(now, facts.cpu.movedAt))
        .put("waitingForApproval", facts.approval).put("followUpsWaiting", facts.queued)
        .put("leftovers", JSONArray(facts.leftovers.map { JSONObject().put("pid", it.pid).put("startedMinAgo", minutesAgo(now, it.startedAt)).put("cpuSeconds", it.cpuMs / 1000) }))
}
