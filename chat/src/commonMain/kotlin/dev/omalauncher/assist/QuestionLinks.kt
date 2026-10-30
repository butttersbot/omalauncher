package dev.omalauncher.assist

import org.json.JSONArray
import org.json.JSONObject

/*
 * Linked questions (Boss, 2026-10-29): the same decision reached him as two question cards, from Phone A and from a
 * MacBook thread; he answered one and the other went on waiting, blocking its thread. Answered once, it is answered
 * everywhere it was linked:
 *  - an agent that asks on behalf of, or about, a peer's question links it with AskUserQuestion's metadata,
 *    {"source": "link:KEY"}, KEY being that peer's thread (a Mac thread id such as t1703ae4f, or phone:A..D) or any
 *    id both asks share (the mail id the peer wrote with). Questions with the same KEY, or whose thread KEY names,
 *    are one group, and his answer to one goes to the others, as his, "answered in <where>";
 *  - with no link, a question much like one he answered elsewhere only says so, with a one-tap [ answer there too ].
 */

/** One question form waiting for him: [key] is what answers it (a Mac ticket or a builder's nonce). */
data class QuestionMember(val key: String, val where: String, val thread: String, val input: JSONObject)

/** The link an agent put on its question form, or null. */
fun questionLink(input: JSONObject): String? =
    (input.optJSONObject("metadata")?.optString("source")?.takeIf { it.startsWith("link:") }?.removePrefix("link:")
        ?: input.optString("link").takeIf { it.isNotBlank() })
        ?.trim()?.take(200)?.takeIf { it.isNotBlank() }

private fun names(member: QuestionMember, link: String): Boolean {
    val l = link.removePrefix("mac:").removePrefix("thread:")
    return l == member.key || l.equals(member.thread, ignoreCase = true) || l == member.thread.substringAfterLast('/')
}

/** The other waiting forms [answered] is linked to: same link, or a link naming the other's thread or ticket. */
fun linkedQuestions(answered: QuestionMember, waiting: List<QuestionMember>): List<QuestionMember> {
    val others = waiting.filter { it.key != answered.key }
    val mine = questionLink(answered.input)
    val sameLink = others.filter { o -> val theirs = questionLink(o.input); mine != null && theirs == mine }
    // A link naming a thread: that thread's waiting form, or among several there the ones asking the same thing.
    fun inThreadNamed(link: String, from: List<QuestionMember>) = from.filter { names(it, link) }.let { named ->
        if (named.size <= 1) named else named.filter { similarQuestions(it.input, answered.input) }
    }
    val named = mine?.let { inThreadNamed(it, others) }.orEmpty()
    // And the other way round: theirs names the thread (or ticket) of the one he answered.
    val namingMe = others.filter { o -> questionLink(o.input)?.let { names(answered, it) } == true }
    return (sameLink + named + namingMe).distinctBy { it.key }
}

private val STOP = setOf("the", "and", "for", "you", "this", "that", "with", "what", "which", "should", "would", "want",
    "how", "are", "your", "from", "now", "use", "into", "its", "our", "then", "than", "them", "they", "have", "will")
private fun words(input: JSONObject): Set<String> {
    val qs = input.optJSONArray("questions") ?: return emptySet()
    return (0 until qs.length()).flatMap { i ->
        val q = qs.optJSONObject(i) ?: return@flatMap emptyList()
        Regex("[\\p{L}\\p{N}]+").findAll(q.optString("question").lowercase()).map { it.value }.filter { it.length > 2 && it !in STOP }.toList()
    }.toSet()
}

/** Asked much the same: as many questions, and most of their words shared. */
fun similarQuestions(a: JSONObject, b: JSONObject): Boolean {
    if ((a.optJSONArray("questions")?.length() ?: 0) != (b.optJSONArray("questions")?.length() ?: -1)) return false
    val wa = words(a); val wb = words(b)
    if (wa.isEmpty() || wb.isEmpty()) return false
    return wa.intersect(wb).size.toDouble() / wa.union(wb).size >= 0.5
}

/** The waiting forms elsewhere that ask much what [member] asks, with no link between them. */
fun similarElsewhere(member: QuestionMember, waiting: List<QuestionMember>): List<QuestionMember> =
    waiting.filter { it.key != member.key && it.thread != member.thread && similarQuestions(it.input, member.input) }

private fun ids(input: JSONObject): List<Pair<String, JSONObject>> {
    val qs = input.optJSONArray("questions") ?: return emptyList()
    val codex = input.optString("codex_request") == "questions"
    return (0 until qs.length()).map { qs.getJSONObject(it).let { q -> (if (codex) q.optString("id") else q.optString("question")) to q } }
}
private fun norm(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/**
 * His [answers] to [from] as answers to [to], which [where] names as where he gave them: a choice he picked is the same
 * choice there when it is offered; otherwise his words go in as text, saying where they were answered. Null when one of
 * [to]'s questions can't be answered so (no matching question, or text not allowed there): that form keeps waiting.
 */
fun mapQuestionAnswers(from: JSONObject, answers: JSONObject, to: JSONObject, where: String): JSONObject? {
    val source = ids(from); val target = ids(to)
    if (source.isEmpty() || target.isEmpty()) return null
    val out = JSONObject()
    target.forEachIndexed { i, (id, q) ->
        val match = source.firstOrNull { norm(it.second.optString("question")) == norm(q.optString("question")) }
            ?: source.getOrNull(i)?.takeIf { source.size == target.size } ?: return null
        val raw = answers.opt(match.first) ?: return null
        val labels = q.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.getJSONObject(it).optString("label") } }.orEmpty()
        val other = q.opt("isOtherAllowed") != false
        val picked = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            else -> listOf(raw.toString())
        }
        val value: Any = when {
            raw is String && raw == QUESTION_SKIP_ANSWER -> raw
            q.optBoolean("multiSelect") && picked.isNotEmpty() && picked.all { it in labels } -> JSONArray(picked)
            !q.optBoolean("multiSelect") && picked.size == 1 && picked[0] in labels -> picked[0]
            other -> "${picked.joinToString(", ")} (Boss answered this in $where)"
            else -> return null
        }
        out.put(id, value)
    }
    return out
}

/** His answer to a question, kept a day for [ answer there too ] on a much-alike question elsewhere. */
data class GivenAnswer(val member: QuestionMember, val answers: JSONObject, val at: Long)

/** The answers he gave lately, newest first; this process only. */
object GivenAnswers {
    private const val DAY = 24 * 60 * 60 * 1000L
    private val list = mutableListOf<GivenAnswer>()
    @Synchronized fun add(given: GivenAnswer) { list.removeAll { it.member.key == given.member.key }; list.add(0, given); while (list.size > 20) list.removeAt(list.size - 1) }
    @Synchronized fun recent(now: Long = System.currentTimeMillis()): List<GivenAnswer> = list.filter { now - it.at < DAY }
    /** The newest answer he gave elsewhere to much the same question as [member]. */
    fun alike(member: QuestionMember, now: Long = System.currentTimeMillis()): GivenAnswer? =
        recent(now).firstOrNull { it.member.key != member.key && it.member.thread != member.thread && similarQuestions(it.member.input, member.input) }
    @Synchronized fun clear() = list.clear()
}
