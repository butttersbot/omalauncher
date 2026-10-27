package dev.omalauncher.oma

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.ReadingWidth
import dev.omalauncher.ui.framed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal val LocalEarlierThread = staticCompositionLocalOf { "" }

internal val LocalMarkdownArtifactOpen = staticCompositionLocalOf<((String) -> Boolean)?> { null }

internal val LocalArtifactOpen = staticCompositionLocalOf<((ChatArtifact) -> Unit)?> { null }

/** The artifact showing beside the chat right now and its version (0 when it has none), so its card says so; null when none is. */
internal val LocalArtifactShowing = compositionLocalOf<Pair<String, Int>?> { null }

/** The strip above the composer that tells of an agent's artifact on a narrow screen (never covering the chat); null when there is none. */
internal val LocalArtifactPeek = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** The computer this tab's thread is on ("Mac Pro"), for the panel's footer when no event has said it yet. */
internal val LocalArtifactWhere = compositionLocalOf { "" }

/** The live state of agent-made artifacts, for the panel to follow new versions and edits. */
internal val LocalArtifactLive = compositionLocalOf<List<ArtifactLive>> { emptyList() }

/** How long the strip says a version is ready before it goes by itself. */
private const val PEEK_READY_MS = 30_000L

/**
 * The existing chat remains composed under the cover's viewer, so its field and scroll don't restart.
 * [live] is what the threads' events say of agent-made artifacts and [threads] the threads whose entries this tab shows:
 * a new version or an edit of one of theirs opens beside the chat when the window is wide, and is told of in a strip above
 * the composer when it is narrow, which never takes the screen from him.
 */
