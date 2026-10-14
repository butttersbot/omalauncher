package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PHONE_THREAD_B
import dev.omalauncher.assist.PHONE_THREAD_B_NAME
import dev.omalauncher.assist.PHONE_THREAD_NAME
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.builderId
import dev.omalauncher.assist.builderVerdict
import dev.omalauncher.assist.macThreadId
import dev.omalauncher.assist.macThreadKey
import dev.omalauncher.assist.restartable

/*
 * The Oma chat's tabs (Boss, 2026-10-13; design/oma-threads-mockup.html): the Oma tab, which is the one
 * stream there always was, and a tab for each thread, which shows that thread's entries alone and whose field
 * talks straight to it. A tab is known by its thread's key ([AgentThread.key]), the Oma tab by [OMA_TAB].
 * Which tabs there are, which one an intent opens, what a tab shows and what a tap on a dot does are plain
 * functions here, unit tested; the screen only draws what they say. Since Boss looked at it on the phone
 * (2026-10-13), ＋ after the last tab starts a new chat (TabBook.kt), and a long press on a tab opens a menu
 * whose rows are decided here too ([tabMenu]).
 */

/** The Oma tab's key. No thread has it: the builders' are "phone" and "phone-b", and a Mac thread's begins "mac:". */
const val OMA_TAB = "oma"

/** The tabs in the order the strip shows them: Oma, then [threads] as they come, the builder first and the Mac's newest worked after it. */
fun chatTabs(threads: List<AgentThread>): List<String> = listOf(OMA_TAB) + threads.map { it.key }

/**
 * The threads the header and the strip show, in their order: what he archived is left out, and what he pinned
 * comes first, in the order the threads stand in. [archived] are the keys archived now, revived ones left out.
 */
fun shownThreads(threads: List<AgentThread>, pinned: List<String>, archived: Set<String>): List<AgentThread> {
    val kept = threads.filter { it.key !in archived }
    return kept.filter { it.key in pinned } + kept.filter { it.key !in pinned }
}

/**
 * The tab that shows when [wanted] was asked for, by a tile's intent or a tap: that thread's while it is among
 * [threads], and the Oma tab for nothing asked, for a key that is no thread's, and for a thread not heard of
 * (yet: it is asked again as the threads change, so a tab the Mac has still to list opens when it does).
 */
fun openTab(wanted: String?, threads: List<AgentThread>): String = wanted?.takeIf { key -> threads.any { it.key == key } } ?: OMA_TAB

/** The tab a tap on the dot of [tapped] leaves open while [open] shows: that thread's, or back to Oma from the dot that is already open. */
fun dotTap(open: String, tapped: String): String = if (tapped == open) OMA_TAB else tapped

/**
 * The entries [tab] shows, oldest first as [tasks] are: all of them on the Oma tab, on a builder's tab the
 * build lane's that went to that builder in its own conversation, and on a Mac thread's tab the requests that
 * went to that thread. A new chat's tab shows what was sent in it, the front agent's answers there included,
 * and whatever else went to the Mac thread made for it. A request the front agent answered on the Oma tab, or
 * has not decided, is on the Oma tab alone.
 */
fun tabEntries(tasks: List<ChatTask>, tab: String, meta: TabMeta = TabMeta()): List<ChatTask> {
    meta.chatOf(tab)?.let { chat -> return tasks.filter { it.chat == chat.id || (chat.mac.isNotEmpty() && it.thread == chat.mac) } }
    return when {
        tab == OMA_TAB -> tasks
        tab == PHONE_THREAD -> tasks.filter { it.build && it.builderId == BuilderId.A && it.chat.isEmpty() }
        tab == PHONE_THREAD_B -> tasks.filter { it.build && it.builderId == BuilderId.B && it.chat.isEmpty() }
        else -> macThreadId(tab)?.let { id -> tasks.filter { it.thread == id && it.chat.isEmpty() } }.orEmpty()
    }
}

/** The tab an entry's receipt ("→ NAME") opens when tapped on the Oma tab: its chat's, if a chat has its thread; null for an entry with no receipt. */
fun receiptTab(task: ChatTask, meta: TabMeta = TabMeta()): String? = when {
    !task.mac -> null
    task.chat.isNotEmpty() && meta.chat(task.chat) != null -> chatKey(task.chat)
    else -> meta.chatOnMac(task.thread)?.key ?: macThreadKey(task.thread)
}

/** The Mac thread whose waiting requests [tab] shows: a Mac thread's own, or the one made for a new chat; null for any other tab. */
fun tabMacThread(tab: String, meta: TabMeta = TabMeta()): String? = macThreadId(tab) ?: meta.chatOf(tab)?.mac?.takeIf { it.isNotEmpty() }

/** What the header calls the open tab: the thread's name, or "Oma". */
fun tabTitle(tab: String, threads: List<AgentThread>): String = threads.firstOrNull { it.key == tab }?.name ?: "Oma"

/** What the empty field says on a thread's tab; null on the Oma tab, which keeps its own hints. */
fun fieldHint(to: SendTo): String? = when (to) {
    SendTo.Oma -> null
    SendTo.Phone -> "to $PHONE_THREAD_NAME…"
    SendTo.PhoneB -> "to $PHONE_THREAD_B_NAME…"
    is SendTo.Mac -> "to ${to.name}…"
    is SendTo.Chat -> "start this chat on the Mac…"
    is SendTo.ChatStarting -> "starting a new thread on the Mac…"
    is SendTo.ChatProject -> "name a project on the Mac…"
}

