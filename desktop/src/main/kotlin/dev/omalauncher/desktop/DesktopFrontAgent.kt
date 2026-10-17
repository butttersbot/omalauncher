package dev.omalauncher.desktop

import dev.omalauncher.assist.FRONT_MAX_REQUEST
import dev.omalauncher.assist.FrontOutcome
import dev.omalauncher.assist.FrontQueue
import dev.omalauncher.assist.FrontQueueFull
import dev.omalauncher.assist.FrontRead
import dev.omalauncher.assist.FrontRoute
import dev.omalauncher.assist.FrontWaiting
import dev.omalauncher.assist.MacThread
import dev.omalauncher.assist.MacThreadsEngine
import dev.omalauncher.assist.macMachineOf
import dev.omalauncher.assist.QUICK_MODEL
import dev.omalauncher.assist.QuickAnswer
import dev.omalauncher.assist.QuickEvent
import dev.omalauncher.assist.WHICH_PROJECT
import dev.omalauncher.assist.agentName
import dev.omalauncher.assist.chatBeside
import dev.omalauncher.assist.chatRetried
import dev.omalauncher.assist.desktopFrontPrompt
import dev.omalauncher.assist.frontHolds
import dev.omalauncher.assist.frontOutcome
import dev.omalauncher.assist.frontRead
import dev.omalauncher.assist.frontRoute
import dev.omalauncher.assist.quickDesktopCommand
import dev.omalauncher.assist.quickEvent
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import dev.omalauncher.oma.heard
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The front agent on a computer: the phone's QuickLane with no builders behind it (cockpit slice 6). A fast model
 * ([QUICK_MODEL], through this machine's own `claude`) hears a request first and answers it, or hands his words on
 * unchanged to a project thread on a computer through [threads]. Work for no listed thread ([FrontRoute.Build]) starts a
 * new thread, as a new chat's first words do. One request at a time; what he sends meanwhile waits in [queue]. Its
 * rules are the phone's own (FrontAgent.kt, QuickLaneRules.kt); only its prompt ([desktopFrontPrompt]) and its tools
 * (the web alone) differ.
 */
class DesktopFrontAgent(
    private val threads: MacThreadsEngine,
    private val platform: DesktopThreadsPlatform,
    private val notices: Notices,
    /** Where Claude Code is looked for; a test can say there is none. */
    private val places: List<String> = listOf("~/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude", "~/.local/share/mise/shims/claude", "/usr/bin/claude"),
) {
    @Volatile var working = false; private set
    @Volatile var answer = ""; private set
    @Volatile var label = "Ready"; private set
    @Volatile var time = 0L; private set
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    private val queue = FrontQueue()

    /** Claude Code, found where a login shell would find it. */
    val claude: String? by lazy { places.map { it.replaceFirst("~", System.getProperty("user.home")) }.firstOrNull { File(it).canExecute() } }

    /** Whether there is a front agent to ask here. */
    val ready: Boolean get() = claude != null

    private val log get() = platform.chatLog()

    /** As QuickLane.ask: into the thread, undecided, read now or when the one before is done. Throws [FrontQueueFull]. */
    @Synchronized fun ask(prompt: String, chat: String = "") {
        check(prompt.isNotBlank()) { "Type a request first." }
        check(prompt.length <= FRONT_MAX_REQUEST) { "That request is too long to ask first." }
        check(ready) { "Install Claude Code on this computer to talk to Soma here." }
        if (!working) return read(prompt, waited = null, chat = chat)
        synchronized(platform.stampLock) {
            val filed = ChatHistory.stamp(log.tasks, System.currentTimeMillis())
            if (!queue.add(FrontWaiting(filed, prompt, chat = chat))) throw FrontQueueFull()
            log.begin(ChatTask(filed, UNDECIDED_AGENT, prompt, status = TaskStatus.Queued, chat = chat))
        }
    }

    /** `[ try again ]` under a new chat's words a computer was out of reach for: read again, now or in turn. */
    @Synchronized fun again(time: Long) {
        val task = log.tasks.firstOrNull { it.time == time } ?: return
        val retried = chatRetried(task) ?: return
        if (working && !queue.add(FrontWaiting(time, task.prompt, chat = task.chat))) return
        log.update(time, save = true) { chatRetried(it) ?: it }
        if (!working) read(retried.prompt, waited = time, chat = retried.chat)
    }

    @Synchronized fun cancel(time: Long) {
        val gone = queue.cancel(time) ?: return
        log.update(gone.time, save = true) { ChatHistory.dropped(it, FrontQueue.CANCELLED) }
    }

    @Synchronized fun dropWaiting(chat: String?) {
        queue.drop { chat == null || it.chat == chat }.forEach { waiting -> log.update(waiting.time, save = true) { ChatHistory.dropped(it, FrontQueue.STOPPED) } }
    }

    @Synchronized fun stop() {
        if (!working) return
        stopping = true
        process?.let(::end); label = "Stopping"
    }

    private fun advance() {
        while (true) {
            val next = queue.next() ?: return
            try {
                return read(next.prompt, waited = next.time, chat = next.chat)
            } catch (e: Exception) {
                log.update(next.time, save = true) { it.copy(result = e.message ?: FrontQueue.UNREAD, status = TaskStatus.Failed) }
            }
        }
    }

    /** Under the lock, with nothing being read: starts the front agent on [prompt], as QuickLane.read does. */
    private fun read(prompt: String, waited: Long?, chat: String) {
        val resumed = if (chat.isEmpty()) platform.prefString(SESSION) else platform.book().chat(chat)?.front?.ifEmpty { null }
        val began = System.currentTimeMillis()
        val mac = threads.board(began)
        val asked = synchronized(platform.stampLock) {
            waited?.also { t -> log.update(t, save = true) { it.copy(status = TaskStatus.Running) } }
                ?: ChatHistory.stamp(log.tasks, began).also { log.begin(ChatTask(it, UNDECIDED_AGENT, prompt, chat = chat)) }
        }
        val command = quickDesktopCommand(desktopFrontPrompt(mac, prompt), resumed, newChat = chat.isNotEmpty())
        time = asked; working = true; stopping = false; answer = ""; label = ANSWERING
        val holds = frontHolds(chat, prompt)
        thread(name = "soma-front", isDaemon = true) {
            val late = AtomicBoolean()
            var next = resumed
            var handed = false
            var held = false
            var failed = false
            try {
                val child = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(false).apply {
                    environment()["PATH"] = listOf(File(claude!!).parent, "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin").joinToString(":")
                }.start()
                process = child
                if (stopping) end(child)
                thread(name = "soma-front-timeout", isDaemon = true) { if (!child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { late.set(true); end(child) } }
                var said = QuickAnswer()
                var session: String? = null
                var lost = false
                var its = false
                fun own() {
                    if (!its) log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, answeredBy = agentName(QUICK_MODEL).orEmpty()) }
                    its = true
                }
                child.inputStream.bufferedReader().forEachLine { line ->
                    val event = quickEvent(line) ?: return@forEachLine
                    if (handed || held) return@forEachLine
                    when (event) {
                        is QuickEvent.Session -> session = event.id
                        is QuickEvent.Delta -> {}
                        is QuickEvent.Message -> if (!holds) for (step in event.steps) {
                            own()
                            log.update(asked) { ChatHistory.withStep(it, step) }
                            if (!stopping) label = "Looking"
                        }
                        is QuickEvent.Result -> {
                            if (event.failed) failed = true
                            if (!event.failed || resumed != null) next = event.session ?: next
                        }
                        QuickEvent.Fault -> failed = true
                        QuickEvent.Lost -> lost = resumed != null
                    }
                    if (event is QuickEvent.Result && event.failed) return@forEachLine
                    said = said.after(event)
                    when (val read = frontRead(said.text, ended = event is QuickEvent.Result)) {
                        FrontRead.Undecided -> {}
                        is FrontRead.Answer -> if (holds) {
                            held = true
                            session?.let { next = it }
                            end(child)
                        } else {
                            own()
                            answer = read.text
                            if (!stopping && event !is QuickEvent.Message) label = ANSWERING
                        }
                        is FrontRead.HandOver -> {
                            handed = place(asked, prompt, chat, frontRoute(read.to, mac))
                            session?.let { next = it }
                            end(child)
                        }
                    }
                }
                val code = child.waitFor()
                if (lost) next = null
                if (stopping && session != null) next = session
                if (late.get() || lost || code != 0) failed = true
            } catch (e: Exception) { failed = true }
            finally {
                var outcome = frontOutcome(handed, stopping, failed, answer.isNotBlank())
                // With nothing it can be read by, his words go on as a new thread's, as a new chat's do with no front agent.
                if (outcome == FrontOutcome.FellBack && !place(asked, prompt, chat, route = null)) outcome = FrontOutcome.Stopped
                val status = when (outcome) {
                    FrontOutcome.Answered -> TaskStatus.Done
                    FrontOutcome.Stopped -> TaskStatus.Stopped
                    FrontOutcome.Broken -> TaskStatus.Failed
                    FrontOutcome.HandedOver, FrontOutcome.FellBack -> null
                }
                if (status != null) log.update(asked, save = true) { it.copy(result = answer, status = status) }
                synchronized(this) {
                    if (chat.isNotEmpty()) platform.changeBook { it.heard(chat, next) } else next?.let { platform.putPref(SESSION, it) }
                    process = null; working = false
                    advance()
                }
            }
        }
    }

    /**
     * Where his words go once the front agent hands them on ([route] null when it said nothing it could be read by):
     * to the thread it named, to a fresh one beside a busy one, to the chat's own thread, or to a new thread (no
     * builders here). A thread it named that isn't on the board is never guessed: the entry asks which project.
     * False if he stopped it first.
     */
    private fun place(asked: Long, prompt: String, chat: String, route: FrontRoute?): Boolean = synchronized(this) {
        if (stopping) return false
        val bound = if (chat.isEmpty()) null else platform.book().chat(chat)?.mac?.takeIf { it.isNotEmpty() }
        val own = bound?.let { id -> threads.view.threads.firstOrNull { it.id == id } }
        when {
            own != null -> threads.handOver(asked, prompt, own, away = false)
            // The Soma tab's words, and a chat's that has its thread, go to the thread named (or fresh beside it), as on the phone.
            route is FrontRoute.Mac && (chat.isEmpty() || bound != null) -> threads.handOver(asked, prompt, route.thread, away = false, fresh = route.fresh)
            // A thread named that isn't on the board is never guessed: the Soma tab's entry asks which project.
            route is FrontRoute.Unknown && chat.isEmpty() -> log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = WHICH_PROJECT, status = TaskStatus.Failed) }
            // A new chat's first words never join a thread already there: a fresh one beside it, on the computer he picked.
            chat.isNotEmpty() && besidePicked(chat, route) != null -> threads.handOver(asked, prompt, besidePicked(chat, route)!!, away = false, fresh = true, newChat = true)
            else -> threads.startChat(asked, prompt, away = false, chat = chat)
        }
        true
    }

    private fun besidePicked(chat: String, route: FrontRoute?): MacThread? {
        val picked = platform.book().chat(chat)?.host
        return chatBeside(route)?.takeIf { picked == null || macMachineOf(it.id) == picked }
    }

    /** Ends [child] and whatever it started. */
    private fun end(child: Process) {
        child.descendants().forEach { it.destroy() }
        child.destroy()
        thread(isDaemon = true) { if (!child.waitFor(3, TimeUnit.SECONDS)) { child.descendants().forEach { it.destroyForcibly() }; child.destroyForcibly() } }
    }

    private companion object {
        const val SESSION = "front-session"
        const val ANSWERING = "Answering"
        const val TIMEOUT_SECONDS = 120L
    }
}
