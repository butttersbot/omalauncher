package dev.omalauncher.oma

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.framed
import dev.omalauncher.ui.onSecondaryClick

/**
 * All chats (slice 4, as mocked in design/soma-all-chats-mockup.html): a search field, a pick of computer, and every chat
 * and thread in groups by day, newest first. A tap opens one ([onOpen]); a long press or a right-click asks for its menu
 * ([onMenu]: pin, rename, archive, as its tab's). Beside the chat when the window is wide, over it when it is narrow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllChatsPanel(
    rows: List<AllChatRow>,
    entries: (String) -> List<ChatTask>,
    now: Long,
    onOpen: (AllChatRow) -> Unit,
    onMenu: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var where by rememberSaveable { mutableStateOf("") }
    val places = remember(rows) { rows.map { it.where }.filter { it.isNotEmpty() }.distinct() }
    // A computer picked that has since gone from the list (its chips gone with it) is no pick: never a list left empty.
    val place = where.takeIf { it in places }.orEmpty()
    val shown = remember(rows, query, place) { allChatMatches(rows, query, place, entries) }
    Column(Modifier.fillMaxSize().background(Pack.background).framed(Pack.hair, Pack.border, Pack.background).semantics { contentDescription = "All chats" }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("All chats", Modifier.weight(1f), style = Pack.body(Pack.size.small))
            BracketAction("close", Pack.accent, onClose)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).framed(Pack.mark, Pack.border, Pack.surface).padding(10.dp)) {
            if (query.isEmpty()) BasicText("Search chats", style = Pack.body(Pack.size.item, Pack.muted))
            BasicTextField(
                value = query,
                onValueChange = { query = it.replace("\n", " ").take(80) },
                singleLine = true,
                textStyle = Pack.body(Pack.size.item, Pack.foreground),
                cursorBrush = SolidColor(Pack.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chats" },
            )
        }
        if (places.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (place in listOf("") + places) {
                    val on = place == where || (place.isEmpty() && where !in places)
                    BasicText(
                        if (place.isEmpty()) "all" else place,
                        Modifier.framed(Pack.hair, Pack.border, if (on) Pack.foreground else Pack.background)
                            .combinedClickable(onClick = { where = place }).padding(horizontal = 8.dp, vertical = 3.dp),
                        style = Pack.mono(Pack.size.small, if (on) Pack.background else Pack.foreground),
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            var group = ""
            for ((row, line) in shown) {
                val heading = allChatGroup(row.worked, now)
                if (heading != group) {
                    group = heading
                    // Keyed by its first row too: a heading met twice can never take the same key (slice 4 review).
                    item(key = "group:$heading:${row.key}") {
                        BasicText(heading.uppercase(), Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp), style = Pack.mono(Pack.size.small, Pack.muted))
                    }
                }
                item(key = row.key) { AllChatItem(row, line, query, now, onOpen, onMenu) }
            }
            item(key = "count") {
                BasicText(allChatCount(shown.size, rows.size, query), Modifier.padding(12.dp), style = Pack.body(Pack.size.small, Pack.muted))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllChatItem(row: AllChatRow, line: String, query: String, now: Long, onOpen: (AllChatRow) -> Unit, onMenu: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            // A right-click is its menu, as a long press is.
            .onSecondaryClick(row.key) { onMenu(row.key) }
            .combinedClickable(onClick = { onOpen(row) }, onLongClick = { onMenu(row.key) })
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Open ${row.name}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(row.name, Modifier.weight(1f, fill = false), style = Pack.body(Pack.size.item, Pack.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tag = if (row.archived) "archived" else if (row.open) "open" else null
            if (tag != null) BasicText(tag, Modifier.padding(start = 6.dp).framed(Pack.hair, Pack.muted, Pack.background).padding(horizontal = 4.dp), style = Pack.mono(Pack.size.small, Pack.muted))
        }
        val about = listOf(row.where, row.agent, allChatWhen(row.worked, now)).filter { it.isNotBlank() }.joinToString(" · ").uppercase()
        if (about.isNotEmpty()) BasicText(about, style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (line.isNotBlank()) BasicText(marked(line, query), style = Pack.body(Pack.size.small, Pack.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** [line] with [query]'s matches marked, as the mockup marks them. */
private fun marked(line: String, query: String): AnnotatedString = buildAnnotatedString {
    val wanted = query.trim()
    if (wanted.isEmpty()) return@buildAnnotatedString append(line)
    var from = 0
    while (true) {
        val at = line.indexOf(wanted, from, ignoreCase = true)
        if (at < 0) { append(line.substring(from)); break }
        append(line.substring(from, at))
        withStyle(SpanStyle(background = Pack.accent, color = Pack.background)) { append(line.substring(at, at + wanted.length)) }
        from = at + wanted.length
    }
}
