package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * All chats (desktop parity slice 4, Boss approved the mockup design/soma-all-chats-mockup.html on 2026-10-28): every
 * chat and thread there is, on every computer and the Fold, requests for Boss first, then work and the rest by recency, with a search over their
 * names, his words and the answers kept here, and each thread's last words as its computer says them. Tapping one opens
 * it as a tab; an archived one comes back. The decisions are plain functions here; AllChatsPanel draws them.
 */

/** What a row of the phone's own threads (its builders) says it is on, as the mockup has it ("FOLD · CODEX"). */
const val ALL_CHATS_PHONE = "Fold"

/** One chat or thread in the list: [where] its computer (or the phone), [agent] who runs it, [last] what to show of it. */
data class AllChatRow(
    val key: String,
    val name: String,
    val where: String,
    val agent: String,
    val worked: Long?,
    val last: String,
    val open: Boolean,
    val archived: Boolean,
    val state: dev.omalauncher.assist.ThreadState = dev.omalauncher.assist.ThreadState.Idle,
)

/** Which group a chat last worked at [worked] (ms) falls in at [now]: Today, Yesterday, This week or Earlier. */
fun allChatGroup(worked: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (worked == null) return "Earlier"
    val day = Instant.ofEpochMilli(worked).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        // A day ahead (a computer's clock a little ahead of this one's, past midnight) is today's, never this week's.
        !day.isBefore(today) -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> "This week"
        else -> "Earlier"
    }
}

/** When it last worked, as the row says it: the time today and yesterday, the weekday this week, else the date. */
fun allChatWhen(worked: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (worked == null) return ""
    val at = Instant.ofEpochMilli(worked).atZone(zone)
    val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val pattern = when {
        // Yesterday's by its time too, as the mockup has it; a day ahead (a clock ahead of this one's) as today's.
        !at.toLocalDate().isBefore(today.minusDays(1)) -> "h:mm a"
        at.toLocalDate().isAfter(today.minusDays(7)) -> "EEE"
        else -> "MMM d"
    }
    return at.format(DateTimeFormatter.ofPattern(pattern, Locale.US)).uppercase(Locale.US)
}

/**
 * The rows, in shared session priority: every one of [all] (what the host lists, archived ones too), each with what is known of it:
 * [shown] the keys in the tab strip now, [archived] the archived keys, [kind] the agent of a key ("" when none is known),
 * and [entries] its entries here, whose last answer (or its last words, as its computer said) is what the row shows.
 */
fun allChatRows(
    all: List<AgentThread>,
    shown: Set<String>,
    archived: Set<String>,
    kind: (String) -> String,
    entries: (String) -> List<ChatTask>,
    /** Its computer's name when the thread doesn't say (one computer set up): "" for none. */
    place: (String) -> String = { "" },
): List<AllChatRow> =
    all.filter { it.bot == null }.sortedWith(dev.omalauncher.assist.agentThreadOrder).map { thread ->
        val said = entries(thread.key).lastOrNull { it.result.isNotBlank() }?.result
        AllChatRow(
            key = thread.key,
            name = thread.name,
            where = thread.machine.ifBlank { if (thread.mac) place(thread.key) else ALL_CHATS_PHONE },
            agent = kind(thread.key),
            worked = thread.worked,
            last = Markdown.plain(thread.doing.ifBlank { said.orEmpty() }).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(),
            open = thread.key in shown,
            archived = thread.key in archived,
            state = thread.state,
        )
    }

/**
 * The rows that match [query] (case aside: in a name, the row's last words, or his words and the answers in its
 * [entries]) and are on [where] when one is picked ("" for all), each with the words its match is in, to show under it.
 */
fun allChatMatches(rows: List<AllChatRow>, query: String, where: String, entries: (String) -> List<ChatTask>): List<Pair<AllChatRow, String>> {
    val wanted = query.trim()
    return rows.filter { where.isEmpty() || it.where == where }.mapNotNull { row ->
        if (wanted.isEmpty()) return@mapNotNull row to row.last
        // As they read: a match only in a link's address or the markup isn't one he would see marked (slice 4 review).
        val texts = listOf(row.name, row.last) + entries(row.key).flatMap { listOf(it.prompt, it.result) }.map { Markdown.plain(it) }
        val hit = texts.firstOrNull { it.contains(wanted, ignoreCase = true) } ?: return@mapNotNull null
        row to if (hit == row.name) row.last else allChatSnippet(hit, wanted)
    }
}

/** A line of [text] around [query]'s first match, short enough for one or two lines of a row. */
fun allChatSnippet(text: String, query: String, around: Int = 40): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val at = flat.indexOf(query, ignoreCase = true)
    if (at < 0) return flat.take(around * 2)
    val start = (at - around).coerceAtLeast(0)
    val end = (at + query.length + around).coerceAtMost(flat.length)
    return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
}

/** What stands under the list when it is searched: how many of how many match. */
fun allChatCount(matching: Int, of: Int, query: String): String =
    if (query.isBlank()) "$of ${if (of == 1) "chat" else "chats"}, requests for you first."
    else "$matching of $of ${if (of == 1) "chat" else "chats"} match “${query.trim()}”. Clear the field to see them all, requests for you first."
