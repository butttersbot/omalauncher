package dev.omalauncher.messages.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.hideFromAccessibility
import dev.omalauncher.ui.moveTo
import dev.omalauncher.messages.engine.SendMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
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
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.viewinterop.AndroidView
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
internal fun TextButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (inked) 0.dp else 20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (filled && enabled) Pack.foreground else Pack.background, shape)
            .border(2.dp, Pack.foreground, shape)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { BasicText(label, style = Pack.body(Pack.size.body, if (!enabled) Pack.muted else if (filled) Pack.background else Pack.foreground).copy(fontWeight = FontWeight.SemiBold)) }
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
    val choices by dev.omalauncher.messages.engine.ChatChoices.state.collectAsState()
    val conversations = choices.order(book.conversations)
    var revealedRow by remember { mutableStateOf<String?>(null) }
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
        val actionNotice by Messages.notice.collectAsState()
        if (actionNotice.isNotEmpty()) BasicText(actionNotice, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), style = Pack.body(Pack.size.small, Pack.accent))
        if (conversations.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(Pack.heading("Nothing yet"), style = Pack.display(20.sp))
                Spacer(Modifier.height(6.dp))
                BasicText(if (Messages.linked) "Your conversations are on their way from your Mac." else "Conversations appear here as messages arrive in OpenBubbles.", style = Pack.body(Pack.size.body, Pack.muted))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(conversations, key = { it.id }) { c -> ConversationActionsRow(c, c.id == selected, left[c.id]?.draft.orEmpty(), choices, { onOpen(c.id) }, revealedRow, { revealedRow = it },
                Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (Motion.reduced) snap() else Motion.spatial())) }
        }
    }
}

@Composable
internal fun ConversationRow(
    c: Conversation,
    selected: Boolean,
    draft: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    choices: dev.omalauncher.messages.engine.LocalChatChoices = dev.omalauncher.messages.engine.LocalChatChoices(),
    modifier: Modifier = Modifier,
) {
    val last = c.last
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) Pack.surface else Color.Transparent)
            .semantics(mergeDescendants = true) { contentDescription = c.title + if (c.unread > 0) ", ${c.unread} unread" else "" }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Conversation actions")
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
            val badges = listOfNotNull("Pinned".takeIf { c.id in choices.pinned }, "Muted".takeIf { c.id in choices.muted })
            if (badges.isNotEmpty()) BasicText(Pack.caption(badges.joinToString(" · ")), style = Pack.mono(Pack.size.small, Pack.muted))
        }
        if (c.unread > 0) {
            Spacer(Modifier.size(10.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(Pack.accent))
        }
    }
}

/** What each conversation is putting together besides its words: a quoted reply, photos, and link previews put away. */
@Stable
class Pending(private val storage: (String) -> String = { it }) {
    val quotes = mutableStateMapOf<String, String>()
    val replyTo = mutableStateMapOf<String, String>()
    val photos = mutableStateMapOf<String, List<Uri>>()

    fun quote(id: String, text: String, target: String?) {
        quotes[id] = text
        if (target == null) replyTo.remove(id) else replyTo[id] = target.removeSuffix("#a")
        Desk.update(storage(id)) { it.copy(quote = text, replyTo = replyTo[id]) }
    }

