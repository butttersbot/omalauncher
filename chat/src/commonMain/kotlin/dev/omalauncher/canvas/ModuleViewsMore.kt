package dev.omalauncher.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.MacHandoff
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.ThreadDot

/*
 * The Computers, Handoff, Sessions and Pocket modules (Soma Canvas slice 3b), drawn as slice 3a's are: given only their size,
 * they measure what fits and show less rather than scroll or cut a line, and a name always keeps its room before any detail.
 * Only Sessions does anything: a nudge, on his tap, never beside a name too short to read.
 */

/** A computer as the cockpit shows it: its [name], whether it is [online], and a word on how it is ([detail]: load, GPU). */
data class ModuleComputer(val name: String, val online: Boolean, val detail: String = "")

private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

/** A computer set up for threads: its runner's [id] and [name], whether it answered, and the host it is reached at ([address]). */
data class ComputerHost(val id: String, val name: String, val reachable: Boolean, val address: String = "")

private fun detailOf(m: HerdMachine) = m.gpu ?: m.load?.let { "load %.1f".format(it) }.orEmpty()

/**
 * The computers to show: those set up for threads ([hosts], as Soma last heard each runner) and every other machine herd sees,
 * each once, with herd's load or GPU where it has one. A runner is matched to herd's machine first by where it is reached
 * (its URL's host is that machine's tailnet name or address), then by the same name; never by a guess at part of one.
 */
fun moduleComputers(hosts: List<ComputerHost>, herd: List<HerdMachine>): List<ModuleComputer> {
    val matched = arrayOfNulls<HerdMachine>(hosts.size)
    val used = HashSet<String>()
    fun claim(i: Int, test: (HerdMachine) -> Boolean) {
        if (matched[i] != null) return
        herd.firstOrNull { it.name !in used && test(it) }?.let { matched[i] = it; used += it.name }
    }
    hosts.forEachIndexed { i, h ->
        val at = h.address.lowercase()
        if (at.isNotEmpty()) claim(i) { it.name.lowercase() == at || it.address.lowercase() == at || it.name.lowercase() == at.substringBefore('.') }
    }
    hosts.forEachIndexed { i, h -> claim(i) { m -> listOf(key(h.id), key(h.name)).any { it.isNotEmpty() && it == key(m.name) } } }
    val out = hosts.mapIndexed { i, h -> ModuleComputer(h.name, h.reachable, matched[i]?.let(::detailOf).orEmpty()) }
    return out + herd.filter { it.name !in used }.map { ModuleComputer(it.name, it.online, detailOf(it)) }
}

/** A steady mark for online or not: it doesn't breathe as a working thread's dot does. */
@Composable
private fun OnlineMark(online: Boolean) = Box(Modifier.size(8.dp).background(if (online) Pack.green else Pack.muted))

/** How long the public door has been down, in a word or two ("3 min", "2 h"); empty when [since] is unknown or ahead of [now]. */
fun downFor(since: Long, now: Long): String {
    if (since <= 0 || now < since) return ""
    val min = (now - since) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min"
        min < 48 * 60 -> "${min / 60} h"
        else -> "${min / (24 * 60)} days"
    }
}

/**
 * Computers: each computer, online or not, and how it is. When the canvas service's public door ([door], Funnel) has stopped
 * answering, that comes first at every size, in red: the car and Grok can't reach Soma until it's back (Boss, 2026-10-17).
 */
