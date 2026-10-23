package dev.omalauncher.oma

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.withContext

internal val LocalEarlierThread = staticCompositionLocalOf { "" }

internal val LocalMarkdownArtifactOpen = staticCompositionLocalOf<((String) -> Boolean)?> { null }

internal val LocalArtifactOpen = staticCompositionLocalOf<((ChatArtifact) -> Unit)?> { null }

/** The existing chat remains composed under the cover's viewer, so its field and scroll don't restart. */
@Composable
internal fun ChatArtifactWorkspace(
    host: ChatHost,
    tab: String,
    artifacts: List<ChatArtifact>,
    onDiscuss: (ChatArtifact) -> Unit,
    content: @Composable () -> Unit,
) {
    var selections by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var opened by remember { mutableStateOf(mapOf<String, ChatArtifact>()) }
    LaunchedEffect(tab) { expanded = false }
    val selected = artifacts.firstOrNull { it.id == selections[tab] } ?: opened[tab]?.takeIf { it.id == selections[tab] }
    val open: (ChatArtifact) -> Unit = { opened = opened + (tab to it); selections = selections + (tab to it.id); expanded = false }
    val close = { selections = selections - tab; opened = opened - tab; expanded = false }
    BackHandler(selected != null) { close() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Portrait Fold windows still have enough room for two useful reading panes. Cover windows do not.
        val wide = maxWidth >= 680.dp
        val overlay = selected != null && (!wide || expanded)
        val focus = LocalFocusManager.current
        LaunchedEffect(overlay) { if (overlay) focus.clearFocus() }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().then(if (overlay) Modifier.clearAndSetSemantics {} else Modifier), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                CompositionLocalProvider(LocalArtifactOpen provides open, LocalMarkdownArtifactOpen provides { link ->
                    val target = artifactPath(link) ?: link
                    artifacts.firstOrNull { it.location == target }?.let { open(it); true } ?: false
                }) {
                    Column(Modifier.fillMaxSize().widthIn(max = ReadingWidth)) {
                        if (artifacts.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            BracketAction("files · ${artifacts.size}", Pack.accent) { open(selected ?: artifacts.first()) }
                        }
                        content()
                    }
                }
            }
            if (selected != null && !overlay) {
                Box(Modifier.weight(.92f).fillMaxHeight()) {
                    ArtifactPanel(host, selected, artifacts, false, { expanded = true }, close, open, onDiscuss)
                }
            }
        }
        if (overlay) {
            Box(Modifier.fillMaxSize().background(Pack.background)) {
                ArtifactPanel(host, selected, artifacts, wide, if (wide) ({ expanded = false }) else null, close, open) {
                    onDiscuss(it)
                    if (!wide) close() else expanded = false
                }
            }
        }
    }
}

@Composable
private fun ArtifactPanel(
    host: ChatHost,
    artifact: ChatArtifact,
    artifacts: List<ChatArtifact>,
    canSplit: Boolean,
    onExpand: (() -> Unit)?,
    onClose: () -> Unit,
    onOpen: (ChatArtifact) -> Unit,
    onDiscuss: (ChatArtifact) -> Unit,
) {
    var view by rememberSaveable(artifact.id) { mutableStateOf("Preview") }
    var reload by remember(artifact.id) { mutableIntStateOf(0) }
    val read by produceState<ArtifactRead?>(null, artifact, reload) {
        value = null
        value = if (artifact.inline != null) inlineArtifact(artifact) else if (artifact.kind == ArtifactKind.Web) null
        else withContext(Dispatchers.IO) {
            runCatching { host.readArtifact(artifact.thread, artifact.location) }.getOrElse { ArtifactRead.Failed("This artifact could not be read. Try again.") }
        }
    }
    Column(Modifier.fillMaxSize().background(Pack.background).framed(Pack.hair, Pack.border, Pack.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            BasicText(artifact.name, Modifier.weight(1f).semantics { contentDescription = "Artifact: ${artifact.name}" }, style = Pack.body(Pack.size.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onExpand != null) BracketAction(if (canSplit) "split" else "expand", Pack.accent, onExpand)
            BracketAction("close", Pack.accent, onClose)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            for (name in listOf("Preview", "Source", "Files")) BracketAction(name.lowercase(), if (view == name) Pack.accent else Pack.muted) { view = name }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                view == "Files" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    BasicText("From this conversation", style = Pack.mono(Pack.size.small, Pack.muted))
                    for (file in artifacts) BasicText(file.name,
                        Modifier.fillMaxWidth().clickable { onOpen(file); view = "Preview" }.padding(vertical = 14.dp), style = Pack.body(color = if (file.id == artifact.id) Pack.accent else Pack.foreground))
                }
                artifact.kind == ArtifactKind.Web -> if (view == "Source") ArtifactText(artifact.location) else key(reload) { ArtifactWeb(artifact.location, null, Modifier.fillMaxSize()) }
                read == null -> Column(Modifier.padding(16.dp)) {
                    BasicText("Reading ${artifact.name}…", style = Pack.mono(Pack.size.small, Pack.muted))
                    repeat(5) { Spacer(Modifier.padding(top = 12.dp).fillMaxWidth(if (it == 4) .6f else 1f).height(10.dp).background(Pack.surface)) }
                }
                read is ArtifactRead.Failed -> Column(Modifier.padding(16.dp)) {
                    BasicText((read as ArtifactRead.Failed).reason, style = Pack.body())
                    BracketAction("try again", Pack.accent) { reload++ }
                }
                else -> {
                    val document = (read as ArtifactRead.Ready).document
                    when {
                        view == "Source" && artifact.kind == ArtifactKind.Image -> ArtifactText("${document.name}\n${document.mime}\n${document.bytes.size} bytes")
                        view == "Source" -> ArtifactText(document.readingText)
                        artifact.kind == ArtifactKind.Html -> ArtifactWeb(null, document.text, Modifier.fillMaxSize())
                        artifact.kind == ArtifactKind.Image -> ArtifactImage(document.bytes, document.name, Modifier.fillMaxSize())
                        artifact.kind == ArtifactKind.Markdown -> SelectionContainer { MarkdownText(document.readingText, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) }
                        else -> ArtifactText(document.readingText)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val version = (read as? ArtifactRead.Ready)?.document?.version?.take(8)
            BasicText(version?.let { "version $it" } ?: "from this conversation", Modifier.weight(1f), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BracketAction("refresh", Pack.muted) { reload++ }
            BracketAction("discuss", Pack.accent) { onDiscuss(artifact) }
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

@Composable
internal expect fun ArtifactWeb(url: String?, html: String?, modifier: Modifier)

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
