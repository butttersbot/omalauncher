package dev.omalauncher.messages.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.composerShape
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.omalauncher.messages.engine.Part
import dev.omalauncher.messages.engine.Upright
import dev.omalauncher.messages.engine.Mic
import dev.omalauncher.messages.engine.Alerts
import dev.omalauncher.messages.engine.Desk
import dev.omalauncher.messages.engine.CafOpus
import dev.omalauncher.messages.engine.MacLink
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.omalauncher.messages.engine.Parts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.messages.engine.Conversation
import dev.omalauncher.messages.engine.ConversationBook
import dev.omalauncher.messages.engine.Message
import dev.omalauncher.messages.engine.Messages
import dev.omalauncher.messages.engine.Sent
import dev.omalauncher.ui.ComposerState
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.SomaComposer
import dev.omalauncher.ui.initials
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/* The cover's screens, in the active Soma look: the inbox, a conversation, and the one-time access page. */

private val clock = DateTimeFormatter.ofPattern("HH:mm")

/** When a message came, as a list shows it: the time today, then Yesterday, the weekday this week, else the date. */
internal fun whenLabel(
    time: Long,
    today: LocalDate = LocalDate.now(),
): String {
    val at = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val day = at.toLocalDate()
    return when {
        day == today -> at.format(clock)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else -> day.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

/** Whether the look draws ink lines (Manga, Instrument): bubbles from others then get an outline instead of a fill. */
internal val inked @Composable get() = Pack.look.line > 0.dp

@Composable
internal fun Avatar(
    name: String,
    size: Int,
) {
    // A steady color per person from the look's own inks.
    val fills = listOf(Pack.foreground, Pack.accent, Pack.background)
    val fill = fills[(name.hashCode() and 0x7fffffff) % fills.size]
    val ink = if (fill == Pack.background) Pack.foreground else Pack.background
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(fill).border(2.dp, Pack.foreground, CircleShape),
        contentAlignment = Alignment.Center,
    ) { BasicText(initials(name), style = Pack.display((size / 3).sp, ink)) }
}

@Composable
private fun TextButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (inked) 0.dp else 20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (filled) Pack.foreground else Pack.background, shape)
            .border(2.dp, Pack.foreground, shape)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { BasicText(label, style = Pack.body(Pack.size.body, if (filled) Pack.background else Pack.foreground).copy(fontWeight = FontWeight.SemiBold)) }
}

/** The first page until notification access is on: what the app needs and why, and a way to look around first. */
@Composable
fun AccessScreen(openBubbles: Boolean) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        BasicText(Pack.caption("Soma"), style = Pack.mono(Pack.size.small, Pack.muted))
        BasicText(Pack.heading("Messages"), style = Pack.display(40.sp))
        Spacer(Modifier.height(18.dp))
        val why =
            if (openBubbles) {
                "Your iMessages come through OpenBubbles, which keeps doing the sending. To show them here and answer them, " +
                    "Android needs your OK for this app to read OpenBubbles' notifications. Nothing else is read, and nothing leaves the phone."
            } else {
                "Soma Messages shows your iMessages through OpenBubbles, which isn't on this phone. Install it and set it up first."
            }
        BasicText(why, style = Pack.body(Pack.size.item, Pack.foreground).copy(lineHeight = 24.sp))
        Spacer(Modifier.height(24.dp))
        if (openBubbles) TextButton("Turn on notification access", filled = true) { context.startActivity(Messages.accessSettings(context)) }
        Spacer(Modifier.height(10.dp))
        TextButton("Look around with example conversations", filled = false) { Messages.showSamples(true) }
    }
}

@Composable
fun Inbox(
    book: ConversationBook,
    selected: String?,
    samples: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations = book.conversations
    val left by Desk.left.collectAsState()
    Column(modifier) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)) {
            BasicText(Pack.caption("Soma · メッセージ"), style = Pack.mono(Pack.size.small, Pack.muted))
            BasicText(Pack.heading("Messages"), style = Pack.display(36.sp))
        }
        if (samples) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).clickable { Messages.showSamples(false) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Example conversations · ", style = Pack.mono(Pack.size.small, Pack.muted))
                BasicText("show mine", style = Pack.mono(Pack.size.small, Pack.accent))
            }
        }
        val trouble by MacLink.trouble.collectAsState()
        if (trouble.isNotEmpty() && !samples) {
            BasicText(trouble, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = Pack.mono(Pack.size.small, Pack.muted))
        }
        if (conversations.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(Pack.heading("Nothing yet"), style = Pack.display(20.sp))
                Spacer(Modifier.height(6.dp))
                BasicText(if (Messages.linked) "Your conversations are on their way from your Mac." else "Conversations appear here as messages arrive in OpenBubbles.", style = Pack.body(Pack.size.body, Pack.muted))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(conversations, key = { it.id }) { c -> ConversationRow(c, c.id == selected, left[c.id]?.draft.orEmpty()) { onOpen(c.id) } }
        }
    }
}

