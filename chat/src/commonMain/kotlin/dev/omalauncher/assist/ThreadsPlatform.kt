package dev.omalauncher.assist

import dev.omalauncher.oma.ChatStore
import dev.omalauncher.oma.TabMeta

/*
 * What following the threads on the computers ([MacThreadsEngine]) asks of the device it runs on: the phone (with its
 * builders, notifications and Tailscale to heal) or the desktop app (cockpit/DESIGN.md §6). The engine is the same on
 * both; this is the whole of what differs.
 */
interface ThreadsPlatform {
    /** The computers set up, read fresh each time, so one is there the moment its file is in place. */
    fun machines(): List<MacMachine>

    /** The Soma thread, kept on disk. */
    fun chatLog(): ChatStore

    /** The tab book as it stands. */
    fun book(): TabMeta

    /** The tab book after [change], saved when it changed. */
    fun changeBook(change: (TabMeta) -> TabMeta): TabMeta

    /** What the engine keeps between runs (where it read each thread to, when each computer was last seen, limits). */
    fun prefString(key: String): String?

    fun prefLong(key: String): Long

    fun putPref(key: String, value: String)

    fun putPref(key: String, value: Long)

    /** The lock every entry's time is taken under, shared with whatever else files entries (the phone's builders). */
    val stampLock: Any

    /** No card from an earlier run can be answered: whatever shows them is cleared. */
    fun clearStaleCards()

    /** A request of a thread's that waits for his OK, shown where he will see it. */
    fun requestCard(entry: MacPending)

    /** That request no longer waits. */
    fun withdrawCard(entry: MacPending)

    /** Tells him a request ended or could not be sent, over whatever is in front: [front] for the front agent's, else a thread's. */
    fun answeredNotice(label: String, answer: String, front: Boolean)

    /** Whether the device is locked, when nothing may be answered from it. */
    fun locked(): Boolean

    /** What the front agent's board says of the device's network link at [now], or null. */
    fun linkNote(now: Long): String?

    /** How the last try to reach a computer went, for whatever heals the device's link. */
    fun linkAfter(link: MacLink)

    /** What a thread does changed: the status the agents read is written again. */
    fun statusChanged()

    /** The threads' work looks different: whatever shows it (tiles, ring, badges) is told. */
    fun threadsChanged()

    /** A request went somewhere else than it first did ([was] says how), for the routing log. */
    fun routingCorrected(request: String, was: String)

    /** Runs [block] on the screen's thread. */
    fun post(block: () -> Unit)
}
