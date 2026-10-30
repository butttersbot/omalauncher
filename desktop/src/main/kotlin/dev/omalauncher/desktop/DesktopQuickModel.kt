package dev.omalauncher.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The fast model asked once on this computer, as the titles are (DesktopChatTitles): null without Claude Code or on failure. */
internal fun desktopQuickModel(command: String): String? {
    val home = System.getProperty("user.home")
    val path = listOf("$home/.local/bin", "$home/.local/share/mise/shims", "$home/.bun/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
    if (path.none { File(it, "claude").canExecute() }) return null
    val child = ProcessBuilder("/bin/sh", "-c", command).apply {
        environment()["PATH"] = (path + listOfNotNull(System.getenv("PATH"))).joinToString(":")
        redirectErrorStream(true)
    }.start()
    child.outputStream.close()
    val output = StringBuilder()
    val reader = thread(name = "soma-same-read", isDaemon = true) { runCatching { child.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
    if (!child.waitFor(45, TimeUnit.SECONDS)) {
        child.descendants().forEach { it.destroyForcibly() }
        child.destroyForcibly()
        return null
    }
    reader.join(1_000)
    return output.toString()
}
