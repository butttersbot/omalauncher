package dev.omalauncher.oma

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
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
internal val LocalQuestionDraftStore = staticCompositionLocalOf<WaitingQuestionDrafts?> { null }
internal val LocalQuestionOpen = staticCompositionLocalOf<((WaitingQuestion) -> Unit)?> { null }

internal fun questionInput(tool: String, input: String): Boolean =
    runCatching { dev.omalauncher.assist.questionWaits(tool, JSONObject(input)) }.getOrDefault(false)

/** Owned above both panel branches: moving a form never replaces its draft or in-flight gate. */
internal class WaitingQuestionDraft(initialScroll: Int = 0) {
    val scroll = ScrollState(initialScroll)
    var answers by mutableStateOf(mapOf<String, List<String>>())
    var others by mutableStateOf(mapOf<String, String>())
    var sending by mutableStateOf(false)
    var sent by mutableStateOf(false)
    var skipped by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun json(): JSONObject = JSONObject().put("answers", JSONObject().apply {
        answers.forEach { (key, values) -> put(key, JSONArray(values)) }
    }).put("others", JSONObject(others)).put("sending", sending).put("sent", sent).put("skipped", skipped).put("scroll", scroll.value)

    companion object {
        fun restore(json: JSONObject) = WaitingQuestionDraft(json.optInt("scroll").coerceAtLeast(0)).apply {
            val picks = json.optJSONObject("answers") ?: JSONObject()
            answers = picks.keys().asSequence().associateWith { key ->
                val values = picks.getJSONArray(key)
                (0 until values.length()).map { values.getString(it) }
            }
            val text = json.optJSONObject("others") ?: JSONObject()
            others = text.keys().asSequence().associateWith { text.getString(it) }
            sending = json.optBoolean("sending")
            sent = json.optBoolean("sent")
            skipped = json.optBoolean("skipped")
        }
    }
}

internal class WaitingQuestionDrafts {
    // Observable, so a form opened after the last save is saved too (see ChatArtifactWorkspace).
    private val entries = mutableStateMapOf<String, WaitingQuestionDraft>()
    fun get(key: String): WaitingQuestionDraft = entries.getOrPut(key) { WaitingQuestionDraft() }
    fun json(): String = JSONObject().apply { entries.forEach { (key, draft) -> put(key, draft.json()) } }.toString()

    /**
     * What is kept on disk: only forms not yet answered or skipped, with something picked or typed, and no "sending"
     * (after a restart nothing is in flight). The newest 20 at most.
     */
    fun unsentJson(): String = JSONObject().apply {
        entries.entries.filter { (_, d) -> !d.sent && (d.answers.isNotEmpty() || d.others.values.any { it.isNotBlank() }) }.takeLast(20)
            .forEach { (key, draft) -> put(key, draft.json().put("sending", false)) }
    }.toString()

    companion object {
        fun restore(text: String) = WaitingQuestionDrafts().apply {
            val json = JSONObject(text)
            json.keys().forEach { key -> entries[key] = WaitingQuestionDraft.restore(json.getJSONObject(key)) }
        }
        val saver = Saver<WaitingQuestionDrafts, String>(save = { it.json() }, restore = { restore(it) })
    }
}

/** Every question form waiting for him that this device can answer: the Macs' threads' and the phone builders'. */
internal fun ChatHost.pendingQuestions(): List<dev.omalauncher.assist.QuestionMember> =
    macPending().filter { questionInput(it.request.tool, it.request.input) }.mapNotNull { p ->
        runCatching { dev.omalauncher.assist.QuestionMember(p.ticket, p.threadName, p.threadId, JSONObject(p.request.input)) }.getOrNull()
    } + builders().flatMap { (builder, state) ->
        state.pending.filter { questionInput(it.approval.tool, it.approval.input.toString()) }
            .map { dev.omalauncher.assist.QuestionMember(it.nonce, "Phone ${builder.letter}", "phone:${builder.letter}", it.approval.input) }
    }

/**
 * Re-resolve a saved form's nonce rather than retaining an Activity or an old host callback. Once his own answer is
 * delivered, the forms linked to this one (dev.omalauncher.assist.linkedQuestions) get it too, as his, "answered in"
 * this one's thread; a skip, or an answer his finger didn't send, goes nowhere else.
 */
