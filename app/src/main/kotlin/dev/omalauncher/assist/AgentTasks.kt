package dev.omalauncher.assist

import android.content.*
import dev.omalauncher.App
import dev.omalauncher.linux.*
import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.bundledMailHistory
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.FollowUp
import dev.omalauncher.oma.FollowUpQueue
import dev.omalauncher.oma.Markdown
import dev.omalauncher.oma.ScreenShots
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.phone.HomeDoor
import dev.omalauncher.phone.PHONE_DISCONNECTED
import dev.omalauncher.phone.PhoneAccess
import dev.omalauncher.phone.PhoneBridge
import dev.omalauncher.phone.PhoneLink
import java.io.File
import java.io.BufferedWriter
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The phone's builders, as one object every caller has always reached (Boss, 2026-10-13: two of them, so one
 * at work never holds up the next request). Each [Builder] runs one request at a time and has its own Claude
 * conversation, follow-up queue and approvals; what is read here of them all is their sum or the one that
 * leads, and what is sent here goes to the builder [pickBuilder] says. Every builder takes this object's lock,
 * so the threads of the chat are filed one at a time and the approvals' notification slots are never shared.
 */
object AgentTasks {
    val builders: List<Builder> = BuilderId.entries.take(PHONE_BUILDERS).map(::Builder)

    fun of(id: BuilderId): Builder = builders[id.ordinal]

    /** Whether any builder has a task. */
    val working: Boolean get() = builders.any { it.working }

    /** The builder the notification, the tiles and the status file follow: the first that works, or the one that last changed. */
    @Volatile private var lead = BuilderId.A
    private fun leading(): Builder = builders.firstOrNull { it.working } ?: of(lead)

    /** The leading builder's label as it says it, which is what the status file and the lock widget read a state from. */
    val label: String get() = leading().label

    /** The leading builder's state in words for the notification and the tile, naming builder B and how many others work besides it. */
    val headline: String get() = leading().let { led -> poolLabel(led.id, led.label, builders.count { it.working && it !== led }) }
    val answer: String get() = leading().answer

    /** The leading builder's running task, as [Builder] records it for [BuilderWatch]; each builder's own is on it. */
    val startedAt: Long get() = leading().startedAt
    val heardAt: Long get() = leading().heardAt
    val lastStep: String get() = leading().lastStep
    val lastStepAt: Long get() = leading().lastStepAt
    /** How many follow-ups wait behind the leading builder's task. */
    val queued: Int get() = leading().waiting

    /** The builder the notification follows now. */
    val leadId: BuilderId get() = leading().id

    /**
     * Every builder's approvals waiting for the owner, each builder's oldest first. Each builder publishes its own
     * as one immutable list, so no reader (the phone control guard above all) can see "none waiting" for a
     * builder while it still holds an entry.
     */
    val pending: List<PendingApproval> get() = builders.flatMap { it.pending }
    val approvals: List<AgentApproval> get() = pending.map { it.approval }

    /** Whether any builder has a conversation that its next request continues. */
    val continuing: Boolean get() = builders.any { it.thread != null }

    /** The home page of the request that began last, and how long its task ran: a widget made meanwhile lands there. */
    val asked: PageAsk? get() = builders.mapNotNull { it.asked }.maxByOrNull { it.started }

