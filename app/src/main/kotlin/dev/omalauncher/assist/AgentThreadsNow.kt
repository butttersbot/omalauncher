package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.TabBook

/**
 * The threads as they stand this moment, from what the builder, the Oma thread and [MacThreads] already
 * publish. It asks the Mac nothing, waits for nothing and takes no lock, so the chat and home can read it as
 * often as they draw; they restore [AgentTasks] and [MacThreads] once, before the first read.
 */
fun agentThreadsNow(context: Context): List<AgentThread> {
    fun now(builder: Builder) = BuilderNow(builder.working, builder.label, builder.pending.map { ThreadAsk(it.approval.tool, askTarget(it.approval.input.toString())) }, builder.startedAt)
    val asks = MacThreads.pending.groupBy({ it.threadId }) { ThreadAsk(it.request.tool, askTarget(it.request.input)) }
    val book = TabBook.of(context)
    return agentThreads(
        ChatLog.of(context).tasks, now(AgentTasks.of(BuilderId.A)), MacThreads.view, asks, now(AgentTasks.of(BuilderId.B)),
        book.chats, book.names,
    )
}