@Composable
private fun ConversationRow(
    c: Conversation,
    selected: Boolean,
    draft: String,
    onClick: () -> Unit,
) {
    val last = c.last
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Pack.surface else Color.Transparent)
            .semantics(mergeDescendants = true) { contentDescription = c.title + if (c.unread > 0) ", ${c.unread} unread" else "" }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.title, 46)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    c.title,
                    Modifier.weight(1f),
                    style = Pack.body(Pack.size.item).copy(fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (last != null) BasicText(whenLabel(last.time), style = Pack.mono(Pack.size.small, Pack.muted))
            }
            // Words waiting to be sent show in place of the last message, as iMessage marks a draft.
            if (draft.isNotBlank()) {
                BasicText(
                    androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Pack.accent, fontWeight = FontWeight.SemiBold))
                        append("Draft  ")
                        pop()
                        append(draft.replace('\n', ' '))
                    },
                    style = Pack.body(Pack.size.body, Pack.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val who = if (last != null && c.isGroup && !last.fromMe) last.sender + ": " else ""
                BasicText(
                    who + (last?.text ?: ""),
                    style = Pack.body(Pack.size.body, if (c.unread > 0) Pack.foreground else Pack.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (c.unread > 0) {
            Spacer(Modifier.size(10.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(Pack.accent))
        }
    }
}

/** What each conversation is putting together besides its words: a quoted reply, photos, and link previews put away. */
@Stable
class Pending {
    val quotes = mutableStateMapOf<String, String>()
    val photos = mutableStateMapOf<String, List<Uri>>()

    /** Addresses whose preview tile was closed: the words keep them, the tile doesn't come back. */
    val unlinked = mutableStateMapOf<String, Set<String>>()
}

/** The most photos handed over at once. */
private const val MAX_PHOTOS = 10

/**
 * A conversation: its messages as balloons, and the shared composer. Replies go through OpenBubbles' own Reply
 * action; until a conversation has had a notification since the app started, that action isn't known, so the app
 * says so and offers to open the conversation in OpenBubbles.
 *
 * Drag a message right to reply: it follows the finger, and past the mark it becomes the quote above the field.
 * Press and hold for Reply and Copy. Links and attachments are bubbles of their own, before the words. Photos wait
 * in the field and go to OpenBubbles' conversation with the words, since its Reply action carries words only.
 * [pending] holds each conversation's quote and attachments, like its draft.
 */
@Composable
fun Thread(
    c: Conversation,
    draft: ComposerState,
    pending: Pending,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Unfolded, a link opens in the side panel beside the chat; on the cover, in the browser.
    val openLink = LocalOpenLink.current
    var stuck by remember(c.id) { mutableStateOf(false) }
    var noOpenBubbles by remember(c.id) { mutableStateOf(false) }
    var held by remember(c.id) { mutableStateOf<Message?>(null) }
    // Where the owner left it: that place, or the latest.
    val left = remember(c.id) { Desk[c.id] }
    val size = c.messages.size
    val list = rememberLazyListState(if (left.hasPlace) left.index.coerceIn(0, maxOf(0, size - 1)) else maxOf(0, size - 1), if (left.hasPlace) left.offset else 0)
    // Whether the owner is following the latest: decided when a scroll ends, not by the list's size, so a composer
    // that grows (a quote, photos, the keyboard) keeps the latest in view instead of leaving it behind.
    var latest by remember(c.id) { mutableStateOf(!left.hasPlace) }
    val currentSize by rememberUpdatedState(size)
    LaunchedEffect(c.id) {
        snapshotFlow { list.isScrollInProgress }.drop(1).collect { scrolling -> if (!scrolling) latest = !list.canScrollForward }
    }
    LaunchedEffect(c.id) {
        snapshotFlow { list.layoutInfo.viewportSize }.drop(1).collect { if (latest && currentSize > 0) list.scrollToItem(currentSize - 1) }
    }
    // Messages that came since the place was left, or while reading further up.
    var fresh by remember(c.id) { mutableIntStateOf(if (left.hasPlace) (size - left.count).coerceAtLeast(0) else 0) }
    // The oldest and newest times shown, to tell earlier history loaded at the top from new messages at the bottom by
    // when they were sent, not by how many more there are: a page of history counted as new ("↓ 36 new", the 0.7.7
    // walk), and a message replaced by the Mac's copy of it isn't one more.
    var oldest by remember(c.id) { mutableStateOf(c.messages.firstOrNull()?.time ?: 0L) }
    var newest by remember(c.id) { mutableStateOf(c.messages.lastOrNull()?.time ?: 0L) }
    LaunchedEffect(size) {
        val before = oldest
        val after = newest
        oldest = c.messages.firstOrNull()?.time ?: 0L
        newest = c.messages.lastOrNull()?.time ?: 0L
        // Earlier history arrived above: the same messages stay in view where the owner is reading.
        val prepended = if (before > 0) c.messages.count { it.time < before } else 0
        if (prepended > 0) list.scrollToItem(list.firstVisibleItemIndex + prepended, list.firstVisibleItemScrollOffset)
        // The first messages into an empty conversation: shown from the latest, nothing counted.
        if (after == 0L) {
            if (latest && size > 0) list.scrollToItem(size - 1)
            return@LaunchedEffect
        }
        val came = c.messages.count { it.time > after }
        if (came <= 0) return@LaunchedEffect
        // Following along at the bottom: the new ones come into view. Reading further up: the pill counts them.
        if (latest) list.animateScrollToItem(size - 1) else fresh += came
    }
    LaunchedEffect(latest) { if (latest) fresh = 0 }
    // Near the top: the Mac's earlier history, a page at a time (the first fetch brings only the latest 50).
    LaunchedEffect(c.id) {
        // Asked again as each page arrives too, not only as the place changes: reaching the top while a page was on its
        // way left the last one never asked for (the Mac Pro's 0.8.0 walk stopped at Long #22).
        snapshotFlow { list.firstVisibleItemIndex to currentSize }.collect { (first, n) -> if (first <= 5 && n > 0) Messages.loadEarlier(c.id) }
    }
    // The draft, the quote and the place are kept as they change, and once more on leaving, so a closed app loses none.
    fun keep() {
        val place = if (latest) -1 to 0 else list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        Desk.update(c.id) { it.copy(draft = draft.text, quote = pending.quotes[c.id], index = place.first, offset = place.second, count = if (place.first >= 0) currentSize else 0) }
    }
    LaunchedEffect(c.id) {
        snapshotFlow { Triple(draft.text, pending.quotes[c.id], latest to (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)) }
            .collectLatest {
                delay(300)
                keep()
            }
    }
    DisposableEffect(c.id) { onDispose { keep() } }
    // On screen: its new messages need no notification, and any it had go.
    DisposableEffect(c.id) {
        Alerts.looking = c.id
        Alerts.clear(context, c.id)
        onDispose { if (Alerts.looking == c.id) Alerts.looking = null }
    }
    fun quoteOf(m: Message) = (if (m.fromMe) "You" else m.sender.ifEmpty { c.title }) + ": " + m.text.take(90)
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
            if (uris.isNotEmpty()) pending.photos[c.id] = (pending.photos[c.id].orEmpty() + uris).distinct().take(MAX_PHOTOS)
        }
    // The microphone for voice messages: Android asks him once; the tap after he says yes records.
    val hear = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val mic = remember { Mic(context.applicationContext) { hear.launch(android.Manifest.permission.RECORD_AUDIO) } }
    DisposableEffect(mic) { onDispose { mic.stopPlaying() } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).semantics { contentDescription = "Back to conversations"; role = Role.Button }.clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { BasicText("‹", style = Pack.body(30.sp)) }
            }
            Avatar(c.title, 32)
            Spacer(Modifier.size(10.dp))
            Column {
                BasicText(c.title, style = Pack.body(Pack.size.item).copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(Pack.caption(if (Messages.linked) "iMessage · from your Mac" else "iMessage · through OpenBubbles"), style = Pack.mono(Pack.size.small, Pack.muted))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Room under the last message for the Latest pill, so it never covers the end of its words (the 0.7.6 walk),
            // but only while the pill is there: kept always, it left a strip of empty paper between the last message and
            // the composer whenever the owner was at the latest (the Fold, 0.8.2). Its height is the pill's own, measured.
            val pillShown = !latest && size > 0
            val density = LocalDensity.current
            var pillHeight by remember { mutableIntStateOf(0) }
            val pillRoom by androidx.compose.animation.core.animateDpAsState(
                if (pillShown) with(density) { pillHeight.toDp() } + PILL_GAP * 2 else 0.dp,
                label = "pill room",
            )
            // A short conversation sits down on the composer too, as in Messages, not up under the name with paper below.
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = pillRoom),
                verticalArrangement = Arrangement.Bottom,
            ) {
                itemsIndexed(c.messages) { i, m ->
                    val prev = c.messages.getOrNull(i - 1)
                    val next = c.messages.getOrNull(i + 1)
                    val first = prev == null || prev.sender != m.sender
                    val tail = next == null || next.sender != m.sender
                    if (prev == null || whenLabel(prev.time) != whenLabel(m.time) && m.time - prev.time > 60 * 60_000L) {
                        BasicText(
                            Pack.caption(whenLabel(m.time)),
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            style = Pack.mono(Pack.size.small, Pack.muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        )
                    }
                    if (c.isGroup && first && !m.fromMe) BasicText(m.sender, Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp), style = Pack.body(Pack.size.small, Pack.muted))
                    val parts = remember(m.text) { Parts.of(m.text) }
                    parts.forEachIndexed { j, part ->
                        // Photos and videos stand on their own, as iMessage has them: no balloon, no tail (Boss, 2026-10-24).
                        val bare = part is Part.Attachment && m.files.any { visual(it) }
                        Balloon(
                            m,
                            tail = tail && j == parts.lastIndex && !bare,
                            bare = bare,
                            onReply = {
                                pending.quotes[c.id] = quoteOf(m)
                                m.id?.let { Messages.quoting[c.id] = it }
                            },
                            onHold = { held = m },
                            onTap = tapFor(part, c.id, context, openLink),
                            // A photo stands a little clear of the balloon under it (the 0.8.3 walk: 3 px apart).
                            modifier = Modifier.padding(top = if (first && j == 0) 8.dp else 2.dp, bottom = if (bare) 2.dp else 0.dp),
                        ) { ink ->
                            if (j == 0) m.quote?.let { QuoteInBalloon(it, ink) }
                            PartContent(part, ink, m.files, onHold = { held = m })
                        }
                    }
                    if (m.tapbacks.isNotEmpty()) Tapbacks(m)
                    if (m.failed && next?.failed != true) {
                        // Words can go again from here; photos are picked again.
                        val again = Parts.of(m.text).none { it is Part.Attachment } || m.files.any { it.startsWith("local:") }
                        BasicText(
                            Pack.caption(if (again) "Not sent · tap to try again" else "Not sent"),
                            Modifier.fillMaxWidth().clickable(enabled = again) { Messages.retry(context, c.id, m) }.padding(top = 4.dp, end = 6.dp),
                            style = Pack.mono(Pack.size.small, Pack.accent).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
                        )
                    }
                    // Once under a run of handed-off messages, not under each.
                    if (m.handedOff && next?.handedOff != true) {
                        BasicText(
                            Pack.caption("Waiting in OpenBubbles · tap to finish"),
                            Modifier.fillMaxWidth().clickable { Messages.openInOpenBubbles(context, c.id) }.padding(top = 4.dp, end = 6.dp),
                            style = Pack.mono(Pack.size.small, Pack.muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
                        )
                    }
                }
            }
            if (pillShown) {
                LatestPill(fresh, Modifier.align(Alignment.BottomCenter).padding(bottom = PILL_GAP).onSizeChanged { pillHeight = it.height }) {
                    scope.launch {
                        list.animateScrollToItem(size - 1)
                        latest = true
                    }
                }
            }
            // Back closes the menu, and only the menu: it went to the screen's own back (the slide away), which left the
            // conversation's taps dead after it (the 0.7.8 walk: a link tapped after closing the menu did nothing).
            androidx.activity.compose.BackHandler(enabled = held != null) { held = null }
            held?.let { m ->
                HoldMenu(
                    onReply = {
                        pending.quotes[c.id] = quoteOf(m)
                        m.id?.let { Messages.quoting[c.id] = it }
                        held = null
                    },
                    onCopy = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", m.text))
                        held = null
                    },
                    onRemove =
                        if (m.handedOff) {
                            {
                                Messages.drop(c.id, m)
                                held = null
                            }
                        } else {
                            null
                        },
                    onDismiss = { held = null },
                    // Through the Mac, a message it knows takes a tapback.
                    onTapback =
                        if (Messages.linked && m.id?.startsWith("c:") == false) {
                            { kind ->
                                Messages.tapback(c.id, m, kind)
                                held = null
                            }
                        } else {
                            null
                        },
                    mine = m.tapbacks.filter { it.startsWith("me:") }.map { it.removePrefix("me:") },
                )
            }
        }
        val photos = pending.photos[c.id].orEmpty()
        val notice by Messages.notice.collectAsState()
        // Said a few seconds, in this conversation only; the message that didn't go keeps its own "Not sent" line.
        LaunchedEffect(notice) {
            if (notice.isNotEmpty()) {
                kotlinx.coroutines.delay(NOTICE_MS)
                if (Messages.notice.value == notice) Messages.clearNotice()
            }
        }
        DisposableEffect(c.id) { onDispose { Messages.clearNotice(c.id) } }
        if (notice.isNotEmpty() && Messages.noticeFor.let { it == null || it == c.id }) {
            BasicText(
                notice,
                Modifier.fillMaxWidth().clickable { Messages.clearNotice() }.padding(horizontal = 14.dp, vertical = 6.dp),
                style = Pack.body(Pack.size.small, Pack.accent),
            )
        }
        if (noOpenBubbles) {
            BasicText("OpenBubbles isn't on this phone, so photos can't go out. They're still in the field.", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = Pack.body(Pack.size.small, Pack.muted))
        } else if (photos.isEmpty() && (stuck || !Messages.canReply(c.id))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                BasicText(
                    "Replies go out from here once ${c.title.substringBefore(' ')} writes again. Until then, answer in OpenBubbles.",
                    style = Pack.body(Pack.size.small, Pack.muted),
                )
                Spacer(Modifier.height(6.dp))
                TextButton("Open in OpenBubbles", filled = false) { Messages.openInOpenBubbles(context, c.id) }
            }
        }
        val quote = pending.quotes[c.id]
        // The first address in the words gets a preview tile, unless its tile was closed.
        val link = Parts.links(draft.text).firstOrNull { it !in pending.unlinked[c.id].orEmpty() }
        SomaComposer(
            draft,
            // Voice messages go through the Mac only: OpenBubbles' share sheet can't send one as a voice message.
            voice = if (Messages.linked && !Messages.samples.value) mic else null,
            onSendVoice = { clip ->
                clip.file?.let { f ->
                    mic.handOver()
                    scope.launch {
                        withContext(Dispatchers.IO) { Messages.sendVoice(context, c.id, java.io.File(f), quote) }
                        pending.quotes.remove(c.id)
                    }
                }
            },
            onSend = { text ->
                if (photos.isEmpty()) {
                    if (Messages.send(context, c.id, text, quote) == Sent.NoReplyPath) {
                        // Nothing went out: the words go back in the field, and the quote stays.
                        draft.text = text
                        stuck = true
                    } else {
                        pending.quotes.remove(c.id)
                    }
                } else if (Messages.linked && !Messages.samples.value) {
                    // Through the Mac, photos and words go out together, from here.
                    Messages.sendPhotos(context, c.id, photos, text, photos.map { summary(context, listOf(it)) }, quote)
                    pending.photos.remove(c.id)
                    pending.quotes.remove(c.id)
                } else {
                    // Photos go to OpenBubbles' own chat, which sends them; the chat here shows them waiting there.
                    val handed = listOf(summary(context, photos)) + listOfNotNull(text.ifEmpty { null })
                    if (Messages.samples.value) {
                        Messages.handedOff(c.id, handed, quote)
                        pending.photos.remove(c.id)
                        pending.quotes.remove(c.id)
                    } else {
                        scope.launch {
                            val share = withContext(Dispatchers.IO) { Messages.share(context, c.id, photos, text, beside = openLink != null) }
                            if (share == null) {
                                draft.text = text
                                noOpenBubbles = true
                            } else {
                                Messages.handedOff(c.id, handed, quote)
                                pending.photos.remove(c.id)
                                pending.quotes.remove(c.id)
                                context.startActivity(share)
                            }
                        }
                    }
                }
            },
            modifier = Modifier.padding(bottom = 4.dp),
            placeholder = "iMessage",
            sendColor = Pack.foreground,
            onSendColor = Pack.background,
            onAttach = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            attached = photos.isNotEmpty(),
            sendsElsewhere = photos.isNotEmpty(),
            above = quote?.let { q -> { QuoteBar(q) { pending.quotes.remove(c.id) } } },
            inField =
                if (photos.isEmpty() && link == null) {
                    null
                } else {
                    {
                        FieldTiles(
                            photos,
                            link,
                            onDropPhoto = { uri -> pending.photos[c.id] = photos - uri },
                            onDropLink = { url -> pending.unlinked[c.id] = pending.unlinked[c.id].orEmpty() + url },
                        )
                    }
                },
        )
    }
}

