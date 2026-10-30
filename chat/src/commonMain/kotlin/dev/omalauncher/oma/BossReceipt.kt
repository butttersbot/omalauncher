package dev.omalauncher.oma

import java.util.UUID
import org.json.JSONObject

/*
 * Boss's own words, signed (design/soma-canvas/DESIGN-GATE.md amendment 77d43019; Boss, 2026-10-29: "my approval in
 * chat should absolutely work"). What he types into Soma's chat and how he answers a native question card are signed
 * with the device key of canvas-record-approved.md, so an agent can show Canvas that an approval was his:
 * `soma-canvas approve-from-chat CARD... --receipt R`, with R from `oma receipt last-boss-message`.
 *
 * An agent must never get one for its own text. Three things hold that:
 * - [BossWords] can only be made in this module, and only the composer's send and the question form's send make one
 *   (a source scan, BossReceiptSourceTest, keeps it so): no command, bridge, mail or CLI path reaches the signer.
 * - Soma signs only when a real finger on the touchscreen sent it: the activity records the last touch from a
 *   physical touchscreen ([BossTouch.touched]); injected input (adb `input`, accessibility gestures) comes from a
 *   virtual device or none and does not count.
 * - Nothing is signed within [AGENT_QUIET_MS] of any phone-control action ([BossTouch.agentActed]), so an agent that
 *   types into the chat box and taps Send through Soma's own phone control gets no receipt.
 */

/** What Boss himself sent: made only by the chat's own send paths (see the note above). */
class BossWords internal constructor(
    val kind: String,
    val conversation: String,
    val text: String = "",
    val question: String = "",
    val answers: JSONObject? = null,
    val messageId: String = UUID.randomUUID().toString(),
    /** Whether his finger sent it, decided at the moment of sending ([BossTouch.mayVouch]), not when it is signed. */
    val vouched: Boolean,
    /**
     * What he was answering, as it was in front of him: the agent's last message in that conversation, or the question
     * card itself. Canvas approves only cards named there (taebc3f66, 2026-10-29), so a bare "ok" approves nothing.
     */
    val replyTo: JSONObject? = null,
) {
    internal companion object {
        fun chat(conversation: String, text: String, vouched: Boolean = BossTouch.mayVouch(), replyTo: JSONObject? = null) =
            BossWords("chat-message", conversation, text = text, vouched = vouched, replyTo = replyTo)
        fun answered(conversation: String, key: String, question: String, answers: JSONObject, vouched: Boolean, card: String = "") =
            BossWords("question-answer", conversation, question = question, answers = answers, messageId = key, vouched = vouched,
                replyTo = BossReceipt.replyTo(key, card))

        /** The agent's newest answer among [entries] (a tab's, oldest first), as what his words reply to; null for none. */
        fun lastAnswer(entries: List<ChatTask>): JSONObject? =
            entries.lastOrNull { it.result.isNotBlank() }?.let { BossReceipt.replyTo("task:${it.time}", it.result) }
    }
}

/** When a real finger last sent something here, and when phone control last acted: process-wide, monotonic ms. */
object BossTouch {
    /** A send counts as his only this soon after his finger left the screen. */
    const val TOUCH_WINDOW_MS = 2_000L

    /** And only this long after any phone-control action. */
    const val AGENT_QUIET_MS = 10_000L

    /** Monotonic ms, the one clock all of these are in. */
    fun now(): Long = System.nanoTime() / 1_000_000

    @Volatile var lastRealTouch = Long.MIN_VALUE / 2
        private set
    @Volatile var lastAgentAction = Long.MIN_VALUE / 2
        private set

    /** A touch from a physical touchscreen, finger or stylus, not generated: the activity says which. */
    fun touched(physical: Boolean, now: Long = now()) { if (physical) lastRealTouch = now else agentActed(now) }

    /** Any phone-control command that changes the screen (PhoneService), and any injected or virtual input seen. */
    fun agentActed(now: Long = now()) { lastAgentAction = now }

    /** Whether Soma may vouch, at [now], that what was just sent came from Boss's own hand. */
    fun mayVouch(now: Long = now(), realTouch: Long = lastRealTouch, agentAction: Long = lastAgentAction): Boolean =
        now - realTouch in 0..TOUCH_WINDOW_MS && now - agentAction > AGENT_QUIET_MS
}

object BossReceipt {
    /** {messageId, textSha256, text}: what a receipt says he was answering; null when there is nothing. */
    fun replyTo(messageId: String, text: String): JSONObject? = text.takeIf { it.isNotBlank() }?.let {
        JSONObject().put("messageId", messageId).put("textSha256", CanvasReceipt.sha256(it.toByteArray(Charsets.UTF_8))).put("text", it)
    }

    /** The receipt for [words], to be serialized with [CanvasReceipt.canonical] and signed; null for nothing to vouch for. */
    fun receipt(deviceId: String, at: Long, nonce: String, words: BossWords): JSONObject? {
        if (!words.vouched) return null
        val base = JSONObject().put("v", 1).put("kind", words.kind).put("deviceId", deviceId).put("nonce", nonce)
            .put("messageId", words.messageId).put("at", at).put("conversation", words.conversation)
        words.replyTo?.let { base.put("replyTo", it) }
        return when (words.kind) {
            "chat-message" -> words.text.takeIf { it.isNotBlank() }?.let {
                base.put("text", it).put("textSha256", CanvasReceipt.sha256(it.toByteArray(Charsets.UTF_8)))
            }
            "question-answer" -> words.answers?.takeIf { it.length() > 0 }?.let {
                base.put("question", words.question).put("answers", it)
                    .put("textSha256", CanvasReceipt.sha256(CanvasReceipt.canonical(it).toByteArray(Charsets.UTF_8)))
            }
            else -> null
        }
    }
}
