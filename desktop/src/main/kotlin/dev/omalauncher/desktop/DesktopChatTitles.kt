package dev.omalauncher.desktop

import dev.omalauncher.assist.TitleAsk
import dev.omalauncher.assist.QUICK_MODEL
import dev.omalauncher.assist.titlePrompt
import dev.omalauncher.linux.shellQuote
import dev.omalauncher.assist.titleClean
import dev.omalauncher.assist.titleDue
import dev.omalauncher.assist.titleFallback
import dev.omalauncher.assist.titleFrom
import dev.omalauncher.oma.chatKey
import dev.omalauncher.oma.titled
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * New chats named here as the phone names them (ChatTitles.kt; the phone's ChatTitlesJob): once the first exchange is in,
 * this computer's Claude Code is asked once with the fast model for a few words, and without it (an Omarchy box with no
 * Claude, a failure, a minute gone) the first words of his message name the chat. A name he gave a tab is never replaced.
 * Before this every tab on the desktop said "New chat" (2026-10-27).
 */
class DesktopChatTitles(private val platform: DesktopThreadsPlatform, private val ask: (TitleAsk) -> String? = ::model) {
    /** The chats asked for in this run, so none is asked twice while its answer is on its way. */
    private val asked = HashSet<String>()

    /** Starts naming every chat that has no name and can be named now; returns at once. */
    fun check() {
        val book = platform.book()
        val tasks = platform.chatLog().tasks
        val now = System.currentTimeMillis()
        for (chat in book.chats) {
            if (chatKey(chat.id) in book.names) continue
            val ask = titleDue(tasks.filter { it.chat == chat.id }, now) ?: continue
            if (!synchronized(this) { asked.add(chat.id) }) continue
            thread(name = "soma-title", isDaemon = true) {
                val title = runCatching { this.ask(ask) }.getOrNull()?.let(::titleClean) ?: titleFallback(ask.message)
                platform.changeBook { it.titled(chatKey(chat.id), title) }
            }
        }
    }
}

/** What this computer's fast model says for [ask]; null without Claude Code here, or when it fails or takes too long. */
private fun model(ask: TitleAsk): String? {
    val home = System.getProperty("user.home")
    // Opened from Finder or at login, Soma's PATH is launchd's: Claude Code's own places are added.
    val path = listOf("$home/.local/bin", "$home/.local/share/mise/shims", "$home/.bun/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
    if (path.none { File(it, "claude").canExecute() }) return null
    val child = ProcessBuilder("/bin/sh", "-c", desktopTitleCommand(ask)).apply {
        environment()["PATH"] = (path + listOfNotNull(System.getenv("PATH"))).joinToString(":")
        redirectErrorStream(true)
    }.start()
    child.outputStream.close()
    val output = StringBuilder()
    val reader = thread(name = "soma-title-read", isDaemon = true) { runCatching { child.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
    if (!child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        // Claude Code is the shell's child: ending the shell alone would leave it running (round-1 Critic).
        child.descendants().forEach { it.destroyForcibly() }
        child.destroyForcibly()
        return null
    }
    reader.join(1_000)
    return titleFrom(output.toString())
}

private const val TIMEOUT_SECONDS = 60L

/**
 * The phone's title ask (titleCommand) for a computer's own Claude Code, where his whole setup is installed: without his
 * MCP servers, skills, user settings and hooks, and keeping no session. With them it loaded 166k tokens of context ($0.17
 * a title) and never exited after answering (Mac Pro, 2026-10-28); without, 2 s and a tenth of a cent.
 */
internal fun desktopTitleCommand(ask: TitleAsk): String =
    "mkdir -p /tmp/oma-title && cd /tmp/oma-title && claude -p --model $QUICK_MODEL --output-format json --tools '' " +
        "--strict-mcp-config --disable-slash-commands --no-session-persistence --setting-sources project -- ${shellQuote(titlePrompt(ask))} < /dev/null"
