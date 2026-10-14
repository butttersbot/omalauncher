package dev.omalauncher.assist

import org.json.JSONArray
import org.json.JSONObject

/*
 * What a thread on the Mac says, as the phone reads it: the runner's events, the server-sent-events lines they
 * arrive in, and the rule that keeps an event from being shown twice after a dropped link. Plain Kotlin with no
 * network in it, so all of it is unit tested; MacRunner.kt holds the requests and the thin transport.
 * The contract is "As built on the Mac" in docs/MAC-RUNNER-SPEC.md.
 */

/** A thread's state on the Mac. [Unknown] is a word this phone does not know yet, which is not an error. */
enum class MacThreadStatus(val word: String) {
    Idle("idle"), Working("working"), Waiting("waiting"), Interrupted("interrupted"), Unknown("");

    companion object {
        fun of(word: String): MacThreadStatus = entries.firstOrNull { it.word == word } ?: Unknown
    }
}

/** How an approval request ended. [Withdrawn] is the agent taking its request back; nobody answered it. */
enum class MacDecision(val word: String) {
    Allow("allow"), Deny("deny"), Withdrawn("withdrawn"), Unknown("");

    companion object {
        fun of(word: String): MacDecision = entries.firstOrNull { it.word == word } ?: Unknown
    }
}

/**
 * One request for approval waiting on the Mac. [input] is the tool's input as JSON text, so two of these compare
 * by what they say. Only an answer carrying [nonce] is taken, so it is left out of [toString] with the input.
 */
data class MacApproval(val approval: String, val nonce: String, val tool: String, val input: String, val explanation: String) {
    override fun toString() = "MacApproval(approval=$approval, tool=$tool)"
}

/** One event of a thread. Ids count from 1 per thread with no gaps; [time] is Unix seconds. */
sealed interface MacEvent {
    val id: Long
    val time: Long

    data class Status(override val id: Long, override val time: Long, val status: MacThreadStatus) : MacEvent

    /** The owner's words, at the moment the thread took them. */
    data class Message(override val id: Long, override val time: Long, val text: String) : MacEvent

    /** Words of the agent. The first of a turn names the agent's [kind] and the [model] answering; the others leave them empty. */
    data class Text(override val id: Long, override val time: Long, val text: String, val kind: String = "", val model: String = "") : MacEvent

    /** A tool call as one line, written as [ClaudeProtocol.step] writes it. */
    data class Step(override val id: Long, override val time: Long, val text: String, val tool: String) : MacEvent

    data class Approval(override val id: Long, override val time: Long, val request: MacApproval) : MacEvent

    data class ApprovalResult(override val id: Long, override val time: Long, val approval: String, val decision: MacDecision) : MacEvent

    /** The turn's answer, with the agent's [kind] and the [model] that answered when the runner says them. */
    data class Result(override val id: Long, override val time: Long, val text: String, val failed: Boolean, val kind: String = "", val model: String = "") : MacEvent

    /**
     * The runner's word on what the agent did that is not his answer: background work still running, ended or
     * stopped unfinished, or a turn of the agent's own between his messages. Said quietly, never as the answer.
     */
    data class Note(override val id: Long, override val time: Long, val text: String) : MacEvent

    /** The task was stopped; [dropped] messages that were waiting their turn went with it. */
    data class Stopped(override val id: Long, override val time: Long, val dropped: Int) : MacEvent

    /**
     * [code] is the agent's exit code when it ended without an answer, and null when it never started or the
     * runner gave a word instead. [cause] is that word: [MAC_LIMIT] for an agent at its usage or concurrency
     * limit, "agent" for one that refused, "runner" for the runner failing in the turn; empty for an exit code.
     */
    data class Error(override val id: Long, override val time: Long, val text: String, val code: Int?, val cause: String = "") : MacEvent

    /**
     * An event of a type this phone does not know, or one it knows with a part missing. Nothing is shown for it,
     * but it keeps its place in the count: left out, the event after it would look like a hole in the stream.
     */
    data class Other(override val id: Long, override val time: Long, val type: String) : MacEvent
}