@Composable
internal fun ChatArtifactWorkspace(
    host: ChatHost,
    tab: String,
    artifacts: List<ChatArtifact>,
    onDiscuss: (ChatArtifact) -> Unit,
    live: List<ArtifactLive> = emptyList(),
    threads: Set<String> = emptySet(),
    where: String = "",
    content: @Composable () -> Unit,
) {
    var selections by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var opened by remember { mutableStateOf(mapOf<String, ChatArtifact>()) }
    var fileLists by rememberSaveable { mutableStateOf(setOf<String>()) }
    // Each open counts, so opening a card of an older version shows that version even when the same artifact is open.
    var opens by remember { mutableIntStateOf(0) }
    // The version the panel shows, so only the card of that version says it is showing.
    var shownVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab) { expanded = false }
    val selected = artifacts.firstOrNull { it.id == selections[tab] }?.let { listed -> opened[tab]?.takeIf { it.id == listed.id } ?: listed }
        ?: opened[tab]?.takeIf { it.id == selections[tab] }
    val open: (ChatArtifact) -> Unit = { opened = opened + (tab to it); selections = selections + (tab to it.id); fileLists = fileLists - tab; expanded = false; opens++ }
    val close = { selections = selections - tab; opened = opened - tab; fileLists = fileLists - tab; expanded = false }
    val listing = tab in fileLists
    val panelOpen = selected != null || listing
    BackHandler(panelOpen) { close() }
    // What was last seen of each artifact of this tab's threads: what is already there when the tab opens opens nothing.
    var seen by remember(tab) { mutableStateOf(live.filter { it.thread in threads }.associate { it.key to (it.version to it.editing) }) }
    // Kept across a fold or unfold, which may rebuild the screen: the strip, and whether the window was wide before.
    var peek by rememberSaveable(tab) { mutableStateOf<String?>(null) }
    var wasWide by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // What the strip shows for an artifact no event has spoken of yet (one he had open when he folded).
    var peekAlone by remember { mutableStateOf<ArtifactLive?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Portrait Fold windows still have enough room for two useful reading panes. Cover windows do not.
        val wide = maxWidth >= 680.dp
        val news = artifactNews(seen, live, threads, System.currentTimeMillis() / 1000)
        LaunchedEffect(news) {
            seen = artifactSeen(seen, live, threads)
            val newest = news.firstOrNull() ?: return@LaunchedEffect
            val artifact = somaArtifact(newest.thread, newest.name, newest.version)
            // Beside the chat, unless he is looking at another artifact there: that one stays.
            if (wide) {
                if (selected == null || listing || selected.id == artifact.id) {
                    if (selected?.id != artifact.id) open(artifact)
                }
            } else if (selected?.id != artifact.id) peek = newest.key
        }
        // The strip goes once he has seen the artifact, and a version that is ready goes by itself after a while.
        val peeked = live.firstOrNull { it.key == peek } ?: peekAlone?.takeIf { it.key == peek }
        LaunchedEffect(peek, peeked?.editing, selected?.id) {
            if (peeked != null && selected?.id == peeked.key) peek = null
            else if (peeked != null && peeked.editing == null) { delay(PEEK_READY_MS); peek = null }
        }
        // Unfolding while the strip tells of an artifact shows that artifact beside the chat. Folding with an agent's artifact
        // beside the chat never covers the chat with it: it goes to the strip, one tap away (Boss: the cover screen is the chat's).
        LaunchedEffect(wide, peek, selected?.id) {
            val before = wasWide
            wasWide = wide
            if (wide && peeked != null && selected == null) { open(somaArtifact(peeked.thread, peeked.name, peeked.version)); peek = null }
            else if (before == true && !wide && !expanded && selected?.soma == true) {
                val folded = selected
                peekAlone = ArtifactLive(folded.thread, folded.name, maxOf(folded.version, shownVersion), null, time = System.currentTimeMillis() / 1000)
                close()
                peek = folded.id
            }
        }
        val overlay = panelOpen && (!wide || expanded)
        val focus = LocalFocusManager.current
        LaunchedEffect(overlay) { if (overlay) focus.clearFocus() }
        val strip: (@Composable () -> Unit)? = peeked?.takeIf { !wide && !overlay }?.let { shown ->
            { ArtifactPeek(shown) { peek = null; open(somaArtifact(shown.thread, shown.name, shown.version)) } }
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().then(if (overlay) Modifier.clearAndSetSemantics {} else Modifier), contentAlignment = Alignment.TopCenter) {
                CompositionLocalProvider(
                    LocalArtifactOpen provides open,
                    LocalMarkdownArtifactOpen provides { link ->
                        val target = artifactPath(link) ?: link
                        artifacts.firstOrNull { it.location == target }?.let { open(it); true } ?: false
                    },
                    LocalArtifactShowing provides selected?.id?.takeIf { panelOpen && !overlay }?.let { it to shownVersion },
                    LocalArtifactPeek provides strip,
                ) {
                    Column(Modifier.fillMaxSize().widthIn(max = ReadingWidth)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            BracketAction("artifacts · ${artifacts.size}", Pack.accent) { fileLists = fileLists + tab; expanded = false }
                        }
                        content()
                    }
                }
            }
            if (panelOpen && !overlay) {
                Box(Modifier.weight(.92f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalArtifactLive provides live, LocalArtifactWhere provides where) {
                        if (listing) ArtifactFiles(artifacts, close, open)
                        else if (selected != null) ArtifactPanel(host, selected, opens, artifacts, wide = true, canSplit = false, { expanded = true }, close, open, onDiscuss,
                            onShown = { shownVersion = it }, onLatest = { v -> if (selected.version != v) opened = opened + (tab to selected.copy(version = v)) })
                    }
                }
            }
        }
        if (overlay) {
            Box(Modifier.fillMaxSize().background(Pack.background)) {
                CompositionLocalProvider(LocalArtifactLive provides live, LocalArtifactWhere provides where) {
                    if (listing) ArtifactFiles(artifacts, close, open)
                    else if (selected != null) ArtifactPanel(host, selected, opens, artifacts, wide, canSplit = wide, if (wide) ({ expanded = false }) else null, close, open,
                        onDiscuss = {
                            onDiscuss(it)
                            if (!wide) close() else expanded = false
                        }, onShown = { shownVersion = it }, onLatest = { v -> if (selected.version != v) opened = opened + (tab to selected.copy(version = v)) })
                }
            }
        }
    }
}

