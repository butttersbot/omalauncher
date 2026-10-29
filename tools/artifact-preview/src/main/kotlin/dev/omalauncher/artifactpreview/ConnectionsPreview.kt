package dev.omalauncher.artifactpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.canvas.ModuleSize
import dev.omalauncher.connections.*
import dev.omalauncher.ui.Pack

/** Fictional source confined to the preview APK. No monitor, credentials, notification or network code runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionsPreview() {
    var shape by remember { mutableStateOf("Small") }
    var scenario by remember { mutableStateOf("Outage") }
    var receipt by remember { mutableStateOf("Fixture ready") }
    val now = remember { System.currentTimeMillis() }
    var clock by remember { mutableStateOf(now) }
    val ledger = remember { ConnectionAlerts() }
    fun snapshot(state: ConnectionState = ConnectionState.Failed): ConnectionsSnapshot = ConnectionsSnapshot(Capability.entries.map { c ->
        val affected = c in setOf(Capability.Agents, Capability.Builds)
        ConnectionReading(c, if (affected) state else if (c == Capability.Optional) ConnectionState.Disabled else ConnectionState.Ready,
            when { affected -> "Fictional runner evidence; agent runs and builds share one incident."
                c == Capability.Canvas -> "Fictional CORE Design Canvas board and workspace sync verified."
                c == Capability.Optional -> "Fictional optional tools disabled."
                else -> "Fictional end-to-end capability verified." }, clock, if (affected) "runner:fixture" else null, if (affected) "Fictional Mac" else "")
    }, clock)
    fun show(s: String) {
        scenario = s; ConnectionsSource.repair = null; ConnectionsSource.guide = null
        ConnectionsSource.diagnostic = org.json.JSONObject().put("schema", 1).put("service", "fictional-runner")
            .put("host", "Fictional Mac").put("stage", s).put("root_cause", "fixture_only")
            .put("checks", org.json.JSONArray().put(org.json.JSONObject().put("check", "fixture_probe").put("code", 503)))
            .put("next_steps", org.json.JSONArray().put(org.json.JSONObject().put("actor", "agent").put("action", "inspect_fixture"))).toString(2)
        ConnectionsSource.snapshot = snapshot(when (s) {
            "Auth check" -> ConnectionState.Auth
            "Stale check" -> ConnectionState.Ready
            "Pause checks" -> ConnectionState.Paused
            else -> ConnectionState.Failed
        }).let { if (s == "Stale check") it.copy(readings = it.readings.map { r -> r.copy(checkedAt = clock - 100_000) }) else it }
    }
    LaunchedEffect(Unit) {
        show("Outage")
        ConnectionsSource.restore = {
            ConnectionsSource.repair = when (scenario) {
                "Active jobs" -> restoreGuard(1, 0, true)
                "Failed repair" -> "Failed: fictional service restart did not pass authenticated verification. Not restored."
                "Successful repair" -> {
                    ConnectionsSource.snapshot = snapshot(ConnectionState.Ready)
                    "Restored: fictional authenticated agent access and build connection verified."
                }
                else -> restoreGuard(null, null, false)
            }
            receipt = "Restore utility used; detail stays closed"
        }
        ConnectionsSource.refresh = { receipt = "Fixture checked again" }
        ConnectionsSource.toggleAlerts = { ConnectionsSource.enabled = !ConnectionsSource.enabled; receipt = "Fixture alert setting changed; no Android notification" }
        ConnectionsSource.togglePause = { ConnectionsSource.paused = !ConnectionsSource.paused; receipt = "Fixture pause changed" }
        ConnectionsSource.alerts = "Fixture only: no real alerts or configuration changes."
    }
    Column(Modifier.fillMaxSize().background(Pack.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Connections fixture · no live actions", style = Pack.mono(Pack.size.small))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in listOf("Small", "Medium", "Large")) FixtureAction(s) { shape = s }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf("Outage", "Auth check", "Stale check", "Active jobs", "Failed repair", "Successful repair", "Pause checks")) FixtureAction(s) { show(s) }
        }
        BasicText(receipt, style = Pack.body())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageWidth = if (maxWidth > 600.dp) (maxWidth - 24.dp) / 2 else maxWidth
            Box(Modifier.width(if (shape == "Small") (pageWidth - 12.dp) / 2 else pageWidth)
                .height(if (shape == "Large") 300.dp else 180.dp)) {
                ConnectionsModule(if (shape == "Large") ModuleSize.Full else ModuleSize.Card, clock)
            }
        }
    }
}
@Composable private fun FixtureAction(label: String, action: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = action).padding(horizontal = 4.dp, vertical = 10.dp)) {
        BasicText(label, style = Pack.mono(Pack.size.small, Pack.accent))
    }
}