/** Android's own JSONObject answers "null" for a null string, so every read asks whether the value is there first. */
internal fun JSONObject.macText(key: String): String = if (isNull(key)) "" else optString(key)

internal fun JSONObject.macWhole(key: String): Long? = if (isNull(key)) null else (opt(key) as? Number)?.toLong()

internal fun JSONArray?.macObjects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** An approval request from an `approval` event or a thread's `pending` list; null when it could not be answered. */
internal fun macApproval(json: JSONObject): MacApproval? {
    val approval = json.macText("approval")
    val nonce = json.macText("nonce")
    if (approval.isBlank() || nonce.isBlank()) return null
    return MacApproval(approval, nonce, json.macText("tool"), json.optJSONObject("input")?.toString() ?: "{}", json.macText("explanation"))
}

/**
 * One event from its JSON. The runner writes the id and type both in the JSON and on the stream's own `id:` and
 * `event:` lines; the JSON wins and the lines ([lineId], [lineType]) fill in. Null when there is no id to place it by.
 */
internal fun macEvent(json: JSONObject, lineId: Long? = null, lineType: String? = null): MacEvent? {
    val id = json.macWhole("id") ?: lineId ?: return null
    if (id < 1) return null
    val time = json.macWhole("time") ?: 0L
    val type = json.macText("type").ifBlank { lineType.orEmpty() }
    return when (type) {
        "status" -> MacEvent.Status(id, time, MacThreadStatus.of(json.macText("status")))
        "message" -> MacEvent.Message(id, time, json.macText("text"))
        "text" -> MacEvent.Text(id, time, json.macText("text"), json.macText("kind"), json.macText("model"))
        "step" -> MacEvent.Step(id, time, json.macText("text"), json.macText("tool"))
        "approval" -> macApproval(json)?.let { MacEvent.Approval(id, time, it) } ?: MacEvent.Other(id, time, type)
        "approval_result" -> MacEvent.ApprovalResult(id, time, json.macText("approval"), MacDecision.of(json.macText("decision")))
        "result" -> MacEvent.Result(id, time, json.macText("text"), json.optBoolean("failed"), json.macText("kind"), json.macText("model"))
        "note" -> json.macText("text").trim().takeIf { it.isNotEmpty() }?.let { MacEvent.Note(id, time, it) } ?: MacEvent.Other(id, time, type)
        "stopped" -> MacEvent.Stopped(id, time, (json.macWhole("dropped") ?: 0L).toInt())
        // The runner's code is a number when the agent died and a word when it says why the turn ended.
        "error" -> MacEvent.Error(id, time, json.macText("text"), json.macWhole("code")?.toInt(), (json.opt("code") as? String).orEmpty())
        else -> MacEvent.Other(id, time, type)
    }
}

/** One event from the text of a stream's `data:`; null for anything that is not an event, which is ignored. */
fun parseMacEvent(data: String, lineId: String? = null, lineType: String? = null): MacEvent? {
    val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
    return macEvent(json, lineId?.trim()?.toLongOrNull(), lineType?.trim())
}

/** One event as server-sent events carry it: what its `id:`, `event:` and `data:` lines said. */
data class SseEvent(val id: String?, val type: String?, val data: String)

/**
 * Reads a server-sent-events stream a line at a time. A blank line ends an event; a line that starts with a colon
 * is a comment (the runner's keep-alive every fifteen seconds) and says nothing; `data:` on several lines is one
 * value with a line break between the parts. A blank line with no data before it ends nothing.
 */
class SseReader {
    private var id: String? = null
    private var type: String? = null
    private val data = ArrayList<String>()

    fun feed(line: String): SseEvent? {
        val text = line.removeSuffix("\r")
        if (text.isEmpty()) {
            val event = if (data.isEmpty()) null else SseEvent(id, type, data.joinToString("\n"))
            id = null
            type = null
            data.clear()
            return event
        }
        if (text.startsWith(":")) return null
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        when (field) {
            "id" -> id = value
            "event" -> type = value
            "data" -> data.add(value)
        }
        return null
    }
}

