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
import dev.omalauncher.assist.FrontAgent
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
import dev.omalauncher.assist.desktopFrontCommand
import dev.omalauncher.assist.frontSession
import dev.omalauncher.assist.frontThread
import dev.omalauncher.assist.writeGrokGuard
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
 * (Grok through this machine's own `grok` once it is signed in here, else [QUICK_MODEL] through its `claude`; Boss,
 * 2026-10-18; `soma front haiku|grok` chooses) hears a request first and answers it, or hands his words on
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
    private val places: List<String> = PLACES,
    /** The canvas's door (slice 6): each request gets a token of its own to arrange the canvas with, gone when it ends. */
    private val door: CanvasDoor? = null,
    /** Where Grok is looked for: beside each place Claude Code is, so a test that says there is no Claude says there is no Grok. */
    private val grokPlaces: List<String> = places.map { it.removeSuffix("claude") + "grok" },
    /** Whether Grok is signed in on this computer: only that its sign-in file is there. */
    private val grokSignedIn: () -> Boolean = { File(System.getProperty("user.home"), ".grok/auth.json").isFile },
    /** What `soma front` wrote, null when he never chose. */
    private val choice: () -> String? = { runCatching { File(SomaHome.dir, FRONT_CHOICE_FILE).takeIf { it.isFile }?.readText() }.getOrNull() },
    /** The empty folder the front agent runs in while it can arrange the canvas. */
    private val emptyFolder: () -> java.io.File = { java.io.File(SomaHome.dir, "front").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } } },
    /** The `soma` it runs that with, put first on its PATH; with none, it isn't told it can arrange anything. */
    private val shim: File? = null,
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

    /** Grok, found the same way. */
    val grok: String? by lazy { grokPlaces.map { it.replaceFirst("~", System.getProperty("user.home")) }.firstOrNull { File(it).canExecute() } }

    /** Who answers the next request: Grok when found and signed in here unless he chose Haiku, else Haiku; null for neither. */
    val agent: FrontAgent? get() = FrontAgent.pick(choice(), grok != null, grokSignedIn(), claude != null)

    /** Whether there is a front agent to ask here. */
    val ready: Boolean get() = agent != null

    private val log get() = platform.chatLog()

    /** As QuickLane.ask: into the thread, undecided, read now or when the one before is done. Throws [FrontQueueFull]. */
    @Synchronized fun ask(prompt: String, chat: String = "") {
        check(prompt.isNotBlank()) { "Type a request first." }
        check(prompt.length <= FRONT_MAX_REQUEST) { "That request is too long to ask first." }
        check(ready) { "Install Grok or Claude Code on this computer to talk to Soma here." }
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
        // Grok runs only behind its command guard, in the folder it runs in; if that can't be put there, Haiku answers instead.
        val canvasFolder = if (door != null && shim != null) emptyFolder() else null
        val agent = agent?.let { chosen ->
            if (chosen != FrontAgent.Grok || writeGrokGuard(canvasFolder ?: File(SomaHome.dir, "front-grok"))) chosen
            else FrontAgent.Haiku.takeIf { claude != null }
        } ?: error("Install Grok or Claude Code on this computer to talk to Soma here.")
        // Only that agent's own conversation is followed up on: a Claude session means nothing to Grok.
        val resumed = frontSession(agent, if (chat.isEmpty()) platform.prefString(SESSION) else platform.book().chat(chat)?.front?.ifEmpty { null })
        val bin = if (agent == FrontAgent.Grok) grok!! else claude!!
        val began = System.currentTimeMillis()
        val mac = threads.board(began)
        val asked = synchronized(platform.stampLock) {
            waited?.also { t -> log.update(t, save = true) { it.copy(status = TaskStatus.Running) } }
                ?: ChatHistory.stamp(log.tasks, began).also { log.begin(ChatTask(it, UNDECIDED_AGENT, prompt, chat = chat)) }
        }
        val canvas = door != null && shim != null
        val command = desktopFrontCommand(agent, desktopFrontPrompt(mac, prompt), resumed, newChat = chat.isNotEmpty(), canvas = canvas)
        // One request, one restore point: every `soma canvas` it runs for this request carries the request's own token.
        val token = if (canvas) door!!.mint("front-$asked") else null
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
                    // In a folder of its own with nothing in it: what the agent may run reads only where it runs (s6 Critic:
                    // read-only commands chained after `soma canvas` read the folder Soma was started in, `/` from launchd).
                    // The guard (GrokFront.kt) was put in this folder as the agent was picked.
                    (if (token != null) canvasFolder else if (agent == FrontAgent.Grok) File(SomaHome.dir, "front-grok") else null)?.let(::directory)
                    environment()["PATH"] = (listOfNotNull(shim?.parent?.takeIf { token != null }, File(bin).parent) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")).joinToString(":")
                    environment().remove(CanvasDoor.TOKEN)
                    token?.let { environment()[CanvasDoor.TOKEN] = it }
                }.start()
                process = child
                if (stopping) end(child)
                thread(name = "soma-front-timeout", isDaemon = true) { if (!child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { late.set(true); end(child) } }
                var said = QuickAnswer()
                var session: String? = null
                var lost = false
                var its = false
                fun own() {
                    if (!its) log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, answeredBy = agentName(agent.model).orEmpty()) }
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
                // Grok says nothing JSON of a conversation it can't find; failing before it named one is that.
                if (agent == FrontAgent.Grok && resumed != null && session == null && (failed || code != 0)) next = null
                if (stopping && session != null) next = session
                if (late.get() || lost || code != 0) failed = true
            } catch (e: Exception) { failed = true }
            finally {
                token?.let { door?.revoke(it) }
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
                    val kept = frontThread(agent, next)
                    if (chat.isNotEmpty()) platform.changeBook { it.heard(chat, kept) } else kept?.let { platform.putPref(SESSION, it) }
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

    companion object {
        /** Where Claude Code is looked for on a computer. */
        val PLACES = listOf("~/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude", "~/.local/share/mise/shims/claude", "/usr/bin/claude")

        /** For `soma front`: whether Grok is found here, and whether it is signed in, as the front agent looks. */
        fun found(): Triple<Boolean, Boolean, Boolean> {
            val home = System.getProperty("user.home")
            val places = PLACES.map { it.replaceFirst("~", home) }
            val grok = places.map { it.removeSuffix("claude") + "grok" }.any { File(it).canExecute() }
            return Triple(grok, File(home, ".grok/auth.json").isFile, places.any { File(it).canExecute() })
        }

        /** Where `soma front` keeps his choice of front agent, in Soma's folder. */
        const val FRONT_CHOICE_FILE = "front-agent"

        /**
         * `soma front [grok|haiku]`: writes his choice, then says who answers here and why. Exit 2 for any other word.
         * [home] is Soma's folder, [grok] and [signedIn] whether Grok is found and signed in here.
         */
        fun choose(words: List<String>, home: File, grok: Boolean, signedIn: Boolean, claude: Boolean = true, out: (String) -> Unit): Int {
            val word = words.firstOrNull()?.lowercase()
            when (word) {
                null -> {}
                "grok", "haiku" -> { File(home, FRONT_CHOICE_FILE).writeText("$word\n"); out("Chosen: $word, from the next request.") }
                else -> { out("usage: soma front [grok|haiku]"); return 2 }
            }
            val chosen = runCatching { File(home, FRONT_CHOICE_FILE).readText().trim() }.getOrNull()
            out(when {
                chosen == "haiku" && !claude -> "Haiku is chosen but Claude Code is not installed here, so nobody answers first. Switch with: soma front grok"
                chosen == "haiku" -> "The front agent is Haiku (Claude Code). Switch with: soma front grok"
                !grok -> "Grok is chosen but not installed here, so Haiku answers if Claude Code is. Install it with: npm i -g @xai-official/grok"
                !signedIn -> "Grok is chosen but not signed in here, so Haiku answers if Claude Code is. Sign in with: grok login"
                else -> "The front agent is Grok. Switch back with: soma front haiku"
            })
            return 0
        }

        private const val SESSION = "front-session"
        private const val ANSWERING = "Answering"
        private const val TIMEOUT_SECONDS = 120L
    }
}
