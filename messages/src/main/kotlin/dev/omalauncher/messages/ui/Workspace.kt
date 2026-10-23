package dev.omalauncher.messages.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.messages.engine.Conversation
import dev.omalauncher.messages.engine.ConversationBook
import dev.omalauncher.messages.engine.Desk
import dev.omalauncher.messages.engine.Messages
import dev.omalauncher.messages.engine.Part
import dev.omalauncher.messages.engine.Parts
import dev.omalauncher.messages.engine.Shared
import dev.omalauncher.messages.engine.shared
import dev.omalauncher.ui.ComposerState
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.composerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Unfolded: the Side Table workspace (PLAN.md sections 8 and 9). A fixed rail of conversations on the left, the
 * conversation beside it, and the utility panel on the right page: what was shared, or whatever was opened from it,
 * so a link never takes the owner out of the chat. One grip at the panel's edge has three resting places: a strip at
 * the right edge, beside the chat, and full screen up to the rail, so other chats stay a tap away.
 */

/** Opens a link beside the conversation; null on the cover, where links go to the browser. */
val LocalOpenLink = compositionLocalOf<((String) -> Unit)?> { null }

/** Where the panel rests. */
enum class Side { Strip, Beside, Full }

private val RAIL = 76.dp
private val STRIP = 56.dp

/** The narrowest the conversation gets beside the panel. */
private val CHAT_MIN = 360.dp

