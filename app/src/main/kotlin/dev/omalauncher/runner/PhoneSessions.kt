package dev.omalauncher.runner

import android.content.Context
import dev.omalauncher.App
import dev.omalauncher.assist.*
import dev.omalauncher.oma.*
import org.json.JSONArray
import org.json.JSONObject

/** Only the existing tab operations own work and approval state; HTTP never starts an agent itself. */
class PhoneSessions(private val context: Context) : PhoneRunnerSessions {
    private val log get() = ChatLog.of(context)
    private fun builder(id: String): BuilderId? = when (id) {
        "builder-a" -> BuilderId.A; "builder-b" -> BuilderId.B; "builder-c" -> BuilderId.C; "phone-d" -> BuilderId.D
        else -> null
    }
    private fun tasks(id: String) = log.tasks.filter { phoneTaskSession(it) == id }
    override fun list(): List<JSONObject> {
        AgentTasks.restore(context)
        val book = TabBook.of(context)
        val ids = listOf("builder-a", "builder-b", "builder-c", "phone-d") +
            book.chats.filter { it.mac.isEmpty() && tasks("chat-${it.id}").isNotEmpty() }.map { "chat-${it.id}" }
        return ids.map { id ->
            val all = tasks(id)
            val latest = all.lastOrNull()
            val slot = builder(id)?.let(AgentTasks::of)
            val pending = slot?.pending.orEmpty()
            val working = slot?.working ?: all.any { it.status == TaskStatus.Running }
            val agents = (context.applicationContext as App).runtime.agents
            val choice = slot?.let { builderAgent(it.id, agents.installed, agents.default) }
            val status = when {
                pending.isNotEmpty() -> "waiting"
                working -> "working"
                latest?.status == TaskStatus.Interrupted -> "interrupted"
                else -> "idle"
            }
            JSONObject().put("id", id).put("name", slot?.let { "Phone ${it.id.letter}" } ?: book.names[chatKey(id.removePrefix("chat-"))] ?: "Chat")
                .put("home", "phone").put("kind", choice?.agent?.bin ?: latest?.agent.orEmpty())
                .put("model", choice?.model ?: latest?.answeredBy.orEmpty()).put("summary", latest?.prompt.orEmpty().take(240))
                .put("tail", latest?.result.orEmpty().takeLast(2000)).put("last_worked", (latest?.time ?: 0) / 1000)
                .put("status", status).put("waiting_turns", slot?.waiting ?: all.count { it.status == TaskStatus.Queued })
                .put("last_event", log.runnerLast(id)).put("pending", JSONArray(pending.map(::phoneRunnerApproval)))
        }
    }
    override fun events(id: String, after: Long) = log.runnerEvents(id, after)
    override fun message(id: String, text: String): JSONObject {
        val b = builder(id)
        if (b == BuilderId.D) throw RunnerFault(403, "Phone D takes agent mail only")
        return if (b != null) synchronized(AgentTasks) {
            val slot = AgentTasks.of(b)
            val busy = slot.working
            AgentTasks.direct(context, text, page = null, builder = b)
            JSONObject().put("taken", if (busy) "queued" else "now").apply { if (busy) put("position", slot.waiting) }
        } else {
            val chat = id.removePrefix("chat-")
            if (TabBook.of(context).chat(chat)?.mac != "") throw RunnerFault(404, "no such phone chat")
            synchronized(QuickLane) {
                val busy = QuickLane.working
                QuickLane.ask(context, text, chat = chat)
                JSONObject().put("taken", if (busy) "queued" else "now").apply { if (busy) put("position", tasks(id).count { it.status == TaskStatus.Queued }) }
            }
        }
    }
    override fun answer(id: String, approval: String, nonce: String, decision: String, answers: JSONObject?): JSONObject {
        val slot = builder(id)?.let(AgentTasks::of) ?: throw RunnerFault(404, "no such approval")
        val pending = slot.pending.firstOrNull { it.nonce == approval } ?: throw RunnerFault(404, "approval already answered")
        val reply = phoneRunnerDecision(pending, nonce, decision, answers)
        val outcome = try { slot.decide(context, pending.id, nonce, reply.allow, reply.answers) }
            catch (_: IllegalArgumentException) { throw RunnerFault(400, "invalid question form") }
        when (outcome) {
            ApprovalOutcome.Decided -> return JSONObject()
            ApprovalOutcome.Locked -> throw RunnerFault(403, "phone locked")
            ApprovalOutcome.Expired -> throw RunnerFault(404, "approval already answered")
        }
    }
    override fun stop(id: String) {
        val b = builder(id)
        if (b != null) AgentTasks.stop(context, b)
        else QuickLane.stopChat(context, id.removePrefix("chat-"))
    }
}

/** The wire approval id is the phone's unique ticket nonce, never an agent's reusable tool id. */
fun phoneRunnerApproval(entry: PendingApproval): JSONObject = JSONObject().put("approval", entry.nonce).put("nonce", entry.nonce)
    .put("tool", entry.approval.tool).put("input", entry.approval.input).put("explanation", entry.approval.explanation).put("can_remember", false)
