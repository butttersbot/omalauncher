package dev.omalauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.Runtime
import dev.omalauncher.assist.AssistantPromptActivity
import dev.omalauncher.assist.prototypeUnit
import dev.omalauncher.linux.FILES_COMMAND
import dev.omalauncher.spokenLabel
import dev.omalauncher.theme.pack.DockStyle
import dev.omalauncher.widgets.Shortcut

/**
 * Height of the dock's row of icons, as of the prompt bar it replaced; the home grid leaves this much
 * room for it, so the name stays for the files that read it.
 */
val PromptBarHeight = 48.dp

/** A dock icon's size: fixed, whatever the width. */
internal val DockSlot = 48.dp

/** The least room between two dock icons, in the prototype's units. */
internal val DockGap = 6.dp

/** From the screen's edge to the dock's first and last icon, in the prototype's units. */
internal val DockEdge = 14.dp

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
 * The band at the bottom of home, as design/oma-scrapbook-prototype.html draws its `.dock`: one row from
 * edge to edge of the pinned apps that fit, leaning one way and the other, then the app list's button and
 * the pen's. Sizes are in the prototype's units, so a wider screen fits more apps: four closed, eleven open.
 */
@Composable
internal fun DockBand(
    runtime: Runtime,
    state: LauncherState,
    dock: DockStore,
    modifier: Modifier = Modifier,
) {
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    // Measured rather than assumed, and passed on while home shows.
    var frame by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val gap = with(density) { PageGap.roundToPx() }
    LaunchedEffect(frame, panelShown, gap) { if (frame > 0 && !panelShown) state.barHeight = bandHeight(frame, gap) }
    val u = LocalConfiguration.current.run { prototypeUnit(screenWidthDp.toFloat(), screenHeightDp.toFloat()) }
    val slot = DockSlot * u
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .onSizeChanged { frame = it.height }
            .padding(horizontal = DockEdge * u, vertical = DockBandPadding),
    ) {
        val fits = with(density) { dockFits(constraints.maxWidth, slot.roundToPx(), (DockGap * u).roundToPx()) }
        val apps = (fits - DOCK_KEYS).coerceAtLeast(0)
        val entries = dockEntries(runtime, dock, apps)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = slot)
                // A pack whose dock is a bar keeps its fill behind the row; buttons and bare icons stand on the page.
                .then(if (Pack.bareIcons || Pack.dock != DockStyle.Bar) Modifier else Modifier.dockBarFill()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockCells(runtime, dock, entries, slot)
            DockKeys(state, first = entries.size, slot = slot, u = u)
        }
    }
}

/** The two buttons that end the dock: the app list, and the pen in the pack's emphasised colors. */
@Composable
internal fun DockKeys(
    state: LauncherState,
    first: Int,
    slot: Dp,
    u: Float,
) {
    DockKey("↑", "All apps", first, alt = false, slot, u) { state.open() }
    DockKey("✎", "Open drawing tools", first + 1, alt = true, slot, u) {
        // Not in a session that isn't being saved, nor on a page whose ink couldn't be read: the book says which.
        if (state.marks.book?.takes(state.currentPage) == true) state.startDrawing()
    }
}

/** One of the dock's own buttons: a mark in the pack's display voice on the dock's surface, leaning like its neighbours. */
@Composable
private fun DockKey(
    mark: String,
    label: String,
    index: Int,
    alt: Boolean,
    slot: Dp,
    u: Float,
    onClick: () -> Unit,
) {
    val pack = Pack.look.pack
    // The pen's button is the dock's own, in the pack's emphasised fill and ink.
    val surface = if (alt) pack.dock.surface.copy(fill = pack.scrap.alt.fill, ink = pack.scrap.alt.ink) else pack.dock.surface
    val buttons = Pack.dockButtons
    Box(
        Modifier
            .size(slot)
            .then(if (buttons) Modifier.surface(surface, focused = false, tilt = dockTilt(index), radius = Pack.look.radius) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(mark, style = Pack.display((18 * u).sp, if (buttons) surface.ink.toColor() else Pack.dockInk))
    }
}

/** How far the [index]th thing in the dock leans: by the pack's dock tilt, each the other way from its neighbours. */
private fun dockTilt(index: Int): Float = Pack.look.pack.dock.tilt.let { if (index % 2 == 0) -it else it }

/**
 * What stands in a dock with room for [count] apps: the pinned ones, and where the look has the prototype's
 * dock, the apps opened lately after them, so the row is full as the prototype's is.
 */
internal fun dockEntries(
    runtime: Runtime,
    dock: DockStore,
    count: Int,
): List<DockEntry> {
    val pinned = dock.items.take(count)
    if (!Pack.ownsPage || pinned.size >= count) return pinned
    val keys = dock.items.mapTo(HashSet()) { it.key }
    return pinned + recentApps(runtime, count + keys.size).map { it.dockEntry() }.filter { it.key !in keys }.take(count - pinned.size)
}

/** [entries] for a row or a column to lay out: each app's own icon, bare, [slot] across. */
@Composable
internal fun DockCells(
    runtime: Runtime,
    dock: DockStore,
    entries: List<DockEntry>,
    slot: Dp = DockSlot,
) {
    val pinned = dock.items
    entries.forEachIndexed { index, entry ->
        // A pinned app moves and unpins; one that is only here because it was opened lately can be pinned.
        val actions = {
            if (pinned.any { it.key == entry.key }) {
                pinned.actions(entry.key).map { action -> action.label to { dock.act(action, entry.key) } }
            } else {
                listOf(dockActionLabel(false) to { dock.pin(entry) })
            }
        }
        val shortcut = remember(entry) { entry.shortcut() }
        val tilt = dockTilt(index)
        // Only the picture leans; where it is touched stays square.
        Box(Modifier.graphicsLayer { rotationZ = tilt }) {
            StripCell(
                label = entry.label,
                icon = runtime.shortcutIcon(shortcut, mono = false),
                mask = runtime.shortcutIcon(shortcut, mono = true),
                onClick = { runtime.open(shortcut) },
                actions = actions,
                glyph = if (entry.linux?.exec == FILES_COMMAND) Icons.Folder else null,
                spoken = spokenLabel(entry.label, entry.profile),
                paused = runtime.androidApps.paused(entry.profile),
                size = slot,
                cell = slot,
            )
        }
    }
}

/** The app list's button and the pen's. */
private const val DOCK_KEYS = 2

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