    private var restored = false
    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        builders.forEach { it.restore(context) }
        AgentMailReceipts.interrupted(context)
        AgentNotices.clearStale(context)
    }

    /** The builder that gets a request for [named], or new work when that is null: see [pickBuilder]. */
    @Synchronized fun pick(named: BuilderId? = null): BuilderId = pickBuilder(named, builders.associate { it.id to BuilderLoad(it.working, it.waiting) })

    /**
     * Starts [prompt] now on [builder], or on a free one for new work; refused if that one is working. [screen]
     * and [page] are as [Builder.send] has them.
     */
    @Synchronized fun send(context: Context, prompt: String, images: List<String> = emptyList(), screen: File? = null, page: String? = HomeDoor.home?.showing, builder: BuilderId? = null) {
        restore(context)
        of(pick(builder)).send(context, prompt, images, screen, page)
    }

    /**
     * Sends [prompt] to [builder], or for new work to the builder [pick] chooses: now if it is free, or behind
     * its task. [earlier] is as [Builder.followUp] has it, and [screen] is this object's to delete once this
     * returns. Says which builder took it.
     */
    @Synchronized fun followUp(context: Context, prompt: String, images: List<String> = emptyList(), page: String? = HomeDoor.home?.showing, earlier: String = "", screen: File? = null, builder: BuilderId? = null): BuilderId {
        restore(context)
        val to = pick(builder)
        of(to).followUp(context, prompt, images, page, earlier, screen)
        return to
    }

    /**
     * As [followUp], with what goes ahead of his words made for the builder that takes them: what it missed is
     * what was said since that builder was last sent anything, which only the choice of builder can say.
     */
    @Synchronized fun handOver(context: Context, prompt: String, page: String?, builder: BuilderId?, errand: Boolean = false, earlier: (BuilderId) -> String): BuilderId {
        restore(context)
        val to = pick(builder)
        of(to).followUp(context, prompt, page = page, earlier = earlier(to), errand = errand)
        return to
    }

    /**
     * His words straight to a builder, past the front agent (pictures with them, or a view on the work: see
     * forTheBuilder), with what he and the front agent said since that builder last heard from him put before
     * them, as a hand-over has. Without it, "yeah, let's do it" reached the builder with nothing to say what "it" was
     * (Boss, 2026-10-15). Sent now if the builder is free, behind its task if not. Says which builder took it.
     */
    @Synchronized fun direct(context: Context, prompt: String, images: List<String> = emptyList(), page: String? = HomeDoor.home?.showing, screen: File? = null, builder: BuilderId? = null): BuilderId {
        restore(context)
        val to = pick(builder)
        val said = ChatLog.of(context).tasks.filter { it.chat.isEmpty() }
        val shared = builderShares(to, builders.associate { it.id to it.working })
        of(to).followUp(context, prompt, images, page, sharedPreface(shared, PHONE_SHARED_NOTE, handOverPreface(handOverContext(said, Long.MAX_VALUE, gotBy(to)))), screen)
        return to
    }

    /**
     * Another agent's [mail] (AgentMail.kt): to the builder it names, else to the one [pick] chooses, now if that
     * one is free or behind its task. The chat shows it as agent mail, never as his words, and the agent gets it
     * inside the wrapper that says who wrote it. Says which builder took it.
     */
    @Synchronized fun mail(context: Context, mail: AgentMail): BuilderId? {
        restore(context)
        // A phone job (PhoneJobs.kt): a ping is Soma's own to answer at once; the rest go to a builder with their steps.
        val job = phoneJob(mail.text)
        if (job?.kind == JobKind.Ping) {
            PhoneJobs.ping(context, mail, builders.associate { it.id to !it.working })
            return null
        }
        val to = pick(addressed(context, mail))
        if (job != null) of(to).followUp(context, mail.text, page = null, earlier = jobPlaybook(job, mail, to), mail = jobLabel(job, mail))
        else of(to).followUp(context, mail.text, page = null, earlier = mail.wrapper(to), mail = mail.label, agentMail = mail)
        return to
    }

    /** The builder [mail] names, if it names one ([mailBuilder]). */
    @Synchronized fun addressed(context: Context, mail: AgentMail): BuilderId? {
        restore(context)
        return mailBuilder(mail.toSession, builders.associate { it.id to it.thread })
    }

    /** `[ cancel ]` on a follow-up that still waits, whichever builder it waits for. */
    @Synchronized fun cancelFollowUp(context: Context, time: Long) = builders.forEach { it.cancelFollowUp(context, time) }

    /** `[ send now ]` on a follow-up that still waits: its builder's turn is stopped and this goes at once. */
    fun sendNow(context: Context, time: Long): Boolean = builders.any { it.sendNow(context, time) }

    /**
     * Answers the one approval with this [id] and [nonce], through the builder that holds it and no other. The
     * nonce is made on the phone for that one request, so no other builder can hold it; one that none holds is
     * answered through builder A, which finds it expired as an approval that ended always was.
     */
    fun answerApproval(context: Context, id: String, nonce: String, allow: Boolean): ApprovalOutcome {
        val holder = synchronized(this) { approvalHolder(waiting(), id, nonce) ?: BuilderId.A }
        return of(holder).decide(context, id, nonce, allow)
    }

    /** The builder whose approval [entry] is; null once none holds it. */
    fun holder(entry: PendingApproval): BuilderId? = approvalHolder(waiting(), entry.id, entry.nonce)

    private fun waiting(): Map<BuilderId, List<PendingApproval>> = builders.associate { it.id to it.pending }

    /** Stops [builder]'s task, or every builder's when it is null: stop beside the Oma tab's field means nothing more runs. */
    fun stop(context: Context, builder: BuilderId? = null) {
        if (builder == null) builders.forEach { it.stop(context) } else of(builder).stop(context)
    }

    /** The slots of every builder's waiting approvals but [builder]'s: under the lock, so no two notifications are given one. */
    internal fun slotsBesides(builder: Builder): Set<Int> = builders.filter { it !== builder }.flatMap { it.pending }.map { it.slot }.toSet()

    /** The Mac's work as the tiles, the ring and the lock widget tell it beside the builders'. */
    val mac: MacGlance get() = macGlance(MacThreads.view, MacThreads.pending)

    /** The Mac's work as they last told it, so a change of the Mac's that changes none of it draws nothing again. */
    @Volatile private var macShown = MacGlance()

    /** [from] changed: the ring, the tiles, the lock widget, the notification and the status file follow the leading builder. */
    internal fun changed(context: Context, from: Builder) {
        lead = from.id
        val led = leading()
        shown(context, succeeded = led.label == "Done")
        AgentNotices.task(context, led.working, headline, led.answer, pending.size, led.id)
    }

    /** A change of the Mac's not looked at yet, and whether a look runs: a burst of changes makes few looks. */
    private val macDirty = AtomicBoolean()
    private val macLooking = AtomicBoolean()

    /**
     * What the phone knows of the Mac's threads or their requests changed: the ring, the tiles, the lock widget and
     * the status file follow, off the caller's thread, since [MacThreads] tells this under its own lock and drawing
     * them must not hold it. The notification stays the builders' own; the Mac's requests have their own cards.
     * A Mac thread's turn ending is no success the phone can vouch for, so it never lights done.
     */
    fun macChanged(context: Context) {
        macDirty.set(true)
        if (!macLooking.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "oma-mac-shown", isDaemon = true) {
            try {
                while (macDirty.getAndSet(false)) if (mac != macShown) shown(app, succeeded = false)
            } finally {
                macLooking.set(false)
            }
            // A change that came just as the last look ended is looked at all the same.
            if (macDirty.get()) macChanged(app)
        }
    }

    private fun shown(context: Context, succeeded: Boolean) {
        val mac = mac
        macShown = mac
        // The ring follows the task from here, the one place every change of it passes: its approvals, and whether a task
        // that used the phone ended well. Working itself is lit only by the phone in use (PhoneService). Background work
        // left running on the Mac is not work in hand: it may run for hours, and a ring lit that long would say nothing.
        OmaRing.task(working || mac.working != null, pending.size + mac.approvals, succeeded)
        OmaTiles.refresh(context)
        OmaLockWidget.refresh(context)
        writeStatus(context, mac)
    }

    /**
     * The task as a widget can read it: a `file("~/.cache/oma/agent.json")` source sees every change the
     * moment it is written. Home has no strip or note of its own for this; the widget is the place for it.
     */
    /** Phone control came, went or was found disconnected: the status file says so. Nothing about the task has changed. */
    fun phoneChanged(context: Context) {
        restore(context)
        writeStatus(context, mac)
    }

    private fun writeStatus(context: Context, mac: MacGlance) {
        runCatching {
            val file = File((context.applicationContext as App).runtime.proot.home, AGENT_STATUS_FILE)
            file.parentFile?.mkdirs()
            val builders = agentStatus(working, label, answer, pending.size, System.currentTimeMillis() / 1000, PhoneAccess.link(context))
            // The builders' line is about the last turn's builder; its tab is where a tap goes.
            val tab = runCatching { dev.omalauncher.oma.lastTurnTab(ChatLog.of(context).tasks, dev.omalauncher.oma.TabBook.of(context)) }.getOrNull()
            file.writeText(omaStatus(builders, mac, tab.orEmpty()).toString())
        }
    }
}

/**
 * One of the phone's builders. A single background request stays alive with the existing Linux foreground service.
 * Claude requests continue one conversation, resumed by session id.
 * What he sends while a request runs waits in [followUps] and is sent as the next request when it has finished.
 * Builder A keeps what was saved before there were two; every builder takes the lock of [AgentTasks].
 */
