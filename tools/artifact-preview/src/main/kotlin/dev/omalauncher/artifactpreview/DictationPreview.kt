package dev.omalauncher.artifactpreview

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.omalauncher.assist.RelayOutcome
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.runtime.Dictation
import dev.omalauncher.widgets.runtime.DictationMic
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WidgetInstance
import dev.omalauncher.widgets.runtime.WidgetTerminal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.io.File

/** A plain widget on the real owl source and action, for dictation-widget.walk; its look is the real widget's to have. */
private val FIXTURE = """
    widget "dictation-fixture" {
      meta { owl: 11 }
      padding: 12
      source d = android.dictation
      Column {
        gap: 12
        Text { text: "state ${'$'}{d.state}" }
        Text { text: d.words }
        Text { text: d.message }
        Button { text: d.state == "listening" ? "Stop" : "Record"; onTap: dictation("toggle") }
        Button { text: "Send"; onTap: dictation("send") }
        Button { text: "Clear"; onTap: dictation("clear") }
      }
    }
""".trimIndent()

/** Hears the same words every time: the emulator has no microphone. */
private class FixtureMic(override val allowed: Boolean) : DictationMic {
    private var done: ((String, String?) -> Unit)? = null

    override fun start(onText: (String) -> Unit, onDone: (String, String?) -> Unit) {
        done = onDone
        onText(HEARD)
    }

    override fun stop() {
        done?.invoke(HEARD, null)
    }

    override fun cancel() {}

    private companion object {
        const val HEARD = "turn the lights down"
    }
}

/** --es dictationWidget typed|locked|offline|nomic|other: what the fictional MG1 answers, no microphone, or any other widget. Nothing reaches a device. */
@Composable
internal fun DictationPreview(kind: String) {
    val scope = rememberCoroutineScope()
    val dictation = remember {
        Dictation(FixtureMic(kind != "nomic"), "MG1") { words, done ->
            Log.i("SomaWalk", "fixture relay: $words")
            done(
                when (kind) {
                    "locked" -> RelayOutcome.Locked
                    "offline" -> RelayOutcome.Offline
                    else -> RelayOutcome.Typed("Brave")
                },
            )
        }
    }
    val inst = remember {
        WidgetInstance(
            Compiler.compile(FIXTURE),
            object : Services {
                override suspend fun cmd(command: String, timeoutSeconds: Int) = ShellResult("", "", 1)
                override suspend fun http(url: String, headers: Map<String, String>, timeoutSeconds: Int, body: ByteArray?) = ShellResult("", "", -1)
                override fun run(command: String, done: (ShellResult?) -> Unit) = done(null)
                override suspend fun stream(command: String, onLine: (String) -> Unit) {}
                override fun android(name: String): Value = if (name == "dictation") dictation.now.value.value(dictation.target) else Null
                override fun androidUpdates(name: String): Flow<Value>? =
                    if (name == "dictation") dictation.now.map { it.value(dictation.target) } else null
                override fun act(name: String, args: List<Value>, done: () -> Unit) {
                    if (name == "dictation") dictation.control(args[0].toString())
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
    WidgetContent(inst, Modifier.fillMaxSize())
}
