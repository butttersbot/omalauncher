package dev.omalauncher.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One process-wide feed, independent of whether a home tile is visible. Desktop is explicitly unconfigured. */
object ConnectionsSource {
    var snapshot by mutableStateOf(ConnectionsSnapshot())
    var checking by mutableStateOf(false)
    var repair by mutableStateOf<String?>(null)
    var guide by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var diagnostic by mutableStateOf<String?>(null)
    var alerts by mutableStateOf("Monitoring is not configured on this screen.")
    var enabled by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var refresh: () -> Unit = {}
    var restore: () -> Unit = {}
    var toggleAlerts: () -> Unit = {}
    var togglePause: () -> Unit = {}
}
