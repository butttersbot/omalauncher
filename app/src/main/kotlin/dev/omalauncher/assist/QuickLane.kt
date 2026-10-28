package dev.omalauncher.assist

import android.content.Context
import dev.omalauncher.App
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.oma.BACKGROUND_AGENTS
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.QUICK_AGENT
import dev.omalauncher.oma.TabBook
import dev.omalauncher.oma.heard
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.UNDECIDED_AGENT
import dev.omalauncher.phone.HomeDoor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The front agent: a fast model that hears a request before the build lane does and answers it, or hands his
 * words on unchanged, to [AgentTasks] on the phone or to a project thread on the Mac through [MacThreads]
 * (Boss, 2026-10-13 and 2026-10-14; the rules are in FrontAgent.kt). A process, a state and a Claude
 * conversation of its own, one request at a time: what he sends meanwhile waits in [queue] and is read next, in
 * the order he sent it. It can look and change nothing: stopping, restarting and cleaning up are a tab's
 * menu's, and it is never asked for an approval or able to answer one: what its command does not allow is denied
 * without asking. It writes no status, and the tiles and the ring report the build task only. If it fails with
 * nothing to show, the request goes to the build lane as if it had never been here.
 */
object QuickLane {
    @Volatile var working = false; private set
    @Volatile var answer = ""; private set
    @Volatile var label = "Ready"; private set
    /** Which task of the Oma thread the request it has now is. */
    @Volatile var time = 0L; private set
    /** The Claude session the next request follows up on. */
    @Volatile private var thread: String? = null
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    /** Only touched under the lock. Each one is also a waiting entry in the thread, which is where the chat reads them. */
    private val queue = FrontQueue()
    /** The agent mail a waiting entry is, by its time; only touched under the lock. */
    private val mails = HashMap<Long, AgentMail>()
    private var restored = false

    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        thread = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("thread", null)
    }

    /**
     * Whether there is a front agent to ask: Grok or Claude Code is it ([agent]), and what it hands over needs a
     * default agent that works in the background. One that opens in a terminal is sent to as it always was.
     */
    fun ready(context: Context): Boolean =
        agent(context) != null && (context.applicationContext as App).runtime.agents.default?.bin in BACKGROUND_AGENTS

    /**
     * Who answers the next request (Boss, 2026-10-18: Grok, with Haiku kept to switch back): Grok once it is installed
     * and signed in in the guest, unless `oma front haiku` chose Haiku; Haiku on Claude Code otherwise. Looked at for
     * each request, so a sign-in or a switch counts from the next one.
     */
    fun agent(context: Context): FrontAgent? {
        val runtime = (context.applicationContext as App).runtime
        val installed = runtime.agents.installed
        val chosen = runCatching { java.io.File(runtime.proot.home, FRONT_CHOICE).takeIf { it.isFile }?.readText() }.getOrNull()
        return FrontAgent.pick(chosen, installed.any { it.bin == "grok" }, java.io.File(runtime.proot.home, GROK_AUTH).isFile, installed.any { it.bin == "claude" })
    }

    /**
     * Asks the front agent first if [frontFirst] allows it. False when it wasn't asked and nothing is in
     * the thread: the caller then sends [prompt] to the build lane as it would have without a front agent.
     * [FrontQueueFull] is not that: it is thrown on, so the caller tells him rather than sending his words
     * past the front agent.
     */
    fun offer(context: Context, prompt: String, away: Boolean = false): Boolean {
        if (!frontFirst(prompt, 0, ready(context))) return false
        if (forTheBuilder(prompt, ChatLog.of(context).tasks.filter { it.chat.isEmpty() }, System.currentTimeMillis(), AgentTasks.working)) return false
        return try {
            ask(context, prompt, away)
            true
        } catch (full: FrontQueueFull) {
            throw full
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Puts [prompt] in the thread, undecided, and starts the front agent on it; or, while it is still on the
     * request before, files it as waiting to be read next. If it throws, nothing was asked: [FrontQueueFull]
     * when as many as fit already wait. [away] is a request spoken on the listening screen, which has closed
     * by the time there is an answer: that answer comes to him over whatever is in front, as a finished task's does.
     * [chat] is the new chat he sent it in, empty for the Oma tab: its entry is that chat's, the front agent hears
     * it in that chat's own conversation, and what it hands on goes to a new thread on the Mac, never the phone.
     */
    @Synchronized fun ask(context: Context, prompt: String, away: Boolean = false, chat: String = "") = ask(context, prompt, away, chat, mail = null)

    /**
     * Another agent's [mail] for the front agent to answer or hand over, as [mailToFront] decides (Boss, 2026-10-19):
     * shown as agent mail, and read inside [AgentMail.frontWrapper]. One it hands over goes to a builder as mail always
     * did ([AgentTasks.mail]). If it throws, nothing was asked.
     */
    @Synchronized fun mail(context: Context, mail: AgentMail) = ask(context, mail.text, away = false, chat = "", mail = mail)

    private fun ask(context: Context, prompt: String, away: Boolean, chat: String, mail: AgentMail?) {
        // A waiting request is read long after the screen that sent it may have gone, so nothing here keeps that screen.
        val app = context.applicationContext
        restore(app)
        AgentTasks.restore(app)
        check(prompt.isNotBlank()) { "Type or speak a request first." }
        check(prompt.length <= FRONT_MAX_REQUEST) { "That request is too long to ask first." }
        check((app as App).runtime.bootstrap.state.value == LinuxState.Installed) { "Open Omalauncher and finish Linux setup first." }
        // If it is handed over, it is sent from the page he was on when he asked, not the one he has turned to since.
        val page = HomeDoor.home?.showing
        if (!working) return read(app, prompt, page, away, waited = null, chat = chat, mail = mail)
        val log = ChatLog.of(app)
        // Filed under the build lane's lock for the same reason a request read at once is: no two tasks may take one time.
        synchronized(AgentTasks) {
            val filed = ChatHistory.stamp(log.tasks, System.currentTimeMillis())
            if (!queue.add(FrontWaiting(filed, prompt, page, away, chat))) throw FrontQueueFull()
            mail?.let { mails[filed] = it }
            log.begin(ChatTask(filed, UNDECIDED_AGENT, prompt, status = TaskStatus.Queued, chat = chat, mail = mail?.label.orEmpty()))
        }
    }

    /**
     * `[ try again ]` under a new chat's words the Mac was out of reach for ([chatRetried]): the same entry is read
     * again as if just sent, now or after what waits ahead of it, so the Mac is looked at as it stands now. Nothing
     * happens to any other entry, nor when as many as fit already wait.
     */
    @Synchronized fun again(context: Context, time: Long) {
        val app = context.applicationContext
        restore(app)
        AgentTasks.restore(app)
        val log = ChatLog.of(app)
        val task = log.tasks.firstOrNull { it.time == time } ?: return
        val retried = chatRetried(task) ?: return
        if (working && !queue.add(FrontWaiting(time, task.prompt, chat = task.chat))) return
        log.update(time, save = true) { chatRetried(it) ?: it }
        RoutingLog.corrected(app, task.prompt, MAC_TRIED_AGAIN)
        if (!working) read(app, retried.prompt, page = null, away = false, waited = time, chat = retried.chat)
    }

    /**
     * `[ cancel ]` on a request that still waits to be read: it leaves the queue and its entry keeps his words
     * and says so. One the front agent has begun on is not in the queue any more, and nothing happens.
     */
    @Synchronized fun cancel(context: Context, time: Long) {
        val gone = queue.cancel(time) ?: return
        mails.remove(gone.time)
        ChatLog.of(context).update(gone.time, save = true) { ChatHistory.dropped(it, FrontQueue.CANCELLED) }
    }

    /**
     * Stop beside the field: what waited to be read is not read after it, as a follow-up behind a stopped task is
     * not sent. On a new chat's tab ([chat]) only that chat's; on the Oma tab (null) everything.
     */
    @Synchronized fun dropWaiting(context: Context, chat: String? = null) {
        val log = ChatLog.of(context)
        queue.drop { chat == null || it.chat == chat }.forEach { waiting -> mails.remove(waiting.time); log.update(waiting.time, save = true) { ChatHistory.dropped(it, FrontQueue.STOPPED) } }
    }

    /**
     * Under the lock, as a request ends: the oldest that waited is read now. One that cannot even be begun
     * says so in its entry, and the one behind it gets its turn rather than waiting for ever.
     */
    private fun advance(context: Context) {
        while (true) {
            val next = queue.next() ?: return
            try {
                return read(context, next.prompt, next.page, next.away, waited = next.time, chat = next.chat, mail = mails.remove(next.time))
            } catch (e: Exception) {
                ChatLog.of(context).update(next.time, save = true) { it.copy(result = e.message ?: FrontQueue.UNREAD, status = TaskStatus.Failed) }
            }
        }
    }

    /**
     * Under the lock, with nothing being read: starts the front agent on [prompt]. [waited] is the time of the
     * waiting entry this request already is in the thread; null for one sent just now, which is filed here.
     * The board is made here and not when he sent it, so a request that waited is read with the builder and
     * the Mac as they stand now. A request of the new chat [chat] continues that chat's own conversation with the
     * front agent, never the Oma tab's, so nothing said elsewhere colours where its first words go.
     */
    private fun read(context: Context, prompt: String, page: String?, away: Boolean, waited: Long?, chat: String = "", mail: AgentMail? = null) {
        val runtime = (context.applicationContext as App).runtime
        // Checked as it is read, so a request that waited goes to whoever answers now; ready() was true when it was sent.
        // Grok runs only behind its command guard; if that can't be put in place, Haiku answers instead, or nobody.
        val agent = agent(context)?.let { chosen ->
            if (chosen != FrontAgent.Grok || writeGrokGuard(java.io.File(runtime.proot.home, GROK_FRONT_FOLDER))) chosen
            else FrontAgent.Haiku.takeIf { runtime.agents.installed.any { it.bin == "claude" } }
        } ?: error("Install and sign in to Grok or Claude Code in Omalauncher first.")
        // Only that agent's own conversation is followed up on: a Claude session means nothing to Grok.
        val resumed = frontSession(agent, if (chat.isEmpty()) thread else TabBook.of(context).chat(chat)?.front?.ifEmpty { null })
        val log = ChatLog.of(context)
        val began = System.currentTimeMillis()
        // The Mac's threads as the phone last heard of them: asking the Mac here would hold his request up. Null
        // when no Mac is set up, and then nothing below differs from a phone with no Mac at all.
        val mac = MacThreads.board(context, began)
        // How the builders are doing, looked at now from /proc, so "is it frozen?" is answered from what is so.
        val health = BuilderWatch.look(context)
        // Phone control off by his choice, or put back by itself, so the front agent says which.
        val phone = dev.omalauncher.phone.PhoneControlHeal.note(context, System.currentTimeMillis())
        // A task's time is what identifies it, and the build lane files its tasks under its own lock: held here
        // so the two can't take the same one, and so the board is the build lane as it stands at this moment.
        // Nothing of the build lane is changed.
        val (asked, command, asking) = synchronized(AgentTasks) {
            val a = AgentTasks.of(BuilderId.A)
            val b = AgentTasks.of(BuilderId.B)
            // Builder B is on the board once it has worked; until then the board is the one builder's, as it always was.
            val two = builderBShown(log.tasks, b.working)
            // Builder C likewise, once it has worked.
            val c = AgentTasks.of(BuilderId.C)
            val three = builderShown(BuilderId.C, log.tasks, c.working)
            // A's line is under its letter once another builder is shown beside it.
            val healthA = BuilderWatch.lines(health, two || three).first
            val healthB = BuilderWatch.line(health, BuilderId.B, two)
            val healthC = BuilderWatch.line(health, BuilderId.C, three)
            val board = buildBoard(log.tasks, a.working, a.label, began).copy(health = healthA)
            val second = if (two) buildBoard(log.tasks, b.working, b.label, began, BuilderId.B).copy(health = healthB) else null
            val third = if (three) buildBoard(log.tasks, c.working, c.label, began, BuilderId.C).copy(health = healthC) else null
            // Which builder a plain hand-over answers, decided from the boards it is read with, so what the front
            // agent saw and where his words go agree.
            val asking = askingBuilder(mapOf(
                BuilderId.A to board,
                BuilderId.B to (second ?: buildBoard(log.tasks, b.working, b.label, began, BuilderId.B)),
                BuilderId.C to (third ?: buildBoard(log.tasks, c.working, c.label, began, BuilderId.C)),
            ))
            val filed = waited?.also { time -> log.update(time, save = true) { it.copy(status = TaskStatus.Running) } }
                ?: ChatHistory.stamp(log.tasks, began).also { log.begin(ChatTask(it, UNDECIDED_AGENT, prompt, chat = chat, mail = mail?.label.orEmpty())) }
            // Its home changes are told apart from a builder's working at the same time (Soma Canvas slice 6).
            Triple(filed, "OMA_CANVAS_ACTOR=quick " + frontCommand(agent, frontPrompt(board, mail?.let { it.frontWrapper() + it.text } ?: prompt, mac, second, phone, third), resumed, mac = mac != null, newChat = chat.isNotEmpty(), mail = mail != null), asking)
        }
        time = asked; working = true; stopping = false; answer = ""; label = ANSWERING
        // A new chat's work that it answers in prose is not shown, and goes to the Mac as if it had said nothing.
        val holds = frontHolds(chat, prompt)
        thread(name = "oma-quick", isDaemon = true) {
            val late = AtomicBoolean()
            var next = resumed
            var handed = false
            var held = false
            var failed = false
            try {
                val child = runtime.proot.process(command).start()
                process = child
                if (stopping) RunEnd.end(child)
                // The lines are read until the process ends, so one that hangs is ended from beside it.
                thread(name = "oma-quick-timeout", isDaemon = true) {
                    if (!child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { late.set(true); RunEnd.end(child) }
                }
                var said = QuickAnswer()
                var session: String? = null
                var lost = false
                var its = false
                // The entry reads as a task just begun until the front agent shows it is answering this one itself.
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
                        // What it looks up for an answer that may yet be held back is not shown either.
                        is QuickEvent.Message -> if (!holds) for (step in event.steps) {
                            own()
                            log.update(asked) { ChatHistory.withStep(it, step) }
                            if (!stopping) label = "Looking"
                        }
                        is QuickEvent.Result -> {
                            if (event.failed) failed = true
                            // A failed first request may leave no conversation to return to.
                            if (!event.failed || resumed != null) next = event.session ?: next
                        }
                        QuickEvent.Fault -> failed = true
                        QuickEvent.Lost -> lost = resumed != null
                    }
                    // What Claude Code says of a run that failed is not an answer of the front agent's.
                    if (event is QuickEvent.Result && event.failed) return@forEachLine
                    said = said.after(event)
                    when (val read = frontRead(said.text, ended = event is QuickEvent.Result)) {
                        FrontRead.Undecided -> {}
                        // Prose can no longer turn into a mark, so the process is not waited for: with no answer
                        // shown, the request ends as one the front agent said nothing to, which a new chat sends to the Mac.
                        is FrontRead.Answer -> if (holds) {
                            held = true
                            session?.let { next = it }
                            RunEnd.end(child)
                        } else {
                            own()
                            answer = read.text
                            if (!stopping && event !is QuickEvent.Message) label = ANSWERING
                        }
                        // Handed on the moment the mark is whole; the process is not waited for, and what it says after is not read.
                        is FrontRead.HandOver -> {
                            val route = frontRoute(read.to, mac)
                            handed = if (mail != null) mailOver(context, asked, if (route == FrontRoute.Errand) mail.copy(toSession = "D") else mail) else if (chat.isNotEmpty()) toChat(context, asked, prompt, away, chat, route) else when (route) {
                                FrontRoute.Build -> pass(context, asked, prompt, page, away, asking)
                                FrontRoute.Errand -> pass(context, asked, prompt, page, away, asking, errand = true)
                                is FrontRoute.Mac -> passToMac(context, asked, prompt, route.thread, away, route.fresh)
                                is FrontRoute.Unknown -> unplaced(context, asked, prompt, away, route.named, mac?.threads?.map { it.id }.orEmpty())
                            }
                            session?.let { next = it }
                            RunEnd.end(child)
                        }
                    }
                }
                val code = child.waitFor()
                if (lost) next = null
                // Grok says nothing JSON of a conversation it can't find; failing before it named one is that.
                if (agent == FrontAgent.Grok && resumed != null && session == null && (failed || code != 0)) next = null
                if (stopping && session != null) next = session // A stopped request can still be followed up.
                if (late.get() || lost || code != 0) failed = true
            } catch (e: Exception) { failed = true }
            finally {
                var outcome = frontOutcome(handed, stopping, failed, answer.isNotBlank())
                // He may stop it in the moment it is being sent on: then it is not sent.
                if (outcome == FrontOutcome.FellBack && !(if (mail != null) mailOver(context, asked, mail) else if (chat.isNotEmpty()) toChat(context, asked, prompt, away, chat, route = null) else pass(context, asked, prompt, page, away))) {
                    outcome = FrontOutcome.Stopped
                }
                val status = when (outcome) {
                    FrontOutcome.Answered -> TaskStatus.Done
                    FrontOutcome.Stopped -> TaskStatus.Stopped
                    FrontOutcome.Broken -> TaskStatus.Failed
                    FrontOutcome.HandedOver, FrontOutcome.FellBack -> null
                }
                // In the thread before it stops being the running one, so the chat never shows it without its answer.
                // One that went to the build lane is that lane's entry now, or already says why it was refused.
                if (status != null) log.update(asked, save = true) { it.copy(result = answer, status = status) }
                if (away && (outcome == FrontOutcome.Answered || outcome == FrontOutcome.Broken)) {
                    AgentNotices.answered(context, if (outcome == FrontOutcome.Answered) "Done" else INCOMPLETE, answer, AgentNotices.FRONT)
                }
                synchronized(this) {
                    val kept = frontThread(agent, next)
                    if (chat.isNotEmpty()) TabBook.change(context) { it.heard(chat, kept) }
                    else {
                        thread = kept
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("thread", kept).apply()
                    }
                    process = null; working = false
                    // The last thing this request does: the next one takes over every field above.
                    advance(context)
                }
            }
        }
    }

    /**
     * Gives [prompt], his words and nothing of the board, to the build lane as the chat's Send does: to [builder]
     * when one asked him what his words answer ([askingBuilder]), and otherwise as new work to whichever builder
     * is free; sent now, or waiting behind the running task. The builder was not there
     * for what he and the front agent said since it was last sent anything, so that goes ahead of his words as
     * [handOverPreface] marks it, and while the other builder works, [PHONE_SHARED_NOTE] goes before that; the
     * entry, the notification and the logs keep his words alone. The entry sent
     * at [asked] then goes, since the build lane has filed the request as its own; if the build lane refuses,
     * the entry stays and says why, which is the only time a failure here is mentioned. False if he stopped it
     * first: nothing is sent then. Words of a new chat never come here: they go to the Mac ([toChat]).
     */
    private fun pass(context: Context, asked: Long, prompt: String, page: String?, away: Boolean, builder: BuilderId? = null, errand: Boolean = false): Boolean = synchronized(this) {
        if (stopping) false
        else {
            val log = ChatLog.of(context)
            // What went ahead of his words is from the Oma stream alone: a new chat's talk never reaches a builder.
            val said = log.tasks.filter { it.chat.isEmpty() }
            try {
                if (errand) AgentTasks.mail(context, frontPhoneMail("front-phone-$asked", prompt))
                else AgentTasks.handOver(context, prompt, page, builder) { to ->
                    val shared = builderShares(to, AgentTasks.builders.associate { it.id to it.working })
                    sharedPreface(shared, PHONE_SHARED_NOTE, handOverPreface(handOverContext(said, asked, gotBy(to))))
                }
                log.handedOver(asked)
            } catch (e: Exception) {
                val reason = e.message ?: "The request could not be sent."
                // As an entry of the front agent's it has `[ send to build ]` under it, for when the builder has room again.
                log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = reason, status = TaskStatus.Failed) }
                if (away) AgentNotices.answered(context, NOT_SENT, reason, AgentNotices.FRONT)
            }
            true
        }
    }

    /**
     * Gives agent [mail] the front agent handed over, or could not answer, to a builder as mail always went
     * ([AgentTasks.mail]): the builder files it as its own entry, so the one sent at [asked] goes. Wherever the front
     * agent said to send it, mail goes to the phone's builders. False if he stopped it first: nothing is sent then.
     */
    private fun mailOver(context: Context, asked: Long, mail: AgentMail): Boolean = synchronized(this) {
        if (stopping) false
        else {
            val log = ChatLog.of(context)
            try {
                AgentTasks.mail(context, mail)
                log.handedOver(asked)
            } catch (e: Exception) {
                log.update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = e.message ?: "The mail could not be given to a builder.", status = TaskStatus.Failed) }
            }
            true
        }
    }

    /**
     * Gives [prompt], his words, to [thread] on the Mac; [MacThreads] puts what the thread missed ahead of them
     * as it sends. The entry sent at [asked] becomes that thread's at once, and [MacThreads] asks the Mac and sends on a thread of its own, so the front agent
     * is free for his next request meanwhile. [fresh] is separate work on the project [thread] is busy with,
     * which [MacThreads] gives a new thread there. Nothing here can end with the phone's builder doing the work
     * instead. False if he stopped it first: nothing is sent then.
     */
    private fun passToMac(context: Context, asked: Long, prompt: String, thread: MacThread, away: Boolean, fresh: Boolean): Boolean = synchronized(this) {
        if (stopping) false
        else {
            MacThreads.handOver(context, asked, prompt, thread, away, fresh)
            true
        }
    }

    /**
     * Gives [prompt], words of the new chat [chat], to the Mac whatever the front agent did with them ([route] is
     * null when it said nothing it could be read by, held them back or failed): never to a phone builder (Boss,
     * 2026-10-13: "The plus button should always open up a Mac session"), and nothing asks him which project (Boss,
     * 2026-10-14: "i shouldn't be asked which project"). Words of a chat that has its thread go to that thread.
     * Before it has one, a new thread is made in the folder of the thread the front agent named ([chatBeside]),
     * or else in the default folder ([MacThreads.startChat]). An id not on the board is noted in the routing log.
     * False if he stopped it first: nothing is sent then.
     */
    private fun toChat(context: Context, asked: Long, prompt: String, away: Boolean, chat: String, route: FrontRoute?): Boolean = synchronized(this) {
        if (stopping) false
        else {
            if (route is FrontRoute.Unknown) RoutingLog.corrected(context, prompt, UNKNOWN_THREAD)
            val bound = TabBook.of(context).chat(chat)?.mac?.takeIf { it.isNotEmpty() }
            val own = bound?.let { id -> MacThreads.view.threads.firstOrNull { it.id == id } }
            // Beside a named thread only on the computer he picked for this chat; elsewhere it starts where he picked.
            val picked = TabBook.of(context).chat(chat)?.host
            val beside = chatBeside(route)?.takeIf { picked == null || macMachineOf(it.id) == picked }
            when {
                own != null -> MacThreads.handOver(context, asked, prompt, own, away)
                bound != null && route is FrontRoute.Mac -> MacThreads.handOver(context, asked, prompt, route.thread, away, route.fresh)
                beside != null -> MacThreads.handOver(context, asked, prompt, beside, away, fresh = true, newChat = true)
                else -> MacThreads.startChat(context, asked, prompt, away, chat)
            }
            true
        }
    }

    /**
     * The front agent named a thread that is not on the board. No thread is guessed and nothing is sent: the
     * entry keeps his words and asks which project he meant, with `[ send to build ]` under it in case it was
     * phone work after all. A new chat's words never come here: they go to the Mac ([toChat]). Noted in the
     * routing log. False if he stopped it first.
     */
    private fun unplaced(context: Context, asked: Long, prompt: String, away: Boolean, named: String, board: List<String>): Boolean = synchronized(this) {
        if (stopping) false
        else {
            // What it named and what was there: the routing log keeps only that it missed (Boss, 2026-10-17, FF4 asked twice).
            android.util.Log.w("QuickLane", "Front agent named thread \"${named.take(MAC_ID_CHARS)}\"; the board had ${board.joinToString(", ")}")
            ChatLog.of(context).update(asked, save = true) { it.copy(agent = QUICK_AGENT, result = WHICH_PROJECT, status = TaskStatus.Failed) }
            RoutingLog.corrected(context, prompt, UNKNOWN_THREAD)
            if (away) AgentNotices.answered(context, INCOMPLETE, WHICH_PROJECT, AgentNotices.FRONT)
            true
        }
    }

    /**
     * Ends the request it has now. One still undecided is not sent anywhere; what it had said so far stays in
     * the thread. What waits behind it is read next, as it would have been had this one ended by itself.
     */
    /** Desktop Stop applies to this chat only, including its queued words. */
    @Synchronized fun stopChat(context: Context, chat: String) {
        dropWaiting(context, chat)
        if (ChatLog.of(context).tasks.firstOrNull { it.time == time }?.chat == chat) stop()
    }

    @Synchronized fun stop() {
        if (!working) return
        stopping = true
        process?.let { RunEnd.end(it) }; label = "Stopping"
    }

    private const val PREFS = "quick-lane"
    private const val ANSWERING = "Answering"
    /** What the card over a spoken request's answer says in place of Oma's name when it isn't a whole answer. */
    private const val INCOMPLETE = "Oma needs attention"
    private const val NOT_SENT = "Not sent"
    /** Far past the few seconds an answer takes, a look at the phone or the web included. */
    private const val TIMEOUT_SECONDS = 120L
}
