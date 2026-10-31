package dev.omalauncher.messages.engine

/** How an action through the Mac ended, when it didn't succeed. */
enum class Outcome {
    /** The Mac answered no. A later try is a new action, with a new id: the Mac replays an id's answer. */
    Refused,

    /** No answer came, so it may have gone. A later try keeps the id, and the Mac won't do it twice. */
    NoAnswer,

    /** The Mac can't say whether it went (a crash mid-send, an id already used otherwise): never tried again by itself. */
    NotConfirmed,
}

data class ActionFailure(val why: String, val outcome: Outcome) {
    /** [lead] and why; not confirmed is its own lead, since [lead] usually says it didn't happen. */
    fun told(lead: String) = if (outcome == Outcome.NotConfirmed) "Not confirmed: $why" else "$lead: $why"
}

/** The Mac's answer to a request it refused, from its `{ok:false, error, code}`. */
class MacRefusal(val status: Int, val outcome: Outcome, val theMacs: Boolean, message: String) : IllegalStateException(message) {
    companion object {
        const val CHECK = "check the conversation before trying again."
        const val FULL = "your Mac's list of recent actions is full. Nothing was sent; try again later."

        fun of(status: Int, error: String?, code: String?): MacRefusal {
            val text = error.orEmpty()
            return when {
                text == "request_ledger_full" -> MacRefusal(status, Outcome.Refused, true, FULL)
                // The record is written after the send as well as before, so this one doesn't say nothing went.
                text == "client_id_conflict" || text == "request_state_unavailable" || text.contains("do_not_resend") ->
                    MacRefusal(status, Outcome.NotConfirmed, true, CHECK)
                code == "unauthorized" -> MacRefusal(status, Outcome.Refused, false, "Your Mac didn't take this phone's token.")
                code == "read_only" -> MacRefusal(status, Outcome.Refused, false, "Sending is off on your Mac.")
                else -> MacRefusal(status, Outcome.Refused, false, text.ifEmpty { "Your Mac answered $status." })
            }
        }

        fun outcomeOf(e: Throwable) = (e as? MacRefusal)?.outcome ?: Outcome.NoAnswer
    }
}
