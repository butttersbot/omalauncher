package dev.omalauncher.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.MacHandoff
import dev.omalauncher.assist.MacThread
import dev.omalauncher.assist.RegisteredProject
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.projectOf
import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.HostChoice
import dev.omalauncher.oma.TabMeta
import dev.omalauncher.oma.chatKey
import dev.omalauncher.assist.MAC_NEW_THREAD
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.ThreadDot

/*
 * The Timeline, New thread and Project modules (Soma Canvas slice 3c). Timeline and Project only show and open the chat;
 * New thread opens a new chat in the chat app, where its computer, agent and model are picked as they always are (slices 5
 * and 8). None of the three changes anything by itself.
 */

/** One thing that happened, newest first: when, how it stood, what it says, and the tab it opens. */
data class ModuleEvent(val time: Long, val state: ThreadState, val text: String, val threadKey: String)

/**
 * The timeline from the chat's own entries: every request of his with where it went and how it ended, newest first. What
 * a request said is cut to its first line; one handed to a thread on a computer opens that thread's tab.
 */
fun moduleTimeline(tasks: List<ChatTask>, meta: TabMeta = TabMeta(), limit: Int = 60): List<ModuleEvent> = tasks.sortedByDescending { it.time }.take(limit).map { t ->
    val state = when (t.status) {
        TaskStatus.Running -> if (t.approvals.any { it.answer == ApprovalAnswer.Waiting }) ThreadState.NeedsOk else ThreadState.Working
        TaskStatus.Queued -> ThreadState.Queued
        TaskStatus.Done, TaskStatus.Stopped -> ThreadState.Idle
        TaskStatus.Failed, TaskStatus.Dropped, TaskStatus.Interrupted -> ThreadState.Attention
    }
    val where = t.threadName.ifEmpty { t.agent.removePrefix("→").trim() }.take(40)
    val said = t.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120).orEmpty()
    ModuleEvent(t.time, state, listOf(where, said).filter { it.isNotEmpty() }.joinToString(" · "), eventTab(t, meta))
}

/**
 * The tab an entry opens, as its receipt in the chat does: the new chat it was asked in or whose thread it went to, else
 * its thread's own tab. A first-words request whose chat is gone has no thread yet, so it opens nothing.
 */
private fun eventTab(t: ChatTask, meta: TabMeta): String = when {
    !t.mac -> ""
    t.chat.isNotEmpty() && meta.chat(t.chat) != null -> chatKey(t.chat)
    t.thread == MAC_NEW_THREAD -> ""
    else -> macTabKey(t.thread, meta)
}

private fun clock(time: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = time }
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

