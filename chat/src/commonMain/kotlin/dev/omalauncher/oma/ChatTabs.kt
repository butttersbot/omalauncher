package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.PHONE_THREAD_B_NAME
import dev.omalauncher.assist.PHONE_THREAD_C_NAME
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

/** The Oma tab's key. No thread has it: the builders' are "phone", "phone-b" and "phone-c", and a Mac thread's begins "mac:". */
const val OMA_TAB = "oma"

/**
 * The tabs in the order the strip shows them: Oma, then [threads] as they come, the builder first and the Mac's newest
 * worked after it. No Oma tab where there is no front agent to talk to ([soma] false: the desktop, [ChatHost.somaTab]).
 */
fun chatTabs(threads: List<AgentThread>, soma: Boolean = true): List<String> = (if (soma) listOf(OMA_TAB) else emptyList()) + threads.map { it.key }

/**
 * The threads the header and the strip show, in their order: the [live] ones, which are the threads home has
 * tiles for ([dev.omalauncher.widgets.liveThreads]: at work, or within 12 hours of it, or pinned, and never
 * archived), what he pinned first, in the order they stand in. The tab [wanted], by a tile he keeps on another
 * page or a receipt in the Oma stream, is shown after them while it is open, though its thread has rested longer:
 * it is one of [all], every thread there is, and not [archived], the keys archived now.
 */
fun shownThreads(live: List<AgentThread>, pinned: List<String>, all: List<AgentThread> = emptyList(), archived: Set<String> = emptySet(), wanted: String? = null): List<AgentThread> {
    // Bots are always shown, first, however long they have rested (soma-bots PRD §6); then the rest as before.
    val bots = (all + live).filter { it.bot != null }.distinctBy { it.key }
    // The phone's builders are always there too, first, however long they have rested, so either can be written to
    // while idle (Boss, 2026-10-21: "Phone B's tab always on").
    fun builder(t: AgentThread) = BuilderId.ofThread(t.key) != null
    val builders = (live + all).filter(::builder).distinctBy { it.key }.sortedBy { BuilderId.ofThread(it.key)?.ordinal ?: 0 }
    val rest = live.filter { it.bot == null && !builder(it) }
    val open = all.firstOrNull { it.key == wanted && it.bot == null && !builder(it) && it.key !in archived && rest.none { shown -> shown.key == it.key } }
    val kept = builders + rest + listOfNotNull(open)
    return (bots + kept.filter { it.key in pinned } + kept.filter { it.key !in pinned }).sortedWith(dev.omalauncher.assist.agentThreadOrder)
}

/**
 * Where the tab strip scrolls to so the stretch from [start] to [end] of what it holds, in pixels, is wholly in
 * view, [view] wide, from [now], where it stands: unchanged when it already is, as little as brings it in when it
 * is not, and its start first when it is wider than the strip. The strip keeps the result inside what it can scroll.
 */
fun stripScroll(now: Int, view: Int, start: Int, end: Int): Int = when {
    end - start >= view || start < now -> start
    end > now + view -> end - view
    else -> now
}

/**
 * The stretch of the strip the open tab needs in view, from the stretch each tab and ＋ ([NEW_TAB]) take in
 * [spans]: the open one's, and when it is the last tab, as a chat just started with ＋ is, the ＋ beside it too,
 * so another can be started without scrolling. Null until the open tab has been laid out.
 */
fun stripSpan(open: String, tabs: List<String>, spans: Map<String, Pair<Int, Int>>): Pair<Int, Int>? {
    val span = spans[open] ?: return null
    val plus = spans[NEW_TAB]?.takeIf { tabs.lastOrNull() == open }
    return span.first to maxOf(span.second, plus?.second ?: span.second)
}

/** What [stripSpan] calls ＋ among the tabs' keys. No thread has it, nor the Oma tab. */
const val NEW_TAB = "+"

/**
 * The tab that shows when [wanted] was asked for, by a tile's intent or a tap: that thread's while it is among
 * [threads], and the Oma tab for nothing asked, for a key that is no thread's, and for a thread not heard of
 * (yet: it is asked again as the threads change, so a tab the Mac has still to list opens when it does).
 */
fun openTab(wanted: String?, threads: List<AgentThread>): String = wanted?.takeIf { key -> threads.any { it.key == key } } ?: OMA_TAB

/**
 * The tab a tap on the dot of [tapped] leaves open while [open] shows: that thread's, or back to Oma from the dot that is
 * already open. With no Oma tab ([soma] false), the open one's dot leaves it open.
 */
fun dotTap(open: String, tapped: String, soma: Boolean = true): String = if (tapped == open && soma) OMA_TAB else tapped

