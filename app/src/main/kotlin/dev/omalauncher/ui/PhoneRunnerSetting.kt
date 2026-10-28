package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.runner.PhoneRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pair and Unpair are owner taps. Keys never enter Compose state, clipboard, chat or screen text. */
@Composable
fun PhoneRunnerSetting(runner: PhoneRunner, dismiss: () -> Unit) {
    var devices by remember { mutableStateOf<List<String>>(emptyList()) }
    var paired by remember { mutableStateOf(runner.paired()) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("Loading your computers…") }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        busy = true
        val result = withContext(Dispatchers.IO) { runCatching { runner.devices() } }
        devices = result.getOrDefault(emptyList()); paired = runner.paired()
        notice = if (result.isFailure) "Your tailnet identity service is unavailable. Try again when a paired Mac is reachable."
            else "A paired computer can send work to Phone A, B and C, read sessions and answer your question cards. Phone D stays mail only."
        busy = false
    }
    LaunchedEffect(Unit) { refresh() }
    OverlayPanel("phone sessions", dismiss, contentWidth = ReadingWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Let a computer reach this phone’s sessions", style = Mono.copy(fontSize = TextSize.Item))
            BasicText(notice, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((devices + paired).distinct(), key = { it }) { device ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            BasicText(device, style = Mono.copy(fontSize = TextSize.Item))
                            BasicText(if (device in paired) "paired" else "not paired", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                        if (!busy) BracketAction(if (device in paired) "unpair" else "pair", Palette.accent) {
                            scope.launch {
                                busy = true
                                val existed = device in paired
                                val result = withContext(Dispatchers.IO) { runCatching { if (existed) runner.unpair(device) else runner.pair(device) } }
                                paired = runner.paired()
                                notice = if (result.isSuccess) {
                                    if (existed) "Unpaired $device. Its key no longer works."
                                    else "Pairing file sent to $device by Taildrop. Open it in Soma desktop."
                                } else "Pairing could not be changed. Check Tailscale and try again."
                                busy = false
                            }
                        }
                    }
                }
            }
            if (!busy) BracketAction("refresh computers", Palette.muted) { scope.launch { refresh() } }
        }
    }
}