class Builder(val id: BuilderId) {
    /** Read when taken, not kept: a builder is made while [AgentTasks] itself is still being made. */
    private val lock: Any get() = AgentTasks
    @Volatile var working = false; private set
    /** Whether the current request publishes Claude's structured tool events; read by the watchdog. */
    @Volatile var traced = false; private set
    @Volatile var answer = ""; private set
    @Volatile var label = "Ready"; private set
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    /**
     * The approvals waiting for the owner, each with what its notification and buttons are keyed by. This one
     * immutable list is all that is published of the ledger, so no reader (the phone control guard above all)
     * can see "none waiting" from one field while another still holds an entry.
     */
    @Volatile var pending: List<PendingApproval> = emptyList(); private set
    val approvals: List<AgentApproval> get() = pending.map { it.approval }
    private val ledger = ApprovalLedger()
    /** Only touched under the lock. Each one is also a waiting task in the thread, which is where the chat reads them. */
    private val followUps = FollowUpQueue()
    /** How many follow-ups wait behind the task, for choosing where new work waits. */
    val waiting: Int get() = synchronized(lock) { followUps.waiting.size }
    /** The Claude session that the next request follows up on. */
    @Volatile var thread: String? = null; private set
    private var inputWriter: BufferedWriter? = null
    private var restored = false
    /** Which task of the Oma thread the running request is. */
    @Volatile private var taskTime = 0L
    /** The home page the latest request was sent from, and how long its task ran: a widget made meanwhile lands there. */
    @Volatile var asked: PageAsk? = null; private set
    /**
     * When the running task began, when its agent last printed a line of any kind, and its last tool step and
     * when: only recorded here, for [BuilderWatch] to tell a builder that works from one that is stuck.
     */
    @Volatile var startedAt = 0L; private set
    @Volatile var heardAt = 0L; private set
    @Volatile var lastStep = ""; private set
    /** Its latest tool call works the phone ([phoneToolCall]): what lights the ring's working, with any other builder's. */
    @Volatile var phone = false; private set

