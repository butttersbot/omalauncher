package dev.omalauncher.messages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.omalauncher.messages.engine.*
import dev.omalauncher.ui.ComposerState
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Server search and a private contiguous context window. The parent's history is never spliced. */
@Composable internal fun ConversationSearchPane(book: Conversation, draft: ComposerState, pending: Pending,
                                                onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val updates by Messages.liveUpdates.collectAsState()
    val deleted by Messages.deleted.collectAsState()
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<SearchPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<String?>(null) }
    var window by remember { mutableStateOf<ContextWindow?>(null) }
    var reveal by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun runSearch(cursor: String? = null) {
        val text = if (cursor != null) searched else query.trim()
        if (text.isEmpty()) return
        val generation = ++request
        searched = text; loading = true; error = null
        if (cursor == null) page = null
        keyboard?.hide()
        MacLink.search(book.id, text, cursor) { result -> scope.launch {
            if (generation != request) return@launch
            loading = false
            result.fold(onSuccess = { next -> page = if (cursor == null) next else next.copy(hits =
                (page?.hits.orEmpty() + next.hits).distinctBy { it.id }) }, onFailure = { error = it.message ?: "Search couldn't be loaded." })
        } }
    }
    fun context(id: String, direction: Int = 0) {
        val generation = ++request
        if (direction == 0) { selection = id; window = null }
        loading = true; error = null; keyboard?.hide()
        MacLink.contextWindow(book.id, id) { result -> scope.launch {
            if (generation != request) return@launch
            loading = false
            result.fold(onSuccess = { next ->
                val old = window
                window = if (direction == 0 || old == null) next else ContextWindow(
                    ThreadSlice.merge(old.messages, next.messages),
                    if (direction < 0) next.hasOlder else old.hasOlder,
                    if (direction > 0) next.hasNewer else old.hasNewer)
                if (direction == 0) reveal = id
            }, onFailure = { error = it.message ?: "Message context couldn't be loaded." })
        } }
    }
    // Keep redactions and revisions in this window/result page after the global bounded cache evicts a fact.
    LaunchedEffect(window?.messages, page?.hits, updates[book.id], deleted[book.id]) {
        val facts = updates[book.id].orEmpty()
        window?.let { current ->
            val known = current.messages.mapNotNull { it.id?.removeSuffix("#a") }.toSet()
            window = current.copy(messages = ThreadSlice.merge(current.messages, facts.filter { it.id?.removeSuffix("#a") in known })
                .filter { it.id?.removeSuffix("#a") !in deleted[book.id].orEmpty() })
        }
        page?.let { current ->
            page = current.copy(hits = current.hits.filter { hit ->
                hit.id !in deleted[book.id].orEmpty() && facts.none { it.id == hit.id && it.unsent }
            }.map { hit ->
                val fact = facts.firstOrNull { it.id == hit.id && it.edited }
                if (fact == null) hit else hit.copy(snippet = fact.text.takeCodePoints(180), edited = true)
            })
        }
    }
    fun back() {
        ++request; loading = false; error = null; keyboard?.hide()
        if (selection != null) { selection = null; window = null } else onDismiss()
    }
    MessageOverlay(::back, dismissOnOutside = false) {
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        Column(Modifier.fillMaxSize().background(Pack.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(if (selection == null) "Done searching" else "Results", filled = false, onClick = ::back)
                if (selection == null) BasicText(book.title, Modifier.weight(1f).padding(top = 10.dp), style = Pack.body(Pack.size.item))
            }
            if (selection == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(query, { query = it.takeCodePoints(512) }, Modifier.weight(1f).border(1.dp, Pack.foreground).padding(10.dp)
                        .semantics { contentDescription = "Search in conversation" }, singleLine = true,
                        textStyle = Pack.body(Pack.size.body), cursorBrush = SolidColor(Pack.foreground),
                        decorationBox = { inner -> if (query.isEmpty()) BasicText("Search messages", style = Pack.body(Pack.size.body, Pack.muted)); inner() })
                    TextButton("Find", filled = true, enabled = query.isNotBlank()) { focus.clearFocus(); runSearch() }
                }
                if (query.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton("Clear search", filled = false) {
                        focus.clearFocus(); ++request; query = ""; searched = ""; page = null; loading = false; error = null
                    }
                }
            }
            if (loading) BasicText(if (selection == null) "Searching…" else "Loading context…", Modifier.padding(12.dp), style = Pack.body(Pack.size.small, Pack.muted))
            error?.let { message ->
                BasicText(message, Modifier.padding(12.dp), style = Pack.body(Pack.size.body, Pack.accent))
                Row(Modifier.padding(horizontal = 12.dp)) { TextButton("Retry", filled = false) {
                    if (selection != null) context(selection!!) else runSearch()
                } }
            }
            val selected = selection
            if (selected == null) {
                val current = page
                if (current == null && !loading && error == null) BasicText("Search this conversation's history on your Mac.", Modifier.padding(12.dp), style = Pack.body(Pack.size.body, Pack.muted))
                if (current != null) {
                    val hits = current.hits.filter { it.id !in deleted[book.id].orEmpty() && updates[book.id].orEmpty().none { u -> u.id == it.id && u.unsent } }.map { hit ->
                        val fact = updates[book.id].orEmpty().firstOrNull { it.id == hit.id && it.edited }
                        if (fact == null) hit else hit.copy(snippet = fact.text.takeCodePoints(180), edited = true)
                    }
                    BasicText(if (hits.isEmpty() && current.complete) "No results for “$searched”" else
                        "${hits.size} results${if (current.complete) "" else " · more available"}", Modifier.padding(12.dp), style = Pack.body(Pack.size.small, Pack.muted))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(hits, key = { it.id }) { hit ->
                            Column(Modifier.fillMaxWidth().clickable { focus.clearFocus(); context(hit.id) }.padding(14.dp)) {
                                BasicText(hit.sender + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(hit.at)), style = Pack.body(Pack.size.small, Pack.muted))
                                BasicText(highlight(hit.snippet, searched), style = Pack.body(Pack.size.body))
                            }
                        }
                    }
                    if (current.nextCursor != null) Row(Modifier.padding(12.dp)) {
                        TextButton("More results", filled = false, enabled = !loading) { focus.clearFocus(); runSearch(current.nextCursor) }
                    }
                }
            } else {
                val hits = page?.hits.orEmpty(); val index = hits.indexOfFirst { it.id == selected }
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("Previous match", filled = false, enabled = index > 0 && !loading) { context(hits[index - 1].id) }
                    TextButton("Next match", filled = false, enabled = index >= 0 && index < hits.lastIndex && !loading) { context(hits[index + 1].id) }
                }
                val current = window
                if (current != null) {
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current.hasOlder) TextButton("Earlier context", filled = false, enabled = !loading) { current.messages.firstOrNull()?.id?.let { context(it.removeSuffix("#a"), -1) } }
                        if (current.hasNewer) TextButton("Later context", filled = false, enabled = !loading) { current.messages.lastOrNull()?.id?.let { context(it.removeSuffix("#a"), 1) } }
                    }
                    val known = current.messages.mapNotNull { it.id?.removeSuffix("#a") }.toSet()
                    val shown = ThreadSlice.merge(current.messages, updates[book.id].orEmpty().filter { it.id?.removeSuffix("#a") in known })
                        .filter { it.id?.removeSuffix("#a") !in deleted[book.id].orEmpty() }
                    if (selected in deleted[book.id].orEmpty()) BasicText("Message unavailable", Modifier.padding(12.dp), style = Pack.body(Pack.size.body, Pack.muted))
                    Thread(book.copy(messages = shown), draft, pending, onBack = ::back, Modifier.weight(1f).fillMaxWidth(),
                        focused = true, contextView = true, revealTarget = reveal, onJumpHandled = { reveal = null },
                        onJumpTarget = { context(it) }, onSent = onSent)
                }
            }
        }
    }
}

private fun highlight(text: String, query: String): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    if (query.isNotEmpty()) {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(query, start, ignoreCase = true)
            if (index < 0) break
            builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Pack.accent.copy(alpha = .18f)), index, index + query.length)
            start = index + query.length
        }
    }
    return builder.toAnnotatedString()
}

private fun String.takeCodePoints(limit: Int): String = substring(0, offsetByCodePoints(0, minOf(limit, codePointCount(0, length))))
