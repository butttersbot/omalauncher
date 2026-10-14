package dev.omalauncher.assist

import android.content.*
import dev.omalauncher.App
import dev.omalauncher.linux.*
import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
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
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * A single background request stays alive with the existing Linux foreground service.
 * Claude requests continue one conversation, resumed by session id, until [newThread].
 * What he sends while a request runs waits in [followUps] and is sent as the next request when it has finished.
 */
object AgentTasks {
    @Volatile var working = false; private set
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
    /** The Claude session that the next request follows up on. */
    @Volatile var thread: String? = null; private set
    private var inputWriter: BufferedWriter? = null
    private var restored = false
    /** Which task of the Oma thread the running request is. */
    @Volatile private var taskTime = 0L
    /** The home page the latest request was sent from, and how long its task ran: a widget made meanwhile lands there. */
    @Volatile var asked: PageAsk? = null; private set
    /** Far above what a scaled-down screenshot weighs; anything bigger isn't one. */
    private const val MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024
    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        val saved = context.getSharedPreferences("assistant-result", Context.MODE_PRIVATE)
        answer = saved.getString("answer", "") ?: ""
        label = saved.getString("label", "Ready") ?: "Ready"
        thread = saved.getString("thread", null)
        AgentNotices.clearStale(context)
    }
    @Synchronized fun newThread(context: Context) {
        restore(context)
        check(!working) { "An agent is still working. Stop it before starting a new thread." }
        thread = null; answer = ""; label = "Ready"
        context.getSharedPreferences("assistant-result", Context.MODE_PRIVATE).edit()
            .remove("thread").putString("label", label).putString("answer", answer).apply()
        notify(context)
    }
    /**
     * [screen] is the screenshot behind the "this screen" chip, and only that: once this returns the file is
     * gone from where the chip kept it. If it throws, nothing was sent and the file is still the caller's.
     * [page] is the id of the home page he was on when he asked: the one home shows, unless the caller knows better.
     */
    @Synchronized fun send(context: Context, prompt: String, images: List<String> = emptyList(), screen: File? = null, page: String? = HomeDoor.home?.showing) {
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
    @Synchronized fun followUp(context: Context, prompt: String, images: List<String> = emptyList(), page: String? = HomeDoor.home?.showing, earlier: String = "", screen: File? = null) {
        restore(context)
        check(prompt.isNotBlank()) { "Type or speak a request first." }
        if (!working) return start(context, prompt, images, screen, page, queued = null, earlier = earlier)
        check(!stopping) { "The agent is stopping. Send this again in a moment." }
        val agent = (context.applicationContext as App).runtime.agents.default ?: error("Install and sign in to an agent in Omalauncher first.")
        val log = ChatLog.of(context)
        val time = ChatHistory.stamp(log.tasks, System.currentTimeMillis())
        check(followUps.add(FollowUp(time, prompt, images, page, earlier, screen))) { FollowUpQueue.FULL }
        log.begin(ChatTask(time, agent.name, prompt, images, screen = screen != null, status = TaskStatus.Queued))
    }
    /**
     * `[ cancel ]` on a follow-up that still waits: it leaves the queue and the thread says so. One that has
     * begun is no longer in the queue and is not touched, and nothing of the running task changes.
     */
    @Synchronized fun cancelFollowUp(context: Context, time: Long) {
        followUps.cancel(time)?.let { dropped(context, listOf(it), FollowUpQueue.CANCELLED) }
    }
    /** [queued] is the time of the waiting task this request already is in the thread; null for one sent just now. */
    @Synchronized private fun start(context: Context, prompt: String, images: List<String>, screen: File?, page: String?, queued: Long?, earlier: String = "") {
        restore(context)
        check(prompt.isNotBlank()) { "Type or speak a request first." }
        check(!working) { "An agent is already working. Stop it before starting another task." }
        val runtime = (context.applicationContext as App).runtime
        check(runtime.bootstrap.state.value == LinuxState.Installed) { "Open Omalauncher and finish Linux setup first." }
        val agent = runtime.agents.default ?: error("Install and sign in to an agent in Omalauncher first.")
        val seen = if (screen != null) "\n\nThe attached image is the screen I was looking at when I asked." else ""
        val imagePrompt = earlier + (if (agent.bin == "claude" && images.isNotEmpty()) prompt + "\n\nRead these attached images before answering:\n" + images.joinToString("\n") else prompt) + seen
        val fullPrompt = imagePrompt + "\n\n" + PhoneBridge.INSTRUCTIONS + "\nAndroid build tools, when installed, are in /opt/android-sdk. Run oma-build-check to check the toolchain. Use the approval UI for blocked tools; never ask for blanket permission bypass.\n"
        val resumed = thread.takeIf { agent.bin == "claude" }
        // Claude gets the screenshot inside the message, so its file goes now. Codex can only read a file, which lasts until its task ends.
        var shot: String? = null
        var handed: File? = null
        val command = if (agent.bin == "claude") {
            if (screen != null) {
                check(screen.isFile && screen.length() in 1..MAX_SCREENSHOT_BYTES) { "The screenshot is no longer available. Remove it and send again." }
                shot = java.util.Base64.getEncoder().encodeToString(screen.readBytes())
                screen.delete()
            }
            claudeStreamCommand(resumed)
        } else {
            backgroundAgentCommand(agent.bin, fullPrompt, images) // Throws for an agent that can't run here, before the screenshot is touched.
            handed = screen?.let { ScreenShots.handOver(it, runtime.proot.promptAttachments) }
            backgroundAgentCommand(agent.bin, fullPrompt, images + listOfNotNull(handed?.let(runtime.proot::guestPath)))
        }
        val log = ChatLog.of(context)
        val began = System.currentTimeMillis()
        val time = queued ?: ChatHistory.stamp(log.tasks, began)
        taskTime = time
        asked = page?.let { PageAsk(it, began) }
        if (queued == null) log.begin(ChatTask(time, agent.name, prompt, images, screen = screen != null, newThread = resumed == null))
        else log.update(time, save = true) { it.copy(agent = agent.name, newThread = resumed == null, status = TaskStatus.Running) }
        working = true; stopping = false; ledger.clear(); published(); answer = ""; label = "${agent.name} is working"
        notify(context)
        thread(name = "assistant-agent", isDaemon = true) {
            try {
                val child = runtime.proot.process(command).start()
                process = child
                if (stopping) child.destroy()
                if (agent.bin == "claude") {
                    synchronized(this) { inputWriter = child.outputStream.bufferedWriter() }
                    writeEvent(JSONObject().put("type", "control_request").put("request_id", "oma-init")
                        .put("request", JSONObject().put("subtype", "initialize")))
                    var sent = false
                    var session: String? = null
                    var lost = false
                    child.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val event = runCatching { JSONObject(line) }.getOrNull()
                            if (event == null) {
                                if (resumed != null && line.startsWith("No conversation found")) lost = true
                                continue
                            }
                            if (!sent && event.optString("type") == "control_response" && event.optJSONObject("response")?.optString("request_id") == "oma-init") {
                                check(event.getJSONObject("response").optString("subtype") != "error") { "Agent initialization failed" }
                                writeEvent(ClaudeProtocol.user(fullPrompt, shot)); shot = null; sent = true
                            }
                            ClaudeProtocol.approval(event)?.let { admit(context, it) }
                            when (event.optString("type")) {
                                "control_cancel_request" -> {
                                    val gone = synchronized(this) {
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
                                                log.update(time) { ChatHistory.withStep(it, ClaudeProtocol.step(block)) }
                                                if (approvals.isEmpty()) { label = "Using ${block.optString("name").take(60)}"; notify(context) }
                                            }
                                        }
                                    }
                                }
                                "system" -> if (event.optString("subtype") == "init") session = event.optString("session_id").ifBlank { null }
                                "result" -> {
                                    // A failed first request may leave no conversation to return to.
                                    if (!event.optBoolean("is_error") || resumed != null) thread = event.optString("session_id").ifBlank { thread }
                                    val result = event.optString("result")
                                    if (result.isNotBlank()) answer = result.takeLast(40_000)
                                    if (event.optBoolean("is_error")) {
                                        answer = answer.ifBlank { event.optJSONArray("errors")?.join("\n") ?: "The agent needs attention." }
                                    }
                                    synchronized(this) { inputWriter?.close(); inputWriter = null }
                                }
                            }
                        }
                    }
                    if (lost) {
                        thread = null
                        answer = "The earlier thread is no longer available. Send your request again to start a new one."
                    } else if (stopping && session != null) thread = session // A stopped request can still be followed up.
                } else {
                    child.outputStream.close()
                    child.inputStream.bufferedReader().forEachLine { answer = (answer + it + "\n").takeLast(40_000) }
                }
                val code = child.waitFor()
                label = if (stopping) "Stopped" else if (code == 0) "Done" else "Agent needs attention"
                if (answer.isBlank()) answer = if (code == 0) "Task completed." else "The agent exited with status $code. Open its terminal to check sign-in or permissions."
            } catch (e: Exception) { label = "Could not run agent"; answer = e.message ?: "Please open Omalauncher and try again." }
            finally {
                // Whatever was still unanswered goes with the task, notifications included.
                val unanswered = synchronized(this) { inputWriter?.runCatching { close() }; inputWriter = null; ledger.clear().also { published() } }
                unanswered.forEach { AgentNotices.withdraw(context, it) }
                shot = null; handed?.delete()
                val status = when { stopping -> TaskStatus.Stopped; label == "Done" -> TaskStatus.Done; else -> TaskStatus.Failed }
                log.update(time, save = true) { task ->
                    task.copy(result = answer, status = status, approvals = task.approvals.map { if (it.answer == ApprovalAnswer.Waiting) it.copy(answer = ApprovalAnswer.Unanswered) else it })
                }
                // One step under the lock, so a follow-up that waited starts before anything else can find the agent idle.
                synchronized(this) {
                    process = null; working = false
                    asked = asked?.let { if (it.started == began && it.ended == null) it.copy(ended = System.currentTimeMillis()) else it }
                    context.getSharedPreferences("assistant-result", Context.MODE_PRIVATE).edit()
                        .putString("label", label).putString("answer", answer).putString("thread", thread).apply()
                    File(context.filesDir, "assistant-last-result.txt").writeText(answer)
                    notify(context)
                    // A task the owner stopped says nothing more, and one with a follow-up now running has its answer in
                    // the thread, under a task notification that is ongoing again; any other ending brings its answer to him.
                    if (!advance(context, status) && status != TaskStatus.Stopped) AgentNotices.answered(context, label, answer)
                }
            }
        }
    }
    /**
     * Under the lock, as a task ends with [status]: sends the follow-up that waited behind it, or says in the
     * thread why none will be. True when one is now running.
     */
    private fun advance(context: Context, status: TaskStatus): Boolean {
        val handoff = followUps.after(status)
        dropped(context, handoff.dropped, FollowUpQueue.FAILED)
        val next = handoff.next ?: return false
        return try {
            start(context, next.prompt, next.images, next.screen, next.page, queued = next.time, earlier = next.earlier)
            true
        } catch (e: Exception) {
            // What stopped this one from starting would stop the ones behind it too.
            dropped(context, listOf(next) + followUps.drop(), e.message ?: FollowUpQueue.FAILED)
            false
        }
    }
    /** Marks follow-ups that will never be sent, with the [reason] where their answer would have been. A screenshot that waited with one goes. */
    private fun dropped(context: Context, unsent: List<FollowUp>, reason: String) {
        val log = ChatLog.of(context)
        unsent.forEach { followUp ->
            ScreenShots.discard(followUp.screen)
            log.update(followUp.time, save = true) { ChatHistory.dropped(it, reason) }
        }
    }
    @Synchronized private fun writeEvent(event: JSONObject) {
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
        val shown = synchronized(this) {
            if (stopping) return
            when (val admission = ledger.admit(approval)) {
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
        AgentNotices.request(context, shown)
        // Stop may have cleared the ledger between admitting and posting; its withdrawal then ran before there was anything to take down.
        if (pending.none { it.nonce == shown.nonce }) AgentNotices.withdraw(context, shown)
        notify(context)
    }
    /**
     * Answers the one approval with this [id] and [nonce]; the notification buttons, the approval screen and
     * the chat thread all come through here. Nothing is answered from a locked phone, and an approval that was
     * already answered, withdrawn, stopped or lost to a restart is [ApprovalOutcome.Expired] and changes nothing.
     */
    fun answerApproval(context: Context, id: String, nonce: String, allow: Boolean): ApprovalOutcome {
        val locked = AgentNotices.deviceLocked(context)
        val (entry, told) = synchronized(this) {
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
    fun stop(context: Context) {
        val (unanswered, unsent) = synchronized(this) {
            if (!working) return
            stopping = true
            inputWriter?.runCatching { close() }; inputWriter = null
            process?.destroy(); label = "Stopping…"
            ledger.clear().also { published() } to followUps.drop()
        }
        unanswered.forEach { record(context, it, ApprovalAnswer.Unanswered); AgentNotices.withdraw(context, it) }
        // Stop means stop: what waited behind the task is not sent after it.
        dropped(context, unsent, FollowUpQueue.STOPPED)
        notify(context)
    }
    private fun notify(context: Context) {
        // The ring follows the task from here, the one place every change of it passes.
        OmaRing.task(working, pending.size, succeeded = label == "Done")
        OmaTiles.refresh(context)
        OmaLockWidget.refresh(context)
        AgentNotices.task(context, working, label, answer, approvals.size)
        writeStatus(context)
    }

    /**
     * The task as a widget can read it: a `file("~/.cache/oma/agent.json")` source sees every change the
     * moment it is written. Home has no strip or note of its own for this; the widget is the place for it.
     */
    /** Phone control came, went or was found disconnected: the status file says so. Nothing about the task has changed. */
    fun phoneChanged(context: Context) {
        restore(context)
        writeStatus(context)
    }

    private fun writeStatus(context: Context) {
        runCatching {
            val file = File((context.applicationContext as App).runtime.proot.home, AGENT_STATUS_FILE)
            file.parentFile?.mkdirs()
            file.writeText(agentStatus(working, label, answer, approvals.size, System.currentTimeMillis() / 1000, PhoneAccess.link(context)).toString())
        }
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

/** How much of an answer the status file carries: enough for a tile, not the whole thread. */
const val AGENT_STATUS_ANSWER = 400

fun claudeStreamCommand(resume: String? = null): String = "claude -p " + (resume?.takeIf(SESSION_ID::matches)?.let { "--resume $it " } ?: "") + "--input-format stream-json --output-format stream-json --verbose --permission-mode auto --permission-prompt-tool stdio --allowedTools ${shellQuote("Bash(oma-phone *)")} Read"

fun backgroundAgentCommand(agent: String, prompt: String, images: List<String> = emptyList()): String = when (agent) {
    "claude" -> "claude -p --permission-mode auto --allowedTools ${shellQuote("Bash(oma-phone *)")} Read -- ${shellQuote(prompt)} < /dev/null"
    "codex" -> "codex exec --skip-git-repo-check " + images.joinToString(" ") { "--image ${shellQuote(it)}" }.let { if (it.isBlank()) "" else "$it " } + "-- ${shellQuote(prompt)} < /dev/null"
    else -> error("The assistant currently supports Claude Code and Codex. Choose one in Omalauncher.")
}

class StopAgentTask : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AgentTasks.stop(context)
}
