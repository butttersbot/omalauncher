package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask

/*
 * What a Mac thread's tab shows of the work done in it before this phone came in (OPEN-BUGS #11): the thread's
 * own record, read once when the tab opens, cut into turns and laid above the phone's own entries. It is only
 * read: none of it is kept in the chat, sent anywhere or answered. Plain functions here, unit tested;
 * MacThreads.earlier does the asking.
 */

/** How many of a thread's newest events its tab reads for its earlier work. */
const val MAC_EARLIER_EVENTS = 200

/** How many pages of events are asked for at most, in case a runner one day answers a page at a time. */
const val MAC_EARLIER_PAGES = 10

/** Where a thread whose newest event is [lastEvent] is read from for its earlier work: its last [MAC_EARLIER_EVENTS]. */
fun macEarlierAfter(lastEvent: Long): Long = (lastEvent - MAC_EARLIER_EVENTS).coerceAtLeast(0)

/**
 * The events after [after] up to [last], asked for a page at a time with [fetch] (`events?after=N`; null when the
 * Mac did not answer) until [last] is reached, a page brings nothing new or [MAC_EARLIER_PAGES] were asked. Null
 * when the first page could not be had; what later pages could not add is simply not there. In order, each once,
 * at most the newest [MAC_EARLIER_EVENTS].
 */
fun macEarlierEvents(after: Long, last: Long, fetch: (Long) -> List<MacEvent>?): List<MacEvent>? {
    val got = ArrayList<MacEvent>()
    var from = after
    repeat(MAC_EARLIER_PAGES) { page ->
        val events = fetch(from) ?: return if (page == 0) null else got.done(last)
        val fresh = events.filter { it.id > from && it.id <= last }
        if (fresh.isEmpty()) return got.done(last)
        got += fresh
        from = fresh.maxOf { it.id }
        if (from >= last) return got.done(last)
    }
    return got.done(last)
}

private fun List<MacEvent>.done(last: Long) = filter { it.id <= last }.distinctBy { it.id }.sortedBy { it.id }.takeLast(MAC_EARLIER_EVENTS)

/**
 * One turn of a thread as its record tells it: the [words] it was sent (empty for the agent's own turn, or one
 * whose start is older than what was read), the [steps] it took, its [answer], and whether it [failed] or was
 * [stopped]. [id] and [time] are its first event's (Unix seconds).
 */
data class MacTurn(
    val id: Long,
    val time: Long,
    val words: String,
    val steps: List<String> = emptyList(),
    val answer: String = "",
    val failed: Boolean = false,
    val stopped: Boolean = false,
)

/**
 * [events], in order, cut into turns: each `message` begins one, and what comes before the first is a turn of
 * its own. The words are his alone, without what Oma put ahead of them ([handOverWords]). The answer is the
 * result's text, or else the words said along the way, as an entry's is ([macApply]); what the agent says after
 * it, between his messages, follows it. Notes and approvals asked for are steps. Status and approval results say
 * nothing here.
 */
fun macTurns(events: List<MacEvent>): List<MacTurn> {
    val turns = ArrayList<MacTurn>()
    var turn: MacTurn? = null
    var said = StringBuilder()
    fun answer(done: MacTurn, more: String) = done.copy(answer = listOf(done.answer, more.trim()).filter { it.isNotEmpty() }.joinToString("\n\n"))
    fun close() {
        turn?.let { done -> turns += answer(done, said.toString()) }
        turn = null
        said = StringBuilder()
    }
    fun open(event: MacEvent): MacTurn = turn ?: MacTurn(event.id, event.time, "").also { turn = it }
    fun step(event: MacEvent, text: String) {
        val line = text.trim().replace(Regex("\\s+"), " ")
        if (line.isEmpty()) return
        val now = open(event)
        if (now.steps.lastOrNull() != line) turn = now.copy(steps = now.steps + line)
    }
    for (event in events.distinctBy { it.id }.sortedBy { it.id }) {
        when (event) {
            is MacEvent.Message -> {
                close()
                turn = MacTurn(event.id, event.time, handOverWords(event.text).trim())
            }
            is MacEvent.Text -> {
                open(event)
                said.append(event.text.trimEnd()).append('\n')
            }
            is MacEvent.Step -> step(event, event.text)
            is MacEvent.Note -> step(event, event.text)
            is MacEvent.Approval -> step(event, "asked to use ${event.request.tool.ifBlank { "a tool" }}")
            is MacEvent.Result -> {
                turn = answer(open(event), event.text.ifBlank { said.toString() }).copy(failed = event.failed)
                said = StringBuilder()
            }
            is MacEvent.Error -> {
                turn = answer(answer(open(event), said.toString()), event.text).copy(failed = true)
                said = StringBuilder()
            }
            is MacEvent.Stopped -> turn = open(event).copy(stopped = true)
            is MacEvent.Artifact -> dev.omalauncher.oma.artifactStep(event.name, event.version, event.state, event.settled)?.takeIf { event.state == "ready" }?.let { step(event, it) }
            is MacEvent.ApprovalResult -> if (event.decision == MacDecision.Allow && event.skipped) step(event, "skipped")
            is MacEvent.Status, is MacEvent.Other -> Unit
        }
    }
    close()
    return turns
}

/**
 * The turns of [threadId]'s record ([events]) that the phone has no entry for, oldest first, to stand above its
 * entries in [tasks]: those begun before the first request this phone sent the thread (what came after reached
 * it by following, under its own entries), and of those none whose words are one of his entries' there. A thread
 * the phone never sent anything shows all it read.
 */
fun macEarlier(events: List<MacEvent>, tasks: List<ChatTask>, threadId: String): List<MacTurn> {
    val mine = tasks.filter { it.thread == threadId }
    val first = mine.minOfOrNull { it.time }
    val prompts = mine.map { it.prompt.trim() }.toSet()
    return macTurns(events).filter { turn ->
        (first == null || turn.time * 1000 < first) && (turn.words.isEmpty() || turn.words !in prompts)
    }
}

/** A turn's steps as one quiet line: the only one, or how many with the last; null with none. */
fun macTurnSteps(turn: MacTurn): String? = when (turn.steps.size) {
    0 -> null
    1 -> turn.steps.single()
    else -> "${turn.steps.size} steps, the last: ${turn.steps.last()}"
}

/** What the record of a thread came to when its tab asked: still asking, not to be had, or read. */
sealed interface MacEarlierRead {
    data object Asking : MacEarlierRead

    data object Unread : MacEarlierRead

    /** [cut] when the thread has more events than were read. */
    data class Read(val events: List<MacEvent>, val cut: Boolean) : MacEarlierRead
}

/**
 * What stands above a Mac thread's tab, over its earlier turns ([shown] of them): that they are the Mac's record,
 * only read, and how far back; or that it is being read or could not be. Null when it was read and held nothing
 * the phone does not already show.
 */
fun macEarlierCaption(read: MacEarlierRead, shown: Int, on: String = "the Mac"): String? = when (read) {
    MacEarlierRead.Asking -> "Reading earlier work on $on…"
    MacEarlierRead.Unread -> "Earlier work on $on could not be read now"
    is MacEarlierRead.Read -> when {
        shown == 0 -> null
        read.cut -> "Earlier on $on (its last $MAC_EARLIER_EVENTS events), only shown here"
        else -> "Earlier on $on, only shown here"
    }
}