    private fun onPhone(now: Boolean) {
        if (phone == now) return
        phone = now
        OmaRing.phone(AgentTasks.builders.any { it.phone })
    }
    @Volatile var lastStepAt = 0L; private set
    /** Where this builder's answer and conversation are kept: builder A's are where the one builder's always were. */
    private val prefs = if (id == BuilderId.A) "assistant-result" else "assistant-result-" + id.letter.lowercase()
    fun restore(context: Context): Unit = synchronized(lock) {
        if (restored) return
        restored = true
        val saved = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        answer = saved.getString("answer", "") ?: ""
        label = saved.getString("label", "Ready") ?: "Ready"
        thread = saved.getString("thread", null)
    }
    /**
     * [screen] is the screenshot behind the "this screen" chip, and only that: once this returns the file is
     * gone from where the chip kept it. If it throws, nothing was sent and the file is still the caller's.
     * [page] is the id of the home page he was on when he asked: the one home shows, unless the caller knows better.
     */
    fun send(context: Context, prompt: String, images: List<String> = emptyList(), screen: File? = null, page: String? = HomeDoor.home?.showing): Unit = synchronized(lock) {
        // Mail may already be collecting while the agent itself is idle. Owner requests keep their place.
        if (followUps.waiting.isNotEmpty()) return followUp(context, prompt, images, page, screen = screen)
        start(context, prompt, images, screen, page, queued = null)
    }
    /**
     * A message sent while a request runs. It is in the thread at once as his, waiting; the running agent is
     * not told, stopped or restarted, so it can't answer or skip an approval. It is sent as a request of its
     * own when the one ahead has finished, and dropped, with the thread saying why, if that one is stopped or
     * fails or the app is closed first. With nothing running it is simply sent. [earlier] is what the agent is
     * sent ahead of [prompt] and he is never shown: the context of a request the front agent hands over.
     * [screen] is as for [send]: the screenshot of a spoken request, which waits with it and is this object's
     * to delete once this returns.
     */
    fun followUp(context: Context, prompt: String, images: List<String> = emptyList(), page: String? = HomeDoor.home?.showing, earlier: String = "", screen: File? = null, mail: String = "", errand: Boolean = false, agentMail: AgentMail? = null): Unit = synchronized(lock) {
        restore(context)
        check(prompt.isNotBlank()) { "Type or speak a request first." }
        if (!working && agentMail == null && followUps.waiting.isEmpty()) return start(context, prompt, images, screen, page, queued = null, earlier = earlier, mail = mail, errand = errand)
        check(!working || !stopping) { "The agent is stopping. Send this again in a moment." }
        val agents = (context.applicationContext as App).runtime.agents
        val agent = builderAgent(id, agents.installed, agents.default)?.agent ?: error("Install and sign in to an agent in Omalauncher first.")
        val log = ChatLog.of(context)
        val time = ChatHistory.stamp(log.tasks, System.currentTimeMillis())
        if (agentMail != null && AgentMailReceipts.seen(context, agentMail)) return
        if (agentMail != null && followUps.waiting.any { it.mailSender == agentMail.replyAddress && it.mailId == agentMail.id }) return
        val routing = agentMail?.routing ?: emptyMap()
        val incoming = FollowUp(time, prompt, images, page, earlier, screen, errand,
            mailSender = agentMail?.replyAddress.orEmpty(), mailId = agentMail?.id.orEmpty(),
            mailTask = routing["task"].orEmpty(), supersedes = routing["supersedes"].orEmpty())
        val replaced = followUps.supersededBy(incoming)
        check(followUps.add(incoming)) { FollowUpQueue.FULL }
        replaced?.let {
            dropped(context, listOf(it), "Superseded by agent mail ${incoming.mailId} before delivery.")
            AgentMailReceipts.state(context, listOf(it.time), "superseded")
        }
        log.begin(ChatTask(time, agent.name, prompt, images, screen = screen != null, status = TaskStatus.Queued, builder = id.saved, mail = mail))
        if (agentMail != null) AgentMailReceipts.queued(context, agentMail, time)
        if (!working) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            synchronized(lock) { if (!working) advance(context, TaskStatus.Done) }
        }, 1_000)
    }
    /**
     * `[ cancel ]` on a follow-up that still waits: it leaves the queue and the thread says so. One that has
     * begun is no longer in the queue and is not touched, and nothing of the running task changes.
     */
    fun cancelFollowUp(context: Context, time: Long): Unit = synchronized(lock) {
        followUps.cancel(time)?.let { dropped(context, listOf(it), FollowUpQueue.CANCELLED) }
    }
    /**
     * [queued] is the time of the waiting task this request already is in the thread; null for one sent just now.
     * An [errand] runs on [ERRAND_MODEL] in a session of its own: the builder's own conversation is neither resumed,
     * which would make every step read all of it, nor replaced, so its next real task goes on where it left off.
     */
    private fun start(context: Context, prompt: String, images: List<String>, screen: File?, page: String?, queued: Long?, earlier: String = "", mail: String = "", errand: Boolean = false): Unit = synchronized(lock) {
        restore(context)
        check(prompt.isNotBlank()) { "Type or speak a request first." }
        check(!working) { "An agent is already working. Stop it before starting another task." }
        val runtime = (context.applicationContext as App).runtime
        check(runtime.bootstrap.state.value == LinuxState.Installed) { "Open Omalauncher and finish Linux setup first." }
        val choice = builderAgent(id, runtime.agents.installed, runtime.agents.default) ?: error("Install and sign in to an agent in Omalauncher first.")
        val agent = choice.agent
        val seen = if (screen != null) "\n\nThe attached image is the screen I was looking at when I asked." else ""
        val imagePrompt = earlier + (if (agent.bin == "claude" && images.isNotEmpty()) prompt + "\n\nRead these attached images before answering:\n" + images.joinToString("\n") else prompt) + seen
        val fullPrompt = imagePrompt + "\n\n" + PhoneBridge.INSTRUCTIONS + "\n" + GROKBOT_RULE + "\nAndroid build tools, when installed, are in /opt/android-sdk. Run oma-build-check to check the toolchain. Run Gradle as `oma-gradle TASKS` (oma-build does): one build at a time and small, so Android does not kill the app for memory. Use the approval UI for blocked tools; never ask for blanket permission bypass.\n" + dev.omalauncher.oma.PhoneArtifacts.INSTRUCTIONS + "\n"
        val resumed = when {
            errand -> null
            agent.bin == "claude" -> thread?.takeUnless { it.startsWith(CodexProtocol.THREAD_PREFIX) }
            agent.bin == "codex" -> thread?.takeIf { it.startsWith(CodexProtocol.THREAD_PREFIX) }?.removePrefix(CodexProtocol.THREAD_PREFIX)
            else -> null
        }
        // Decided as it starts, not as it was sent: work that waited may now run alone, or beside the other builder.
        val worktree = builderWorktree(id).takeIf { builderWorktreeWanted(id, AgentTasks.builders.associate { it.id to it.working }, agent.bin == "claude" || agent.bin == "codex") }
        // Claude gets the screenshot inside the message, so its file goes now. Codex can only read a file, which lasts until its task ends.
        var shot: String? = null
        var handed: File? = null
        val command = if (agent.bin == "claude") {
            if (screen != null) {
                check(screen.isFile && screen.length() in 1..MAX_SCREENSHOT_BYTES) { "The screenshot is no longer available. Remove it and send again." }
                shot = java.util.Base64.getEncoder().encodeToString(screen.readBytes())
                screen.delete()
            }
            // Which builder this is, for the tools that sign mail (oma msg, oma-macbuild): a reply comes back to it, not to A.
            "export OMA_BUILDER=${id.letter} SOMA_THREAD=${id.thread}\n" + (worktree?.let { it.script() + "\n" } ?: "") + claudeStreamCommand(resumed, if (errand) ERRAND_MODEL else null)
        } else if (agent.bin == "codex") {
            // As Claude's: its words go in once the worktree step has said where it works (OMA-READY ends that step).
            handed = screen?.let { ScreenShots.handOver(it, runtime.proot.promptAttachments) }
            "export OMA_BUILDER=${id.letter} SOMA_THREAD=${id.thread}\n" + (worktree?.let { it.script() + "\n" } ?: "") + "echo $CODEX_READY\n" +
                codexBuilderCommand(images + listOfNotNull(handed?.let(runtime.proot::guestPath)), choice.model, resumed, fullAccess = id == BuilderId.B)
        } else {
            backgroundAgentCommand(agent.bin, fullPrompt, images, choice.model, codexFullAccess = id == BuilderId.B) // Validate before the screenshot is touched.
            handed = screen?.let { ScreenShots.handOver(it, runtime.proot.promptAttachments) }
            "export OMA_BUILDER=${id.letter} SOMA_THREAD=${id.thread}\n" + backgroundAgentCommand(agent.bin, fullPrompt, images + listOfNotNull(handed?.let(runtime.proot::guestPath)), choice.model, codexFullAccess = id == BuilderId.B)
        }
        val log = ChatLog.of(context)
        val began = System.currentTimeMillis()
        val time = queued ?: ChatHistory.stamp(log.tasks, began)
        taskTime = time
        startedAt = began; heardAt = began; lastStep = ""; lastStepAt = 0L
        asked = page?.let { PageAsk(it, began) }
        if (queued == null) log.begin(ChatTask(time, agent.name, prompt, images, screen = screen != null, newThread = resumed == null, answeredBy = builderName(agent.bin), builder = id.saved, mail = mail))
        else log.update(time, save = true) { it.copy(agent = agent.name, newThread = resumed == null, status = TaskStatus.Running, answeredBy = builderName(agent.bin)) }
        choice.note?.let { note -> log.update(time, save = true) { it.copy(steps = it.steps + note) } }
        traced = agent.bin == "claude"
        working = true; stopping = false; ledger.clear(); published(); answer = ""; label = "${agent.name} is working"
        notify(context)
        thread(name = "assistant-agent-" + id.letter.lowercase(), isDaemon = true) {
            try {
                val child = runtime.proot.process(command).start()
                process = child
                if (stopping) RunEnd.end(child)
                if (agent.bin == "claude") {
                    synchronized(lock) { inputWriter = child.outputStream.bufferedWriter() }
                    writeEvent(JSONObject().put("type", "control_request").put("request_id", "oma-init")
                        .put("request", JSONObject().put("subtype", "initialize")))
                    var sent = false
                    var session: String? = null
                    var lost = false
                    var named = ""
                    // What it is sent: told of its own worktree once the step before it says one was had.
                    var words = fullPrompt
                    child.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            heardAt = System.currentTimeMillis()
                            val given = if (sent) null else worktree?.made(line)
                            if (given != null) { words = withWorktreeNote(fullPrompt, given.note); continue }
                            val event = runCatching { JSONObject(line) }.getOrNull()
                            if (event == null) {
                                if (resumed != null && line.startsWith("No conversation found")) lost = true
                                continue
                            }
                            if (!sent && event.optString("type") == "control_response" && event.optJSONObject("response")?.optString("request_id") == "oma-init") {
                                check(event.getJSONObject("response").optString("subtype") != "error") { "Agent initialization failed" }
                                writeEvent(ClaudeProtocol.user(words, shot)); shot = null; sent = true
                            }
                            ClaudeProtocol.approval(event)?.let { admit(context, it) }
                            // The reply is named after the model Claude Code says answers it, whatever alias it was started with.
                            streamAgentName(event)?.takeIf { it != named }?.let { name ->
                                named = name
                                log.update(time, save = true) { it.copy(answeredBy = name) }
                            }
                            when (event.optString("type")) {
                                "control_cancel_request" -> {
                                    val gone = synchronized(lock) {
                                        ledger.withdraw(event.optString("request_id"))?.also {
                                            published()
                                            if (approvals.isEmpty() && !stopping) label = "${agent.name} is working"
                                        }
                                    }
                                    if (gone != null) { record(context, gone, ApprovalAnswer.Withdrawn); AgentNotices.withdraw(context, gone); notify(context) }
                                }
                                "assistant" -> {
                                    val content = event.optJSONObject("message")?.optJSONArray("content")
                                    if (content != null) for (i in 0 until content.length()) {
                                        val block = content.optJSONObject(i) ?: continue
                                        when (block.optString("type")) {
                                            "text" -> answer = (answer + block.optString("text") + "\n").takeLast(40_000)
                                            "tool_use" -> {
                                                val step = ClaudeProtocol.step(block)
                                                lastStep = step; lastStepAt = System.currentTimeMillis()
                                                // On the phone from a phone command until its next tool of any other kind: a pause to think between
                                                // two phone steps is still phone use, and the ring goes out the moment it turns to anything else.
                                                onPhone(phoneToolCall(block.optString("name"), block.optJSONObject("input")?.optString("command").orEmpty()))
                                                log.update(time) { ChatHistory.withStep(it, step) }
                                                // A file that is one of its artifacts, about to change: the panel says it is being edited.
                                                val input = block.optJSONObject("input")
                                                runCatching { dev.omalauncher.oma.PhoneArtifacts.touched(context, id, time, block.optString("name"), input?.optString("file_path").orEmpty(), input?.optString("command").orEmpty(), agent.name) }
                                                if (approvals.isEmpty()) { label = "Using ${block.optString("name").take(60)}"; notify(context) }
                                            }
                                        }
                                    }
                                }
                                "system" -> if (event.optString("subtype") == "init") session = event.optString("session_id").ifBlank { null }.also { runSession = it }
                                // A tool call's result: anything it put with soma-artifact is told to the chat now, under this entry.
                                "user" -> runCatching { dev.omalauncher.oma.PhoneArtifacts.poll(context, id, time, agent.name) }
                                "result" -> {
                                    // A failed first request may leave no conversation to return to.
                                    if (!errand && (!event.optBoolean("is_error") || resumed != null)) thread = event.optString("session_id").ifBlank { thread }
                                    val result = event.optString("result")
                                    if (result.isNotBlank()) answer = result.takeLast(40_000)
                                    if (event.optBoolean("is_error")) {
                                        answer = answer.ifBlank { event.optJSONArray("errors")?.join("\n") ?: "The agent needs attention." }
                                    }
                                    synchronized(lock) { inputWriter?.close(); inputWriter = null }
                                }
                            }
                        }
                    }
                    if (lost) {
                        thread = null
                        answer = "The earlier thread is no longer available. Send your request again to start a new one."
                    } else if (stopping && session != null && !errand) thread = session // A stopped request can still be followed up.
                } else if (agent.bin == "codex") {
                    // Its events, as Claude's stream is read: steps as it goes, its words as the answer. Anything else it
                    // prints (a sign-in problem, a crash) is kept only to say why when no answer came.
                    var other = ""
                    var words = fullPrompt + "\n" + CodexProtocol.NO_QUESTIONS + "\n"
                    var sent = false
                    var session: String? = null
                    child.inputStream.bufferedReader().forEachLine { line ->
                        heardAt = System.currentTimeMillis()
                        if (!sent) {
                            worktree?.made(line)?.let { words = withWorktreeNote(words, it.note); return@forEachLine }
                            if (line.trim() != CODEX_READY) return@forEachLine
                            child.outputStream.bufferedWriter().use { it.write(words) }
                            sent = true
                            return@forEachLine
                        }
                        when (val said = CodexProtocol.read(line)) {
                            is CodexProtocol.Said.Started -> session = said.thread
                            is CodexProtocol.Said.Text -> answer = (answer + said.text + "\n").takeLast(40_000)
                            is CodexProtocol.Said.Step -> {
                                lastStep = said.step; lastStepAt = System.currentTimeMillis()
                                onPhone(phoneToolCall(said.tool, said.command))
                                log.update(time) { ChatHistory.withStep(it, said.step) }
                                label = "Using ${said.tool}"; notify(context)
                            }
                            is CodexProtocol.Said.Failed -> answer = (answer + said.why + "\n").takeLast(40_000)
                            null -> if (runCatching { org.json.JSONObject(line) }.isFailure) other = (other + line + "\n").takeLast(4_000)
                        }
                    }
                    if (!sent) child.outputStream.runCatching { close() }
                    if (answer.isBlank()) answer = other.trim()
                    // Kept for his next words, as Claude's session is: unless the conversation it was asked to go on with is gone.
                    if (!errand) {
                        val code = child.waitFor()
                        thread = when {
                            session != null && (code == 0 || stopping || resumed != null) -> CodexProtocol.THREAD_PREFIX + session
                            resumed != null -> null.also { answer = answer.ifBlank { "The earlier thread is no longer available. Send your request again to start a new one." } }
                            else -> thread
                        }
                    }
                } else {
                    child.outputStream.close()
                    child.inputStream.bufferedReader().forEachLine { heardAt = System.currentTimeMillis(); answer = (answer + it + "\n").takeLast(40_000) }
                }
                val code = child.waitFor()
                label = if (stopping) "Stopped" else if (code == 0) "Done" else "Agent needs attention"
                if (answer.isBlank()) answer = if (code == 0) "Task completed." else "The agent exited with status $code. Open its terminal to check sign-in or permissions."
            } catch (e: Exception) { label = "Could not run agent"; answer = e.message ?: "Please open Omalauncher and try again." }
            finally {
                // A stopped run is idle only once it is gone, so the next request never runs beside it (RunEnd).
                if (stopping) process?.runCatching { waitFor(8, java.util.concurrent.TimeUnit.SECONDS) }
                // And its agent with it, by the agent's own processes: one ran on after "Send now" (Boss, 2026-10-23).
                if (stopping) RunEnd.endLingering(setOfNotNull(resumed, runSession), began)
                runSession = null
                // Whatever was still unanswered goes with the task, notifications included.
                val unanswered = synchronized(lock) { inputWriter?.runCatching { close() }; inputWriter = null; ledger.clear().also { published() } }
                unanswered.forEach { AgentNotices.withdraw(context, it) }
                shot = null; handed?.delete()
                val status = when { stopping -> TaskStatus.Stopped; label == "Done" -> TaskStatus.Done; else -> TaskStatus.Failed }
                log.update(time, save = true) { task ->
                    task.copy(result = answer, status = status, approvals = task.approvals.map { if (it.answer == ApprovalAnswer.Waiting) it.copy(answer = ApprovalAnswer.Unanswered) else it })
                }
                // What it put is told under its entry, and an edit it never finished stops showing.
                runCatching { dev.omalauncher.oma.PhoneArtifacts.settle(context, id, time) }
                // Its work is over, so is its use of the phone: the ring goes out at once.
                onPhone(false)
                // One step under the lock, so a follow-up that waited starts before anything else can find the agent idle.
                synchronized(lock) {
                    process = null; working = false
                    asked = asked?.let { if (it.started == began && it.ended == null) it.copy(ended = System.currentTimeMillis()) else it }
                    context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
                        .putString("label", label).putString("answer", answer).putString("thread", thread).apply()
                    File(context.filesDir, "assistant-last-result.txt").writeText(answer)
                    notify(context)
                    // A task the owner stopped says nothing more, and one with a follow-up now running has its answer in
                    // the thread, under a task notification that is ongoing again; any other ending brings its answer to him.
                    val next = advance(context, status)
                    if (!next && status != TaskStatus.Stopped) AgentNotices.answered(context, label, answer, AgentNotices.answerId(id))
                    // Once no builder works on it any more, the phone goes back to what he had in front (PhoneReturn.kt).
                    if (!next && AgentTasks.builders.none { it.working }) dev.omalauncher.phone.PhoneService.instance?.workEnded()
                }
            }
        }
    }
    /**
     * Under the lock, as a task ends with [status]: sends the follow-up that waited behind it, or says in the
     * thread why none will be. True when one is now running.
     */
    private fun advance(context: Context, status: TaskStatus): Boolean {
        val handoff = if (sendingNow) followUps.afterSendNow().also { sendingNow = false } else followUps.after(status)
        dropped(context, handoff.dropped, FollowUpQueue.FAILED)
        val next = handoff.next ?: return false
        return try {
            start(context, next.prompt, next.images, next.screen, next.page, queued = next.time, earlier = next.earlier, errand = next.errand)
            AgentMailReceipts.state(context, listOf(next.time) + next.bundled, "delivered")
            ChatLog.of(context).revise { bundledMailHistory(it, next) }
            true
        } catch (e: Exception) {
            // What stopped this one from starting would stop the ones behind it too.
            dropped(context, listOf(next) + followUps.drop(), e.message ?: FollowUpQueue.FAILED)
            AgentMailReceipts.state(context, next.bundled, "not-delivered")
            next.bundled.forEach { time -> ChatLog.of(context).update(time, save = true) {
                ChatHistory.dropped(it, e.message ?: FollowUpQueue.FAILED)
            } }
            false
        }
    }
    /** Marks follow-ups that will never be sent, with the [reason] where their answer would have been. A screenshot that waited with one goes. */
    private fun dropped(context: Context, unsent: List<FollowUp>, reason: String) {
        val log = ChatLog.of(context)
        AgentMailReceipts.state(context, unsent.map { it.time }, "not-delivered")
        unsent.forEach { followUp ->
            ScreenShots.discard(followUp.screen)
            log.update(followUp.time, save = true) { ChatHistory.dropped(it, reason) }
        }
    }
    private fun writeEvent(event: JSONObject): Unit = synchronized(lock) {
        val writer = inputWriter ?: error("The agent session has ended.")
        writer.write(event.toString()); writer.newLine(); writer.flush()
    }
    /** Under the lock: republishes the ledger for readers on other threads. */
    private fun published() { pending = ledger.live }
    /** A request from the agent's stream: shown to the owner, or denied unseen if it can't be shown safely. */
    private fun admit(context: Context, approval: AgentApproval) {
        fun logged(answer: ApprovalAnswer, key: String) = ChatLog.of(context).update(taskTime, save = true) {
            it.copy(approvals = it.approvals + ChatApproval(approval.tool, approval.detail, answer, key))
        }
        val shown = synchronized(lock) {
            if (stopping) return
            // No other builder's waiting approval has the slot this one is given, so neither notification replaces the other's.
            when (val admission = ledger.admit(approval, AgentTasks.slotsBesides(this))) {
                // In the thread as waiting before any reader can see it pending.
                is Admission.Shown -> { logged(ApprovalAnswer.Waiting, admission.entry.nonce); published(); label = "Approval needed"; admission.entry }
                Admission.Duplicate -> return
                Admission.Refused -> {
                    runCatching { writeEvent(ClaudeProtocol.decision(approval, false)) }
                    logged(ApprovalAnswer.Refused, "")
                    return
                }
            }
        }
        AgentNotices.request(context, shown, id)
        // Stop may have cleared the ledger between admitting and posting; its withdrawal then ran before there was anything to take down.
        if (pending.none { it.nonce == shown.nonce }) AgentNotices.withdraw(context, shown)
        notify(context)
    }
    /**
     * Answers this builder's one approval with this [id] and [nonce]; the notification buttons, the approval
     * screen and the chat thread all come through [AgentTasks.answerApproval] to here. Nothing is answered from a
     * locked phone, and an approval that was already answered, withdrawn, stopped or lost to a restart is
     * [ApprovalOutcome.Expired] and changes nothing.
     */
    internal fun decide(context: Context, id: String, nonce: String, allow: Boolean): ApprovalOutcome {
        val locked = AgentNotices.deviceLocked(context)
        val (entry, told) = synchronized(lock) {
            when (val claim = ledger.claim(id, nonce, locked, active = working && !stopping)) {
                Claim.Locked -> return ApprovalOutcome.Locked
                Claim.Expired -> return ApprovalOutcome.Expired
                is Claim.Granted -> {
                    published()
                    label = if (approvals.isEmpty()) "Agent is working" else "Approval needed"
                    // An agent that has just gone can't be told; the request is over either way.
                    claim.entry to runCatching { writeEvent(ClaudeProtocol.decision(claim.entry.approval, allow)) }.isSuccess
                }
            }
        }
        record(context, entry, if (!told) ApprovalAnswer.Unanswered else if (allow) ApprovalAnswer.Approved else ApprovalAnswer.Denied)
        AgentNotices.withdraw(context, entry)
        notify(context)
        return if (told) ApprovalOutcome.Decided else ApprovalOutcome.Expired
    }
    /** Writes how an approval ended into the thread. */
    private fun record(context: Context, entry: PendingApproval, answer: ApprovalAnswer) = ChatLog.of(context).update(taskTime, save = true) { task ->
        task.copy(approvals = task.approvals.map { if (it.key == entry.nonce) it.copy(answer = answer) else it })
    }
    /**
     * "Send now" (Boss, 2026-10-16: "like the Send Now button in the Claude app", for an agent that is stuck or must be
     * told something at once): the follow-up filed at [time] jumps the queue, the running turn is ended as a stop
     * would end it, and that follow-up starts as soon as it has, resuming the same session, so nothing the agent
     * knew is lost. What else waited still waits. False when it is not this builder's, or no longer waits.
     */
    fun sendNow(context: Context, time: Long): Boolean {
        val unanswered = synchronized(lock) {
            if (!working || stopping || !followUps.first(time)) return false
            sendingNow = true
            stopping = true
            inputWriter?.runCatching { close() }; inputWriter = null
            process?.let { RunEnd.end(it) }; label = "Stopping for your message…"
            ledger.clear().also { published() }
        }
        unanswered.forEach { record(context, it, ApprovalAnswer.Unanswered); AgentNotices.withdraw(context, it) }
        notify(context)
        return true
    }

    /** The session the running agent said it is in, to end what is left of it after a stop. */
    @Volatile private var runSession: String? = null

    /** The running turn is being ended for a follow-up sent now, which starts after it instead of being dropped. */
    @Volatile private var sendingNow = false

    fun stop(context: Context) {
        val (unanswered, unsent) = synchronized(lock) {
            if (!working) return
            stopping = true
            inputWriter?.runCatching { close() }; inputWriter = null
            process?.let { RunEnd.end(it) }; label = "Stopping…"
            ledger.clear().also { published() } to followUps.drop()
        }
        unanswered.forEach { record(context, it, ApprovalAnswer.Unanswered); AgentNotices.withdraw(context, it) }
        // Stop means stop: what waited behind the task is not sent after it.
        dropped(context, unsent, FollowUpQueue.STOPPED)
        notify(context)
    }
    private fun notify(context: Context) {
        AgentTasks.changed(context, this)
        BuilderWatch.watch(context)
    }
}

