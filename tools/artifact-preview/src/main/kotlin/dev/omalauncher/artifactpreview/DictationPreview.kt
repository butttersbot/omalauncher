package dev.omalauncher.artifactpreview

import android.util.Log
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.ListeningAct
import dev.omalauncher.assist.ListeningEffect
import dev.omalauncher.assist.ListeningLayer
import dev.omalauncher.assist.ListeningPhase
import dev.omalauncher.assist.ListeningRequest
import dev.omalauncher.assist.RelayOutcome
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.render.ArtOverlay
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WidgetInstance
import dev.omalauncher.widgets.runtime.WidgetTerminal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/** A plain widget over art, on the real `over` and dictation(...), for dictation-widget.walk; its look is Phone C's to give. */
private val FIXTURE = """
    widget "dictation-fixture" {
      meta { owl: 11 }
      over: "art:f4618f2aa61acfb0"
      onTap: dictation("open")
      padding: 4
      // A thin rail along the top, as Boss asked (2026-10-30); the rest of the picture is clear, and still takes the tap.
      Row {
        gap: 6
        Text { text: "MG1"; style: small }
        Text { text: "Press to record"; style: small }
      }
    }
""".trimIndent()

private const val HEARD = "turn the lights down"

/**
 * --es dictationWidget typed|locked|offline|other: a fictional picture, always moving, with the widget over it; a tap opens
 * the real listening screen and its real decisions, with the words a fictional microphone heard and what a fictional MG1
 * answers. `other` is any widget but his. Nothing reaches a device; the emulator has no microphone.
 */
@Composable
internal fun DictationPreview(kind: String) {
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<ListeningRequest?>(null) }
    val inst = remember {
        WidgetInstance(
            Compiler.compile(FIXTURE),
            object : Services {
                override suspend fun cmd(command: String, timeoutSeconds: Int) = ShellResult("", "", 1)
                override suspend fun http(url: String, headers: Map<String, String>, timeoutSeconds: Int, body: ByteArray?) = ShellResult("", "", -1)
                override fun run(command: String, done: (ShellResult?) -> Unit) = done(null)
                override suspend fun stream(command: String, onLine: (String) -> Unit) {}
                override fun android(name: String): Value = Null
                override fun act(name: String, args: List<Value>, done: () -> Unit) {
                    if (name == "dictation") {
                        Log.i("SomaWalk", "fixture dictation open")
                        request = ListeningRequest(ListeningPhase.Listening)
                    }
                    done()
                }
                override fun guestFile(path: String) = File("/nonexistent")
                override fun fileUpdates(path: String): Flow<String> = emptyFlow()
                override fun terminal(command: String?, fontSize: Double?): WidgetTerminal = error("no terminal here")
            },
            onError = { Log.i("SomaWalk", "fixture widget error: $it") },
            mayDictate = kind != "other",
        )
    }
    LaunchedEffect(inst) { inst.start(scope) { true } }
    val moving by rememberInfiniteTransition(label = "art").animateColor(Color(0xFF3A6EA5), Color(0xFFD7262D), infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "art")
    Box(Modifier.fillMaxSize()) {
        // The panel: the picture under the widget, as Home draws them.
        Box(Modifier.size(200.dp, 300.dp).background(moving)) {
            ArtOverlay(inst) { inst.program.onTap?.run(inst.root, emptyList()) }
        }
        val now = request
        ListeningLayer(
            now?.look(if (now.phase == ListeningPhase.Listening) HEARD else "", "MG1"),
            { act ->
                val step = now?.act(act)
                when (val effect = step?.effect) {
                    is ListeningEffect.Send -> {
                        Log.i("SomaWalk", "fixture relay: ${effect.text}")
                        val sending = ListeningRequest(ListeningPhase.Heard, effect.text).sending()
                        request = sending.relayed(
                            when (kind) {
                                "locked" -> RelayOutcome.Locked
                                "offline" -> RelayOutcome.Offline
                                else -> RelayOutcome.Typed("Brave")
                            },
                            "MG1",
                        )
                    }
                    ListeningEffect.Leave -> request = null
                    ListeningEffect.Listen -> request = step.next
                    else -> {}
                }
            },
            onEdit = { text -> request = request?.edited(text) },
            onAway = {
                when {
                    now == null || now.holdsOnAway -> {}
                    // The fictional microphone stops, as a tap on the paper stops the real one.
                    now.phase == ListeningPhase.Listening -> request = now.spoke(HEARD)
                    else -> request = null
                }
            },
        )
    }
}
