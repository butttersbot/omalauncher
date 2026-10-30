package dev.omalauncher.messages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.messages.engine.*
import dev.omalauncher.ui.ComposerState
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Focused replies retain their own draft; the parent timeline/composer stays mounted behind the modal. */
@Composable internal fun ReplyThreadPane(book: Conversation, requestedRoot: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val updates by Messages.liveUpdates.collectAsState()
    val deleted by Messages.deleted.collectAsState()
    var slice by remember(book.id, requestedRoot) { mutableStateOf<ThreadSlice?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    fun load(cursor: String? = null) {
        if (loading) return
        loading = true; error = null
        MacLink.replyThread(book.id, requestedRoot, cursor) { result ->
            scope.launch {
                loading = false
                result.fold(onSuccess = { next -> slice = slice?.let { if (cursor == null) it.refreshing(next) else it.adding(next) } ?: next },
                    onFailure = { error = it.message ?: "Replies couldn't be loaded." })
            }
        }
    }
    fun jump(id: String) {
        if (loading) return
        loading = true; error = null
        MacLink.messageContext(book.id, id) { result ->
            scope.launch {
                loading = false
                result.fold(onSuccess = { context ->
                    slice?.let { current ->
                        val known = ThreadSlice.merge(current.messages, context)
                        val target = context.filter { it.id?.removeSuffix("#a") == id.removeSuffix("#a") }
                        slice = current.copy(messages = ThreadSlice.merge(current.messages, target + Replies.members(current.rootId, known)))
                        jumpTo = id
                    }
                }, onFailure = { error = it.message ?: "Reply context couldn't be loaded." })
            }
        }
    }
    // Apply facts to the private page itself. Evicting a bounded global fact must not restore old contents.
    LaunchedEffect(slice?.messages, book.messages, updates[book.id], deleted[book.id]) {
        slice?.let { current ->
            val known = current.messages.mapNotNull { it.id?.removeSuffix("#a") }.toSet()
            val facts = (book.messages + updates[book.id].orEmpty()).filter { it.id?.removeSuffix("#a") in known }
            slice = current.copy(messages = ThreadSlice.merge(current.messages, facts)
                .filter { it.id?.removeSuffix("#a") !in deleted[book.id].orEmpty() })
        }
    }
    LaunchedEffect(book.id, requestedRoot) { load() }
    LaunchedEffect(book.messages) {
        if (slice != null) { delay(300); load() }
    }
    MessageOverlay(onDismiss, dismissOnOutside = false) {
        Column(Modifier.fillMaxSize().background(Pack.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val current = slice
            if (loading) BasicText("Loading replies…", Modifier.padding(14.dp), style = Pack.body(Pack.size.small, Pack.muted))
            error?.let { text ->
                BasicText(text, Modifier.padding(14.dp), style = Pack.body(Pack.size.body, Pack.accent))
                Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("Retry", filled = false) { load() }
                    if (current == null) TextButton("Back to conversation", filled = false, onClick = onDismiss)
                }
            }
            if (current != null) key(current.rootId) {
                val storage = Replies.draftKey(book.id, current.rootId)
                val draft = remember(storage) { ComposerState(Desk[storage].draft) }
                val pending = remember(storage) {
                    Pending { storage }.apply {
                        val left = Desk[storage]
                        left.quote?.let { quotes[book.id] = it }
                        left.replyTo?.let { replyTo[book.id] = it }
                    }
                }
                if (current.rootUnavailable) BasicText("Original message unavailable", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = Pack.body(Pack.size.small, Pack.muted))
                if (current.unresolved > 0) BasicText("Some reply context is unavailable.", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = Pack.body(Pack.size.small, Pack.muted))
                if (current.nextCursor != null && !loading) Row(Modifier.padding(horizontal = 14.dp)) {
                    TextButton("More replies", filled = false) { load(current.nextCursor) }
                }
                val live = Replies.members(current.rootId, ThreadSlice.merge(current.messages, book.messages))
                val shown = ThreadSlice.merge(ThreadSlice.merge(current.messages, live), updates[book.id].orEmpty().filter { u -> current.messages.any { it.id?.removeSuffix("#a") == u.id?.removeSuffix("#a") } })
                    .filter { it.id?.removeSuffix("#a") !in deleted[book.id].orEmpty() }
                Thread(book.copy(messages = shown), draft, pending, onBack = onDismiss,
                    modifier = Modifier.weight(1f).fillMaxWidth(), storageId = storage,
                    focused = true, defaultReplyTo = current.rootId, onJumpTarget = ::jump,
                    revealTarget = jumpTo, onJumpHandled = { jumpTo = null })
            }
        }
    }
}
