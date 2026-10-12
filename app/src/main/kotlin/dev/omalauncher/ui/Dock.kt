package dev.omalauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.Runtime
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.AssistantPromptActivity
import dev.omalauncher.assist.marginNote
import dev.omalauncher.assist.prototypeUnit
import dev.omalauncher.linux.FILES_COMMAND
import dev.omalauncher.spokenLabel
import dev.omalauncher.theme.pack.DockStyle
import dev.omalauncher.widgets.Shortcut
import kotlinx.coroutines.delay

/**
 * Height of the dock's row of icons, as of the prompt bar it replaced; the home grid leaves this much
 * room for it, so the name stays for the files that read it.
 */
val PromptBarHeight = 48.dp

/** A dock icon's size: fixed, whatever the width. */
internal val DockSlot = 48.dp

/** The least room between two dock icons. */
internal val DockGap = 8.dp

/** Room around the band's content, as around the bar's. */
internal val DockBandPadding = 6.dp

/**
 * What the band takes of home, in px: its measured frame and the grid gap above and below it.
 * This is [LauncherState.barHeight], which home ends its grid above.
 */
internal fun bandHeight(
    frame: Int,
    gap: Int,
) = frame + 2 * gap

/** The action that opens the Oma chat, inside this app only. */
const val OMA_CHAT_ACTION = "dev.omalauncher.action.OMA_CHAT"

/**
 * Opens the Oma chat, with [text] waiting in its field (not sent) when there is some. The one way in for
 * the dock entry, a tap on the agent button, Super+A and the drawer's ask row. A build without the chat
 * opens the assistant prompt instead, which takes no text.
 */
fun openOmaChat(
    context: Context,
    text: String? = null,
) {
    // From anything but an activity, a start needs its own task.
    val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
    val chat = Intent(OMA_CHAT_ACTION).setPackage(context.packageName).addFlags(flags)
    if (!text.isNullOrBlank()) chat.putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(chat)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(context, AssistantPromptActivity::class.java).addFlags(flags))
    }
}

/** The dock key of an app in the drawer: the key its home shortcut would have. */
internal fun AppHit.dockKey() =
    when (this) {
        is AppHit.Linux -> "linux:" + app.id
        is AppHit.Android -> "app:" + app.key
    }

internal fun AppHit.dockEntry() =
    when (this) {
        is AppHit.Linux -> DockEntry(dockKey(), app.name, linux = app)
        is AppHit.Android -> DockEntry(dockKey(), app.label, component = app.component.flattenToString(), profile = app.profile)
    }

/** The entry as a home shortcut, which the runtime knows how to draw and open. */
private fun DockEntry.shortcut() = Shortcut(key, label, component = component?.let(ComponentName::unflattenFromString), profile = profile, linux = linux)

/**
 * The band at the bottom of home: one row with the dock's pinned apps and the agent button, across the whole
 * width, also where home shows two pages. What the agent is doing is pencilled in the margin above it (the launcher's margin note), and
 * what it hears is written over the whole page (the launcher's listening layer), so the row never changes.
 */
@Composable
internal fun DockBand(
    runtime: Runtime,
    state: LauncherState,
    dock: DockStore,
    voice: AgentVoice,
    modifier: Modifier = Modifier,
) {
    val bare = Pack.bareIcons
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    // Measured rather than assumed (a large system font makes the band taller), and passed on while home shows.
    var frame by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { PageGap.roundToPx() }
    LaunchedEffect(frame, panelShown, gap) { if (frame > 0 && !panelShown) state.barHeight = bandHeight(frame, gap) }
    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { frame = it.height }
            .padding(DockBandPadding),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = PromptBarHeight)
                // The bar's fill without its border, which would run over icons as tall as the row. Bare icons sit on
                // the wallpaper, as in the recent apps strip, and so does a pack's dock of separate buttons or of
                // icons alone. The row is as tall either way.
                .then(if (bare || Pack.dock != DockStyle.Bar) Modifier else Modifier.dockBarFill()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldStrut()
            BoxWithConstraints(Modifier.weight(1f).padding(horizontal = DockGap)) {
                val count = with(LocalDensity.current) { dockFits(constraints.maxWidth, DockSlot.roundToPx(), DockGap.roundToPx()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DockGap, Alignment.CenterHorizontally)) {
                    DockCells(runtime, dock, count)
                }
            }
            // In a dock of buttons the agent button is one more of them, leaning its own way.
            AgentButton(runtime, state, voice, Modifier.dockButton(1), resting = if (bare && !Pack.dockButtons) Pack.surface else Pack.clear)
        }
    }
}

/**
 * The prototype's `.marg`: while the agent works, what it is doing, pencilled at a slant in the margin over
 * the dock; also where the microphone says what it needs. It takes no room, so the pages above keep theirs.
 * A tap opens the Oma chat, or puts a message from the microphone away.
 */
