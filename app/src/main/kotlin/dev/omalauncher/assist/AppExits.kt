package dev.omalauncher.assist

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/*
 * Why Soma's process ended the last few times, in Android's own words, written where the guest reads it (Boss,
 * 2026-10-17: a build took the app and its Linux down mid-work, and without adb nobody could say why). `oma status`
 * prints it, so the next time everything restarts the cause is read, not guessed: low memory, a crash, an update.
 */

/** Where the guest finds it: ~/.cache/oma-agents/app-exits.txt, beside the status file. */
private const val EXITS_FILE = "arch/root/.cache/oma-agents/app-exits.txt"

/** How many of the latest exits are kept. */
private const val EXITS_KEPT = 8

/** Android's reason for an exit (ApplicationExitInfo.REASON_*), in a few words. */
fun exitReason(reason: Int): String = when (reason) {
    1 -> "exited by itself"
    2 -> "killed by a signal"
    3 -> "killed for low memory"
    4 -> "crashed"
    5 -> "crashed in native code"
    6 -> "not responding (ANR)"
    7 -> "failed to start"
    8 -> "a permission changed"
    9 -> "used too much of a resource"
    10 -> "stopped at the owner's request"
    11 -> "force-stopped"
    12 -> "a process it needs died"
    13 -> "other"
    14 -> "frozen"
    15 -> "its package changed"
    16 -> "its package was updated"
    else -> "unknown"
}

/** One exit as a line: when, why, how big the process was, and Android's own note. */
fun exitLine(at: Long, reason: Int, pssKb: Long, description: String?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(at))
    val size = if (pssKb > 0) ", ${pssKb / 1024} MB in use" else ""
    val note = description?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    return "$time  ${exitReason(reason)}$size$note"
}

object AppExits {
    /** Writes the latest exits for the guest, off the caller's thread. Nothing on Android before 11, which keeps none. */
    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val app = context.applicationContext
        thread(name = "oma-app-exits", isDaemon = true) {
            runCatching {
                val exits = app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, EXITS_KEPT)
                val file = File(app.filesDir, EXITS_FILE)
                file.parentFile?.mkdirs()
                file.writeText(exits.joinToString("\n", postfix = "\n") { exitLine(it.timestamp, it.reason, it.pss, it.description) })
            }
        }
    }
}