/**
 * The home page a request was sent from: [page] is its id, [started] when the task began and [ended] when it
 * finished (null while it runs), by the wall clock that file times use.
 */
data class PageAsk(val page: String, val started: Long, val ended: Long? = null) {
    /**
     * Whether a widget file first seen at [now], last written at [written], is this task's doing as far as can
     * be told: written since the task began, and seen while it runs, just after it ended, or shortly after it
     * started if it was over sooner than that. A file from before the task, or one turning up long after, isn't.
     */
    fun covers(written: Long, now: Long): Boolean = written >= started && now >= started && now <= maxOf((ended ?: now) + SEEN_MILLIS, started + SHORTLY_MILLIS)

    companion object {
        /** How long after a task started a new widget still counts as asked for from that page. */
        const val SHORTLY_MILLIS = 120_000L

        /** The launcher sees a file a moment after it is written: this long after the task ended still counts. */
        const val SEEN_MILLIS = 30_000L
    }
}

/** Far above what a scaled-down screenshot weighs; anything bigger isn't one. */
private const val MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024

private val SESSION_ID = Regex("[0-9a-fA-F-]{36}")

/** [resume] is the session id of the conversation to continue; anything else starts a new one. */
/** Where the agent's status is written in the Linux home, for widgets. */
const val AGENT_STATUS_FILE = ".cache/oma/agent.json"

