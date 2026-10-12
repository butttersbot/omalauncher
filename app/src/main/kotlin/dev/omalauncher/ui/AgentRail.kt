package dev.omalauncher.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.AssistantPromptActivity
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.oma.ring.RingState
import dev.omalauncher.oma.ring.doneMillis
import dev.omalauncher.oma.ring.ringState
import dev.omalauncher.oma.ring.ringStyle
import dev.omalauncher.phone.RING_STAYS_HOME
import dev.omalauncher.phone.RingFollow
import kotlinx.coroutines.delay

/** A single quiet task strip, measured with the composer so it never covers home tiles. */
@Composable internal fun AgentRail() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { AgentTasks.restore(context); tick++; delay(500) } }
    val label = remember(tick) { AgentTasks.label }
    val pending = remember(tick) { AgentTasks.approvals.isNotEmpty() }
    val working = remember(tick) { AgentTasks.working }
    // Said once, and only if the ring over other apps is allowed and phone control is off or paused; still one line, so the strip keeps its height.
    val homeOnly = remember(tick) {
        RingFollow.homeOnly(context, lit = ringState(OmaRing.signals, OmaRing.now(), doneMillis(ringStyle().done)) != RingState.Off)
    }
    val color = Palette.accent
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(min = 38.dp)
        .clip(RoundedCornerShape(10.dp)).background(Palette.surface)
        .clickable { context.startActivity(Intent(context, AssistantPromptActivity::class.java)) }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(9.dp))
        BasicText(if (homeOnly) RING_STAYS_HOME else if (label == "Ready") "Oma agent" else label, Modifier.weight(1f),
            style = Mono.copy(color = Palette.foreground, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        BasicText(if (pending) "Review" else if (working) "View" else if (label == "Ready") "Ask" else "Result",
            style = Mono.copy(color = color, fontSize = 12.sp))
    }
}
