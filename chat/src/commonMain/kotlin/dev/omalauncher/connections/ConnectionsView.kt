package dev.omalauncher.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.omalauncher.canvas.LocalModuleFocus
import dev.omalauncher.canvas.ModuleSize
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.scrap

/*
 * Built from the Canvas cards soma-widgets / Soma Connections v6: body sans for what is read, mono only for small
 * captions, green for what passed, the accent for what needs repair, hairline rules between rows.
 */
@Composable
private fun Words(text: String, style: TextStyle, lines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) =
    BasicText(text, modifier, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis)
private fun sans(size: TextUnit, color: Color = Pack.foreground, weight: FontWeight? = null) =
    Pack.body(size, color).let { if (weight == null) it else it.copy(fontWeight = weight) }
private fun caption(size: TextUnit = 10.sp, color: Color = Pack.muted) = Pack.mono(size, color)
/** The v6 CSS sets the flag, status words, section heads and step numbers in the mono face at its regular weight
 * (`font: 10px var(--font-mono)`), not the pack's bold caption voice; [tracking] as the card's em. */
private fun plainMono(size: TextUnit, color: Color = Pack.foreground, tracking: Float = 0f) =
    Pack.mono(size, color).copy(fontWeight = FontWeight.Normal, letterSpacing = tracking.em)
private val ConnectionTone.color get() = when (this) { ConnectionTone.Good -> Pack.green; ConnectionTone.Bad -> Pack.accent; ConnectionTone.Quiet -> Pack.muted }

private fun Modifier.rule(top: Boolean = false, bottom: Boolean = false) = drawBehind {
    val w = Pack.hair.toPx()
    if (top) drawLine(Pack.border, Offset(0f, w / 2), Offset(size.width, w / 2), w)
    if (bottom) drawLine(Pack.border, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}
private fun Modifier.bar(color: Color) = drawBehind { drawRect(color, Offset.Zero, Size(3.dp.toPx(), size.height)) }.padding(start = 17.dp)

/** [ Label ] in the body face and the accent, flush left, a 44dp target. */
@Composable
private fun ConnectionAction(label: String, action: () -> Unit, modifier: Modifier = Modifier, size: TextUnit = 13.sp, tight: Boolean = false) {
    Box(modifier.heightIn(min = 44.dp).clickable(onClick = action), contentAlignment = Alignment.CenterStart) {
        BasicText(if (tight) "[$label]" else "[ $label ]", style = sans(size, Pack.accent))
    }
}
/** Three bars: done in green, the current one in the accent, the rest in the pack's highlight. */
@Composable
private fun ProgressRail(stage: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 3) Box(Modifier.weight(1f).height(3.dp).background(when {
            stage > i -> Pack.green; stage == i -> Pack.accent; else -> Pack.highlight }))
    }
}
@Composable
private fun SectionHead(left: String, right: String, rightColor: Color = Pack.foreground) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Words(left.uppercase(), plainMono(10.sp, Pack.foreground, .07f), 1)
        Words(right.uppercase(), plainMono(10.sp, rightColor, .07f), 1)
    }
}
private fun repairCardSummary(text: String): String = when {
    text.startsWith("Restored", ignoreCase = true) -> "Service restored"
    text.startsWith("Failed", ignoreCase = true) || text.startsWith("Restore failed", ignoreCase = true) -> "Repair failed"
    text.contains("unverified", ignoreCase = true) || text.startsWith("Restore interrupted", ignoreCase = true) -> "Result unverified"
    text.contains("blocked", ignoreCase = true) || text.startsWith("No verified", ignoreCase = true) -> "Repair blocked"
    text.startsWith("Owner approval", ignoreCase = true) -> "Owner confirmation"
    text.startsWith("Verifying", ignoreCase = true) -> "Verifying repair…"
    text.startsWith("Checking", ignoreCase = true) -> "Checking repair…"
    text.startsWith("Already", ignoreCase = true) || text.contains("in progress", ignoreCase = true) -> "Repair in progress…"
    else -> text
}

