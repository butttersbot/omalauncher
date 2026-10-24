package dev.omalauncher.assist

import android.system.Os
import android.system.OsConstants
import kotlin.concurrent.thread

/**
 * Everything a run started through proot: the proot ([root]), what it started and their children, and whatever
 * else it traces, such as a build daemon that has left its parent.
 */
fun runTree(rows: List<ProcRow>, root: Int): List<ProcRow> {
    val tree = mutableSetOf(root)
    while (true) {
        val children = rows.filter { it.ppid in tree && it.pid !in tree }
        if (children.isEmpty()) break
        children.forEach { tree.add(it.pid) }
    }
    return rows.filter { it.pid in tree || it.tracer == root }
}

/**
 * Ends a run for good (Boss, 2026-10-14: a stopped builder kept building the keyboard unseen, and the next
 * message started a second copy of the same conversation beside it). Process.destroy() only signals proot,
 * which ignores SIGTERM and passes nothing on, so the agent inside ran on. Here each process of the run is
 * asked to end, then made to, and proot goes last: started with --kill-on-exit, it takes along anything left.
 * Each is checked again by its pid and start before a signal, so a pid taken since by something else is never touched.
 */
object RunEnd {
    /** Android's process says its pid only in its name, "Process[pid=1234, hasExited=false]"; its field as a fallback. */
    fun pid(process: Process): Int? =
        Regex("""pid=(\d+)""").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
            ?: runCatching { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process) }.getOrNull()

    /** Ends [process] and all it started, off the caller's thread; [done] runs once it is over. */
    fun end(process: Process, done: () -> Unit = {}) {
        thread(name = "oma-run-end", isDaemon = true) {
            try {
                val root = pid(process)
                if (root != null && process.isAlive) {
                    val found = runTree(BuilderWatch.rows(), root).filter { it.pid != root }
                    fun still() = runTree(BuilderWatch.rows(), root).filter { left -> found.any { it.pid == left.pid && it.startedAt == left.startedAt } }
                    still().forEach { runCatching { Os.kill(it.pid, OsConstants.SIGTERM) } }
                    val until = System.currentTimeMillis() + GRACE_MS
                    while (still().isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(200)
                    still().forEach { runCatching { Os.kill(it.pid, OsConstants.SIGKILL) } }
                }
                process.destroyForcibly()
                runCatching { process.waitFor(GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
            } finally {
                done()
            }
        }
    }

    /**
     * Ends whatever is left of a stopped run whose agent resumed [sessions] or began at [began] ([lingeringAgents]):
     * asked, then made, to end, and waited for, so the next request never starts beside it. On the caller's thread.
     */
    fun endLingering(
        sessions: Set<String>,
        began: Long,
    ) {
        fun left() = runCatching { BuilderWatch.rows() }.getOrDefault(emptyList()).let { rows -> lingeringAgents(rows, sessions, began, android.os.Process.myPid()).flatMap { runTree(rows, it.pid) }.distinctBy { it.pid } }
        val first = left()
        if (first.isEmpty()) return
        android.util.Log.w("oma-run-end", "stopped run still had ${first.size} process(es); ending them")
        first.forEach { runCatching { Os.kill(it.pid, OsConstants.SIGTERM) } }
        var until = System.currentTimeMillis() + GRACE_MS
        while (left().isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(200)
        left().forEach { runCatching { Os.kill(it.pid, OsConstants.SIGKILL) } }
        until = System.currentTimeMillis() + GRACE_MS
        while (left().isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(200)
    }

    private const val GRACE_MS = 3_000L
}