/** Between the Latest pill and the composer, and again between the pill and the last message above it. */
private val PILL_GAP = 10.dp

/** Back to the newest message, with how many came since the owner was last there. */
@Composable
private fun LatestPill(
    fresh: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = composerShape(null)
    Box(
        modifier
            .clip(shape)
            .background(Pack.foreground, shape)
            .semantics { role = Role.Button; contentDescription = if (fresh > 0) "$fresh new messages, go to the latest" else "Go to the latest message" }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { BasicText(if (fresh > 0) "↓ $fresh new" else "↓ Latest", style = Pack.mono(Pack.size.small, Pack.background)) }
}

/** "2 Photos & 1 Video", as OpenBubbles words it. */
private fun summary(
    context: android.content.Context,
    photos: List<Uri>,
): String {
    val videos = photos.count { context.contentResolver.getType(it)?.startsWith("video/") == true }
    val stills = photos.size - videos
    fun n(
        count: Int,
        what: String,
    ) = "$count $what${if (count > 1) "s" else ""}"
    return listOfNotNull(stills.takeIf { it > 0 }?.let { n(it, "Photo") }, videos.takeIf { it > 0 }?.let { n(it, "Video") }).joinToString(" & ")
}

/** What a tap on [part] does: a link opens in the browser; an attachment or a previewed page opens the conversation in OpenBubbles. */
private fun tapFor(
    part: Part,
    id: String,
    context: android.content.Context,
    openLink: ((String) -> Unit)?,
): (() -> Unit)? =
    when (part) {
        is Part.Words -> null
        is Part.Link -> {
            {
                if (openLink != null) openLink(Parts.openable(part.url)) else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Parts.openable(part.url))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        // Through the Mac, photos and videos open from their own frames (Photos, Videos); nothing else to open yet.
        is Part.Website, is Part.Attachment -> if (Messages.linked) null else {
            { Messages.openInOpenBubbles(context, id) }
        }
    }

/** A photo's or video's own corners, iMessage's: it stands without a balloon. */
private val MEDIA_SHAPE = RoundedCornerShape(18.dp)

/** A photo or video, which shows by itself rather than in a balloon. */
private fun visual(file: String): Boolean = file.substringAfter('|').let { it.startsWith("image/") || it.startsWith("video/") }

/** One part of a message inside its balloon. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PartContent(
    part: Part,
    ink: Color,
    files: List<String> = emptyList(),
    onHold: () -> Unit = {},
) {
    when (part) {
        is Part.Words -> BasicText(part.text, style = Pack.body(Pack.size.item, ink))
        is Part.Link -> Card(part.host, part.path.ifEmpty { null }, "↗", ink)
        is Part.Website -> Card(part.host.ifEmpty { "Website" }, part.title, "↗", ink)
        is Part.Attachment ->
            // From the Mac, pictures show as themselves; anything else, by its card.
            if (files.any { it.substringAfter('|').startsWith("image/") }) {
                Photos(files.filter { it.substringAfter('|').startsWith("image/") }, onHold)
            } else if (files.any { it.substringAfter('|').startsWith("video/") }) {
                Videos(files.filter { it.substringAfter('|').startsWith("video/") }, ink, onHold)
            } else if (files.any { it.substringAfter('|') == "audio/x-caf" }) {
                Voice(files.first { it.substringAfter('|') == "audio/x-caf" }, ink, onHold)
            } else if (Messages.linked && files.any { !it.startsWith("local:") }) {
                // Anything else from the Mac (a PDF, a document): fetched on a tap and opened in the app for it.
                val context = LocalContext.current
                Box(Modifier.combinedClickable(onLongClick = onHold) { openMedia(context, files.first()) }.semantics { contentDescription = part.summary + ", tap to open" }) {
                    Card(part.summary, "Tap to open", "▣", ink)
                }
            } else {
                Card(part.summary, if (Messages.linked) null else "Open in OpenBubbles", if (part.kind == "Video") "▶" else "▣", ink)
            }
    }
}

/**
 * Pictures from the Mac, fetched once and kept in the cache: one alone fits a balloon's width, several sit side by side
 * and scroll. A tap opens the picture in the phone's own viewer.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Photos(
    files: List<String>,
    onHold: () -> Unit = {},
) {
    val one = files.size == 1
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            val id = f.substringBefore('|')
            val context = LocalContext.current
            // A photo picked here and still on its way: the phone's own copy. Otherwise the Mac's, fetched once.
            val local = id.takeIf { it.startsWith("local:") }?.removePrefix("local:")?.let { Uri.parse(it) }
            val file by produceState<java.io.File?>(null, id) { if (local == null) value = withContext(Dispatchers.IO) { MacLink.file(context, id, picture = true) } }
            val picture by produceState<ImageBitmap?>(null, file, local) {
                value =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (local != null) {
                                context.contentResolver.loadThumbnail(local, android.util.Size(720, 720), null).asImageBitmap()
                            } else {
                                file?.let { decode(it, 1080) }
                            }
                        }.getOrNull()
                    }
            }
            // Round in every look, as the balloons are; the composer's square looks square only the field.
            val shape = MEDIA_SHAPE
            // One alone: 240 dp wide, as tall as its shape asks within 120-320 dp; several: 132 dp squares. The picture
            // fills its frame whatever its own size (a tiny one is scaled up, never drawn at its few pixels).
            val ratio = picture?.let { it.height.toFloat() / it.width.coerceAtLeast(1) } ?: 0.75f
            val size = if (one) Modifier.width(240.dp).height((240 * ratio).coerceIn(120f, 320f).dp) else Modifier.size(132.dp)
            Box(
                // A hold is the balloon's (tapbacks, reply): the picture's own tap would otherwise take it (Boss, 2026-10-23).
                size.clip(shape).background(Pack.surface).combinedClickable(onLongClick = onHold) { file?.let { view(context, it) } }
                    .semantics { contentDescription = "Photo" },
                contentAlignment = Alignment.Center,
            ) {
                val p = picture
                if (p != null) {
                    Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    BasicText(if (file == null) "Photo" else "", style = Pack.body(Pack.size.small, Pack.muted))
                }
            }
        }
    }
}

/**
 * Videos from the Mac (Boss, 2026-10-23: a "1 Video" card that did nothing). Not fetched until tapped, as they can be
 * large; then kept in the cache, shown by a frame of their own from then on, and played in the phone's own player.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Videos(
    files: List<String>,
    ink: Color,
    onHold: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            val id = f.substringBefore('|')
            val mime = f.substringAfter('|').ifEmpty { "video/*" }
            var loading by remember(id) { mutableStateOf(false) }
            var failed by remember(id) { mutableStateOf(false) }
            var kept by remember(id) { mutableStateOf(MacLink.cached(context, id, picture = false).takeIf { it.isFile && it.length() > 0 }) }
            val frame by produceState<ImageBitmap?>(null, kept) {
                value = kept?.let { k -> withContext(Dispatchers.IO) { posterOf(k) } }
            }
            fun open() {
                kept?.let { return play(context, it, mime) }
                if (loading) return
                loading = true
                failed = false
                scope.launch {
                    val got = withContext(Dispatchers.IO) { MacLink.file(context, id, picture = false) }
                    loading = false
                    if (got == null) failed = true else {
                        kept = got
                        play(context, got, mime)
                    }
                }
            }
            val p = frame
            if (p != null) {
                val ratio = (p.height.toFloat() / p.width.coerceAtLeast(1)).coerceIn(0.5f, 1.4f)
                Box(
                    Modifier.width(240.dp).height((240 * ratio).dp).clip(MEDIA_SHAPE).background(Pack.surface)
                        .combinedClickable(onLongClick = onHold) { open() }.semantics { contentDescription = "Video, tap to play" },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        BasicText("▶", style = Pack.body(Pack.size.item, Color.White))
                    }
                }
            } else {
                Box(Modifier.combinedClickable(onLongClick = onHold) { open() }.semantics { contentDescription = "Video, tap to play" }) {
                    Card("Video", if (loading) "Getting it from your Mac…" else if (failed) "Couldn't get it from your Mac · tap to try again" else "Tap to play", "▶", ink)
                }
            }
        }
    }
}

/**
 * A frame from early in [f], to stand for the video; null when it can't be read. Kept beside it as a JPEG, so it shows
 * at once next time, after a restart too (the 0.7.8 walk: after one the video was a plain card again).
 */
private fun posterOf(f: java.io.File): ImageBitmap? =
    runCatching {
        // ".upright": frames kept before 0.8.3 could be sideways (Boss, 2026-10-24), so those are not read again.
        val kept = java.io.File(f.path + ".upright.jpg")
        if (kept.isFile && kept.length() > 0) {
            android.graphics.BitmapFactory.decodeFile(kept.path)?.let { return@runCatching it.asImageBitmap() }
        }
        android.media.MediaMetadataRetriever().use { r ->
            r.setDataSource(f.path)
            val rotation = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val storedW = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val storedH = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            r.getScaledFrameAtTime(500_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720)?.let { frame ->
                if (Upright.frameNeedsTurn(rotation, frame.width, frame.height, storedW, storedH)) turned(frame, rotation, false) else frame
            }?.also { bmp ->
                runCatching { kept.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
            }?.asImageBitmap()
        }
    }.getOrNull()

/**
 * A voice message (Boss, 2026-10-23: one from a friend couldn't be seen): fetched from the Mac on the first tap,
 * rewrapped from Apple's CAF as Ogg Opus ([CafOpus]) and kept, then played here, in the conversation; a tap again stops it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Voice(
    file: String,
    ink: Color,
    onHold: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = file.substringBefore('|')
    var state by remember(id) { mutableStateOf("Tap to play") }
    val player = remember(id) { arrayOfNulls<android.media.MediaPlayer>(1) }
    DisposableEffect(id) { onDispose { player[0]?.release(); player[0] = null } }
    fun toggle() {
        player[0]?.let { p ->
            p.release()
            player[0] = null
            state = "Tap to play"
            return
        }
        state = "Getting it from your Mac…"
        scope.launch {
            val ogg =
                withContext(Dispatchers.IO) {
                    // One recorded here and still on its way (or not sent): its own recording.
                    if (id.startsWith("local:")) return@withContext java.io.File(id.removePrefix("local:")).takeIf { it.isFile }
                    val out = java.io.File(MacLink.cached(context, id, picture = false).path + ".ogg")
                    if (out.isFile && out.length() > 0) return@withContext out
                    val caf = MacLink.file(context, id, picture = false) ?: return@withContext null
                    CafOpus.toOgg(caf.readBytes())?.let { out.writeBytes(it); out }
                }
            if (ogg == null) {
                state = "Couldn't play it · tap to try again"
                return@launch
            }
            runCatching {
                android.media.MediaPlayer().apply {
                    setDataSource(ogg.path)
                    setOnCompletionListener {
                        it.release()
                        player[0] = null
                        state = "Tap to play"
                    }
                    prepare()
                    start()
                    player[0] = this
                }
                state = "Playing · tap to stop"
            }.onFailure { state = "Couldn't play it · tap to try again" }
        }
    }
    Box(Modifier.combinedClickable(onLongClick = onHold) { toggle() }.semantics { contentDescription = "Voice message, $state" }) {
        Card("Voice message", state, if (player[0] != null) "■" else "▶", ink)
    }
}

/**
 * Opens the Mac's attachment [file] ("id|mime") from outside its balloon (the unfolded table): fetched if it isn't
 * kept yet, then a picture in the viewer, a video in the player.
 */
internal fun openMedia(
    context: android.content.Context,
    file: String,
) {
    val id = file.substringBefore('|')
    val mime = file.substringAfter('|')
    val picture = mime.startsWith("image/")
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    if (!MacLink.cached(context, id, picture).let { it.isFile && it.length() > 0 }) {
        android.widget.Toast.makeText(context, "Getting it from your Mac…", android.widget.Toast.LENGTH_SHORT).show()
    }
    kotlin.concurrent.thread(name = "open-media", isDaemon = true) {
        val f = MacLink.file(context, id, picture)
        main.post {
            when {
                f == null -> android.widget.Toast.makeText(context, "Couldn't get it from your Mac", android.widget.Toast.LENGTH_SHORT).show()
                picture -> view(context, f)
                else -> play(context, f, mime)
            }
        }
    }
}

/** Plays [f] in the phone's own video player, or opens any other file in the app for its [mime]. */
private fun play(
    context: android.content.Context,
    f: java.io.File,
    mime: String,
) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "dev.omalauncher.messages.photos", f)
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.widget.Toast.makeText(context, "No app here opens this", android.widget.Toast.LENGTH_SHORT).show() }
}