/**
 * The agent's task for a widget: whether it is `working`, how many `approvals` wait, `state` as one word
 * (working, waiting, done, stopped, failed or ready), `doing` as home used to pencil it, the first lines of
 * the last `answer` as plain words (a tile draws no Markdown), `phone` control as on, off or disconnected, and
 * when this was `updated` in Unix seconds. Disconnected between tasks, `state` is failed and `answer` says how to mend it.
 */
fun agentStatus(working: Boolean, label: String, answer: String, approvals: Int, now: Long, phone: PhoneLink = PhoneLink.On): JSONObject {
    // Between tasks, a tile has room to say that phone control needs him: it reads as needing attention, with how to mend it.
    val cut = phone == PhoneLink.Disconnected && !working && approvals == 0
    val state = when {
        approvals > 0 -> "waiting"
        working -> "working"
        cut -> "failed"
        label == "Done" -> "done"
        label == "Stopped" -> "stopped"
        label == "Ready" -> "ready"
        else -> "failed"
    }
    return JSONObject()
        .put("working", working).put("approvals", approvals).put("state", state).put("label", label)
        .put("doing", if (working || approvals > 0) marginNote(label) else "")
        .put("answer", if (cut) PHONE_DISCONNECTED else Markdown.plain(answer.trim().take(4 * AGENT_STATUS_ANSWER)).take(AGENT_STATUS_ANSWER))
        .put("phone", phone.id).put("updated", now)
}