internal fun ChatHost.sendWaitingAnswers(key: String, answers: JSONObject, done: (String?) -> Unit) {
    // Whether his finger sent these is decided now, as he sends: a Mac's answer may come back seconds later.
    val vouched = BossTouch.mayVouch()
    val waiting = runCatching { pendingQuestions() }.getOrDefault(emptyList())
    val me = waiting.firstOrNull { it.key == key }
    deliverWaitingAnswers(key, answers, vouched, null) { failure ->
        done(failure)
        if (failure != null || me == null || !vouched || dev.omalauncher.assist.questionAnswersSkipped(answers)) return@deliverWaitingAnswers
        dev.omalauncher.assist.GivenAnswers.add(dev.omalauncher.assist.GivenAnswer(me, answers, System.currentTimeMillis()))
        val words = givenWords(answers)
        // Linked by the agents (a link key): no model needed.
        val linked = dev.omalauncher.assist.linkedQuestions(me, waiting)
        for (other in linked) {
            val mapped = dev.omalauncher.assist.mapQuestionAnswers(me.input, answers, other.input, me.where) ?: continue
            carryAnswer(other, mapped, me.where, words, vouched)
        }
        // By meaning (Boss, 2026-10-29): every other question waiting for him elsewhere, phone builders' and every
        // computer's, goes in ONE call to the Mac Pro (POST /questions/match with them as candidates; its model decides).
        // Only when the Mac Pro can't be reached, is too slow or doesn't answer does Soma's own fast-model check decide,
        // with the same rules. Either way Soma alone delivers: a clear same decision with a valid pick of that card's own
        // option is answered as his, once; anything less is offered as the one-tap hint.
        val rest = waiting.filter { o -> o.key != me.key && o.thread != me.thread && linked.none { it.key == o.key } && !CarriedAnswers.has(o.key) }
        if (rest.isEmpty()) return@deliverWaitingAnswers
        kotlin.concurrent.thread(name = "soma-same-question", isDaemon = true) {
            val candidates = JSONArray(rest.map { JSONObject().put("target_id", it.key).put("thread_name", it.where).put("card", it.input) })
            val matches = runCatching { matchOnMacPro(me.input, answers, me.where, candidates) }.getOrNull()
            if (matches != null) {
                for (i in 0 until matches.length()) {
                    val m = matches.optJSONObject(i) ?: continue
                    val target = rest.firstOrNull { it.key == m.optString("target_id") } ?: continue
                    // The Mac Pro's picks are checked against the card here too: its own labels, or text only where allowed.
                    val picks = m.optJSONObject("answers")?.takeIf { validPicks(target.input, it) }
                    if (m.optBoolean("deliver") && picks != null) carryAnswer(target, picks, me.where, words, vouched)
                    else SameSuggestions.offer(target.key, me.where, words, picks)
                }
                return@thread
            }
            val said = runCatching { askSameQuestion(dev.omalauncher.assist.sameQuestionPrompt(me, answers, rest)) }.getOrNull() ?: return@thread
            for (verdict in dev.omalauncher.assist.sameQuestionVerdicts(said, rest, answers, me.where)) {
                val target = rest.firstOrNull { it.key == verdict.key } ?: continue
                if (verdict.sure && verdict.picks != null) carryAnswer(target, verdict.picks, me.where, words, vouched)
                else if (verdict.same) SameSuggestions.offer(target.key, me.where, words, verdict.picks)
            }
        }
    }
}

/** [answers] keyed by [input]'s own question ids, each one of its labels (all, for multiSelect), or text where text is allowed. */
internal fun validPicks(input: JSONObject, answers: JSONObject): Boolean = runCatching {
    val qs = input.getJSONArray("questions")
    val codex = input.optString("codex_request") == "questions"
    (0 until qs.length()).all { i ->
        val q = qs.getJSONObject(i)
        val labels = q.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.getJSONObject(it).optString("label") } }.orEmpty()
        when (val v = answers.opt(if (codex) q.optString("id") else q.optString("question"))) {
            is JSONArray -> q.optBoolean("multiSelect") && v.length() > 0 && (0 until v.length()).all { v.optString(it) in labels }
            is String -> v.isNotBlank() && (v in labels || q.opt("isOtherAllowed") != false)
            else -> false
        }
    } && answers.length() == qs.length()
}.getOrDefault(false)

private fun givenWords(answers: JSONObject): String = answers.keys().asSequence().joinToString(" · ") { k ->
    answers.opt(k).let { v -> if (v is JSONArray) (0 until v.length()).joinToString(", ") { v.optString(it) } else v.toString() }
}