/** The strip above the composer on a narrow screen: an agent is editing an artifact, or a version of it is ready. */
@Composable
private fun ArtifactPeek(shown: ArtifactLive, onView: () -> Unit) {
    val editing = shown.editing != null
    val label = shown.name.substringBeforeLast('.')
    val words = if (editing) "${shown.agent.ifBlank { "The agent" }} is editing $label · v${shown.editing}" else "$label · v${shown.version} is ready"
    val blink by rememberInfiniteTransition().animateFloat(1f, 0f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse))
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).framed(Pack.hair * 1.4f, Pack.red, Pack.background).clickable(onClick = onView).padding(start = 10.dp)
            .semantics { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(if (editing) blink else 1f).background(if (editing) Pack.red else Pack.green))
        BasicText(words, Modifier.weight(1f).padding(horizontal = 8.dp), style = Pack.body(Pack.size.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BracketAction("view", Pack.accent, onView)
    }
}

@Composable
private fun ArtifactFiles(artifacts: List<ChatArtifact>, onClose: () -> Unit, onOpen: (ChatArtifact) -> Unit) {
    Column(Modifier.fillMaxSize().background(Pack.background).framed(Pack.hair, Pack.border, Pack.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Artifacts", Modifier.weight(1f), style = Pack.body(Pack.size.small))
            BracketAction("close", Pack.accent, onClose)
        }
        ArtifactFileList(artifacts, onOpen)
    }
}

@Composable
private fun ArtifactFileList(artifacts: List<ChatArtifact>, onOpen: (ChatArtifact) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        BasicText("From this conversation", style = Pack.mono(Pack.size.small, Pack.muted))
        if (artifacts.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            BasicText("No artifacts in this conversation yet.", style = Pack.body())
            Spacer(Modifier.height(8.dp))
            BasicText("What an agent makes for you, and files and code shared in replies, appear here.", style = Pack.body(Pack.size.small, Pack.muted))
        }
        for (file in artifacts) BasicText(file.name + if (file.soma) "  · v${file.version}" else "",
            Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(vertical = 14.dp), style = Pack.body())
    }
}

