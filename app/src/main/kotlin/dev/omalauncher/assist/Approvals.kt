package dev.omalauncher.assist

import java.util.UUID

/*
 * Which approval a tap answers, and which notification belongs to it. Plain Kotlin, so the
 * rules are unit tested; AgentNotices.kt is the thin Android layer over them.
 */

/** What a tap on an approval asks for. [word] is written into notification intents: never rename. */
enum class ApprovalVerb(val word: String) { Approve("approve"), Deny("deny"), Review("review") }

/** Limits and ids for approvals. Approval ids come from the agent's stream, so they are untrusted text. */
object ApprovalIds {
    const val MAX_LENGTH = 128

    /** More unanswered approvals than this are denied instead of shown, so an agent can't flood the shade. */
    const val MAX_LIVE = 16

    /** Approval notifications use ids from here on, apart from the task notification (41) and the phone control one (40). */
    const val FIRST_NOTIFICATION = 4100
    const val TAG_PREFIX = "oma-approval:"

    /** Whether an agent's id may go into an intent or a notification tag: short, and only letters, digits and `-_.:`. */
    fun safe(id: String): Boolean = id.length in 1..MAX_LENGTH && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_.:" }

    fun validSlot(slot: Int) = slot in 0 until MAX_LIVE

    fun notificationId(slot: Int): Int {
        require(validSlot(slot)) { "No such approval slot: $slot" }
        return FIRST_NOTIFICATION + slot
    }

    /** One request code per slot and verb, so no two buttons of live approvals share a PendingIntent. */
    fun requestCode(slot: Int, verb: ApprovalVerb): Int {
        require(validSlot(slot)) { "No such approval slot: $slot" }
        return FIRST_NOTIFICATION + slot * ApprovalVerb.entries.size + verb.ordinal
    }

    fun tag(id: String) = TAG_PREFIX + id
}

/**
 * An approval the owner hasn't answered. [nonce] is made on the phone for this one request, so a button
 * drawn for it can't answer a later request the agent sends under the same id; [slot] is free among the
 * live approvals and gives the notification id and request codes.
 */
data class PendingApproval(val approval: AgentApproval, val nonce: String, val slot: Int) {
    val id: String get() = approval.id
    val notificationId: Int get() = ApprovalIds.notificationId(slot)
    fun ticket(verb: ApprovalVerb) = ApprovalTicket(verb, slot, nonce, id)
}

/** What an approval button carries, as the data URI of its intent: `oma-approval:<verb>/<slot>/<nonce>/<id>`. */
data class ApprovalTicket(val verb: ApprovalVerb, val slot: Int, val nonce: String, val id: String) {
    fun encode() = "$SCHEME:${verb.word}/$slot/$nonce/$id"

    companion object {
        const val SCHEME = "oma-approval"
        private val NONCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Null for anything this app wouldn't have written itself. */
        fun parse(text: String?): ApprovalTicket? {
            val parts = text?.takeIf { it.startsWith("$SCHEME:") && it.length < 400 }?.removePrefix("$SCHEME:")?.split('/') ?: return null
            if (parts.size != 4) return null
            val verb = ApprovalVerb.entries.firstOrNull { it.word == parts[0] } ?: return null
            val slot = parts[1].takeIf { it.length in 1..2 && it.all(Char::isDigit) }?.toInt()?.takeIf(ApprovalIds::validSlot) ?: return null
            if (!NONCE.matches(parts[2]) || !ApprovalIds.safe(parts[3])) return null
            return ApprovalTicket(verb, slot, parts[2], parts[3])
        }
    }
}

/** What became of a request the agent sent. */
sealed interface Admission {
    /** Waiting for the owner. */
    data class Shown(val entry: PendingApproval) : Admission

    /** The same id is already waiting; the one on screen stays as it is. */
    data object Duplicate : Admission

    /** An id that can't be shown safely, or too many waiting: denied without asking. */
    data object Refused : Admission
}

/** What a tap on an approval came to. */
sealed interface Claim {
    /** The approval is the caller's to answer, and no longer waiting. */
    data class Granted(val entry: PendingApproval) : Claim

    /** Answered, withdrawn, stopped, or from before the app restarted. */
    data object Expired : Claim

    /** The phone is locked; nothing changed. */
    data object Locked : Claim
}

enum class ApprovalOutcome { Decided, Expired, Locked }

/** The approvals waiting for an answer. Not thread safe: AgentTasks guards it. */
class ApprovalLedger(private val newNonce: () -> String = { UUID.randomUUID().toString() }) {
    private val entries = LinkedHashMap<String, PendingApproval>()

    /** Oldest first. */
    val live: List<PendingApproval> get() = entries.values.toList()

    fun admit(approval: AgentApproval): Admission {
        if (!ApprovalIds.safe(approval.id)) return Admission.Refused
        if (approval.id in entries) return Admission.Duplicate
        val taken = entries.values.map { it.slot }.toSet()
        val slot = (0 until ApprovalIds.MAX_LIVE).firstOrNull { it !in taken } ?: return Admission.Refused
        return Admission.Shown(PendingApproval(approval, newNonce(), slot).also { entries[approval.id] = it })
    }

    /**
     * The one way an approval gets answered, whatever was tapped. A locked phone answers nothing; otherwise only
     * the approval with this [id] and [nonce] is taken, and only while its task is still [active].
     */
    fun claim(id: String, nonce: String, locked: Boolean, active: Boolean): Claim {
        if (locked) return Claim.Locked
        if (!active) return Claim.Expired
        val entry = entries[id]?.takeIf { it.nonce == nonce } ?: return Claim.Expired
        entries.remove(id)
        return Claim.Granted(entry)
    }

    /** The agent took a request back. */
    fun withdraw(id: String): PendingApproval? = entries.remove(id)

    /** The task stopped or ended: nothing can be answered any more. */
    fun clear(): List<PendingApproval> = live.also { entries.clear() }
}

enum class ApprovalSurface { Notification, Screen }

/** Where a new approval shows: a notification, or the approval screen when the app or its channel is blocked. */
fun approvalSurface(notificationsEnabled: Boolean, channelBlocked: Boolean) =
    if (notificationsEnabled && !channelBlocked) ApprovalSurface.Notification else ApprovalSurface.Screen

/** A notification button can only demand an unlock from Android 12; before that it opens the approval screen instead. */
fun actionDecidesDirectly(sdk: Int) = sdk >= 31

/** What the task notification shows; [expanded] is the longer text of a finished task. */
data class TaskNotice(val title: String, val text: String, val ongoing: Boolean, val expanded: String? = null)

/**
 * The task notification for a state: the current step while working, the result once done,
 * and nothing (null) when there is neither, such as after a new thread.
 */
fun taskNotice(working: Boolean, label: String, answer: String, pending: Int): TaskNotice? = when {
    working -> TaskNotice(
        label,
        when {
            pending == 1 -> "1 request is waiting for your answer"
            pending > 1 -> "$pending requests are waiting for your answer"
            else -> answer.trim().lines().lastOrNull { it.isNotBlank() }?.take(180) ?: "Request received"
        },
        ongoing = true,
    )
    answer.isNotBlank() -> TaskNotice(label, answer.trim().take(180), ongoing = false, expanded = answer.trim().take(1500))
    else -> null
}