/** His answer carried to [target] (once only), signed as his, "answered in [from]: [words]"; kept for [ undo ]. */
private fun ChatHost.carryAnswer(target: dev.omalauncher.assist.QuestionMember, answers: JSONObject, from: String, words: String, vouched: Boolean) {
    if (!CarriedAnswers.claim(target.key)) return
    deliverWaitingAnswers(target.key, answers, vouched, "$from: $words") { failure ->
        if (failure == null) CarriedAnswers.add(CarriedAnswer(target, macPendingApproval(target.key), from, words, System.currentTimeMillis()))
        else CarriedAnswers.release(target.key)
    }
}

private fun ChatHost.macPendingApproval(ticket: String): String? = macPending().firstOrNull { it.ticket == ticket }?.request?.approval

/** An answer Soma carried over for him: shown in the thread it went to, with [ undo ]. */
data class CarriedAnswer(val target: dev.omalauncher.assist.QuestionMember, val approval: String?, val from: String, val words: String, val at: Long)

/** The answers carried this run, newest last; each target answered so at most once. */
object CarriedAnswers {
    val list = androidx.compose.runtime.mutableStateListOf<CarriedAnswer>()
    private val claimed = mutableSetOf<String>()
    @Synchronized fun claim(key: String) = claimed.add(key)
    @Synchronized fun release(key: String) { claimed.remove(key) }
    @Synchronized fun has(key: String) = key in claimed
    fun add(carried: CarriedAnswer) { list.add(carried); while (list.size > 20) list.removeAt(0) }
    fun clear() { list.clear(); synchronized(this) { claimed.clear() } }
}

/** The questions the model thought were his, but not surely or not with a clear pick: offered, never sent. */
object SameSuggestions {
    data class Offer(val from: String, val words: String, val picks: JSONObject?)
    private val offers = mutableMapOf<String, Offer>()
    @Synchronized fun offer(key: String, from: String, words: String, picks: JSONObject?) { offers[key] = Offer(from, words, picks) }
    @Synchronized fun get(key: String): Offer? = offers[key]
    @Synchronized fun clear() = offers.clear()
}

/**
 * [ undo ] on an answer Soma carried over: that thread is told, in his words, that it wasn't his answer there and to ask
 * again. A follow-up to the thread, as if he had typed it there (his tap, his receipt); the answer itself has gone.
 */
internal fun ChatHost.undoCarried(carried: CarriedAnswer) {
    val text = "Undo: the answer \"${carried.words}\" to your question was carried over automatically from ${carried.from}, where I " +
        "answered what looked like the same question. It is not my answer here: ask me again if you still need it."
    val vouched = BossTouch.mayVouch()
    val thread = carried.target.thread
    if (thread.startsWith("phone:")) {
        val builder = dev.omalauncher.assist.BuilderId.entries.firstOrNull { it.letter == thread.removePrefix("phone:") } ?: return
        build(text, emptyList(), BuildWay.FollowUp, builder)
        bossSaid(BossWords.chat("phone", text, vouched))
    } else {
        sayToMac(text, thread, carried.target.where, "")
        bossSaid(BossWords.chat("mac:$thread", text, vouched))
    }
    CarriedAnswers.list.remove(carried)
}

/** What a waiting form shows of the same question elsewhere: waiting there too, or answered there already. */
internal data class QuestionHint(val pendingIn: List<String>, val answeredIn: String?, val answeredWith: String = "", val answerThereToo: (() -> Unit)? = null)
internal val LocalQuestionHint = staticCompositionLocalOf<((String) -> QuestionHint?)?> { null }

internal fun ChatHost.questionHint(key: String): QuestionHint? {
    val waiting = runCatching { pendingQuestions() }.getOrDefault(emptyList())
    val me = waiting.firstOrNull { it.key == key } ?: return null
    val linked = dev.omalauncher.assist.linkedQuestions(me, waiting).map { it.key }.toSet()
    val alike = dev.omalauncher.assist.similarElsewhere(me, waiting).filter { it.key !in linked }.map { it.where }.distinct()
    // The model's offer (same decision, not sure enough to send) before the word-alike one.
    val offer = SameSuggestions.get(key)
    val given = if (offer != null) null else dev.omalauncher.assist.GivenAnswers.alike(me)
    val mapped = offer?.picks ?: given?.let { dev.omalauncher.assist.mapQuestionAnswers(it.member.input, it.answers, me.input, it.member.where) }
    val answeredWhere = offer?.from ?: given?.member?.where.orEmpty()
    if (offer != null) return QuestionHint(alike, answeredWhere, offer.words,
        mapped?.let { answers -> { deliverWaitingAnswers(key, answers, BossTouch.mayVouch(), "$answeredWhere: ${offer.words}") {} } })
    if (alike.isEmpty() && mapped == null && linked.isEmpty()) return null
    return QuestionHint(
        pendingIn = (alike + waiting.filter { it.key in linked }.map { it.where + " (linked)" }).distinct(),
        answeredIn = answeredWhere.takeIf { mapped != null },
        answeredWith = mapped?.let { m -> m.keys().asSequence().joinToString(" · ") { k -> m.opt(k).let { v -> if (v is JSONArray) (0 until v.length()).joinToString(", ") { v.optString(it) } else v.toString() } } }.orEmpty(),
        // His tap here answers this one with what he answered there: his finger, so his receipt.
        answerThereToo = mapped?.let { answers -> { deliverWaitingAnswers(key, answers, BossTouch.mayVouch(), answeredWhere) {} } },
    )
}