/**
 * The entries [tab] shows, oldest first as [tasks] are: all of them on the Oma tab, on a builder's tab the
 * build lane's that went to that builder in its own conversation, and on a Mac thread's tab the requests that
 * went to that thread. A new chat's tab shows what was sent in it, the front agent's answers there included,
 * and whatever else went to the Mac thread made for it. A request the front agent answered on the Oma tab, or
 * has not decided, is on the Oma tab alone.
 */
fun tabEntries(allTasks: List<ChatTask>, tab: String, meta: TabMeta = TabMeta()): List<ChatTask> {
    val tasks = allTasks.filterNot { it.isBundledMailReceipt() }
    meta.chatOf(tab)?.let { chat -> return tasks.filter { it.chat == chat.id || (chat.mac.isNotEmpty() && it.thread == chat.mac) } }
    return when {
        tab == OMA_TAB -> tasks
        BuilderId.ofThread(tab) != null -> BuilderId.ofThread(tab).let { b -> tasks.filter { it.build && it.builderId == b && it.chat.isEmpty() } }
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

/** What the header calls the open tab: the thread's name, or "Soma". */
fun tabTitle(tab: String, threads: List<AgentThread>): String = threads.firstOrNull { it.key == tab }?.name ?: "Soma"

/** What the empty field says on a thread's tab; null on the Oma tab, which keeps its own hints. */
fun fieldHint(to: SendTo, on: String = "the Mac"): String? = when (to) {
    SendTo.Oma -> null
    SendTo.Phone -> "to $PHONE_THREAD_NAME…"
    SendTo.PhoneB -> "to $PHONE_THREAD_B_NAME…"
    SendTo.PhoneC -> "to $PHONE_THREAD_C_NAME…"
    is SendTo.Mac -> "to ${to.name}…"
    is SendTo.Chat -> "start this chat on $on…"
    is SendTo.ChatStarting -> "starting a new thread on $on…"
}

/**
 * Whether the red stop beside the field shows. It is the phone builders' stop, and takes what the front agent
 * is deciding with it: so it shows on the Oma tab while either goes on, on a builder's tab while that builder
 * works ([working] is then that builder's), on a new chat with nowhere to go yet while the front agent decides
 * its words ([deciding] is then that chat's), and never on a Mac thread's tab, whose tasks each have a stop of
 * their own on their entry.
 */
fun fieldStop(to: SendTo, working: Boolean, deciding: Boolean): Boolean = when (to) {
    SendTo.Oma -> working || deciding
    SendTo.Phone, SendTo.PhoneB, SendTo.PhoneC -> working
    is SendTo.Chat, is SendTo.ChatStarting -> deciding
    is SendTo.Mac -> false
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
    ForgetCommands("Forget saved commands"),

    /** Moves a thread on a computer to another agent or model ([switchThread]), from a page of picks. */
    Switch("Switch agent"),

    /** A bot's instructions, read only: to change them he asks an agent. */
    Instructions("Instructions"),

    /** A bot's own notes, read only. */
    Notes("Notes"),

    /** A bot's conversation archived on its machine; its instructions and notes stay. */
    Fresh("Start fresh"),
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
fun tabMenu(tab: String, pinned: Boolean, stoppable: Boolean, answered: Boolean, archived: Int, mend: TabMend = TabMend(), switchable: Boolean = false, bot: Boolean = false, forgetCommands: Boolean = false): List<TabAction> =
    if (bot) {
        // A bot's tab is always there and named by its folder: no rename, pin, switch or archive.
        listOfNotNull(TabAction.Instructions, TabAction.Notes, TabAction.Stop.takeIf { stoppable }, TabAction.Copy.takeIf { answered }, TabAction.Fresh)
    } else if (tab == OMA_TAB) {
        listOfNotNull(TabAction.Copy.takeIf { answered }, TabAction.Archived.takeIf { archived > 0 })
    } else {
        listOfNotNull(
            TabAction.Rename,
            if (pinned) TabAction.Unpin else TabAction.Pin,
            TabAction.Stop.takeIf { stoppable },
            TabAction.Restart.takeIf { mend.restart != null },
            TabAction.Clean.takeIf { mend.clean },
            TabAction.Copy.takeIf { answered },
            TabAction.Switch.takeIf { switchable },
            TabAction.ForgetCommands.takeIf { forgetCommands },
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
fun tabBuilder(tab: String): BuilderId? = BuilderId.ofThread(tab)

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

/** What stands over the archived tabs' list: short, so it fits the menu's width on one line. */
fun archivedCaption(count: Int) = archivedRow(count) + " · tap to restore"

/** Where a tab's menu is: its rows, a field to rename the tab in, the archived tabs, or the picks a thread can switch to. */
enum class MenuPage { Rows, Rename, Archived, Switch, Instructions, Notes }

/** The thread on a computer whose tab [tab] is: its own, or the one a new chat lives on. Null for the Oma tab, a builder's, or a chat with no thread yet. */
fun switchThread(tab: String, meta: TabMeta): String? = meta.chatOf(tab)?.mac?.takeIf { it.isNotEmpty() } ?: macThreadId(tab)

/**
 * Where Back leaves a tab's menu that shows [page]: from Rename or the archived tabs back to the rows, and from
 * the rows out of the menu, which is null. Never the whole chat while the menu is open. With the keyboard up,
 * the first Back is the keyboard's own and only puts it away, so a name half typed is still there.
 */
fun menuBack(page: MenuPage): MenuPage? = if (page == MenuPage.Rows) null else MenuPage.Rows

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
    return BuilderId.ofThread(tab)?.let(TabStop::Builder) ?: macThreadId(tab)?.let(TabStop::Mac)
}

/** The newest finished answer among [entries], as it was written, for Copy last answer; null when none has one. */
fun lastAnswer(entries: List<ChatTask>): String? = entries.lastOrNull { it.status == TaskStatus.Done && it.result.isNotBlank() }?.result?.trim()

/**
 * The field's words, kept for each tab by its key, as [drafts] holds them, once [tab]'s are [text]: each tab has its
 * own, so what was typed on one is never left in the field of another and sent to the wrong thread. A tab with an
 * empty field keeps nothing.
 */
fun withDraft(drafts: Map<String, String>, tab: String, text: String): Map<String, String> = if (text.isEmpty()) drafts - tab else drafts + (tab to text)

/**
 * The drafts once text is set into the field from outside, by accessibility's set-text as `oma-phone fill` does:
 * the open [tab]'s draft becomes [text], as typing it would, so the field keeps it rather than drawing the old draft
 * over it. Null while [listening]: the field shows his speech then and takes nothing else, and the one setting the
 * text is told so.
 */
fun setDraft(drafts: Map<String, String>, tab: String, text: String, listening: Boolean): Map<String, String>? =
    if (listening) null else withDraft(drafts, tab, text)

/**
 * The tab an intent to open the chat asks for, by the thread key it carries ([OmaChatActivity.EXTRA_THREAD]); null
 * for Oma, which the dock, a notification and voice open, naming none. A Mac thread a new chat lives on is shown as
 * that chat's tab, so its key opens that tab.
 */
fun askedTab(extra: String?, meta: TabMeta = TabMeta()): String? {
    val key = extra?.take(ChatHistory.MAX_THREAD + 8)?.takeIf { it.isNotBlank() } ?: return null
    return macThreadId(key)?.let { meta.chatOnMac(it)?.key } ?: key
}

/**
 * The tab of the last turn among [tasks], where the chat opens when nothing names a thread (Boss, 2026-10-20: the chat
 * widget "should open to the specific chat that the last turn came from"; opened on Soma, his next words went to
 * whichever builder the front agent picked): a new chat's, a phone builder's, or a Mac thread's (its chat's, when a
 * chat lives on it). Null, and so the Soma tab, when the last turn was the front agent's own or is still undecided.
 */
fun lastTurnTab(tasks: List<ChatTask>, meta: TabMeta = TabMeta()): String? {
    val last = tasks.lastOrNull { !it.undecided } ?: return null
    return when {
        last.chat.isNotEmpty() -> meta.chat(last.chat)?.let { chatKey(last.chat) }
        last.mac -> meta.chatOnMac(last.thread)?.key ?: macThreadKey(last.thread)
        last.build -> last.builderId.thread
        else -> null
    }
}

/** The line under a bot's name: "Venice · venice-uncensored-1-2 · on omarchy-mg1 · memory 41% · 2 summaries". */
fun botInfoLine(bot: dev.omalauncher.assist.MacBot, model: String): String = listOfNotNull(
    "Venice",
    model.takeIf { it.isNotBlank() },
    bot.machine.takeIf { it.isNotBlank() }?.let { "on $it" },
    "memory ${bot.memory}%",
    bot.summaries.takeIf { it > 0 }?.let { if (it == 1) "1 summary" else "$it summaries" },
).joinToString(" · ")

/** The marks a bot's card leads with, to show in red, and the rest of its explanation. */
private val CARD_MARKS = listOf("Runs as administrator.", "Builds or runs code from outside Arch's own repos.")

fun cardMarks(explanation: String): Pair<List<String>, String> {
    var rest = explanation.trim()
    val found = mutableListOf<String>()
    for (mark in CARD_MARKS) {
        if (rest.startsWith(mark)) {
            found += mark
            rest = rest.removePrefix(mark).trim()
        }
    }
    return found to rest
}