@Composable
private fun ArtifactPanel(
    host: ChatHost,
    artifact: ChatArtifact,
    opens: Int,
    artifacts: List<ChatArtifact>,
    wide: Boolean,
    canSplit: Boolean,
    onExpand: (() -> Unit)?,
    onClose: () -> Unit,
    onOpen: (ChatArtifact) -> Unit,
    onDiscuss: (ChatArtifact) -> Unit,
    onShown: (Int) -> Unit = {},
    /** The panel follows the newest version, now [Int]: what is open is that version, so a rebuilt panel follows on too. */
    onLatest: (Int) -> Unit = {},
) {
    var view by rememberSaveable(artifact.id) { mutableStateOf("Preview") }
    var reload by remember(artifact.id) { mutableIntStateOf(0) }
    val live = LocalArtifactLive.current.firstOrNull { it.key == artifact.id }
    // An agent-made artifact follows its newest version by itself; an older one is shown only when he asks for it.
    var latestRead by remember(artifact.id) { mutableIntStateOf(0) }
    val latest = maxOf(artifact.version, live?.version ?: 0, latestRead)
    // Opened at the version its card made; once that turns out to be the newest, it follows new versions from then on.
    var viewing by remember(artifact.id, opens) { mutableStateOf(artifact.version.takeIf { artifact.soma && it > 0 }) }
    val older = artifact.soma && viewing != null && viewing != latest
    val editing = live?.editing?.takeIf { artifact.soma && !older }
    val path = if (artifact.soma) somaArtifactPath(artifact.name, viewing) else artifact.location
    // The version shown stays while the next is read, so a new version replaces it in place instead of flashing.
    var read by remember(artifact.id) { mutableStateOf<ArtifactRead?>(null) }
    LaunchedEffect(artifact.id, path, maxOf(artifact.version, live?.version ?: 0).takeIf { viewing == null }, reload) {
        if (!artifact.soma) read = null
        // The first version isn't there yet while an agent makes it: nothing to read until it is.
        if (artifact.soma && latest == 0) return@LaunchedEffect
        read = if (artifact.inline != null) inlineArtifact(artifact) else if (artifact.kind == ArtifactKind.Web) null
        else withContext(Dispatchers.IO) {
            runCatching { host.readArtifact(artifact.thread, path) }.getOrElse { ArtifactRead.Failed("This artifact could not be read. Try again.") }
        }
        (read as? ArtifactRead.Ready)?.document?.let { got ->
            if (got.latest > latestRead) latestRead = got.latest
            if (viewing != null && got.latest > 0 && got.v == got.latest) viewing = null
        }
    }
    val document = (read as? ArtifactRead.Ready)?.document
    val shownVersion = document?.v?.takeIf { it > 0 } ?: viewing ?: latest
    LaunchedEffect(shownVersion) { onShown(shownVersion) }
    LaunchedEffect(shownVersion, viewing) { if (artifact.soma && viewing == null && shownVersion > 0) onLatest(shownVersion) }
    // A new version while he is looking at the newest: said quietly over the preview for a moment.
    var toast by remember(artifact.id) { mutableStateOf<String?>(null) }
    var toldOf by remember(artifact.id) { mutableIntStateOf(latest) }
    LaunchedEffect(latest) {
        if (artifact.soma && latest > toldOf && toldOf > 0 && viewing == null) { toast = "v$latest ready"; toldOf = latest; delay(1800); toast = null }
        else toldOf = maxOf(toldOf, latest)
    }
    val tabs = if (artifact.soma) listOf("Preview", "Source", "Versions") else listOf("Preview", "Source", "Files")
    Column(Modifier.fillMaxSize().background(Pack.background).framed(Pack.hair, Pack.border, Pack.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) BracketAction("← chat", Pack.accent, onClose)
            BasicText(artifact.name, Modifier.weight(1f, fill = false).padding(start = 4.dp).semantics { contentDescription = "Artifact: ${artifact.name}" }, style = Pack.body(Pack.size.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artifact.soma && shownVersion > 0) {
                BasicText(if (older) "v$shownVersion of $latest" else "v$shownVersion",
                    Modifier.padding(start = 8.dp).background(if (older) Pack.red else Pack.foreground).padding(horizontal = 6.dp, vertical = 1.dp),
                    style = Pack.mono(Pack.size.small, Pack.background), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (onExpand != null) BracketAction(if (canSplit) "split" else "expand", Pack.accent, onExpand)
            if (wide) BracketAction("close", Pack.accent, onClose)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (name in tabs) {
                val label = if (name == "Versions") "versions · $latest" else name.lowercase()
                BracketAction(label, if (view == name) Pack.accent else Pack.muted) { view = name }
            }
        }
        Box(Modifier.fillMaxWidth().height(Pack.hair).background(Pack.border))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                view == "Files" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    BasicText("From this conversation", style = Pack.mono(Pack.size.small, Pack.muted))
                    for (file in artifacts) BasicText(file.name,
                        Modifier.fillMaxWidth().clickable { onOpen(file); view = "Preview" }.padding(vertical = 14.dp), style = Pack.body(color = if (file.id == artifact.id) Pack.accent else Pack.foreground))
                }
                view == "Versions" -> ArtifactVersionsList(host, artifact, latest, shownVersion) { picked ->
                    viewing = picked.takeIf { it != latest }
                    view = "Preview"
                }
                artifact.kind == ArtifactKind.Web -> if (view == "Source") ArtifactText(artifact.location) else key(reload) { ArtifactWeb(artifact.location, null, Modifier.fillMaxSize(), canvasOrigin = host.canvasOrigin()) }
                artifact.soma && latest == 0 -> Column(Modifier.fillMaxSize()) {
                    ArtifactEditingStrip("${live?.agent?.ifBlank { null } ?: "The agent"} is making ${artifact.name} · v1")
                    BasicText("It shows here as soon as it is saved.", Modifier.padding(16.dp), style = Pack.body(Pack.size.small, Pack.muted))
                }
                read == null -> Column(Modifier.padding(16.dp)) {
                    BasicText("Reading ${artifact.name}…", style = Pack.mono(Pack.size.small, Pack.muted))
                    repeat(5) { Spacer(Modifier.padding(top = 12.dp).fillMaxWidth(if (it == 4) .6f else 1f).height(10.dp).background(Pack.surface)) }
                }
                read is ArtifactRead.Failed -> Column(Modifier.padding(16.dp)) {
                    BasicText((read as ArtifactRead.Failed).reason, style = Pack.body())
                    BracketAction("try again", Pack.accent) { reload++ }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    val shown = document!!
                    if (older && view == "Preview") {
                        Row(Modifier.fillMaxWidth().background(Pack.foreground).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText("Viewing v$shownVersion, an earlier version", Modifier.weight(1f), style = Pack.body(Pack.size.small, Pack.background), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BracketAction("back to latest", Pack.accent) { viewing = null }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.fillMaxSize().alpha(if (editing != null && view == "Preview") .45f else 1f)) {
                            when {
                                view == "Source" && artifact.kind == ArtifactKind.Image -> ArtifactText("${shown.name}\n${shown.mime}\n${shown.bytes.size} bytes")
                                view == "Source" -> ArtifactText(shown.readingText)
                                artifact.kind == ArtifactKind.Html -> ArtifactWeb(null, shown.text, Modifier.fillMaxSize(),
                                    onAnswer = if (editing != null) null else { request, done ->
                                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(shown.bytes).joinToString("") { "%02x".format(it) }
                                        host.submitArtifactReply(ArtifactReplySource(artifact.id, artifact.name, shown.v.takeIf { it > 0 }?.toString() ?: shown.version, hash), request, done)
                                    })
                                artifact.kind == ArtifactKind.Image -> ArtifactImage(shown.bytes, shown.name, Modifier.fillMaxSize())
                                artifact.kind == ArtifactKind.Markdown -> SelectionContainer { MarkdownText(shown.readingText, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) }
                                else -> ArtifactText(shown.readingText)
                            }
                        }
                        if (editing != null && view == "Preview") ArtifactEditingStrip("${live.agent.ifBlank { "The agent" }} is editing · v$editing")
                        toast?.let { said ->
                            BasicText(said, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).background(Pack.foreground).padding(horizontal = 12.dp, vertical = 7.dp),
                                style = Pack.body(Pack.size.small, Pack.background))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(Pack.hair).background(Pack.border))
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val caption = if (artifact.soma) listOfNotNull(
                "v$shownVersion".takeIf { shownVersion > 0 },
                (live?.where?.takeIf { it.isNotBlank() } ?: LocalArtifactWhere.current.takeIf { it.isNotBlank() })?.let { "made on $it" },
                "offline, sandboxed".takeIf { artifact.kind == ArtifactKind.Html },
            ).joinToString(" · ")
            else document?.version?.take(8)?.let { "version $it" } ?: "from this conversation"
            BasicText(Pack.caption(caption), Modifier.weight(1f), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!artifact.soma) BracketAction("refresh", Pack.muted) { reload++ }
            BracketAction("discuss", Pack.accent) { onDiscuss(if (artifact.soma) artifact.copy(version = shownVersion) else artifact) }
        }
    }
}

/** The red strip over the preview while an agent works on the next version, with a line that keeps moving under it. */
@Composable
private fun ArtifactEditingStrip(words: String) {
    val moving by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse))
    Column(Modifier.fillMaxWidth().semantics { contentDescription = words }) {
        BasicText(Pack.caption(words).uppercase(), Modifier.fillMaxWidth().background(Pack.red).padding(horizontal = 12.dp, vertical = 6.dp),
            style = Pack.mono(Pack.size.small, Pack.background), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp)) {
            Box(Modifier.fillMaxHeight().width(maxWidth * .3f).offset(x = maxWidth * .7f * moving).background(Pack.foreground))
        }
    }
}

