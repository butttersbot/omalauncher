package dev.omalauncher.assist

import dev.omalauncher.canvas.macTabKey
import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.archivedAs
import dev.omalauncher.oma.restored

/*
 * One archive for a thread, kept on its runner and read by every Soma, the phone included (A, 2026-10-29: archiving on
 * one device and seeing it come back on another was a one-way road). The tab book stays what the strip and All chats
 * read; for a thread whose runner keeps the archive ([MacThread.sharesArchive]), the book follows the runner, and his
 * archive or restore here goes to the runner. Each runner thread remembers what the two last agreed ([TabMeta.agreed], by
 * the thread, not the tab: a chat that goes on on another thread starts that one afresh; review round 1): a side that
 * differs from it is the side that changed. A change made here that can't be sent yet stays different, so it goes at the
 * next look (a sleeping Mac's queue, for free). The first look at such a runner joins the two: archived on either side
 * is archived on both. An older runner, and anything with no runner thread (the phone's builders, the front chat, a new
 * chat not made yet), keeps the book's own archive as before.
 */

/** What one look decides: [send] each thread's archive to its runner (id to archived), and [change] the book. */
class ArchiveShare(val send: List<Pair<String, Boolean>>, val change: ((TabMeta) -> TabMeta)?)

/** What to do about [threads] (one runner's list) against [meta], the book as it is now. */
fun archiveShare(meta: TabMeta, threads: List<MacThread>): ArchiveShare {
    val send = mutableListOf<Pair<String, Boolean>>()
    val changes = mutableListOf<(TabMeta) -> TabMeta>()
    for (t in threads) {
        if (!t.sharesArchive || t.bot != null) continue
        val key = macTabKey(t.id, meta)
        val agreedKey = macThreadKey(t.id)
        val here = key in meta.archived
        val there = t.archived != null
        val agreed = meta.agreed[agreedKey]
        fun take() {
            // Only onto the book as it was looked at: an archive or restore he made since then stands, and goes at the next look.
            changes += { m ->
                if ((macTabKey(t.id, m) in m.archived) != here) m
                else (if (there) m.archivedAs(key, t.archived ?: System.currentTimeMillis()) else m.restored(key)).agreedAs(agreedKey, there)
            }
        }
        when {
            // First look: joined. Archived on either side is archived on both.
            agreed == null -> when {
                here && !there -> send += t.id to true
                else -> take()
            }
            here == there -> if (agreed != here) changes += { m -> m.agreedAs(agreedKey, here) }
            // Changed here (or changed on both, differently: his change on this device wins): it goes to the runner.
            here != agreed -> send += t.id to here
            // Changed on another Soma: the book follows.
            else -> take()
        }
    }
    return ArchiveShare(send, if (changes.isEmpty()) null else { m -> changes.fold(m) { acc, f -> f(acc) } })
}

/** The runner thread [key] ([macThreadKey]) as agreed with its runner: archived ([on]) or not. */
fun TabMeta.agreedAs(key: String, on: Boolean): TabMeta = if (agreed[key] == on) this else copy(agreed = agreed + (key to on))
