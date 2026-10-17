package dev.omalauncher.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.ThreadDot
import dev.omalauncher.ui.scrap

/*
 * The Threads and Needs you modules (Soma Canvas slice 3a), in the active pack. A module is given only its size; what fits
 * is measured, never guessed: rows that don't fit whole are left out (with "+N more" where there is room), nothing is cut
 * mid-line, and a narrow row gives way rather than wrap. Tapping a module (outside a button) opens the chat on that
 * thread's tab; approve and deny are the only things a module does, and only on his tap.
 */

/** Below this width a one-row module has no room for its heading beside what it says. */
private val LINE_HEADING_ROOM = 260.dp

/** Below this width a one-row Needs you shows the count and opens the chat rather than offer approve and deny blind. */
private val LINE_ANSWER_ROOM = 300.dp

/** A module's chrome: the pack's tile, its name over what it holds (not at the dot size, nor on a narrow line). */
@Composable
fun ModuleFrame(title: String, caption: String, size: ModuleSize, key: String, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val open = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
    Box(modifier.fillMaxSize().scrap(focused = LocalModuleFocus.current, key = key, dressed = size != ModuleSize.Dot).then(open).semantics { contentDescription = "$title module" }.padding(if (size == ModuleSize.Dot) 6.dp else 12.dp)) {
        when (size) {
            ModuleSize.Dot -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
            ModuleSize.Line -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                // What it says comes first; the heading only where there is room for both.
                val heading = maxWidth >= LINE_HEADING_ROOM
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (heading) BasicText(Pack.heading(title), style = Pack.display(Pack.size.large), maxLines = 1, softWrap = false)
                    Box(Modifier.weight(1f)) { content() }
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TitleRow(title, caption)
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

/** A card's name, and its caption only when both fit: the name is never cut to make room for the caption. */
@Composable
private fun TitleRow(title: String, caption: String) {
    Layout(content = {
        BasicText(Pack.heading(title), style = Pack.display(Pack.size.large), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(Pack.caption(caption), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
    }) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        // Their natural widths decide whether the caption shows; each is then measured once, the name bounded so a long one ends in an ellipsis.
        val nameWidth = measurables[0].maxIntrinsicWidth(constraints.maxHeight)
        val noteWidth = measurables[1].maxIntrinsicWidth(constraints.maxHeight)
        val both = caption.isNotEmpty() && nameWidth + gap + noteWidth <= constraints.maxWidth
        val name = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth))
        val note = if (both) measurables[1].measure(Constraints(maxWidth = noteWidth)) else null
        val height = maxOf(name.height, note?.height ?: 0)
        layout(constraints.maxWidth, height) {
            name.placeRelative(0, (height - name.height) / 2)
            note?.placeRelative(constraints.maxWidth - note.width, (height - note.height) / 2)
        }
    }
}

/**
 * [count] rows stacked from the top, as many as fit whole in the height there is; when some don't, a last row says how many
 * are left out, if it fits too. Nothing is drawn cut off.
 */
@Composable
fun FitRows(count: Int, gap: Dp = 6.dp, row: @Composable (Int) -> Unit) {
    // The "+N more" row is composed for every N it could say, and only the one that matches is placed: a Layout can't
    // compose during measure, and N is only known there.
    Layout(content = {
        for (i in 0 until count) Box { row(i) }
        for (n in 1..count) Box { BasicText("+$n more", style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false) }
    }) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val space = gap.roundToPx()
        val rows = measurables.take(count).map { it.measure(loose) }
        // Each "+N more" is measured once, here; only the one that is needed is placed.
        val mores = measurables.drop(count).map { it.measure(loose) }
        val total = rows.sumOf { it.height } + space * (rows.size - 1).coerceAtLeast(0)
        var shown = rows.size
        if (total > constraints.maxHeight) {
            val moreHeight = mores.maxOfOrNull { it.height } ?: 0
            var used = 0
            shown = 0
            for (r in rows) {
                val next = used + (if (shown > 0) space else 0) + r.height
                if (next + space + moreHeight > constraints.maxHeight) break
                used = next
                shown++
            }
        }
        val hidden = count - shown
        val more = if (hidden > 0) mores[hidden - 1] else null
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = 0
            for (r in rows.take(shown)) {
                r.place(0, y)
                y += r.height + space
            }
            if (more != null && y + more.height <= constraints.maxHeight) more.place(0, y)
        }
    }
}