@Composable
fun ConnectionsModule(size: ModuleSize, now: Long, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val snapshot = ConnectionsSource.snapshot
    val rows = snapshot.rows(now)
    val incidents = snapshot.incidents(now)
    val worst = incidents.firstOrNull()
    val bad = worst?.readings?.firstOrNull()
    val allReady = rows.all { it.state == ConnectionState.Ready || it.state == ConnectionState.Disabled }
    BoxWithConstraints(modifier.widthIn(max = 475.dp).heightIn(max = if (size == ModuleSize.Full) 300.dp else 180.dp)
        .scrap(focused = LocalModuleFocus.current, key = "connections")
        .clickable { expanded = true }.semantics { contentDescription = "Connections module" }
        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp)) {
        val small = maxWidth < 280.dp
        val large = maxHeight > 190.dp && !small
        val showSummary = maxHeight >= 140.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (small) 3.dp else 5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = if (small) Alignment.Top else Alignment.CenterVertically) {
                BasicText(Pack.heading("Connections"), style = Pack.display(if (small) 14.sp else 15.sp), maxLines = 1)
                val (flag, tone) = when {
                    incidents.isNotEmpty() -> "△ ${incidents.size}" to ConnectionTone.Bad
                    allReady -> "✓" to ConnectionTone.Good
                    else -> "?" to ConnectionTone.Quiet
                }
                Words(flag, plainMono(11.sp, tone.color), 1)
            }
            if (showSummary) {
                // Restored and verified: the v6 healthy card, "Everything is ready" over what came back.
                val restored = incidents.isEmpty() && allReady && repairStage(ConnectionsSource.repair, ConnectionsSource.checking) == 3
                Words(if (restored) "Everything is ready" else ConnectionsSource.repair?.let(::repairCardSummary) ?: bad?.let { "${it.capability.title} unavailable" }
                    ?: if (ConnectionsSource.checking) "Checking connections…" else if (allReady && snapshot.checkedAt > 0) "Everything is ready" else "Connection check",
                    sans(if (small) 14.sp else 16.sp, weight = FontWeight.SemiBold), 1)
                Words(if (restored) "Service restored · tap for details" else bad?.let { connectionCause(it, worst.key) }
                    ?: if (snapshot.checkedAt == 0L) "Waiting for a real check" else "Tap for verified details",
                    sans(if (small) 11.sp else 12.sp, Pack.muted), if (small) 2 else 1)
            }
            if (large) {
                ProgressRail(repairStage(ConnectionsSource.repair, ConnectionsSource.checking), Modifier.widthIn(max = 170.dp).fillMaxWidth().padding(top = 3.dp))
                val lines = listOf(Capability.Network, Capability.Phone, Capability.Sync, Capability.Canvas)
                Column(Modifier.fillMaxWidth().padding(top = 5.dp).rule(top = true)) {
                    lines.forEachIndexed { i, capability ->
                        val row = rows.first { it.capability == capability }
                        val status = connectionStatus(row, incidents, card = true)
                        Row(Modifier.fillMaxWidth().rule(bottom = i < lines.lastIndex).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Words(capability.title, sans(12.sp), 1, Modifier.weight(1f))
                            Words(status.text, sans(12.sp, status.tone.color), 1)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                val bands = if (small) listOf(Capability.Network, Capability.Agents, Capability.Canvas)
                    else listOf(Capability.Network, Capability.Agents, Capability.Canvas, Capability.Phone, Capability.Sync)
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    for (capability in bands) {
                        val row = rows.first { it.capability == capability }
                        val status = connectionStatus(row, incidents, card = true)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (small) 3.dp else 4.dp),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "${capability.title}: ${status.word}" }) {
                            Words(row.state.mark, sans(if (row.state.mark == "✓") 17.sp else 26.sp, status.tone.color), 1, Modifier.height(24.dp).wrapContentHeight())
                            Words(capability.band.orEmpty(), sans(10.sp, weight = FontWeight.Medium), 1)
                        }
                    }
                }
            }
            // A child clickable consumes the tap: Restore does not open the detail modal.
            // Small card (v6 .small .controls: 8px insets): the rule and [Restore] run 4dp wider than the text column.
            val reach = if (small) 4.dp else 0.dp
            Box(Modifier.fillMaxWidth().layout { m, c ->
                val extra = (reach * 2).roundToPx()
                val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                layout(c.maxWidth, p.height) { p.place(-reach.roundToPx(), 0) }
            }.height(45.dp).rule(top = true), contentAlignment = Alignment.CenterStart) {
                ConnectionAction(if (ConnectionsSource.checking) "Checking…" else "Restore", { ConnectionsSource.restore() }, size = if (small) 11.sp else 12.sp, tight = small)
            }
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
            // Two columns from 600dp: unfolded landscape and the inner screen held upright (Canvas: Expanded · Unfolded upright).
            val wide = maxWidth >= 600.dp
            val inset = if (!wide) 16.dp else 20.dp
            Column(Modifier.widthIn(max = if (wide) 880.dp else 447.dp).fillMaxWidth().heightIn(max = maxHeight)
                .background(Pack.background, Pack.shape()).border(Pack.hair, Pack.border, Pack.shape())) {
                Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).rule(bottom = true).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(44.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Words(Pack.caption("Soma / systems"), caption(10.sp))
                        BasicText(Pack.heading("Connections"), style = Pack.display(22.sp).copy(textAlign = TextAlign.Center))
                    }
                    Box(Modifier.size(44.dp).focusRequester(closeFocus).clickable(onClick = close)
                        .semantics { contentDescription = "Close Connections" }, contentAlignment = Alignment.Center) {
                        BasicText("×", style = Pack.body(26.sp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(inset)) {
                    val snapshot = ConnectionsSource.snapshot
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { ConnectionsRepair(snapshot, now) }
                        Column(Modifier.weight(1f)) { ConnectionsInventory(snapshot, now) }
                    } else {
                        ConnectionsRepair(snapshot, now)
                        Spacer(Modifier.height(24.dp))
                        ConnectionsInventory(snapshot, now)
                    }
                    ConnectionsFooter()
                }
            }
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}
@Composable
private fun ConnectionsRepair(snapshot: ConnectionsSnapshot, now: Long) {
    val incidents = snapshot.incidents(now)
    val stage = repairStage(ConnectionsSource.repair, ConnectionsSource.checking)
    SectionHead("Connection check", when {
        incidents.isNotEmpty() -> "△ ${incidents.size} incident${if (incidents.size == 1) "" else "s"}"
        stage == 3 -> "✓ Restored"
        else -> if (snapshot.rows(now).all { it.state == ConnectionState.Ready || it.state == ConnectionState.Disabled }) "✓ All ready" else "No incident"
    }, if (incidents.isNotEmpty()) Pack.accent else Pack.green)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (incident in incidents) {
            val copy = incidentCopy(incident, snapshot, now)
            Column(Modifier.fillMaxWidth().bar(Pack.accent)) {
                Words(Pack.caption(copy.caption), caption(10.sp))
                Words(copy.headline, sans(19.sp, weight = FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                Words(copy.impact, sans(13.sp).copy(lineHeight = 19.5.sp))
                RestoreBlock(stage, copy.steps)
                Words(copy.note, Pack.hand(13.sp), modifier = Modifier.padding(top = 10.dp))
                AgentTools()
            }
        }
        if (incidents.isEmpty()) {
            val ready = snapshot.rows(now).all { it.state == ConnectionState.Ready || it.state == ConnectionState.Disabled }
            Column(Modifier.fillMaxWidth().bar(if (ready) Pack.green else Pack.muted)) {
                Words(Pack.caption("All devices"), caption(10.sp))
                Words(if (stage == 3) "Back in working order." else if (ready) "Everything is ready." else "No confirmed failure.",
                    sans(19.sp, weight = FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                Words(if (ready) "Every capability passed its last check." else "Some checks have no fresh evidence yet; they are marked in the list.", sans(13.sp))
                ConnectionsSource.repair?.let { Words(it, sans(13.sp), modifier = Modifier.padding(top = 8.dp)) }
                AgentTools()
            }
        }
    }
}
@Composable
private fun RestoreBlock(stage: Int, steps: List<String>) {
    ConnectionAction(if (ConnectionsSource.checking) "Checking…" else "Restore", { ConnectionsSource.restore() }, size = 16.sp)
    ConnectionsSource.repair?.let { Words(it, sans(13.sp), modifier = Modifier.padding(bottom = 8.dp)) }
    ProgressRail(stage, Modifier.fillMaxWidth())
    Column(Modifier.padding(top = 12.dp)) {
        steps.forEachIndexed { i, step ->
            val done = stage > i
            val current = stage == i
            val color = if (done) Pack.green else if (current) Pack.foreground else Pack.muted
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Words(if (done) "✓" else "0${i + 1}", plainMono(11.sp, color), 1, Modifier.widthIn(min = 18.dp))
                Words(step, sans(12.sp, color, if (current) FontWeight.SemiBold else null))
            }
        }
    }
}
/** [ Check again ] under the repair. Agents read the same record with `oma connections incident`: no button for them
 * on his widget (Boss, 2026-10-29: "these widget controls are really just for me"). */
@Composable
private fun AgentTools() {
    ConnectionAction("Check again", { ConnectionsSource.refresh() }, Modifier.padding(top = 4.dp))
}
@Composable
private fun ConnectionsInventory(snapshot: ConnectionsSnapshot, now: Long) {
    val rows = snapshot.rows(now)
    val incidents = snapshot.incidents(now)
    SectionHead("All capabilities", "${rows.size} groups")
    for (row in rows) ConnectionDisclosure(row, connectionStatus(row, incidents))
}
@Composable
private fun ConnectionDisclosure(row: ConnectionReading, status: ConnectionStatus) {
    var open by remember(row.capability) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().rule(bottom = true)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }
            .semantics { contentDescription = "${row.capability.title}: ${status.word}" }, verticalAlignment = Alignment.CenterVertically) {
            Words(row.capability.title, sans(13.sp), modifier = Modifier.weight(1f))
            Words(status.text, plainMono(10.sp, status.tone.color), 1)
        }
        if (open) Words(row.detail, sans(12.sp, Pack.muted), modifier = Modifier.padding(bottom = 12.dp))
    }
}
@Composable
private fun ConnectionsFooter() {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).rule(top = true).padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(ConnectionsSource.enabled, role = Role.Checkbox) { ConnectionsSource.toggleAlerts() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(17.dp).background(if (ConnectionsSource.enabled) Pack.foreground else Pack.background).border(Pack.hair, Pack.border),
                contentAlignment = Alignment.Center) {
                if (ConnectionsSource.enabled) BasicText("✓", style = sans(12.sp, Pack.background))
            }
            Words("Notify me about unexpected outages and recovery", sans(12.sp, Pack.muted))
        }
        Words("After 60 seconds · one alert per incident · planned pauses stay quiet.", sans(11.sp, Pack.muted))
        Words(ConnectionsSource.alerts, caption(10.sp), modifier = Modifier.padding(top = 4.dp))
    }
}
