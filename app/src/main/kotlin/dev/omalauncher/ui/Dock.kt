package dev.omalauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.assist.AssistantPromptActivity
import dev.omalauncher.linux.FILES_COMMAND
import dev.omalauncher.spokenLabel
import dev.omalauncher.widgets.Shortcut

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
 * The band at the bottom of home, where the prompt bar was: the agent's status strip over one row with
 * the dock's pinned apps and the agent button. Exactly as tall as the bar's band, so the grid above keeps its
 * rows; one row across the whole width, also where home shows two pages. While the button is held, the
 * row shows what's heard instead of the icons.
 */
@Composable
internal fun DockBand(
    runtime: Runtime,
    state: LauncherState,
    dock: DockStore,
    modifier: Modifier = Modifier,
) {
    val voice = rememberAgentVoice()
    val speech = voice.speech
    val listening = speech.listening
    val bare = LauncherStyle.bareAppIcons
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    // Measured rather than assumed (a large system font makes the band taller), and passed on while home shows.
    var frame by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { HomeGap.roundToPx() }
    LaunchedEffect(frame, panelShown, gap) { if (frame > 0 && !panelShown) state.barHeight = bandHeight(frame, gap) }
    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { frame = it.height }
            .padding(DockBandPadding),
    ) {
        AgentRail()
        // What went wrong, in red, or what to do next.
        val failed = speech.error
        (failed ?: voice.notice)?.takeIf { !listening }?.let {
            BasicText(
                it,
                Modifier
                    // Takes no room: it shows over the recent apps strip rather than pushing it into the grid.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, 0) { p.place(0, -p.height) }
                    }.background(Palette.background)
                    .clickable {
                        speech.error = null
                        voice.notice = null
                    }.padding(horizontal = 10.dp, vertical = 4.dp),
                style = Mono.copy(color = if (failed != null) Palette.red else Palette.accent, fontSize = TextSize.Small),
            )
        }
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = PromptBarHeight)
                    // A tile's fill without its border, which would run over icons as tall as the row; outlined while
                    // listening, as the bar was. Bare icons sit on the wallpaper, as in the recent apps strip.
                    .then(
                        when {
                            listening -> Modifier.tile(true)
                            bare -> Modifier
                            else -> Modifier.frame(2.dp, Color.Transparent, Palette.glass)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldStrut()
                if (listening) {
                    // One line, so the band keeps its height; cut at the start, so the latest words show.
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp).clipToBounds(), contentAlignment = Alignment.CenterEnd) {
                        BasicText(
                            speech.text.ifEmpty { "listening…" },
                            Modifier.wrapContentWidth(Alignment.End, unbounded = true),
                            style = Mono.copy(fontSize = TextSize.Item, color = Palette.accent),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                } else {
                    BoxWithConstraints(Modifier.weight(1f).padding(horizontal = DockGap)) {
                        val count = with(LocalDensity.current) { dockFits(constraints.maxWidth, DockSlot.roundToPx(), DockGap.roundToPx()) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DockGap, Alignment.CenterHorizontally)) {
                            DockCells(runtime, dock, count)
                        }
                    }
                }
                AgentButton(runtime, state, voice, resting = if (bare) Palette.surface else Color.Transparent)
            }
            // Input level along the bottom edge while listening.
            if (listening) {
                val accent = Palette.accent
                Box(
                    Modifier.matchParentSize().drawBehind {
                        val h = 3.dp.toPx()
                        drawRect(accent, Offset(0f, size.height - h), Size(size.width * (0.05f + 0.95f * voice.level.value), h))
                    },
                )
            }
        }
    }
}

/**
 * Keeps the row as tall as the prompt bar's was: the bar's field, agent name and buttons each stood a line
 * of text on 12dp of padding above and below, which a large system font makes taller than [PromptBarHeight].
 * Draws nothing and takes no width.
 */
@Composable
private fun FieldStrut() {
    Box(Modifier.width(0.dp).padding(vertical = 12.dp).alpha(0f).clearAndSetSemantics {}) {
        BasicText(" ", style = Mono.copy(fontSize = TextSize.Item), maxLines = 1, softWrap = false)
        BasicText("+", style = Mono, maxLines = 1, softWrap = false)
        Symbol(Glyph.ChevronDown, Color.Transparent)
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
    for (entry in entries.take(count)) {
        val actions = { entries.actions(entry.key).map { action -> action.label to { dock.act(action, entry.key) } } }
        if (entry.builtIn) {
            ChatCell(onClick = { openOmaChat(context) }, actions = actions)
            continue
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
            size = DockSlot,
        )
    }
}

/** The Oma chat's place in the dock: a mark on a badge, as apps without an icon get their initials. */
@Composable
private fun ChatCell(
    onClick: () -> Unit,
    actions: () -> List<Pair<String, () -> Unit>>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(DockSlot)
            .clip(LauncherStyle.shape(DockSlot / 2))
            .background(Palette.surface)
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
        BasicText("✦", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
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