/**
 * Whether the red stop beside the field shows. It is the phone builders' stop, and takes what the front agent
 * is deciding with it: so it shows on the Oma tab while either goes on, on a builder's tab while that builder
 * works ([working] is then that builder's), on a new chat with nowhere to go yet while the front agent decides
 * its words ([deciding] is then that chat's), and never on a Mac thread's tab, whose tasks each have a stop of
 * their own on their entry, nor on a chat that waits for him to name its project.
 */
fun fieldStop(to: SendTo, working: Boolean, deciding: Boolean): Boolean = when (to) {
    SendTo.Oma -> working || deciding
    SendTo.Phone, SendTo.PhoneB -> working
    is SendTo.Chat, is SendTo.ChatStarting -> deciding
    is SendTo.Mac, is SendTo.ChatProject -> false
}

/** What a long press on a tab can do. */
enum class TabAction(val label: String) {
    Rename("Rename"),
    Pin("Pin"),
    Unpin("Unpin"),
    Stop("Stop"),

    /** A stuck phone builder's task stopped and sent again ([tabMend]). */
    Restart("Restart"),

    /** Builder processes no task owns ended ([tabMend]). */
    Clean("Clean up leftovers"),
    Copy("Copy last answer"),
    Archive("Archive"),

    /** Lists the archived tabs, from which one is brought back. Always the last row. */
    Archived("Archived"),
}

/**
 * The rows of the menu a long press on [tab] opens, in order. [pinned] is whether it is pinned, [stoppable]
 * whether it has a task its stop would end ([tabStop]), [mend] what of a phone builder it can mend ([tabMend]),
 * [answered] whether it has an answer to copy ([lastAnswer]), and [archived] how many tabs are archived. The Oma
 * tab can be neither renamed, pinned nor archived: it is the stream everything else hangs from. No row deletes a
 * word of history or a thread on the Mac, and none answers an approval.
 */
fun tabMenu(tab: String, pinned: Boolean, stoppable: Boolean, answered: Boolean, archived: Int, mend: TabMend = TabMend()): List<TabAction> =
    if (tab == OMA_TAB) {
        listOfNotNull(TabAction.Copy.takeIf { answered }, TabAction.Archived.takeIf { archived > 0 })
    } else {
        listOfNotNull(
            TabAction.Rename,
            if (pinned) TabAction.Unpin else TabAction.Pin,
            TabAction.Stop.takeIf { stoppable },
            TabAction.Restart.takeIf { mend.restart != null },
            TabAction.Clean.takeIf { mend.clean },
            TabAction.Copy.takeIf { answered },
            TabAction.Archive,
            TabAction.Archived.takeIf { archived > 0 },
        )
    }

/**
 * What a phone builder's tab can mend (Boss, 2026-10-14: stop, restart and clean are buttons on the tab, not the
 * front agent's): [restart] is its builder while that one is stalled or dead, and [clean] whether builder
 * processes no task owns are running.
 */
data class TabMend(val restart: BuilderId? = null, val clean: Boolean = false)

/** The phone builder whose tab [tab] is; null for every other tab. */
fun tabBuilder(tab: String): BuilderId? = when (tab) {
    PHONE_THREAD -> BuilderId.A
    PHONE_THREAD_B -> BuilderId.B
    else -> null
}

/**
 * What [tab]'s menu offers to mend, from each builder's facts at the last look ([seen]). Only a phone builder's
 * tab offers either: Restart while its builder is [restartable], which one waiting for his approval never is,
 * and Clean up leftovers on either phone tab while any are found. The restart itself looks again and checks
 * again before it stops anything, so a menu opened on an older look cannot restart a builder that moved since.
 */
fun tabMend(tab: String, seen: Map<BuilderId, BuilderFacts>): TabMend {
    val builder = tabBuilder(tab) ?: return TabMend()
    val facts = seen[builder]
    val stuck = facts != null && !facts.approval && restartable(builderVerdict(facts))
    return TabMend(builder.takeIf { stuck }, seen.values.any { it.leftovers.isNotEmpty() })
}

/** The row that lists the archived tabs, with how many there are. */
fun archivedRow(count: Int) = "${TabAction.Archived.label} ($count)"

/** Whose task a tab's Stop ends: one of the phone's builders, or a thread on the Mac. */
sealed interface TabStop {
    data class Builder(val builder: BuilderId) : TabStop

    data class Mac(val thread: String) : TabStop
}

/**
 * What Stop in [tab]'s menu stops, the same as its entry's `[ stop ]`: while [thread] works, queues or waits for
 * his OK, its builder or its Mac thread, a new chat's being the thread made for it; null otherwise, and for a
 * chat whose first message has gone nowhere yet, whose entry has its own way out.
 */
fun tabStop(tab: String, thread: AgentThread?, meta: TabMeta): TabStop? {
    if (thread == null || !thread.state.active || thread.state == ThreadState.Attention) return null
    meta.chatOf(tab)?.let { chat -> return chat.mac.takeIf { it.isNotEmpty() }?.let(TabStop::Mac) }
    return when (tab) {
        PHONE_THREAD -> TabStop.Builder(BuilderId.A)
        PHONE_THREAD_B -> TabStop.Builder(BuilderId.B)
        else -> macThreadId(tab)?.let(TabStop::Mac)
    }
}

/** The newest finished answer among [entries], as it was written, for Copy last answer; null when none has one. */
fun lastAnswer(entries: List<ChatTask>): String? = entries.lastOrNull { it.status == TaskStatus.Done && it.result.isNotBlank() }?.result?.trim()

/** What a Mac thread's tab says above its entries: the phone shows what it sent, not what was begun at the Mac. */
const val MAC_EARLIER = "Earlier work on the Mac is not shown"