@Composable
fun ComputersModule(computers: List<ModuleComputer>, size: ModuleSize, modifier: Modifier = Modifier, door: PublicDoor? = null, now: Long = 0L) {
    val up = computers.count { it.online }
    val down = door?.up == false
    val caption = if (down) "funnel down" else if (up == computers.size) "all online" else "$up of ${computers.size} online"
    ModuleFrame("Computers", caption, size, "module:computers", modifier) {
        val extra = if (down) 1 else 0
        when (size) {
            ModuleSize.Dot -> FitAcross(computers.size + extra, gap = 4.dp) { i -> if (down && i == 0) DownMark() else OnlineMark(computers[i - extra].online) }
            ModuleSize.Line -> FitAcross(computers.size + extra, gap = 12.dp) { i ->
                if (down && i == 0) Row(verticalAlignment = Alignment.CenterVertically) { DownMark(); Spacer(Modifier.width(4.dp)); BasicText("Funnel down", style = Pack.body(color = Pack.red), maxLines = 1, softWrap = false) }
                else Row(verticalAlignment = Alignment.CenterVertically) { OnlineMark(computers[i - extra].online); Spacer(Modifier.width(4.dp)); BasicText(computers[i - extra].name, style = Pack.body(), maxLines = 1, softWrap = false) }
            }
            else -> if (computers.isEmpty() && !down) {
                BasicText("No computers set up yet.", style = Pack.body(color = Pack.muted))
            } else {
                FitRows(computers.size + extra) { i ->
                    // The name keeps the width; how it is only where there is room for both.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val roomy = maxWidth >= 240.dp
                        if (down && i == 0) {
                            val long = door?.let { downFor(it.since, now) }.orEmpty()
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    DownMark()
                                    Spacer(Modifier.width(8.dp))
                                    BasicText("Funnel down", Modifier.weight(1f), style = Pack.body(color = Pack.red), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (roomy && long.isNotEmpty()) BasicText(Pack.caption(long), Modifier.padding(start = 6.dp), style = Pack.mono(Pack.size.small, Pack.red), maxLines = 1, softWrap = false)
                                }
                                // What it means, where there is room: wrapped, never cut.
                                if (roomy) BasicText("The car and Grok can't reach Soma until it's back.", Modifier.padding(start = 16.dp), style = Pack.mono(Pack.size.small, Pack.muted))
                            }
                        } else {
                            val c = computers[i - extra]
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OnlineMark(c.online)
                                Spacer(Modifier.width(8.dp))
                                BasicText(c.name, Modifier.weight(1f), style = Pack.body(color = if (c.online) Pack.foreground else Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (roomy) BasicText(Pack.caption(if (c.online) c.detail else "offline"), Modifier.padding(start = 6.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The public door's mark: red and square like a computer's, so it reads as one more thing that is down. */
@Composable
private fun DownMark() = Box(Modifier.size(8.dp).background(Pack.red))

/**
 * [line] without Markdown's inline marks (bold, italics, links, code ticks), as a module shows prose. What is inside a code
 * span is kept as written, and marks inside a word (snake_case, __init__) are left alone.
 */
fun plainMarkdown(line: String): String {
    // Code spans are kept literal: each is set aside as a placeholder, so emphasis may run across one ("**see `x` now**").
    // A span opens with a run of backticks and closes at the next run of the same length; an unclosed run is just text.
    val spans = ArrayList<String>()
    val held = StringBuilder()
    var i = 0
    while (i < line.length) {
        if (line[i] != '`') { held.append(line[i]); i++; continue }
        var run = i
        while (run < line.length && line[run] == '`') run++
        val ticks = run - i
        var close = -1
        var j = run
        while (j < line.length) {
            if (line[j] != '`') { j++; continue }
            var end = j
            while (end < line.length && line[end] == '`') end++
            if (end - j == ticks) { close = j; break }
            j = end
        }
        if (close < 0) { held.append(line, i, run); i = run; continue }
        held.append('\uE000').append(spans.size).append('\uE001')
        spans += line.substring(run, close).let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }
        i = close + ticks
    }
    val plain = held.toString()
        .replace(Regex("""\[([^\]\[]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""~~(\S(?:.*?\S)?)~~"""), "$1")
        .replace(Regex("""(?<![\w*])\*\*(\S(?:.*?\S)?)\*\*(?![\w*])"""), "$1")
        // Underscores only around words with a space between them: __init__ and snake_case are names, not emphasis.
        .replace(Regex("""(?<!\w)__(?=[^_]*\s)(\S[^_]*?\S)__(?!\w)"""), "$1")
        .replace(Regex("""(?<![\w*])\*(\S(?:.*?\S)?)\*(?![\w*])"""), "$1")
        .replace(Regex("""(?<!\w)_(?=[^_]*\s)(\S[^_]*?\S)_(?!\w)"""), "$1")
    return Regex("""\uE000(\d+)\uE001""").replace(plain) { spans.getOrNull(it.groupValues[1].toInt()) ?: it.value }
}

/** A line that starts a block of its own: a heading, a list item, a table row, a quote or a rule, never the rest of a paragraph. */
private val BLOCK_START = Regex("""^\s*(#|[-*+]\s|\d+[.)]\s|\||>|---|\*\*\*|___)""")

/** A table's divider row (|---|:--:|), which says nothing: only pipes, dashes, colons and spaces, with a pipe and a dash run. */
private fun tableRule(line: String): Boolean = line.contains('|') && line.contains("--") && line.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }

/** A thematic break (---, ***, ___ and spaced forms), which says nothing either. */
private fun ruleLine(line: String): Boolean {
    val marks = line.filter { !it.isWhitespace() }
    return marks.length >= 3 && marks.all { it == marks[0] } && marks[0] in "-*_="
}

/**
 * A handoff's sections: each heading with its lines (blank lines, code blocks, table dividers and the file's own title
 * left out). A paragraph or list item wrapped over several lines of the file is one line here; a table row is its cells.
 */
fun handoffSections(text: String): List<Pair<String, List<String>>> {
    val out = ArrayList<Pair<String, MutableList<String>>>()
    var title = true
    var fenced = false
    // Whether the last line kept can take the next as its continuation: a paragraph or a list item, with no blank between.
    var open = false
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        // A code block is left out whole: its fences and what is between them.
        if (line.trimStart().startsWith("```")) { fenced = !fenced; open = false; continue }
        if (fenced) continue
        if (line.isBlank()) { open = false; continue }
        val quoted = line.trimStart().startsWith(">")
        val plain = if (quoted) line.trimStart().removePrefix(">").removePrefix(" ") else line
        if (plain.isBlank()) { open = false; continue }
        if (plain.startsWith("# ") && title) { title = false; open = false; continue }
        title = false
        if (tableRule(plain) || ruleLine(plain)) { open = false; continue }
        // Sections are the top two levels; a deeper heading is a line of its section (shown as a small heading).
        if (plain.startsWith("# ") || plain.startsWith("## ")) { out += plain.trimStart('#', ' ') to ArrayList(); open = false; continue }
        val row = plain.trim()
        val kept = if (row.startsWith("|")) row.trim('|').split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }.filter { it.isNotEmpty() }.joinToString(" · ") else plain
        if (kept.isBlank()) continue
        if (open && out.isNotEmpty() && out.last().second.isNotEmpty() && !BLOCK_START.containsMatchIn(plain)) {
            val lines = out.last().second
            lines[lines.size - 1] = lines.last().trimEnd() + " " + kept.trim()
            continue
        }
        if (out.isEmpty()) out += "" to arrayListOf(kept) else out.last().second += kept
        open = !row.startsWith("|") && !row.startsWith("#")
    }
    return out
}

/** The lines a handoff module shows in order: its first title left out, blanks and code fences dropped. */
fun handoffLines(text: String): List<String> = handoffSections(text).flatMap { (heading, lines) -> (if (heading.isEmpty()) emptyList() else listOf("## $heading")) + lines }

/** A heading that says what is next ("Next", "Next actions", "3. Next steps", "What's next?"), never one that just has the word. */
private val NEXT = Regex("""^\W*(\d+[.)]\s*)?(what['\u2019]?s\s+)?next\b(?![.-]\w)""", RegexOption.IGNORE_CASE)
private val STANDS = Regex("""^\W*(\d+\.\s*)?(where (things|we) (stand|are|left off)|current state|state of|status)\b""", RegexOption.IGNORE_CASE)

/** The "Next" line of a handoff: the first line under a heading that says next, else the first plain line after the title. */
fun handoffNext(text: String): String {
    val sections = handoffSections(text)
    val next = sections.firstOrNull { NEXT.containsMatchIn(it.first) && it.second.isNotEmpty() }?.second ?: sections.flatMap { it.second }
    return next.firstOrNull()?.trim()?.removePrefix("- ")?.let(::plainMarkdown)?.take(200).orEmpty()
}

/**
 * What a handoff card shows (PRD §4: where things stand, then next): those two sections when the file has them, else its
 * sections in order. Each section's heading travels with its first line, so a heading is never the last thing shown.
 */
fun handoffCard(text: String): List<Pair<String?, String>> {
    val sections = handoffSections(text)
    val stands = sections.firstOrNull { STANDS.containsMatchIn(it.first) && it.second.isNotEmpty() }
    val next = sections.firstOrNull { NEXT.containsMatchIn(it.first) && it.second.isNotEmpty() }
    // A section's leading row is its first sentence, not a sub-heading: the heading (and the sub-heading over that
    // sentence, if any) travels with it, and the other lines follow in order.
    fun rows(section: Pair<String, List<String>>): List<Pair<String?, String>> {
        val (heading, lines) = section
        val lead = lines.indexOfFirst { !it.trim().startsWith("#") }
        if (lead < 0) return lines.mapIndexed { i, line -> (if (i == 0 && heading.isNotEmpty()) heading else null) to line }
        val sub = lines.take(lead).lastOrNull()?.trim()?.trimStart('#', ' ')
        val label = listOf(heading, sub.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
        return listOf(label to lines[lead]) + lines.filterIndexed { i, _ -> i != lead && !(i == lead - 1 && sub != null) }.map { null to it }
    }
    if (stands == null && next == null) return sections.flatMap(::rows)
    // The first line of each comes first, so "next" is on screen even in a small card; the rest follow.
    // With no "where things stand", the file's top stands in for it: a Next section never displaces what is current.
    val first = stands ?: sections.firstOrNull { it.second.isNotEmpty() }
    val a = first?.let(::rows).orEmpty()
    val b = next?.takeIf { it !== first }?.let(::rows).orEmpty()
    return listOfNotNull(a.firstOrNull(), b.firstOrNull()) + a.drop(1) + b.drop(1)
}

@Composable
fun HandoffModule(project: String, handoff: MacHandoff?, size: ModuleSize, modifier: Modifier = Modifier) {
    ModuleFrame(handoff?.file ?: "Handoff", project, size, "module:handoff:$project", modifier) {
        val text = handoff?.text.orEmpty()
        when {
            handoff == null -> BasicText(if (size == ModuleSize.Dot) "—" else "No handoff for $project yet.", style = Pack.body(color = Pack.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            size == ModuleSize.Dot -> BasicText("↳", style = Pack.display(Pack.size.title))
            size == ModuleSize.Line -> BasicText(handoffNext(text).ifEmpty { "Nothing next in the handoff." }, style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> {
                val rows = handoffCard(text)
                FitRows(rows.size, gap = 4.dp) { i ->
                    val (heading, line) = rows[i]
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (heading != null) BasicText(Pack.caption(plainMarkdown(heading)), style = Pack.mono(Pack.size.small, Pack.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val item = line.trim().startsWith("- ")
                        val sub = line.trim().startsWith("#")
                        if (sub) BasicText(Pack.caption(plainMarkdown(line.trim().trimStart('#', ' '))), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else BasicText(plainMarkdown(line.trim().removePrefix("- ")).let { if (item) "· $it" else it }, style = Pack.body(Pack.size.small), maxLines = if (size == ModuleSize.Full) 3 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun herdState(state: String) = when (state) {
    "needs-you" -> ThreadState.NeedsOk
    "working" -> ThreadState.Working
    "your-turn" -> ThreadState.Attention
    else -> ThreadState.Idle
}

/** Below this width a one-row Sessions shows the count rather than a nudge beside a name too short to read. */
private val LINE_NUDGE_ROOM = 300.dp

/**
 * Sessions: Claude desktop and terminal sessions, read-only, with a nudge, which is agent mail to that session. What came of a
 * nudge is said for a few seconds and then the module is as it was.
 */
@Composable
fun SessionsModule(sessions: List<HerdSession>, size: ModuleSize, modifier: Modifier = Modifier, onNudge: (HerdSession, (Boolean) -> Unit) -> Unit) {
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(said) {
        if (said != null) {
            kotlinx.coroutines.delay(4_000)
            said = null
        }
    }
    var sending by remember { mutableStateOf(false) }
    // One nudge at a time: a second tap while one is on its way sends nothing.
    val nudge = { s: HerdSession ->
        if (!sending) {
            sending = true
            onNudge(s) { ok ->
                sending = false
                said = if (ok) "Nudged ${s.title.ifEmpty { "it" }}" else "That nudge wasn't taken"
            }
        }
    }
    ModuleFrame("Sessions", "read-only", size, "module:sessions", modifier) {
        when (size) {
            ModuleSize.Dot -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("${sessions.size}", style = Pack.display(Pack.size.title), maxLines = 1, softWrap = false)
                BasicText(Pack.caption("sessions"), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
            }
            ModuleSize.Line -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val s = sessions.firstOrNull()
                val note = said
                when {
                    note != null -> BasicText(note, style = Pack.body(color = Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    s == null -> BasicText("No desktop sessions.", style = Pack.body(color = Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    maxWidth < LINE_NUDGE_ROOM -> BasicText("${sessions.size} open", style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        ThreadDot(herdState(s.state), Pack.muted)
                        Spacer(Modifier.width(6.dp))
                        BasicText("${s.title} · ${s.machine}", Modifier.weight(1f), style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ModuleButton("nudge", Pack.accent) { nudge(s) }
                    }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                said?.let { BasicText(it, style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (sessions.isEmpty()) {
                    BasicText("No desktop sessions.", style = Pack.body(color = Pack.muted))
                } else {
                    FitRows(sessions.size) { i ->
                        val s = sessions[i]
                        // Its name keeps the width; on a narrow card the nudge goes under it rather than squeeze it.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val narrow = maxWidth < 240.dp
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                ThreadDot(herdState(s.state), Pack.muted)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    BasicText(s.title.ifEmpty { s.project }, style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (narrow) ModuleButton("nudge", Pack.accent) { nudge(s) }
                                    else BasicText(Pack.caption("${s.machine} · ${s.project}"), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (!narrow) ModuleButton("nudge", Pack.accent) { nudge(s) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PocketModule(items: List<PocketItem>, size: ModuleSize, now: Long, modifier: Modifier = Modifier) {
    fun ago(t: Long): String {
        val m = ((now - t) / 60_000).coerceAtLeast(0)
        return when {
            m < 60 -> "${m}m"
            m < 48 * 60 -> "${m / 60}h"
            else -> "${m / 1440}d"
        }
    }
    ModuleFrame("Pocket", "every machine", size, "module:pocket", modifier) {
        when (size) {
            ModuleSize.Dot -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("${items.size}", style = Pack.display(Pack.size.title), maxLines = 1, softWrap = false)
                BasicText(Pack.caption("pocket"), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
            }
            ModuleSize.Line -> BasicText(items.firstOrNull()?.let { "${it.name} · ${it.device} · ${ago(it.time)}" } ?: "The pocket is empty.", style = Pack.body(color = if (items.isEmpty()) Pack.muted else Pack.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> if (items.isEmpty()) {
                BasicText("The pocket is empty.", style = Pack.body(color = Pack.muted))
            } else {
                FitRows(items.size) { i ->
                    val it = items[i]
                    // The name keeps the width; where it came from only where there is room for both.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val roomy = maxWidth >= 320.dp
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(it.name, Modifier.weight(1f), style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(Pack.caption(if (roomy) "${it.device} · ${ago(it.time)}" else ago(it.time)), Modifier.padding(start = 6.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
