package dev.omalauncher.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.omalauncher.canvas.LocalModuleFocus
import dev.omalauncher.canvas.ModuleSize
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.scrap

@Composable
private fun ConnectionText(text: String, small: Boolean = false, muted: Boolean = false, lines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = if (small) Pack.mono(Pack.size.small, if (muted) Pack.muted else Pack.foreground)
        else Pack.body(Pack.size.body, if (muted) Pack.muted else Pack.foreground), maxLines = lines, overflow = TextOverflow.Ellipsis)
}
@Composable
private fun ConnectionAction(label: String, action: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 44.dp).clickable(onClick = action).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        BasicText("[ $label ]", style = Pack.mono(Pack.size.small, Pack.accent))
    }
}
@Composable
fun ConnectionsModule(size: ModuleSize, now: Long, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val rows = ConnectionsSource.snapshot.rows(now)
    val incidents = ConnectionsSource.snapshot.incidents(now)
    val bad = incidents.firstOrNull()?.readings?.firstOrNull()
    BoxWithConstraints(modifier.widthIn(max = 475.dp).heightIn(max = if (size == ModuleSize.Full) 300.dp else 180.dp)
        .scrap(focused = LocalModuleFocus.current, key = "connections")
        .clickable { expanded = true }.semantics { contentDescription = "Connections module" }.padding(12.dp)) {
        val small = maxWidth < 280.dp
        val large = maxHeight > 190.dp && !small
        val showSummary = maxHeight >= 140.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText(Pack.heading("Connections"), style = Pack.display(if (small) 14.sp else 15.sp), maxLines = 1)
                ConnectionText(if (incidents.isNotEmpty()) "△ ${incidents.size}" else if (rows.all { it.state == ConnectionState.Ready || it.state == ConnectionState.Disabled }) "✓" else "?", small = true)
            }
            if (showSummary) {
                ConnectionText(ConnectionsSource.repair ?: bad?.let { "${it.capability.title} unavailable" } ?: if (ConnectionsSource.checking) "Checking connections…" else "Connection check", lines = 1)
                ConnectionText(bad?.let { listOf(it.host, it.state.label).filter(String::isNotBlank).joinToString(" · ") }
                    ?: if (ConnectionsSource.snapshot.checkedAt == 0L) "Waiting for a real check" else "Tap for verified details", small = true, muted = true, lines = 1)
            }
            val bands = if (small) listOf(Capability.Network, Capability.Agents, Capability.Canvas)
                else listOf(Capability.Network, Capability.Agents, Capability.Canvas, Capability.Phone, Capability.Sync)
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                for (capability in bands) {
                    val row = rows.first { it.capability == capability }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).semantics { contentDescription = "${capability.title}: ${row.state.label}" }) {
                        ConnectionText(row.state.mark)
                        ConnectionText(capability.band.orEmpty(), small = true, lines = 1)
                    }
                }
            }
            if (large) for (capability in listOf(Capability.Network, Capability.Phone, Capability.Sync, Capability.Canvas)) {
                val row = rows.first { it.capability == capability }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ConnectionText(capability.title, small = true, lines = 1, modifier = Modifier.weight(1f))
                    ConnectionText("${row.state.mark} ${row.state.label}", small = true, lines = 1)
                }
            }
            // A child clickable consumes the tap: Restore does not open the detail modal.
            ConnectionAction(if (ConnectionsSource.checking) "Checking…" else "Restore", { ConnectionsSource.restore() })
        }
    }
    if (expanded) ConnectionsDialog(now) { expanded = false }
}

/** Standard modal shell v1: fixed 80dp header, equal 44dp side slots and a scrolling body. */
@Composable
fun ConnectionsDialog(now: Long, close: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 600.dp
            val inset = if (!wide) 16.dp else 20.dp
            Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().heightIn(max = maxHeight)
                .background(Pack.background, Pack.shape()).border(Pack.hair, Pack.border, Pack.shape())) {
                Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(44.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        ConnectionText("Soma / systems", small = true, muted = true)
                        BasicText("Connections", style = Pack.display(22.sp).copy(textAlign = TextAlign.Center))
                    }
                    Box(Modifier.size(44.dp).focusRequester(closeFocus).clickable(onClick = close)
                        .semantics { contentDescription = "Close Connections" }, contentAlignment = Alignment.Center) {
                        BasicText("×", style = Pack.body(26.sp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val snapshot = ConnectionsSource.snapshot
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { ConnectionsRepair(snapshot, now) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { ConnectionsInventory(snapshot, now) }
                    } else {
                        ConnectionsRepair(snapshot, now)
                        ConnectionsInventory(snapshot, now)
                    }
                    ConnectionAction(if (ConnectionsSource.enabled) "Outage alerts: on" else "Enable outage alerts", { ConnectionsSource.toggleAlerts() })
                    ConnectionText(ConnectionsSource.alerts, small = true, muted = true)
                    ConnectionAction(if (ConnectionsSource.paused) "Resume monitoring" else "Pause monitoring for 1 hour", { ConnectionsSource.togglePause() })
                    ConnectionText("Unexpected failures wait 60 seconds. One notice per incident, then a verified recovery notice. Intentional pauses stay quiet. No reboot or security setting is changed.", small = true, muted = true)
                }
            }
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}
@Composable
private fun ConnectionsRepair(snapshot: ConnectionsSnapshot, now: Long) {
    val incidents = snapshot.incidents(now)
    ConnectionText(if (incidents.isEmpty()) "Connection check" else "${incidents.size} incident${if (incidents.size == 1) "" else "s"}")
    for (incident in incidents) {
        Column(Modifier.fillMaxWidth().border(Pack.hair, Pack.border).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectionText(incident.readings.map { it.host }.filter(String::isNotBlank).distinct().joinToString(" · ").ifEmpty { "This phone" }, small = true)
            ConnectionText(incident.readings.joinToString(" + ") { it.capability.title })
            ConnectionText(incident.readings.first().detail, muted = true)
        }
    }
    ConnectionAction(if (ConnectionsSource.checking) "Checking…" else "Restore", { ConnectionsSource.restore() })
    ConnectionsSource.repair?.let { ConnectionText(it) }
    var details by remember { mutableStateOf(false) }
    ConnectionAction("Details for agents", { details = !details })
    if (details) ConnectionText(ConnectionsSource.diagnostic?.take(24_000) ?: "No diagnostic record yet. Use Check again.", small = true, muted = true)
    ConnectionText("01  Check device & active jobs\n02  Target the affected service\n03  Verify authenticated capabilities", small = true, muted = true)
    ConnectionAction("Check again", { ConnectionsSource.refresh() })
}
@Composable
private fun ConnectionsInventory(snapshot: ConnectionsSnapshot, now: Long) {
    ConnectionText("All capabilities · 12 groups", small = true)
    for (row in snapshot.rows(now)) ConnectionDisclosure(row)
}
@Composable
private fun ConnectionDisclosure(row: ConnectionReading) {
    var open by remember(row.capability) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(Pack.hair, Pack.border).padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            ConnectionText("${if (open) "▾" else "▸"} ${row.capability.title}", modifier = Modifier.weight(1f))
            ConnectionText("${row.state.mark} ${row.state.label}", small = true)
        }
        if (open) ConnectionText(row.detail, muted = true, modifier = Modifier.padding(bottom = 12.dp))
    }
}
