package dev.omalauncher.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One process-wide feed, independent of whether a home tile is visible. Desktop is explicitly unconfigured. */
object ConnectionsSource {
    /**
     * The time freshness is judged against: the real clock, read when the view draws and again as it stays open. Not the
     * home look's tick, which only moves when home looks and left every check that landed after it reading Stale. A test sets it.
     */
    var clock: () -> Long = { System.currentTimeMillis() }
    var snapshot by mutableStateOf(ConnectionsSnapshot())
    var checking by mutableStateOf(false)
    var repair by mutableStateOf<String?>(null)
    var guide by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var diagnostic by mutableStateOf<String?>(null)
    var alerts by mutableStateOf("Monitoring is not configured on this screen.")
    var enabled by mutableStateOf(false)
    var paused by mutableStateOf(false)
    /** herd's machines, read on the tailnet while the sheet is open (set by the phone; the desktop has none and the section says so). */
    var readMachines: suspend () -> dev.omalauncher.canvas.HerdSnapshot? = { null }
    var refresh: () -> Unit = {}
    var restore: () -> Unit = {}
    var toggleAlerts: () -> Unit = {}
    var togglePause: () -> Unit = {}
}
