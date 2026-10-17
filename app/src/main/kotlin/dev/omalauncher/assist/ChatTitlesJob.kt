package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.App
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.TabBook
import dev.omalauncher.oma.chatKey
import dev.omalauncher.oma.titled
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Names new chats in the background, once each. Called from the chat and from home as they look at the threads. */
object ChatTitles {
    /** The chats asked for in this run, so none is asked twice while its answer is on its way. */
    private val asked = HashSet<String>()

    private const val TIMEOUT_SECONDS = 60L

    /** Starts naming every chat that has no name and can be named now; returns at once. */
    fun check(context: Context) {
        val app = context.applicationContext
        val book = TabBook.of(app)
        val tasks = ChatLog.of(app).tasks
        val now = System.currentTimeMillis()
        for (chat in book.chats) {
            if (chat.key in book.names) continue
            val ask = titleDue(tasks.filter { it.chat == chat.id }, now) ?: continue
            if (!synchronized(this) { asked.add(chat.id) }) continue
            thread(name = "oma-title", isDaemon = true) {
                val title = runCatching { model(app, ask) }.getOrNull()?.let(::titleClean) ?: titleFallback(ask.message)
                TabBook.change(app) { it.titled(chatKey(chat.id), title) }
            }
        }
    }

    /** What the fast model says for [ask]; null without Linux or Claude Code, or when it fails or takes too long. */
    private fun model(context: Context, ask: TitleAsk): String? {
        val runtime = (context as App).runtime
        if (runtime.bootstrap.state.value != LinuxState.Installed || runtime.agents.installed.none { it.bin == "claude" }) return null
        val child = runtime.proot.process(titleCommand(ask)).start()
        child.outputStream.close()
        val output = StringBuilder()
        val reader = thread(name = "oma-title-read", isDaemon = true) { runCatching { child.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
        if (!child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            RunEnd.end(child)
            return null
        }
        reader.join(1_000)
        return titleFrom(output.toString())
    }
}
