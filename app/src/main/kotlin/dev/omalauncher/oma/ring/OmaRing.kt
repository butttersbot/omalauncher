package dev.omalauncher.oma.ring

import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.PackColor
import dev.omalauncher.theme.pack.PackRing
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.ThemePalette
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one place the ring's signals are kept. The agent's task and whoever takes a request report here;
 * the hosts (home, the assistant window, the overlay) only watch and draw. Safe from any thread.
 */
object OmaRing {
    @Volatile var signals = RingSignals(); private set

    /** The assistant window is up or about to be, so the overlay must be out of the way of its screenshot. */
    @Volatile var assistantUp = false; private set
    private val hearing = HashSet<Any>()
    private val watchers = CopyOnWriteArrayList<() -> Unit>()

    /** The ring's clock. */
    fun now() = SystemClock.elapsedRealtime()

    /** From the agent's task, whenever it starts, ends or its approvals change. */
    @Synchronized fun task(working: Boolean, pending: Int, succeeded: Boolean, now: Long = now()) =
        publish(signals.afterTask(working, pending, succeeded, now))

    /** [source] started or stopped taking a request; the ring listens while anyone does. */
    @Synchronized fun hearing(source: Any, on: Boolean) {
        if (on) hearing += source else hearing -= source
        publish(signals.afterHearing(hearing.isNotEmpty()))
    }

    /** The assistant window is about to show, or has gone. */
    @Synchronized fun assistant(up: Boolean) {
        if (assistantUp == up) return
        assistantUp = up
        watchers.forEach { it() }
    }

    /** Calls [watcher] on whatever thread a change is reported from; the returned function stops it. */
    fun watch(watcher: () -> Unit): () -> Unit {
        watchers += watcher
        return { watchers -= watcher }
    }

    private fun publish(next: RingSignals) {
        if (next == signals) return
        signals = next
        watchers.forEach { it() }
    }
}

@Volatile private var styled: Pair<ThemePalette, PackRing>? = null

/**
 * The ring's style from the theme pack, in the applied theme's colors. Console is the only pack the launcher
 * draws from so far; when another can be chosen, this is the one place that has to ask which.
 */
fun ringStyle(): PackRing {
    val palette = Palette.current
    styled?.let { (of, ring) -> if (of === palette) return ring }
    val ring = ConsolePack.withColors(palette.named.mapValues { PackColor(it.value.toArgb()).toString() }).ring
    styled = palette to ring
    return ring
}