/** [f] decoded no wider or taller than about [max] pixels. */
private fun decode(
    f: java.io.File,
    max: Int,
): ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
    val bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val tag = runCatching { android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    val (turn, mirror) = Upright.of(tag)
    return turned(bmp, turn, mirror).asImageBitmap()
}

/** [bmp] mirrored if [mirror], then turned [degrees] clockwise; itself when neither. */
private fun turned(
    bmp: android.graphics.Bitmap,
    degrees: Int,
    mirror: Boolean,
): android.graphics.Bitmap {
    if (degrees == 0 && !mirror) return bmp
    val m = android.graphics.Matrix().apply {
        if (mirror) postScale(-1f, 1f)
        postRotate(degrees.toFloat())
    }
    return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/** Opens [f] in the phone's own viewer. */
private fun view(
    context: android.content.Context,
    f: java.io.File,
) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "dev.omalauncher.messages.photos", f)
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** A link or attachment: a square mark, a bold line, and a quieter one under it. */
@Composable
internal fun Card(
    title: String,
    detail: String?,
    mark: String,
    ink: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).border(1.5.dp, ink.copy(alpha = 0.6f), composerShape(8.dp)), contentAlignment = Alignment.Center) {
            BasicText(mark, style = Pack.body(Pack.size.item, ink))
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.widthIn(max = 220.dp)) {
            BasicText(title, style = Pack.body(Pack.size.item, ink).copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) BasicText(detail, style = Pack.body(Pack.size.small, ink.copy(alpha = 0.72f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The quoted message at the top of a reply's balloon. */
@Composable
private fun QuoteInBalloon(
    q: String,
    ink: Color,
) {
    Row(Modifier.padding(bottom = 6.dp).background(ink.copy(alpha = 0.12f))) {
        Box(Modifier.width(3.dp).height(32.dp).background(ink.copy(alpha = 0.6f)))
        BasicText(q, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = Pack.body(Pack.size.small, ink.copy(alpha = 0.85f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Inside the field, over the words, as iMessage shows them: photos as 72 dp tiles, then the link preview as a wide
 * one, each with × at its corner. A line under photos says where they go.
 */
@Composable
private fun FieldTiles(
    photos: List<Uri>,
    link: String?,
    onDropPhoto: (Uri) -> Unit,
    onDropLink: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (photos.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                photos.forEach { uri -> Tile("Remove photo", { onDropPhoto(uri) }) { Thumb(uri) } }
            }
            BasicText(if (Messages.linked) "Photos go out with the words" else "Photos open in OpenBubbles' chat, ready to send", Modifier.padding(start = 4.dp), style = Pack.body(Pack.size.small, Pack.muted))
        }
        if (link != null) {
            Tile("Remove link preview", { onDropLink(link) }, wide = true) {
                Box(Modifier.fillMaxSize().background(Pack.surface).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    Card(Parts.host(link), Parts.path(link).ifEmpty { null }, "↗", Pack.foreground)
                }
            }
        }
    }
}

/** A tile in the field with × at its top right corner. */
@Composable
private fun Tile(
    dropLabel: String,
    onDrop: () -> Unit,
    wide: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = composerShape(12.dp)
    Box(
        (if (wide) Modifier.fillMaxWidth().height(60.dp) else Modifier.size(72.dp))
            .clip(shape)
            .border(if (inked) 2.dp else 1.dp, Pack.border, shape),
    ) {
        content()
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(composerShape(null)).background(Pack.foreground.copy(alpha = 0.8f))
                .semantics { contentDescription = dropLabel; role = Role.Button }.clickable(onClick = onDrop),
            contentAlignment = Alignment.Center,
        ) { BasicText("✕", style = Pack.body(Pack.size.small, Pack.background)) }
    }
}

/** A picked photo or video, small. */
@Composable
private fun Thumb(uri: Uri) {
    val context = LocalContext.current
    val picture by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(216, 216), null).asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.fillMaxSize().background(Pack.surface)) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** The quote waiting above the field, with × to drop it. OpenBubbles sends words only, so the quote stays on this phone. */
@Composable
private fun QuoteBar(
    quote: String,
    onDrop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 42.dp).background(Pack.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(Pack.accent))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BasicText(Pack.caption("Replying · quoted on this phone"), style = Pack.mono(Pack.size.small, Pack.accent))
            BasicText(quote, style = Pack.body(Pack.size.body), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(40.dp).semantics { contentDescription = "Don't reply to this"; role = Role.Button }.clickable(onClick = onDrop),
            contentAlignment = Alignment.Center,
        ) { BasicText("✕", style = Pack.body(Pack.size.body, Pack.muted)) }
    }
}

/** Press and hold: Reply and Copy, for anyone who'd rather tap than drag. */
@Composable
private fun HoldMenu(
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
    onTapback: ((String) -> Unit)? = null,
    mine: List<String> = emptyList(),
) {
    Box(Modifier.fillMaxSize().background(Pack.foreground.copy(alpha = 0.18f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        val shape = composerShape(14.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // iMessage's six tapbacks, above the actions; the owner's own one is marked, and a second tap takes it off.
        if (onTapback != null) {
            Row(Modifier.padding(bottom = 8.dp).clip(shape).background(Pack.background, shape).border(2.dp, Pack.foreground, shape).padding(4.dp)) {
                TAPBACKS.forEach { (kind, glyph) ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(if (kind in mine) Pack.surface else Pack.background)
                            .semantics { role = Role.Button; contentDescription = "Tapback $kind" }.clickable { onTapback(kind) },
                        contentAlignment = Alignment.Center,
                    ) { BasicText(glyph, style = Pack.body(22.sp)) }
                }
            }
        }
        Row(Modifier.clip(shape).background(Pack.background, shape).border(2.dp, Pack.foreground, shape).padding(4.dp)) {
            // Remove only for a hand-off the owner didn't finish in OpenBubbles: nothing sent is ever taken back from here.
            (listOf("Reply" to onReply, "Copy" to onCopy) + listOfNotNull(onRemove?.let { "Remove" to it })).forEach { (label, act) ->
                Box(Modifier.semantics { role = Role.Button }.clickable(onClick = act).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    BasicText(label, style = Pack.body(Pack.size.item).copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
        }
    }
}

/** iMessage's tapbacks and how they show. */
private val TAPBACKS = listOf("love" to "❤️", "like" to "👍", "dislike" to "👎", "laugh" to "😂", "emphasize" to "‼️", "question" to "❓")

/** The tapbacks on [m], small, under its balloon on its side: the owner's own outlined. An emoji tapback shows as itself. */
@Composable
private fun Tapbacks(m: Message) {
    val glyphs = m.tapbacks.map { t -> t.removePrefix("me:").let { k -> TAPBACKS.firstOrNull { it.first == k }?.second ?: k } to t.startsWith("me:") }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, start = 10.dp, end = 10.dp),
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        glyphs.forEach { (g, mine) ->
            Box(
                Modifier.padding(end = 4.dp).clip(CircleShape).background(Pack.surface).then(if (mine) Modifier.border(1.5.dp, Pack.foreground, CircleShape) else Modifier)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { BasicText(g, style = Pack.body(Pack.size.small)) }
        }
    }
}

/** How far a message is dragged before letting go makes it a reply. */
private val REPLY_AT = 64.dp

/**
 * One message: the owner's in ink on the right, others' on the left, outlined where the look draws ink lines; in an
 * inked look the last of a run gets a speech balloon's tail. The balloon follows a drag to the right (a reply) and a
 * long press; the empty space beside it leaves both to the conversation's own back drag.
 */
@Composable
private fun Balloon(
    m: Message,
    tail: Boolean,
    bare: Boolean = false,
    onReply: () -> Unit,
    onHold: () -> Unit,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (ink: Color) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val mark = with(density) { REPLY_AT.toPx() }
    val corner = 20.dp
    val balloon = inked && tail
    val tip = if (tail && !inked) 5.dp else corner
    val shape = if (m.fromMe) RoundedCornerShape(corner, corner, tip, corner) else RoundedCornerShape(corner, corner, corner, tip)
    val fill = if (m.fromMe) Pack.foreground else if (inked) Pack.background else Pack.surface
    val ink = if (m.fromMe) Pack.background else Pack.foreground
    val line = Pack.foreground
    // Only the balloon itself takes the reply drag; a drag anywhere else in the conversation is the way back.
    val replyDrag =
        Modifier
            .pointerInput(m) {
                var armed = false
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (armed) onReply()
                        armed = false
                        scope.launch { drag.animateTo(0f, Motion.spatial) }
                    },
                    onDragCancel = {
                        armed = false
                        scope.launch { drag.animateTo(0f, Motion.spatial) }
                    },
                ) { change, dx ->
                    change.consume()
                    val raw = (drag.value + dx).coerceAtLeast(0f)
                    // Past the mark it gets heavier, as if on a spring.
                    val x = if (raw <= mark * 1.3f) raw else mark * 1.3f + (raw - mark * 1.3f) * 0.3f
                    if ((x >= mark) != armed) {
                        armed = x >= mark
                        if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    scope.launch { drag.snapTo(x) }
                }
            }.pointerInput(m, onTap) { detectTapGestures(onTap = onTap?.let { tap -> { tap() } }, onLongPress = { onHold() }) }
    Box(modifier.fillMaxWidth()) {
        // The reply arrow, uncovered as the message moves.
        val shown = (drag.value / mark).coerceIn(0f, 1f)
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(28.dp).graphicsLayer { alpha = shown; scaleX = 0.5f + shown / 2; scaleY = 0.5f + shown / 2 }
                .clip(CircleShape).background(if (shown >= 1f) Pack.foreground else Pack.surface),
            contentAlignment = Alignment.Center,
        ) { BasicText("↩", style = Pack.body(Pack.size.body, if (shown >= 1f) Pack.background else Pack.foreground)) }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(drag.value.roundToInt(), 0) }.padding(bottom = if (balloon) 11.dp else 0.dp),
            horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start,
        ) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .then(replyDrag)
                    // The tail is drawn after the balloon and outside its clip, over the balloon's own outline.
                    .drawWithContent {
                        drawContent()
                        if (balloon) tail(m.fromMe, fill, line, density)
                    }.then(
                        // A photo or video draws its own rounded frame; around it, nothing.
                        if (bare) {
                            Modifier
                        } else {
                            Modifier.clip(shape)
                                .background(fill, shape)
                                .then(if (!m.fromMe && inked) Modifier.border(2.dp, line, shape) else Modifier)
                                .padding(horizontal = 13.dp, vertical = 8.dp)
                        },
                    ),
            ) {
                Column { content(ink) }
            }
        }
    }
}

/** A speech balloon's tail under the balloon's lower corner, as the prototype draws it: filled, with an ink edge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.tail(
    mine: Boolean,
    fill: Color,
    line: Color,
    density: androidx.compose.ui.unit.Density,
) {
    with(density) {
        val w = 13.dp.toPx()
        val h = 13.dp.toPx()
        val inset = 16.dp.toPx()
        val stroke = 2.dp.toPx()
        val y = size.height - stroke
        // For others' balloons the tail hangs from the lower left, straight down then back along a slant; mirrored for the owner's.
        val x0 = if (mine) size.width - inset else inset
        val x1 = if (mine) x0 - w else x0 + w
        // The fill reaches up over the balloon's bottom outline, so no line of it is left across the tail's mouth (Boss,
        // 2026-10-24: a line through the white balloons' tails). The corner's curve still rises a little where the tail
        // starts, so it goes half a stroke into the balloon (same colour there); at the slant it stops a stroke short,
        // where the slant takes the outline over.
        val top = y - stroke / 2
        val step = if (mine) stroke else -stroke
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x0, top)
            lineTo(x0, y + h)
            lineTo(x1, y)
            lineTo(x1 + step, y)
            lineTo(x1 + step, top)
            close()
        }
        drawPath(path, fill)
        if (!mine) {
            drawLine(line, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x0, y + h), stroke)
            drawLine(line, androidx.compose.ui.geometry.Offset(x0, y + h), androidx.compose.ui.geometry.Offset(x1, y), stroke)
        }
    }
}

/** How long an error is said under a conversation before it goes by itself. */
private const val NOTICE_MS = 8_000L
