package dev.omalauncher.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.notes.engine.Note
import dev.omalauncher.notes.engine.Notes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The stream: every note, newest first, in stacks that make themselves: what agents wrote lately on top, then today,
 * yesterday, this week and before. Chips narrow it to Boss's own or his agents'; the field searches titles and text.
 */
@Composable
fun Stream(
    notes: List<Note>,
    reach: Notes.Reach,
    selected: String?,
    open: (Note) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    val shown =
        notes
            .filter { filter == "all" || (filter == "agents") == it.agent }
            .filter { query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) || (query.length > 2 && Notes.text(it.id).contains(query, true)) }
    val stacks = stacks(shown, System.currentTimeMillis())
    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                BasicText("Notes", Modifier.weight(1f), style = TextStyle(fontFamily = Look.display, fontSize = 30.sp, color = Look.ink))
                BasicText(reachLine(reach), Modifier.padding(bottom = 6.dp), style = mono(if (reach.reached == false) Look.red else Look.muted))
            }
        }
        item {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().border(1.15.dp, Look.ink).padding(horizontal = 10.dp, vertical = 9.dp)) {
                if (query.isEmpty()) BasicText("Search notes", style = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.muted))
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.ink), cursorBrush = SolidColor(Look.red))
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((key, label) in listOf("all" to "All", "mine" to "Mine", "agents" to "From agents")) {
                    val on = filter == key
                    BasicText(
                        label,
                        Modifier.border(1.15.dp, Look.ink).background(if (on) Look.ink else Look.paper).clickable { filter = key }.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = TextStyle(fontFamily = Look.body, fontSize = 13.sp, color = if (on) Look.paper else Look.ink),
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                BasicText(
                    if (notes.isEmpty()) "No notes yet.\nTap + for a blank page; hold it to talk." else "Nothing matches.",
                    Modifier.fillMaxWidth().padding(40.dp),
                    style = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
            }
        }
        for ((name, stack) in stacks) {
            item(key = "h:$name") { BasicText(name.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), style = mono(Look.muted)) }
            items(stack, key = { it.id }) { n -> Card(n, n.id == selected) { open(n) } }
        }
    }
}

@Composable
private fun Card(
    n: Note,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
            .background(if (selected) Look.tone else Look.paper).border(if (selected) 2.5.dp else 1.15.dp, Look.ink).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n.agent) {
                BasicText(who(n).uppercase(), Modifier.background(Look.ink).padding(horizontal = 4.dp), style = mono(Look.paper))
                Spacer(Modifier.width(6.dp))
            }
            if (n.html) {
                BasicText("HTML", Modifier.border(1.15.dp, Look.muted).padding(horizontal = 4.dp), style = mono(Look.muted))
                Spacer(Modifier.width(6.dp))
            }
            BasicText(whenOf(n.modified, System.currentTimeMillis()).uppercase(), Modifier.weight(1f), style = mono(Look.muted))
            if (n.dirty) BasicText("NOT SENT", style = mono(Look.red))
        }
        BasicText(n.title.ifEmpty { "Untitled" }, Modifier.padding(top = 4.dp, bottom = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Look.body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Look.ink))
        if (n.preview.isNotEmpty()) BasicText(n.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Look.body, fontSize = 13.5.sp, color = Look.ink.copy(alpha = 0.75f), lineHeight = 18.sp))
    }
}

/** "From Claude": the agent's folder under Agents/, when it has one. */
fun who(n: Note): String {
    val parts = n.id.split('/')
    val name = if (parts.size > 2) parts[1] else "agent"
    return "From " + name.replaceFirstChar { it.uppercase() }
}

/** The stream's stacks, in order: agents' notes of the last two days, then by day. Pure, for the tests. */
fun stacks(
    notes: List<Note>,
    now: Long,
): List<Pair<String, List<Note>>> {
    val day = startOfDay(now)
    val out = LinkedHashMap<String, MutableList<Note>>()
    for (n in notes.sortedByDescending { it.modified }) {
        val name =
            when {
                n.agent && n.modified >= day - DAY -> "New from agents"
                n.modified >= day -> "Today"
                n.modified >= day - DAY -> "Yesterday"
                n.modified >= day - 6 * DAY -> "This week"
                else -> "Earlier"
            }
        out.getOrPut(name) { ArrayList() } += n
    }
    val order = listOf("New from agents", "Today", "Yesterday", "This week", "Earlier")
    return order.mapNotNull { k -> out[k]?.let { k to it.toList() } }
}

fun whenOf(
    at: Long,
    now: Long,
): String {
    val day = startOfDay(now)
    return when {
        at >= day -> "Today · " + SimpleDateFormat("h:mm", Locale.US).format(Date(at))
        at >= day - DAY -> "Yesterday"
        at >= day - 6 * DAY -> SimpleDateFormat("EEE", Locale.US).format(Date(at))
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(at))
    }
}

private fun reachLine(r: Notes.Reach): String =
    when {
        r.reached == null -> if (r.left > 0) "${r.left} TO SEND" else ""
        r.reached == false -> "OFFLINE" + if (r.left > 0) " · ${r.left} TO SEND" else ""
        r.left > 0 -> "${r.left} TO SEND"
        else -> "SYNCED"
    }

fun mono(color: androidx.compose.ui.graphics.Color) = TextStyle(fontFamily = Look.mono, fontSize = 10.5.sp, letterSpacing = 0.8.sp, color = color)

private fun startOfDay(now: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private const val DAY = 86_400_000L
