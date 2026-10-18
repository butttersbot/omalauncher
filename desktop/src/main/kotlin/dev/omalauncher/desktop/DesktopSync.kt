package dev.omalauncher.desktop

import dev.omalauncher.canvas.ScreenSync
import dev.omalauncher.canvas.findSomaService
import dev.omalauncher.canvas.syncExchange
import dev.omalauncher.widgets.layout.VerbJson
import java.io.File

/*
 * Soma desktop's side of sync (slice 8b): this window's canvas kept on the service as a screen of its own ("desk-<this
 * machine>"), so an agent asked from anywhere ("move Needs you on my desk") works on it as he last left it. Its own layout
 * only: nothing is mirrored from or to another screen. What the service gave is kept beside the canvas between starts.
 */
class DesktopSync(private val canvas: DesktopCanvas, private val file: File, machine: String) {
    /** What the service gave, as kept beside the canvas; null when it isn't there or can't be read. */
    private val kept = runCatching { VerbJson.logOf(file.readText()) }.getOrNull()

    val sync = ScreenSync("desk-" + machine.replace(Regex("[^a-z0-9-]"), "-").take(34), DESKTOP_COLUMNS, DESKTOP_ROWS,
        kept ?: dev.omalauncher.widgets.layout.VerbLog("desk"))

    /**
     * Without what the service gave (never synced, or the file lost or cut short), the first round reads the service's copy
     * and sends nothing: where the window's log and that copy part is worked out from both, so nothing old goes up again
     * (round-4 review: a lost file sent the window's whole history over an agent's newer changes).
     */
    private var readFirst = kept == null

    // How far the window's log is the service's, worked out again each start (round-2 review: it started at nothing, so the
    // whole log went up again and older changes were played over an agent's newer ones).
    init { canvas.resumeSync(sync.synced) }

    /** What the last round said, so a refusal that keeps coming is said once, not every half minute. */
    private var lastSaid: List<String> = emptyList()

    /**
     * A window whose saved layout couldn't be read starts from the default page: it takes the service's copy first, never
     * sends the default over it (round-3 review). Once, at the first round.
     */
    private var restoring = canvas.setAside != null

    /**
     * One round with [exchange] (the network): what changed here goes up, the canonical log comes back and becomes the
     * window's, on the event thread through [onWindow]. Null when the service couldn't be reached. A refusal changes
     * nothing here (round-2 review: the refused change was dropped): it goes again next round. What it says is new.
     */
    fun round(exchange: (screen: String, since: Long, sent: List<dev.omalauncher.canvas.SentVerb>) -> org.json.JSONObject?, onWindow: (() -> Unit) -> Unit = { it() }): List<String>? {
        var pending: List<dev.omalauncher.canvas.SentVerb> = emptyList()
        var upTo = 0L
        if (readFirst) {
            val (target, notes) = sync.round(emptyList()) { since, sent -> exchange(sync.screen, since, sent) } ?: return null
            if (target == null) return said(notes)
            readFirst = false
            // An empty copy (a first sync): the window goes up whole, now, as any first round.
            if (sync.synced.version > 0) {
                restoring = false
                keep()
                // The service's copy becomes the window's, with what the window has past where they part on top (to go up
                // next round); a window that started from the default page takes the copy as it is.
                onWindow {
                    canvas.resumeSync(sync.synced)
                    canvas.adopt(sync.synced, if (canvas.setAside != null) canvas.log.version else canvas.syncedTo)
                }
                return said(notes)
            }
        }
        if (restoring) {
            restoring = false
            if (sync.synced.version > 0) onWindow { canvas.adopt(sync.synced, canvas.log.version) }
        }
        // Where the window's log and the service's copy part, worked out each round: after the service lost its copy this
        // window's whole log goes, and the copy becomes it.
        onWindow { canvas.resumeSync(sync.synced); pending = canvas.pendingSync(sync.synced.version); upTo = canvas.log.version }
        val (target, notes) = sync.round(pending) { since, sent -> exchange(sync.screen, since, sent) } ?: return null
        if (target != null) {
            // What the service gave is kept before the window takes it: a start between the two sends this round's changes
            // once more, which the service takes as the same moves again or drops with a note (an agent's change made in
            // that moment to the same thing can be moved back; the window is a few milliseconds).
            keep()
            onWindow { canvas.adopt(sync.synced, upTo) }
        }
        return said(notes)
    }

    /** What the round says, when it is new. */
    private fun said(notes: List<String>): List<String> {
        if (notes == lastSaid) return emptyList()
        lastSaid = notes
        return notes
    }

    /** What the service gave, written whole: a stop mid-write leaves the one before, never half of it. */
    private fun keep() = runCatching {
        val fresh = File(file.parentFile, "." + file.name + ".new")
        fresh.writeText(VerbJson.log(sync.synced))
        if (!fresh.renameTo(file)) { file.delete(); fresh.renameTo(file) }
    }

    companion object {
        /** A round against the service its runners advertise, with the key for that runner; null when none says it has one. */
        fun overTheTailnet(machines: List<dev.omalauncher.assist.MacMachine>): ((String, Long, List<dev.omalauncher.canvas.SentVerb>) -> org.json.JSONObject?)? {
            val (service, key) = findSomaService(machines) ?: return null
            return { screen, since, sent -> syncExchange(service, key, screen, DESKTOP_COLUMNS, DESKTOP_ROWS, since, sent) }
        }
    }
}
