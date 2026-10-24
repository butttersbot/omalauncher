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
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * The stream, newest first, in two tabs: Mine, Boss's own notes, one list; Agents, what his agents wrote, under a
 * heading for each project. The field searches titles and text.
 * A note swiped right is pinned (or let go), swiped left deleted, with a moment to take it back: the directions of
 * Apple's Notes and Mail, which most apps follow (Boss, 2026-10-24).
 */
@Composable
fun Stream(
    notes: List<Note>,
    reach: Notes.Reach,
    selected: String?,
    open: (Note) -> Unit,
    deleted: (String) -> Unit = {},
    /** The tab, the search and the scroll, kept by the app: on the cover the list leaves the screen while a note is
     * open, and back from it he finds the list as he left it (Boss, 2026-10-24: back from an agent note went to Mine). */
    view: StreamView = remember { StreamView() },
    modifier: Modifier = Modifier,
) {
    // Swiped away, waiting a moment for Undo before it really goes.
    var going by remember { mutableStateOf<Note?>(null) }
    LaunchedEffect(going) {
        val n = going ?: return@LaunchedEffect
        delay(UNDO_MS)
        Notes.delete(n.id)
        going = null
    }
    // Left before the moment is up: it goes all the same.
    DisposableEffect(Unit) { onDispose { going?.let { Notes.delete(it.id) } } }
    // Boss's own notes by default; his agents' notes in a tab of their own, so they never clutter his (Boss, 2026-10-24).
    var filter by view.filter
    var query by view.query
    val shown =
        notes
            .filter { it.id != going?.id }
            .filter { (filter == "agents") == it.agent }
            .filter { query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) || (query.length > 2 && Notes.text(it.id).contains(query, true)) }
    val stacks = sections(shown, agents = filter == "agents")
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = view.list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
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
                    for ((key, label) in listOf("mine" to "Mine", "agents" to "Agents")) {
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
                        when {
                            query.isNotBlank() -> "Nothing matches."
                            filter == "agents" -> "No agent notes yet.\nAgents file them under Agents/ in ~/Notes on the Mac Pro."
                            else -> "No notes yet.\nTap + for a blank page; hold it to talk."
                        },
                        Modifier.fillMaxWidth().padding(40.dp),
                        style = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    )
                }
            }
            for ((name, stack) in stacks) {
                if (name != null) item(key = "h:$name") { BasicText(name.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), style = mono(Look.muted)) }
                items(stack, key = { it.id }) { n ->
                    Swipe(
                        n,
                        pin = { Notes.pin(n.id, !n.pinned) },
                        delete = if (n.readonly) null else {
                            {
                                going?.let { Notes.delete(it.id) }
                                going = n
                                deleted(n.id)
                            }
                        },
                    ) { Card(n, n.id == selected) { open(n) } }
                }
            }
        }
        going?.let { n ->
            Row(
                // As wide as it can be beside the + button, UNDO always at its right end, where a thumb (and a walk) finds it.
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 92.dp, bottom = 26.dp).fillMaxWidth().background(Look.ink).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Deleted “${n.title.take(24)}”", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Look.body, fontSize = 14.sp, color = Look.paper))
                BasicText("UNDO", Modifier.clickable { going = null }.padding(horizontal = 12.dp, vertical = 10.dp), style = mono(Look.highlight).copy(fontSize = 13.sp))
            }
        }
    }
}

/**
 * A card that slides under the finger: right shows Pin (ink), left Delete (red). Let go past a third of the way and it
 * happens; short of that it springs back.
 */
@Composable
private fun Swipe(
    n: Note,
    pin: () -> Unit,
    /** Null for a note that can't be deleted from here (a project's own file): then it only swipes right. */
    delete: (() -> Unit)?,
    card: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val x = remember(n.id) { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth().onSizeChanged { width = it.width }) {
        val v = x.value
        if (v != 0f) {
            Row(
                Modifier.matchParentSize().background(if (v > 0) Look.ink else Look.red).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (v > 0) Arrangement.Start else Arrangement.End,
            ) { BasicText(if (v > 0) (if (n.pinned) "UNPIN" else "PIN") else "DELETE", style = mono(Look.paper).copy(fontSize = 13.sp)) }
        }
        Box(
            Modifier.offset { IntOffset(x.value.roundToInt(), 0) }.pointerInput(n.id, n.pinned) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val w = width.toFloat()
                        scope.launch {
                            when {
                                x.value > w * PIN_AT -> {
                                    x.animateTo(0f)
                                    pin()
                                }
                                delete != null && x.value < -w * DELETE_AT -> {
                                    x.animateTo(-w)
                                    delete?.invoke()
                                }
                                else -> x.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { x.animateTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    scope.launch { x.snapTo((x.value + dx).coerceIn(if (delete == null) 0f else -width.toFloat(), width * 0.5f)) }
                }
            },
        ) { card() }
    }
}

@Composable
private fun Card(
    n: Note,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Look.tone else Look.paper).border(if (selected) 2.5.dp else 1.15.dp, Look.ink).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n.html) {
                BasicText("HTML", Modifier.border(1.15.dp, Look.muted).padding(horizontal = 4.dp), style = mono(Look.muted))
                Spacer(Modifier.width(6.dp))
            }
            BasicText(whenOf(n.modified, System.currentTimeMillis()).uppercase(), Modifier.weight(1f), style = mono(Look.muted))
            if (n.dirty) BasicText("NOT SENT", style = mono(Look.red))
            if (n.pinned) BasicText(" ● PINNED", style = mono(Look.red))
        }
        BasicText(n.title.ifEmpty { "Untitled" }, Modifier.padding(top = 4.dp, bottom = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Look.body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Look.ink))
        if (n.preview.isNotEmpty()) BasicText(n.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Look.body, fontSize = 13.5.sp, color = Look.ink.copy(alpha = 0.75f), lineHeight = 18.sp))
    }
}

/** What the stream shows: Mine or Agents, the search, the scroll. */
class StreamView {
    val filter = mutableStateOf("mine")
    val query = mutableStateOf("")
    val list = androidx.compose.foundation.lazy.LazyListState()
}

/** The project an agent's note is for: its folder under Agents/ ("Agents/soma-notes/Plan.md" is Soma Notes). */
fun project(n: Note): String {
    val parts = n.id.split('/')
    val name = if (parts.size > 2) parts[1] else "Other"
    return name.replace('-', ' ').replace('_', ' ').split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/**
 * The stream's sections, in order. Boss's own: one list, no headings (sections later, when he wants them), pinned
 * first. His agents': a heading for each project, the most recently written project first. Pure, for the tests.
 */
fun sections(
    notes: List<Note>,
    agents: Boolean,
): List<Pair<String?, List<Note>>> {
    val newest = notes.sortedByDescending { it.modified }
    if (!agents) return listOf(null to newest.sortedByDescending { it.pinned })
    // In each project its Brief, then its Current state, first; then pinned, then newest.
    val first = listOf("Brief", "Current state")
    return newest.groupBy(::project).map { (k, v) ->
        k to v.sortedWith(compareBy<Note>({ n -> first.indexOf(n.title.takeIf { n.readonly } ?: "").let { if (it < 0) 9 else it } }, { !it.pinned }))
    }
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
private const val UNDO_MS = 5_000L

/** How far a card goes, of its width, to pin or delete when let go. */
private const val PIN_AT = 0.3f
private const val DELETE_AT = 0.35f