private fun ChatHost.deliverWaitingAnswers(key: String, answers: JSONObject, vouched: Boolean, answeredIn: String?, done: (String?) -> Unit) {
    // His answers, once delivered, are signed as his (BossReceipt): a skip is no answer of his and isn't.
    // What he was asked, as the card showed it: each question's text, one per line.
    fun asked(input: String) = runCatching {
        val qs = JSONObject(input).getJSONArray("questions")
        (0 until qs.length()).joinToString("\n") { qs.getJSONObject(it).optString("question") }
    }.getOrDefault("") + (answeredIn?.let { "\n(answered in $it)" } ?: "")
    // The card as he saw it (its questions, headers and options), which is what his answer replies to.
    fun card(input: String) = runCatching { CanvasReceipt.canonical(JSONObject(input)) }.getOrDefault("")
    fun said(conversation: String, question: String, input: String) {
        if (!dev.omalauncher.assist.questionAnswersSkipped(answers)) bossSaid(BossWords.answered(conversation, key, question, answers, vouched, card(input)))
    }
    macPending().firstOrNull { it.ticket == key }?.let { entry ->
        answerMacQuestions(entry.ticket, answers) {
            val notice = dev.omalauncher.assist.macApprovalNotice(it)
            if (notice == null) said("mac:${entry.threadId}", asked(entry.request.input), entry.request.input)
            done(notice)
        }
        return
    }
    builders().values.flatMap { it.pending }.firstOrNull { it.nonce == key }?.let { entry ->
        val outcome = runCatching { answerBuilderQuestions(entry, answers) }.getOrElse {
            done("These answers could not be sent. Review every question and try again."); return
        }
        if (outcome == dev.omalauncher.assist.ApprovalOutcome.Decided) said("phone", asked(entry.approval.input.toString()), entry.approval.input.toString())
        done(when (outcome) {
            dev.omalauncher.assist.ApprovalOutcome.Decided -> null
            dev.omalauncher.assist.ApprovalOutcome.Locked -> "Unlock your phone to answer"
            dev.omalauncher.assist.ApprovalOutcome.Expired -> "That request has expired"
        })
        return
    }
    done("That request has expired")
}

/** A remote answer/withdrawal closes an unanswered viewer; own normal Send may show its receipt. */
internal fun WaitingQuestionDraft.closeAfterWithdrawal(stillWaiting: Boolean) =
    !stillWaiting && !sending && (!sent || skipped)

/** Shared by the card and form: one in-flight gate, including rotation and double taps. */
internal fun WaitingQuestionDraft.send(request: WaitingQuestion, payload: JSONObject, skip: Boolean = false, done: () -> Unit = {}) {
    if (sending || sent) return
    sending = true; error = null
    try {
        request.send(payload) { failure ->
            sending = false; error = failure; sent = failure == null
            skipped = sent && skip
            if (sent) done()
        }
    } catch (failure: Exception) {
        sending = false; error = failure.message ?: "These answers could not be sent. Try again."
    }
}

internal fun WaitingQuestionDraft.skip(request: WaitingQuestion, done: () -> Unit = {}) {
    val payload = runCatching { dev.omalauncher.assist.questionSkipAnswers(request.input) }.getOrElse {
        error = "This question form could not be skipped: ${it.message}"; return
    }
    send(request, payload, skip = true, done = done)
}

