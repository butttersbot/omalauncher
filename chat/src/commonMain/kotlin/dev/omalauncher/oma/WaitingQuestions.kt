package dev.omalauncher.oma

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Entirely native: the visible answers and the Send action share the same state. */
@Composable
internal fun WaitingQuestionPanel(request: WaitingQuestion, close: () -> Unit) {
    val questions = remember(request.key) { JSONObject(request.input).getJSONArray("questions") }
    val codex = remember(request.key) { JSONObject(request.input).optString("codex_request") == "questions" }
    var answers by rememberSaveable(request.key) { mutableStateOf(mapOf<String, List<String>>()) }
    var others by rememberSaveable(request.key) { mutableStateOf(mapOf<String, String>()) }
    var sending by remember(request.key) { mutableStateOf(false) }
    var sent by rememberSaveable(request.key) { mutableStateOf(false) }
    var error by remember(request.key) { mutableStateOf<String?>(null) }
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
            BasicText(if (sent) "Answers sent" else "Before I continue", Modifier.padding(vertical = 12.dp), style = Pack.body())
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
                        BasicText("${if (selected) "●" else "○"} $label", style = Pack.body())
                        option.optString("description").takeIf { it.isNotBlank() }?.let {
                            BasicText(it, Modifier.padding(top = 4.dp), style = Pack.body(Pack.size.small, Pack.muted))
                        }
                    }
                }
                if (q.opt("isOtherAllowed") != false) {
                    BasicText("Other", style = Pack.mono(Pack.size.small, Pack.muted))
                    BasicTextField(others[key] ?: "", { value ->
                        others = others + (key to value); answers = answers - key
                    }, Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Pack.surface).padding(12.dp),
                        enabled = !sending, textStyle = Pack.body())
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Column(Modifier.fillMaxWidth().background(Pack.surface).padding(12.dp)) {
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
