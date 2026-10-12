package dev.omalauncher.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.layout.dockBeside

/** Where landscape home keeps the recent apps strip and the dock: the side portrait's bottom turned to. */
enum class HomeSide { Left, Right }

/**
 * The side for the strip and the dock in a phone's landscape, which is too short for them below the page; null
 * everywhere else: upright, and on an unfolded foldable or a tablet either way up. The page itself is never turned.
 */
@Composable
internal fun homeSide(): HomeSide? {
    val view = LocalView.current
    // Turning straight from one landscape to the other changes no size, so nothing would recompose: ask the display.
    var rotation by remember(view) { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view) {
        val displays = view.context.getSystemService(DisplayManager::class.java)
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) {
                    view.display?.let { rotation = it.rotation }
                }

                override fun onDisplayAdded(displayId: Int) {}

                override fun onDisplayRemoved(displayId: Int) {}
            }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    val window = LocalWindowInfo.current.containerSize
    val beside = with(LocalDensity.current) { dockBeside(window.width.toDp().value, window.height.toDp().value) }
    return when {
        !beside -> null
        rotation == Surface.ROTATION_270 -> HomeSide.Left
        else -> HomeSide.Right
    }
}

/**
 * Room the side takes beside the grid: the strip, the rail and the gaps after them (the grid's own gap comes before).
 * Added up in whole pixels, each part rounded as [SideRail] lays it out, so the grid ends exactly where the strip starts.
 */
@Composable
internal fun sideWidth(): Dp =
    with(LocalDensity.current) {
        val gap = HomeGap.roundToPx()
        ((if (LauncherStyle.recentApps) RecentStripHeight.roundToPx() + gap else 0) + PromptBarHeight.roundToPx() + gap).toDp()
    }

/**
 * Landscape home's side on a phone: the recent apps strip stands beside the page with its icons upright,
 * and the dock stands in a rail at the edge.
 */
@Composable
internal fun SideRail(
    runtime: Runtime,
    state: LauncherState,
    handlers: AppHandlers,
    dock: DockStore,
    side: HomeSide,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxHeight().padding(vertical = 6.dp),
        horizontalArrangement = if (side == HomeSide.Left) Arrangement.Start else Arrangement.End,
    ) {
        // Outward from the grid, like portrait from the grid down: the strip, then the dock.
        val strip = @Composable { if (LauncherStyle.recentApps) RecentStrip(runtime, handlers, vertical = true) }
        if (side == HomeSide.Left) {
            Spacer(Modifier.width(6.dp))
            DockRail(runtime, state, dock)
            Spacer(Modifier.width(6.dp))
            strip()
        } else {
            strip()
            Spacer(Modifier.width(6.dp))
            DockRail(runtime, state, dock)
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * The dock stood on end: its pinned apps down the rail, as many as fit, and the agent button below them.
 * A tap on the rail between the icons opens the app list. While the button is held, how loud it hears
 * runs up the rail's edge; the rail is too narrow for the words.
 */
@Composable
private fun DockRail(
    runtime: Runtime,
    state: LauncherState,
    dock: DockStore,
) {
    val voice = rememberAgentVoice()
    val listening = voice.speech.listening
    val accent = Palette.accent
    Column(
        Modifier
            .width(PromptBarHeight)
            .fillMaxHeight()
            .tile(listening)
            .drawWithContent {
                drawContent()
                if (listening) {
                    val h = size.height * (0.05f + 0.95f * voice.level.value)
                    drawRect(accent, Offset(0f, size.height - h), Size(3.dp.toPx(), h))
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The click is the icons' area's, not the whole rail's: the agent button's taps are its own.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clickable { state.open() }.padding(vertical = DockGap)) {
            val count = with(LocalDensity.current) { dockFits(constraints.maxHeight, DockSlot.roundToPx(), DockGap.roundToPx()) }
            Column(verticalArrangement = Arrangement.spacedBy(DockGap)) { DockCells(runtime, dock, count) }
        }
        AgentButton(runtime, state, voice)
    }
}