/** Entirely native: the visible answers and the Send action share the same state. */
@OptIn(ExperimentalLayoutApi::class)
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
    fun sendCompleteAnswers() {
        if (sending || sent || !keys.all { others[it]?.isNotBlank() == true || !answers[it].isNullOrEmpty() }) return
        val payload = JSONObject()
        keys.forEachIndexed { i, key ->
            val other = others[key]?.takeIf { it.isNotBlank() }
            payload.put(key, other ?: if (questions.getJSONObject(i).optBoolean("multiSelect")) JSONArray(answers[key]) else answers.getValue(key).single())
        }
        draft.send(request, payload)
    }
    val summary = keys.mapIndexedNotNull { i, key ->
        val value = others[key]?.takeIf { it.isNotBlank() } ?: answers[key]?.joinToString(", ")
        value?.let { "${questions.getJSONObject(i).optString("header").ifBlank { "${i + 1}" }}: $it" }
    }.joinToString(" · ")
    BoxWithConstraints(Modifier.fillMaxSize().background(Pack.background)) {
    val shortPanel = maxHeight < 480.dp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            BasicText("Questions · ${questions.length()}", Modifier.weight(1f), style = Pack.mono(Pack.size.small, Pack.muted))
            BracketAction("back to chat", Pack.accent, onClick = close)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(draft.scroll).padding(16.dp)) {
            BasicText(request.title, style = Pack.body(color = Pack.accent))
            BasicText(if (draft.skipped) "Skipped" else if (sent) "Answers sent" else "Before I continue", Modifier.padding(vertical = 12.dp), style = Pack.display(Pack.size.title, Pack.accent))
            if (sent) BasicText(summary, style = Pack.body()) else for (i in keys.indices) {
                val q = questions.getJSONObject(i); val key = keys[i]
                BasicText("${i + 1} · ${q.optString("header")}", style = Pack.mono(Pack.size.small, Pack.muted))
                BasicText(q.getString("question"), Modifier.padding(vertical = 10.dp), style = Pack.body())
                val options = q.optJSONArray("options") ?: JSONArray()
                val sendsOnPick = !q.optBoolean("multiSelect") && !ready && keys.filter { it != key }.all { others[it]?.isNotBlank() == true || !answers[it].isNullOrEmpty() }
                if (sendsOnPick) BasicText("Choose your final answer to send all choices.", style = Pack.mono(Pack.size.small, Pack.accent))
                for (j in 0 until options.length()) {
                    val option = options.getJSONObject(j); val label = option.getString("label")
                    val selected = label in (answers[key] ?: emptyList()) && others[key].isNullOrBlank()
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .framed(Pack.mark, if (selected) Pack.accent else Pack.muted, Pack.surface, radius = 8.dp)
                        .clickable(enabled = !sending) {
                            error = null
                            others = others - key
                            val previous = answers[key] ?: emptyList()
                            answers = answers + (key to if (q.optBoolean("multiSelect")) {
                                if (label in previous) previous - label else previous + label
                            } else listOf(label))
                            if (sendsOnPick) sendCompleteAnswers()
                        }.padding(12.dp)) {
                        BasicText("${if (q.optBoolean("multiSelect")) { if (selected) "☑" else "☐" } else { if (selected) "●" else "○" }} $label", style = Pack.body())
                        if (sendsOnPick) BasicText("Choose and send answers", style = Pack.mono(Pack.size.small, Pack.accent))
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
                        error = null; others = others + (key to value.take(4096)); answers = answers - key
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
        Column(Modifier.fillMaxWidth().background(Pack.surface).padding(12.dp)) {
            BasicText("To: ${request.title}", style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!ready && !sent) BasicText("${keys.count { others[it].isNullOrBlank() && answers[it].isNullOrEmpty() }} of ${keys.size} questions left. Answer every question to send, or skip.", style = Pack.mono(Pack.size.small, Pack.muted), maxLines = if (shortPanel) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank() && !sent) BasicText(summary, style = Pack.mono(Pack.size.small, Pack.muted), maxLines = if (shortPanel) 1 else 3, overflow = TextOverflow.Ellipsis)
            error?.let { BasicText(it, style = Pack.body(Pack.size.small, Pack.red), maxLines = if (shortPanel) 1 else 2, overflow = TextOverflow.Ellipsis) }
            if (!sent) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BracketAction("skip", if (sending) Pack.muted else Pack.accent) { draft.skip(request, close) }
                BracketAction(if (sending) "Sending…" else "Send answers", if (ready) Pack.accent else Pack.muted) {
                    if (ready) sendCompleteAnswers()
                    else error = "Answer every remaining question first. Nothing has been sent."
                }
            }
        }
    }
}
}