@Composable
fun Workspace(
    book: ConversationBook,
    shown: String?,
    samples: Boolean,
    onPick: (String) -> Unit,
    draft: (String) -> ComposerState,
    pending: Pending,
) {
    var side by rememberSaveable { mutableStateOf(Side.Beside) }
    var all by rememberSaveable { mutableStateOf(false) }
    // All opened a folded panel, and folds it again on closing unless something was opened meanwhile.
    var allOpenedPanel by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val conversation = shown?.let { book[it] }
    val context = LocalContext.current

    fun open(url: String) {
        viewing = url
        if (side == Side.Strip) side = Side.Beside
        allOpenedPanel = false
    }

    fun showAll(on: Boolean) {
        all = on
        if (on && side == Side.Strip) {
            side = Side.Beside
            allOpenedPanel = true
        } else if (!on && allOpenedPanel) {
            side = Side.Strip
            allOpenedPanel = false
        }
    }

    // Back steps out one layer at a time: full screen, then All, then what was opened.
    BackHandler(enabled = side == Side.Full || all || viewing != null) {
        when {
            side == Side.Full -> side = Side.Beside
            all -> showAll(false)
            else -> viewing = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val beside = maxOf(CHAT_MIN, (width - RAIL) * 0.5f)
        fun edgeOf(s: Side): Dp =
            when (s) {
                Side.Strip -> width - STRIP
                Side.Beside -> RAIL + beside
                Side.Full -> RAIL
            }
        val density = LocalDensity.current
        val edge = remember { Animatable(with(density) { edgeOf(side).toPx() }) }
        val scope = rememberCoroutineScope()
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(side, width) { if (!dragging) edge.animateTo(with(density) { edgeOf(side).toPx() }, Motion.spatial) }
        val edgeDp = with(density) { edge.value.toDp() }

        // The conversation, as wide as the panel leaves it, but never narrower than beside it (full screen covers it).
        Box(Modifier.offset(x = RAIL).width(maxOf(edgeDp - RAIL, beside)).fillMaxHeight()) {
            if (conversation != null) {
                CompositionLocalProvider(LocalOpenLink provides ::open) {
                    key(conversation.id) { Thread(conversation, draft(conversation.id), pending, onBack = null, Modifier.fillMaxSize()) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(Pack.heading("Nothing yet"), style = Pack.display(20.sp))
                    BasicText("Conversations appear as messages arrive in OpenBubbles.", style = Pack.body(Pack.size.body, Pack.muted))
                }
            }
        }

        // All: the whole list over the conversation, from the rail.
        val allIn by animateFloatAsState(if (all) 1f else 0f, Motion.spatial, label = "all")
        if (allIn > 0.001f) {
            Box(
                Modifier
                    .offset { IntOffset((-beside.toPx() * (1 - allIn) + RAIL.toPx()).roundToInt(), 0) }
                    .width(beside)
                    .fillMaxHeight()
                    .shadow(if (inked) 0.dp else 14.dp)
                    .background(Pack.paper)
                    .then(if (inked) Modifier.border(2.dp, Pack.foreground) else Modifier),
            ) {
                Inbox(book, shown, samples, { id -> onPick(id); showAll(false) }, Modifier.fillMaxSize())
            }
        }

        // The panel, from its edge to the right side of the screen, set off by a rule at its edge, not a box.
        val rule = if (inked) Pack.foreground else Pack.border
        val ruleWidth = if (inked) 2.dp else 1.dp
        Box(
            Modifier
                .offset { IntOffset(edge.value.roundToInt(), 0) }
                .width(width - edgeDp)
                .fillMaxHeight()
                .background(Pack.paper)
                .drawBehind { drawRect(rule, size = androidx.compose.ui.geometry.Size(ruleWidth.toPx(), size.height)) },
        ) {
            val items =
                if (all) {
                    shared(*book.conversations.toTypedArray())
                } else {
                    conversation?.let { shared(it) }.orEmpty()
                }
            val url = viewing
            when {
                edgeDp > width - STRIP - 8.dp && !dragging -> StripOf(items) { s -> openShared(s, ::open, onPick, context) }
                url != null && !all -> Viewer(url, onBack = { viewing = null })
                else -> Table(items, all, conversation, ::open) { id -> onPick(id); if (all) showAll(false) }
            }
        }

        Rail(book, shown, all, onAll = { showAll(!all) }, onPick = { id -> onPick(id); if (all) showAll(false) }, Modifier.width(RAIL).fillMaxHeight())
        // The grip, on the panel's edge, over the rail when full screen: drag between the three places, or tap to fold and unfold.
        Box(
            Modifier
                .offset { IntOffset((edge.value - 9.dp.toPx()).roundToInt(), ((maxHeight / 2) - 36.dp).roundToPx()) }
                .size(18.dp, 72.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = if (side == Side.Strip) "Unfold the side panel" else "Fold the side panel"
                }.pointerInput(width) {
                    detectTapGestures {
                        side = if (side == Side.Beside) Side.Strip else Side.Beside
                        allOpenedPanel = false
                    }
                }.pointerInput(width) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            val at = edge.value
                            side = Side.entries.minBy { abs(edgeOf(it).toPx() - at) }
                            allOpenedPanel = false
                            scope.launch { edge.animateTo(edgeOf(side).toPx(), Motion.spatial) }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { edge.animateTo(edgeOf(side).toPx(), Motion.spatial) }
                        },
                    ) { change, dx ->
                        change.consume()
                        // Full screen stops at the rail; folded, the strip still shows.
                        scope.launch { edge.snapTo((edge.value + dx).coerceIn(RAIL.toPx(), (width - STRIP).toPx())) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(5.dp, if (dragging) 60.dp else 44.dp).clip(composerShape(3.dp)).background(Pack.foreground.copy(alpha = if (dragging) 0.85f else 0.32f))) }

    }
}

/** Opens [s]: a link in the panel; an attachment or a previewed page in its chat in OpenBubbles. */
private fun openShared(
    s: Shared,
    open: (String) -> Unit,
    pick: (String) -> Unit,
    context: android.content.Context,
) {
    when (val p = s.part) {
        is Part.Link -> {
            // Opened first, so closing All keeps the panel open for it.
            open(Parts.openable(p.url))
            pick(s.conversation)
        }
        else -> Messages.openInOpenBubbles(context, s.conversation)
    }
}

/** The rail: Soma's mark, All, and the conversations' faces, which fade while All lists everyone in full. */
@Composable
private fun Rail(
    book: ConversationBook,
    shown: String?,
    all: Boolean,
    onAll: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val left by Desk.left.collectAsState()
    val faces by animateFloatAsState(if (all) 0f else 1f, Motion.spatial, label = "faces")
    Column(modifier.background(Pack.paper).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("S", Modifier.padding(top = 2.dp, bottom = 6.dp), style = Pack.display(30.sp))
        val shape = composerShape(10.dp)
        Column(
            Modifier
                .width(60.dp)
                .clip(shape)
                .background(if (all) Pack.background else Color.Transparent, shape)
                .then(if (all) Modifier.border(2.dp, Pack.foreground, shape) else Modifier)
                .semantics { role = Role.Button; contentDescription = if (all) "Close the list" else "All conversations" }
                .clickable(onClick = onAll)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("☰", style = Pack.body(18.sp, if (all) Pack.foreground else Pack.muted))
            BasicText(Pack.caption("All"), style = Pack.mono(9.sp, if (all) Pack.foreground else Pack.muted))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).alpha(faces), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(book.conversations, key = { it.id }) { c ->
                Box(
                    Modifier
                        .padding(3.dp)
                        .semantics { role = Role.Button; contentDescription = c.title + if (c.unread > 0) ", ${c.unread} unread" else "" }
                        .clickable(enabled = !all) { onPick(c.id) },
                ) {
                    Box(if (c.id == shown) Modifier.border(2.dp, Pack.foreground, CircleShape).padding(3.dp) else Modifier.padding(3.dp)) { Avatar(c.title, 42) }
                    if (c.unread > 0) Box(Modifier.align(Alignment.TopEnd).size(12.dp).clip(CircleShape).background(Pack.accent))
                    // A waiting draft: a small dot at the bottom corner.
                    if (left[c.id]?.draft?.isNotBlank() == true) {
                        Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Pack.paper), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(Pack.accent))
                        }
                    }
                }
            }
        }
    }
}