/** [count] items side by side, as many as fit whole in the width there is; the rest are left off. */
@Composable
fun FitAcross(count: Int, gap: Dp = 10.dp, item: @Composable (Int) -> Unit) {
    Layout(content = { for (i in 0 until count) Box { item(i) } }) { measurables, constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val space = gap.roundToPx()
        val placed = measurables.map { it.measure(loose) }
        var x = 0
        val shown = placed.takeWhile { p -> (x + p.width <= constraints.maxWidth).also { if (it) x += p.width + space } }
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var at = 0
            for (p in shown) {
                p.place(at, (height - p.height) / 2)
                at += p.width + space
            }
        }
    }
}

/** A module's tap target: one line, never wrapped, in the bracket style. */
@Composable
fun ModuleButton(label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp)) {
        BasicText("[ $label ]", style = Pack.body(color = color), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Dotted(count: Int, label: String, state: ThreadState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ThreadDot(state, Pack.muted)
        BasicText("$count", style = Pack.display(Pack.size.title), maxLines = 1, softWrap = false)
        BasicText(Pack.caption(label), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
    }
}

/** Threads: the worst state and a count; counts by state; a list; columns by state. */
@Composable
fun ThreadsModule(threads: List<AgentThread>, size: ModuleSize, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val first = threads.firstOrNull()?.key
    ModuleFrame("Threads", "${threads.size} live", size, "module:threads", modifier, onOpen = first?.let { { onOpen(it) } }) {
        when (size) {
            ModuleSize.Dot -> Dotted(threads.size, "threads", worstState(threads))
            ModuleSize.Line -> if (threads.isEmpty()) {
                BasicText("No threads", style = Pack.body(color = Pack.muted), maxLines = 1, softWrap = false)
            } else {
                // Worst first, so what a short row leaves off at its end is the calm part; only whole counts are shown.
                val counts = MODULE_STATES.map { (state, _) -> state to threads.count { it.state == state } }.filter { it.second > 0 }
                FitAcross(counts.size) { i -> Row(verticalAlignment = Alignment.CenterVertically) { ThreadDot(counts[i].first, Pack.muted); Spacer(Modifier.width(3.dp)); BasicText("${counts[i].second}", style = Pack.body(), maxLines = 1, softWrap = false) } }
            }
            ModuleSize.Card -> if (threads.isEmpty()) {
                BasicText("No threads at work.", style = Pack.body(color = Pack.muted))
            } else {
                FitRows(threads.size) { i -> ThreadRow(threads[i], onOpen) }
            }
            ModuleSize.Full -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((state, label) in MODULE_STATES.filter { (s, _) -> s != ThreadState.Queued }) {
                    val inIt = threads.filter { it.state == state || (state == ThreadState.Working && it.state == ThreadState.Queued) }
                    Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { ThreadDot(state, Pack.muted); Spacer(Modifier.width(6.dp)); BasicText(Pack.caption(label), style = Pack.mono(Pack.size.small), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Box(Modifier.weight(1f)) {
                            FitRows(inIt.size) { i ->
                                val t = inIt[i]
                                Column(Modifier.fillMaxWidth().clickable { onOpen(t.key) }.padding(vertical = 2.dp)) {
                                    BasicText(t.name, style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    BasicText(t.doing.ifEmpty { t.label }, style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One thread in a list: its name first; what it is doing and its computer only where there is room for them. */
@Composable
private fun ThreadRow(t: AgentThread, onOpen: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clickable { onOpen(t.key) }) {
        val roomy = maxWidth >= 320.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ThreadDot(t.state, Pack.muted)
            Spacer(Modifier.width(8.dp))
            // Fixed shares, so the columns line up row under row.
            BasicText(t.name, Modifier.weight(if (roomy) 0.42f else 1f), style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (roomy) {
                Spacer(Modifier.width(8.dp))
                BasicText(t.doing.ifEmpty { t.label }, Modifier.weight(0.4f), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(Pack.caption(t.machine), Modifier.weight(0.18f).padding(start = 6.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Needs you: a count; the first ask with approve and deny (on a row too short for them, the count, which opens the chat); every
 * ask that fits, each with both; the same with its detail. Approve and deny answer through [onAnswer] and nothing else; what
 * comes back ("That request has expired") is shown at every size but the dot, in place of the ask, until the next look.
 */
@Composable
fun NeedsYouModule(asks: List<ModuleAsk>, size: ModuleSize, modifier: Modifier = Modifier, onAnswer: (ModuleAsk, Boolean, (String?) -> Unit) -> Unit, onOpen: (String) -> Unit) {
    var said by remember { mutableStateOf<String?>(null) }
    val first = asks.firstOrNull()
    val answer = { ask: ModuleAsk, allow: Boolean -> onAnswer(ask, allow) { said = it } }
    ModuleFrame("Needs you", if (asks.isEmpty()) "all clear" else "${asks.size} waiting", size, "module:needs", modifier, onOpen = first?.let { { onOpen(it.threadKey) } }) {
        when (size) {
            ModuleSize.Dot -> Dotted(asks.size, "waiting", if (asks.isEmpty()) ThreadState.Idle else ThreadState.NeedsOk)
            ModuleSize.Line -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val note = said
                when {
                    note != null -> BasicText(note, style = Pack.body(color = Pack.red), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    first == null -> BasicText("Nothing waits on you.", style = Pack.body(color = Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    maxWidth < LINE_ANSWER_ROOM -> Row(Modifier.clickable { onOpen(first.threadKey) }, verticalAlignment = Alignment.CenterVertically) {
                        ThreadDot(ThreadState.NeedsOk, Pack.muted)
                        Spacer(Modifier.width(6.dp))
                        BasicText("${asks.size} waiting · open", style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // A command can't be read on one line: it is approved where it shows whole.
                    first.wholeDetail -> BasicText("${first.title} · make Needs you bigger to read it", style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(first.title, Modifier.weight(1f), style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Answers(first, { allow -> answer(first, allow) }) { onOpen(first.threadKey) }
                    }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                said?.let { BasicText(it, style = Pack.mono(Pack.size.small, Pack.red), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (asks.isEmpty()) {
                    BasicText("Nothing waits on you.", style = Pack.body(color = Pack.muted))
                } else {
                    FitRows(asks.size, gap = 8.dp) { i ->
                        val ask = asks[i]
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BasicText(ask.title, Modifier.clickable { onOpen(ask.threadKey) }, style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ask.wholeDetail) {
                                // The command whole, or no approve: one cut short by the room here is never approved unread.
                                var cut by remember(ask.id) { mutableStateOf(false) }
                                BasicText(ask.detail, style = Pack.mono(Pack.size.small, Pack.foreground), maxLines = 8, overflow = TextOverflow.Ellipsis, onTextLayout = { cut = it.hasVisualOverflow })
                                if (cut) BasicText("too long to read here: make Needs you bigger", style = Pack.body(Pack.size.small, Pack.muted), maxLines = 1)
                                else Answers(ask, { allow -> answer(ask, allow) }) { onOpen(ask.threadKey) }
                            } else {
                                if (size == ModuleSize.Full) BasicText(ask.detail, style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Answers(ask, { allow -> answer(ask, allow) }) { onOpen(ask.threadKey) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Approve and deny, always both and whole: "approve" and "deny" where they fit side by side, else "yes" and "no", else
 * neither and an "open" that takes him to the ask in the chat. Measured in the pack in use, never guessed from a width.
 */
@Composable
private fun Answers(ask: ModuleAsk, answer: (Boolean) -> Unit, open: () -> Unit) {
    Layout(
        modifier = Modifier.semantics { contentDescription = "answers for ${ask.id}" },
        content = {
            ModuleButton("approve", Pack.green) { answer(true) }
            ModuleButton("deny", Pack.red) { answer(false) }
            ModuleButton("yes", Pack.green) { answer(true) }
            ModuleButton("no", Pack.red) { answer(false) }
            ModuleButton("open", Pack.accent, open)
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val all = measurables.map { it.measure(loose) }
        val pair = listOf(all[0] to all[1], all[2] to all[3]).firstOrNull { (a, b) -> a.width + b.width <= constraints.maxWidth }
        val shown = pair?.toList() ?: listOf(all[4])
        val height = shown.maxOf { it.height }
        layout(shown.sumOf { it.width }.coerceAtMost(constraints.maxWidth), height) {
            var x = 0
            for (p in shown) {
                p.place(x, 0)
                x += p.width
            }
        }
    }
}
