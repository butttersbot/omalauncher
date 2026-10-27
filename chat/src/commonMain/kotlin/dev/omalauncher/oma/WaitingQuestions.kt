package dev.omalauncher.oma

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.framed
import org.json.JSONArray
import org.json.JSONObject

internal data class WaitingQuestion(val key: String, val title: String, val input: String,
    val send: (JSONObject, (String?) -> Unit) -> Unit)
internal val LocalQuestionOpen = staticCompositionLocalOf<((WaitingQuestion) -> Unit)?> { null }

internal fun questionInput(tool: String, input: String): Boolean = runCatching {
    val json = JSONObject(input)
    (tool == "AskUserQuestion" || json.optString("codex_request") == "questions") &&
        (json.optJSONArray("questions")?.length() ?: 0) > 0
}.getOrDefault(false)

/** Owned above both panel branches: moving a form never replaces its draft or in-flight gate. */
internal class WaitingQuestionDraft {
    var answers by mutableStateOf(mapOf<String, List<String>>())
    var others by mutableStateOf(mapOf<String, String>())
    var sending by mutableStateOf(false)
    var sent by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun json(): JSONObject = JSONObject().put("answers", JSONObject().apply {
        answers.forEach { (key, values) -> put(key, JSONArray(values)) }
    }).put("others", JSONObject(others)).put("sending", sending).put("sent", sent)

    companion object {
        fun restore(json: JSONObject) = WaitingQuestionDraft().apply {
            val picks = json.optJSONObject("answers") ?: JSONObject()
            answers = picks.keys().asSequence().associateWith { key ->
                val values = picks.getJSONArray(key)
                (0 until values.length()).map { values.getString(it) }
            }
            val text = json.optJSONObject("others") ?: JSONObject()
            others = text.keys().asSequence().associateWith { text.getString(it) }
            sending = json.optBoolean("sending")
            sent = json.optBoolean("sent")
        }
    }
}

internal class WaitingQuestionDrafts {
    private val entries = mutableMapOf<String, WaitingQuestionDraft>()
    fun get(key: String): WaitingQuestionDraft = entries.getOrPut(key) { WaitingQuestionDraft() }
    fun json(): String = JSONObject().apply { entries.forEach { (key, draft) -> put(key, draft.json()) } }.toString()

    companion object {
        fun restore(text: String) = WaitingQuestionDrafts().apply {
            val json = JSONObject(text)
            json.keys().forEach { key -> entries[key] = WaitingQuestionDraft.restore(json.getJSONObject(key)) }
        }
        val saver = Saver<WaitingQuestionDrafts, String>(save = { it.json() }, restore = { restore(it) })
    }
}

/** Re-resolve a saved form's nonce rather than retaining an Activity or an old host callback. */
internal fun ChatHost.sendWaitingAnswers(key: String, answers: JSONObject, done: (String?) -> Unit) {
    macPending().firstOrNull { it.ticket == key }?.let { entry ->
        answerMacQuestions(entry.ticket, answers) { done(dev.omalauncher.assist.macApprovalNotice(it)) }
        return
    }
    builders().values.flatMap { it.pending }.firstOrNull { it.nonce == key }?.let { entry ->
        val outcome = runCatching { answerBuilderQuestions(entry, answers) }.getOrElse {
            done("These answers could not be sent. Review every question and try again."); return
        }
        done(when (outcome) {
            dev.omalauncher.assist.ApprovalOutcome.Decided -> null
            dev.omalauncher.assist.ApprovalOutcome.Locked -> "Unlock your phone to answer"
            dev.omalauncher.assist.ApprovalOutcome.Expired -> "That request has expired"
        })
        return
    }
    done("That request has expired")
}