/** The shared table: links, then photos and files, from this conversation, or from every chat while All is open. */
@Composable
private fun Table(
    items: List<Shared>,
    across: Boolean,
    conversation: Conversation?,
    open: (String) -> Unit,
    pick: (String) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(start = 26.dp, end = 22.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 24.dp)) {
        item {
            BasicText(Pack.heading(if (across) "Across chats" else "Shared"), style = Pack.display(28.sp))
            BasicText(
                Pack.caption(if (across) "Everything shared lately" else "With ${conversation?.title ?: "nobody yet"}"),
                Modifier.padding(top = 5.dp, bottom = 4.dp),
                style = Pack.mono(10.sp, Pack.muted),
            )
        }
        val links = items.filter { it.part is Part.Link || it.part is Part.Website }
        val files = items.filter { it.part is Part.Attachment }
        if (items.isEmpty()) {
            item {
                BasicText(
                    "Nothing shared yet. Links and photos sent in this chat gather here, and open beside it.",
                    Modifier.padding(top = 16.dp),
                    style = Pack.body(Pack.size.body, Pack.muted),
                )
            }
        }
        if (links.isNotEmpty()) {
            item { Section("Links") }
            items(links, key = { "l" + it.key }) { s -> SharedCard(s, across) { openShared(s, open, pick, context) } }
        }
        if (files.isNotEmpty()) {
            item { Section("Photos & files") }
            items(files, key = { "f" + it.key }) { s -> SharedCard(s, across) { openShared(s, open, pick, context) } }
        }
    }
}

@Composable
private fun Section(title: String) {
    BasicText(Pack.caption(title), Modifier.padding(top = 16.dp, bottom = 8.dp), style = Pack.mono(10.sp, Pack.muted))
}

/** One shared thing on the table: its card, who shared it, and from which chat when looking across chats. */
@Composable
private fun SharedCard(
    s: Shared,
    across: Boolean,
    onOpen: () -> Unit,
) {
    val shape = composerShape(10.dp)
    Column(
        Modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Pack.background, shape)
            .border(2.dp, Pack.foreground, shape)
            .semantics { role = Role.Button }
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        when (val p = s.part) {
            is Part.Link -> Card(p.host, p.path.ifEmpty { null }, "↗", Pack.foreground)
            is Part.Website -> Card(p.host.ifEmpty { "Website" }, p.title, "↗", Pack.foreground)
            is Part.Attachment -> Card(p.summary, "Open in OpenBubbles", if (p.kind == "Video") "▶" else "▣", Pack.foreground)
            is Part.Words -> {}
        }
        BasicText(
            Pack.caption((if (across) s.title + " · " else "") + s.sender + " · " + whenLabel(s.time)),
            Modifier.padding(top = 6.dp),
            style = Pack.mono(9.5.sp, Pack.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Folded: the latest shared things as small marks down the strip. */
@Composable
private fun StripOf(
    items: List<Shared>,
    onOpen: (Shared) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.take(8).forEach { s ->
            val shape = composerShape(8.dp)
            Box(
                Modifier.size(38.dp).clip(shape).background(Pack.background, shape).border(2.dp, Pack.foreground, shape)
                    .semantics { role = Role.Button; contentDescription = "Open shared item" }
                    .clickable { onOpen(s) },
                contentAlignment = Alignment.Center,
            ) { BasicText(if (s.part is Part.Attachment) "▣" else "↗", style = Pack.body(Pack.size.item)) }
        }
    }
}

/**
 * Whatever was opened, beside the chat: the page itself in the panel. "‹ Shared" goes back to the table; Copy puts
 * the address on the clipboard, where the keyboard's clipboard history keeps it for any chat.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Viewer(
    url: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember(url) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeadButton("‹ Shared", onBack)
            BasicText(Pack.caption(Parts.host(url)), Modifier.weight(1f), style = Pack.mono(10.sp, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            HeadButton(if (copied) "Copied" else "Copy") {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Link", url))
                copied = true
            }
        }
        val shape = composerShape(10.dp)
        Box(Modifier.fillMaxSize().clip(shape).border(2.dp, Pack.foreground, shape)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        // Links inside the page stay in the panel.
                        webViewClient = WebViewClient()
                        loadUrl(url)
                    }
                },
                update = { if (it.url != url && it.originalUrl != url) it.loadUrl(url) },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun HeadButton(
    label: String,
    onClick: () -> Unit,
) {
    val shape = composerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(Pack.background, shape)
            .border(2.dp, Pack.foreground, shape)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) { BasicText(label, style = Pack.body(Pack.size.small).copy(fontWeight = FontWeight.SemiBold)) }
}