@Composable
fun TimelineModule(events: List<ModuleEvent>, size: ModuleSize, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    // The tile itself opens the newest entry that has a thread; a row opens its own.
    val newest = events.firstOrNull { it.threadKey.isNotEmpty() }?.threadKey
    ModuleFrame("Timeline", "every thread", size, "module:timeline", modifier, onOpen = newest?.let { { onOpen(it) } }) {
        when {
            size == ModuleSize.Dot -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(events.firstOrNull()?.let { clock(it.time) } ?: "—", style = Pack.display(Pack.size.large), maxLines = 1, softWrap = false)
                BasicText(Pack.caption("latest"), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
            }
            events.isEmpty() -> BasicText("Nothing yet today.", style = Pack.body(color = Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> androidx.compose.foundation.layout.BoxWithConstraints {
                // A narrow module keeps what happened and lets the times go; the order still says which came first.
                val timed = maxWidth >= 200.dp
                if (size == ModuleSize.Line) EventRow(events.first(), timed, onOpen) else FitRows(events.size) { i -> EventRow(events[i], timed, onOpen) }
            }
        }
    }
}

@Composable
private fun EventRow(e: ModuleEvent, timed: Boolean, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = e.threadKey.isNotEmpty()) { onOpen(e.threadKey) }, verticalAlignment = Alignment.CenterVertically) {
        if (timed) {
            BasicText(clock(e.time), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, softWrap = false)
            Spacer(Modifier.width(8.dp))
        }
        ThreadDot(e.state, Pack.muted)
        Spacer(Modifier.width(8.dp))
        BasicText(e.text, Modifier.weight(1f), style = Pack.body(Pack.size.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What a new chat would start on: the computer, its agent and model (the default new chats remember there). */
data class NewThreadPick(val on: String, val agent: String, val model: String)

/**
 * The pick a new chat starts with: the first computer that answers (else the first set up), with the agent and model it
 * remembers for new chats, else its first agent and that agent's own default model. Null with no computer set up.
 */
fun moduleNewThread(hosts: List<HostChoice>): NewThreadPick? {
    val host = hosts.firstOrNull { it.reachable } ?: hosts.firstOrNull() ?: return null
    val agent = host.default?.agent ?: host.agents.firstOrNull().orEmpty()
    return NewThreadPick(host.name, agent, host.default?.model.orEmpty().ifEmpty { "default" })
}

/** New thread: what a new chat would start on, and a tap that opens one in the chat, where any of it can be changed. */
@Composable
fun NewThreadModule(pick: NewThreadPick?, size: ModuleSize, modifier: Modifier = Modifier, onNew: () -> Unit) {
    // The picks say where it starts, so there is no caption; each pick is shown whole or not at all, the computer first.
    ModuleFrame("New thread", "", size, "module:newthread", modifier, onOpen = onNew) {
        val picks = listOfNotNull(pick?.on, pick?.agent, pick?.model).filter { it.isNotBlank() }.ifEmpty { listOf("new chat") }
        val row = @Composable { FitAcross(picks.size, gap = 8.dp) { i -> BasicText("[ ${picks[i]} ]", style = Pack.body(color = Pack.accent), maxLines = 1, softWrap = false) } }
        when (size) {
            ModuleSize.Dot -> BasicText("+", style = Pack.display(Pack.size.title), maxLines = 1, softWrap = false)
            ModuleSize.Line -> row()
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row()
                BasicText("start a chat…", Modifier.fillMaxWidth(), style = Pack.body(color = Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A project as its module shows it: its [name], first folder, the states of the threads in it, and its handoff's next step. */
data class ModuleProject(val name: String, val folder: String, val states: List<ThreadState>, val next: String, val firstThread: String?)

/**
 * Project [name] from what is known: its registry entry (for the folder), the threads on the computers whose folder belongs to
 * it ([macThreads] with [threads] for their states), and its handoff's next line.
 */
fun moduleProject(name: String, registry: List<RegisteredProject>, macThreads: List<MacThread>, threads: List<AgentThread>, handoff: MacHandoff?, meta: TabMeta = TabMeta()): ModuleProject {
    val mine = macThreads.filter { projectOf(it.project, registry) == name }
    // A thread made for a new chat is shown, and opened, as that chat.
    val known = mine.mapNotNull { m -> macTabKey(m.id, meta).let { key -> threads.firstOrNull { it.key == key } } }
    val folder = registry.firstOrNull { it.name == name }?.folders?.firstOrNull { !it.endsWith("*") }.orEmpty()
    // A tap opens the thread that most needs him (an ask, then a failure, then work), else the first.
    val order = MODULE_STATES.map { it.first }
    val first = known.minByOrNull { t -> order.indexOf(t.state).let { if (it < 0) order.size else it } }?.key ?: mine.firstOrNull()?.let { macTabKey(it.id, meta) }
    return ModuleProject(name, folder, known.map { it.state }, handoff?.let { handoffNext(it.text) }.orEmpty(), first)
}

@Composable
fun ProjectModule(project: ModuleProject, size: ModuleSize, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    ModuleFrame(project.name, project.folder, size, "module:project:${project.name}", modifier, onOpen = project.firstThread?.let { { onOpen(it) } }) {
        val dots = @Composable { FitAcross(project.states.size, gap = 4.dp) { i -> ThreadDot(project.states[i], Pack.muted) } }
        when (size) {
            ModuleSize.Dot -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ThreadDot(MODULE_STATES.map { it.first }.firstOrNull { it in project.states } ?: ThreadState.Idle, Pack.muted)
                BasicText(Pack.caption(project.name), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ModuleSize.Line -> Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("${project.states.size} threads", style = Pack.body(), maxLines = 1, softWrap = false)
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { dots() }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("${project.states.size} threads", style = Pack.body(), maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { dots() }
                }
                if (project.next.isNotEmpty()) {
                    BasicText(Pack.caption("next"), style = Pack.mono(Pack.size.small, Pack.accent), maxLines = 1)
                    BasicText(project.next, style = Pack.body(Pack.size.small), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
