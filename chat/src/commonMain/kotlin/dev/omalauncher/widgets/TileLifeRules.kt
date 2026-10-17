package dev.omalauncher.widgets

import dev.omalauncher.assist.ThreadState

/*
 * How long a thread's tile and tab live, shared with the Mac app; the tiles themselves are in AgentTiles.kt.
 */

/**
 * What becomes of the tile of a thread in [state] that was last busy at [busy] (null if it never was, as far
 * as the phone knows). It never leaves while the thread works, has requests queued or waits for his OK. Idle, or
 * ended in a failure that nothing is waiting on, it stays [AGENT_TILE_IDLE_HOURS], faded for the last
 * [AGENT_TILE_FADE_HOURS], then goes: a red tile that stayed until he sent that thread something again would pile up.
 */
fun tileLife(state: ThreadState, busy: Long?, now: Long): TileLife {
    if (state.active && state != ThreadState.Attention) return TileLife.Stays
    if (state == ThreadState.Attention && busy == null) return TileLife.Stays
    val idle = now - (busy ?: return TileLife.Gone)
    return when {
        idle >= AGENT_TILE_IDLE_HOURS * HOUR_MS -> TileLife.Gone
        idle >= (AGENT_TILE_IDLE_HOURS - AGENT_TILE_FADE_HOURS) * HOUR_MS -> TileLife.Fading
        else -> TileLife.Stays
    }
}

/**
 * Whether a thread is shown at all, by its dot and tab in the chat and its tile on home alike: [archived] never,
 * [pinned] always, any other by [tileLife]. [busy] is when it was last busy ([lastBusy]).
 */
fun threadLife(state: ThreadState, busy: Long?, now: Long, pinned: Boolean, archived: Boolean): TileLife = when {
    archived -> TileLife.Gone
    pinned -> TileLife.Stays
    else -> tileLife(state, busy, now)
}

/** Whether a tile shows, and how. */
enum class TileLife { Stays, Fading, Gone }

private const val HOUR_MS = 3_600_000L

/** How long a thread's tile stays once the thread is idle. */
const val AGENT_TILE_IDLE_HOURS = 12

/** How long before it leaves a tile is drawn faded, so its going is not a surprise. */
const val AGENT_TILE_FADE_HOURS = 1
