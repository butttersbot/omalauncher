package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.NEEDS_OK
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.widgets.AgentTile
import dev.omalauncher.widgets.tileWhere
import kotlinx.coroutines.delay

/** How opaque a thread's tile is in its last hour, before it leaves the page. */
internal const val AGENT_TILE_FADED = 0.62f

/** How far a working thread's dot dims at each beat, and how long half a beat takes: the mock-up's 1.2 seconds a beat. */
internal const val THREAD_DOT_DIM = 0.45f
internal const val THREAD_PULSE_MS = 600

/** How often a resting tile looks at the clock for its "2 MIN". */
private const val TILE_CLOCK_MS = 30_000L

/** How often a working tile's turn clock ticks: each second, as a terminal agent's does. */
private const val TILE_TICK_MS = 1_000L

/**
 * A thread's state as a dot, the same on its tile and in the chat's header and tabs: green and gently pulsing
 * while it works, the pack's highlight with requests queued, the accent when a step needs his OK, red when it
 * needs attention, and [idle] when it rests. The one whose tab is [here] is ringed. The pulse is the dot's
 * opacity alone, on the shared timing, and it stands still with the system's animations off.
 */
@Composable
fun ThreadDot(
    state: ThreadState,
    idle: Color,
    here: Boolean = false,
) {
    val color =
        when (state) {
            ThreadState.Working -> Pack.green
            ThreadState.Queued -> Pack.highlight
            ThreadState.NeedsOk -> Pack.accent
            ThreadState.Attention -> Pack.red
            ThreadState.Idle -> idle
        }
    val working = state == ThreadState.Working
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(working) {
        // Asked at every beat, so turning the system's animations off stops it where a snap would otherwise spin.
        while (working && !Motion.reduced) {
            pulse.moveTo(THREAD_DOT_DIM, Motion.timed(THREAD_PULSE_MS))
            pulse.moveTo(1f, Motion.timed(THREAD_PULSE_MS))
        }
        pulse.snapTo(1f)
    }
    Box(
        Modifier.size(17.dp).then(if (here) Modifier.framed(Pack.mark, Pack.foreground, radius = 9.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer { alpha = pulse.value }
                // The highlight is a marker's colour and can be lost on a light scrap: it gets the ink's hairline, as in the mock-up.
                .then(if (state == ThreadState.Queued) Modifier.framed(Pack.hair, Pack.foreground, color, radius = 5.dp) else Modifier.background(color, Pack.round(5.dp))),
        )
    }
}

/**
 * A thread's tile on home (design/oma-threads-mockup.html): where it runs and how long ago it worked, its
 * name, up to three lines of what it is doing, and its state beside a dot. One that waits for his OK wears
 * the scrap's focus outline and says so on a pill; the tile shows the tool and what it is pointed at and no
 * more, and has nothing on it that answers. A tap opens the thread's tab in the Oma chat. [tile] is null for
 * the moment between a tile being placed and its thread being looked at.
 */
@Composable
internal fun AgentTileView(
    tile: AgentTile?,
    key: String,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val thread = tile?.thread
    val state = thread?.state ?: ThreadState.Idle
    val needsOk = state == ThreadState.NeedsOk
    val name = tile?.name?.ifBlank { null } ?: "Thread"
    val counting = thread?.state?.active == true && thread.since != null
    val now by produceState(System.currentTimeMillis(), counting) {
        value = System.currentTimeMillis()
        while (true) {
            delay(if (counting) TILE_TICK_MS else TILE_CLOCK_MS)
            value = System.currentTimeMillis()
        }
    }
    Box(
        modifier
            .alpha(if (tile?.fading == true) AGENT_TILE_FADED else 1f)
            .semantics { contentDescription = "$name: ${thread?.label ?: "not heard from"}" }
            // The pack's scrap as an app's tile wears it, with no tape or title bar: only widget tiles are dressed with those.
            .scrap(focused || needsOk, key)
            .pointerInput(key) { detectTapGestures(onTap = { onTap() }) },
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (tile != null) BasicText(tileWhere(tile, now), style = Pack.mono(Pack.size.small, Pack.scrapMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(Pack.heading(name), style = Pack.display(Pack.size.large, Pack.scrapInk), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(
                thread?.doing.orEmpty(),
                Modifier.fillMaxWidth().weight(1f),
                style = Pack.body(Pack.size.small, Pack.scrapInk),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (needsOk) {
                BasicText(Pack.caption(NEEDS_OK), Modifier.background(Pack.accent, Pack.round(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp), style = Pack.mono(Pack.size.small, Pack.background), maxLines = 1)
            } else if (thread != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThreadDot(state, Pack.scrapMuted)
                    BasicText(Pack.caption(thread.label), Modifier.padding(start = 3.dp), style = Pack.mono(Pack.size.small, Pack.scrapMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
