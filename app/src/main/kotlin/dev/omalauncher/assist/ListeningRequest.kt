package dev.omalauncher.assist

/** What the listening screen's host does after a tap: the part that needs the microphone, the agent or the window. */
sealed interface ListeningEffect {
    /** Open the microphone for the request as it now stands. */
    data object Listen : ListeningEffect

    /** Hand [text] to the agent. */
    data class Send(val text: String) : ListeningEffect

    /** Close the screen; nothing is sent. */
    data object Leave : ListeningEffect

    /** Open the microphone's setup, then close the screen. */
    data object Allow : ListeningEffect

    /** Stop the running task, then close the screen. */
    data object Stop : ListeningEffect

    /** Open the waiting approval in place of the screen. */
    data object Review : ListeningEffect
}

/** A request after a tap: [next] null is the screen going away, [effect] null a tap that does nothing here. */
data class ListeningStep(val next: ListeningRequest?, val effect: ListeningEffect?)

/**
 * A request to Oma while the listening screen is up, and every decision about it, kept apart from Android so
 * it can be tested: [heard] is what has been heard and kept so far, [detail] what a problem or a running task says.
 * Nothing here sends on its own: only [ListeningAct.Send], tapped while the words wait, gives [ListeningEffect.Send].
 */
data class ListeningRequest(
    val phase: ListeningPhase,
    val heard: String = "",
    val detail: String = "",
) {
    /** The microphone closed with [said], maybe nothing; [error] is why, when it closed on a problem. */
    fun spoke(said: String, error: String? = null): ListeningRequest {
        if (phase != ListeningPhase.Listening) return this
        val all = appendSpeech(heard, said)
        return when {
            // Whatever was heard waits for his go; adding that catches nothing, or fails, keeps the earlier words.
            all.isNotEmpty() -> ListeningRequest(ListeningPhase.Heard, all)
            error != null -> ListeningRequest(ListeningPhase.Failed, detail = error)
            else -> ListeningRequest(ListeningPhase.Missed)
        }
    }

    /** The owner tapped [act]. */
    fun act(act: ListeningAct): ListeningStep =
        when (act) {
            // A tap on the paper is Cancel too: it never sends.
            ListeningAct.Cancel, ListeningAct.Close -> ListeningStep(null, ListeningEffect.Leave)
            ListeningAct.Send -> if (phase == ListeningPhase.Heard && heard.isNotBlank()) ListeningStep(null, ListeningEffect.Send(heard)) else ListeningStep(this, null)
            ListeningAct.Add -> if (phase == ListeningPhase.Heard) ListeningStep(ListeningRequest(ListeningPhase.Listening, heard), ListeningEffect.Listen) else ListeningStep(this, null)
            ListeningAct.Retry -> ListeningStep(ListeningRequest(ListeningPhase.Listening), ListeningEffect.Listen)
            ListeningAct.Allow -> ListeningStep(null, ListeningEffect.Allow)
            ListeningAct.Stop -> ListeningStep(null, ListeningEffect.Stop)
            ListeningAct.Review -> ListeningStep(null, ListeningEffect.Review)
        }

    /** What the screen says: while the microphone is open, [hearing] is written after the words already kept. */
    fun look(hearing: String = "", note: String = ""): ListeningLook =
        listeningLook(phase, if (phase == ListeningPhase.Listening) appendSpeech(heard, hearing) else heard, detail, note)
}
