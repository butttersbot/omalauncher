package dev.omalauncher.assist

import android.content.*
import dev.omalauncher.App
import dev.omalauncher.linux.*
import dev.omalauncher.oma.ApprovalAnswer
import dev.omalauncher.oma.ChatApproval
import dev.omalauncher.oma.ChatHistory
import dev.omalauncher.oma.ChatLog
import dev.omalauncher.oma.ChatTask
import dev.omalauncher.oma.ScreenShots
import dev.omalauncher.oma.TaskStatus
import dev.omalauncher.phone.PhoneBridge
import java.io.File
import java.io.BufferedWriter
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * A single background request stays alive with the existing Linux foreground service.
 * Claude requests continue one conversation, resumed by session id, until [newThread].
 */
object AgentTasks {
    @Volatile var working = false; private set
    @Volatile var answer = ""; private set
    @Volatile var label = "Ready"; private set
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile var approvals: List<AgentApproval> = emptyList(); private set
    /** The same approvals, each with what its notification and buttons are keyed by. */
    @Volatile var pending: List<PendingApproval> = emptyList(); private set
    private val ledger = ApprovalLedger()
    /** The Claude session that the next request follows up on. */
    @Volatile var thread: String? = null; private set
    private var inputWriter: BufferedWriter? = null
    private var restored = false
    /** Which task of the Oma thread the running request is. */
    @Volatile private var taskTime = 0L
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
     */
    @Synchronized fun send(context: Context, prompt: String, images: List<String> = emptyList(), screen: File? = null) {
        restore(context)
        check(prompt.isNotBlank()) { "Type or speak a request first." }
        check(!working) { "An agent is already working. Stop it before starting another task." }
        val runtime = (context.applicationContext as App).runtime
        check(runtime.bootstrap.state.value == LinuxState.Installed) { "Open Omalauncher and finish Linux setup first." }
        val agent = runtime.agents.default ?: error("Install and sign in to an agent in Omalauncher first.")
        val seen = if (screen != null) "\n\nThe attached image is the screen I was looking at when I asked." else ""
        val imagePrompt = (if (agent.bin == "claude" && images.isNotEmpty()) prompt + "\n\nRead these attached images before answering:\n" + images.joinToString("\n") else prompt) + seen
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
        val time = System.currentTimeMillis()
        taskTime = time
        log.begin(ChatTask(time, agent.name, prompt, images, screen = screen != null, newThread = resumed == null))
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
                process = null; working = false
                context.getSharedPreferences("assistant-result", Context.MODE_PRIVATE).edit()
                    .putString("label", label).putString("answer", answer).putString("thread", thread).apply()
                File(context.filesDir, "assistant-last-result.txt").writeText(answer)
                notify(context)
            }
        }
    }
    @Synchronized private fun writeEvent(event: JSONObject) {
        val writer = inputWriter ?: error("The agent session has ended.")
        writer.write(event.toString()); writer.newLine(); writer.flush()
    }
    /** Under the lock: republishes the ledger for readers on other threads. */
    private fun published() { pending = ledger.live; approvals = pending.map { it.approval } }
    /** A request from the agent's stream: shown to the owner, or denied unseen if it can't be shown safely. */
    private fun admit(context: Context, approval: AgentApproval) {
        fun logged(answer: ApprovalAnswer, key: String) = ChatLog.of(context).update(taskTime, save = true) {
            it.copy(approvals = it.approvals + ChatApproval(approval.tool, approval.detail, answer, key))
        }
        val shown = synchronized(this) {
            if (stopping) return
            when (val admission = ledger.admit(approval)) {
                is Admission.Shown -> { published(); label = "Approval needed"; admission.entry }
                Admission.Duplicate -> return
                Admission.Refused -> {
                    runCatching { writeEvent(ClaudeProtocol.decision(approval, false)) }
                    logged(ApprovalAnswer.Refused, "")
                    return
                }
            }
        }
        logged(ApprovalAnswer.Waiting, shown.nonce)
        AgentNotices.request(context, shown)
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
        val unanswered = synchronized(this) {
            if (!working) return
            stopping = true
            inputWriter?.runCatching { close() }; inputWriter = null
            process?.destroy(); label = "Stopping…"
            ledger.clear().also { published() }
        }
        unanswered.forEach { record(context, it, ApprovalAnswer.Unanswered); AgentNotices.withdraw(context, it) }
        notify(context)
    }
    private fun notify(context: Context) {
        OmaTiles.refresh(context)
        AgentNotices.task(context, working, label, answer, approvals.size)
    }
}

private val SESSION_ID = Regex("[0-9a-fA-F-]{36}")

/** [resume] is the session id of the conversation to continue; anything else starts a new one. */
fun claudeStreamCommand(resume: String? = null): String = "claude -p " + (resume?.takeIf(SESSION_ID::matches)?.let { "--resume $it " } ?: "") + "--input-format stream-json --output-format stream-json --verbose --permission-mode auto --permission-prompt-tool stdio --allowedTools ${shellQuote("Bash(oma-phone *)")} Read"

fun backgroundAgentCommand(agent: String, prompt: String, images: List<String> = emptyList()): String = when (agent) {
    "claude" -> "claude -p --permission-mode auto --allowedTools ${shellQuote("Bash(oma-phone *)")} Read -- ${shellQuote(prompt)} < /dev/null"
    "codex" -> "codex exec --skip-git-repo-check " + images.joinToString(" ") { "--image ${shellQuote(it)}" }.let { if (it.isBlank()) "" else "$it " } + "-- ${shellQuote(prompt)} < /dev/null"
    else -> error("The assistant currently supports Claude Code and Codex. Choose one in Omalauncher.")
}

class StopAgentTask : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AgentTasks.stop(context)
}