    fun clearQuote(id: String) {
        quotes.remove(id); replyTo.remove(id)
        Desk.update(storage(id)) { it.copy(quote = null, replyTo = null) }
    }

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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Thread(
    c: Conversation,
    draft: ComposerState,
    pending: Pending,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onModalChange: (Boolean) -> Unit = {},
    storageId: String = c.id,
    focused: Boolean = false,
    defaultReplyTo: String? = null,
    onJumpTarget: ((String) -> Unit)? = null,
    revealTarget: String? = null,
    onJumpHandled: () -> Unit = {},
    contextView: Boolean = false,
    matchTarget: String? = null,
    onContextEdge: ((Int) -> Unit)? = null,
    navigation: (@Composable () -> Unit)? = null,
    onSent: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Unfolded, a link opens in the side panel beside the chat; on the cover, in the browser.
    val openLink = LocalOpenLink.current
    var stuck by remember(c.id) { mutableStateOf(false) }
    var noOpenBubbles by remember(c.id) { mutableStateOf(false) }
    var held by remember(c.id) { mutableStateOf<Message?>(null) }
    var reactors by remember(c.id) { mutableStateOf<Message?>(null) }
    var editing by remember(c.id) { mutableStateOf<MessageEditDraft?>(null) }
    var history by remember(c.id) { mutableStateOf<Message?>(null) }
    val changes by Messages.pendingChanges.collectAsState()
    var actionNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(held != null) { while (held != null) { actionNow = System.currentTimeMillis(); delay(500) } }
    var searching by remember(c.id) { mutableStateOf(false) }
    var threadRoot by remember(c.id) { mutableStateOf<String?>(null) }
    var restoreIME by remember(c.id) { mutableStateOf(false) }
    var parentFollow by remember(c.id) { mutableStateOf(false) }
    var parentCount by remember(c.id) { mutableIntStateOf(0) }
    val composerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun closeEdit() { editing = null; keyboard?.hide() }
    fun saveEdit(text: String) {
        val draftEdit = editing ?: return
        draftEdit.submitted = true; draftEdit.error = null
        Messages.changeMessage(c.id, draftEdit.original, text) { error ->
            scope.launch { if (error != null) { draftEdit.submitted = false; draftEdit.error = error } }
        }
    }
    androidx.activity.compose.BackHandler(enabled = editing != null) { closeEdit() }
    history?.let { selected ->
        val current = c.messages.firstOrNull { it.renderKey == selected.renderKey } ?: selected
        if (!current.unsent) MessageEditHistory(current) { history = null }
        else LaunchedEffect(current.unsent) { history = null }
    }
    val modal = held != null || reactors != null || threadRoot != null || editing != null || history != null || searching
    DisposableEffect(c.id, modal) { onModalChange(modal); onDispose { onModalChange(false) } }
    val timeWidth = with(LocalDensity.current) { 72.dp.toPx() }
    var timePull by remember(c.id) { mutableFloatStateOf(0f) }
    var timeDragging by remember(c.id) { mutableStateOf(false) }
    val timeReveal by animateFloatAsState(timePull, if (timeDragging || Motion.reduced) snap() else Motion.spatial, label = "message times")
    val onTimeDelta: (Float) -> Unit = { dx -> timeDragging = true; timePull = (timePull - dx).coerceIn(0f, timeWidth) }
    val onTimeEnd: () -> Unit = { timeDragging = false; timePull = 0f }
    // One recorder per composer; leaving a subview or backgrounding cannot keep it recording invisibly.
    val hear = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val mic = remember { Mic(context.applicationContext) { hear.launch(android.Manifest.permission.RECORD_AUDIO) } }
    fun pauseVoice() {
        if (draft.stage is dev.omalauncher.ui.VoiceStage.Recording) draft.stopVoice(mic, System.currentTimeMillis())
        mic.stopPlaying()
    }
    val pulse = remember(c.id) { dev.omalauncher.messages.engine.TypingPulse() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(c.id, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                pulse.stop()?.let { MacLink.typing(c.id, it) }; pauseVoice()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pulse.stop()?.let { MacLink.typing(c.id, it) }; pauseVoice() }
    }
    LaunchedEffect(c.id, threadRoot, searching) {
        if (threadRoot != null || searching) { pulse.stop()?.let { MacLink.typing(c.id, it) }; return@LaunchedEffect }
        snapshotFlow { draft.text }.drop(1).collectLatest { text ->
            if (Messages.linked && Alerts.front) pulse.changed(text, System.currentTimeMillis())?.let { MacLink.typing(c.id, it) }
            delay(5_000)
            pulse.stop()?.let { MacLink.typing(c.id, it) }
        }
    }
    // Where the owner left it: that place, or the latest.
    val left = remember(storageId) { Desk[storageId] }
    val size = c.messages.size
    // A newly opened search window starts on its target, rather than first laying out its newest row.
    val initialTarget = revealTarget?.let { target -> c.messages.indexOfFirst { it.id?.removeSuffix("#a") == target.removeSuffix("#a") } } ?: -1
    val list = rememberLazyListState(if (initialTarget >= 0) initialTarget else if (left.hasPlace) left.indexIn(c.messages) else maxOf(0, size - 1),
        if (initialTarget >= 0) 0 else if (left.hasPlace) left.offset else 0)
    // Whether the owner is following the latest: decided when a scroll ends, not by the list's size, so a composer
    // that grows (a quote, photos, the keyboard) keeps the latest in view instead of leaving it behind.
    var latest by remember(c.id) { mutableStateOf(!left.hasPlace) }
    LaunchedEffect(revealTarget, size) {
        if (revealTarget != null) {
            val index = c.messages.indexOfFirst { it.id?.removeSuffix("#a") == revealTarget.removeSuffix("#a") }
            if (index >= 0) { latest = false; list.scrollToItem(index); onJumpHandled() }
        }
    }
    val edgeCallback by rememberUpdatedState(onContextEdge)
    LaunchedEffect(list, contextView) {
        if (contextView) snapshotFlow {
            if (!list.isScrollInProgress) 0 else if (!list.canScrollBackward) -1 else if (!list.canScrollForward) 1 else 0
        }.collect { edge -> if (edge != 0) edgeCallback?.invoke(edge) }
    }
    val currentSize by rememberUpdatedState(size)
    val currentMessages by rememberUpdatedState(c.messages)
    val previews by MacLink.previews.collectAsState()
    val typing by MacLink.typing.collectAsState()
    LaunchedEffect(c.id) { while (true) { MacLink.expireTyping(); delay(500) } }
    // Only a reader's drag changes follow intent. IME resize and programmatic scrolls can end with
    // canScrollForward temporarily true while layout settles; they must not turn following off.
    var readerScrolling by remember(c.id) { mutableStateOf(false) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) readerScrolling = true
        }
    }
    LaunchedEffect(c.id) {
        snapshotFlow { list.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling && readerScrolling) { latest = !list.canScrollForward; readerScrolling = false }
        }
    }
    LaunchedEffect(c.id) {
        snapshotFlow { list.layoutInfo.viewportSize }.drop(1).collect { if (latest && currentSize > 0) list.scrollToItem(currentSize - 1) }
    }
    // Messages that came since the place was left, or while reading further up.
    var fresh by remember(c.id) { mutableIntStateOf(if (!contextView && left.hasPlace) (size - left.count).coerceAtLeast(0) else 0) }
    // The oldest and newest times shown, to tell earlier history loaded at the top from new messages at the bottom by
    // when they were sent, not by how many more there are: a page of history counted as new ("↓ 36 new", the 0.7.7
    // walk), and a message replaced by the Mac's copy of it isn't one more.
    var newest by remember(c.id) { mutableStateOf(c.messages.lastOrNull()?.time ?: 0L) }
    LaunchedEffect(size) {
        val after = newest
        newest = c.messages.lastOrNull()?.time ?: 0L
        if (contextView) return@LaunchedEffect // Explicit context paging is not a newly arrived message.
        // Earlier history arrived above: the same messages stay in view where the owner is reading.
        // LazyColumn stable keys preserve the visible message during prepends. Do not also shift its index.
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
        snapshotFlow { list.firstVisibleItemIndex to currentSize }.collect { (first, n) -> if (!focused && first <= 5 && n > 0) Messages.loadEarlier(c.id) }
    }
    // The draft, the quote and the place are kept as they change, and once more on leaving, so a closed app loses none.
    fun keep() {
        val place = if (latest) -1 to 0 else list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        if (contextView) {
            Desk.update(storageId) { it.copy(draft = draft.text, quote = pending.quotes[c.id], replyTo = pending.replyTo[c.id]) }
            return
        }
        Desk.update(storageId) { it.copy(draft = draft.text, quote = pending.quotes[c.id], replyTo = pending.replyTo[c.id], index = place.first, offset = place.second, count = if (place.first >= 0) currentSize else 0, anchor = if (place.first >= 0) currentMessages.getOrNull(place.first)?.renderKey else null) }
    }
    LaunchedEffect(c.id) {
        snapshotFlow { Triple(draft.text, pending.quotes[c.id] to pending.replyTo[c.id], latest to (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)) }
            .collectLatest {
                delay(300)
                keep()
            }
    }
    DisposableEffect(c.id) { onDispose { keep() } }
    // The parent owns notification visibility; a focused subview must not clear it on return.
    if (!focused) DisposableEffect(c.id) {
        Alerts.looking = c.id
        Alerts.clear(context, c.id)
        onDispose { if (Alerts.looking == c.id) Alerts.looking = null }
    }
    fun quoteOf(m: Message) = (if (m.fromMe) "You" else m.sender.ifEmpty { c.title }) + ": " + m.text.take(90)
    fun openThread(id: String) {
        if (focused) {
            val index = c.messages.indexOfFirst { it.id?.removeSuffix("#a") == id.removeSuffix("#a") }
            if (index >= 0) { latest = false; scope.launch { list.animateScrollToItem(index) } }
            else onJumpTarget?.invoke(id)
            return
        }
        pauseVoice()
        restoreIME = imeOpen
        parentFollow = latest; parentCount = c.messages.size
        latest = false // Keep the parent reading anchor while replies arrive in the focused thread.
        threadRoot = id.removeSuffix("#a")
    }
    fun closeThread() {
        threadRoot = null
        if (c.messages.size == parentCount) latest = parentFollow
        if (restoreIME) scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            composerFocus.requestFocus()
            keyboard?.show()
        }
    }
    fun closeSearch(sent: Boolean = false) {
        searching = false
        if (sent) { latest = true; scope.launch { if (c.messages.isNotEmpty()) list.scrollToItem(c.messages.lastIndex) } }
        else if (c.messages.size == parentCount) latest = parentFollow
        if (!sent && restoreIME) scope.launch {
            androidx.compose.runtime.withFrameNanos { }; composerFocus.requestFocus(); keyboard?.show()
        }
    }
    if (searching) {
        ConversationSearchPane(c, draft, pending, onDismiss = { closeSearch() }, onSent = { closeSearch(true) })
    }
    if (threadRoot != null) {
        androidx.activity.compose.BackHandler { closeThread() }
        ReplyThreadPane(c, threadRoot!!, onDismiss = ::closeThread)
    }

    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
            if (uris.isNotEmpty()) pending.photos[c.id] = (pending.photos[c.id].orEmpty() + uris).distinct().take(MAX_PHOTOS)
        }
    Column(modifier) {
        ConversationHeader(c, onBack, focused, contextView, if (!focused && Messages.linked) ({
            pauseVoice(); restoreIME = imeOpen
            parentFollow = latest; parentCount = c.messages.size; latest = false
            keyboard?.hide(); searching = true
        }) else null)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Room under the last message for the Latest pill, so it never covers the end of its words (the 0.7.6 walk),
            // but only while the pill is there: kept always, it left a strip of empty paper between the last message and
            // the composer whenever the owner was at the latest (the Fold, 0.8.2). Its height is the pill's own, measured.
            val pillShown = !contextView && !latest && size > 0
            val density = LocalDensity.current
            var pillHeight by remember { mutableIntStateOf(0) }
            val pillRoom by androidx.compose.animation.core.animateDpAsState(
                if (pillShown) with(density) { pillHeight.toDp() } + PILL_GAP * 2 else 0.dp,
                label = "pill room",
            )
            // A short conversation sits down on the composer too, as in Messages, not up under the name with paper below.
            LazyColumn(
                // The pill's room comes off the list itself, so no message runs under the pill (0.8.8 walk, unfolded).
                Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = pillRoom)
                    .messageDrag(c.id, { 0f }, with(density) { 24.dp.toPx() }, allowReply = false,
                        delta = { _, dx -> onTimeDelta(dx) }, end = { _, _ -> onTimeEnd() }),
                state = list,
                verticalArrangement = Arrangement.Bottom,
            ) {
                itemsIndexed(c.messages, key = { _, m -> m.renderKey }) { i, m ->
                    val entering = remember(m.renderKey) { SendMotion.take(m.renderKey) && !Motion.reduced }
                    val entrance = remember(m.renderKey) { Animatable(if (entering) 0f else 1f) }
                    LaunchedEffect(m.renderKey) { entrance.moveTo(1f, MessageMotion.send) }
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
                    val edit = editing?.takeIf { it.original.id == m.id }
                    val pendingChange = m.id?.let { changes[Messages.changeKey(c.id, it)] }
                    if (m.unsent) {
                        BasicText(m.text, Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = "Unsent message: ${m.text}" },
                            style = Pack.body(Pack.size.small, Pack.muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                    } else if (edit != null) {
                        InlineMessageEdit(edit, m, pendingChange, onSave = ::saveEdit, onCancel = ::closeEdit,
                            onReload = { m.id?.let { Messages.reloadChange(c.id, it.removeSuffix("#a")) } })
                    } else {
                    val parts = remember(m.text) { Parts.of(m.text) }
                    parts.forEachIndexed { j, part ->
                        // Photos and videos stand on their own, as iMessage has them: no balloon, no tail (Boss, 2026-10-24).
                        val bare = part is Part.Attachment && m.files.any { visual(it) }
                        val richLink = (part as? Part.Link)?.takeUnless { it.url in m.hiddenLinks }?.let { Parts.openable(it.url) }?.takeUnless { it in m.hiddenLinks }
                            ?.let { url -> linkCardFor(url, m.richLinks.firstOrNull { it.url == url }) }
                        Balloon(
                            m,
                            highlighted = m.id?.removeSuffix("#a") == matchTarget?.removeSuffix("#a") && matchTarget != null,
                            tail = tail && j == parts.lastIndex && !bare,
                            bare = bare,
                            timeOffset = { timeReveal },
                            showTime = j == 0,
                            onTimeDelta = onTimeDelta,
                            onTimeEnd = onTimeEnd,
                            flush = richLink != null && richLink.state != "unavailable",
                            onReply = {
                                pending.quote(c.id, quoteOf(m), m.id)
                            },
                            onHold = { held = m },
                            onTap = tapFor(part, c.id, context, openLink),
                            // A photo stands a little clear of the balloon under it (the 0.8.3 walk: 3 px apart).
                            modifier = Modifier.graphicsLayer {
                                val progress = entrance.value
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (m.fromMe) 1f else 0f, 1f)
                                scaleX = 0.92f + progress * 0.08f; scaleY = scaleX
                                translationY = (1f - progress) * 24.dp.toPx(); alpha = 0.6f + progress * 0.4f
                            }.padding(top = if (first && j == 0) 8.dp else 2.dp, bottom = if (bare) 2.dp else 0.dp),
                        ) { ink ->
                            if (j == 0) (m.quote ?: m.replyTo?.let { "Reply to message" })?.let {
                                QuoteInBalloon(it, ink, m.replyTo?.let { target -> { openThread(target) } })
                            }
                            PartContent(part, ink, m.files, onHold = { held = m }, richLink = richLink)
                        }
                    }
                    Tapbacks(m) { reactors = m }
                        if (m.edited) BasicText("Edited", Modifier.fillMaxWidth().padding(top = 3.dp, end = 6.dp)
                            .semantics { contentDescription = "Edit history for: ${m.text.take(40)}"; role = Role.Button }.clickable { history = m },
                            style = Pack.body(Pack.size.small, Pack.muted).copy(textAlign = if (m.fromMe) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start))
                    }
                    if (pendingChange != null && edit == null && !m.unsent) {
                        val label = if (pendingChange.accepted) "Change requested · tap to reload" else if (pendingChange.unsend) "Undoing send…" else "Saving edit…"
                        BasicText(label, Modifier.fillMaxWidth().clickable { Messages.reloadChange(c.id, pendingChange.id) }.padding(vertical = 4.dp),
                            style = Pack.body(Pack.size.small, Pack.muted))
                    }
                    if (m.failed && next?.failed != true) {
                        // Words can go again from here; photos are picked again.
                        val again = !m.notConfirmed && (Parts.of(m.text).none { it is Part.Attachment } || m.files.any { it.startsWith("local:") })
                        BasicText(
                            Pack.caption(if (m.notConfirmed) "Not confirmed · tap to check" else if (again) "Not sent · tap to try again" else "Not sent"),
                            // Unconfirmed, a tap asks the Mac for the conversation again; it never sends.
                            Modifier.fillMaxWidth().clickable(enabled = again || m.notConfirmed) { if (m.notConfirmed) Messages.read(c.id) else Messages.retry(context, c.id, m) }.padding(top = 4.dp, end = 6.dp),
                            style = Pack.mono(Pack.size.small, Pack.accent).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
                        )
                    }
                    if (m.fromMe && !m.failed && !m.unsent && edit == null && i == c.messages.indexOfLast { it.fromMe && !it.unsent }) {
                        m.delivery?.let { status ->
                            // Apple's place (iOS 26.5 reference): ending where the tail's slant starts, under the balloon
                            // rather than under the tail. A tail's room is taken back, unless something else came in under it.
                            val lastPart = Parts.of(m.text).lastOrNull()
                            val tailRoom = if (inked && tail && !m.edited && m.tapbacks.isEmpty() && !(lastPart is Part.Attachment && m.files.any { visual(it) })) BALLOON_TAIL_ROOM else 0.dp
                            BasicText(Pack.caption(status.label), Modifier.fillMaxWidth().raisedBy(tailRoom).heightIn(min = 16.dp).padding(top = STATUS_GAP, end = STATUS_END),
                                style = Pack.mono(Pack.size.small, Pack.muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
                        }
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
            androidx.activity.compose.BackHandler(enabled = held != null || reactors != null) { held = null; reactors = null }
            reactors?.let { selected ->
                val current = c.messages.firstOrNull { it.renderKey == selected.renderKey } ?: selected
                ReactorMenu(current) { reactors = null }
            }
            held?.let { selected ->
                val m = c.messages.firstOrNull { it.renderKey == selected.renderKey } ?: selected
                val changing = m.id?.let { Messages.changeKey(c.id, it) in changes } == true
                LaunchedEffect(m.unsent) { if (m.unsent) held = null }
                HoldMenu(
                    onReply = {
                        pending.quote(c.id, quoteOf(m), m.id)
                        held = null
                    },
                    onCopy = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        // A photo or video copies itself, so it pastes as the picture (Boss, 2026-10-24: Copy on a photo
                        // copied only "1 Photo"); words copy as words.
                        clipboard.setPrimaryClip(mediaClip(context, m.files) ?: android.content.ClipData.newPlainText("Message", m.text))
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
                    onThread = if (Messages.linked && m.id?.startsWith("c:") == false) ({ held = null; openThread(m.id!!.removeSuffix("#a")) }) else null,
                    onEdit = if (MacLink.messageActionsSupported && !changing && m.changes.canEdit(actionNow)) ({ held = null; editing = MessageEditDraft(m) }) else null,
                    onUnsend = if (MacLink.messageActionsSupported && !changing && m.changes.canUnsend(actionNow)) ({ held = null; Messages.changeMessage(c.id, m, null) }) else null,
                    mine = m.tapbacks.filter { it.startsWith("me:") }.map { it.removePrefix("me:") },
                )
            }
        }
        editing?.takeIf { selected -> c.messages.none { it.id == selected.original.id && !it.unsent } }?.let { edit ->
            InlineMessageEdit(edit, null, changes[Messages.changeKey(c.id, edit.original.id.orEmpty())], onSave = ::saveEdit,
                onCancel = ::closeEdit, onReload = { edit.original.id?.let { Messages.reloadChange(c.id, it.removeSuffix("#a")) } })
        }
        val names = typing.names(c.id, System.currentTimeMillis())
        if (names.isNotEmpty()) BasicText(names.joinToString(", ") + if (names.size == 1) " is typing…" else " are typing…",
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), style = Pack.body(Pack.size.body, Pack.muted))
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
        if (notice.isNotEmpty() && notice != editing?.error && Messages.noticeFor.let { it == null || it == c.id }) {
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
        LaunchedEffect(link) { delay(300); link?.let { MacLink.preview(Parts.openable(it)) } }
        navigation?.invoke()
        SomaComposer(
            draft,
            // Voice messages go through the Mac only: OpenBubbles' share sheet can't send one as a voice message.
            voice = if (Messages.linked && !Messages.samples.value) mic else null,
            onSendVoice = { clip ->
                clip.file?.let { f ->
                    val target = pending.replyTo[c.id] ?: defaultReplyTo
                    if (Messages.replyReady(c.id, quote, target)) {
                        mic.handOver()
                        scope.launch {
                            val sent = withContext(Dispatchers.IO) { Messages.sendVoice(context, c.id, java.io.File(f), quote, target) }
                            if (sent) { if (pending.quotes[c.id] == quote && pending.replyTo[c.id] == target) pending.clearQuote(c.id); onSent() }
                        }
                    } else draft.restoreVoice(clip)
                }
            },
            onSend = { text ->
                if (photos.isEmpty()) {
                    val result = Messages.send(context, c.id, text, quote, pending.unlinked[c.id].orEmpty().map(Parts::openable).toSet(), pending.replyTo[c.id] ?: defaultReplyTo)
                    if (result != Sent.Sent) {
                        // Nothing went out: the words go back in the field, and the quote stays.
                        draft.text = text
                        stuck = result == Sent.NoReplyPath
                    } else {
                        pending.clearQuote(c.id)
                        pending.unlinked.remove(c.id)
                        onSent()
                    }
                } else if (Messages.linked && !Messages.samples.value) {
                    // Through the Mac, photos and words go out together, from here.
                    if (Messages.sendPhotos(context, c.id, photos, text, photos.map { summary(context, listOf(it)) }, quote, pending.replyTo[c.id] ?: defaultReplyTo)) {
                        pending.photos.remove(c.id)
                        pending.clearQuote(c.id)
                        onSent()
                    } else draft.text = text
                } else {
                    // Photos go to OpenBubbles' own chat, which sends them; the chat here shows them waiting there.
                    val handed = listOf(summary(context, photos)) + listOfNotNull(text.ifEmpty { null })
                    if (Messages.samples.value) {
                        Messages.handedOff(c.id, handed, quote)
                        pending.photos.remove(c.id)
                        pending.clearQuote(c.id)
                    } else {
                        scope.launch {
                            val share = withContext(Dispatchers.IO) { Messages.share(context, c.id, photos, text, beside = openLink != null) }
                            if (share == null) {
                                draft.text = text
                                noOpenBubbles = true
                            } else {
                                Messages.handedOff(c.id, handed, quote)
                                pending.photos.remove(c.id)
                                pending.clearQuote(c.id)
                                context.startActivity(share)
                            }
                        }
                    }
                }
            },
            modifier = Modifier.padding(bottom = 4.dp),
            inputModifier = Modifier.focusRequester(composerFocus),
            placeholder = if (focused && !contextView) "Reply" else "iMessage",
            sendColor = Pack.foreground,
            onSendColor = Pack.background,
            onAttach = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            // A GIF (or picture) from the keyboard joins the photos waiting to go, as itself (Boss, 2026-10-27).
            receive = { content ->
                received(context, content) { uris -> pending.photos[c.id] = (pending.photos[c.id].orEmpty() + uris).distinct().take(MAX_PHOTOS) }
            },
            attached = photos.isNotEmpty(),
            sendsElsewhere = photos.isNotEmpty(),
            above = quote?.let { q -> { QuoteBar(q, native = Messages.linked && pending.replyTo[c.id] != null) { pending.clearQuote(c.id) } } },
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
                            metadata = link?.let { Parts.openable(it) }?.let { url -> linkCardFor(url, previews[url], settleMs = DRAFT_SETTLE_MS) },
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

/**
 * The first photo or video of [files] as a clip of the file itself, when the phone has it: one picked here (its own
 * uri) or one fetched from the Mac and kept (shared through the app's FileProvider). Null when there is none yet.
 */
private fun mediaClip(
    context: android.content.Context,
    files: List<String>,
): android.content.ClipData? {
    val f = files.firstOrNull { visual(it) } ?: return null
    val id = f.substringBefore('|')
    val mime = f.substringAfter('|')
    val uri =
        if (id.startsWith("local:")) {
            Uri.parse(id.removePrefix("local:"))
        } else {
            val kept = MacLink.cached(context, id, picture = mime.startsWith("image/"))
            if (!kept.isFile || kept.length() == 0L) return null
            androidx.core.content.FileProvider.getUriForFile(context, "dev.omalauncher.messages.photos", kept)
        }
    return android.content.ClipData(if (mime.startsWith("video/")) "Video" else "Photo", arrayOf(mime), android.content.ClipData.Item(uri))
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
    richLink: dev.omalauncher.messages.engine.RichLink? = null,
) {
    when (part) {
        is Part.Words -> BasicText(part.text, style = Pack.body(Pack.size.item, ink))
        is Part.Link -> if (richLink != null && richLink.state != "unavailable") RichLinkCard(richLink, ink) else Card(part.host, part.path.ifEmpty { null }, "↗", ink)
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
                Box(Modifier.combinedClickable(onLongClick = onHold, onDoubleClick = onHold) { openMedia(context, files.first()) }.semantics { contentDescription = part.summary + ", tap to open" }) {
                    Card(part.summary, "Tap to open", "▣", ink)
                }
            } else {
                Card(part.summary, if (Messages.linked) null else "Open in OpenBubbles", if (part.kind == "Video") "▶" else "▣", ink)
            }
    }
}

/**
 * Pictures from the Mac, fetched once and kept in the cache: one alone fits a balloon's width, several sit side by side
 * and scroll. A tap opens the picture full screen over the chat.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Photos(
    files: List<String>,
    onHold: () -> Unit = {},
) {
    val one = files.size == 1
    val strip = rememberScrollState()
    Row(if (one) Modifier else Modifier.horizontalScroll(strip), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            val id = f.substringBefore('|')
            val context = LocalContext.current
            // A photo picked here and still on its way: the phone's own copy. Otherwise the Mac's, fetched once.
            val local = id.takeIf { it.startsWith("local:") }?.removePrefix("local:")?.let { Uri.parse(it) }
            // A GIF moves, as in iMessage (Boss, 2026-10-27): the Mac's own file, not its still JPEG.
            val gif = f.substringAfter('|') == "image/gif"
            val file by produceState<java.io.File?>(null, id) { if (local == null) value = withContext(Dispatchers.IO) { MacLink.file(context, id, picture = !gif) } }
            val moving by produceState<android.graphics.drawable.Drawable?>(null, file, local) {
                if (gif) value = withContext(Dispatchers.IO) { runCatching { moving(context, local, file) }.getOrNull() }
            }
            DisposableEffect(moving) { onDispose { (moving as? android.graphics.drawable.AnimatedImageDrawable)?.stop() } }
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
            val ratio = moving?.let { it.intrinsicHeight.toFloat() / it.intrinsicWidth.coerceAtLeast(1) } ?: picture?.let { it.height.toFloat() / it.width.coerceAtLeast(1) } ?: 0.75f
            val size = if (one) Modifier.width(240.dp).height((240 * ratio).coerceIn(120f, 320f).dp) else Modifier.size(132.dp)
            Box(
                // A hold is the balloon's (tapbacks, reply): the picture's own tap would otherwise take it (Boss, 2026-10-23).
                size.clip(shape).background(Pack.surface).combinedClickable(onLongClick = onHold, onDoubleClick = onHold) { file?.let { PhotoViewer.show(it, moving = gif) } }
                    .semantics { contentDescription = "Photo" },
                contentAlignment = Alignment.Center,
            ) {
                val p = picture
                val m = moving
                if (m != null) {
                    // Not clickable itself, so the tap and the hold stay the frame's.
                    AndroidView(
                        { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
                        Modifier.fillMaxSize(),
                        update = { v ->
                            if (v.drawable !== m) {
                                v.setImageDrawable(m)
                                (m as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                            }
                        },
                    )
                } else if (p != null) {
                    Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    BasicText(if (file == null) "Photo" else "", style = Pack.body(Pack.size.small, Pack.muted))
                }
            }
        }
    }
}

/** The GIF [local] (picked or from the keyboard) or [file] (the Mac's), as a drawable that moves; null when neither is here. */
internal fun moving(
    context: android.content.Context,
    local: Uri?,
    file: java.io.File?,
): android.graphics.drawable.Drawable? {
    val source =
        when {
            local != null -> android.graphics.ImageDecoder.createSource(context.contentResolver, local)
            file != null -> android.graphics.ImageDecoder.createSource(file)
            else -> return null
        }
    return android.graphics.ImageDecoder.decodeDrawable(source)
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
                        .combinedClickable(onLongClick = onHold, onDoubleClick = onHold) { open() }.semantics { contentDescription = "Video, tap to play" },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        BasicText("▶", style = Pack.body(Pack.size.item, Color.White))
                    }
                }
            } else {
                Box(Modifier.combinedClickable(onLongClick = onHold, onDoubleClick = onHold) { open() }.semantics { contentDescription = "Video, tap to play" }) {
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
    Box(Modifier.combinedClickable(onLongClick = onHold, onDoubleClick = onHold) { toggle() }.semantics { contentDescription = "Voice message, $state" }) {
        Card("Voice message", state, if (player[0] != null) "■" else "▶", ink)
    }
}

/**
 * Opens the Mac's attachment [file] ("id|mime") from outside its balloon (the unfolded table): fetched if it isn't
 * kept yet, then a picture full screen over the app, a video in the player.
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
                picture -> PhotoViewer.show(f)
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
internal fun decode(
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
    onOpen: (() -> Unit)? = null,
) {
    Row(Modifier.padding(bottom = 6.dp).background(ink.copy(alpha = 0.12f)).then(if (onOpen != null) Modifier.semantics { role = Role.Button; contentDescription = "Open reply context" }.clickable(onClick = onOpen) else Modifier)) {
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
    metadata: dev.omalauncher.messages.engine.RichLink? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (photos.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                photos.forEach { uri -> Tile("Remove photo", { onDropPhoto(uri) }) { Thumb(uri) } }
            }
            BasicText(if (Messages.linked) "Photos go out with the words" else "Photos open in OpenBubbles' chat, ready to send", Modifier.padding(start = 4.dp), style = Pack.body(Pack.size.small, Pack.muted))
        }
        if (link != null) {
            if (metadata != null && metadata.state != "unavailable") {
                RichLinkCard(metadata, Pack.foreground, draft = true, onRemove = { onDropLink(link) })
            } else Tile("Remove link preview", { onDropLink(link) }, wide = true) {
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
    tall: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = composerShape(12.dp)
    Box(
        (if (wide) Modifier.fillMaxWidth().height(if (tall) 122.dp else 60.dp) else Modifier.size(72.dp))
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

/**
 * The pictures in [content], from the keyboard (a GIF from Soma's), copied into the cache at once (the keyboard's
 * permission to read them is brief) and handed to [add] as the app's own; the rest goes back to the field.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun received(
    context: android.content.Context,
    content: androidx.compose.foundation.content.TransferableContent,
    add: (List<Uri>) -> Unit,
): androidx.compose.foundation.content.TransferableContent? {
    if (!content.hasMediaType(androidx.compose.foundation.content.MediaType.Image)) return content
    val clip = content.clipEntry.clipData
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val kept = mutableListOf<Uri>()
    val rest =
        content.consume { item ->
            val uri = item.uri ?: return@consume false
            val type = context.contentResolver.getType(uri) ?: clip.description.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0) ?: "image/gif"
            if (!type.startsWith("image/")) return@consume false
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "gif"
            val f = java.io.File(dir, "keyboard-" + java.util.UUID.randomUUID() + "." + ext)
            runCatching { context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } } }
                .onFailure { f.delete() }
                .isSuccess
                .also { ok -> if (ok) kept += androidx.core.content.FileProvider.getUriForFile(context, "dev.omalauncher.messages.photos", f) }
        }
    if (kept.isNotEmpty()) add(kept)
    return rest
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
    native: Boolean,
    onDrop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 42.dp).background(Pack.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(Pack.accent))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BasicText(Pack.caption(if (native) "Replying" else "Quoted reply"), style = Pack.mono(Pack.size.small, Pack.accent))
            BasicText(quote, style = Pack.body(Pack.size.body), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(40.dp).semantics { contentDescription = "Don't reply to this"; role = Role.Button }.clickable(onClick = onDrop),
            contentAlignment = Alignment.Center,
        ) { BasicText("✕", style = Pack.body(Pack.size.body, Pack.muted)) }
    }
}

/** Press and hold: Reply and Copy, for anyone who'd rather tap than drag. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HoldMenu(
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
    onTapback: ((String) -> Unit)? = null,
    mine: List<String> = emptyList(),
    onThread: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onUnsend: (() -> Unit)? = null,
) {
    MessageOverlay(onDismiss, enterMillis = MessageMotion.MENU_ENTER_MS) {
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
        Column(Modifier.width(300.dp).clip(shape).background(Pack.background, shape).border(2.dp, Pack.foreground, shape).padding(4.dp)) {
            // Remove only for a hand-off the owner didn't finish in OpenBubbles: nothing sent is ever taken back from here.
            (listOf("Reply" to onReply, "Copy" to onCopy) + listOfNotNull(onThread?.let { "Show replies" to it }, onEdit?.let { "Edit" to it }, onUnsend?.let { "Undo Send" to it }, onRemove?.let { "Remove" to it })).forEach { (label, act) ->
                Box(Modifier.fillMaxWidth().semantics { role = Role.Button }.clickable(onClick = act).padding(horizontal = 18.dp, vertical = 12.dp)) {
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
private fun Tapbacks(m: Message, onOpen: () -> Unit) {
    AnimatedContent(targetState = m.tapbacks, label = "tapback change", transitionSpec = {
        if (Motion.reduced) EnterTransition.None togetherWith ExitTransition.None
        else (fadeIn(Motion.fade()) + scaleIn(Motion.spatial, initialScale = 0.8f)) togetherWith fadeOut(Motion.fade())
    }) { reactions ->
        if (reactions.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, start = 10.dp, end = 10.dp),
            horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start,
        ) {
            reactions.forEach { reaction ->
                val kind = reaction.removePrefix("me:")
                val glyph = TAPBACKS.firstOrNull { it.first == kind }?.second ?: kind
                val mine = reaction.startsWith("me:")
                Box(Modifier.padding(end = 4.dp).clip(CircleShape).background(Pack.surface)
                    .then(if (mine) Modifier.border(1.5.dp, Pack.foreground, CircleShape) else Modifier)
                    .semantics { contentDescription = "Reactions on: " + m.text.take(40); role = Role.Button }.clickable(onClick = onOpen)
                    .padding(horizontal = 6.dp, vertical = 2.dp)) { BasicText(glyph, style = Pack.body(Pack.size.small)) }
            }
        } else Spacer(Modifier.height(0.dp))
    }
}

@Composable private fun ReactorMenu(m: Message, onDismiss: () -> Unit) {
    MessageOverlay(onDismiss) {
        Column(Modifier.widthIn(min = 220.dp, max = 320.dp).background(Pack.background, composerShape(14.dp))
            .border(2.dp, Pack.foreground, composerShape(14.dp)).pointerInput(Unit) { detectTapGestures(onTap = {}) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Reactions", Modifier.semantics { contentDescription = "People who reacted" }, style = Pack.body(Pack.size.item).copy(fontWeight = FontWeight.SemiBold))
            val labels = if (m.reactions.isNotEmpty()) m.reactions.map { (it.name ?: if (it.fromMe) "You" else it.sender.ifEmpty { "Someone" }) to it.kind }
                else m.tapbacks.map { (if (it.startsWith("me:")) "You" else "Someone") to it.removePrefix("me:") }
            labels.forEach { (name, kind) ->
                BasicText((TAPBACKS.firstOrNull { it.first == kind }?.second ?: kind) + "  " + name, Modifier.semantics { contentDescription = "Reaction from $name: $kind" }, style = Pack.body(Pack.size.body))
            }
            BasicText("Done", Modifier.semantics { role = Role.Button }.clickable(onClick = onDismiss).padding(vertical = 8.dp), style = Pack.body(Pack.size.item))
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
    highlighted: Boolean = false,
    flush: Boolean = false,
    timeOffset: () -> Float = { 0f },
    showTime: Boolean = true,
    onTimeDelta: (Float) -> Unit = {},
    onTimeEnd: () -> Unit = {},
    onReply: () -> Unit,
    onHold: () -> Unit,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (ink: Color) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pull by remember { mutableFloatStateOf(0f) }
    var pulling by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var rootLeft by remember { mutableFloatStateOf(0f) }
    val drag by animateFloatAsState(pull, if (pulling || Motion.reduced) snap() else Motion.spatial, label = "reply drag")
    val currentReply by rememberUpdatedState(onReply)
    val currentHold by rememberUpdatedState(onHold)
    val currentTap by rememberUpdatedState(onTap)
    val mark = with(density) { REPLY_AT.toPx() }
    val corner = 20.dp
    val balloon = inked && tail
    val tip = if (tail && !inked) 5.dp else corner
    val shape = if (m.fromMe) RoundedCornerShape(corner, corner, tip, corner) else RoundedCornerShape(corner, corner, corner, tip)
    val fill = if (m.fromMe) Pack.foreground else if (inked) Pack.background else Pack.surface
    val ink = if (m.fromMe) Pack.background else Pack.foreground
    val line = if (highlighted) Pack.accent else Pack.foreground
    // Only the balloon itself takes the reply drag; a drag anywhere else in the conversation is the way back.
    val replyDrag = Modifier.onGloballyPositioned { rootLeft = it.boundsInRoot().left }
        .messageDrag(m.renderKey, { rootLeft }, with(density) { 24.dp.toPx() }, delta = { times, dx ->
            if (times) onTimeDelta(dx) else {
                pulling = true
                val raw = (pull + dx).coerceAtLeast(0f)
                pull = if (raw <= mark * 1.3f) raw else mark * 1.3f + (raw - mark * 1.3f) * 0.3f
                if ((pull >= mark) != armed) {
                    armed = pull >= mark
                    if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }, end = { times, completed ->
            if (times) onTimeEnd() else {
                if (completed && armed) currentReply()
                armed = false; pulling = false; pull = 0f
            }
        }).pointerInput(m.renderKey) {
            detectTapGestures(onTap = { currentTap?.invoke() }, onDoubleTap = { currentHold() }, onLongPress = { currentHold() })
        }
    Box(modifier.fillMaxWidth()) {
        // The reply arrow, uncovered as the message moves.
        val shown = (drag / mark).coerceIn(0f, 1f)
        if (showTime) {
            val context = LocalContext.current
            val stamp = remember(m.time) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(m.time)) }
            BasicText(stamp, Modifier.align(Alignment.CenterEnd).semantics { contentDescription = "Message time, $stamp"; if (timeOffset() <= 0f) hideFromAccessibility() }.graphicsLayer { alpha = (timeOffset() / with(density) { 72.dp.toPx() }).coerceIn(0f, 1f) },
                style = Pack.mono(Pack.size.small, Pack.muted))
        }
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(28.dp).graphicsLayer { alpha = shown; scaleX = 0.5f + shown / 2; scaleY = 0.5f + shown / 2 }
                .clip(CircleShape).background(if (shown >= 1f) Pack.foreground else Pack.surface),
            contentAlignment = Alignment.Center,
        ) { BasicText("↩", style = Pack.body(Pack.size.body, if (shown >= 1f) Pack.background else Pack.foreground)) }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset((drag - timeOffset()).roundToInt(), 0) }.padding(bottom = if (balloon) BALLOON_TAIL_ROOM else 0.dp),
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
                                .then(if (highlighted || (!m.fromMe && inked)) Modifier.border(if (highlighted) 3.dp else 2.dp, line, shape) else Modifier)
                                .padding(horizontal = if (flush) 0.dp else 13.dp, vertical = if (flush) 0.dp else 8.dp)
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

/** A draft's link is read once it stops changing: part-way through typing, its number is some other post's. */
private const val DRAFT_SETTLE_MS = 800L

/** Room kept under a balloon for its tail. */
private val BALLOON_TAIL_ROOM = 11.dp

/** A status line's distance under the balloon, and its end's from the balloon's right edge: clear of the tail's 29 dp. */
private val STATUS_GAP = 5.dp
private val STATUS_END = 31.dp

/** Laid out [by] higher, taking that much less room in the column. */
private fun Modifier.raisedBy(by: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = by.roundToPx()
    layout(placeable.width, (placeable.height - up).coerceAtLeast(0)) { placeable.place(0, -up) }
}

/** How long an error is said under a conversation before it goes by itself. */
private const val NOTICE_MS = 8_000L

@Composable internal fun ConversationHeader(c: Conversation, onBack: (() -> Unit)?, focused: Boolean = true,
                                           contextView: Boolean = false, onSearch: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).semantics { contentDescription = if (focused) "Back to conversation" else "Back to conversations"; role = Role.Button }.clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { BasicText("‹", style = Pack.body(30.sp)) }
            }
            Avatar(c.title, 32)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                BasicText(c.title, style = Pack.body(Pack.size.item).copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Nothing to say under the name when it all comes from the Mac (Boss, 0.8.7).
                val caption = if (contextView) "Search context" else if (focused) "Replies" else if (Messages.linked) null else "iMessage · through OpenBubbles"
                if (caption != null) BasicText(Pack.caption(caption), style = Pack.mono(Pack.size.small, Pack.muted))
            }
            if (onSearch != null) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).semantics { contentDescription = "Search"; role = Role.Button }.clickable(onClick = onSearch),
                    contentAlignment = Alignment.Center,
                ) { SearchIcon(Pack.foreground) }
            }
        }
}

/** A magnifying glass, drawn in the ink of the header's back mark. */
@Composable private fun SearchIcon(ink: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val radius = size.width * 0.32f
        val center = androidx.compose.ui.geometry.Offset(radius + stroke / 2, radius + stroke / 2)
        val edge = radius * 0.7071f
        drawCircle(ink, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(ink, center + androidx.compose.ui.geometry.Offset(edge, edge), androidx.compose.ui.geometry.Offset(size.width - stroke / 2, size.height - stroke / 2),
            stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