/** Every version the computer keeps of [artifact], newest first; a tap shows that one. */
@Composable
private fun ArtifactVersionsList(host: ChatHost, artifact: ChatArtifact, latest: Int, shown: Int, onPick: (Int) -> Unit) {
    val listed by produceState<ArtifactVersions?>(null, artifact.id, latest) {
        value = withContext(Dispatchers.IO) { runCatching { host.artifactVersions(artifact.thread, artifact.name) }.getOrNull() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        BasicText(Pack.caption("Every version the agent saved"), Modifier.padding(bottom = 4.dp), style = Pack.mono(Pack.size.small, Pack.muted))
        val versions = listed?.versions.orEmpty()
        if (listed == null) BasicText("Reading the versions…", Modifier.padding(vertical = 12.dp), style = Pack.body(Pack.size.small, Pack.muted))
        val rows = if (versions.isNotEmpty()) versions else (1..latest).map { ArtifactVersion(it, 0L, 0L, "") }.takeIf { listed != null }.orEmpty()
        for (one in rows.sortedByDescending { it.version }) {
            val on = one.version == shown
            Row(Modifier.fillMaxWidth().clickable { onPick(one.version) }.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
                BasicText("v${one.version}", Modifier.background(Pack.foreground).padding(horizontal = 6.dp, vertical = 1.dp), style = Pack.mono(Pack.size.small, Pack.background))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    BasicText(one.note.ifBlank { "Version ${one.version}" }, style = Pack.body(color = if (on) Pack.accent else Pack.foreground))
                    val stamp = if (one.time > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(one.time * 1000)) else ""
                    BasicText(listOfNotNull(stamp.ifBlank { null }, "latest".takeIf { one.version == latest }).joinToString(" · "), style = Pack.body(Pack.size.small, Pack.muted))
                }
            }
            Box(Modifier.fillMaxWidth().height(Pack.hair).background(Pack.surface))
        }
    }
}

