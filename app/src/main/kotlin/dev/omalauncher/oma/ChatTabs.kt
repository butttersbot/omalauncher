package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PHONE_THREAD_NAME
import dev.omalauncher.assist.macThreadId
import dev.omalauncher.assist.macThreadKey

/*
 * The Oma chat's tabs (Boss, 2026-10-13; design/oma-threads-mockup.html): the Oma tab, which is the one
 * stream there always was, and a tab for each thread, which shows that thread's entries alone and whose field
 * talks straight to it. A tab is known by its thread's key ([AgentThread.key]), the Oma tab by [OMA_TAB].
 * Which tabs there are, which one an intent opens, what a tab shows and what a tap on a dot does are plain
 * functions here, unit tested; the screen only draws what they say.
 */

/** The Oma tab's key. No thread has it: the builder's is "phone" and a Mac thread's begins "mac:". */
const val OMA_TAB = "oma"

/** The tabs in the order the strip shows them: Oma, then [threads] as they come, the builder first and the Mac's newest worked after it. */
fun chatTabs(threads: List<AgentThread>): List<String> = listOf(OMA_TAB) + threads.map { it.key }

/**
 * The tab that shows when [wanted] was asked for, by a tile's intent or a tap: that thread's while it is among
 * [threads], and the Oma tab for nothing asked, for a key that is no thread's, and for a thread not heard of
 * (yet: it is asked again as the threads change, so a tab the Mac has still to list opens when it does).
 */
fun openTab(wanted: String?, threads: List<AgentThread>): String = wanted?.takeIf { key -> threads.any { it.key == key } } ?: OMA_TAB

/** The tab a tap on the dot of [tapped] leaves open while [open] shows: that thread's, or back to Oma from the dot that is already open. */
fun dotTap(open: String, tapped: String): String = if (tapped == open) OMA_TAB else tapped

/**
 * The entries [tab] shows, oldest first as [tasks] are: all of them on the Oma tab, the build lane's on the
 * builder's tab, and on a Mac thread's tab the requests that went to that thread. A request the front agent
 * answered itself, or has not decided, is on the Oma tab alone.
 */
fun tabEntries(tasks: List<ChatTask>, tab: String): List<ChatTask> = when {
    tab == OMA_TAB -> tasks
    tab == PHONE_THREAD -> tasks.filter { it.build }
    else -> macThreadId(tab)?.let { id -> tasks.filter { it.thread == id } }.orEmpty()
}

/** The tab an entry's receipt ("→ NAME") opens when tapped on the Oma tab; null for an entry with no receipt. */
fun receiptTab(task: ChatTask): String? = if (task.mac) macThreadKey(task.thread) else null

/** What the header calls the open tab: the thread's name, or "Oma". */
fun tabTitle(tab: String, threads: List<AgentThread>): String = threads.firstOrNull { it.key == tab }?.name ?: "Oma"

/** The line under the log on a thread's tab; null on the Oma tab, where the front agent hears his words first as it always did. */
fun straightLine(to: SendTo): String? = when (to) {
    SendTo.Oma -> null
    SendTo.Phone -> "straight to $PHONE_THREAD_NAME · Oma is not asked"
    is SendTo.Mac -> "straight to ${to.name} · Oma is not asked"
}

/** What the empty field says on a thread's tab; null on the Oma tab, which keeps its own hints. */
fun fieldHint(to: SendTo): String? = when (to) {
    SendTo.Oma -> null
    SendTo.Phone -> "to $PHONE_THREAD_NAME…"
    is SendTo.Mac -> "to ${to.name}…"
}

/**
 * Whether the red stop beside the field shows. It is the phone builder's stop, and takes what the front agent
 * is deciding with it: so it shows on the Oma tab while either goes on, on the builder's tab while the builder
 * works, and never on a Mac thread's tab, whose tasks each have a stop of their own on their entry.
 */
fun fieldStop(to: SendTo, working: Boolean, deciding: Boolean): Boolean = when (to) {
    SendTo.Oma -> working || deciding
    SendTo.Phone -> working
    is SendTo.Mac -> false
}

/** What a Mac thread's tab says above its entries: the phone shows what it sent, not what was begun at the Mac. */
const val MAC_EARLIER = "Earlier work on the Mac is not shown"