/**
 * The Mac's work as the oma tile, the ring and the lock widget tell it beside the phone's builders (OPEN-BUGS
 * #10: they read the builders only): the thread [waiting] for his OK and how many [approvals] wait, the one
 * [working], and the one with [background] work left running after its answer; each null when there is none.
 * A name stands for the first of several, with how many more.
 */
data class MacGlance(
    val waiting: String? = null,
    val approvals: Int = 0,
    val working: String? = null,
    val background: String? = null,
    /** The thread the [waiting], [working] and [background] lines name first, by id: the chat tab a tap on the tile opens. */
    val waitingThread: String? = null,
    val workingThread: String? = null,
    val backgroundThread: String? = null,
)

/**
 * The Mac's work in [view], with the Mac's requests [pending] his OK. A Mac out of reach says nothing: what was
 * last heard of it may have ended long since, and its requests cannot be answered until it is back.
 */
fun macGlance(view: MacView, pending: List<MacPending>): MacGlance {
    if (!view.reachable) return MacGlance()
    fun named(names: List<String>): String? {
        val distinct = names.distinct()
        return distinct.firstOrNull()?.let { if (distinct.size > 1) "$it +${distinct.size - 1}" else it }
    }
    val waiting = view.threads.filter { it.status == MacThreadStatus.Waiting }
    val working = view.threads.filter { it.status == MacThreadStatus.Working }
    val background = view.threads.filter { macResting(it.status) && it.background.isNotEmpty() }
    return MacGlance(
        waiting = named(pending.map { macName(it.threadName) } + waiting.map { macName(it.name) }),
        approvals = pending.size,
        working = named(working.map { macName(it.name) }),
        background = named(background.map { macName(it.name) }),
        waitingThread = pending.firstOrNull()?.threadId ?: waiting.firstOrNull()?.id,
        workingThread = working.firstOrNull()?.id,
        backgroundThread = background.firstOrNull()?.id,
    )
}