@Composable
internal fun ArtifactText(text: String) {
    SelectionContainer {
        BasicText(text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), style = Pack.mono(Pack.size.small))
    }
}

/** Same pack frame as a prompt, with an explicit open action; source text still stays in the answer. */
@Composable
internal fun ChatArtifactLinks(text: String, origin: String, thread: String) {
    val open = LocalArtifactOpen.current ?: return
    val files = remember(text, origin, thread) { chatArtifacts(text, origin, thread) }
    for (file in files) Row(Modifier.fillMaxWidth().padding(top = 8.dp).framed(Pack.hair, Pack.border, Pack.surface).clickable { open(file) }.padding(10.dp)) {
        BasicText(file.name, Modifier.weight(1f), style = Pack.body(Pack.size.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("[ open ]", style = Pack.mono(Pack.size.small, Pack.accent))
    }
}

/** The card under an entry for each artifact its turn made: a tap opens it (that version), and the one showing says so. */
@Composable
internal fun SomaArtifactCards(made: List<ChatArtifact>) {
    val open = LocalArtifactOpen.current ?: return
    val showing = LocalArtifactShowing.current
    for (artifact in made) {
        val on = showing != null && showing.first == artifact.id && (showing.second == 0 || showing.second == artifact.version)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).framed(Pack.hair * 1.3f, if (on) Pack.red else Pack.border, Pack.surface).clickable { open(artifact) }.padding(horizontal = 12.dp, vertical = 9.dp)
                .semantics { contentDescription = "${artifact.name}, version ${artifact.version}" + if (on) ", showing" else "" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).framed(Pack.hair, Pack.foreground, Pack.background), contentAlignment = Alignment.Center) {
                BasicText(artifactGlyph(artifact.name), style = Pack.mono(Pack.size.small * .75f))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                BasicText(artifact.name, style = Pack.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(Pack.caption("v${artifact.version} · ${artifactKindLabel(artifact.kind)}"), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1)
            }
            BasicText(Pack.caption(if (on) "showing →" else "open"), style = Pack.mono(Pack.size.small, Pack.red))
        }
    }
}

@Composable
internal expect fun ArtifactWeb(url: String?, html: String?, modifier: Modifier, onAnswer: ArtifactAnswerHandler? = null, onApprove: ((ArtifactCanvasRequest) -> Unit)? = null, canvasOrigin: String? = null)

@Composable
internal expect fun ArtifactImage(bytes: ByteArray, description: String, modifier: Modifier)

/** Links in a streaming reply carry that reply's computer, even before its result reaches the saved log. */
@Composable
internal fun ArtifactMarkdown(text: String, origin: String, thread: String, modifier: Modifier = Modifier) {
    val open = LocalArtifactOpen.current
    val files = remember(text, origin, thread) { chatArtifacts(text, origin, thread) }
    val intercept: ((String) -> Boolean)? = if (open == null) null else { link ->
        val target = artifactPath(link) ?: link
        files.firstOrNull { it.location == target }?.let { open(it); true } ?: false
    }
    CompositionLocalProvider(LocalMarkdownArtifactOpen provides intercept) { MarkdownText(text, modifier) }
}
