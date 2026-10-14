package dev.omalauncher.assist

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the processor on while an agent works with the display off, as `caffeinate` does on a Mac: the foreground
 * service keeps the process, but Android still puts the processor to sleep once the phone is locked, and a task
 * stopped half-way until the phone was picked up again (Boss, 2026-10-13). The display is left to sleep.
 */
object StayAwake {
    private var lock: PowerManager.WakeLock? = null

    /** Called every [CHECK_MILLIS] while the Linux session runs; holds the lock for as long as [wanted] says. */
    @Synchronized fun check(context: Context) {
        val want = wanted(AgentTasks.working, QuickLane.working, MacThreads.following, AgentTasks.pending.size + MacThreads.pending.size)
        if (want) {
            val held = lock ?: context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).also { it.setReferenceCounted(false); lock = it }
            // Taken again on every check with a limit, so a check that stops coming lets go by itself.
            held.acquire(HOLD_MILLIS)
        } else release()
    }

    @Synchronized fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }

    private const val TAG = "omalauncher:agent-working"
}

const val CHECK_MILLIS = 5_000L

/** How long one check keeps the processor on: several checks, so a late one does not let it sleep in between. */
const val HOLD_MILLIS = 60_000L

/**
 * Whether the processor has to stay on: an agent is working here, the front agent is answering, or a thread on the
 * Mac is being followed. A request that only waits for the owner does not keep it on: its notification is already
 * up, nothing moves until he answers, and a phone left on a table overnight should sleep.
 */
fun wanted(building: Boolean, answering: Boolean, following: Boolean, waiting: Int): Boolean =
    if (waiting > 0 && !answering) false else building || answering || following