/**
 * The status file with the Mac's work in it. [builders] is [agentStatus]'s, unchanged, and stands whenever a
 * phone builder waits for his OK, and while one works unless a Mac thread waits for his OK, which outranks work
 * as it does on the ring. Otherwise a thread waiting, working or with work running says so by name in `doing`
 * and `mac`, "Working on the Mac · FF4 better menu mod": the home tile is his to read. `approvals` counts the
 * phone's and the Mac's; `mac` is empty while the builders' own say stands.
 */
fun omaStatus(builders: JSONObject, mac: MacGlance, builderTab: String = ""): JSONObject {
    val own = builders.getInt("approvals")
    // `thread` is the chat tab of what the tile shows, so a tap opens it (Boss, 2026-10-20): the builders' tab, or the Mac thread's.
    val status = JSONObject(builders.toString()).put("approvals", own + mac.approvals).put("mac", "").put("thread", builderTab)
    fun told(state: String, line: String, thread: String?) =
        status.put("state", state).put("doing", line).put("mac", line).put("thread", thread?.let(::macThreadKey) ?: "")
    return when {
        own > 0 -> status
        mac.waiting != null -> told("waiting", "Needs your OK · ${mac.waiting}", mac.waitingThread)
        builders.getBoolean("working") -> status
        mac.working != null -> told("working", "Working on the Mac · ${mac.working}", mac.workingThread)
        mac.background != null -> told("working", "Running on the Mac · ${mac.background}", mac.backgroundThread)
        else -> status
    }
}

/** How much of an answer the status file carries: enough for a tile, not the whole thread. */
const val AGENT_STATUS_ANSWER = 400

/**
 * Whether a tool call works the phone: a Bash command that runs `oma-phone` for anything but asking whether phone
 * control is on or how it is used (Boss, 2026-10-17: the ring should stop "as soon as the agent stopped using" the
 * phone, not a while after).
 */
fun phoneToolCall(tool: String, command: String): Boolean =
    tool == "Bash" && Regex("(^|[\\s;&|(])oma-phone\\s+(\\S+)").findAll(command).any { it.groupValues[2] !in setOf("status", "help") }

/**
 * The model a plain errand on the phone runs on (Boss, 2026-10-16: phone control "quite slow"): a turn of the fast model
 * takes a fraction of the builder's default one's, and an errand is many short turns. Work on code stays on the default.
 */
const val ERRAND_MODEL = "sonnet"

fun claudeStreamCommand(resume: String? = null, model: String? = null): String = "claude -p " + (resume?.takeIf(SESSION_ID::matches)?.let { "--resume $it " } ?: "") +
    (model?.let { "--model $it " } ?: "") + "--input-format stream-json --output-format stream-json --verbose --permission-mode auto --permission-prompt-tool stdio --allowedTools ${shellQuote("Bash(oma-phone *)")} ${shellQuote("Bash(soma-artifact *)")} Read"

/** The line a builder's shell says once the steps before Codex are done, when its words can be given. */
const val CODEX_READY = "OMA-READY"

/**
 * A builder's Codex, reading its words from stdin ("-") and saying what it does as JSON events ([CodexProtocol]),
 * going on with the conversation [resume] when there is one. Phone B runs with [fullAccess]: Codex's own sandbox
 * cannot work inside proot.
 */
fun codexBuilderCommand(images: List<String>, model: String?, resume: String?, fullAccess: Boolean): String =
    "codex exec " + (if (resume != null) "resume " else "") + "--json --skip-git-repo-check " +
        (if (fullAccess) "--dangerously-bypass-approvals-and-sandbox " else "") + (model?.let { "--model ${shellQuote(it)} " } ?: "") +
        images.joinToString("") { "--image ${shellQuote(it)} " } + "-- " + (resume?.let { shellQuote(it) + " " } ?: "") + "-"

fun backgroundAgentCommand(agent: String, prompt: String, images: List<String> = emptyList(), model: String? = null, codexFullAccess: Boolean = false): String = when (agent) {
    "claude" -> "claude -p --permission-mode auto --allowedTools ${shellQuote("Bash(oma-phone *)")} Read -- ${shellQuote(prompt)} < /dev/null"
    // Phone B cannot use Codex's bubblewrap sandbox inside proot. Other callers keep their existing policy.
    "codex" -> "codex exec --json --skip-git-repo-check " + (if (codexFullAccess) "--dangerously-bypass-approvals-and-sandbox " else "") + (model?.let { "--model ${shellQuote(it)} " } ?: "") + images.joinToString(" ") { "--image ${shellQuote(it)}" }.let { if (it.isBlank()) "" else "$it " } + "-- ${shellQuote(prompt)} < /dev/null"
    // Grok as Claude Code is run here, in auto mode with phone commands and reading allowed; nobody answers its prompts in
    // -p, so what auto mode won't allow is refused. It reads an image from its path, so the paths go in its words. The
    // prompt is given as --single= so one starting with a dash is still his words.
    "grok" -> "grok --permission-mode auto --allow ${shellQuote("Bash(oma-phone *)")} --allow Read --single=" +
        shellQuote(if (images.isEmpty()) prompt else prompt + "\n\nRead these attached images before answering:\n" + images.joinToString("\n")) + " < /dev/null"
    else -> error("The assistant currently supports Claude Code, Codex and Grok. Choose one in Omalauncher.")
}

/** Stop on the task notification: it stops the builder the notification is about, and A for one posted before there were two. */
class StopAgentTask : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AgentTasks.stop(context, BuilderId.of(intent.getStringExtra(EXTRA_BUILDER)))

    companion object {
        const val EXTRA_BUILDER = "builder"
    }
}
