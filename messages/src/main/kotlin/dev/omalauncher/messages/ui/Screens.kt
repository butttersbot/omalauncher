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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.composerShape
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
private val inked @Composable get() = Pack.look.line > 0.dp

@Composable
private fun Avatar(
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
        if (conversations.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(Pack.heading("Nothing yet"), style = Pack.display(20.sp))
                Spacer(Modifier.height(6.dp))
                BasicText("Conversations appear here as messages arrive in OpenBubbles.", style = Pack.body(Pack.size.body, Pack.muted))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(conversations, key = { it.id }) { c -> ConversationRow(c, c.id == selected) { onOpen(c.id) } }
        }
    }
}

@Composable
private fun ConversationRow(
    c: Conversation,
    selected: Boolean,
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
            val who = if (last != null && c.isGroup && !last.fromMe) last.sender + ": " else ""
            BasicText(
                who + (last?.text ?: ""),
                style = Pack.body(Pack.size.body, if (c.unread > 0) Pack.foreground else Pack.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.unread > 0) {
            Spacer(Modifier.size(10.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(Pack.accent))
        }
    }
}

/**
 * A conversation: its messages as balloons, and the shared composer. Replies go through OpenBubbles' own Reply
 * action; until a conversation has had a notification since the app started, that action isn't known, so the app
 * says so and offers to open the conversation in OpenBubbles.
 *
 * Drag a message right to reply: it follows the finger, and past the mark it becomes the quote above the field.
 * Press and hold for Reply and Copy. [quotes] holds each conversation's quote, like its draft.
 */
@Composable
fun Thread(
    c: Conversation,
    draft: ComposerState,
    quotes: MutableMap<String, String>,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var stuck by remember(c.id) { mutableStateOf(false) }
    var held by remember(c.id) { mutableStateOf<Message?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(c.id, c.messages.size) { if (c.messages.isNotEmpty()) list.scrollToItem(c.messages.size - 1) }
    fun quoteOf(m: Message) = (if (m.fromMe) "You" else m.sender.ifEmpty { c.title }) + ": " + m.text.take(90)
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
                BasicText(Pack.caption("iMessage · through OpenBubbles"), style = Pack.mono(Pack.size.small, Pack.muted))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = list) {
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
                    Balloon(m, tail, onReply = { quotes[c.id] = quoteOf(m) }, onHold = { held = m }, Modifier.padding(top = if (first) 8.dp else 2.dp))
                }
            }
            held?.let { m ->
                HoldMenu(
                    onReply = {
                        quotes[c.id] = quoteOf(m)
                        held = null
                    },
                    onCopy = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", m.text))
                        held = null
                    },
                    onDismiss = { held = null },
                )
            }
        }
        if (stuck || !Messages.canReply(c.id)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                BasicText(
                    "Replies go out from here once ${c.title.substringBefore(' ')} writes again. Until then, answer in OpenBubbles.",
                    style = Pack.body(Pack.size.small, Pack.muted),
                )
                Spacer(Modifier.height(6.dp))
                TextButton("Open in OpenBubbles", filled = false) { Messages.openInOpenBubbles(context, c.id) }
            }
        }
        val quote = quotes[c.id]
        SomaComposer(
            draft,
            onSend = { text ->
                if (Messages.send(context, c.id, text, quote) == Sent.NoReplyPath) {
                    // Nothing went out: the words go back in the field, and the quote stays.
                    draft.text = text
                    stuck = true
                } else {
                    quotes.remove(c.id)
                }
            },
            Modifier.padding(bottom = 4.dp),
            placeholder = "iMessage",
            sendColor = Pack.foreground,
            onSendColor = Pack.background,
            above = quote?.let { q -> { QuoteBar(q) { quotes.remove(c.id) } } },
        )
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
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Pack.foreground.copy(alpha = 0.18f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        val shape = composerShape(14.dp)
        Row(Modifier.clip(shape).background(Pack.background, shape).border(2.dp, Pack.foreground, shape).padding(4.dp)) {
            listOf("Reply" to onReply, "Copy" to onCopy).forEach { (label, act) ->
                Box(Modifier.semantics { role = Role.Button }.clickable(onClick = act).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    BasicText(label, style = Pack.body(Pack.size.item).copy(fontWeight = FontWeight.SemiBold))
                }
            }
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
    onReply: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
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
            }.pointerInput(m) { detectTapGestures(onLongPress = { onHold() }) }
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
                    }.clip(shape)
                    .background(fill, shape)
                    .then(if (!m.fromMe && inked) Modifier.border(2.dp, line, shape) else Modifier)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Column {
                    m.quote?.let { q ->
                        Row(Modifier.padding(bottom = 6.dp).background(ink.copy(alpha = 0.12f))) {
                            Box(Modifier.width(3.dp).height(32.dp).background(ink.copy(alpha = 0.6f)))
                            BasicText(q, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = Pack.body(Pack.size.small, ink.copy(alpha = 0.85f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    BasicText(m.text, style = Pack.body(Pack.size.item, ink))
                }
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
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x0, y)
            lineTo(x0, y + h)
            lineTo(x1, y)
            close()
        }
        drawPath(path, fill)
        if (!mine) {
            drawLine(line, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x0, y + h), stroke)
            drawLine(line, androidx.compose.ui.geometry.Offset(x0, y + h), androidx.compose.ui.geometry.Offset(x1, y), stroke)
        }
    }
}
