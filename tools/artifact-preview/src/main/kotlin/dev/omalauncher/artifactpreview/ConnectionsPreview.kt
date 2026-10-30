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

/** Fictional source confined to the preview APK. No live monitor, credentials, notifications or network runs; fake runner transport exercises the real wire client. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionsPreview() {
    var shape by remember { mutableStateOf("Small") }
    var scenario by remember { mutableStateOf("Outage") }
    var receipt by remember { mutableStateOf("Fixture ready") }
    val now = remember { System.currentTimeMillis() }
    var clock by remember { mutableStateOf(now) }
    val ledger = remember { ConnectionAlerts() }
    // Stamped when each scenario is shown, as the monitor stamps a check when it lands.
    fun snapshot(state: ConnectionState = ConnectionState.Failed, modelsTrouble: Boolean = false): ConnectionsSnapshot = System.currentTimeMillis().let { clock -> ConnectionsSnapshot(Capability.entries.filter { it != Capability.Models }.map { c ->
        val affected = c in setOf(Capability.Agents, Capability.Builds)
        ConnectionReading(c, if (affected) state else ConnectionState.Ready,
            when { affected -> "Fictional runner evidence; agent runs and builds share one incident."
                c == Capability.Canvas -> "Fictional CORE Design Canvas board and workspace sync verified."
                c == Capability.Optional -> "Fictional optional tools available."
                else -> "Fictional end-to-end capability verified." }, clock, if (affected) "runner:fixture" else null, if (affected) "Fictional Mac" else "",
            if (affected) "fictional-mac" else "")
    } + ConnectionReading(Capability.Network, ConnectionState.Ready, "Fictional route to the Mac responded; capability status is checked separately.",
        clock, "network", "Fictional Mac", "fictional-mac") + fictionalModels(clock, modelsTrouble), clock) }
    fun show(s: String) {
        scenario = s; ConnectionsSource.repair = null; ConnectionsSource.guide = null
        ConnectionsSource.diagnostic = org.json.JSONObject().put("schema", 1).put("service", "fictional-runner")
            .put("host", "Fictional Mac").put("stage", s).put("root_cause", "fixture_only")
            .put("id", "$clock-0000000000000000").put("time", clock).put("last_seen", clock).put("occurrences", 1)
            .put("summary", "Fictional check evidence only; no live service or credentials.").put("checks_truncated", false)
            .put("checks", org.json.JSONArray().put(org.json.JSONObject().put("check", "fixture_probe").put("target", "fictional-mac/soma").put("code", 503).put("result", "fail")
                .put("fields", org.json.JSONObject().put("fixture_only", "true")).put("stderr_tail", "")))
            .put("next_steps", org.json.JSONArray().put(org.json.JSONObject().put("actor", "agent").put("action", "inspect_fixture").put("reason", "Fictional evidence; no live action requested."))).toString(2)
        ConnectionsSource.snapshot = snapshot(when (s) {
            "Auth check" -> ConnectionState.Auth
            "Stale check" -> ConnectionState.Ready
            "Pause checks" -> ConnectionState.Paused
            "Models trouble" -> ConnectionState.Ready
            else -> ConnectionState.Failed
        }, modelsTrouble = s == "Models trouble").let { if (s == "Stale check") it.copy(readings = it.readings.map { r -> r.copy(checkedAt = clock - 100_000) }) else it }
    }
    // The monitor's rounds, as the real one lands a fresh check every half minute: the walk leaves the sheet open past the 90 s limit.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            if (scenario == "Stale check") continue
            val t = System.currentTimeMillis()
            ConnectionsSource.snapshot = ConnectionsSource.snapshot.let { it.copy(readings = it.readings.map { r -> r.copy(checkedAt = t) }, checkedAt = t) }
        }
    }
    LaunchedEffect(Unit) {
        show("Outage")
        ConnectionsSource.restore = {
            ConnectionsSource.repair = if (scenario in setOf("Active jobs", "Failed repair", "Successful repair")) {
                val runner = ConnectionsFakeRunner(scenario)
                val result = runner.run()
                ConnectionsSource.diagnostic = org.json.JSONObject().put("fixture_only", true).put("stage", result.stage)
                    .put("verified", result.verified).put("restart_posts", runner.posts).put("backend_calls", runner.backendCalls)
                    .put("fictional_owner_signature_verified", true).toString(2)
                when {
                    result.restored -> { ConnectionsSource.snapshot = snapshot(ConnectionState.Ready); "Restored: fictional authenticated agent access and build connection verified." }
                    result.stage == "blocked" -> "Blocked: active or queued fictional work; nothing restarted."
                    else -> "Failed: fictional service restart did not pass authenticated verification. Not restored."
                }
            } else restoreGuard(null, null, false)
            receipt = "Restore utility used; detail stays closed"
        }
        ConnectionsSource.refresh = { receipt = "Fixture checked again" }
        ConnectionsSource.toggleAlerts = { ConnectionsSource.enabled = !ConnectionsSource.enabled; receipt = "Fixture alert setting changed; no Android notification" }
        ConnectionsSource.togglePause = { ConnectionsSource.paused = !ConnectionsSource.paused; receipt = "Fixture pause changed" }
        ConnectionsSource.alerts = "Fixture only: no real alerts or configuration changes."
        // The cards' sample state: outage alerts on (the walk turns them off and on again).
        ConnectionsSource.enabled = true
    }
    Column(Modifier.fillMaxSize().background(Pack.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Connections fixture · no live actions", style = Pack.mono(Pack.size.small))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in listOf("Small", "Medium", "Large")) FixtureAction(s) { shape = s }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf("Outage", "Auth check", "Stale check", "Active jobs", "Failed repair", "Successful repair", "Pause checks", "Models trouble")) FixtureAction(s) { show(s) }
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

/** The phone's agents, fictional: Claude Code and Codex (GPT-6.1 Terra) healthy, Grok with no run yet; "Models trouble" adds a Codex error and a missing Claude... */
private fun fictionalModels(now: Long, trouble: Boolean): List<ConnectionReading> {
    fun turn(agent: String, status: dev.omalauncher.oma.TaskStatus, result: String, builder: String = "") =
        dev.omalauncher.oma.ChatTask(now - 1000, agent, "fictional", status = status, result = result, builder = builder)
    val installed = listOf(InstalledAgent("claude", "Claude Code"), InstalledAgent("codex", "Codex"), InstalledAgent("grok", "Grok"))
    val tasks = listOf(turn("Claude Code", dev.omalauncher.oma.TaskStatus.Done, "fine"),
        if (trouble) turn("Codex", dev.omalauncher.oma.TaskStatus.Failed, "You've hit your usage limit. Try again later.", "D") else turn("Codex", dev.omalauncher.oma.TaskStatus.Done, "fine", "D"))
    val needs = if (trouble) listOf(AgentNeed("grok", "Grok", listOf("the front agent")), AgentNeed("opencode", "OpenCode", listOf("Phone C")))
        else listOf(AgentNeed("grok", "Grok", listOf("the front agent")))
    return phoneModelReadings(installed, needs, tasks, now) { t -> if (t.agent == "Codex" && t.builder == "D") prettyModel("gpt-6.1-terra") else null }
}