/** What to do with an event, given the last one shown. */
enum class MacOffer {
    /** The next one: show it. */
    Show,

    /** Seen before (the link dropped and the stream was asked again from too far back): drop it. */
    Repeat,

    /** Events between the last one shown and this one are missing: show nothing, and ask for them. */
    Gap,
}

fun macOffer(last: Long, id: Long): MacOffer = when {
    id <= last -> MacOffer.Repeat
    id == last + 1 -> MacOffer.Show
    else -> MacOffer.Gap
}

/**
 * The last event shown in each thread, which is where the stream is asked from after a dropped link
 * (`?after=N`). [known] is what was kept from before; [snapshot] is what to keep.
 */
class MacCursors(known: Map<String, Long> = emptyMap()) {
    private val last = HashMap(known)

    @Synchronized fun after(threadId: String): Long = last[threadId] ?: 0L

    /** For an event off the stream. Only [MacOffer.Show] moves the thread on. */
    @Synchronized fun offer(threadId: String, event: MacEvent): MacOffer {
        val offer = macOffer(after(threadId), event.id)
        if (offer == MacOffer.Show) last[threadId] = event.id
        return offer
    }

    /**
     * For the answer to `events?after=N`, asked after a [MacOffer.Gap]: the events not shown yet, in order. A hole
     * in this list is passed over, since it is the runner's whole record and asking again would bring the same.
     */
    @Synchronized fun catchUp(threadId: String, events: List<MacEvent>): List<MacEvent> {
        val from = after(threadId)
        val fresh = events.filter { it.id > from }.distinctBy { it.id }.sortedBy { it.id }
        if (fresh.isNotEmpty()) last[threadId] = fresh.last().id
        return fresh
    }

    /**
     * The Mac's record of a thread was cleared and its ids count from 1 again: the thread starts over here too,
     * or every event of the new record would look like one already shown.
     */
    @Synchronized fun forget(threadId: String) {
        last.remove(threadId)
    }

    @Synchronized fun snapshot(): Map<String, Long> = HashMap(last)
}

/** Why a stream was left. Whichever it is, the task on the Mac goes on and is not started again. */
enum class MacStreamWhy {
    /** The caller said to stop. */
    Stopped,

    /** The link ended or went silent: ask again from [MacStreamEnd.last] when the Mac answers. */
    Dropped,

    /** An event arrived out of turn: get `events?after=last`, then follow again. */
    Gap,

    /** The runner answered something other than a stream; [MacStreamEnd.code] is its HTTP status. */
    Refused,
}

/** [last] is the id of the last event handed on, or where the stream was asked from if there was none. */
data class MacStreamEnd(val last: Long, val why: MacStreamWhy, val code: Int = 0)

/**
 * Follows a stream's lines from event [after], handing each new event to [onEvent] once and in order.
 * [readLine] gives null, or fails, when the link is gone. [shouldStop] is asked before each line, so it is
 * heard at the latest when the next keep-alive comes.
 */
fun followMacStream(after: Long, readLine: () -> String?, shouldStop: () -> Boolean, onEvent: (MacEvent) -> Unit): MacStreamEnd {
    val reader = SseReader()
    var last = after
    while (!shouldStop()) {
        val line = runCatching { readLine() }.getOrNull() ?: return MacStreamEnd(last, MacStreamWhy.Dropped)
        val sse = reader.feed(line) ?: continue
        val event = parseMacEvent(sse.data, sse.id, sse.type) ?: continue
        when (macOffer(last, event.id)) {
            MacOffer.Show -> {
                last = event.id
                onEvent(event)
            }
            MacOffer.Repeat -> Unit
            MacOffer.Gap -> return MacStreamEnd(last, MacStreamWhy.Gap)
        }
    }
    return MacStreamEnd(last, MacStreamWhy.Stopped)
}
