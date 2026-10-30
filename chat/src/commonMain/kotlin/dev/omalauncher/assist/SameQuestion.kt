package dev.omalauncher.assist

import dev.omalauncher.linux.shellQuote
import org.json.JSONArray
import org.json.JSONObject

/*
 * Same question by meaning (Boss, 2026-10-29 22:2x: tonight's two cards were the same question, worded differently, and
 * should have answered each other). Once he has answered a question, the fast model is asked, once, whether each other
 * waiting question elsewhere is the same decision, and which of that card's own options his answer is. It never writes
 * an answer: it only picks the target's labels, or says to use his own words (where the card takes text). Soma checks
 * every pick against the card; a clear match with a clear pick is delivered as his, anything less is offered to him.
 * One decision path (agreed with the runner side): this check is for the phone builders' questions only; a Mac thread's
 * question is matched by its own runner (POST /questions/match), and Soma alone delivers either, each target once.
 */

/** What the model said of one waiting question. [picks] is null unless the mapping is clear and valid for that card. */
data class SameVerdict(val key: String, val same: Boolean, val sure: Boolean, val picks: JSONObject?)

private fun card(input: JSONObject): JSONArray = JSONArray().apply {
    val qs = input.optJSONArray("questions") ?: JSONArray()
    for (i in 0 until qs.length()) {
        val q = qs.getJSONObject(i)
        val labels = q.optJSONArray("options")?.let { o -> JSONArray((0 until o.length()).map { o.getJSONObject(it).optString("label") }) } ?: JSONArray()
        put(JSONObject().put("question", q.optString("question").take(1200)).put("options", labels)
            .put("multiSelect", q.optBoolean("multiSelect")).put("textAllowed", q.opt("isOtherAllowed") != false))
    }
}

/** The whole of what the model is sent: his question and answer, and the questions waiting elsewhere, by key. */
fun sameQuestionPrompt(answered: QuestionMember, answers: JSONObject, waiting: List<QuestionMember>): String {
    val asked = JSONObject().put("thread", answered.where).put("card", card(answered.input)).put("hisAnswer", answers)
    val others = JSONArray(waiting.map { JSONObject().put("key", it.key).put("thread", it.where).put("card", card(it.input)) })
    return """
        Boss answered a question in one thread. Other threads may be waiting on the SAME decision, asked in other words.
        For each waiting question decide: is it the same decision he just made (same thing being decided, so his answer
        settles it)? If so, which of ITS OWN options is his answer? Never invent an option or words. If his answer is
        not one of its options and textAllowed is true, use "USE_HIS_WORDS". If unsure about either, say so.
        Reply with JSON only: {"verdicts":[{"key":"…","same":"yes|no|unsure","mapping":"clear|unsure","picks":[one entry per
        question of that card, in order: an option label string, an array of labels for multiSelect, or "USE_HIS_WORDS"]}]}

        ANSWERED: $asked

        WAITING: $others
    """.trimIndent()
}

/** The guest command: the fast model, no tools, no session kept, from an internal empty folder (never in his tabs). */
fun sameQuestionCommand(prompt: String, desktop: Boolean = false): String =
    "mkdir -p /tmp/soma-same && touch /tmp/soma-same/.soma-internal && cd /tmp/soma-same && claude -p --no-session-persistence " +
        "--model $QUICK_MODEL --output-format json --tools '' " +
        (if (desktop) "--strict-mcp-config --disable-slash-commands --setting-sources project " else "") +
        "-- ${shellQuote(prompt)} < /dev/null"

private fun hisWords(answers: JSONObject): String = answers.keys().asSequence().joinToString(" · ") { k ->
    answers.opt(k).let { v -> if (v is JSONArray) (0 until v.length()).joinToString(", ") { v.optString(it) } else v.toString() }
}

/**
 * The model's [output] (Claude Code's JSON line, its result holding the verdicts) checked against each waiting card:
 * a pick must be one of that card's own labels (as many as its multiSelect allows), or his own words where it takes text.
 */
fun sameQuestionVerdicts(output: String, waiting: List<QuestionMember>, answers: JSONObject, where: String): List<SameVerdict> {
    val result = output.lineSequence().mapNotNull { runCatching { JSONObject(it.trim()) }.getOrNull() }
        .lastOrNull { it.has("result") && !it.optBoolean("is_error") }?.optString("result") ?: return emptyList()
    // The JSON in what it said, fenced or not: from its first brace to its last.
    val open = result.indexOf('{'); val close = result.lastIndexOf('}')
    if (open < 0 || close <= open) return emptyList()
    val body = result.substring(open, close + 1)
    val list = runCatching { JSONObject(body).getJSONArray("verdicts") }.getOrNull() ?: return emptyList()
    val words = hisWords(answers)
    return (0 until list.length()).mapNotNull { i ->
        val v = list.optJSONObject(i) ?: return@mapNotNull null
        val member = waiting.firstOrNull { it.key == v.optString("key") } ?: return@mapNotNull null
        val same = v.optString("same") == "yes"
        val unsure = v.optString("same") == "unsure"
        if (!same && !unsure) return@mapNotNull SameVerdict(member.key, false, true, null)
        // A clear, valid pick is kept even when the match is unsure: then it is only offered, as his one tap.
        val picks = if (v.optString("mapping") == "clear") checkedPicks(member.input, v.optJSONArray("picks"), words, where) else null
        SameVerdict(member.key, same || unsure, same && picks != null, picks)
    }
}

/** [picks] as answers to [input], or null when any pick isn't that card's own option (or text where text is allowed). */
fun checkedPicks(input: JSONObject, picks: JSONArray?, words: String, where: String): JSONObject? {
    val qs = input.optJSONArray("questions") ?: return null
    if (picks == null || picks.length() != qs.length()) return null
    val codex = input.optString("codex_request") == "questions"
    val out = JSONObject()
    for (i in 0 until qs.length()) {
        val q = qs.getJSONObject(i)
        val id = if (codex) q.optString("id") else q.optString("question")
        val labels = q.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.getJSONObject(it).optString("label") } }.orEmpty()
        val pick = picks.opt(i)
        val value: Any = when {
            pick == "USE_HIS_WORDS" && q.opt("isOtherAllowed") != false && words.isNotBlank() -> "$words (Boss answered this in $where)"
            pick is String && !q.optBoolean("multiSelect") && pick in labels -> pick
            pick is JSONArray && q.optBoolean("multiSelect") && pick.length() > 0 &&
                (0 until pick.length()).all { pick.optString(it) in labels } -> JSONArray((0 until pick.length()).map { pick.optString(it) }.distinct())
            else -> return null
        }
        out.put(id, value)
    }
    return out
}