@Composable
internal fun MarginNote(voice: AgentVoice) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            AgentTasks.restore(context)
            tick++
            delay(500)
        }
    }
    val doing = remember(tick) { if (AgentTasks.working) marginNote(AgentTasks.label) else null }
    val said = (voice.speech.error ?: voice.notice)?.takeIf { !voice.taking }
    val text = said ?: doing
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    val shown by animateFloatAsState(if (text != null) 1f else 0f, Motion.timed(300), label = "margin")
    if (shown == 0f) return
    // In the prototype's units, like the listening screen: 19 of them on a phone 380 across.
    val u = LocalConfiguration.current.run { prototypeUnit(screenWidthDp.toFloat(), screenHeightDp.toFloat()) }
    BasicText(
        "✎ Oma: $last",
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0))
                layout(p.width, 0) { p.place(16.dp.roundToPx(), -p.height - 8.dp.roundToPx()) }
            }.graphicsLayer {
                alpha = shown
                rotationZ = -2f
                transformOrigin = TransformOrigin(0f, 1f)
            }.clickable(enabled = text != null) {
                if (said != null) {
                    voice.speech.error = null
                    voice.notice = null
                } else {
                    openOmaChat(context)
                }
            },
        style = Pack.hand((19 * u).sp, Pack.onPage).copy(fontWeight = FontWeight.Bold, lineHeight = (21 * u).sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Keeps the row as tall as the prompt bar's was: the bar's field, agent name and buttons each stood a line
 * of text on 12dp of padding above and below, which a large system font makes taller than [PromptBarHeight].
 * Draws nothing and takes no width.
 */
@Composable
private fun FieldStrut() {
    Box(Modifier.width(0.dp).padding(vertical = 12.dp).alpha(0f).clearAndSetSemantics {}) {
        BasicText(" ", style = Pack.strut(Pack.size.item), maxLines = 1, softWrap = false)
        BasicText("+", style = Pack.strut(Pack.size.body), maxLines = 1, softWrap = false)
        Symbol(Glyph.ChevronDown, Pack.clear)
    }
}

/** The first [count] dock entries, for a row or a column to lay out. */
@Composable
internal fun DockCells(
    runtime: Runtime,
    dock: DockStore,
    count: Int,
) {
    val context = LocalContext.current
    val entries = dock.items
    // A pack's dock of buttons frames each icon, which is drawn smaller to sit inside its button.
    val buttons = Pack.dockButtons
    entries.take(count).forEachIndexed { index, entry ->
        val actions = { entries.actions(entry.key).map { action -> action.label to { dock.act(action, entry.key) } } }
        if (entry.builtIn) {
            ChatCell(onClick = { openOmaChat(context) }, actions = actions, chrome = Modifier.dockButton(index))
            return@forEachIndexed
        }
        val shortcut = remember(entry) { entry.shortcut() }
        StripCell(
            label = entry.label,
            icon = runtime.shortcutIcon(shortcut, mono = false),
            mask = runtime.shortcutIcon(shortcut, mono = true),
            onClick = { runtime.open(shortcut) },
            actions = actions,
            glyph = if (entry.linux?.exec == FILES_COMMAND) Icons.Folder else null,
            spoken = spokenLabel(entry.label, entry.profile),
            paused = runtime.androidApps.paused(entry.profile),
            size = if (buttons) DockSlot * DOCK_BUTTON_ICON else DockSlot,
            chrome = Modifier.dockButton(index),
        )
    }
}

/** How much of its button a dock icon fills, in a pack whose dock is a row of buttons. */
private const val DOCK_BUTTON_ICON = 0.66f

/** The Oma chat's place in the dock: a mark on a badge, as apps without an icon get their initials. */
@Composable
private fun ChatCell(
    onClick: () -> Unit,
    actions: () -> List<Pair<String, () -> Unit>>,
    /** Its button, in a pack whose dock is a row of buttons: see [dockButton]. */
    chrome: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val buttons = Pack.dockButtons
    Box(
        Modifier
            .size(DockSlot)
            .then(chrome)
            .clip(Pack.shape(DockSlot / 2))
            // A button is its own badge.
            .background(if (buttons) Pack.clear else Pack.badge)
            .semantics { contentDescription = "Oma chat" }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // Alone in the dock it has nowhere to move, and it can't be unpinned: no menu.
                    if (actions().isNotEmpty()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("✦", style = if (buttons) Pack.placeholder(Pack.size.title, Pack.dockInk) else Pack.placeholder(Pack.size.title))
        if (menuOpen) AppActions(actions(), onDismiss = { menuOpen = false })
    }
}

/**
 * Covers the dock while the app list is over it: a tap there closes the list, as on the scrim,
 * rather than reaching an icon that isn't showing.
 */
@Composable
internal fun DockCover(
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    Box(modifier.pointerInput(state) { detectTapGestures { state.close() } })
}

/** Fades the dock out as the app list comes up over it. */
internal fun Modifier.fadesUnderDrawer(state: LauncherState) = graphicsLayer { alpha = 1f - state.drawer.value.coerceIn(0f, 1f) }
