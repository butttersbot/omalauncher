package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.App
import dev.omalauncher.linux.LinuxState
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The fast model asked once, from Linux, as the chat titles are (ChatTitlesJob): for whether a question waiting
 * elsewhere is the one Boss just answered (SameQuestion.kt). Blocking; null without Linux or Claude Code, or on failure.
 */
object QuickModel {
    private const val TIMEOUT_SECONDS = 45L

    fun ask(context: Context, command: String): String? {
        val runtime = (context.applicationContext as App).runtime
        if (runtime.bootstrap.state.value != LinuxState.Installed || runtime.agents.installed.none { it.bin == "claude" }) return null
        val child = runtime.proot.process(command).start()
        child.outputStream.close()
        val output = StringBuilder()
        val reader = thread(name = "soma-same-read", isDaemon = true) { runCatching { child.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
        if (!child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            RunEnd.end(child)
            return null
        }
        reader.join(1_000)
        return output.toString()
    }
}
