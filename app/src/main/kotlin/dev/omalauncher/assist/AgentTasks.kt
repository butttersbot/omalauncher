package dev.omalauncher.assist

import android.app.*
import android.content.*
import dev.omalauncher.App
import dev.omalauncher.R
import dev.omalauncher.linux.*
import dev.omalauncher.phone.PhoneBridge
import java.io.File
import java.io.BufferedWriter
import org.json.JSONObject
import kotlin.concurrent.thread

/** A single background request stays alive with the existing Linux foreground service. */
object AgentTasks {
    @Volatile var working = false; private set
    @Volatile var answer = ""; private set
    @Volatile var label = "Ready"; private set
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile var approvals: List<AgentApproval> = emptyList(); private set
    private var inputWriter: BufferedWriter? = null
    private var restored = false
    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        val saved = context.getSharedPreferences("assistant-result", Context.MODE_PRIVATE)
        answer = saved.getString("answer", "") ?: ""
        label = saved.getString("label", "Ready") ?: "Ready"
    }
    @Synchronized fun send(context: Context, prompt: String, images: List<String> = emptyList()) {
        restore(context)
        check(prompt.isNotBlank()) { "Type or speak a request first." }
        check(!working) { "An agent is already working. Stop it before starting another task." }
        val runtime = (context.applicationContext as App).runtime
        check(runtime.bootstrap.state.value == LinuxState.Installed) { "Open Omalauncher and finish Linux setup first." }
        val agent = runtime.agents.default ?: error("Install and sign in to an agent in Omalauncher first.")
        val imagePrompt = if (agent.bin == "claude" && images.isNotEmpty()) prompt + "\n\nRead these attached images before answering:\n" + images.joinToString("\n") else prompt
        val fullPrompt = imagePrompt + "\n\n" + PhoneBridge.INSTRUCTIONS + "\nAndroid build tools, when installed, are in /opt/android-sdk. Run oma-build-check to check the toolchain. Use the approval UI for blocked tools; never ask for blanket permission bypass.\n"
        val command = if (agent.bin == "claude") claudeStreamCommand() else backgroundAgentCommand(agent.bin, fullPrompt, images)
        working = true; stopping = false; approvals = emptyList(); answer = ""; label = "${agent.name} is working"
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
                    child.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val event = runCatching { JSONObject(line) }.getOrNull()
                            if (event == null) continue
                            if (!sent && event.optString("type") == "control_response" && event.optJSONObject("response")?.optString("request_id") == "oma-init") {
                                check(event.getJSONObject("response").optString("subtype") != "error") { "Agent initialization failed" }
                                writeEvent(ClaudeProtocol.user(fullPrompt)); sent = true
                            }
                            val approval = ClaudeProtocol.approval(event)
                            if (approval != null) {
                                synchronized(this) { approvals = approvals + approval; label = "Approval needed" }
                                notify(context)
                            }
                            when (event.optString("type")) {
                                "control_cancel_request" -> synchronized(this) {
                                    approvals = approvals.filterNot { it.id == event.optString("request_id") }
                                    if (approvals.isEmpty() && !stopping) label = "${agent.name} is working"
                                }
                                "assistant" -> {
                                    val content = event.optJSONObject("message")?.optJSONArray("content")
                                    if (content != null) for (i in 0 until content.length()) {
                                        val block = content.optJSONObject(i) ?: continue
                                        when (block.optString("type")) {
                                            "text" -> answer = (answer + block.optString("text") + "\n").takeLast(40_000)
                                            "tool_use" -> if (approvals.isEmpty()) label = "Using ${block.optString("name")}"
                                        }
                                    }
                                }
                                "result" -> {
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
                } else {
                    child.outputStream.close()
                    child.inputStream.bufferedReader().forEachLine { answer = (answer + it + "\n").takeLast(40_000) }
                }
                val code = child.waitFor()
                label = if (stopping) "Stopped" else if (code == 0) "Done" else "Agent needs attention"
                if (answer.isBlank()) answer = if (code == 0) "Task completed." else "The agent exited with status $code. Open its terminal to check sign-in or permissions."
            } catch (e: Exception) { label = "Could not run agent"; answer = e.message ?: "Please open Omalauncher and try again." }
            finally {
                synchronized(this) { inputWriter?.runCatching { close() }; inputWriter = null; approvals = emptyList() }
                process = null; working = false
                context.getSharedPreferences("assistant-result", Context.MODE_PRIVATE).edit()
                    .putString("label", label).putString("answer", answer).apply()
                File(context.filesDir, "assistant-last-result.txt").writeText(answer)
                notify(context)
            }
        }
    }
    @Synchronized private fun writeEvent(event: JSONObject) {
        val writer = inputWriter ?: error("The agent session has ended.")
        writer.write(event.toString()); writer.newLine(); writer.flush()
    }
    @Synchronized fun decide(context: Context, id: String, allow: Boolean) {
        val approval = approvals.firstOrNull { it.id == id } ?: return
        check(working && !stopping) { "This request is no longer active." }
        writeEvent(ClaudeProtocol.decision(approval, allow))
        approvals = approvals.filterNot { it.id == id }
        label = if (approvals.isEmpty()) "Agent is working" else "Approval needed"
        notify(context)
    }
    @Synchronized fun stop(context: Context) {
        if (!working) return
        stopping = true; approvals = emptyList()
        inputWriter?.runCatching { close() }; inputWriter = null
        process?.destroy(); label = "Stopping…"; notify(context)
    }
    private fun notify(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent-task-updates", "Agent progress and results", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, AssistantPromptActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, "agent-task-updates").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label).setContentText(if (approvals.isNotEmpty()) "${approvals.first().tool} · tap to review and approve once" else if (working) "Request received · tap to check progress" else answer.take(180).ifBlank { "Tap to read the result" })
            .setContentIntent(open).setOnlyAlertOnce(working && approvals.isEmpty()).setOngoing(working)
        if (!working) builder.setStyle(Notification.BigTextStyle().bigText(answer.take(1500)))
        if (working) {
            val stop = PendingIntent.getBroadcast(context, 0, Intent(context, StopAgentTask::class.java), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        nm.notify(41, builder.build())
    }
}

fun claudeStreamCommand(): String = "claude -p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio --allowedTools ${shellQuote("Bash(oma-phone *)")} Read"

fun backgroundAgentCommand(agent: String, prompt: String, images: List<String> = emptyList()): String = when (agent) {
    "claude" -> "claude -p --allowedTools ${shellQuote("Bash(oma-phone *)")} Read -- ${shellQuote(prompt)} < /dev/null"
    "codex" -> "codex exec --skip-git-repo-check " + images.joinToString(" ") { "--image ${shellQuote(it)}" }.let { if (it.isBlank()) "" else "$it " } + "-- ${shellQuote(prompt)} < /dev/null"
    else -> error("The assistant currently supports Claude Code and Codex. Choose one in Omalauncher.")
}

class StopAgentTask : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AgentTasks.stop(context)
}