/** Entirely native: the visible answers and the Send action share the same state. */
@Composable
internal fun WaitingQuestionPanel(request: WaitingQuestion, draft: WaitingQuestionDraft, close: () -> Unit) {
    val questions = remember(request.key) { runCatching {
        val json = JSONObject(request.input)
        val array = json.getJSONArray("questions")
        require(array.length() in 1..20)
        val identifiers = (0 until array.length()).map { i ->
            val q = array.getJSONObject(i)
            require(q.getString("question").isNotBlank())
            val options = q.optJSONArray("options") ?: JSONArray()
            require(options.length() <= 30)
            val labels = (0 until options.length()).map { options.getJSONObject(it).getString("label") }
            require(labels.all { it.isNotBlank() } && labels.distinct().size == labels.size)
            if (json.optString("codex_request") == "questions") q.getString("id") else q.getString("question")
        }
        require(identifiers.all { it.isNotBlank() } && identifiers.distinct().size == identifiers.size)
        array
    }.getOrNull() }
    if (questions == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            BasicText("This question form could not be read. Ask the agent to send it again.", style = Pack.body())
            BracketAction("back to chat", Pack.accent, onClick = close)
        }
        return
    }
    val codex = remember(request.key) { JSONObject(request.input).optString("codex_request") == "questions" }
    var answers by draft::answers
    var others by draft::others
    var sending by draft::sending
    var sent by draft::sent
    var error by draft::error
    val keys = (0 until questions.length()).map { i -> questions.getJSONObject(i).let { if (codex) it.getString("id") else it.getString("question") } }
    val ready = keys.all { others[it]?.isNotBlank() == true || !answers[it].isNullOrEmpty() }
    val summary = keys.mapIndexedNotNull { i, key ->
        val value = others[key]?.takeIf { it.isNotBlank() } ?: answers[key]?.joinToString(", ")
        value?.let { "${questions.getJSONObject(i).optString("header").ifBlank { "${i + 1}" }}: $it" }
    }.joinToString(" · ")
    Column(Modifier.fillMaxSize().background(Pack.background)) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            BasicText("Questions · ${questions.length()}", Modifier.weight(1f), style = Pack.mono(Pack.size.small, Pack.muted))
            BracketAction("back to chat", Pack.accent, onClick = close)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            BasicText(request.title, style = Pack.body(color = Pack.accent))
            BasicText(if (sent) "Answers sent" else "Before I continue", Modifier.padding(vertical = 12.dp), style = Pack.display(Pack.size.title, Pack.accent))
            if (sent) BasicText(summary, style = Pack.body()) else for (i in keys.indices) {
                val q = questions.getJSONObject(i); val key = keys[i]
                BasicText("${i + 1} · ${q.optString("header")}", style = Pack.mono(Pack.size.small, Pack.muted))
                BasicText(q.getString("question"), Modifier.padding(vertical = 10.dp), style = Pack.body())
                val options = q.optJSONArray("options") ?: JSONArray()
                for (j in 0 until options.length()) {
                    val option = options.getJSONObject(j); val label = option.getString("label")
                    val selected = label in (answers[key] ?: emptyList()) && others[key].isNullOrBlank()
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .framed(Pack.mark, if (selected) Pack.accent else Pack.muted, Pack.surface, radius = 8.dp)
                        .clickable(enabled = !sending) {
                            others = others - key
                            val previous = answers[key] ?: emptyList()
                            answers = answers + (key to if (q.optBoolean("multiSelect")) {
                                if (label in previous) previous - label else previous + label
                            } else listOf(label))
                        }.padding(12.dp)) {
                        BasicText("${if (q.optBoolean("multiSelect")) { if (selected) "☑" else "☐" } else { if (selected) "●" else "○" }} $label", style = Pack.body())
                        option.optString("description").takeIf { it.isNotBlank() }?.let {
                            BasicText(it, Modifier.padding(top = 4.dp), style = Pack.body(Pack.size.small, Pack.muted))
                        }
                    }
                }
                if (q.opt("isOtherAllowed") != false) {
                    BasicText("Other", style = Pack.mono(Pack.size.small, Pack.muted))
                    val bringIntoView = remember(request.key, key) { BringIntoViewRequester() }
                    val scope = rememberCoroutineScope()
                    BasicTextField(others[key] ?: "", { value ->
                        others = others + (key to value.take(4096)); answers = answers - key
                    }, Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .bringIntoViewRequester(bringIntoView).onFocusChanged { focus ->
                            if (focus.isFocused) scope.launch { delay(300); bringIntoView.bringIntoView() }
                        }.framed(Pack.mark, Pack.muted, Pack.surface, radius = 8.dp).padding(12.dp),
                        enabled = !sending, textStyle = Pack.body(), decorationBox = { field ->
                            Box { if (others[key].isNullOrEmpty()) BasicText("Write your answer", style = Pack.body(color = Pack.muted)); field() }
                        })
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).background(Pack.surface).verticalScroll(rememberScrollState()).padding(12.dp)) {
            BasicText("To: ${request.title}", style = Pack.mono(Pack.size.small, Pack.muted))
            if (!ready && !sent) BasicText("Answer every question to send.", style = Pack.mono(Pack.size.small, Pack.muted))
            error?.let { BasicText(it, style = Pack.body(Pack.size.small, Pack.red)) }
            if (!sent) BracketAction(if (sending) "Sending…" else if (ready) "Send: $summary" else "Send answers", if (ready) Pack.accent else Pack.muted) {
                if (ready && !sending) {
                    val payload = JSONObject()
                    keys.forEachIndexed { i, key ->
                        val other = others[key]?.takeIf { it.isNotBlank() }
                        payload.put(key, other ?: if (questions.getJSONObject(i).optBoolean("multiSelect")) JSONArray(answers[key]) else answers.getValue(key).single())
                    }
                    sending = true; error = null
                    request.send(payload) { failure -> sending = false; error = failure; sent = failure == null }
                }
            }
        }
    }
}
