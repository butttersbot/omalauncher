package dev.omalauncher.assist

import org.json.JSONObject

/** An explicit answer, never denial/cancellation: continue the waiting turn using best judgment. */
const val QUESTION_SKIP_ANSWER = "Boss skipped this question: decide yourself with your best judgment and carry on; only ask again if you truly can't continue without him."

fun questionSkipAnswers(input: String): JSONObject {
    val json = JSONObject(input)
    val questions = json.getJSONArray("questions")
    require(questions.length() in 1..20) { "Question form is empty or too large" }
    val codex = json.optString("codex_request") == "questions"
    val keys = (0 until questions.length()).map { questions.getJSONObject(it).getString(if (codex) "id" else "question") }
    require(keys.all { it.isNotBlank() } && keys.distinct().size == keys.size) { "Question identifiers must be unique" }
    return JSONObject().apply { keys.forEach { put(it, QUESTION_SKIP_ANSWER) } }
}

fun questionAnswersSkipped(answers: JSONObject?): Boolean = answers != null && answers.length() > 0 &&
    answers.keys().asSequence().all { answers.opt(it) == QUESTION_SKIP_ANSWER }

/** A question form (Claude's AskUserQuestion, Codex's questions) waits in this approval: only his Send or skip ends it. */
fun questionWaits(tool: String, input: JSONObject): Boolean =
    (tool == "AskUserQuestion" || input.optString("codex_request") == "questions") && (input.optJSONArray("questions")?.length() ?: 0) > 0
